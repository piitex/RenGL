package me.piitex.engine.audio;

import me.piitex.engine.io.AssetManager;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * A long track such as background music. Unlike a {@link Sound} it is decoded a little at a time while it plays,
 * so a ten-minute {@code .ogg} costs only its compressed size in memory. Music always plays on
 * {@link AudioChannel#MUSIC}.
 * <pre>{@code
 * Music theme = Music.load("music/theme.ogg");
 * theme.loop(2f); // fades in over two seconds and repeats
 * }</pre>
 * For the common case of "switch the background music", {@link Audio#playMusic(String)} handles loading,
 * caching, looping and crossfading.
 * <p>
 * Supports {@code .ogg} (Vorbis), {@code .opus}, {@code .flac}, {@code .mp3} and {@code .wav}, mono or stereo. Each call to {@code play} starts an
 * independent playback.
 */
public final class Music implements AutoCloseable {
    private final String name;
    private final byte[] bytes;   // the file, or null for a WAV on disk, which is read as it plays
    private final String wavFile;
    private float loopStart, loopEnd;
    private boolean closed;

    private Music(String name, byte[] bytes, String wavFile) {
        this.name = name;
        this.bytes = bytes;
        this.wavFile = wavFile;
        if (Audio.isAvailable()) open().close(); // fail now, not at play time, on a bad file
    }

    private PcmStream open() {
        return wavFile != null ? AudioDecoder.openWavFile(name, wavFile) : AudioDecoder.open(name, bytes);
    }

    /**
     * Loads a track from a file path or classpath resource. Only the compressed file is read here, and a {@code .wav} on
     * disk is not read at all until it plays, then a little at a time.
     *
     * @throws java.io.UncheckedIOException if the file cannot be found or read
     * @throws IllegalArgumentException     if the format is unsupported or the file is corrupt
     */
    public static Music load(String path) {
        if (AudioDecoder.isWavFile(path)) return new Music(path, null, path);
        return new Music(path, AudioDecoder.read(path), null);
    }

    /**
     * Loads a track through an {@link AssetManager}, so it can come out of a mounted {@code .pak}.
     */
    public static Music load(AssetManager assets, String name) {
        return new Music(name, AudioDecoder.read(assets, name), null);
    }

    /**
     * Sets where a {@linkplain #loop() loop} repeats. The track plays from the start, and when it reaches
     * {@code endSeconds} it jumps back to {@code startSeconds}, so a start above zero gives an intro that plays
     * once before the looped part. Playbacks that are already running keep their old points.
     * <p>
     * MP3 has no sample index, so jumping back in an MP3 decodes forward from the start of the file. That is
     * quick for normal tracks; prefer Ogg for very long ones with a late loop start.
     *
     * @param startSeconds where each repeat begins
     * @param endSeconds   where each repeat ends, or 0 for the end of the track
     * @throws IllegalArgumentException if {@code startSeconds} is negative or not before a non-zero {@code endSeconds}
     */
    public Music loopPoints(float startSeconds, float endSeconds) {
        if (startSeconds < 0f || (endSeconds > 0f && endSeconds <= startSeconds)) {
            throw new IllegalArgumentException("Loop points " + startSeconds + "s to " + endSeconds + "s are not a valid region");
        }
        loopStart = startSeconds;
        loopEnd = endSeconds;
        return this;
    }

    /**
     * Loops from {@code startSeconds} to the end of the track, playing everything before it once as an intro.
     */
    public Music loopFrom(float startSeconds) {
        return loopPoints(startSeconds, 0f);
    }

    /**
     * Plays once from the start.
     */
    public Playback play() {
        return start(0f, false);
    }

    /**
     * Plays once, fading in over {@code fadeInSeconds}.
     */
    public Playback play(float fadeInSeconds) {
        return start(fadeInSeconds, false);
    }

    /**
     * Plays repeatedly until stopped.
     */
    public Playback loop() {
        return start(0f, true);
    }

    /**
     * Plays repeatedly, fading in over {@code fadeInSeconds}.
     */
    public Playback loop(float fadeInSeconds) {
        return start(fadeInSeconds, true);
    }

    private Playback start(float fadeIn, boolean loop) {
        if (closed) throw new IllegalStateException("Music is closed: " + name);
        if (!Audio.ensureInit()) return Playback.silent(AudioChannel.MUSIC);
        PcmStream stream = open();
        int rate = stream.sampleRate();
        Playback p = Playback.start(new Playback.Spec(this, AudioChannel.MUSIC, 0, stream.channels(), stream,
                fadeIn > 0f ? 0f : 1f, 1f, loop, Math.round(loopStart * rate), Math.round(loopEnd * rate), null));
        if (fadeIn > 0f) p.fadeTo(1f, fadeIn);
        return p;
    }

    public String getName() {
        return name;
    }

    /**
     * Stops every playback of this track. The track cannot be played afterwards.
     */
    @Override
    public void close() {
        synchronized (Audio.LOCK) {
            if (closed) return;
            closed = true;
            Audio.stopOwnedBy(this);
        }
    }
}
