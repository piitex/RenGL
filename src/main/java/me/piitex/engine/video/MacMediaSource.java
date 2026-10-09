package me.piitex.engine.video;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

/**
 * Reads a video file through the operating system on macOS: AVFoundation opens the container and decodes the
 * picture (on the GPU's video engine where there is one) and the sound, and this class receives finished BGRA
 * frames and 16-bit PCM. That makes every codec the machine can play, H.264 and HEVC included, available without
 * the engine containing a decoder for them. The file is still read from disk as it plays.
 * <p>
 * An AVAssetReader cannot seek, so a seek starts a new reader at the target.
 */
final class MacMediaSource implements Demuxer {
    private static final Logger log = LogManager.getLogger("Video");
    private static final int VIDEO = 0, AUDIO = 1;
    /** A seek starts this far before the target, so the picture for the target time is among the frames. */
    private static final double SEEK_LEAD = 0.5;

    private final String name;
    private MemorySegment asset, videoTrack, audioTrack;
    private MemorySegment reader, videoOut, audioOut;
    private MemorySegment pendingVideo = MemorySegment.NULL, pendingAudio = MemorySegment.NULL;
    private double pendingVideoPts, pendingAudioPts;
    private boolean videoDone, audioDone;
    private final MediaInfo info;
    private final double duration;
    private final int audioChannelsOut, audioRateOut;
    private Buffers buffers;
    private int frameWidth, frameHeight;
    private int quarterTurns; // clockwise turns the file asks to show the picture with; a phone recording sideways
    private boolean closed;

    static boolean available() {
        return ObjC.available();
    }

    MacMediaSource(File file) throws IOException {
        name = file.getName();
        try (var pool = ObjC.pool(); Arena a = Arena.ofConfined()) {
            MemorySegment url = ObjC.id(ObjC.cls("NSURL"), "fileURLWithPath:", ObjC.string(a, file.getAbsolutePath()));
            asset = ObjC.retain(ObjC.id(ObjC.cls("AVURLAsset"), "URLAssetWithURL:options:", url, MemorySegment.NULL));
            if (!ObjC.bool(asset, "isReadable")) {
                throw new UnsupportedOperationException("Unsupported video format: " + name + " cannot be read by this system's media framework");
            }
            videoTrack = firstTrack(a, "vide");
            if (videoTrack == null) throw new UnsupportedOperationException("Unsupported video format: " + name + " has no video track");
            audioTrack = firstTrack(a, "soun");

            MemorySegment size = ObjC.struct(ObjC.CGSIZE, a, videoTrack, "naturalSize");
            frameWidth = (int) Math.round(size.get(JAVA_DOUBLE, 0));
            frameHeight = (int) Math.round(size.get(JAVA_DOUBLE, 8));
            MemorySegment shown = ObjC.struct(ObjC.CGAFFINE, a, videoTrack, "preferredTransform");
            quarterTurns = Rotation.quarterTurns(shown.get(JAVA_DOUBLE, 0), shown.get(JAVA_DOUBLE, 8));
            if ((quarterTurns & 1) == 1) {
                int w = frameWidth;
                frameWidth = frameHeight;
                frameHeight = w;
            }
            MemorySegment d = ObjC.struct(ObjC.CMTIME, a, asset, "duration");
            double seconds = ObjC.seconds(d);
            duration = Double.isNaN(seconds) || Double.isInfinite(seconds) ? 0 : seconds;
            float fps = ObjC.flt(videoTrack, "nominalFrameRate");
            String videoCodec = describeVideo(codecOf(videoTrack));

            int channels = 0, rate = 0;
            String audioCodec = null;
            if (audioTrack != null) {
                MemorySegment desc = formatDescription(audioTrack);
                MemorySegment asbd = desc == null ? null : ObjC.audioDescription(desc);
                if (asbd != null) {
                    rate = (int) Math.round(asbd.get(JAVA_DOUBLE, 0));
                    channels = asbd.get(JAVA_INT, 28);
                    audioCodec = describeAudio(ObjC.fourcc(asbd.get(JAVA_INT, 8)));
                }
                if (channels <= 0 || rate <= 0) {
                    ObjC.release(audioTrack);
                    audioTrack = null;
                    channels = rate = 0;
                }
            }
            audioChannelsOut = Math.min(channels, 2);
            audioRateOut = rate;
            info = new MediaInfo(frameWidth, frameHeight, fps, duration, videoCodec, audioChannelsOut, rate, audioCodec,
                    "OS (AVFoundation)");
            if (frameWidth <= 0 || frameHeight <= 0) {
                throw new UnsupportedOperationException("Unsupported video format: " + name + " reports no picture size");
            }
            startReader(0, a);
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    // ------------------------------------------------------------------ the asset

    private MemorySegment firstTrack(Arena a, String type) {
        MemorySegment tracks = ObjC.id(asset, "tracksWithMediaType:", ObjC.string(a, type));
        if (ObjC.isNull(tracks) || ObjC.lng(tracks, "count") == 0) return null;
        return ObjC.retain(ObjC.id(tracks, "objectAtIndex:", 0L));
    }

    private static MemorySegment formatDescription(MemorySegment track) {
        MemorySegment descriptions = ObjC.id(track, "formatDescriptions");
        if (ObjC.isNull(descriptions) || ObjC.lng(descriptions, "count") == 0) return null;
        return ObjC.id(descriptions, "objectAtIndex:", 0L);
    }

    private static String codecOf(MemorySegment track) {
        MemorySegment desc = formatDescription(track);
        return desc == null ? "" : ObjC.fourcc(ObjC.mediaSubType(desc));
    }

    private static String describeVideo(String code) {
        return switch (code) {
            case "avc1", "avc3" -> "H.264";
            case "hvc1", "hev1" -> "H.265";
            case "vp09" -> "VP9";
            case "av01" -> "AV1";
            case "apch", "apcn", "apcs", "apco", "ap4h", "ap4x" -> "ProRes";
            case "jpeg", "mjpa", "mjpb" -> "MJPEG";
            default -> code.isBlank() ? "Video" : code;
        };
    }

    private static String describeAudio(String code) {
        return switch (code) {
            case "mp4a", "aac " -> "AAC";
            case "lpcm" -> "PCM";
            case ".mp3" -> "MP3";
            case "ac-3" -> "AC-3";
            case "alac" -> "ALAC";
            case "Opus" -> "Opus";
            default -> code.trim();
        };
    }

    // ------------------------------------------------------------------ the reader

    private void startReader(double from, Arena a) throws IOException {
        stopReader();
        reader = ObjC.retain(ObjC.id(ObjC.cls("AVAssetReader"), "assetReaderWithAsset:error:", asset, MemorySegment.NULL));
        if (ObjC.isNull(reader)) throw new IOException("Could not open " + name + " for reading");

        MemorySegment videoSettings = ObjC.dictionary(a, new MemorySegment[]{ObjC.string(a, "PixelFormatType")},
                new MemorySegment[]{ObjC.number(0x42475241)}); // 'BGRA'
        videoOut = addOutput(videoTrack, videoSettings);
        if (audioTrack != null) {
            MemorySegment audioSettings = ObjC.dictionary(a, new MemorySegment[]{
                    ObjC.string(a, "AVFormatIDKey"), ObjC.string(a, "AVLinearPCMBitDepthKey"), ObjC.string(a, "AVLinearPCMIsFloatKey"),
                    ObjC.string(a, "AVLinearPCMIsBigEndianKey"), ObjC.string(a, "AVLinearPCMIsNonInterleaved"),
                    ObjC.string(a, "AVNumberOfChannelsKey"), ObjC.string(a, "AVSampleRateKey")
            }, new MemorySegment[]{
                    ObjC.number(0x6C70636D), ObjC.number(16), ObjC.number(false), ObjC.number(false), ObjC.number(false),
                    ObjC.number(audioChannelsOut), ObjC.number((double) audioRateOut)
            }); // linear PCM, 16-bit, little-endian, interleaved
            audioOut = addOutput(audioTrack, audioSettings);
        }
        if (from > 0) {
            double start = Math.min(from, Math.max(0, duration - 0.05));
            ObjC.run(reader, "setTimeRange:", new ObjC.Struct(ObjC.range(a, start, Math.max(0.05, duration - start)), ObjC.CMTIME_RANGE));
        }
        if (!ObjC.bool(reader, "startReading")) {
            String why = ObjC.text(ObjC.id(ObjC.id(reader, "error"), "localizedDescription"));
            throw new IOException("The system could not read " + name + (why.isEmpty() ? "" : ": " + why));
        }
        videoDone = false;
        audioDone = audioOut == null;
    }

    private MemorySegment addOutput(MemorySegment track, MemorySegment settings) throws IOException {
        MemorySegment out = ObjC.retain(ObjC.id(ObjC.cls("AVAssetReaderTrackOutput"), "assetReaderTrackOutputWithTrack:outputSettings:", track, settings));
        ObjC.run(out, "setAlwaysCopiesSampleData:", false);
        if (!ObjC.bool(reader, "canAddOutput:", out)) throw new UnsupportedOperationException("Unsupported video format: " + name + " cannot be decoded by this system");
        ObjC.run(reader, "addOutput:", out);
        return out;
    }

    private void stopReader() {
        if (!ObjC.isNull(pendingVideo)) ObjC.cfRelease(pendingVideo);
        if (!ObjC.isNull(pendingAudio)) ObjC.cfRelease(pendingAudio);
        pendingVideo = pendingAudio = MemorySegment.NULL;
        if (reader != null) {
            ObjC.run(reader, "cancelReading");
            ObjC.release(videoOut);
            ObjC.release(audioOut);
            ObjC.release(reader);
        }
        reader = videoOut = audioOut = null;
    }

    // ------------------------------------------------------------------ Demuxer

    @Override
    public MediaInfo info() {
        return info;
    }

    @Override
    public int videoStream() {
        return VIDEO;
    }

    @Override
    public int audioStream() {
        return audioTrack == null ? -1 : AUDIO;
    }

    @Override
    public void useBuffers(Buffers buffers) {
        this.buffers = buffers;
    }

    @Override
    public FrameDecoder videoDecoder() {
        return packet -> new VideoFrame(frameWidth, frameHeight, packet.data(), packet.pts());
    }

    @Override
    public AudioTrackDecoder audioDecoder() {
        return audioTrack == null ? null : new PcmAudioDecoder(audioChannelsOut, audioRateOut, 16, false);
    }

    @Override
    public Packet next() throws IOException {
        try (var pool = ObjC.pool(); Arena a = Arena.ofConfined()) {
            while (true) {
                if (ObjC.isNull(pendingVideo) && !videoDone) {
                    pendingVideo = ObjC.id(videoOut, "copyNextSampleBuffer");
                    if (ObjC.isNull(pendingVideo)) videoDone = true;
                    else pendingVideoPts = ObjC.presentationTime(a, pendingVideo);
                }
                if (ObjC.isNull(pendingAudio) && !audioDone) {
                    pendingAudio = ObjC.id(audioOut, "copyNextSampleBuffer");
                    if (ObjC.isNull(pendingAudio)) audioDone = true;
                    else pendingAudioPts = ObjC.presentationTime(a, pendingAudio);
                }
                boolean haveVideo = !ObjC.isNull(pendingVideo), haveAudio = !ObjC.isNull(pendingAudio);
                if (!haveVideo && !haveAudio) {
                    reportFailure();
                    return null;
                }
                boolean takeVideo = haveVideo && (!haveAudio || pendingVideoPts <= pendingAudioPts);
                MemorySegment sample = takeVideo ? pendingVideo : pendingAudio;
                double pts = takeVideo ? pendingVideoPts : pendingAudioPts;
                if (takeVideo) pendingVideo = MemorySegment.NULL;
                else pendingAudio = MemorySegment.NULL;
                try {
                    Packet p = takeVideo ? videoPacket(sample, pts) : audioPacket(a, sample, pts);
                    if (p != null) return p;
                } finally {
                    ObjC.cfRelease(sample);
                }
            }
        }
    }

    private void reportFailure() throws IOException {
        if (reader == null) return;
        long status = ObjC.lng(reader, "status");
        if (status == 3) { // AVAssetReaderStatusFailed
            String why = ObjC.text(ObjC.id(ObjC.id(reader, "error"), "localizedDescription"));
            throw new IOException("The system stopped reading " + name + (why.isEmpty() ? "" : ": " + why));
        }
    }

    private Packet videoPacket(MemorySegment sample, double pts) {
        MemorySegment image = ObjC.imageBuffer(sample);
        if (ObjC.isNull(image) || !ObjC.lockPixels(image)) return null;
        try {
            int w = ObjC.pixelWidth(image), h = ObjC.pixelHeight(image);
            long stride = ObjC.pixelStride(image);
            boolean sideways = (quarterTurns & 1) == 1;
            frameWidth = sideways ? h : w;
            frameHeight = sideways ? w : h;
            byte[] out = buffers != null ? buffers.take(w * h * 4) : new byte[w * h * 4];
            MemorySegment base = ObjC.pixelBase(image).reinterpret(stride * h);
            MemorySegment dst = MemorySegment.ofArray(out);
            if (stride == w * 4L) {
                MemorySegment.copy(base, 0, dst, 0, (long) w * h * 4);
            } else {
                for (int y = 0; y < h; y++) MemorySegment.copy(base, y * stride, dst, (long) y * w * 4, (long) w * 4);
            }
            if (quarterTurns != 0) {
                byte[] turned = buffers != null ? buffers.take(w * h * 4) : new byte[w * h * 4];
                Rotation.rotate(out, turned, w, h, quarterTurns);
                if (buffers != null) buffers.give(out);
                out = turned;
            }
            return new Packet(VIDEO, pts, true, out);
        } finally {
            ObjC.unlockPixels(image);
        }
    }

    private Packet audioPacket(Arena a, MemorySegment sample, double pts) {
        MemorySegment block = ObjC.dataBuffer(sample);
        if (ObjC.isNull(block)) return null;
        long length = ObjC.blockLength(block);
        if (length <= 0) return null;
        MemorySegment native_ = a.allocate(length);
        if (ObjC.copyBlock(block, length, native_) != 0) return null;
        byte[] out = new byte[(int) length];
        MemorySegment.copy(native_, 0, MemorySegment.ofArray(out), 0, length);
        return new Packet(AUDIO, pts, true, out);
    }

    @Override
    public void seek(double seconds) throws IOException {
        try (var pool = ObjC.pool(); Arena a = Arena.ofConfined()) {
            startReader(Math.max(0, seconds - SEEK_LEAD), a);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try (var pool = ObjC.pool()) {
            stopReader();
            ObjC.release(videoTrack);
            ObjC.release(audioTrack);
            ObjC.release(asset);
        } catch (RuntimeException e) {
            log.debug("Closing {}: {}", name, e.toString());
        }
    }
}
