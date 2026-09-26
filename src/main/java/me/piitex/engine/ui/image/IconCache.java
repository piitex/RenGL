package me.piitex.engine.ui.image;

import org.jetbrains.skia.Data;
import org.jetbrains.skia.Point;
import org.jetbrains.skia.Surface;
import org.jetbrains.skia.svg.SVGDOM;
import org.jetbrains.skia.svg.SVGLengthContext;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rasterizes an SVG icon into a decoded {@link org.jetbrains.skia.Image}, the same role {@link
 * ImageLoader} plays for PNG, JPEG, and similar formats. It uses Skia's own
 * {@code SVGDOM}, already bundled in Skiko, so no third-party SVG library is needed to
 * use vector icon packs.
 * <p>
 * Loads from either an external {@link File} ({@link #load}) or a classpath resource
 * ({@link #loadResource}). The resource path is how {@link IconAsset} and
 * {@link me.piitex.engine.ui.icons.Feather} reach icons bundled inside this engine's own
 * jar, or any jar on the consuming application's classpath, which a plain {@code File}
 * cannot address once the source is not loose files on disk.
 * <p>
 * Unlike a raster format, an SVG has no pixel size of its own. It is re-rendered into a
 * raster {@link Surface} at whatever size is asked for, so the cache key is the source
 * together with the requested size rather than the source alone, and the same icon
 * requested at two different sizes decodes and caches separately. Each icon is
 * rasterized at {@link #OVERSAMPLE} times the requested size and drawn scaled back down
 * by the renderer, so edges stay crisp on a HiDPI display rather than matching only a 1x
 * screen.
 * <p>
 * As with {@link ImageLoader}'s entries, cached Images are never closed. See that
 * class's docs for the trade-off this makes. {@link #rasterizeFile} and {@link
 * #rasterizeResource} skip the cache, for an icon whose size keeps changing.
 */
public final class IconCache {
    private static final float OVERSAMPLE = 2f;
    private static final Map<String, org.jetbrains.skia.Image> cache = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> sources = new ConcurrentHashMap<>();

    private IconCache() {
    }

    /**
     * Loads (or returns the cached) rasterized Image for {@code svgFile} at {@code width}x{@code height} logical points.
     */
    public static org.jetbrains.skia.Image load(File svgFile, int width, int height) {
        String key = "file:" + svgFile.getAbsolutePath() + "@" + width + "x" + height;
        return cache.computeIfAbsent(key, k -> rasterizeFile(svgFile, width, height));
    }

    /**
     * Loads (or returns the cached) rasterized Image for the SVG at {@code
     * resourcePath} on the classpath, such as
     * {@code "icons/feather/alert-triangle.svg"}. The path is resolved through
     * {@link ClassLoader#getResourceAsStream}, so it works whether this engine, or the
     * consuming application, runs from loose class files or from a jar. This is what the
     * {@link IconAsset}-based {@code IconOverlay} constructors call.
     */
    public static org.jetbrains.skia.Image loadResource(String resourcePath, int width, int height) {
        String key = "resource:" + resourcePath + "@" + width + "x" + height;
        return cache.computeIfAbsent(key, k -> rasterizeResource(resourcePath, width, height));
    }

    /**
     * Rasterizes {@code svgFile} at {@code width}x{@code height} logical points without caching the result, so the
     * caller owns the Image and should close it once nothing draws it. This is for an icon whose size keeps
     * changing, where caching every size would grow without bound. The file's bytes are still read only once.
     */
    public static org.jetbrains.skia.Image rasterizeFile(File svgFile, int width, int height) {
        String source = svgFile.getAbsolutePath();
        return rasterize(sources.computeIfAbsent("file:" + source, k -> readFile(svgFile)), source, width, height);
    }

    /**
     * The classpath counterpart of {@link #rasterizeFile}.
     */
    public static org.jetbrains.skia.Image rasterizeResource(String resourcePath, int width, int height) {
        return rasterize(sources.computeIfAbsent("resource:" + resourcePath, k -> readResource(resourcePath)), resourcePath, width, height);
    }

    private static byte[] readFile(File svgFile) {
        try {
            return Files.readAllBytes(svgFile.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read icon file: " + svgFile.getAbsolutePath(), e);
        }
    }

    private static byte[] readResource(String resourcePath) {
        ClassLoader loader = IconCache.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("Icon resource not found on classpath: " + resourcePath);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read icon resource: " + resourcePath, e);
        }
    }

    private static org.jetbrains.skia.Image rasterize(byte[] bytes, String source, int width, int height) {
        int rasterWidth = Math.max(1, Math.round(width * OVERSAMPLE));
        int rasterHeight = Math.max(1, Math.round(height * OVERSAMPLE));

        try (Data data = Data.Companion.makeFromBytes(bytes, 0, bytes.length);
             SVGDOM svg = new SVGDOM(data);
             Surface surface = Surface.Companion.makeRasterN32Premul(rasterWidth, rasterHeight)) {
            if (svg.getRoot() == null) {
                throw new IllegalArgumentException("Not a decodable SVG file: " + source);
            }
            svg.setContainerSize((float) rasterWidth, (float) rasterHeight);

            // setContainerSize alone only rescales content sized in percentages. An SVG
            // that declares its own absolute width and height, the common case for icon
            // packs such as Feather's width="24" height="24", renders at that fixed
            // intrinsic size no matter how big the raster surface is, so the requested
            // size would be ignored. Computing the intrinsic-to-raster scale here and
            // applying it as a canvas transform before rendering makes both
            // percentage-sized and absolute-sized SVGs fill the requested raster.
            SVGLengthContext lengthContext = new SVGLengthContext(rasterWidth, rasterHeight, 96f);
            Point intrinsic = svg.getRoot().getIntrinsicSize(lengthContext);
            float scaleX = intrinsic.getX() > 0 ? rasterWidth / intrinsic.getX() : 1f;
            float scaleY = intrinsic.getY() > 0 ? rasterHeight / intrinsic.getY() : 1f;

            surface.getCanvas().clear(0x00000000);
            surface.getCanvas().scale(scaleX, scaleY);
            svg.render(surface.getCanvas());
            return surface.makeImageSnapshot();
        }
    }
}
