package me.piitex.engine.render3d.scene;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ShadowAtlasTest {
    private static boolean overlap(ShadowAtlas.Rect a, ShadowAtlas.Rect b) {
        return a.x() < b.x() + b.size() && b.x() < a.x() + a.size() && a.y() < b.y() + b.size() && b.y() < a.y() + a.size();
    }

    private static void assertValid(int[] sizes, ShadowAtlas.Layout layout) {
        List<ShadowAtlas.Rect> rects = layout.rects();
        assertEquals(sizes.length, rects.size());
        for (int i = 0; i < rects.size(); i++) {
            ShadowAtlas.Rect r = rects.get(i);
            assertEquals(sizes[i], r.size(), "the tile keeps the order and size it was asked for");
            assertTrue(r.x() >= 0 && r.y() >= 0 && r.x() + r.size() <= layout.width() && r.y() + r.size() <= layout.height(),
                    "tile " + i + " inside the atlas");
            for (int j = i + 1; j < rects.size(); j++) assertFalse(overlap(r, rects.get(j)), "tiles " + i + " and " + j + " overlap");
        }
    }

    @Test
    void oneTileFillsItsAtlas() {
        ShadowAtlas.Layout layout = ShadowAtlas.layout(new int[]{2048}, 4096);
        assertEquals(2048, layout.width());
        assertEquals(2048, layout.height());
        assertEquals(new ShadowAtlas.Rect(0, 0, 2048), layout.rects().get(0));
    }

    @Test
    void fourTilesMakeASquare() {
        int[] sizes = {1024, 1024, 1024, 1024};
        ShadowAtlas.Layout layout = ShadowAtlas.layout(sizes, 4096);
        assertValid(sizes, layout);
        assertEquals(2048, layout.width());
        assertEquals(2048, layout.height());
    }

    @Test
    void pointLightsSixFacesPackTight() {
        int[] sizes = {1024, 1024, 1024, 1024, 1024, 1024};
        ShadowAtlas.Layout layout = ShadowAtlas.layout(sizes, 4096);
        assertValid(sizes, layout);
        assertTrue((long) layout.width() * layout.height() <= 4096L * 2048L);
    }

    @Test
    void mixedSizesNeverOverlap() {
        Random random = new Random(7);
        int[] choices = {128, 256, 512, 1024, 2048};
        for (int round = 0; round < 200; round++) {
            int[] sizes = new int[1 + random.nextInt(24)];
            for (int i = 0; i < sizes.length; i++) sizes[i] = choices[random.nextInt(choices.length)];
            assertValid(sizes, ShadowAtlas.layout(sizes, 4096));
        }
    }

    @Test
    void oddSizesWork() {
        int[] sizes = {1500, 700, 700};
        assertValid(sizes, ShadowAtlas.layout(sizes, 4096));
    }

    @Test
    void shrinksTheBiggestUntilEverythingFits() {
        ShadowAtlas.Fit fit = ShadowAtlas.fit(new int[]{4096, 4096, 4096, 4096}, new int[]{1, 1, 1, 1}, 4096);
        for (int size : fit.lightSizes()) assertTrue(size <= 2048, "each is halved: " + size);
        assertTrue(fit.layout().width() <= 4096 && fit.layout().height() <= 4096);
        assertValid(new int[]{fit.lightSizes()[0], fit.lightSizes()[1], fit.lightSizes()[2], fit.lightSizes()[3]}, fit.layout());
    }

    @Test
    void onlyTheBiggestShrinks() {
        // A 4096 tile fills the whole 4096 atlas, so with a 1024 tile beside it the big one has to give way.
        ShadowAtlas.Fit fit = ShadowAtlas.fit(new int[]{4096, 1024}, new int[]{1, 1}, 4096);
        assertArrayEquals(new int[]{2048, 1024}, fit.lightSizes());
        // Three 2048 tiles fit as they are, so nothing is touched.
        ShadowAtlas.Fit roomy = ShadowAtlas.fit(new int[]{2048, 2048, 2048}, new int[]{1, 1, 1}, 4096);
        assertArrayEquals(new int[]{2048, 2048, 2048}, roomy.lightSizes());
        // 4096 + two 2048: the big one gives way first, and then all three fit.
        ShadowAtlas.Fit crowded = ShadowAtlas.fit(new int[]{4096, 2048, 2048}, new int[]{1, 1, 1}, 4096);
        assertArrayEquals(new int[]{2048, 2048, 2048}, crowded.lightSizes());
    }

    @Test
    void aLoneTileBiggerThanTheAtlasIsKept() {
        ShadowAtlas.Fit fit = ShadowAtlas.fit(new int[]{8192}, new int[]{1}, 4096);
        assertArrayEquals(new int[]{8192}, fit.lightSizes());
        assertEquals(8192, fit.layout().width());
    }

    @Test
    void fourPointLightsOfHighQualityStillFit() {
        ShadowAtlas.Fit fit = ShadowAtlas.fit(new int[]{2048, 2048, 2048, 2048}, new int[]{6, 6, 6, 6}, 4096);
        List<ShadowAtlas.Rect> rects = fit.layout().rects();
        assertEquals(24, rects.size());
        assertTrue(fit.layout().width() <= 4096 && fit.layout().height() <= 4096);
        for (int size : fit.lightSizes()) assertTrue(size >= ShadowAtlas.MIN_TILE);
    }

    @Test
    void nothingToPack() {
        ShadowAtlas.Fit fit = ShadowAtlas.fit(new int[0], new int[0], 4096);
        assertEquals(0, fit.layout().rects().size());
    }
}
