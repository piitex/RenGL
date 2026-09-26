package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.animation.Easing;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * An on/off switch: a pill-shaped track with a knob that slides from one end to the
 * other, the touch-style counterpart of {@link CheckBoxOverlay}. Clicking anywhere on it,
 * or pressing Space or Enter while it has keyboard focus, flips it, and the knob animates
 * across while the track color fades between its off and on colors.
 * <p>
 * Themed like the other controls: the off track uses {@link Theme#getFieldBorder()}, the
 * on track uses {@link Theme#getSelection()} at full opacity (the engine has no separate
 * accent token), and the knob is white. Pin any of them with {@link #setOffColor}, {@link
 * #setOnColor} and {@link #setKnobColor}. It is {@link Focusable} and a Tab stop; a focused
 * switch draws a ring around the track.
 * <p>
 * This overlay registers its own {@link #onMouseClick} handler to toggle. Replacing that
 * handler stops the switch from toggling, so use {@link #onToggle} to react to changes,
 * as with {@link CheckBoxOverlay}.
 */
public class ToggleSwitchOverlay extends Overlay implements Themeable, Focusable {
    private static final float ANIMATION_SECONDS = 0.16f;

    private boolean on;
    private float position;          // 0 = off end, 1 = on end; eased when drawn
    private boolean focused;
    private Consumer<Boolean> onToggle;
    private final Overridable<Color> offColor = new Overridable<>(Color.LIGHT_GRAY);
    private final Overridable<Color> onColor = new Overridable<>(new Color(0.60f, 0.78f, 1.0f, 1f));
    private final Overridable<Color> knobColor = new Overridable<>(Color.WHITE);

    /**
     * An off 44x24 switch.
     */
    public ToggleSwitchOverlay() {
        this(44, 24);
    }

    /**
     * An off switch of the given size; the knob is as tall as the track less a small inset.
     */
    public ToggleSwitchOverlay(double width, double height) {
        this(width, height, false);
    }

    public ToggleSwitchOverlay(double width, double height, boolean on) {
        this.on = on;
        this.position = on ? 1f : 0f;
        setWidth(width);
        setHeight(height);
        setCursorMode(CursorMode.POINTER);
        setFocusTraversable(true);
        // The track is drawn in render(), so the plain Element box must not paint underneath it.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        onMouseClick(event -> toggle());
        ThemeManager.register(this);
    }

    public boolean isOn() {
        return on;
    }

    /**
     * Sets the state programmatically: the knob jumps straight there and {@link #onToggle} does NOT fire.
     */
    public void setOn(boolean on) {
        this.on = on;
        this.position = on ? 1f : 0f;
    }

    /**
     * Flips the switch, with animation, and fires {@link #onToggle} with the new state. This is what a click runs.
     */
    public void toggle() {
        on = !on;
        if (onToggle != null) onToggle.accept(on);
    }

    /**
     * Fired with the new state whenever the USER flips the switch (click or keyboard). One listener is held at a time.
     */
    public void onToggle(Consumer<Boolean> onToggle) {
        this.onToggle = onToggle;
    }

    public Color getOffColor() {
        return offColor.get();
    }

    /**
     * Pins the off-track color against theme switches. See {@link Overridable}.
     */
    public void setOffColor(Color color) {
        offColor.set(color);
    }

    public Color getOnColor() {
        return onColor.get();
    }

    /**
     * Pins the on-track color against theme switches.
     */
    public void setOnColor(Color color) {
        onColor.set(color);
    }

    public Color getKnobColor() {
        return knobColor.get();
    }

    public void setKnobColor(Color color) {
        knobColor.set(color);
    }

    @Override
    public void applyTheme(Theme theme) {
        offColor.applyTheme(theme.getFieldBorder());
        Color selection = theme.getSelection();
        onColor.applyTheme(new Color(selection.getR(), selection.getG(), selection.getB(), 1f));
    }

    @Override
    public void followTheme() {
        super.followTheme();
        offColor.clearExplicit();
        onColor.clearExplicit();
        knobColor.clearExplicit();
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        float target = on ? 1f : 0f;
        if (position == target) return;
        float step = delta / ANIMATION_SECONDS;
        position = position < target ? Math.min(target, position + step) : Math.max(target, position - step);
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);

        float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
        float t = Easing.EASE_OUT.apply(position);
        Color off = offColor.get(), onC = onColor.get();
        Color track = new Color(
                off.getR() + (onC.getR() - off.getR()) * t,
                off.getG() + (onC.getG() - off.getG()) * t,
                off.getB() + (onC.getB() - off.getB()) * t,
                off.getA() + (onC.getA() - off.getA()) * t);

        renderer.drawQuad(x, y, w, h, track, h / 2f);
        if (isHovered()) {
            renderer.drawQuad(x, y, w, h, new Color(1f, 1f, 1f, 0.10f), h / 2f);
        }

        float inset = Math.max(2f, h * 0.14f);
        float radius = h / 2f - inset;
        float cx = x + h / 2f + (w - h) * t;
        float cy = y + h / 2f;
        renderer.drawCircle(cx, cy + 1f, radius, new Color(0f, 0f, 0f, 0.18f)); // soft shadow
        renderer.drawCircle(cx, cy, radius, knobColor.get());

        if (focused) {
            renderer.drawBorder(x - 2f, y - 2f, w + 4f, h + 4f, onC, 2f, h / 2f + 2f);
        }
    }

    @Override
    public void onFocusGained() {
        focused = true;
    }

    @Override
    public void onFocusLost() {
        focused = false;
    }

    @Override
    public void onCharTyped(int codepoint) {
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (action != GLFW.GLFW_PRESS) return;
        if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            toggle();
        }
    }
}
