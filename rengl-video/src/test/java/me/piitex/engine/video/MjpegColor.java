package me.piitex.engine.video;

import java.io.IOException;

/** Test helper: decodes a JPEG frame and returns its center pixel's RGB . */
final class MjpegColor {
    private MjpegColor() {
    }

    static int of(byte[] jpeg) throws IOException {
        VideoFrame f = new MjpegDecoder().decode(new Packet(0, 0, true, jpeg));
        return center(f);
    }

    static int center(VideoFrame f) {
        int p = ((f.height() / 2) * f.width() + f.width() / 2) * 4;
        int b = f.pixels()[p] & 0xFF, g = f.pixels()[p + 1] & 0xFF, r = f.pixels()[p + 2] & 0xFF;
        return (r << 16) | (g << 8) | b;
    }

    /** Whether two RGB values are within JPEG's error of each other. */
    static boolean near(int expected, int actual) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((expected >> shift) & 0xFF) - ((actual >> shift) & 0xFF)) > 40) return false;
        }
        return true;
    }
}
