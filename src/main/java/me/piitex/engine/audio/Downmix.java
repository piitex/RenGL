package me.piitex.engine.audio;

/**
 * Package-private: mixes 3 to 8 channels down to stereo, for files made for surround speakers. The channel order is
 * the one WAV and FLAC use (front left and right, centre, low-frequency, back, side). The centre and the back and side
 * channels go into both ears at about 70% (the usual ITU mix), the low-frequency channel is dropped, and the result is
 * scaled so a full-volume file cannot clip.
 */
final class Downmix {
    static final int MAX_CHANNELS = 8;
    private static final float H = 0.7071f;

    /** [channels][0 = left, 1 = right][source channel]. */
    private static final float[][][] WEIGHTS = {
            null, null, null,
            {{1, 0, H}, {0, 1, H}},                                          // FL FR FC
            {{1, 0, H, 0}, {0, 1, 0, H}},                                    // FL FR BL BR
            {{1, 0, H, H, 0}, {0, 1, H, 0, H}},                              // FL FR FC BL BR
            {{1, 0, H, 0, H, 0}, {0, 1, H, 0, 0, H}},                        // FL FR FC LFE BL BR
            {{1, 0, H, 0, 0.5f, H, 0}, {0, 1, H, 0, 0.5f, 0, H}},            // FL FR FC LFE BC SL SR
            {{1, 0, H, 0, H, 0, H, 0}, {0, 1, H, 0, 0, H, 0, H}},            // FL FR FC LFE BL BR SL SR
    };

    private Downmix() {
    }

    static boolean supports(int channels) {
        return channels >= 3 && channels <= MAX_CHANNELS;
    }

    /**
     * Mixes {@code frames} sample frames of {@code channels}-channel {@code src} (from {@code srcOff}) into stereo
     * {@code dst} (from {@code dstOff}).
     */
    static void toStereo(short[] src, int srcOff, int frames, int channels, short[] dst, int dstOff) {
        float[] left = WEIGHTS[channels][0], right = WEIGHTS[channels][1];
        float scale = 1f;
        float sum = 0;
        for (float w : left) sum += w;
        scale = 1f / sum;
        for (int f = 0; f < frames; f++) {
            float l = 0, r = 0;
            int base = srcOff + f * channels;
            for (int c = 0; c < channels; c++) {
                float s = src[base + c];
                l += s * left[c];
                r += s * right[c];
            }
            dst[dstOff + f * 2] = (short) Math.round(l * scale);
            dst[dstOff + f * 2 + 1] = (short) Math.round(r * scale);
        }
    }

    /** The whole of {@code samples} mixed down. */
    static short[] toStereo(short[] samples, int channels) {
        int frames = samples.length / channels;
        short[] out = new short[frames * 2];
        toStereo(samples, 0, frames, channels, out, 0);
        return out;
    }

    /**
     * A stream over {@code source} that comes out stereo.
     */
    static PcmStream stream(PcmStream source) {
        int channels = source.channels();
        return new PcmStream() {
            private short[] scratch = new short[0];

            @Override
            public int channels() {
                return 2;
            }

            @Override
            public int sampleRate() {
                return source.sampleRate();
            }

            @Override
            public int bufferFrames() {
                return source.bufferFrames();
            }

            @Override
            public long totalFrames() {
                return source.totalFrames();
            }

            @Override
            public int read(short[] dst, int off, int len) {
                int frames = len / 2;
                if (scratch.length < frames * channels) scratch = new short[frames * channels];
                int got = source.read(scratch, 0, frames * channels) / channels;
                toStereo(scratch, 0, got, channels, dst, off);
                return got * 2;
            }

            @Override
            public void seek(long frame) {
                source.seek(frame);
            }

            @Override
            public void close() {
                source.close();
            }
        };
    }
}
