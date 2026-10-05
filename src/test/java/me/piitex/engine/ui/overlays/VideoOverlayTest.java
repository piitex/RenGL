package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.overlays.VideoOverlay.Fit;
import me.piitex.engine.video.AviBuilderAccess;
import me.piitex.engine.video.VideoPlayer;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/** Renders a {@link VideoOverlay} into an offscreen surface and looks at the pixels. */
class VideoOverlayTest {
    @TempDir
    File dir;
    private VideoOverlay overlay;

    @AfterEach
    void dispose() {
        if (overlay != null) overlay.dispose();
    }

    /** A 64x32 video (2:1) whose first frame is solid red. */
    private VideoOverlay open(double w, double h) throws Exception {
        File file = AviBuilderAccess.write(dir, "wide.avi", 64, 32, 10, 3);
        overlay = new VideoOverlay(file, w, h);
        overlay.setPosition(0, 0);
        overlay.pause();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (overlay.getPlayer().getFrame() == null && System.nanoTime() < deadline) {
            overlay.update(1 / 30f);
            Thread.sleep(2);
        }
        assertNotNull(overlay.getPlayer().getFrame());
        return overlay;
    }

    private int[] pixel(int x, int y, int size) {
        try (OffscreenRenderer r = new OffscreenRenderer(size, size, 1f)) {
            r.begin();
            overlay.render(r);
            r.end();
            try (Image img = r.makeImage(); Bitmap bmp = new Bitmap()) {
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                int c = bmp.getColor(x, y);
                return new int[]{(c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >>> 24)};
            }
        }
    }

    private static boolean red(int[] p) {
        return p[0] > 180 && p[1] < 70 && p[2] < 70;
    }

    private static boolean black(int[] p) {
        return p[0] < 10 && p[1] < 10 && p[2] < 10;
    }

    @Test
    void sizesItselfToTheVideo() throws Exception {
        File file = AviBuilderAccess.write(dir, "n.avi", 64, 32, 10, 3);
        overlay = new VideoOverlay(file);
        assertEquals(64, overlay.getWidth());
        assertEquals(32, overlay.getHeight());
        assertEquals(64, overlay.getNaturalWidth());
    }

    @Test
    void containKeepsTheShapeAndLetterboxesInBlack() throws Exception {
        open(100, 100); // a 2:1 picture in a square: 100x50, centered
        assertTrue(red(pixel(50, 50, 100)), "the picture");
        assertTrue(red(pixel(2, 30, 100)), "its top-left");
        assertTrue(black(pixel(50, 10, 100)), "bar above");
        assertTrue(black(pixel(50, 90, 100)), "bar below");
    }

    @Test
    void stretchFillsTheWholeOverlay() throws Exception {
        open(100, 100).setFit(Fit.STRETCH);
        assertTrue(red(pixel(50, 5, 100)));
        assertTrue(red(pixel(50, 95, 100)));
    }

    @Test
    void coverFillsAndCropsTheSides() throws Exception {
        open(100, 100).setFit(Fit.COVER);
        assertTrue(red(pixel(50, 5, 100)));
        assertTrue(red(pixel(5, 95, 100)));
    }

    @Test
    void nothingDrawsBeforeTheFirstFrameButTheBackground() throws Exception {
        File file = AviBuilderAccess.write(dir, "late.avi", 64, 32, 10, 3);
        overlay = new VideoOverlay(file, 100, 100);
        overlay.setPosition(0, 0);
        // render at once: no frame has been picked yet
        assertTrue(black(pixel(50, 50, 100)));
    }

    @Test
    void backgroundStylingShowsInTheBars() throws Exception {
        open(100, 100).getStyling().setBackgroundColor(new Color(0f, 0f, 1f, 1f));
        int[] bar = pixel(50, 10, 100);
        assertTrue(bar[2] > 200 && bar[0] < 20, "bar should now be blue");
    }

    @Test
    void togglePausesAndResumes() throws Exception {
        open(10, 10);
        assertFalse(overlay.isPlaying());
        overlay.toggle();
        assertTrue(overlay.isPlaying());
        overlay.toggle();
        assertFalse(overlay.isPlaying());
        VideoPlayer p = overlay.getPlayer();
        assertSame(p, overlay.getPlayer());
    }

    @Test
    void disposeClosesThePlayer() throws Exception {
        open(10, 10);
        overlay.dispose();
        assertNull(overlay.getPlayer().getFrame());
        overlay.render(new OffscreenRenderer(10, 10, 1f)); // draws only the background
    }
}
