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
 * the size afterwards, through {@link #setWidth} or a {@link
 * me.piitex.engine.ui.containers.ResizableContainer} for example, re-renders the SVG at the
 * new size once the size has stopped changing for {@link #RERASTER_DELAY} seconds. Until
 * then the old image is stretched, so a live resize does not re-render every frame.
 * <p>
 * Re-rendered images belong to this overlay, not to {@link IconCache}, and each one is
 * closed when the next replaces it, so do not hold on to {@link #getImage()} across a
 * resize. {@link #setImageFile} switches this overlay to that raster image for good.
 */
public class IconOverlay extends ImageOverlay {
    private static final double DEFAULT_SIZE = 24;
    /**
     * Seconds the size must stay the same before the SVG is re-rendered at it.
     */
    public static final float RERASTER_DELAY = 0.12f;

    private File svgFile;          // the SVG source is this file,
    private String resourcePath;   // or this classpath resource; both null after setImageFile
    private int rasterWidth, rasterHeight;   // the size the current image was rendered at
    private int pendingWidth, pendingHeight; // a new size waiting out RERASTER_DELAY
    private float pendingSeconds;
    private org.jetbrains.skia.Image ownedImage; // a re-rendered image this overlay must close; null while the cached one shows

    public IconOverlay(File svgFile, double width, double height) {
        super(IconCache.load(svgFile, (int) Math.round(width), (int) Math.round(height)), width, height);
        this.svgFile = svgFile;
        this.rasterWidth = (int) Math.round(width);
        this.rasterHeight = (int) Math.round(height);
    }

    public IconOverlay(String svgFilePath, double width, double height) {
        this(new File(svgFilePath), width, height);
    }

    public IconOverlay(IconAsset asset, double width, double height) {
        super(IconCache.loadResource(asset.resourcePath(), (int) Math.round(width), (int) Math.round(height)), width, height);
        this.resourcePath = asset.resourcePath();
        this.rasterWidth = (int) Math.round(width);
        this.rasterHeight = (int) Math.round(height);
    }

    /**
     * Same as {@link #IconOverlay(IconAsset, double, double)}, sized to {@link #DEFAULT_SIZE}x{@link #DEFAULT_SIZE}.
     */
    public IconOverlay(IconAsset asset) {
        this(asset, DEFAULT_SIZE, DEFAULT_SIZE);
    }

    /**
     * Shows another of the engine's bundled icons, at this overlay's current size.
     */
    public void setIcon(IconAsset asset) {
        int width = Math.max(1, (int) Math.round(getWidth())), height = Math.max(1, (int) Math.round(getHeight()));
        svgFile = null;
        resourcePath = asset.resourcePath();
        setImage(IconCache.loadResource(resourcePath, width, height));
        closeOwnedImage();
        rasterWidth = width;
        rasterHeight = height;
        pendingSeconds = 0;
    }

    /**
     * Shows a raster image file instead of the SVG, which stops this overlay re-rendering on resize.
     */
    @Override
    public void setImageFile(File imageFile) {
        svgFile = null;
        resourcePath = null;
        super.setImageFile(imageFile);
        closeOwnedImage();
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        if (!isEnabled() || (svgFile == null && resourcePath == null)) return;

        int width = (int) Math.round(getWidth()), height = (int) Math.round(getHeight());
        if ((width == rasterWidth && height == rasterHeight) || width <= 0 || height <= 0) {
            pendingSeconds = 0;
            return;
        }
        if (width != pendingWidth || height != pendingHeight) {
            pendingWidth = width;
            pendingHeight = height;
            pendingSeconds = 0;
            return;
        }
        pendingSeconds += delta;
        if (pendingSeconds >= RERASTER_DELAY) {
            rerasterize(width, height);
        }
    }

    private void rerasterize(int width, int height) {
        org.jetbrains.skia.Image image = svgFile != null
                ? IconCache.rasterizeFile(svgFile, width, height)
                : IconCache.rasterizeResource(resourcePath, width, height);
        setImage(image);
        closeOwnedImage();
        ownedImage = image;
        rasterWidth = width;
        rasterHeight = height;
        pendingSeconds = 0;
    }

    private void closeOwnedImage() {
        if (ownedImage != null) {
            ownedImage.close();
            ownedImage = null;
        }
    }
}