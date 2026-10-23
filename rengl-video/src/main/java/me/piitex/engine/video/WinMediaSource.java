package me.piitex.engine.video;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static java.lang.foreign.ValueLayout.*;
import static me.piitex.engine.video.MediaFoundation.*;

/**
 * Reads a video file through the operating system on Windows: a Media Foundation Source Reader opens the container
 * and decodes the picture and the sound, and this class receives finished BGRA frames and 16-bit PCM. That makes
 * every codec the machine can play, H.264 and HEVC included, available without the engine containing a decoder for
 * them. The file is still read from disk as it plays.
 * <p>
 * The reader hands out samples from whichever stream is ready first, so picture and sound come out in presentation
 * order without this class merging them. A seek repositions the reader, which resumes at the last keyframe.
 */
final class WinMediaSource implements Demuxer {
    private static final Logger log = LogManager.getLogger("Video");
    private static final int VIDEO_STREAM = 0, AUDIO_STREAM = 1;
    private static final double TICKS = 1e7; // Media Foundation counts in 100 ns
    /** A seek starts this far before the target, so the picture for the target time is among the frames. */
    private static final double SEEK_LEAD = 0.5;
    private static final int VT_I8 = 20, VT_UI8 = 21;

    private static final FunctionDescriptor D_SELECTION = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT);
    private static final FunctionDescriptor D_NATIVE = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor D_TYPE = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor D_SET_TYPE = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_POSITION = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_READ = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_FLUSH = FunctionDescriptor.of(JAVA_INT, JAVA_INT);
    private static final FunctionDescriptor D_ATTRIBUTE = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LOCK = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

    private final String name;
    private MemorySegment reader = MemorySegment.NULL;
    private int videoIndex = -1, audioIndex = -1; // the reader's own numbers for the streams
    private boolean videoDone, audioDone;
    private final MediaInfo info;
    private final double duration;
    private final int audioChannelsOut, audioRateOut, audioBits;
    private Buffers buffers;
    private int bufWidth, bufHeight;     // the decoder's buffer: padded up to whole macroblocks
    private int cropX, cropY, frameWidth, frameHeight; // the picture inside it, before any turn
    private int stride;                  // bytes per row of the decoder's buffer; negative when the rows run bottom-up
    private int shownWidth, shownHeight; // the picture as it is handed over, after the turn
    private int quarterTurns;            // clockwise turns the file asks to show the picture with
    private float fps;
    private double timeShift;  // added to picture times, see the constructor
    private double firstPts;   // the earliest picture time the reader reported, before the shift
    private boolean closed;

    static boolean available() {
        return MediaFoundation.available();
    }

    WinMediaSource(File file) throws IOException {
        this(file, 0);
    }

    /**
     * @param editShift what the container's own edit list says to add to every picture time. The MP4 source of Media
     *                  Foundation reports pictures at their composition time and ignores an edit list that cuts the
     *                  composition delay of a stream with B-frames, which leaves the picture two frames behind the
     *                  sound. The shift is applied only when it keeps the first picture at or after zero, so a reader
     *                  that has already applied the edit list is left alone.
     */
    WinMediaSource(File file, double editShift) throws IOException {
        name = file.getName();
        MediaFoundation.thread();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment settings = newAttributes(a, READER_ENABLE_VIDEO_PROCESSING, 1); // lets the reader convert YUV to RGB
            try {
                reader = newReader(a, file.getAbsolutePath(), settings);
            } catch (IOException e) {
                throw new UnsupportedOperationException("Unsupported video format: " + name + " cannot be read by this system's media framework (" + e.getMessage() + ")");
            } finally {
                MediaFoundation.release(settings);
            }
            findStreams(a);
            if (videoIndex < 0) throw new UnsupportedOperationException("Unsupported video format: " + name + " has no video track");

            check(call(reader, READER_SET_STREAM_SELECTION, D_SELECTION, ALL_STREAMS, 0), "deselecting the streams");
            check(call(reader, READER_SET_STREAM_SELECTION, D_SELECTION, videoIndex, 1), "selecting the picture");

            MemorySegment nativeVideo = nativeType(a, videoIndex);
            String videoCodec = describeVideo(getGuid(a, nativeVideo, MT_SUBTYPE));
            long rate = getUInt64(a, nativeVideo, MT_FRAME_RATE, 0);
            fps = (rate >>> 32) == 0 || (rate & 0xFFFFFFFFL) == 0 ? 0 : (float) ((double) (rate >>> 32) / (rate & 0xFFFFFFFFL));
            quarterTurns = (int) (getUInt32(a, nativeVideo, MT_VIDEO_ROTATION, 0) / 90) & 3; // degrees; the MP4 source reports a clockwise-90 transform as 90
            MediaFoundation.release(nativeVideo);
            setVideoOutput(a);
            readVideoType(a);
            duration = readDuration(a);
            int channels = 0, sampleRate = 0, bits = 16;
            String audioCodec = null;
            if (audioIndex >= 0) {
                MemorySegment nativeAudio = nativeType(a, audioIndex);
                audioCodec = describeAudio(getGuid(a, nativeAudio, MT_SUBTYPE));
                int nativeChannels = (int) getUInt32(a, nativeAudio, MT_AUDIO_CHANNELS, 0);
                MediaFoundation.release(nativeAudio);
                try {
                    int[] got = setAudioOutput(a, Math.min(nativeChannels, 2));
                    channels = got[0];
                    sampleRate = got[1];
                    bits = got[2];
                } catch (IOException | UnsupportedOperationException e) {
                    log.debug("The system cannot decode the sound of {}: {}", name, e.getMessage());
                    audioIndex = -1;
                    channels = sampleRate = 0;
                }
            }
            audioChannelsOut = channels;
            audioRateOut = sampleRate;
            audioBits = bits;
            if (audioIndex >= 0) {
                check(call(reader, READER_SET_STREAM_SELECTION, D_SELECTION, audioIndex, 1), "selecting the sound");
            }
            double measured = prime(a);
            if (measured > 0) fps = (float) (Math.round(measured * 1000) / 1000.0);
            if (editShift != 0 && firstPts + editShift > -0.001) timeShift = editShift;
            info = new MediaInfo(shownWidth, shownHeight, fps, duration, videoCodec, audioChannelsOut, audioRateOut, audioCodec,
                    "OS (Media Foundation)");
            if (shownWidth <= 0 || shownHeight <= 0) {
                throw new UnsupportedOperationException("Unsupported video format: " + name + " reports no picture size");
            }
            audioDone = audioIndex < 0;
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    // ------------------------------------------------------------------ the streams

    private void findStreams(Arena a) {
        for (int i = 0; i < 64 && (videoIndex < 0 || audioIndex < 0); i++) {
            MemorySegment out = a.allocate(8);
            int hr = call(reader, READER_GET_NATIVE_TYPE, D_NATIVE, i, 0, out);
            if (hr < 0) {
                if (hr == INVALID_STREAM_NUMBER) break;
                continue; // a stream that cannot say what it is
            }
            MemorySegment type = out.get(ADDRESS, 0);
            String major = getGuid(a, type, MT_MAJOR_TYPE);
            MediaFoundation.release(type);
            if (VIDEO.equals(major) && videoIndex < 0) videoIndex = i;
            else if (AUDIO.equals(major) && audioIndex < 0) audioIndex = i;
        }
    }

    private MemorySegment nativeType(Arena a, int stream) throws IOException {
        MemorySegment out = a.allocate(8);
        check(call(reader, READER_GET_NATIVE_TYPE, D_NATIVE, stream, 0, out), "reading the stream's format");
        return out.get(ADDRESS, 0);
    }

    private MemorySegment currentType(Arena a, int stream) throws IOException {
        MemorySegment out = a.allocate(8);
        check(call(reader, READER_GET_CURRENT_TYPE, D_TYPE, stream, out), "reading the decoded format");
        return out.get(ADDRESS, 0);
    }

    /** Asks for BGRA pictures. The reader loads the decoder now, so a codec this machine lacks fails here. */
    private void setVideoOutput(Arena a) throws IOException {
        MemorySegment type = newMediaType(a);
        try {
            setGuid(a, type, MT_MAJOR_TYPE, VIDEO);
            setGuid(a, type, MT_SUBTYPE, RGB32);
            int hr = call(reader, READER_SET_CURRENT_TYPE, D_SET_TYPE, videoIndex, MemorySegment.NULL, type);
            if (hr < 0) {
                throw new UnsupportedOperationException("Unsupported video format: " + name + " cannot be decoded by this system (" + hex(hr) + ")");
            }
        } finally {
            MediaFoundation.release(type);
        }
    }

    /** Reads the size and row layout of what the decoder now delivers. */
    private void readVideoType(Arena a) throws IOException {
        MemorySegment type = currentType(a, videoIndex);
        try {
            long size = getUInt64(a, type, MT_FRAME_SIZE, 0);
            bufWidth = (int) (size >>> 32);
            bufHeight = (int) (size & 0xFFFFFFFFL);
            stride = getInt32(a, type, MT_DEFAULT_STRIDE, bufWidth * 4);
            if (stride == 0) stride = bufWidth * 4;
            // A decoder pads the buffer to whole macroblocks (368 rows for a 360-row picture) and says where the
            // picture is in a video area: x offset, y offset (each a 16-bit fraction then a 16-bit whole), width, height.
            cropX = cropY = 0;
            frameWidth = bufWidth;
            frameHeight = bufHeight;
            byte[] area = getBlob(a, type, MT_MINIMUM_DISPLAY_APERTURE, 16);
            if (area == null) area = getBlob(a, type, MT_GEOMETRIC_APERTURE, 16);
            if (area != null && area.length == 16) {
                ByteBuffer b = ByteBuffer.wrap(area).order(ByteOrder.LITTLE_ENDIAN);
                int x = b.getShort(2), y = b.getShort(6), w = b.getInt(8), h = b.getInt(12);
                if (x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= bufWidth && y + h <= bufHeight) {
                    cropX = x;
                    cropY = y;
                    frameWidth = w;
                    frameHeight = h;
                }
            }
            boolean sideways = (quarterTurns & 1) == 1;
            shownWidth = sideways ? frameHeight : frameWidth;
            shownHeight = sideways ? frameWidth : frameHeight;
        } finally {
            MediaFoundation.release(type);
        }
    }

    /** Asks for 16-bit PCM, no more than two channels. Returns what the decoder settled on: channels, rate, bits. */
    private int[] setAudioOutput(Arena a, int wantChannels) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            MemorySegment type = newMediaType(a);
            try {
                setGuid(a, type, MT_MAJOR_TYPE, AUDIO);
                setGuid(a, type, MT_SUBTYPE, PCM);
                setUInt32(a, type, MT_AUDIO_BITS, 16);
                if (attempt == 0 && wantChannels > 0) setUInt32(a, type, MT_AUDIO_CHANNELS, wantChannels);
                int hr = call(reader, READER_SET_CURRENT_TYPE, D_SET_TYPE, audioIndex, MemorySegment.NULL, type);
                if (hr < 0) {
                    if (attempt == 0) continue; // let the decoder pick the channel count
                    throw new UnsupportedOperationException("the sound cannot be decoded (" + hex(hr) + ")");
                }
            } finally {
                MediaFoundation.release(type);
            }
            break;
        }
        MemorySegment type = currentType(a, audioIndex);
        try {
            int channels = (int) getUInt32(a, type, MT_AUDIO_CHANNELS, 0);
            int rate = (int) getUInt32(a, type, MT_AUDIO_RATE, 0);
            int bits = (int) getUInt32(a, type, MT_AUDIO_BITS, 16);
            if (channels <= 0 || rate <= 0 || bits != 16) throw new UnsupportedOperationException("the sound is not 16-bit PCM");
            return new int[]{channels, rate, bits};
        } finally {
            MediaFoundation.release(type);
        }
    }

    /**
     * Decodes the first few pictures, which tells what the decoder only learns from the stream: the cropped size and
     * the true frame rate (the container's figure is an average over the whole file). Then goes back to the start.
     * Returns the frame rate measured, or 0 when there were too few pictures to tell.
     */
    private double prime(Arena a) throws IOException {
        MemorySegment actual = a.allocate(4), flags = a.allocate(4), time = a.allocate(8), sample = a.allocate(8);
        double[] times = new double[6];
        int n = 0;
        for (int i = 0; i < 40 && n < times.length; i++) {
            sample.set(ADDRESS, 0, MemorySegment.NULL);
            int hr = call(reader, READER_READ_SAMPLE, D_READ, videoIndex, 0, actual, flags, time, sample);
            if (hr < 0) throw new UnsupportedOperationException("Unsupported video format: " + name + " cannot be decoded by this system (" + hex(hr) + ")");
            MemorySegment s = sample.get(ADDRESS, 0);
            boolean ended = (flags.get(JAVA_INT, 0) & END_OF_STREAM) != 0;
            if (!isNull(s)) {
                times[n++] = time.get(JAVA_LONG, 0) / TICKS;
                MediaFoundation.release(s);
            }
            if (ended) break;
        }
        readVideoType(a);
        reposition(a, 0);
        if (n == 0) return 0;
        java.util.Arrays.sort(times, 0, n);
        firstPts = times[0];
        if (n < 3) return 0;
        double[] steps = new double[n - 1];
        for (int i = 1; i < n; i++) steps[i - 1] = times[i] - times[i - 1];
        java.util.Arrays.sort(steps);
        double step = steps[steps.length / 2];
        return step > 0.002 ? 1 / step : 0;
    }

    private void reposition(Arena a, double seconds) throws IOException {
        MemorySegment position = a.allocate(24); // a PROPVARIANT holding a 64-bit count of 100 ns
        position.set(JAVA_SHORT, 0, (short) VT_I8);
        position.set(JAVA_LONG, 8, Math.round(Math.max(0, seconds) * TICKS));
        check(call(reader, READER_SET_POSITION, D_POSITION, guid(a, GUID_NULL), position), "seeking");
    }

    private double readDuration(Arena a) {
        MemorySegment value = a.allocate(24); // a PROPVARIANT
        int hr = call(reader, READER_GET_PRESENTATION_ATTRIBUTE, D_ATTRIBUTE, MEDIA_SOURCE, guid(a, PD_DURATION), value);
        if (hr < 0 || value.get(JAVA_SHORT, 0) != VT_UI8) return 0;
        return value.get(JAVA_LONG, 8) / TICKS;
    }

    private static String describeVideo(String subtype) {
        if (subtype == null) return "Video";
        String code = fourcc(format(subtype));
        return switch (code) {
            case "H264", "h264", "avc1", "AVC1", "avc3" -> "H.264";
            case "HEVC", "hvc1", "hev1", "HEVS" -> "H.265";
            case "VP90" -> "VP9";
            case "AV01" -> "AV1";
            case "MJPG", "mjpg", "jpeg" -> "MJPEG";
            case "WMV1", "WMV2", "WMV3", "WVC1" -> "WMV";
            case "MP4V", "MP4S", "M4S2", "mp4v" -> "MPEG-4";
            default -> code.isEmpty() ? "Video" : code;
        };
    }

    private static String describeAudio(String subtype) {
        return switch ((int) format(subtype)) {
            case 0x1610, 0xFF, 0x1600, 0x1601 -> "AAC";
            case 0x1, 0x3 -> "PCM";
            case 0x55 -> "MP3";
            case 0x2000 -> "AC-3";
            case 0x161, 0x162, 0x163 -> "WMA";
            case 0xF1AC -> "FLAC";
            case 0x704F -> "Opus";
            default -> subtype == null ? "Audio" : "0x" + Long.toHexString(format(subtype));
        };
    }

    private static String fourcc(long v) {
        StringBuilder s = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            char c = (char) ((v >> (8 * i)) & 0xFF);
            s.append(c >= 32 && c < 127 ? c : '?');
        }
        return s.toString().trim();
    }

    // ------------------------------------------------------------------ Demuxer

    @Override
    public MediaInfo info() {
        return info;
    }

    @Override
    public int videoStream() {
        return VIDEO_STREAM;
    }

    @Override
    public int audioStream() {
        return audioIndex < 0 ? -1 : AUDIO_STREAM;
    }

    @Override
    public void useBuffers(Buffers buffers) {
        this.buffers = buffers;
    }

    @Override
    public FrameDecoder videoDecoder() {
        return packet -> new VideoFrame(shownWidth, shownHeight, packet.data(), packet.pts());
    }

    @Override
    public AudioTrackDecoder audioDecoder() {
        return audioIndex < 0 ? null : new PcmAudioDecoder(audioChannelsOut, audioRateOut, audioBits, false);
    }

    @Override
    public Packet next() throws IOException {
        MediaFoundation.thread();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment actual = a.allocate(4), flags = a.allocate(4), time = a.allocate(8), sample = a.allocate(8);
            while (!(videoDone && audioDone)) {
                sample.set(ADDRESS, 0, MemorySegment.NULL);
                int hr = call(reader, READER_READ_SAMPLE, D_READ, ANY_STREAM, 0, actual, flags, time, sample);
                if (hr < 0) throw new IOException("The system stopped reading " + name + " (" + hex(hr) + ")");
                int stream = actual.get(JAVA_INT, 0), status = flags.get(JAVA_INT, 0);
                MemorySegment s = sample.get(ADDRESS, 0);
                try {
                    if ((status & END_OF_STREAM) != 0) {
                        if (stream == videoIndex) videoDone = true;
                        else if (stream == audioIndex) audioDone = true;
                        else videoDone = audioDone = true; // the reader says the whole file is over
                    }
                    if ((status & CURRENT_TYPE_CHANGED) != 0 && stream == videoIndex) readVideoType(a);
                    if (isNull(s)) continue; // a gap, a tick or a change of format: nothing to hand over
                    double pts = time.get(JAVA_LONG, 0) / TICKS;
                    Packet p = stream == videoIndex ? videoPacket(a, s, shifted(pts)) : stream == audioIndex ? audioPacket(a, s, pts) : null;
                    if (p != null) return p;
                } finally {
                    MediaFoundation.release(s);
                }
            }
            return null;
        }
    }

    private double shifted(double pts) {
        double t = pts + timeShift;
        return t < 0 && t > -0.001 ? 0 : t; // the sum of two rounded times, which should be exactly 0
    }

    /** Locks the sample's one contiguous buffer and runs {@code use} on its bytes. Null if the buffer cannot be read. */
    private interface Bytes<T> {
        T use(MemorySegment data);
    }

    private <T> T withBytes(Arena a, MemorySegment sample, Bytes<T> use) {
        MemorySegment out = a.allocate(8);
        if (call(sample, SAMPLE_CONVERT_TO_CONTIGUOUS, FunctionDescriptor.of(JAVA_INT, ADDRESS), out) < 0) return null;
        MemorySegment buffer = out.get(ADDRESS, 0);
        try {
            MemorySegment data = a.allocate(8), max = a.allocate(4), length = a.allocate(4);
            if (call(buffer, BUFFER_LOCK, D_LOCK, data, max, length) < 0) return null;
            try {
                return use.use(data.get(ADDRESS, 0).reinterpret(length.get(JAVA_INT, 0) & 0xFFFFFFFFL));
            } finally {
                call(buffer, BUFFER_UNLOCK, NO_ARGS);
            }
        } finally {
            MediaFoundation.release(buffer);
        }
    }

    private Packet videoPacket(Arena a, MemorySegment sample, double pts) {
        return withBytes(a, sample, src -> {
            int w = frameWidth, h = frameHeight, row = Math.abs(stride);
            if (w <= 0 || h <= 0 || row < bufWidth * 4 || src.byteSize() < (long) row * bufHeight) return null;
            byte[] out = buffers != null ? buffers.take(w * h * 4) : new byte[w * h * 4];
            MemorySegment dst = MemorySegment.ofArray(out);
            for (int y = 0; y < h; y++) {
                int line = cropY + y;
                long from = (long) (stride < 0 ? bufHeight - 1 - line : line) * row + cropX * 4L;
                MemorySegment.copy(src, from, dst, (long) y * w * 4, (long) w * 4);
            }
            // RGB32 leaves the fourth byte of each pixel undefined; the engine reads it as alpha.
            IntBuffer pixels = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            for (int i = 0, n = w * h; i < n; i++) pixels.put(i, pixels.get(i) | 0xFF000000);
            if (quarterTurns != 0) {
                byte[] turned = buffers != null ? buffers.take(w * h * 4) : new byte[w * h * 4];
                Rotation.rotate(out, turned, w, h, quarterTurns);
                if (buffers != null) buffers.give(out);
                out = turned;
            }
            return new Packet(VIDEO_STREAM, pts, true, out);
        });
    }

    private Packet audioPacket(Arena a, MemorySegment sample, double pts) {
        return withBytes(a, sample, src -> {
            if (src.byteSize() <= 0) return null;
            byte[] out = new byte[(int) src.byteSize()];
            MemorySegment.copy(src, 0, MemorySegment.ofArray(out), 0, out.length);
            return new Packet(AUDIO_STREAM, pts, true, out);
        });
    }

    @Override
    public void seek(double seconds) throws IOException {
        MediaFoundation.thread();
        try (Arena a = Arena.ofConfined()) {
            reposition(a, seconds - SEEK_LEAD - timeShift);
            videoDone = false;
            audioDone = audioIndex < 0;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            MediaFoundation.thread();
            if (!isNull(reader)) {
                call(reader, READER_FLUSH, D_FLUSH, ALL_STREAMS);
                MediaFoundation.release(reader);
            }
        } catch (RuntimeException e) {
            log.debug("Closing {}: {}", name, e.toString());
        }
        reader = MemorySegment.NULL;
    }
}
