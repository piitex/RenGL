package me.piitex.engine.ui.scroll;

import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.theme.Overridable;

/**
 * The visual settings for a {@link ScrollContainer}'s scrollbars: track and thumb
 * colors, thickness, corner rounding, the gap kept between the bar and the container's
 * own edges, and the thumb's minimum length. Like
 * {@link me.piitex.engine.ui.color.Styling}, this is a mutable value object, so set
 * what should change and leave the rest at their defaults. One instance covers both the
 * vertical and horizontal bar on a given {@link ScrollContainer}; pass two different
 * {@code ScrollBarStyle} instances through
 * {@link ScrollContainer#setScrollBarStyle} to make them look different.
 * <p>
 * Colors are {@link Paint}s: a solid {@link Color}, a gradient, or an animated gradient, which the owner advances
 * every frame.
 */
public class ScrollBarStyle {
    private float thickness = 10f;
    private float margin = 2f;
    private float cornerRadius = 4f;
    private float minThumbLength = 24f;
    private final Overridable<Paint> trackColor = new Overridable<>(new Color(0f, 0f, 0f, 0.06f));
    private final Overridable<Paint> thumbColor = new Overridable<>(new Color(0f, 0f, 0f, 0.35f));
    private Paint thumbActiveColor = new Color(0f, 0f, 0f, 0.55f);

    /**
     * How thick the bar is (its width for a vertical bar, height for a horizontal one), in logical points.
     */
    public float getThickness() {
        return thickness;
    }

    public void setThickness(float thickness) {
        this.thickness = thickness;
    }

    /**
     * Gap kept between the bar and the container's own edges, on every side.
     */
    public float getMargin() {
        return margin;
    }

    public void setMargin(float margin) {
        this.margin = margin;
    }

    /**
     * Corner radius applied to both the track and the thumb.
     */
    public float getCornerRadius() {
        return cornerRadius;
    }

    public void setCornerRadius(float cornerRadius) {
        this.cornerRadius = cornerRadius;
    }

    /**
     * The thumb never draws shorter than this, in logical points, no matter how much taller the content is than the viewport. This keeps it grabbable for very long content.
     */
    public float getMinThumbLength() {
        return minThumbLength;
    }

    public void setMinThumbLength(float minThumbLength) {
        this.minThumbLength = minThumbLength;
    }

    /**
     * The bar's background strip, drawn full-length behind the thumb. Null draws no track (just a floating thumb).
     */
    public Paint getTrackColor() {
        return trackColor.get();
    }

    public void setTrackColor(Paint trackColor) {
        this.trackColor.set(trackColor);
    }

    /**
     * Theme-facing counterpart to {@link #setTrackColor}. It is a no-op once that setter has pinned this property. See {@link Overridable}.
     */
    public void applyThemeTrackColor(Paint trackColor) {
        this.trackColor.applyTheme(trackColor);
    }

    /**
     * The draggable thumb's color while idle, meaning not being dragged. Null draws no thumb at all, leaving no visible scrollbar, though wheel and programmatic scrolling still work.
     */
    public Paint getThumbColor() {
        return thumbColor.get();
    }

    public void setThumbColor(Paint thumbColor) {
        this.thumbColor.set(thumbColor);
    }

    /**
     * Theme-facing counterpart to {@link #setThumbColor}. It is a no-op once that setter has pinned this property. See {@link Overridable}.
     */
    public void applyThemeThumbColor(Paint thumbColor) {
        this.thumbColor.applyTheme(thumbColor);
    }

    /**
     * The thumb's color while actively being dragged, in place of {@link #getThumbColor()}.
     */
    public Paint getThumbActiveColor() {
        return thumbActiveColor;
    }

    public void setThumbActiveColor(Paint thumbActiveColor) {
        this.thumbActiveColor = thumbActiveColor;
    }

    /**
     * Advances any {@link AnimatedPaint} among the colors. The scrollbar's owner calls this every frame, so give each
     * owner its own style when a color is animated.
     */
    public void advance(float delta) {
        AnimatedPaint.advanceIfAnimated(trackColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(thumbColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(thumbActiveColor, delta);
    }

    /**
     * Undoes every explicit color set on this style, so they all follow the theme again, starting now.
     */
    public void followTheme() {
        trackColor.clearExplicit();
        thumbColor.clearExplicit();
    }
}
