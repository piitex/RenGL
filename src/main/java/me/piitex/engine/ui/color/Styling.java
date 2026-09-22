package me.piitex.engine.ui.color;

import me.piitex.engine.ui.theme.Overridable;

/**
 * An Element's background/border/hover colors and border/corner geometry. Every field
 * is backed by an {@link Overridable}, so a {@link me.piitex.engine.ui.theme.Theme} can
 * drive it as a baseline (via the {@code applyThemeXxx} methods below, called from a
 * {@link me.piitex.engine.ui.theme.Themeable#applyTheme}) while any explicit {@code
 * setXxx} call from application code pins it against every future theme switch. See
 * {@link Overridable}'s own docs for the exact rule.
 */
public class Styling {
    private final Overridable<Paint> borderColor = new Overridable<>(Color.TRANSPARENT);
    private final Overridable<Paint> backgroundColor = new Overridable<>(Color.TRANSPARENT);
    private final Overridable<Paint> hoverColor = new Overridable<>(Color.TRANSPARENT);
    private boolean hoverFollowsBackground;
    private Paint textHoverColor;
    private final Overridable<Float> borderThickness = new Overridable<>(1f);
    private final Overridable<Float> cornerRadius = new Overridable<>(0.0f);

    public Paint getBorderColor() {
        return borderColor.get();
    }

    public void setBorderColor(Paint borderColor) {
        this.borderColor.set(borderColor);
    }

    /**
     * Theme-facing counterpart to {@link #setBorderColor}. It is a no-op once that setter has pinned this property. See {@link Overridable}.
     */
    public void applyThemeBorderColor(Paint borderColor) {
        this.borderColor.applyTheme(borderColor);
    }

    public Paint getBackgroundColor() {
        return backgroundColor.get();
    }

    public void setBackgroundColor(Paint backgroundColor) {
        this.backgroundColor.set(backgroundColor);
    }

    /**
     * Theme-facing counterpart to {@link #setBackgroundColor}. It is a no-op once that setter has pinned this property. See {@link Overridable}.
     */
    public void applyThemeBackgroundColor(Paint backgroundColor) {
        this.backgroundColor.applyTheme(backgroundColor);
    }

    /**
     * The color used while hovered. While {@link #setHoverFollowsBackground following the background} it is the background.
     */
    public Paint getHoverColor() {
        return hoverFollowsBackground ? backgroundColor.get() : hoverColor.get();
    }

    public void setHoverColor(Paint hoverColor) {
        this.hoverFollowsBackground = false;
        this.hoverColor.set(hoverColor);
    }

    /**
     * Makes the hover color track the background color, so an element with no hover feedback does not flash the
     * default white when the mouse passes over it. Containers start this way. Any explicit {@link #setHoverColor}
     * or theme hover color ends it.
     */
    public void setHoverFollowsBackground(boolean follows) {
        this.hoverFollowsBackground = follows;
    }

    /**
     * Theme-facing counterpart to {@link #setHoverColor}. It is a no-op once that setter has pinned this property. See {@link Overridable}.
     */
    public void applyThemeHoverColor(Paint hoverColor) {
        this.hoverFollowsBackground = false;
        this.hoverColor.applyTheme(hoverColor);
    }

    /**
     * The text color used while hovered, or null (the default) to keep the normal text color. Independent of {@link #getHoverColor()}, which paints the background.
     */
    public Paint getTextHoverColor() {
        return textHoverColor;
    }

    /**
     * Sets the text color shown while the element is hovered. Pass null to go back to the normal text color.
     */
    public void setTextHoverColor(Paint textHoverColor) {
        this.textHoverColor = textHoverColor;
    }

    public float getBorderThickness() {
        return borderThickness.get();
    }

    public void setBorderThickness(float borderThickness) {
        this.borderThickness.set(borderThickness);
    }

    /**
     * Theme-facing counterpart to {@link #setBorderThickness}. It is a no-op once that setter has pinned this property. See {@link Overridable}.
     */
    public void applyThemeBorderThickness(float borderThickness) {
        this.borderThickness.applyTheme(borderThickness);
    }

    public float getCornerRadius() {
        return cornerRadius.get();
    }

    public void setCornerRadius(float cornerRadius) {
        this.cornerRadius.set(cornerRadius);
    }

    /**
     * Theme-facing counterpart to {@link #setCornerRadius}. It is a no-op once that setter has pinned this property. See {@link Overridable}.
     */
    public void applyThemeCornerRadius(float cornerRadius) {
        this.cornerRadius.applyTheme(cornerRadius);
    }
}
