package me.piitex.engine.gl.renderer;

import me.piitex.engine.graphics.Texture;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smooth sprites that share an edge and are turned at an angle must not leave see-through pixels between them in a
 * snapshot, which is drawn by the CPU renderer.
 */
class TurnedQuadSeamTest {
    private static Texture opaque() {
        Bitmap bitmap = new Bitmap();
        bitmap.allocN32Pixels(8, 8, true);
        bitmap.erase(0xFF336699);
        return new Texture(Image.Companion.makeFromBitmap(bitmap));
    }

    /** Two squares of the given side sharing an edge, both turned by {@code degrees} around (cx, cy). */
    private static float[] row(double side, double degrees, double cx, double cy, int count) {
        float[] positions = new float[count * 8];
        double rad = Math.toRadians(degrees), cos = Math.cos(rad), sin = Math.sin(rad);
        for (int q = 0; q < count; q++) {
            // Each quad is placed from its own corner, in float, as a sprite is: its neighbour's corner is only
            // nearly the same number.
            float ox = (float) (cx + q * side * cos), oy = (float) (cy + q * side * sin);
            float fc = (float) cos, fs = (float) sin, w = (float) side;
            float[][] corners = {{0, 0}, {w, 0}, {w, w}, {0, w}};
            for (int c = 0; c < 4; c++) {
                float x = corners[c][0], y = corners[c][1];
                positions[q * 8 + c * 2] = ox + x * fc - y * fs;
                positions[q * 8 + c * 2 + 1] = oy + x * fs + y * fc;
            }
        }
        return positions;
    }

    private static int holes(double degrees, double side, int count) {
        return holes(degrees, side, count, 0f);
    }

    /** {@code gap} pixels are left between the quads on purpose, to check that the test notices. */
    private static int holes(double degrees, double side, int count, float gap) {
        Texture texture = opaque();
        float[] tex = new float[count * 8];
        for (int q = 0; q < count; q++) {
            float[] t = {0, 0, 8, 0, 8, 8, 0, 8};
            System.arraycopy(t, 0, tex, q * 8, 8);
        }
        double cx = 40 + (degrees * 7 % 1) * 0.9, cy = 40 + (side * 13 % 1);
        try (OffscreenRenderer r = new OffscreenRenderer(120, 120, 1f)) {
            r.begin();
            float[] positions = row(side, degrees, cx, cy, count);
            if (gap > 0) { // pull the second quad away along the row
                double rad = Math.toRadians(degrees);
                for (int i = 8; i < 16; i += 2) {
                    positions[i] += gap * (float) Math.cos(rad);
                    positions[i + 1] += gap * (float) Math.sin(rad);
                }
            }
            r.drawImageQuads(texture.getImage(), true, positions, tex, null, count);
            r.end();
            try (Image image = r.makeImage(); Bitmap bmp = new Bitmap()) {
                bmp.allocPixels(image.getImageInfo());
                image.readPixels(bmp);
                int holes = 0;
                double rad = Math.toRadians(degrees), cos = Math.cos(rad), sin = Math.sin(rad);
                for (int y = 0; y < 120; y++) {
                    for (int x = 0; x < 120; x++) {
                        // The pixel's centre in the row's own, unturned, space.
                        double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
                        double u = dx * cos + dy * sin, v = -dx * sin + dy * cos;
                        boolean inside = u > 0.75 && u < side * count - 0.75 && v > 0.75 && v < side - 0.75;
                        if (inside && (bmp.getColor(x, y) >>> 24) < 255) holes++;
                    }
                }
                return holes;
            }
        }
    }

    /** Upright quads side by side, the whole scene turned by a transform, as a rotated camera does. */
    private static int holesUnderTransform(double degrees, double side, int count) {
        Texture texture = opaque();
        float[] tex = new float[count * 8], pos = new float[count * 8];
        for (int q = 0; q < count; q++) {
            float[] t = {0, 0, 8, 0, 8, 8, 0, 8};
            System.arraycopy(t, 0, tex, q * 8, 8);
            float x0 = (float) (q * side), x1 = (float) ((q + 1) * side), w = (float) side;
            float[] p = {x0, 0, x1, 0, x1, w, x0, w};
            System.arraycopy(p, 0, pos, q * 8, 8);
        }
        double cx = 60, cy = 60;
        try (OffscreenRenderer r = new OffscreenRenderer(160, 160, 1f)) {
            r.begin();
            r.pushTransform((float) cx, (float) cy, (float) degrees, 1f, 1f, 0f, 0f);
            r.drawImageQuads(texture.getImage(), true, pos, tex, null, count);
            r.popTransform();
            r.end();
            try (Image image = r.makeImage(); Bitmap bmp = new Bitmap()) {
                bmp.allocPixels(image.getImageInfo());
                image.readPixels(bmp);
                int holes = 0;
                double rad = Math.toRadians(degrees), cos = Math.cos(rad), sin = Math.sin(rad);
                for (int y = 0; y < 160; y++) {
                    for (int x = 0; x < 160; x++) {
                        double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
                        double u = dx * cos + dy * sin, v = -dx * sin + dy * cos;
                        boolean inside = u > 0.75 && u < side * count - 0.75 && v > 0.75 && v < side - 0.75;
                        if (inside && (bmp.getColor(x, y) >>> 24) < 255) holes++;
                    }
                }
                return holes;
            }
        }
    }

    @Test
    void uprightQuadsUnderATurnedTransformLeaveNoHoles() {
        StringBuilder bad = new StringBuilder();
        int total = 0;
        for (double side : new double[]{10, 16, 12.5, 9.99}) {
            for (double degrees = 0.5; degrees < 90; degrees += 0.5) {
                int h = holesUnderTransform(degrees, side, 5);
                if (h > 0) bad.append(' ').append(degrees).append("deg/").append(side).append(':').append(h);
                total += h;
            }
        }
        assertEquals(0, total, "see-through pixels at:" + bad);
    }

    @Test
    void theCheckWouldSeeARealGap() {
        assertTrue(holes(30, 10, 3, 2f) > 10, "a two pixel gap between turned quads shows as holes");
    }

    @Test
    void neighbouringTurnedSmoothQuadsLeaveNoHoles() {
        StringBuilder bad = new StringBuilder();
        int total = 0;
        for (double side : new double[]{10, 16, 12.5, 8, 9.99, 7.3, 11}) {
            for (double degrees = 0.5; degrees < 90; degrees += 0.5) {
                int h = holes(degrees, side, 5);
                if (h > 0) bad.append(' ').append(degrees).append("deg/").append(side).append(':').append(h);
                total += h;
            }
        }
        assertEquals(0, total, "see-through pixels at:" + bad);
    }
}
