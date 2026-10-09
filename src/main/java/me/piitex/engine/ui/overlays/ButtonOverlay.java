package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;

import java.io.File;

/**
 * A clickable, styled button. Extends {@link TextOverlay} to inherit its label/font
 * API wholesale, meaning the text, font file and size, and text color, for which that
 * class lists the exact methods, rather than redefining them here. It overrides the parts
 * that make a button different from free-floating text: an actual bounds box (like
 * {@link Container}, hence the {@code width}/{@code height}
 * constructor params TextOverlay doesn't take), an opaque {@link Theme}-driven default
 * styling (see {@link #applyTheme}) so it reads as a pressable box instead of
 * TextOverlay's transparent default, the label centered within that box instead of
 * anchored at the top-left corner, a pointer {@link CursorMode} on hover, and an
 * {@link #onAction} click callback.
 * <p>
 * The constructor registers its own {@link #onMouseClick} handler to fire
 * {@link #onAction}. Calling {@code onMouseClick(...)} afterward replaces that handler
 * and stops the button from firing, since {@link me.piitex.engine.ui.Element} holds only
 * one click listener at a time. That is the expected trade-off here, because a click on
 * a button is the action, unlike cases elsewhere in this engine where a hook is claimed
 * for something an application might not expect.
 */
public class ButtonOverlay extends TextOverlay implements Themeable {
    /**
     * How much of itself a {@link #setDisabled disabled} button draws, unless {@link #setDisabledOpacity} says otherwise.
     */
    public static final float DEFAULT_DISABLED_OPACITY = 0.45f;
    /**
     * Room kept clear at each side of the label when it is shortened with {@link #setEllipsize}.
     */
    private static final float EDGE_PADDING = 8f;

    private Runnable onAction;
    private Runnable onDisabledClick;
    private boolean disabled;
    private float disabledOpacity = DEFAULT_DISABLED_OPACITY;

    private IconOverlay icon;
    private double iconSize;          // 0 = follows the font size
    private double iconGap = 6;
    private boolean iconAfterText;

    /**
     * A button labeled with a 16pt black {@link me.piitex.engine.ui.text.Font#getDefault() default}-font label.
     */
    public ButtonOverlay(String text, double width, double height) {
        this(text, 16f, Color.BLACK, width, height);
    }

    /**
     * @param textColor a plain {@link Color} for a solid fill, or a
     *                  {@link me.piitex.engine.ui.color.LinearGradient}/
     *                  {@link me.piitex.engine.ui.color.ScrollingGradient} for a
     *                  gradient or animated fill. This is immediately overwritten by
     *                  the current {@link me.piitex.engine.ui.theme.Theme}'s text
     *                  color (see {@link #applyTheme}), and again on every future
     *                  theme switch, so it only sticks if you re-apply it with
     *                  {@link #setTextColor} afterward.
     */
    public ButtonOverlay(String text, float fontSize, Paint textColor, double width, double height) {
        super(text, fontSize, textColor);
        setWidth(width);
        setHeight(height);
        setCursorMode(CursorMode.POINTER);
        onMouseClick(event -> fireAction());
        setClickOnRelease(true);
        ThemeManager.register(this);
    }

    /**
     * Uses a custom font file (cached via {@link me.piitex.engine.ui.text.Font}) instead of the default font.
     */
    public ButtonOverlay(String text, File fontFile, float fontSize, Paint textColor, double width, double height) {
        super(text, fontFile, fontSize, textColor);
        setWidth(width);
        setHeight(height);
        setCursorMode(CursorMode.POINTER);
        onMouseClick(event -> fireAction());
        setClickOnRelease(true);
        ThemeManager.register(this);
    }

    /**
     * Applies {@code theme}'s button tokens to this button's box (background, hover,
     * border, corner radius) and its label color, as a baseline. {@code
     * super.applyTheme} themes the inherited label color the way {@link TextOverlay}
     * does, and each box property below takes effect only while it has not been set
     * explicitly through {@link #getStyling()}'s own setters. See
     * {@link me.piitex.engine.ui.theme.Overridable} for the exact rule, and
     * {@link Themeable} for when this runs.
     */
    @Override
    public void applyTheme(Theme theme) {
        super.applyTheme(theme); // labels color, via TextOverlay's own Overridable-backed textColor
        getStyling().applyThemeBackgroundColor(theme.getButtonBackground());
        getStyling().applyThemeHoverColor(theme.getButtonHoverBackground());
        getStyling().applyThemeBorderColor(theme.getButtonBorder());
        getStyling().applyThemeBorderThickness(theme.getBorderThickness());
        getStyling().applyThemeCornerRadius(theme.getCornerRadius());
    }

    private void fireAction() {
        if (disabled) {
            if (onDisabledClick != null) onDisabledClick.run();
            return;
        }
        if (onAction != null) {
            onAction.run();
        }
    }

    /**
     * Registers the callback fired when this button is clicked. Only one is held at a time; calling this again replaces the previous one.
     */
    public void onAction(Runnable onAction) {
        this.onAction = onAction;
    }

    /**
     * Shows this button as unavailable: drawn faded, with no hover color, a "not allowed" cursor, and a click that does
     * not fire {@link #onAction}. It stays visible and keeps its {@link #setTooltip tooltip}, so the tooltip can say
     * why. This is different from {@link #setEnabled}, which hides an element and removes it from the layout of input
     * altogether.
     * <p>
     * A click on a disabled button runs {@link #onDisabledClick}, if there is one.
     */
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Registers what a click on this button does while it is {@link #setDisabled disabled}, such as telling the person
     * why nothing happened. Only one is held; null removes it.
     */
    public void onDisabledClick(Runnable onDisabledClick) {
        this.onDisabledClick = onDisabledClick;
    }

    /**
     * How much of itself a {@link #setDisabled disabled} button draws, from 0 (nothing) to 1 (no change).
     * {@link #DEFAULT_DISABLED_OPACITY} by default. This is separate from {@link #setOpacity}, so a fade and a disabled
     * button combine.
     */
    public void setDisabledOpacity(float opacity) {
        this.disabledOpacity = Math.clamp(opacity, 0f, 1f);
    }

    public float getDisabledOpacity() {
        return disabledOpacity;
    }

    /**
     * A disabled button shows the "not allowed" cursor instead of the pointer.
     */
    @Override
    public CursorMode getCursorMode() {
        return disabled ? CursorMode.NOT_ALLOWED : super.getCursorMode();
    }

    /**
     * Draws the button, faded and without its hover color while {@link #isDisabled()}.
     */
    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        if (!disabled) {
            super.render(renderer);
            return;
        }
        boolean hovered = isHovered();
        setHovered(false); // a button that cannot be pressed does not light up under the mouse
        renderer.pushOpacityLayer((float) getX(), (float) getY(), (float) getWidth(), (float) getHeight(), disabledOpacity);
        try {
            super.render(renderer);
        } finally {
            renderer.popOpacityLayer();
            setHovered(hovered);
        }
    }

    // --- Icon ----------------------------------------------------------------------------------------------------

    /**
     * Puts an icon beside the label, the pair centered in the button, or the icon alone when the label is empty. The
     * icon is the label's color, so it follows the theme and {@link #setTextColor}, and fades with a
     * {@link #setDisabled disabled} button. Its size is {@link #setIconSize}, by default a little larger than the font.
     * It is part of the button: a click on it is a click on the button.
     * <p>
     * Pass null to take the icon away.
     */
    public void setIcon(IconAsset asset) {
        if (asset == null) {
            icon = null;
            return;
        }
        double size = iconDrawSize();
        if (icon == null) icon = new IconOverlay(asset, size, size);
        else icon.setIcon(asset);
    }

    public boolean hasIcon() {
        return icon != null;
    }

    /**
     * The icon's width and height in points; 0 or less, the default, sizes it to the font (1.25 times the font size).
     */
    public void setIconSize(double size) {
        this.iconSize = Math.max(0, size);
    }

    public double getIconSize() {
        return iconDrawSize();
    }

    /**
     * The space between the icon and the label, in points. 6 by default.
     */
    public void setIconGap(double gap) {
        this.iconGap = Math.max(0, gap);
    }

    /**
     * Puts the icon after the label instead of before it, such as for an arrow that says a menu opens.
     */
    public void setIconAfterText(boolean after) {
        this.iconAfterText = after;
    }

    public boolean isIconAfterText() {
        return iconAfterText;
    }

    private double iconDrawSize() {
        return iconSize > 0 ? iconSize : Math.round(getFontSize() * 1.25f);
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        if (icon != null) icon.update(delta);
    }

    /**
     * A no-op. Unlike a plain {@link TextOverlay}, a button's box size is set explicitly
     * through the {@code width} and {@code height} constructor parameters, and should
     * not auto-resize to fit its label whenever {@link #setText}, {@link #setFontSize},
     * or {@link #setFontFile} changes it.
     */
    @Override
    protected void updateComputedSize() {
    }

    /**
     * Centers {@link #getText()}, with the {@link #setIcon icon} if there is one, within this button's (width, height) box, instead of {@link TextOverlay}'s default of anchoring it at the top-left corner. With {@link #setEllipsize} a label too long for the box is shortened.
     */
    @Override
    protected void drawText(Renderer renderer) {
        String label = getText();
        boolean hasLabel = label != null && !label.isEmpty() && getTextColor() != null;
        if (!hasLabel && icon == null) return;

        Font font = getOrBuildFont();
        float iconWidth = icon != null ? (float) iconDrawSize() : 0f;
        float gap = icon != null && hasLabel ? (float) iconGap : 0f;

        String shown = "";
        float textWidth = 0f;
        if (hasLabel) {
            float limit = isEllipsize() ? Math.max(0f, (float) getWidth() - 2 * EDGE_PADDING - iconWidth - gap) : Float.MAX_VALUE;
            shown = fit(label, font, limit);
            textWidth = font.measureTextWidth(shown);
        }

        float contentWidth = iconWidth + gap + textWidth;
        float startX = (float) getX() + ((float) getWidth() - contentWidth) * textAlignH(0.5f);
        float iconX = iconAfterText ? startX + textWidth + gap : startX;
        float textX = iconAfterText || icon == null ? startX : startX + iconWidth + gap;

        if (icon != null) {
            icon.setWidth(iconWidth);
            icon.setHeight(iconWidth);
            icon.setX(iconX);
            icon.setY((float) getY() + ((float) getHeight() - iconWidth) * textAlignV(0.5f));
            icon.setTint(resolveTextColor(getTextColor()) instanceof Color color ? color : null);
            icon.render(renderer);
        }

        if (hasLabel && !shown.isEmpty()) {
            FontMetricsCompat metrics = FontMetricsCompat.of(font);
            float textHeight = -metrics.getAscent() + metrics.getDescent();
            float drawY = (float) getY() + ((float) getHeight() - textHeight) * textAlignV(0.5f);
            renderer.drawText(shown, textX, drawY, font, resolveTextColor(getTextColor()));
        }
    }
}
