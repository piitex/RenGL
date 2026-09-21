package me.piitex.engine.audio;

import me.piitex.engine.io.AssetManager;
import me.piitex.engine.utils.flags.GeneratedClass;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.SOFTLoopPoints;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.alBufferiv;

/**
 * A short sound effect, fully decoded and held in memory so it starts instantly and can be played many times at
 * once. Use it for clicks, footsteps, explosions and voice lines. For anything long, such as a music track, use
 * {@link Music}, which streams instead.
 * <pre>{@code
 * Sound click = Sound.load("sfx/click.ogg").channel(AudioChannel.UI);
 * button.onAction(click::play);
 * }</pre>
 * Supported files are {@code .wav} (8, 16, 24 and 32-bit PCM, or 32-bit float), {@code .ogg} (Vorbis), {@code .opus}, {@code .flac} and {@code .mp3}, mono
 * or stereo. Paths are looked up on disk first, then on the classpath. If there is no audio device the sound
 * loads as a silent stand-in and every play does nothing.
 * <p>
 * {@link Audio#play(String)} does the loading and caching for you when you do not need to hold the sound.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5", reviewed = false)
public final class Sound implements AutoCloseable {
    private static final Logger log = LogManager.getLogger("Audio");

    private final String name;
    private final int buffer; // 0 when there is no audio device
    private final float duration;
    private final int channels, rate, frames;
    private AudioChannel channel = AudioChannel.SFX;
    private boolean closed;

    private Sound(String name, Pcm pcm) {
        this.name = name;
        if (pcm == null) {
            buffer = 0;
            duration = 0f;
            channels = 1;
            rate = frames = 0;
            return;
        }
        duration = pcm.duration();
        channels = pcm.channels();
        rate = pcm.sampleRate();
        frames = pcm.frames();
        synchronized (Audio.LOCK) {
            buffer = alGenBuffers();
            alBufferData(buffer, pcm.channels() == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16, pcm.samples(), pcm.sampleRate());
        }
    }

    /**
     * Loads a sound from a file path or classpath resource.
     *
     * @throws java.io.UncheckedIOException     if the file cannot be found or read
     * @throws IllegalArgumentException if the format is unsupported or the file is corrupt
     */
    public static Sound load(String path) {
        return create(path, AudioDecoder.read(path));
    }

    /** Loads a sound through an {@link AssetManager}, so it can come out of a mounted {@code .pak}. */
    public static Sound load(AssetManager assets, String name) {
        return create(name, AudioDecoder.read(assets, name));
    }

    private static Sound create(String name, byte[] bytes) {
        // With no device there is nothing to upload to, so skip the decode work.
        return new Sound(name, Audio.isAvailable() ? AudioDecoder.decode(name, bytes) : null);
    }

    /**
     * Generates a sine tone, handy for placeholders and tests when you have no audio files yet.
     *
     * @param frequency pitch in hertz, for example 440 for concert A
     * @param seconds   length of the tone
     */
    public static Sound tone(float frequency, float seconds) {
        int rate = 44100;
        short[] samples = new short[Math.max(1, (int) (seconds * rate))];
        int fade = Math.min(samples.length / 2, rate / 100); // 10 ms ramps stop the ends clicking
        for (int i = 0; i < samples.length; i++) {
            double envelope = Math.min(1.0, Math.min(i, samples.length - 1 - i) / (double) Math.max(1, fade));
            samples[i] = (short) (Math.sin(2 * Math.PI * frequency * i / rate) * 0.4 * 32767 * envelope);
        }
        return new Sound("tone:" + frequency + "Hz", Audio.isAvailable() ? new Pcm(samples, 1, rate) : null);
    }

    /** Sets the channel this sound plays on (default {@link AudioChannel#SFX}). */
    public Sound channel(AudioChannel channel) {
        this.channel = channel;
        return this;
    }

    /**
     * Sets where a {@linkplain #loop() loop} repeats. The sound plays from the start, and when it reaches
     * {@code endSeconds} it jumps back to {@code startSeconds}, so a start above zero gives an intro that plays once
     * before the looped part. Has no effect on a single {@link #play()}.
     * <p>
     * Call it right after loading, before the sound is played: OpenAL will not change a buffer that is in use.
     * Needs OpenAL Soft (bundled with the engine); without it the whole sound loops.
     *
     * @param startSeconds where each repeat begins
     * @param endSeconds   where each repeat ends, or 0 for the end of the sound
     * @throws IllegalArgumentException if the region is empty or outside the sound
     */
    public Sound loopPoints(float startSeconds, float endSeconds) {
        if (buffer == 0) return this;
        int start = Math.round(startSeconds * rate);
        int end = endSeconds <= 0f ? frames : Math.round(endSeconds * rate);
        if (start < 0 || end > frames || start >= end) {
            throw new IllegalArgumentException("Loop points " + startSeconds + "s to " + endSeconds + "s do not fit " + name + " (" + duration + "s)");
        }
        synchronized (Audio.LOCK) {
            if (!AL.getCapabilities().AL_SOFT_loop_points) {
                log.warn("Loop points are not supported by this OpenAL build; {} loops whole.", name);
                return this;
            }
            alBufferiv(buffer, SOFTLoopPoints.AL_LOOP_POINTS_SOFT, new int[]{start, end});
            if (alGetError() != AL_NO_ERROR) {
                log.warn("Could not set loop points on {}: it is probably playing already.", name);
            }
        }
        return this;
    }

    /** Loops from {@code startSeconds} to the end of the sound, playing everything before it once as an intro. */
    public Sound loopFrom(float startSeconds) {
        return loopPoints(startSeconds, 0f);
    }

    /** Plays once at a position in the world. See {@link Playback#position}. Starts already positioned, so there is no moment at the wrong place. */
    public Playback playAt(float x, float y, float z) {
        return start(1f, false, new float[]{x, y, z});
    }

    /** {@link #playAt(float, float, float)} for a 2D game: plays at (x, y) on the plane {@code z = 0}. */
    public Playback playAt(float x, float y) {
        return playAt(x, y, 0f);
    }

    /** Plays once at a position in the world, at {@code volume}. */
    public Playback playAt(float x, float y, float z, float volume) {
        return start(volume, false, new float[]{x, y, z});
    }

    /** Plays repeatedly at a position in the world, for example a campfire or a waterfall. */
    public Playback loopAt(float x, float y, float z) {
        return start(1f, true, new float[]{x, y, z});
    }

    /** Plays once at full volume. */
    public Playback play() {
        return play(1f);
    }

    /** Plays once at {@code volume}. */
    public Playback play(float volume) {
        return start(volume, false);
    }

    /** Plays repeatedly until you {@link Playback#stop()} it. */
    public Playback loop() {
        return start(1f, true);
    }

    private Playback start(float volume, boolean loop) {
        return start(volume, loop, null);
    }

    private Playback start(float volume, boolean loop, float[] position) {
        if (closed) throw new IllegalStateException("Sound is closed: " + name);
        if (buffer == 0) return Playback.silent(channel);
        return Playback.start(new Playback.Spec(this, channel, buffer, channels, null, volume, loop, 0, 0, position));
    }

    /** Length in seconds (0 when there is no audio device). */
    public float getDuration() {
        return duration;
    }

    public String getName() {
        return name;
    }

    /** Stops every playback of this sound and frees its memory. The sound cannot be played afterwards. */
    @Override
    public void close() {
        synchronized (Audio.LOCK) {
            if (closed) return;
            closed = true;
            Audio.stopOwnedBy(this);
            if (buffer != 0 && Audio.isAvailable()) alDeleteBuffers(buffer);
        }
    }
}
