package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;

import java.util.function.Consumer;

/**
 * A clickable, stateful square box that toggles between checked and unchecked on
 * click, filling the role JavaFX's and AtlantaFX's {@code CheckBox} plays. The box is
 * themed the same way {@link TextFieldOverlay}'s field box is, taking its background,
 * hover, and border from {@link Theme#getFieldBackground()},
 * {@link Theme#getFieldHoverBackground()}, and {@link Theme#getFieldBorder()}. The
 * checkmark drawn on top while {@link #isChecked()} uses {@link Theme#getAccent()}.
 * <p>
 * This class registers its own {@link #onMouseClick} handler to toggle state. Calling
 * {@code onMouseClick(...)} afterward replaces that handler and stops the checkbox from
 * toggling, the same trade-off {@link ButtonOverlay} documents for its own
 * {@code onAction}. Use {@link #onToggle} to react to state changes.
 */
public class CheckBoxOverlay extends Overlay implements Themeable {
    private boolean checked;
    private float checkThickness = 2.5f;
    private final Overridable<Paint> checkColor = new Overridable<>(Color.BLACK);
    private Consumer<Boolean> onToggle;

    /**
     * An unchecked 20x20 box.
     */
    public CheckBoxOverlay() {
        this(20);
    }

    /**
     * An unchecked {@code size}x{@code size} box.
     */
    public CheckBoxOverlay(double size) {
        this(size, false);
    }

    /**
     * A {@code size}x{@code size} box, initially checked or not.
     */
    public CheckBoxOverlay(double size, boolean checked) {
        this.checked = checked;
        setWidth(size);
        setHeight(size);
        setCursorMode(CursorMode.POINTER);
        onMouseClick(event -> toggle());
        setClickOnRelease(true);
        ThemeManager.register(this);
    }

    /**
     * Whether this box is currently checked.
     */
    public boolean isChecked() {
        return checked;
    }

    /**
     * Sets the checked state programmatically. Unlike a user click, this does not fire {@link #onToggle}.
     */
    public void setChecked(boolean checked) {
        this.checked = checked;
    }

    /**
     * Flips {@link #isChecked()} and fires {@link #onToggle} with the new value. This is what a click runs.
     */
    public void toggle() {
        checked = !checked;
        if (onToggle != null) {
            onToggle.accept(checked);
        }
    }

    /**
     * Registers the callback fired with this box's new checked state every time a click toggles it. Only one is held at a time; calling this again replaces the previous one.
     */
    public void onToggle(Consumer<Boolean> onToggle) {
        this.onToggle = onToggle;
    }

    /**
     * How thick the drawn checkmark's strokes are, independent of the box's own {@code width}/{@code height}.
     */
    public float getCheckThickness() {
        return checkThickness;
    }

    public void setCheckThickness(float checkThickness) {
        this.checkThickness = checkThickness;
    }

    public Paint getCheckColor() {
        return checkColor.get();
    }

    /**
     * Pins the checkmark color against every future theme switch. See {@link Overridable}.
     */
    public void setCheckColor(Paint checkColor) {
        this.checkColor.set(checkColor);
    }

    /**
     * Themes the box, meaning the background, hover, border, and corner radius field
     * tokens {@link TextFieldOverlay} also uses, along with the checkmark color, as a
     * baseline. It skips whichever property {@link #setCheckColor} or
     * {@link #getStyling()}'s own setters have already pinned. See {@link Overridable}.
     */
    @Override
    public void update(float delta) {
        super.update(delta);
        AnimatedPaint.advanceIfAnimated(checkColor.get(), delta);
    }

    @Override
    public void applyTheme(Theme theme) {
        getStyling().applyThemeBackgroundColor(theme.getFieldBackground());
        getStyling().applyThemeHoverColor(theme.getFieldHoverBackground());
        getStyling().applyThemeBorderColor(theme.getFieldBorder());
        getStyling().applyThemeBorderThickness(theme.getBorderThickness());
        getStyling().applyThemeCornerRadius(theme.getCornerRadius());
        checkColor.applyTheme(theme.getAccent());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        checkColor.clearExplicit();
    }

    /**
     * Draws the box (background/hover/border, via {@code super.render(...)}) then, while {@link #isChecked()}, a checkmark inset within it.
     */
    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;

        super.render(renderer);

        if (checked) {
            drawCheckmark(renderer);
        }
    }

    /**
     * Draws a two-stroke checkmark inset within this box's bounds, sized proportionally to the box rather than by a fixed pixel inset, so it reads correctly at both a small inline size and a larger touch-friendly one.
     */
    private void drawCheckmark(Renderer renderer) {
        float x = (float) getX();
        float y = (float) getY();
        float w = (float) getWidth();
        float h = (float) getHeight();
        Paint color = checkColor.get();

        float x1 = x + w * 0.20f, y1 = y + h * 0.52f;
        float x2 = x + w * 0.42f, y2 = y + h * 0.74f;
        float x3 = x + w * 0.80f, y3 = y + h * 0.26f;

        renderer.drawLine(x1, y1, x2, y2, color, checkThickness);
        renderer.drawLine(x2, y2, x3, y3, color, checkThickness);
    }
}
