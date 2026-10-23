package me.piitex.engine.ui.image;

import me.piitex.engine.ui.text.Font;
import me.piitex.engine.utils.WeightedCache;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.Typeface;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * Caches decoded {@link Image} instances by file path, the same way
 * {@link Font} caches {@link Typeface}. An
 * {@link me.piitex.engine.ui.overlays.ImageOverlay} loading the same file, such as an
 * icon reused across several buttons, shares one decoded Image rather than re-reading
 * and re-decoding the file for every instance.
 * <p>
 * Loading goes through {@link Image.Companion#makeFromEncoded}, which sniffs the file's
 * own bytes to pick a codec. PNG, JPEG, WEBP, GIF, BMP, ICO, and WBMP all route through
 * that one call rather than needing per-format code here, and the formats actually
 * supported are whichever codecs this Skia build was compiled with.
 * <p>
 * The cache stays under {@link #getCacheLimit()} bytes (256 MiB of decoded pixels by default) by dropping the
 * images used longest ago. A dropped image is not closed: an overlay still showing it keeps a working picture, whose
 * memory is freed once nothing draws it, and loading the file again decodes it again. A fixed set of assets that fits
 * stays cached for good, so drawing from it is cheap. Raise the limit for a game with more than that on screen at once.
 */
public final class ImageLoader {
    private static final WeightedCache<Image> cache = new WeightedCache<>(256L << 20);

    private ImageLoader() {
    }

    /**
     * Loads (or returns the cached) Image for the given file.
     */
    public static Image load(File imageFile) {
        String key = imageFile.getAbsolutePath();
        return cache.get(key, () -> {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(imageFile.toPath());
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read image file: " + key, e);
            }

            Image image = Image.Companion.makeFromEncoded(bytes);
            if (image == null) {
                throw new IllegalArgumentException("Not a decodable image file: " + key);
            }
            return image;
        }, image -> 4L * image.getWidth() * image.getHeight());
    }

    /**
     * How many bytes of decoded pixels the cache may hold before it drops the least recently used picture.
     * Default 256 MiB. Lowering it drops pictures at once; 0 or less means no limit.
     */
    public static void setCacheLimit(long bytes) {
        cache.setLimit(bytes);
    }

    public static long getCacheLimit() {
        return cache.getLimit();
    }

    /**
     * The decoded bytes held by the cache now.
     */
    public static long getCachedBytes() {
        return cache.getWeight();
    }

    /**
     * Drops one picture from the cache, so the next {@link #load} decodes the file again. Returns whether it was cached.
     */
    public static boolean evict(File imageFile) {
        return cache.remove(imageFile.getAbsolutePath());
    }

    /**
     * Drops every cached picture. Overlays still showing one keep it.
     */
    public static void clearCache() {
        cache.clear();
    }

    /**
     * Opens {@code imageFile} as an {@link AnimatedImage}, or returns null when it is a still picture: any format
     * but GIF and WEBP, or a GIF or WEBP with a single frame. Unlike {@link #load}, nothing is cached, because
     * each animation is played at its own position and so needs a decoder of its own. A still file is recognised
     * from its first bytes without reading the rest, so asking about a PNG costs next to nothing.
     * <p>
     * This is the first step of how {@link me.piitex.engine.ui.overlays.ImageOverlay} decides whether a file
     * should play; {@link #load} on a GIF still gives its first frame as a plain Image.
     */
    public static AnimatedImage loadAnimated(File imageFile) {
        byte[] head = new byte[12];
        try (InputStream in = Files.newInputStream(imageFile.toPath())) {
            if (in.readNBytes(head, 0, head.length) < head.length || !AnimatedImage.mayBeAnimated(head)) return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read image file: " + imageFile, e);
        }
        AnimatedImage animated = AnimatedImage.load(imageFile);
        if (animated.isAnimated()) return animated;
        animated.close();
        return null;
    }

    /**
     * Loads (or returns the cached) Image for the given path string.
     */
    public static Image load(String imageFilePath) {
        return load(new File(imageFilePath));
    }
}
