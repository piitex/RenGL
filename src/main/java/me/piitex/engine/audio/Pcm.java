package me.piitex.engine.audio;

/** Package-private decoded audio: 16-bit interleaved samples, one or two channels. */
record Pcm(short[] samples, int channels, int sampleRate) {

    float duration() {
        return frames() / (float) sampleRate;
    }

    int frames() {
        return samples.length / channels;
    }

    /** A stream over this data. Each stream has its own read position, so one {@code Pcm} can back several. */
    PcmStream stream() {
        return new PcmStream() {
            private int pos;

            @Override public int channels() { return channels; }
            @Override public int sampleRate() { return sampleRate; }

            @Override
            public int read(short[] dst, int off, int len) {
                int n = Math.min(len, samples.length - pos);
                System.arraycopy(samples, pos, dst, off, n);
                pos += n;
                return n;
            }

            @Override public void seek(long frame) { pos = (int) Math.min(samples.length, Math.max(0, frame) * channels); }
            @Override public void close() { }
        };
    }
}
