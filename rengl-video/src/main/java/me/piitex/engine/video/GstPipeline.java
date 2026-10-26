package me.piitex.engine.video;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static me.piitex.engine.video.GStreamer.*;

/**
 * A GStreamer {@code playbin} that opens a file, decodes it with whatever plug-ins the machine has (H.264 and AAC
 * included, which is the system's licence and not the engine's) and delivers BGRA pictures and 16-bit PCM through two
 * {@code appsink}s. Everything GStreamer-specific is here; {@link LinuxMediaSource} does the rest.
 * <p>
 * The sinks do not wait for a clock ({@code sync=false}) and hold a few buffers each, so the pipeline decodes as fast as
 * the player pulls and stops when it falls behind. A {@code videoflip} set to follow the file's orientation tag turns a
 * phone's upright video.
 */
final class GstPipeline implements GstPlayback {
    private static final Logger log = LogManager.getLogger("Video");
    private static final long SETTLE_NANOS = 20_000_000_000L;
    private static final long POLL_NANOS = 100 * NANOS_PER_MILLI;
    private static final String PLUGIN_HINT = "A plug-in may be missing: MP4 and MOV files need gst-plugins-good"
            + " (gstreamer1.0-plugins-good), and H.264 needs gst-libav (gstreamer1.0-libav) or a decoder from gst-plugins-bad";
    private static final String VIDEO_CAPS = "video/x-raw,format=BGRA";
    private static final String AUDIO_CAPS = "audio/x-raw,format=S16LE,layout=interleaved,channels=[1,2]";

    private final String name;
    private MemorySegment pipeline = MemorySegment.NULL, bus = MemorySegment.NULL;
    private MemorySegment video = MemorySegment.NULL, audio = MemorySegment.NULL; // owned by the pipeline once set on it
    private Format format;
    private int width, height;
    private boolean hasAudio, sizeWarned, closed;
    private Buffers buffers;

    GstPipeline(File file) throws IOException {
        name = file.getName();
        try {
            pipeline = make("playbin", "rengl-play");
            video = make("appsink", "rengl-video");
            audio = make("appsink", "rengl-audio");
            if (isNull(pipeline) || isNull(video) || isNull(audio)) {
                throw new UnsupportedOperationException("Unsupported video format: GStreamer is installed but lacks its base plug-ins (playbin, appsink)."
                        + " Install gst-plugins-base (gstreamer1.0-plugins-base)");
            }
            configure(video, VIDEO_CAPS, 2);
            configure(audio, AUDIO_CAPS, 8);
            MemorySegment flip = make("videoflip", "rengl-flip");
            if (!isNull(flip)) {
                setArg(flip, "video-direction", "auto");
                setObject(pipeline, "video-filter", flip);
            }
            setObject(pipeline, "video-sink", video);
            setObject(pipeline, "audio-sink", audio);
            setArg(pipeline, "flags", "video+audio"); // no subtitles, no soft volume, no deinterlacing
            setString(pipeline, "uri", uriOf(file));
            bus = GStreamer.bus(pipeline);
            preroll();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    /** The {@code file://} address GStreamer wants for a local path. */
    static String uriOf(File file) {
        return "file://" + file.toURI().getRawPath();
    }

    private static void configure(MemorySegment sink, String caps, int buffers) {
        setArg(sink, "caps", caps);
        setArg(sink, "sync", "false");
        setArg(sink, "max-buffers", Integer.toString(buffers));
        setArg(sink, "drop", "false");
    }

    /** Pauses the pipeline, which decodes the first picture and the first sound, and reads what they are. */
    private void preroll() {
        if (setState(pipeline, STATE_PAUSED) == CHANGE_FAILURE) throw cannotPlay(failure());
        long deadline = System.nanoTime() + SETTLE_NANOS;
        for (; ; ) {
            int change = waitForState(pipeline, POLL_NANOS);
            String error = failure();
            if (error != null) throw cannotPlay(error);
            if (change == CHANGE_SUCCESS || change == CHANGE_NO_PREROLL) break;
            if (change == CHANGE_FAILURE) throw cannotPlay(null);
            if (System.nanoTime() > deadline) throw cannotPlay("it took more than 20 seconds to open");
        }
        MemorySegment picture = pullPreroll(video, 0);
        if (isNull(picture)) {
            throw new UnsupportedOperationException("Unsupported video format: " + name + " has no picture GStreamer can decode."
                    + " " + PLUGIN_HINT);
        }
        double frameRate;
        try {
            MemorySegment caps = sampleCaps(picture);
            width = capsInt(caps, "width", 0);
            height = capsInt(caps, "height", 0);
            frameRate = capsFraction(caps, "framerate");
        } finally {
            release(picture);
        }
        int channels = 0, rate = 0;
        MemorySegment sound = pullPreroll(audio, 0);
        if (!isNull(sound)) {
            try {
                MemorySegment caps = sampleCaps(sound);
                channels = capsInt(caps, "channels", 0);
                rate = capsInt(caps, "rate", 0);
            } finally {
                release(sound);
            }
        }
        hasAudio = channels > 0 && rate > 0;
        if (!hasAudio) channels = rate = 0;
        String[] codecs = codecs();
        double duration = GStreamer.duration(pipeline);
        format = new Format(width, height, frameRate, duration, codecs[0], channels, rate, hasAudio ? codecs[1] : null);
        if (setState(pipeline, STATE_PLAYING) == CHANGE_FAILURE) throw cannotPlay(failure());
    }

    /** The codec names the stream announced as tags while it was being opened: {video, audio}. */
    private String[] codecs() {
        String[] found = new String[2];
        for (int i = 0; i < 64; i++) {
            MemorySegment message = popMessage(bus, MESSAGE_TAG, 0);
            if (isNull(message)) break;
            try {
                String[] tags = tagStrings(message, "video-codec", "audio-codec");
                if (tags[0] != null) found[0] = tags[0];
                if (tags[1] != null) found[1] = tags[1];
            } finally {
                release(message);
            }
        }
        return found;
    }

    private UnsupportedOperationException cannotPlay(String why) {
        return new UnsupportedOperationException("Unsupported video format: " + name + " cannot be played by GStreamer"
                + (why == null ? "" : " (" + why + ")")
                + ". " + PLUGIN_HINT);
    }

    @Override
    public Format format() {
        return format;
    }

    @Override
    public void useBuffers(Buffers buffers) {
        this.buffers = buffers;
    }

    @Override
    public String failure() {
        if (isNull(bus)) return null;
        MemorySegment message = popMessage(bus, MESSAGE_ERROR, 0);
        if (isNull(message)) return null;
        try {
            return errorText(message);
        } finally {
            release(message);
        }
    }

    // ------------------------------------------------------------------ samples

    @Override
    public Sample pullVideo(int millis) {
        MemorySegment sample = pullSample(video, millis * NANOS_PER_MILLI);
        if (isNull(sample)) return null;
        try {
            MemorySegment caps = sampleCaps(sample);
            if (capsInt(caps, "width", 0) != width || capsInt(caps, "height", 0) != height) {
                if (!sizeWarned) log.warn("{} changed its picture size part way through, which the player does not follow; those pictures are skipped", name);
                sizeWarned = true;
                return null;
            }
            byte[] pixels = buffers != null ? buffers.take(width * height * 4) : new byte[width * height * 4];
            double pts = read(sample, (data, size) -> copyPicture(data, width, height, pixels));
            if (Double.isNaN(pts)) {
                if (buffers != null) buffers.give(pixels);
                return null;
            }
            return new Sample(pixels, pts);
        } finally {
            release(sample);
        }
    }

    @Override
    public Sample pullAudio(int millis) {
        if (!hasAudio) return null;
        MemorySegment sample = pullSample(audio, millis * NANOS_PER_MILLI);
        if (isNull(sample)) return null;
        try {
            byte[][] sound = new byte[1][];
            double pts = read(sample, (data, size) -> {
                sound[0] = new byte[(int) size];
                MemorySegment.copy(data, 0, MemorySegment.ofArray(sound[0]), 0, size);
                return true;
            });
            return Double.isNaN(pts) || sound[0].length == 0 ? null : new Sample(sound[0], pts);
        } finally {
            release(sample);
        }
    }

    /** Returned by {@link #read} when the memory could not be mapped or {@code use} refused it. */
    private static final double FAILED = Double.NaN;

    private interface Use {
        boolean use(MemorySegment data, long size);
    }

    /** Maps the sample's buffer, runs {@code use} on its bytes and returns the buffer's time in seconds (negative if none). */
    private double read(MemorySegment sample, Use use) {
        MemorySegment buffer = sampleBuffer(sample);
        if (isNull(buffer)) return FAILED;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment info = a.allocate(MAP_INFO_SIZE);
            if (!map(buffer, info)) return FAILED;
            try {
                long size = info.get(JAVA_LONG, MAP_SIZE);
                MemorySegment data = info.get(ADDRESS, MAP_DATA).reinterpret(size);
                if (!use.use(data, size)) return FAILED;
            } finally {
                unmap(buffer, info);
            }
            return seconds(buffer.reinterpret(BUFFER_SIZE).get(JAVA_LONG, BUFFER_PTS), format != null ? format.duration() : 0);
        }
    }

    /**
     * The picture's rows as tight BGRA. A buffer exactly {@code w * h * 4} bytes long is that already. One that is longer
     * is either rows padded out to a common pitch (when its size divides evenly by the height) or has padding after the
     * last row, and a shorter one is no picture.
     */
    static boolean copyPicture(MemorySegment src, int w, int h, byte[] out) {
        long row = (long) w * 4, size = src.byteSize();
        if (w <= 0 || h <= 0 || size < row * h || out.length < row * h) return false;
        long pitch = size > row * h && size % h == 0 && (size / h) % 4 == 0 ? size / h : row;
        MemorySegment dst = MemorySegment.ofArray(out);
        if (pitch == row) {
            MemorySegment.copy(src, 0, dst, 0, row * h);
        } else {
            for (int y = 0; y < h; y++) MemorySegment.copy(src, y * pitch, dst, y * row, row);
        }
        return true;
    }

    /**
     * A GStreamer time in nanoseconds as seconds. GStreamer's "none" (all bits set, which reads as -1) and anything
     * later than the file's length plus a minute give -1: the offset this is read from is not part of a function
     * GStreamer exports, so a value that makes no sense is taken as "no time" rather than believed.
     */
    static double seconds(long nanos, double duration) {
        if (nanos < 0) return -1;
        double s = nanos / 1e9;
        return s <= (duration > 0 ? duration + 60 : 360_000) ? s : -1;
    }

    @Override
    public boolean videoEnded() {
        return ended(video);
    }

    @Override
    public boolean audioEnded() {
        return !hasAudio || ended(audio);
    }

    @Override
    public void seek(double seconds) throws IOException {
        if (!GStreamer.seek(pipeline, seconds, SEEK_FLUSH | SEEK_KEY_UNIT | SEEK_SNAP_BEFORE)) {
            throw new IOException("GStreamer could not seek " + name + " to " + seconds + " s");
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (!isNull(pipeline)) setState(pipeline, STATE_NULL);
            unref(bus);
            unref(pipeline);
        } catch (RuntimeException e) {
            log.debug("Closing {}: {}", name, e.toString());
        }
        pipeline = bus = video = audio = MemorySegment.NULL;
    }
}
