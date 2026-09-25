package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.image.ImageLoader;

import java.io.File;

/**
 * Draws an image loaded from disk, scaled to fill this Element's bounds.
 * <p>
 * Loading goes through {@link ImageLoader}, which sniffs the file's own bytes to pick a
 * codec rather than trusting the extension. PNG, JPEG, WEBP, GIF, BMP, and ICO all load
 * through the same constructor, limited only by which codecs the underlying Skia build
 * was compiled with.
 * <p>
 * Background/border are transparent and zero-thickness by default, and stay that way
 * under a theme, since an image overlay is usually meant to show only its own
 * pixels rather than a filled box behind them. Use {@link #getStyling()} as usual to
 * add one, for instance to letterbox behind a transparent PNG. Rounded corners reuse
 * {@link me.piitex.engine.ui.color.Styling#getCornerRadius()} rather than a separate
 * field, matching how every other Element's rounding works.
 * <p>
 * Unlike {@link TextOverlay} and {@link TextFieldOverlay}, there is no
 * {@code dispose()} here. The decoded {@link org.jetbrains.skia.Image} is owned by
 * {@link ImageLoader}, shared with every other overlay using the same file, and never
 * closed. See that class's docs for the trade-off this makes.
 */
public class ImageOverlay extends Overlay {
    private org.jetbrains.skia.Image image;
    private float opacity = 1f;
    private Color tint;

    /**
     * Takes an already-decoded {@code image} directly, bypassing {@link ImageLoader}.
     * This is for a subclass such as {@link IconOverlay} whose image comes from
     * somewhere other than a file-to-Image cache lookup.
     */
    protected ImageOverlay(org.jetbrains.skia.Image image, double width, double height) {
        this.image = image;
        setWidth(width);
        setHeight(height);
        applyDefaultStyling();
    }

    /**
     * Shows an already-decoded {@code image}, such as a {@link me.piitex.engine.gl.renderer.Snapshot}, sized to the
     * given bounds. The caller keeps ownership of the image and must keep it open while this overlay uses it.
     */
    public static ImageOverlay of(org.jetbrains.skia.Image image, double width, double height) {
        return new ImageOverlay(image, width, height);
    }

    /**
     * Loads {@code imageFile} and sizes this overlay to the image's natural pixel dimensions.
     */
    public ImageOverlay(File imageFile) {
        this.image = ImageLoader.load(imageFile);
        setWidth(image.getWidth());
        setHeight(image.getHeight());
        applyDefaultStyling();
    }

    /**
     * Loads {@code imageFile}, scaled to the given bounds instead of its natural size.
     */
    public ImageOverlay(File imageFile, double width, double height) {
        this.image = ImageLoader.load(imageFile);
        setWidth(width);
        setHeight(height);
        applyDefaultStyling();
    }

    public ImageOverlay(String imageFilePath) {
        this(new File(imageFilePath));
    }

    public ImageOverlay(String imageFilePath, double width, double height) {
        this(new File(imageFilePath), width, height);
    }

    private void applyDefaultStyling() {
        // Seeded as a baseline, not pinned, even though ImageOverlay is not Themeable
        // itself. This stays consistent with every other overlay's constructor, in case
        // a subclass such as IconOverlay opts into theming its own Styling.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
    }

    /**
     * Swaps the displayed image without changing this overlay's current width/height.
     */
    public void setImageFile(File imageFile) {
        this.image = ImageLoader.load(imageFile);
    }

    public void setImageFile(String imageFilePath) {
        setImageFile(new File(imageFilePath));
    }

    /**
     * The underlying decoded Skia image, for advanced use (e.g. building a shader from it).
     */
    public org.jetbrains.skia.Image getImage() {
        return image;
    }

    /**
     * The image's natural pixel width, independent of whatever {@link #getWidth()} is currently set to.
     */
    public int getNaturalWidth() {
        return image.getWidth();
    }

    /**
     * The image's natural pixel height, independent of whatever {@link #getHeight()} is currently set to.
     */
    public int getNaturalHeight() {
        return image.getHeight();
    }

    /**
     * Resizes this overlay to the image's natural pixel dimensions, undoing any explicit size override.
     */
    public void resetToNaturalSize() {
        setWidth(image.getWidth());
        setHeight(image.getHeight());
    }

    /**
     * Uniform opacity in [0, 1] (default 1 = fully opaque) the image is faded to; values outside that range are clamped at draw time.
     */
    public float getOpacity() {
        return opacity;
    }

    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }

    /**
     * Recolors this image's RGB to {@code tint} while keeping its own alpha as a mask.
     * Null, the default, draws the image with the colors already baked into it. This is
     * most useful for a monochrome or single-path asset, the common case for
     * {@link IconOverlay} and similar icon packs, that is meant to be recolored per use,
     * such as to match a theme or change color on hover, rather than shipped as a
     * separately colored file per state. On an image with varied colors of its own, it
     * only flattens them to one.
     */
    public void setTint(Color tint) {
        this.tint = tint;
    }

    public Color getTint() {
        return tint;
    }

    /**
     * Draws this overlay's background/border (transparent by default, see class docs)
     * via {@code super.render(...)}, then draws the image itself, scaled to fill
     * (x, y, width, height) and clipped to {@link me.piitex.engine.ui.color.Styling#getCornerRadius()}.
     */
    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;

        // Background/border (transparent by default) + fires the user's onRender hook.
        super.render(renderer);

        if (image != null) {
            renderer.drawImage(image, (float) getX(), (float) getY(), (float) getWidth(), (float) getHeight(), getStyling().getCornerRadius(), opacity, tint);
        }
    }
}
