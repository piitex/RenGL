package me.piitex.engine.video;

/**
 * What {@link LinuxMediaSource} needs from a GStreamer pipeline: decoded pictures and sound, one stream at a time,
 * and a way to seek. The real one is {@link GstPipeline}; the split keeps everything that is not a native call
 * testable on a machine without GStreamer.
 */
interface GstPlayback extends AutoCloseable {
    /**
     * What the pipeline settled on once it had a first picture.
     *
     * @param frameRate     0 when the stream does not say (a variable rate)
     * @param audioChannels 0 when there is no sound, or none the pipeline can decode; otherwise 1 or 2
     */
    record Format(int width, int height, double frameRate, double duration, String videoCodec,
                  int audioChannels, int audioRate, String audioCodec) {
    }

    /**
     * Decoded data: a BGRA picture, or interleaved 16-bit little-endian sound.
     *
     * @param pts when it belongs, in seconds, or a negative number when the pipeline did not say
     */
    record Sample(byte[] data, double pts) {
    }

    Format format();

    /**
     * The next picture, waiting up to {@code millis} for one; null if none arrived (or the stream is over).
     */
    Sample pullVideo(int millis);

    Sample pullAudio(int millis);

    /**
     * True once the picture stream has ended and everything it decoded has been pulled.
     */
    boolean videoEnded();

    /**
     * Like {@link #videoEnded()}; true at once when there is no sound.
     */
    boolean audioEnded();

    /**
     * Restarts both streams at the last keyframe at or before {@code seconds}.
     */
    void seek(double seconds) throws java.io.IOException;

    /**
     * Why the pipeline stopped working, once it has, or null while it is fine.
     */
    default String failure() {
        return null;
    }

    /**
     * Lets the pipeline take its picture arrays from the player's pool.
     */
    default void useBuffers(Buffers buffers) {
    }

    @Override
    void close();
}
