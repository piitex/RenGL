package me.piitex.engine.ui.image;

import me.piitex.engine.ui.text.Font;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.Typeface;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * As with Font's Typefaces, cached Images are never closed. Constructing a draw call
 * from an already-decoded Image is cheap, while re-decoding the file is not. That is a
 * reasonable trade for the fixed set of UI assets an application ships with, such as
 * icons, backgrounds, and sprites reused throughout. Images are typically much larger
 * than font files and far more numerous, though, so an application loading many one-off
 * images at runtime, such as user-uploaded avatars or fetched content, rather than a
 * bounded set of shipped assets, will grow this cache without limit. In that case,
 * decode those images without going through this cache and close them once done.
 */
public final class ImageLoader {
    private static final Map<String, Image> cache = new ConcurrentHashMap<>();

    private ImageLoader() {
    }

    /**
     * Loads (or returns the cached) Image for the given file.
     */
    public static Image load(File imageFile) {
        String key = imageFile.getAbsolutePath();
        return cache.computeIfAbsent(key, path -> {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(imageFile.toPath());
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read image file: " + path, e);
            }

            Image image = Image.Companion.makeFromEncoded(bytes);
            if (image == null) {
                throw new IllegalArgumentException("Not a decodable image file: " + path);
            }
            return image;
        });
    }

    /**
     * Loads (or returns the cached) Image for the given path string.
     */
    public static Image load(String imageFilePath) {
        return load(new File(imageFilePath));
    }
}
