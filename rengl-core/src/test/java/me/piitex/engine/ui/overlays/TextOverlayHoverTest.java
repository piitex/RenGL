package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.color.Color;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextOverlayHoverTest {
    private static TextOverlay link() {
        TextOverlay text = new TextOverlay("Documents", 20f, Color.BLACK);
        text.setX(10);
        text.setY(10);
        return text;
    }

    private static Bitmap draw(TextOverlay text) {
        try (OffscreenRenderer r = new OffscreenRenderer(200, 60, 1f)) {
            r.clear(1f, 1f, 1f, 1f);
            r.begin();
            text.render(r);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                return bmp;
            }
        }
    }

    /** Pixels that are neither near-white nor near-gray: those colored by red text. */
    private static int reddish(Bitmap bmp) {
        int n = 0;
        for (int y = 0; y < 60; y++)
            for (int x = 0; x < 200; x++) {
                int c = bmp.getColor(x, y);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF;
                if (r > 150 && g < 100) n++;
            }
        return n;
    }

    private static int inked(Bitmap bmp) {
        int n = 0;
        for (int y = 0; y < 60; y++)
            for (int x = 0; x < 200; x++) if ((bmp.getColor(x, y) & 0xFFFFFF) != 0xFFFFFF) n++;
        return n;
    }

    @Test
    void theHoverColorShowsOnlyWhileHovered() {
        TextOverlay text = link();
        text.setHoverColor(new Color(1f, 0f, 0f, 1f));
        assertEquals(0, reddish(draw(text)));

        text.setHovered(true);
        assertTrue(reddish(draw(text)) > 20);

        text.setHoverColor(null);
        assertNull(text.getHoverColor());
        assertEquals(0, reddish(draw(text)));
    }

    @Test
    void underlineOnHoverAddsInkOnlyWhileHovered() {
        TextOverlay text = link();
        int plain = inked(draw(text));
        text.setUnderlineOnHover(true);
        assertEquals(plain, inked(draw(text)), "not hovered: nothing extra");

        text.setHovered(true);
        assertTrue(inked(draw(text)) > plain + 40, "a line under the text");
    }
}
