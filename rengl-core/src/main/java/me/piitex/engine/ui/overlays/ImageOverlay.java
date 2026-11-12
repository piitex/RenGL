package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.image.AnimatedImage;
import me.piitex.engine.ui.image.ImageLoader;
import me.piitex.engine.ui.image.ImagePlayer;

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
 * An animated GIF (or animated WEBP) plays on its own: the overlay starts it when loaded and the engine
 * advances it every frame, looping as often as the file asks. {@link #getPlayer()} has the video-style controls,
 * and {@link #play()}, {@link #pause()} and {@link #stop()} are shortcuts for the common ones.
 * <pre>{@code
 * ImageOverlay cat = new ImageOverlay("assets/cat.gif", 320, 240);
 * cat.getPlayer().setLoopCount(1);
 * cat.getPlayer().onFinish(() -> cat.fadeOut(0.5f));
 * }</pre>
 * Playback is driven by {@link #update}, which a container calls for each element every frame; an overlay
 * drawn without being updated stays on its first frame.
 * <p>
 * Unlike {@link TextOverlay} and {@link TextFieldOverlay}, a still image has no
 * {@code dispose()} to call. The decoded {@link org.jetbrains.skia.Image} is owned by
 * {@link ImageLoader}, shared with every other overlay using the same file, and never
 * closed. See that class's docs for the trade-off this makes. An animation is the exception, as it is not
 * shared: each overlay has its own decoder and position, which {@link #dispose()} frees early rather than
 * waiting for garbage collection.
 */
public class ImageOverlay extends Overlay {
    private org.jetbrains.skia.Image image;
    private ImagePlayer player; // non-null while this overlay shows an animation
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
     * Plays {@code animation}, sized to the given bounds. The overlay takes ownership: {@link #dispose()} closes it.
     * Use this to play an animation you decoded yourself, such as from a zip file system or a byte array.
     */
    public static ImageOverlay of(AnimatedImage animation, double width, double height) {
        ImageOverlay overlay = new ImageOverlay((org.jetbrains.skia.Image) null, width, height);
        overlay.player = new ImagePlayer(animation);
        return overlay;
    }

    /**
     * Loads {@code imageFile} and sizes this overlay to the image's natural pixel dimensions.
     */
    public ImageOverlay(File imageFile) {
        setImageFile(imageFile);
        resetToNaturalSize();
        applyDefaultStyling();
    }

    /**
     * Loads {@code imageFile}, scaled to the given bounds instead of its natural size.
     */
    public ImageOverlay(File imageFile, double width, double height) {
        setImageFile(imageFile);
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
     * Swaps the displayed image without changing this overlay's current width/height. A GIF or WEBP with several
     * frames starts playing from its first frame; this also replaces an animation that was playing.
     */
    public void setImageFile(File imageFile) {
        AnimatedImage animation = ImageLoader.loadAnimated(imageFile);
        if (animation != null) {
            closePlayer();
            this.player = new ImagePlayer(animation);
            this.image = null;
        } else {
            org.jetbrains.skia.Image still = ImageLoader.load(imageFile);
            closePlayer();
            this.image = still;
        }
    }

    public void setImageFile(String imageFilePath) {
        setImageFile(new File(imageFilePath));
    }

    /**
     * Swaps the displayed image for an already-decoded one, without changing this overlay's size. For a subclass
     * that renders its own image, such as {@link IconOverlay} re-rasterizing after a resize.
     */
    protected void setImage(org.jetbrains.skia.Image image) {
        closePlayer();
        this.image = image;
    }

    /**
     * The underlying decoded Skia image, for advanced use (e.g. building a shader from it). For an animation this
     * is the frame showing now, which is closed or replaced as playback moves on, so use it right away.
     */
    public org.jetbrains.skia.Image getImage() {
        return player != null ? player.getFrame() : image;
    }

    /**
     * Whether this overlay is showing an animation, such as a GIF with several frames.
     */
    public boolean isAnimated() {
        return player != null;
    }

    /**
     * The playback controls (seek, loop count, speed, callbacks) of the animation this overlay is showing, or
     * null for a still image.
     */
    public ImagePlayer getPlayer() {
        return player;
    }

    /**
     * Plays or resumes an animation; after a play-once animation has ended, starts it again. Does nothing for a
     * still image.
     */
    public void play() {
        if (player != null) player.play();
    }

    /**
     * Holds an animation on its current frame. Does nothing for a still image.
     */
    public void pause() {
        if (player != null) player.pause();
    }

    /**
     * Stops an animation and returns it to its first frame. Does nothing for a still image.
     */
    public void stop() {
        if (player != null) player.stop();
    }

    /**
     * Whether an animation is playing now; false for a still image, or a paused or finished animation.
     */
    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    /**
     * Frees the decoder of an animation this overlay is showing. The overlay then draws nothing. Calling it is
     * optional, since the decoder is freed on garbage collection anyway, and it has no effect on a still image.
     */
    public void dispose() {
        closePlayer();
        image = null;
    }

    private void closePlayer() {
        if (player == null) return;
        player.getImage().close();
        player = null;
    }

    /**
     * The image's natural pixel width, independent of whatever {@link #getWidth()} is currently set to.
     */
    public int getNaturalWidth() {
        return player != null ? player.getImage().getWidth() : image.getWidth();
    }

    /**
     * The image's natural pixel height, independent of whatever {@link #getHeight()} is currently set to.
     */
    public int getNaturalHeight() {
        return player != null ? player.getImage().getHeight() : image.getHeight();
    }

    /**
     * Resizes this overlay to the image's natural pixel dimensions, undoing any explicit size override.
     */
    public void resetToNaturalSize() {
        setWidth(getNaturalWidth());
        setHeight(getNaturalHeight());
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
     * Moves an animation on by {@code delta} seconds. Nothing happens for a still image or a disabled overlay.
     */
    @Override
    public void update(float delta) {
        super.update(delta);
        if (player != null && isEnabled()) player.update(delta);
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

        org.jetbrains.skia.Image shown = getImage();
        if (shown != null) {
            renderer.drawImage(shown, (float) getX(), (float) getY(), (float) getWidth(), (float) getHeight(), getStyling().getCornerRadius(), opacity, tint);
        }
    }

    // --- Accessibility ---

    @Override
    protected me.piitex.engine.access.AccessibleRole defaultAccessibleRole() {
        return me.piitex.engine.access.AccessibleRole.IMAGE;
    }
}
