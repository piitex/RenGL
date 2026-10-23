package me.piitex.engine.graphics;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.IRect;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A frame a packer stored turned 90 degrees clockwise draws upright. */
class RotatedRegionTest {
    private static final int RED = 0xFFFF0000, BLUE = 0xFF0000FF;

    /** A 20x10 sheet holding a 10x20 picture (red left half, blue right half) turned clockwise: red on top, blue below. */
    private static Texture sheet() {
        Bitmap bitmap = new Bitmap();
        bitmap.allocN32Pixels(20, 10, false);
        bitmap.erase(RED, IRect.Companion.makeLTRB(0, 0, 20, 5));
        bitmap.erase(BLUE, IRect.Companion.makeLTRB(0, 5, 20, 10));
        return new Texture(Image.Companion.makeFromBitmap(bitmap));
    }

    private static Bitmap draw(Sprite sprite, int width, int height) {
        try (OffscreenRenderer r = new OffscreenRenderer(width, height, 1f)) {
            r.begin();
            sprite.draw(r);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                return bmp;
            }
        }
    }

    @Test
    void aRotatedRegionKeepsTheUprightSizeAndFitsTheTurnedRectangle() {
        TextureRegion region = new TextureRegion(sheet(), 0, 0, 10, 20, 0, 0, 10, 20, true);
        assertTrue(region.isRotated());
        assertEquals(10, region.getWidth());
        assertEquals(20, region.getHeight());
        assertThrows(IllegalArgumentException.class, () -> new TextureRegion(sheet(), 0, 0, 20, 10, 0, 0, 20, 10, true),
                "turned, 20x10 needs a 10x20 rectangle, which this sheet doesn't have");
    }

    @Test
    void itDrawsUpright() {
        drawsUpright(true);
    }

    @Test
    void itDrawsUprightInAPixelArtTextureToo() {
        drawsUpright(false);
    }

    private static void drawsUpright(boolean smooth) {
        Texture texture = sheet();
        texture.setSmooth(smooth);
        Sprite sprite = new Sprite(new TextureRegion(texture, 0, 0, 10, 20, 0, 0, 10, 20, true));
        try (Bitmap bmp = draw(sprite, 10, 20)) {
            assertEquals(RED, bmp.getColor(1, 10), "the left half is red");
            assertEquals(RED, bmp.getColor(3, 2));
            assertEquals(BLUE, bmp.getColor(8, 10), "the right half is blue");
            assertEquals(BLUE, bmp.getColor(7, 18));
        }
    }

    @Test
    void anUnturnedRegionOfTheSameSheetIsUnchanged() {
        Sprite sprite = new Sprite(new TextureRegion(sheet(), 0, 0, 20, 10));
        try (Bitmap bmp = draw(sprite, 20, 10)) {
            assertEquals(RED, bmp.getColor(10, 1), "red on top");
            assertEquals(BLUE, bmp.getColor(10, 8), "blue below");
        }
    }

    @Test
    void anAtlasReadsRotatedFramesInsteadOfRefusingThem() {
        String json = """
                {"frames": {"hero": {"frame": {"x": 0, "y": 0, "w": 10, "h": 20}, "rotated": true, "trimmed": false,
                 "sourceSize": {"w": 10, "h": 20}}}, "meta": {"image": "x.png"}}""";
        TextureAtlas atlas = TextureAtlas.parse(json, sheet());
        TextureRegion hero = atlas.get("hero");
        assertTrue(hero.isRotated());
        assertEquals(10, hero.getFrameWidth());
        assertEquals(20, hero.getFrameHeight());
    }
}
