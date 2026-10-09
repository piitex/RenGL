package me.piitex.engine.video;

/**
 * Turning a decoded picture to the way a video says it should be shown. A phone records sideways and marks the file
 * with a turn; the decoder hands over the picture as it was recorded.
 */
final class Rotation {
    private Rotation() {
    }

    /**
     * The clockwise quarter turns (0 to 3) that a display transform asks for, from its first column {@code (a, b)}: the
     * way the picture's x axis points once the transform is applied. A flip in the transform is ignored.
     */
    static int quarterTurns(double a, double b) {
        if (Math.abs(b) > Math.abs(a)) return b > 0 ? 1 : 3;
        return a < 0 ? 2 : 0;
    }

    /**
     * Copies a {@code w} by {@code h} picture of 4-byte pixels into {@code dst}, turned clockwise by {@code turns}
     * quarter turns. A quarter or three-quarter turn makes it {@code h} wide and {@code w} tall.
     */
    static void rotate(byte[] src, byte[] dst, int w, int h, int turns) {
        switch (turns & 3) {
            case 0 -> System.arraycopy(src, 0, dst, 0, w * h * 4);
            case 1 -> {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) copyPixel(src, (y * w + x) * 4, dst, (x * h + (h - 1 - y)) * 4);
                }
            }
            case 2 -> {
                for (int i = 0, n = w * h; i < n; i++) copyPixel(src, i * 4, dst, (n - 1 - i) * 4);
            }
            default -> {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) copyPixel(src, (y * w + x) * 4, dst, ((w - 1 - x) * h + y) * 4);
                }
            }
        }
    }

    private static void copyPixel(byte[] src, int from, byte[] dst, int to) {
        dst[to] = src[from];
        dst[to + 1] = src[from + 1];
        dst[to + 2] = src[from + 2];
        dst[to + 3] = src[from + 3];
    }
}
