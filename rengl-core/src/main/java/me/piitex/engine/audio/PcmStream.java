package me.piitex.engine.audio;

/**
 * A source of 16-bit interleaved PCM that {@link Playback} pulls from while streaming. The engine's own decoders
 * implement it, and so does the audio track of a video (see {@link Audio#playStream}). {@link #read} is called on the
 * audio thread, so it must not block for long.
 */
public interface PcmStream extends AutoCloseable {
    int channels();

    int sampleRate();

    /**
     * Reads up to {@code len} shorts into {@code dst} at {@code off}. Returns how many were read, 0 at the end.
     */
    int read(short[] dst, int off, int len);

    /**
     * How many sample frames to hand the audio device at a time, or 0 for the default. A stream that is produced live
     * (a video's soundtrack) asks for smaller pieces so it never has to supply more than it has ready.
     */
    default int bufferFrames() {
        return 0;
    }

    /**
     * Jumps to a sample frame (one sample per channel), used for looping. Frame 0 is the start.
     */
    void seek(long frame);

    /**
     * Length in sample frames, or -1 when the format does not say. Only a hint for sizing buffers.
     */
    default long totalFrames() {
        return -1;
    }

    @Override
    void close();
}
