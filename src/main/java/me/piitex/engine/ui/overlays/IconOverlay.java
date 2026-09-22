package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.image.IconCache;

import java.io.File;

/**
 * An {@link ImageOverlay} whose pixels come from rasterizing an SVG at this overlay's
 * own size (via {@link IconCache}, using Skia's built-in SVG renderer) instead of
 * decoding an already-rasterized PNG/JPEG/etc the way the plain {@link ImageOverlay}
 * constructors do. Opacity, corner radius, {@link #setTint}, hit-testing, and styling
 * all behave identically, since this is an ImageOverlay once the SVG has been rasterized
 * to an Image. Most icon packs, including Feather, Lucide, Material Symbols, and Font
 * Awesome's free set, ship single-color SVGs meant to be recolored by the consuming
 * application, so {@link #setTint} is usually the first thing to reach for on one of
 * these.
 * <p>
 * Two sources are supported: an external {@link File} or path, for an SVG shipped by the
 * application itself, and an {@link IconAsset}, for one bundled on the classpath. See
 * {@link me.piitex.engine.ui.icons.Feather} for the icon set bundled with this engine:
 * <pre>{@code new IconOverlay(Feather.ALERT_TRIANGLE)}</pre>
 * <p>
 * The {@link IconAsset} constructors can omit the width and height, defaulting to
 * {@link #DEFAULT_SIZE}, which is Feather's natural size. The File and path constructors
 * cannot, since an arbitrary external SVG has no size this class can assume. Changing
 * the size afterwards, through {@link #setWidth} for example, stretches the
 * already-rasterized Image rather than re-rendering the SVG at the new size. Construct a
 * new IconOverlay at the intended size instead, the same way a differently sized raster
 * image would be swapped in rather than stretched.
 */
public class IconOverlay extends ImageOverlay {
    private static final double DEFAULT_SIZE = 24;

    public IconOverlay(File svgFile, double width, double height) {
        super(IconCache.load(svgFile, (int) Math.round(width), (int) Math.round(height)), width, height);
    }

    public IconOverlay(String svgFilePath, double width, double height) {
        this(new File(svgFilePath), width, height);
    }

    public IconOverlay(IconAsset asset, double width, double height) {
        super(IconCache.loadResource(asset.resourcePath(), (int) Math.round(width), (int) Math.round(height)), width, height);
    }

    /**
     * Same as {@link #IconOverlay(IconAsset, double, double)}, sized to {@link #DEFAULT_SIZE}x{@link #DEFAULT_SIZE}.
     */
    public IconOverlay(IconAsset asset) {
        this(asset, DEFAULT_SIZE, DEFAULT_SIZE);
    }
}
