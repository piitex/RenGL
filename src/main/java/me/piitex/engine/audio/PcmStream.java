package me.piitex.engine.audio;

/** Package-private source of 16-bit interleaved PCM that {@link Playback} pulls from while streaming. */
interface PcmStream extends AutoCloseable {
    int channels();

    int sampleRate();

    /** Reads up to {@code len} shorts into {@code dst} at {@code off}. Returns how many were read, 0 at the end. */
    int read(short[] dst, int off, int len);

    /** Jumps to a sample frame (one sample per channel), used for looping. Frame 0 is the start. */
    void seek(long frame);

    /** Length in sample frames, or -1 when the format does not say. Only a hint for sizing buffers. */
    default long totalFrames() {
        return -1;
    }

    @Override
    void close();
}
