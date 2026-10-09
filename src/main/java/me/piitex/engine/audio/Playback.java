package me.piitex.engine.audio;

import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.EXTStereoAngles;
import org.lwjgl.openal.SOFTSourceSpatialize;

import java.util.ArrayDeque;
import java.util.Arrays;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET;
import static org.lwjgl.openal.AL11.AL_SEC_OFFSET;

/**
 * One playing instance of a {@link Sound} or {@link Music}. You get one back from every {@code play} call and can
 * use it to adjust or stop that particular playback. Ignoring it is fine for fire-and-forget effects.
 * <p>
 * Setters return the playback so they chain:
 * <pre>{@code
 * Audio.play("sfx/explosion.ogg").volume(0.8f).pitch(0.7f);
 * }</pre>
 * A playback is finished once it ends, is stopped, or the audio device is unavailable. A finished playback
 * ignores every call, so it is always safe to keep a reference or call methods on one that already ended.
 * <p>
 * All methods may be called from any thread. {@link #onFinished} callbacks run on the render thread.
 */
public final class Playback {
    private static final Logger log = LogManager.getLogger("Audio");
    private static final int STREAM_BUFFERS = 4;
    private static final int STREAM_FRAMES = 16384;

    private final Object owner;
    private final AudioChannel channel;
    private final PcmStream stream; // null for a fully loaded Sound
    private int source;
    private int[] streamBuffers;
    private short[] scratch;
    private int format;
    private int queued;
    private boolean streamEnded;
    private final int channels;
    private long loopStart, loopEnd; // stream loop region in sample frames; loopEnd 0 means the end of the file
    private long framePos;           // sample frame the stream has been read up to
    private final ArrayDeque<Integer> queuedFrames = new ArrayDeque<>(); // frames in each queued buffer, oldest first
    private final ArrayDeque<Long> queuedStarts = new ArrayDeque<>(); // the file frame each queued buffer begins at
    private long processedFrames;    // frames in buffers the device has finished with
    private int lastFillFrames;
    private long lastFillStart;
    private int buffer;              // the OpenAL buffer of a loaded Sound

    private float volume = 1f, pitch = 1f, pan = 0f;
    private boolean positional;
    private float px, py, pz, vx, vy, vz;
    private float refDistance = 1f, maxDistance = Float.MAX_VALUE, rolloff = 1f;
    private boolean looping, paused, finished;

    private boolean fading, stopWhenFaded;
    private float fadeFrom, fadeTarget, fadeDuration, fadeElapsed;

    private Runnable onFinished;

    private Playback(Object owner, AudioChannel channel, int channels, PcmStream stream) {
        this.channels = channels;
        this.owner = owner;
        this.channel = channel;
        this.stream = stream;
    }

    /**
     * Everything needed to start a playback.
     *
     * @param buffer   the OpenAL buffer of a loaded {@link Sound}, unused when {@code stream} is set
     * @param stream   the decoder to stream from, or null for a loaded buffer
     * @param position where to place it in the world (x, y, z), or null for a flat, non-3D playback
     */
    record Spec(Object owner, AudioChannel channel, int buffer, int channels, PcmStream stream, float volume,
                float pitch, boolean loop, long loopStart, long loopEnd, float[] position) {
    }

    /**
     * A playback that has already finished, returned when there is no audio device.
     */
    static Playback silent(AudioChannel channel) {
        Playback p = new Playback(null, channel, 1, null);
        p.finished = true;
        return p;
    }

    /**
     * Starts a playback of a loaded buffer ({@code stream == null}) or of a stream. Holds {@link Audio#LOCK}.
     */
    static Playback start(Spec spec) {
        PcmStream stream = spec.stream();
        AudioChannel channel = spec.channel();
        Playback p = new Playback(spec.owner(), channel, spec.channels(), stream);
        p.loopStart = spec.loopStart();
        p.loopEnd = spec.loopEnd();
        p.volume = Math.max(0f, spec.volume());
        p.pitch = Math.max(0.1f, Math.min(4f, spec.pitch()));
        p.looping = spec.loop();
        if (spec.position() != null) {
            p.positional = true;
            p.px = spec.position()[0];
            p.py = spec.position()[1];
            p.pz = spec.position()[2];
        }
        synchronized (Audio.LOCK) {
            if (!Audio.ensureInit()) {
                if (stream != null) stream.close();
                return silent(channel);
            }
            p.source = alGenSources();
            if (alGetError() != AL_NO_ERROR) {
                log.warn("Out of audio sources; dropping a sound.");
                if (stream != null) stream.close();
                return silent(channel);
            }
            float[] attenuation = Audio.defaultAttenuation();
            p.refDistance = attenuation[0];
            p.maxDistance = attenuation[1];
            p.rolloff = attenuation[2];
            p.applyPosition();
            alSourcef(p.source, AL_PITCH, p.pitch);
            p.applyGain();
            if (stream == null) {
                p.buffer = spec.buffer();
                alSourcei(p.source, AL_BUFFER, spec.buffer());
                alSourcei(p.source, AL_LOOPING, spec.loop() ? AL_TRUE : AL_FALSE);
            } else {
                p.format = stream.channels() == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
                p.scratch = new short[(stream.bufferFrames() > 0 ? stream.bufferFrames() : STREAM_FRAMES) * stream.channels()];
                p.streamBuffers = new int[STREAM_BUFFERS];
                for (int i = 0; i < STREAM_BUFFERS; i++) {
                    p.streamBuffers[i] = alGenBuffers();
                    if (p.fill(p.streamBuffers[i])) p.queueFilled(p.streamBuffers[i]);
                }
            }
            alSourcePlay(p.source);
            Audio.register(p);
        }
        return p;
    }

    /**
     * Sets this playback's own volume (0 is silent, 1 is full). Combined with its channel and master volume. Cancels any fade in progress.
     */
    public Playback volume(float volume) {
        synchronized (Audio.LOCK) {
            fading = false;
            stopWhenFaded = false;
            this.volume = Math.max(0f, volume);
            applyGain();
        }
        return this;
    }

    /**
     * This playback's own volume, before channel and master volume.
     */
    public float getVolume() {
        synchronized (Audio.LOCK) {
            return volume;
        }
    }

    /**
     * Sets playback speed and pitch together: 1 is normal, 2 is an octave up and twice as fast. Clamped to 0.1 to 4.
     */
    public Playback pitch(float pitch) {
        synchronized (Audio.LOCK) {
            this.pitch = Math.max(0.1f, Math.min(4f, pitch));
            if (!finished) alSourcef(source, AL_PITCH, this.pitch);
        }
        return this;
    }

    /**
     * The pitch it plays at: 1 unless set with {@link #pitch}, or varied by {@link Sound#pitchVariation}.
     */
    public float getPitch() {
        synchronized (Audio.LOCK) {
            return pitch;
        }
    }

    /**
     * Pans left (-1) to right (+1), 0 is centered. Works for mono and stereo; a stereo image is moved toward the side as a whole,
     * so at -1 both channels come out of the left speaker.
     */
    public Playback pan(float pan) {
        synchronized (Audio.LOCK) {
            this.pan = Math.max(-1f, Math.min(1f, pan));
            positional = false;
            applyPosition();
        }
        return this;
    }

    /**
     * Places this playback in the world so it is heard from that direction and gets quieter with distance, relative to
     * the {@linkplain Audio#listener() listener}. Units are whatever your game uses; see {@link #attenuation}.
     * Replaces any {@link #pan}. Best with a mono sound.
     */
    public Playback position(float x, float y, float z) {
        synchronized (Audio.LOCK) {
            positional = true;
            px = x;
            py = y;
            pz = z;
            applyPosition();
        }
        return this;
    }

    /**
     * {@link #position(float, float, float)} for a 2D game: places it at (x, y) on the plane {@code z = 0}. Keep the
     * listener on the same plane.
     */
    public Playback position(float x, float y) {
        return position(x, y, 0f);
    }

    /**
     * Sets how fast this source is moving, in world units per second. It only matters for the Doppler effect (the pitch
     * shift of a passing source); see {@link Audio#setDopplerFactor}.
     */
    public Playback velocity(float x, float y, float z) {
        synchronized (Audio.LOCK) {
            vx = x;
            vy = y;
            vz = z;
            applyPosition();
        }
        return this;
    }

    /**
     * Sets how a positioned playback fades with distance, using the {@linkplain Audio#setDistanceModel distance model}.
     * Only affects playbacks that have a {@link #position}.
     *
     * @param referenceDistance the distance at which it plays at full volume (default 1)
     * @param maxDistance       the distance beyond which it stops getting quieter (default: unlimited). The linear models reach silence here
     * @param rolloff           how quickly it fades: 1 is the natural rate, 0 disables fading, 2 is twice as fast (default 1)
     */
    public Playback attenuation(float referenceDistance, float maxDistance, float rolloff) {
        synchronized (Audio.LOCK) {
            this.refDistance = Math.max(0f, referenceDistance);
            this.maxDistance = Math.max(this.refDistance, maxDistance);
            this.rolloff = Math.max(0f, rolloff);
            applyPosition();
        }
        return this;
    }

    /**
     * True if this playback has a world {@link #position} rather than being flat or {@linkplain #pan panned}.
     */
    public boolean isPositional() {
        synchronized (Audio.LOCK) {
            return positional;
        }
    }

    /**
     * Turns looping on or off. A looping playback never finishes by itself.
     */
    public Playback loop(boolean loop) {
        synchronized (Audio.LOCK) {
            looping = loop;
            if (!finished && stream == null) alSourcei(source, AL_LOOPING, loop ? AL_TRUE : AL_FALSE);
        }
        return this;
    }

    public boolean isLooping() {
        synchronized (Audio.LOCK) {
            return looping;
        }
    }

    /**
     * How many seconds of a streamed playback the audio device has actually played, counted from where the stream
     * began and ignoring {@link #pitch}. This is the audio clock a video follows to stay in sync. It stops while
     * paused, and is 0 for a loaded {@link Sound} or a finished playback.
     */
    public double getPlayedSeconds() {
        synchronized (Audio.LOCK) {
            if (finished || stream == null) return 0;
            if (alGetSourcei(source, AL_SOURCE_STATE) == AL_STOPPED) {
                // Every queued buffer has been played, but the device reports offset 0 until they are unqueued.
                long all = processedFrames;
                for (int frames : queuedFrames) all += frames;
                return all / (double) stream.sampleRate();
            }
            int inBuffer = alGetSourcei(source, AL_SAMPLE_OFFSET);
            return (processedFrames + inBuffer) / (double) stream.sampleRate();
        }
    }

    /**
     * Where this playback is in its sound, in seconds from the start of the file, ignoring {@link #pitch}. A looping
     * playback reports its place in the current pass. It stops while paused, and is 0 once finished.
     */
    public double getPosition() {
        synchronized (Audio.LOCK) {
            if (finished) return 0;
            if (stream == null) return Math.max(0, alGetSourcef(source, AL_SEC_OFFSET));
            int offset = alGetSourcei(source, AL_SAMPLE_OFFSET);
            if (alGetSourcei(source, AL_SOURCE_STATE) == AL_STOPPED) offset = Integer.MAX_VALUE;
            long frame = framePos;
            long passed = 0;
            var frames = queuedFrames.iterator();
            var starts = queuedStarts.iterator();
            while (frames.hasNext() && starts.hasNext()) {
                int length = frames.next();
                long start = starts.next();
                frame = start + Math.min(length, Math.max(0, offset - passed));
                if (offset < passed + length) break;
                passed += length;
            }
            if (looping && loopEnd > 0 && frame >= loopEnd) frame = loopStart + (frame - loopEnd);
            return frame / (double) stream.sampleRate();
        }
    }

    /**
     * The length of the sound in seconds, or -1 if the file does not say (some streamed MP3s). For a looping playback this
     * is one pass, not forever.
     */
    public double getDuration() {
        synchronized (Audio.LOCK) {
            if (stream != null) {
                long total = stream.totalFrames();
                return total > 0 ? total / (double) stream.sampleRate() : -1;
            }
            if (buffer == 0 || !Audio.isReady()) return -1;
            int bytes = alGetBufferi(buffer, AL_SIZE);
            int rate = alGetBufferi(buffer, AL_FREQUENCY);
            return rate > 0 && channels > 0 ? bytes / (2.0 * channels) / rate : -1;
        }
    }

    /**
     * Jumps to {@code seconds} from the start of the sound, clamped to its length, and carries on from there. A paused
     * playback stays paused at the new place. Seeking a streamed {@link Music} track flushes what was queued, so a sound
     * of a sample-exact format (Ogg Vorbis, Opus, FLAC, WAV) lands on the frame; an MP3 lands close to it. A finished
     * playback ignores it.
     */
    public Playback seek(double seconds) {
        synchronized (Audio.LOCK) {
            if (finished) return this;
            double target = Math.max(0, seconds);
            if (stream == null) {
                double duration = getDuration();
                if (duration > 0) target = Math.min(target, Math.max(0, duration - 0.001));
                alSourcef(source, AL_SEC_OFFSET, (float) target);
                return this;
            }
            long frame = (long) (target * stream.sampleRate());
            long total = stream.totalFrames();
            if (total > 0) frame = Math.min(frame, Math.max(0, total - 1));

            alSourceStop(source);
            for (int left = alGetSourcei(source, AL_BUFFERS_QUEUED); left > 0; left--) alSourceUnqueueBuffers(source);
            queued = 0;
            queuedFrames.clear();
            queuedStarts.clear();
            processedFrames = 0;
            streamEnded = false;
            stream.seek(frame);
            framePos = frame;
            for (int b : streamBuffers) {
                if (!streamEnded && fill(b)) queueFilled(b);
            }
            if (queued == 0) {
                finish();
                return this;
            }
            alSourcePlay(source);
            if (paused) alSourcePause(source);
        }
        return this;
    }

    /**
     * Pauses, keeping the position. Does nothing if already paused or finished.
     */
    public Playback pause() {
        synchronized (Audio.LOCK) {
            if (!finished && !paused) {
                paused = true;
                alSourcePause(source);
            }
        }
        return this;
    }

    /**
     * Continues after {@link #pause()}.
     */
    public Playback resume() {
        synchronized (Audio.LOCK) {
            if (!finished && paused) {
                paused = false;
                alSourcePlay(source);
            }
        }
        return this;
    }

    /**
     * Stops immediately and finishes this playback.
     */
    public void stop() {
        synchronized (Audio.LOCK) {
            finish();
        }
    }

    /**
     * Fades the volume to zero over {@code seconds}, then stops.
     */
    public void stop(float seconds) {
        synchronized (Audio.LOCK) {
            if (finished) return;
            if (seconds <= 0f) {
                finish();
                return;
            }
            startFade(0f, seconds);
            stopWhenFaded = true;
        }
    }

    /**
     * Fades this playback's volume to {@code target} over {@code seconds}.
     */
    public Playback fadeTo(float target, float seconds) {
        synchronized (Audio.LOCK) {
            if (finished) return this;
            if (seconds <= 0f) return volume(target);
            startFade(Math.max(0f, target), seconds);
            stopWhenFaded = false;
        }
        return this;
    }

    /**
     * Runs {@code action} on the render thread once this playback ends for any reason, including {@link #stop()}. Replaces any earlier listener.
     */
    public Playback onFinished(Runnable action) {
        synchronized (Audio.LOCK) {
            if (finished) {
                if (action != null) Scheduler.runLater(action);
            } else {
                onFinished = action;
            }
        }
        return this;
    }

    /**
     * True while audible: neither paused nor finished.
     */
    public boolean isPlaying() {
        synchronized (Audio.LOCK) {
            return !finished && !paused;
        }
    }

    public boolean isPaused() {
        synchronized (Audio.LOCK) {
            return !finished && paused;
        }
    }

    public boolean isFinished() {
        synchronized (Audio.LOCK) {
            return finished;
        }
    }

    /**
     * The channel this playback belongs to.
     */
    public AudioChannel getChannel() {
        return channel;
    }


    Object owner() {
        return owner;
    }

    private void startFade(float target, float seconds) {
        fadeFrom = volume;
        fadeTarget = target;
        fadeDuration = seconds;
        fadeElapsed = 0f;
        fading = true;
    }

    void applyGain() {
        if (!finished) alSourcef(source, AL_GAIN, volume * Audio.channelGain(channel));
    }

    private void applyPosition() {
        if (finished) return;
        boolean spatialize = AL.getCapabilities().AL_SOFT_source_spatialize;
        if (positional) {
            alSourcei(source, AL_SOURCE_RELATIVE, AL_FALSE);
            alSource3f(source, AL_POSITION, px, py, pz);
            alSource3f(source, AL_VELOCITY, vx, vy, vz);
            alSourcef(source, AL_REFERENCE_DISTANCE, refDistance);
            alSourcef(source, AL_MAX_DISTANCE, maxDistance);
            alSourcef(source, AL_ROLLOFF_FACTOR, rolloff);
            if (channels > 1) {
                // A stereo file is placed in the world as two speakers, like a mono one, but keeps its own spread.
                if (spatialize) alSourcei(source, SOFTSourceSpatialize.AL_SOURCE_SPATIALIZE_SOFT, AL_TRUE);
                if (AL.getCapabilities().AL_EXT_STEREO_ANGLES) {
                    alSourcefv(source, EXTStereoAngles.AL_STEREO_ANGLES, new float[]{(float) (Math.PI / 6), (float) (-Math.PI / 6)});
                }
            }
            return;
        }
        // Flat: fixed to the listener, so it never fades with distance or follows the listener's turning.
        alSourcei(source, AL_SOURCE_RELATIVE, AL_TRUE);
        alSource3f(source, AL_VELOCITY, 0f, 0f, 0f);
        alSourcef(source, AL_ROLLOFF_FACTOR, 0f);
        if (spatialize)
            alSourcei(source, SOFTSourceSpatialize.AL_SOURCE_SPATIALIZE_SOFT, SOFTSourceSpatialize.AL_AUTO_SOFT);
        if (channels == 1) {
            alSource3f(source, AL_POSITION, pan, 0f, -(float) Math.sqrt(1f - pan * pan));
        } else {
            alSource3f(source, AL_POSITION, 0f, 0f, 0f);
            if (AL.getCapabilities().AL_EXT_STEREO_ANGLES) {
                // Both speakers of the stereo image rotate together; 60 degrees is where the whole image sits on one side.
                double centre = -pan * Math.PI / 3;
                double half = Math.PI / 6;
                alSourcefv(source, EXTStereoAngles.AL_STEREO_ANGLES, new float[]{(float) (centre + half), (float) (centre - half)});
            }
        }
    }

    /**
     * Queues a buffer {@link #fill} has just filled.
     */
    private void queueFilled(int buffer) {
        alSourceQueueBuffers(source, buffer);
        queuedFrames.add(lastFillFrames);
        queuedStarts.add(lastFillStart);
        queued++;
    }

    /**
     * Refills one stream buffer. Returns false, and marks the stream ended, if there was nothing left to read.
     */
    private boolean fill(int buffer) {
        int n = 0;
        boolean rewound = false;
        while (n < scratch.length) {
            if (looping && loopEnd > 0 && framePos >= loopEnd) {
                stream.seek(loopStart);
                framePos = loopStart;
            }
            if (n == 0) lastFillStart = framePos;
            int want = scratch.length - n;
            if (looping && loopEnd > 0) want = (int) Math.min(want, (loopEnd - framePos) * stream.channels());
            int r = stream.read(scratch, n, want);
            if (r > 0) {
                n += r;
                framePos += r / stream.channels();
                rewound = false;
            } else if (looping && !rewound) {
                stream.seek(loopStart);
                framePos = loopStart;
                if (n == 0) lastFillStart = framePos;
                rewound = true;
            } else {
                break;
            }
        }
        if (n == 0) {
            streamEnded = true;
            return false;
        }
        alBufferData(buffer, format, n == scratch.length ? scratch : Arrays.copyOf(scratch, n), stream.sampleRate());
        lastFillFrames = n / stream.channels();
        return true;
    }

    /**
     * Called about every 15 ms by the audio thread.
     */
    void update(float dt) {
        if (finished) return;
        if (fading) {
            fadeElapsed += dt;
            float t = Math.min(1f, fadeElapsed / fadeDuration);
            volume = fadeFrom + (fadeTarget - fadeFrom) * t;
            applyGain();
            if (t >= 1f) {
                fading = false;
                if (stopWhenFaded) {
                    finish();
                    return;
                }
            }
        }
        if (paused) return;
        int state = alGetSourcei(source, AL_SOURCE_STATE);
        if (stream != null) {
            int processed = alGetSourcei(source, AL_BUFFERS_PROCESSED);
            while (processed-- > 0) {
                int buffer = alSourceUnqueueBuffers(source);
                queued--;
                Integer done = queuedFrames.poll();
                if (done != null) processedFrames += done;
                queuedStarts.poll();
                if (!streamEnded && fill(buffer)) queueFilled(buffer);
            }
            if (queued <= 0) {
                finish();
            } else if (state != AL_PLAYING) {
                alSourcePlay(source); // the buffers ran dry for a moment
            }
        } else if (state == AL_STOPPED) {
            finish();
        }
    }

    /**
     * Releases the source and fires the finish callback. Safe to call twice.
     */
    void finish() {
        if (finished) return;
        finished = true;
        alSourceStop(source);
        alSourcei(source, AL_BUFFER, 0);
        alDeleteSources(source);
        if (streamBuffers != null) {
            for (int b : streamBuffers) alDeleteBuffers(b);
        }
        if (stream != null) stream.close();
        Audio.unregister(this);
        Runnable callback = onFinished;
        onFinished = null;
        if (callback != null) Scheduler.runLater(callback);
    }
}
