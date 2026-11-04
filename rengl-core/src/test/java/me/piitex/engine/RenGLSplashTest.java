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
 * The RenGL splash's timeline, drawn offscreen. Frames are also written to {@code target/splash-frames} to be looked at.
 */
class RenGLSplashTest {
    private static final int WIDTH = 960;
    private static final int HEIGHT = 600;

    /**
     * Draws the splash at {@code seconds} into the animation.
     */
    private static Image frame(RenGLSplash splash, float seconds) {
        splash.seek(seconds);
        try (OffscreenRenderer renderer = new OffscreenRenderer(WIDTH, HEIGHT, 1f)) {
            renderer.begin();
            splash.render(renderer, WIDTH, HEIGHT);
            renderer.end();
            return renderer.makeImage();
        }
    }

    /**
     * The pixel's average of red, green and blue, from 0 (black) to 1 (white).
     */
    private static float brightness(Image image, int pixelX, int pixelY) {
        Bitmap bitmap = Bitmap.Companion.makeFromImage(image);
        int argb = bitmap.getColor(pixelX, pixelY);
        int red = (argb >> 16) & 0xff;
        int green = (argb >> 8) & 0xff;
        int blue = argb & 0xff;
        // Three channels of up to 255 each, so 765 is the brightest sum.
        return (red + green + blue) / 765f;
    }

    /**
     * The pixel's opacity, from 0 to 1: the top byte of its ARGB value over 255.
     */
    private static float alphaAt(Image image, int pixelX, int pixelY) {
        return (Bitmap.Companion.makeFromImage(image).getColor(pixelX, pixelY) >>> 24) / 255f;
    }

    @Test
    void holdsTheApplicationUntilItFades() {
        RenGLSplash splash = new RenGLSplash();
        assertTrue(splash.isHoldingApp());
        assertFalse(splash.isFinished());
        splash.seek(RenGLSplash.HOLD_END - 0.01f);
        assertTrue(splash.isHoldingApp());
        splash.seek(RenGLSplash.HOLD_END + 0.01f);
        assertFalse(splash.isHoldingApp(), "fading out, the application shows through");
        assertFalse(splash.isFinished());
        splash.seek(RenGLSplash.HOLD_END + RenGLSplash.FADE);
        assertTrue(splash.isFinished());
        splash.dispose();
    }

    @Test
    void aLongFrameDoesNotSkipTheAnimation() {
        RenGLSplash splash = new RenGLSplash();
        splash.update(3f); // the first frame compiles shaders
        assertTrue(splash.time() <= 0.05f + 1e-6f);
        splash.dispose();
    }

    @Test
    void drawsTheWholeTimelineAndFadesToNothing() throws Exception {
        Path directory = Path.of("target", "splash-frames");
        Files.createDirectories(directory);
        RenGLSplash splash = new RenGLSplash();
        splash.showApi("opengl");
        float[] times = {0f, 0.35f, 0.7f, 1.0f, 1.3f, 1.6f, 1.9f, 2.2f, 2.5f, 2.7f, 2.95f};
        Image first = null, middle = null, last = null;
        for (float seconds : times) {
            Image image = frame(splash, seconds);
            // The file name is the time in hundredths of a second, so the frames sort in order.
            Files.write(directory.resolve(String.format("splash-%04d.png", Math.round(seconds * 100))),
                    image.encodeToData(EncodedImageFormat.PNG, 100, 0).getBytes());
            if (seconds == 0f) first = image;
            if (seconds == 2.2f) middle = image;
            if (seconds >= 2.95f) last = image;
        }
        // Dark, opaque backdrop from the first frame (no white flash) and the mark in the middle by the end.
        assertTrue(brightness(first, 5, 5) < 0.2f);
        assertEquals(1f, alphaAt(first, WIDTH / 2, HEIGHT - 1), 0.01f);
        // Row 240 is 40% down the 600 pixel window, through the middle of the mark.
        assertTrue(brightness(middle, WIDTH / 2, 240) > brightness(first, WIDTH / 2, 240) + 0.2f, "the R and hexagon are drawn");
        // Fully faded: nothing left, so the application underneath is untouched.
        assertEquals(0f, alphaAt(last, 5, 5), 0.02f);
        assertEquals(0f, alphaAt(last, WIDTH / 2, HEIGHT / 2), 0.02f);
        splash.dispose();
    }

    @Test
    void worksAtSmallAndLargeWindowSizes() {
        for (int[] size : new int[][]{{320, 200}, {1920, 1080}}) {
            RenGLSplash splash = new RenGLSplash();
            splash.showApi("vulkan");
            splash.seek(2f);
            try (OffscreenRenderer renderer = new OffscreenRenderer(size[0], size[1], 1f)) {
                renderer.begin();
                splash.render(renderer, size[0], size[1]);
                renderer.end();
            }
            splash.dispose();
        }
    }
}
