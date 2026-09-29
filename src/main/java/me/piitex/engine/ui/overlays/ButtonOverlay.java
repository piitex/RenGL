package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.Font;
import org.jetbrains.skia.FontMetrics;

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
    private Runnable onAction;

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
     * A no-op. Unlike a plain {@link TextOverlay}, a button's box size is set explicitly
     * through the {@code width} and {@code height} constructor parameters, and should
     * not auto-resize to fit its label whenever {@link #setText}, {@link #setFontSize},
     * or {@link #setFontFile} changes it.
     */
    @Override
    protected void updateComputedSize() {
    }

    /**
     * Centers {@link #getText()} within this button's (width, height) box, instead of {@link TextOverlay}'s default of anchoring it at the top-left corner.
     */
    @Override
    protected void drawText(Renderer renderer) {
        String label = getText();
        if (label == null || label.isEmpty() || getTextColor() == null) return;

        Font font = getOrBuildFont();
        FontMetrics metrics = font.getMetrics();
        float textWidth = font.measureTextWidth(label);
        float textHeight = -metrics.getAscent() + metrics.getDescent();

        float drawX = (float) getX() + ((float) getWidth() - textWidth) * textAlignH(0.5f);
        float drawY = (float) getY() + ((float) getHeight() - textHeight) * textAlignV(0.5f);

        renderer.drawText(label, drawX, drawY, font, resolveTextColor(getTextColor()));
    }
}
