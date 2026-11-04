package me.piitex.engine;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.EncodedImageFormat;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The splash screen's timeline, drawn offscreen. Frames are also written to {@code target/splash-frames} to be looked at.
 */
class SplashScreenTest {
    private static final int W = 960, H = 600;

    private static Image frame(SplashScreen splash, float seconds, float[] cornerOut) {
        splash.seek(seconds);
        try (OffscreenRenderer r = new OffscreenRenderer(W, H, 1f)) {
            r.begin();
            splash.render(r, W, H, "opengl");
            r.end();
            return r.makeImage();
        }
    }

    private static float brightness(Image image, int x, int y) {
        Bitmap bitmap = Bitmap.Companion.makeFromImage(image);
        int argb = bitmap.getColor(x, y);
        return (((argb >> 16) & 0xff) + ((argb >> 8) & 0xff) + (argb & 0xff)) / 765f;
    }

    private static float alphaAt(Image image, int x, int y) {
        return (Bitmap.Companion.makeFromImage(image).getColor(x, y) >>> 24) / 255f;
    }

    @Test
    void holdsTheApplicationUntilItFades() {
        SplashScreen splash = new SplashScreen();
        assertTrue(splash.holdsApp());
        assertFalse(splash.finished());
        splash.seek(SplashScreen.HOLD_END - 0.01f);
        assertTrue(splash.holdsApp());
        splash.seek(SplashScreen.HOLD_END + 0.01f);
        assertFalse(splash.holdsApp(), "fading out, the application shows through");
        assertFalse(splash.finished());
        splash.seek(SplashScreen.HOLD_END + SplashScreen.FADE);
        assertTrue(splash.finished());
        splash.dispose();
    }

    @Test
    void aLongFrameDoesNotSkipTheAnimation() {
        SplashScreen splash = new SplashScreen();
        splash.advance(3f); // the first frame compiles shaders
        assertTrue(splash.time() <= 0.05f + 1e-6f);
        splash.dispose();
    }

    @Test
    void drawsTheWholeTimelineAndFadesToNothing() throws Exception {
        Path dir = Path.of("target", "splash-frames");
        Files.createDirectories(dir);
        SplashScreen splash = new SplashScreen();
        float[] times = {0f, 0.35f, 0.7f, 1.0f, 1.3f, 1.6f, 1.9f, 2.2f, 2.5f, 2.7f, 2.95f};
        Image first = null, mid = null, last = null;
        for (float t : times) {
            Image image = frame(splash, t, null);
            Files.write(dir.resolve(String.format("splash-%04d.png", Math.round(t * 100))),
                    image.encodeToData(EncodedImageFormat.PNG, 100, 0).getBytes());
            if (t == 0f) first = image;
            if (t == 2.2f) mid = image;
            if (t >= 2.95f) last = image;
        }
        // Dark, opaque backdrop from the first frame (no white flash) and the mark in the middle by the end.
        assertTrue(brightness(first, 5, 5) < 0.2f);
        assertEquals(1f, alphaAt(first, W / 2, H - 1), 0.01f);
        assertTrue(brightness(mid, W / 2, 240) > brightness(first, W / 2, 240) + 0.2f, "the R and hexagon are drawn");
        // Fully faded: nothing left, so the application underneath is untouched.
        assertEquals(0f, alphaAt(last, 5, 5), 0.02f);
        assertEquals(0f, alphaAt(last, W / 2, H / 2), 0.02f);
        splash.dispose();
    }

    @Test
    void worksAtSmallAndLargeWindowSizes() {
        for (int[] size : new int[][]{{320, 200}, {1920, 1080}}) {
            SplashScreen splash = new SplashScreen();
            splash.seek(2f);
            try (OffscreenRenderer r = new OffscreenRenderer(size[0], size[1], 1f)) {
                r.begin();
                splash.render(r, size[0], size[1], "vulkan");
                r.end();
            }
            splash.dispose();
        }
    }
}
