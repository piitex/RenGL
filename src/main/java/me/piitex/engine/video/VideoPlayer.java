package me.piitex.engine.video;

import me.piitex.engine.audio.Audio;
import me.piitex.engine.audio.AudioChannel;
import me.piitex.engine.audio.PcmStream;
import me.piitex.engine.audio.Playback;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.ImageInfo;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plays a video file. It streams from disk through a background decode thread, holding only about a second of
 * decoded pictures and sound at a time, so a file of tens of gigabytes costs no more memory than a short clip, and
 * seeking never reads the whole file.
 * <pre>{@code
 * VideoPlayer video = VideoPlayer.open("assets/intro.avi");
 * video.onFinish(() -> startGame());
 *
 * // every frame, on the render thread:
 * video.update(delta);
 * renderer.drawImage(video.getFrame(), x, y, w, h, 0f, 1f);
 * }</pre>
 * Most code uses {@link me.piitex.engine.ui.overlays.VideoOverlay}, which owns a player and draws it as an
 * element, and reaches this class through {@code getPlayer()} for the controls.
 * <p>
 * Like {@link me.piitex.engine.ui.image.ImagePlayer}, a new player is already playing, and it runs on the time it
 * is given: {@link #update} moves it on. While the soundtrack is playing, the sound card's own position is the
 * clock instead, so the picture stays locked to the audio. Callbacks run inside {@link #update}. The control
 * methods and {@link #update} belong to the render thread.
 * <p>
 * Close it with {@link #close()} when done: that stops the decode thread and releases the file.
 *
 * <h2>Formats</h2>
 * AVI (including files over 4 GB, OpenDML) with Motion JPEG or uncompressed RGB video and PCM audio. The format is
 * recognised from the file's bytes, not its extension. See {@code docs/video} for what is and is not supported.
 */
public final class VideoPlayer implements AutoCloseable {
    private static final Logger log = LogManager.getLogger("Video");

    private static final int MIN_QUEUED = 4;            // always decode at least this many pictures ahead
    private static final int MAX_QUEUED = 48;
    private static final long MAX_QUEUED_BYTES = 192L << 20;
    private static final int AUDIO_PIECE = 4096;        // sample frames per audio device buffer; it keeps four queued
    private static final double AUDIO_AHEAD = 1.5;      // seconds of sound kept decoded, at least

    private final File file;
    private final MediaInfo info;
    private final Buffers buffers = new Buffers();
    private final boolean useAudio;
    private final int audioChannels, audioRate;

    // --- state shared with the decode thread, guarded by lock
    private final Object lock = new Object();
    private final ArrayDeque<VideoFrame> frames = new ArrayDeque<>();
    private long frameBytes;
    private final ArrayDeque<short[]> audioChunks = new ArrayDeque<>();
    private int audioOffset;
    private long audioShorts;
    private double audioBase = Double.NaN; // media time of the first sample queued since the last seek
    private boolean eof, closed;
    private double seekRequest = -1;
    private int generation;
    private Throwable failure;

    private final Thread thread;

    // --- render thread
    private final List<Runnable> finishListeners = new ArrayList<>();
    private final List<Runnable> loopListeners = new ArrayList<>();
    private final List<Consumer<Throwable>> errorListeners = new ArrayList<>();
    private double clock;
    private boolean playing = true, finished, needFirst = true, buffering, failureReported;
    private int loopCount = 1, loopsLeft = 0;
    private float speed = 1f, volume = 1f;
    private boolean muted;
    private AudioChannel channel = AudioChannel.SFX;
    private Image image;
    private double shownPts = -1;
    private Playback playback;
    private boolean audioStarted;
    private boolean disposed;

    private VideoPlayer(File file, Demuxer demuxer) {
        this.file = file;
        this.info = demuxer.info();
        FrameDecoder videoDecoder = demuxer.videoDecoder();
        videoDecoder.useBuffers(buffers);
        AudioTrackDecoder audioDecoder = demuxer.audioDecoder();
        if (audioDecoder != null && !Audio.isAvailable()) {
            audioDecoder.close();
            audioDecoder = null;
        }
        useAudio = audioDecoder != null;
        audioChannels = useAudio ? audioDecoder.channels() : 0;
        audioRate = useAudio ? audioDecoder.sampleRate() : 0;
        Decoding decoding = new Decoding(demuxer, videoDecoder, audioDecoder);
        thread = new Thread(decoding, "RenGL-Video-" + file.getName());
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Opens a video file and starts playing it. The file is read as it plays, never loaded whole.
     *
     * @throws java.io.UncheckedIOException     if the file cannot be found or read
     * @throws UnsupportedOperationException    if the format or codec is not supported
     */
    public static VideoPlayer open(File file) {
        Demuxer demuxer = MediaFiles.open(file);
        try {
            return new VideoPlayer(file, demuxer);
        } catch (RuntimeException e) {
            demuxer.close();
            throw e;
        }
    }

    public static VideoPlayer open(String path) {
        return open(new File(path));
    }

    // ------------------------------------------------------------------ what the file is

    public File getFile() {
        return file;
    }

    public MediaInfo getInfo() {
        return info;
    }

    public int getWidth() {
        return info.width();
    }

    public int getHeight() {
        return info.height();
    }

    /**
     * Length in seconds, or 0 when the file does not say.
     */
    public double getDuration() {
        return info.duration();
    }

    public double getFrameRate() {
        return info.frameRate();
    }

    public boolean hasAudio() {
        return useAudio;
    }

    // ------------------------------------------------------------------ playback

    /**
     * Starts or resumes playing. After the end of a video this starts it again from the beginning.
     */
    public void play() {
        if (finished) {
            seek(0);
            finished = false;
            loopsLeft = loopCount == 0 ? -1 : loopCount - 1;
        }
        playing = true;
        if (playback != null) playback.resume();
    }

    /**
     * Holds on the current picture and silences the sound; {@link #play()} carries on from there.
     */
    public void pause() {
        playing = false;
        if (playback != null) playback.pause();
    }

    /**
     * Pauses and goes back to the first frame.
     */
    public void stop() {
        playing = false;
        finished = false;
        loopsLeft = loopCount == 0 ? -1 : loopCount - 1;
        seek(0);
    }

    public boolean isPlaying() {
        return playing;
    }

    /**
     * True once a video that plays a limited number of times has reached its end.
     */
    public boolean isFinished() {
        return finished;
    }

    /**
     * True while playback is waiting for the decode thread to catch up. A fast disk and codec never show this.
     */
    public boolean isBuffering() {
        return buffering;
    }

    /**
     * Jumps to {@code seconds}, clamped to the video's length. It does not start or stop playback. The picture
     * changes as soon as the frame at the new position has been decoded, which takes a moment on large files.
     */
    public void seek(double seconds) {
        double max = info.duration() > 0 ? info.duration() : Double.MAX_VALUE;
        double target = Math.clamp(Double.isNaN(seconds) ? 0 : seconds, 0, max);
        synchronized (lock) {
            seekRequest = target;
            generation++;
            clearQueues();
            eof = false;
            lock.notifyAll();
        }
        clock = target;
        needFirst = true;
        buffering = false;
        stopAudio();
        if (finished && (info.duration() <= 0 || target < info.duration())) {
            finished = false;
            loopsLeft = 0;
        }
    }

    public void setProgress(float progress) {
        seek(progress * info.duration());
    }

    /**
     * Seconds into the video.
     */
    public double getPosition() {
        return clock;
    }

    /**
     * How far through the video playback is, 0 to 1; 0 when the length is unknown.
     */
    public float getProgress() {
        return info.duration() > 0 ? (float) Math.min(1, clock / info.duration()) : 0f;
    }

    /**
     * How many times to play in total, counting the first: 1 (the default) plays once, 3 plays three times, and 0
     * plays forever.
     */
    public void setLoopCount(int count) {
        loopCount = Math.max(0, count);
        if (!finished) loopsLeft = loopCount == 0 ? -1 : loopCount - 1;
    }

    public int getLoopCount() {
        return loopCount;
    }

    /**
     * Shorthand for {@link #setLoopCount}: true loops forever, false plays once.
     */
    public void setLooping(boolean looping) {
        setLoopCount(looping ? 0 : 1);
    }

    public boolean isLooping() {
        return loopCount == 0;
    }

    /**
     * How fast it plays, 1 by default, from 0.1 to 4. The soundtrack changes pitch with the speed.
     */
    public void setSpeed(float speed) {
        if (!Float.isFinite(speed)) throw new IllegalArgumentException("speed must be a number: " + speed);
        this.speed = Math.clamp(speed, 0.1f, 4f);
        if (playback != null) playback.pitch(this.speed);
    }

    public float getSpeed() {
        return speed;
    }

    /**
     * The soundtrack's own volume (0 silent, 1 full), combined with its channel and the master volume.
     */
    public void setVolume(float volume) {
        this.volume = Math.max(0f, volume);
        applyVolume();
    }

    public float getVolume() {
        return volume;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        applyVolume();
    }

    public boolean isMuted() {
        return muted;
    }

    /**
     * The audio channel the soundtrack plays on, {@link AudioChannel#SFX} by default. Takes effect the next time
     * the sound starts (after a seek, say), so set it right after opening.
     */
    public void setChannel(AudioChannel channel) {
        this.channel = channel == null ? AudioChannel.SFX : channel;
    }

    private void applyVolume() {
        if (playback != null) playback.volume(muted ? 0f : volume);
    }

    public VideoPlayer onFinish(Runnable listener) {
        finishListeners.add(listener);
        return this;
    }

    /**
     * Calls {@code listener} each time a looping video wraps from the end back to the start.
     */
    public VideoPlayer onLoop(Runnable listener) {
        loopListeners.add(listener);
        return this;
    }

    /**
     * Calls {@code listener} if the decode thread fails in a way playback cannot recover from. Playback pauses.
     */
    public VideoPlayer onError(Consumer<Throwable> listener) {
        errorListeners.add(listener);
        return this;
    }

    /**
     * The error that stopped the decode thread, or null.
     */
    public Throwable getError() {
        synchronized (lock) {
            return failure;
        }
    }

    // ------------------------------------------------------------------ per frame

    /**
     * Moves playback on by {@code delta} seconds and picks the picture to show. Call it once per frame on the render
     * thread.
     */
    public void update(float delta) {
        if (disposed) return;
        reportFailure();
        startAudioIfReady();
        advanceClock(delta);
        pickFrame();
        checkEnd();
    }

    private void reportFailure() {
        Throwable t = getError();
        if (t == null || failureReported) return;
        failureReported = true;
        playing = false;
        if (playback != null) playback.pause();
        for (var l : List.copyOf(errorListeners)) l.accept(t);
    }

    private void startAudioIfReady() {
        if (!useAudio || audioStarted || !playing) return;
        boolean ready;
        synchronized (lock) {
            ready = audioShorts >= 4L * AUDIO_PIECE * audioChannels || eof && audioShorts > 0;
        }
        if (!ready) return;
        int gen;
        synchronized (lock) {
            gen = generation;
        }
        playback = Audio.playStream(new RingStream(gen), channel);
        playback.pitch(speed);
        applyVolume();
        audioStarted = true;
    }

    private void advanceClock(float delta) {
        if (!playing || finished || !(delta > 0f) || !Float.isFinite(delta)) return;
        if (playback != null && !playback.isFinished()) {
            double base;
            synchronized (lock) {
                base = audioBase;
            }
            if (!Double.isNaN(base)) clock = base + playback.getPlayedSeconds();
            buffering = false;
            return;
        }
        if (useAudio && !audioStarted) { // waiting for the first second of sound to be ready
            synchronized (lock) {
                buffering = !eof;
                if (!eof || audioShorts > 0) return;
            }
        }
        synchronized (lock) { // the picture alone drives the clock; hold still while the decoder is behind
            if (frames.isEmpty() && !eof) {
                buffering = true;
                return;
            }
        }
        buffering = false;
        clock += delta * speed;
    }

    private void pickFrame() {
        VideoFrame show = null;
        synchronized (lock) {
            while (!frames.isEmpty()) {
                VideoFrame f = frames.peek();
                if (!needFirst && f.pts() > clock + 1e-9) break;
                if (show != null) buffers.give(show.pixels());
                show = frames.poll();
                frameBytes -= show.pixels().length;
                needFirst = false;
            }
            if (show != null) lock.notifyAll();
        }
        if (show == null) return;
        Image next = Image.Companion.makeRaster(ImageInfo.Companion.makeN32Premul(show.width(), show.height()),
                show.pixels(), show.width() * 4);
        buffers.give(show.pixels());
        if (image != null) image.close();
        image = next;
        shownPts = show.pts();
    }

    private void checkEnd() {
        if (!playing || finished) return;
        boolean drained;
        synchronized (lock) {
            drained = eof && frames.isEmpty() && audioShorts == 0;
        }
        if (!drained || needFirst) return;
        if (playback != null && !playback.isFinished()) return;
        double frameTime = info.frameRate() > 0 ? 1 / info.frameRate() : 0;
        if (playback == null && clock < shownPts + frameTime - 1e-6) return;

        if (loopsLeft != 0) {
            if (loopsLeft > 0) loopsLeft--;
            seek(0);
            for (var l : List.copyOf(loopListeners)) l.run();
            return;
        }
        playing = false;
        finished = true;
        stopAudio();
        for (var l : List.copyOf(finishListeners)) l.run();
    }

    /**
     * The picture to show now, or null before the first frame has been decoded. It is replaced as playback moves on,
     * so draw it right away instead of keeping it.
     */
    public Image getFrame() {
        return image;
    }

    /**
     * Media time, in seconds, of the picture showing now.
     */
    public double getFrameTime() {
        return Math.max(0, shownPts);
    }

    // ------------------------------------------------------------------ cleanup

    private void stopAudio() {
        if (playback != null) {
            playback.stop();
            playback = null;
        }
        audioStarted = false;
    }

    /**
     * Stops the decode thread, silences the sound and releases the file. The player cannot be used afterwards.
     */
    @Override
    public void close() {
        if (disposed) return;
        disposed = true;
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
        stopAudio();
        if (image != null) {
            image.close();
            image = null;
        }
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ shared queues (lock held)

    /** How many decoded pictures are waiting. For tests, to check the queue stays bounded. */
    int queuedFrames() {
        synchronized (lock) {
            return frames.size();
        }
    }

    private void clearQueues() {
        for (VideoFrame f : frames) buffers.give(f.pixels());
        frames.clear();
        frameBytes = 0;
        audioChunks.clear();
        audioOffset = 0;
        audioShorts = 0;
        audioBase = Double.NaN;
    }

    private boolean wantsData() {
        if (eof) return false;
        boolean video = frames.size() < MIN_QUEUED || frames.size() < MAX_QUEUED && frameBytes < MAX_QUEUED_BYTES;
        boolean audio = useAudio && audioShorts < Math.max(AUDIO_AHEAD * audioRate, 6.0 * AUDIO_PIECE) * audioChannels;
        return video || audio;
    }

    /**
     * The soundtrack as the audio thread sees it. Reads what the decode thread has queued, and plays silence over a
     * gap rather than ending, until the file really has no more sound.
     */
    private final class RingStream implements PcmStream {
        private final int gen;

        RingStream(int gen) {
            this.gen = gen;
        }

        @Override
        public int channels() {
            return audioChannels;
        }

        @Override
        public int sampleRate() {
            return audioRate;
        }

        @Override
        public int bufferFrames() {
            return AUDIO_PIECE;
        }

        @Override
        public int read(short[] dst, int off, int len) {
            synchronized (lock) {
                if (gen != generation || closed) return 0; // a seek or close replaced this stream
                int n = 0;
                while (n < len && !audioChunks.isEmpty()) {
                    short[] chunk = audioChunks.peek();
                    int take = Math.min(len - n, chunk.length - audioOffset);
                    System.arraycopy(chunk, audioOffset, dst, off + n, take);
                    n += take;
                    audioOffset += take;
                    if (audioOffset == chunk.length) {
                        audioChunks.poll();
                        audioOffset = 0;
                    }
                }
                audioShorts -= n;
                if (n > 0) {
                    lock.notifyAll();
                    return n;
                }
                if (eof) return 0;
                int silence = Math.min(len, audioChannels * 512);
                Arrays.fill(dst, off, off + silence, (short) 0);
                return silence;
            }
        }

        @Override
        public void seek(long frame) {
        }

        @Override
        public void close() {
        }
    }

    // ------------------------------------------------------------------ the decode thread

    private final class Decoding implements Runnable {
        private final Demuxer demuxer;
        private final FrameDecoder videoDecoder;
        private final AudioTrackDecoder audioDecoder;
        private int brokenFrames;

        Decoding(Demuxer demuxer, FrameDecoder videoDecoder, AudioTrackDecoder audioDecoder) {
            this.demuxer = demuxer;
            this.videoDecoder = videoDecoder;
            this.audioDecoder = audioDecoder;
        }

        @Override
        public void run() {
            double floor = 0;          // pictures and sound before this were skipped over after a seek
            VideoFrame held = null;    // the latest picture before the floor, shown if nothing else qualifies
            int gen;
            try {
                synchronized (lock) {
                    gen = generation;
                }
                while (true) {
                    double seekTo;
                    synchronized (lock) {
                        while (!closed && seekRequest < 0 && !wantsData()) lock.wait();
                        if (closed) return;
                        seekTo = seekRequest;
                        seekRequest = -1;
                        gen = generation;
                    }
                    if (seekTo >= 0) {
                        demuxer.seek(seekTo);
                        videoDecoder.flush();
                        if (audioDecoder != null) audioDecoder.flush();
                        floor = seekTo;
                        held = null;
                        continue;
                    }
                    Packet packet = readPacket();
                    if (packet == null) {
                        if (held != null) {
                            if (!offer(held, gen)) buffers.give(held.pixels());
                            held = null;
                        }
                        synchronized (lock) {
                            if (gen == generation) eof = true;
                            lock.notifyAll();
                        }
                        continue;
                    }
                    if (packet.stream() == demuxer.videoStream()) {
                        VideoFrame frame = decodeVideo(packet);
                        if (frame == null) continue;
                        if (frame.pts() < floor - 1e-6) {
                            if (held != null) buffers.give(held.pixels());
                            held = frame;
                            continue;
                        }
                        if (held != null) {
                            if (!offer(held, gen)) buffers.give(held.pixels());
                            held = null;
                        }
                        if (!offer(frame, gen)) buffers.give(frame.pixels());
                    } else if (audioDecoder != null && packet.stream() == demuxer.audioStream()) {
                        decodeAudio(packet, floor, gen);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                log.error("Video decoding failed for {}", file, t);
                synchronized (lock) {
                    failure = t;
                    eof = true;
                }
            } finally {
                videoDecoder.close();
                if (audioDecoder != null) audioDecoder.close();
                demuxer.close();
            }
        }

        /** A file that ends early or is damaged part way plays up to that point instead of failing. */
        private Packet readPacket() {
            try {
                return demuxer.next();
            } catch (java.io.IOException e) {
                log.warn("Reading {} stopped early: {}", file.getName(), e.toString());
                return null;
            }
        }

        private VideoFrame decodeVideo(Packet packet) {
            try {
                return videoDecoder.decode(packet);
            } catch (java.io.IOException | RuntimeException e) {
                if (brokenFrames++ < 5) log.warn("Skipping a frame of {} at {}s: {}", file.getName(), packet.pts(), e.toString());
                return null;
            }
        }

        private void decodeAudio(Packet packet, double floor, int gen) throws InterruptedException {
            short[] pcm;
            try {
                pcm = audioDecoder.decode(packet);
            } catch (java.io.IOException | RuntimeException e) {
                if (brokenFrames++ < 5) log.warn("Skipping audio of {} at {}s: {}", file.getName(), packet.pts(), e.toString());
                return;
            }
            double pts = packet.pts();
            int ch = audioDecoder.channels();
            double rate = audioDecoder.sampleRate();
            double length = pcm.length / (double) ch / rate;
            if (pts + length <= floor) return;
            if (pts < floor) { // trim the part before the seek target
                int skipFrames = (int) Math.round((floor - pts) * rate);
                pcm = Arrays.copyOfRange(pcm, Math.min(pcm.length, skipFrames * ch), pcm.length);
                pts = floor;
            }
            if (pcm.length == 0) return;
            synchronized (lock) {
                if (gen != generation) return;
                if (Double.isNaN(audioBase)) audioBase = pts;
                audioChunks.add(pcm);
                audioShorts += pcm.length;
            }
        }

        /** Queues a finished picture, waiting if the player is far behind. False if a seek made it stale. */
        private boolean offer(VideoFrame frame, int gen) throws InterruptedException {
            synchronized (lock) {
                while (!closed && gen == generation && frames.size() >= MAX_QUEUED * 2) lock.wait();
                if (closed || gen != generation) return false;
                frames.add(frame);
                frameBytes += frame.pixels().length;
                return true;
            }
        }
    }
}
