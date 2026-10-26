package me.piitex.engine.video;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Reads a video file through the operating system on Linux: GStreamer opens the container and decodes the picture and
 * the sound ({@link GstPipeline}), and this class turns what comes out into the packets the player reads. Every codec
 * the machine has a plug-in for is available without the engine containing a decoder, H.264 and AAC included.
 * <p>
 * The pipeline hands out pictures and sound from two queues, so the two are interleaved here by time. A picture or a
 * sound the pipeline gave no time is stamped by counting: pictures by the frame rate, sound by its length. The
 * pipeline's own times are believed only while they keep rising.
 */
final class LinuxMediaSource implements Demuxer {
    private static final int VIDEO_STREAM = 0, AUDIO_STREAM = 1;
    /** How long to wait on one stream when neither has anything ready. */
    private static final int WAIT_MILLIS = 10;
    private static final double FALLBACK_FRAME_RATE = 30;

    private final String name;
    private final GstPlayback playback;
    private final GstPlayback.Format format;
    private final MediaInfo info;
    private Packet videoHead, audioHead; // pulled from the pipeline, not yet handed out
    private boolean videoDone, audioDone;
    private double videoClock, audioClock;       // when the next picture or sound is due, if the pipeline gives no time
    private double lastVideoPts = -1, lastAudioPts = -1;
    private boolean closed;

    static boolean available() {
        return GStreamer.available();
    }

    LinuxMediaSource(File file) throws IOException {
        this(file, new GstPipeline(file));
    }

    LinuxMediaSource(File file, GstPlayback playback) {
        this.name = file.getName();
        this.playback = playback;
        this.format = playback.format();
        if (format.width() <= 0 || format.height() <= 0) {
            playback.close();
            throw new UnsupportedOperationException("Unsupported video format: " + name + " reports no picture size");
        }
        info = new MediaInfo(format.width(), format.height(), format.frameRate(), format.duration(),
                codecName(format.videoCodec(), true), format.audioChannels(), format.audioRate(),
                format.audioChannels() > 0 ? codecName(format.audioCodec(), false) : null, "OS (GStreamer)");
        audioDone = format.audioChannels() <= 0;
    }

    /**
     * A short name for the codec description GStreamer puts in a stream's tags ("H.264 / AVC", "MPEG-4 AAC").
     */
    static String codecName(String tag, boolean video) {
        if (tag == null || tag.isBlank()) return video ? "Video" : "Audio";
        String t = tag.toLowerCase(Locale.ROOT);
        if (video) {
            if (t.contains("264") || t.contains("avc")) return "H.264";
            if (t.contains("265") || t.contains("hevc")) return "H.265";
            if (t.contains("vp9")) return "VP9";
            if (t.contains("vp8")) return "VP8";
            if (t.contains("av1")) return "AV1";
            if (t.contains("jpeg")) return "MJPEG";
            if (t.contains("theora")) return "Theora";
            if (t.contains("windows media") || t.contains("wmv")) return "WMV";
            if (t.contains("mpeg-4") || t.contains("mpeg4")) return "MPEG-4";
            return tag.trim();
        }
        if (t.contains("aac")) return "AAC";
        if (t.contains("layer 3") || t.contains("mp3")) return "MP3";
        if (t.contains("opus")) return "Opus";
        if (t.contains("vorbis")) return "Vorbis";
        if (t.contains("flac")) return "FLAC";
        if (t.contains("ac-3") || t.contains("ac3") || t.contains("dolby digital")) return "AC-3";
        if (t.contains("windows media") || t.contains("wma")) return "WMA";
        if (t.contains("pcm") || t.contains("uncompressed")) return "PCM";
        return tag.trim();
    }

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
        return format.audioChannels() > 0 ? AUDIO_STREAM : -1;
    }

    @Override
    public void useBuffers(Buffers buffers) {
        playback.useBuffers(buffers);
    }

    @Override
    public FrameDecoder videoDecoder() {
        return packet -> new VideoFrame(format.width(), format.height(), packet.data(), packet.pts());
    }

    @Override
    public AudioTrackDecoder audioDecoder() {
        return format.audioChannels() > 0 ? new PcmAudioDecoder(format.audioChannels(), format.audioRate(), 16, false) : null;
    }

    // ------------------------------------------------------------------ packets

    @Override
    public Packet next() throws IOException {
        for (; ; ) {
            if (videoHead == null && !videoDone) {
                GstPlayback.Sample s = playback.pullVideo(0);
                if (s != null) videoHead = videoPacket(s);
                else if (playback.videoEnded()) videoDone = true;
            }
            if (audioHead == null && !audioDone) {
                GstPlayback.Sample s = playback.pullAudio(0);
                if (s != null) audioHead = audioPacket(s);
                else if (playback.audioEnded()) audioDone = true;
            }
            if (videoHead != null || audioHead != null) {
                boolean video = audioHead == null || (videoHead != null && videoHead.pts() <= audioHead.pts());
                Packet out = video ? videoHead : audioHead;
                if (video) videoHead = null;
                else audioHead = null;
                return out;
            }
            if (videoDone && audioDone) return null;
            // Nothing is ready: wait a moment on each stream that is still going, then look again.
            if (!videoDone) {
                GstPlayback.Sample s = playback.pullVideo(WAIT_MILLIS);
                if (s != null) {
                    videoHead = videoPacket(s);
                    continue;
                }
                if (playback.videoEnded()) videoDone = true;
            }
            if (!audioDone) {
                GstPlayback.Sample s = playback.pullAudio(WAIT_MILLIS);
                if (s != null) {
                    audioHead = audioPacket(s);
                    continue;
                }
                if (playback.audioEnded()) audioDone = true;
            }
            String failure = playback.failure();
            if (failure != null) throw new IOException("GStreamer stopped reading " + name + " (" + failure + ")");
            if (closed) return null;
        }
    }

    private Packet videoPacket(GstPlayback.Sample s) {
        double pts = s.pts() > lastVideoPts ? s.pts() : videoClock;
        lastVideoPts = pts;
        videoClock = pts + 1 / (format.frameRate() > 0 ? format.frameRate() : FALLBACK_FRAME_RATE);
        return new Packet(VIDEO_STREAM, pts, true, s.data());
    }

    private Packet audioPacket(GstPlayback.Sample s) {
        double pts = s.pts() > lastAudioPts ? s.pts() : audioClock;
        lastAudioPts = pts;
        audioClock = pts + s.data().length / (2.0 * format.audioChannels() * format.audioRate());
        return new Packet(AUDIO_STREAM, pts, true, s.data());
    }

    @Override
    public void seek(double seconds) throws IOException {
        playback.seek(seconds);
        videoHead = audioHead = null;
        videoDone = false;
        audioDone = format.audioChannels() <= 0;
        videoClock = audioClock = Math.max(0, seconds);
        lastVideoPts = lastAudioPts = -1;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        playback.close();
    }
}
