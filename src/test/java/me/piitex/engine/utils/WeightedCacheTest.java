package me.piitex.engine.utils;

import me.piitex.engine.ui.image.ImageLoader;
import org.jetbrains.skia.EncodedImageFormat;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.Surface;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WeightedCacheTest {
    @AfterEach
    void restore() {
        ImageLoader.clearCache();
        ImageLoader.setCacheLimit(256L << 20);
    }

    @Test
    void entriesAreLoadedOnceAndSharedWhileTheyFit() {
        WeightedCache<String> cache = new WeightedCache<>(100);
        AtomicInteger loads = new AtomicInteger();
        String a = cache.get("a", () -> "A" + loads.incrementAndGet(), s -> 10);
        assertSame(a, cache.get("a", () -> "other", s -> 10));
        assertEquals(1, loads.get());
        assertEquals(10, cache.getWeight());
    }

    @Test
    void theLeastRecentlyUsedGoFirstWhenTheLimitIsPassed() {
        WeightedCache<String> cache = new WeightedCache<>(30);
        for (String key : new String[]{"a", "b", "c"}) cache.get(key, () -> key, s -> 10);
        cache.get("a", () -> "x", s -> 10); // a is now the freshest
        cache.get("d", () -> "d", s -> 10); // over the limit: b, the oldest, goes
        assertEquals(3, cache.size());
        assertEquals(30, cache.getWeight());
        assertEquals("a", cache.get("a", () -> "reloaded", s -> 10));
        assertEquals("reloaded", cache.get("b", () -> "reloaded", s -> 10));
    }

    @Test
    void oneEntryBiggerThanTheLimitStillLoads() {
        WeightedCache<String> cache = new WeightedCache<>(10);
        cache.get("small", () -> "s", s -> 5);
        assertEquals("big", cache.get("big", () -> "big", s -> 50));
        assertEquals(1, cache.size(), "everything else made room for it");
        assertEquals("big", cache.get("big", () -> "again", s -> 50));
    }

    @Test
    void loweringTheLimitDropsAtOnceAndZeroMeansNoLimit() {
        WeightedCache<String> cache = new WeightedCache<>(0);
        for (int i = 0; i < 10; i++) {
            String key = "k" + i;
            cache.get(key, () -> key, s -> 10);
        }
        assertEquals(10, cache.size());
        cache.setLimit(35);
        assertEquals(3, cache.size());
        assertTrue(cache.remove("k9"));
        assertFalse(cache.remove("k9"));
        cache.clear();
        assertEquals(0, cache.getWeight());
    }

    @Test
    void theImageCacheLetsGoOfOldPicturesButNeverClosesOneInUse(@TempDir File dir) throws Exception {
        File[] files = new File[3];
        for (int i = 0; i < files.length; i++) {
            Surface surface = Surface.Companion.makeRasterN32Premul(50, 50); // 10,000 bytes decoded
            try (Image image = surface.makeImageSnapshot()) {
                files[i] = new File(dir, "p" + i + ".png");
                Files.write(files[i].toPath(), image.encodeToData(EncodedImageFormat.PNG, 100, 0).getBytes());
            }
            surface.close();
        }
        ImageLoader.setCacheLimit(25_000);
        Image first = ImageLoader.load(files[0]);
        ImageLoader.load(files[1]);
        ImageLoader.load(files[2]); // 30,000 > 25,000: the first one is let go
        assertEquals(20_000, ImageLoader.getCachedBytes());
        assertFalse(first.isClosed(), "still usable by whoever holds it");
        assertEquals(50, first.getWidth());
        assertNotSame(first, ImageLoader.load(files[0]), "loaded again");
    }
}
