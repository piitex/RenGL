package me.piitex.engine.render3d.upscale;

/**
 * The sub-pixel offsets a temporal upscaler's camera is moved by: a Halton (2, 3) sequence, which spreads its points
 * evenly over the pixel however many of them have been used.
 */
public final class JitterSequence {
    private JitterSequence() {
    }

    /**
     * The length of the sequence for a picture enlarged from {@code renderWidth} to {@code displayWidth}:
     * {@code 8 * (display / render)^2}, as AMD recommends, and never fewer than 8.
     */
    public static int phases(int renderWidth, int displayWidth) {
        float ratio = (float) displayWidth / Math.max(1, renderWidth);
        return Math.max(8, Math.round(8f * ratio * ratio));
    }

    /**
     * The radical inverse of {@code index} in {@code base}, between 0 and 1.
     */
    public static float halton(int index, int base) {
        float f = 1f, result = 0f;
        for (int i = index; i > 0; i /= base) {
            f /= base;
            result += f * (i % base);
        }
        return result;
    }

    /**
     * The offset for a frame, in pixels of the render image, each between -0.5 and 0.5, written to {@code out[0]} (to the
     * right) and {@code out[1]} (down the picture). The sequence repeats every {@code phases} frames.
     */
    public static void offset(long frame, int phases, float[] out) {
        int index = (int) (frame % Math.max(1, phases)) + 1; // from 1, since the first point of the sequence is the corner
        out[0] = halton(index, 2) - 0.5f;
        out[1] = halton(index, 3) - 0.5f;
    }
}
