package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.text.TextShaping;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.scroll.Scrollable;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A round dial the user turns by dragging up and down: a volume knob, a brightness control, a synth's filter cutoff. It
 * sweeps 270 degrees, with the value shown as an arc around the rim and a notch on the knob.
 * <pre>{@code
 * KnobOverlay volume = new KnobOverlay(64, 0, 100);
 * volume.setValue(70);
 * volume.setDefaultValue(70);
 * volume.setLabelFormatter(v -> Math.round(v) + "%");
 * volume.onValueChange(v -> audio.setVolume(v / 100));
 * }</pre>
 * Dragging up raises the value and dragging down lowers it; the whole range takes {@linkplain #setDragRange 200 points} of
 * drag, and holding Shift makes the drag five times finer. The wheel and the arrow keys (Tab to it) turn it by a step, Home
 * and End jump to the ends, and a double-click puts it back to its {@linkplain #setDefaultValue default}. {@link
 * #setValue} sets the value without firing {@link #onValueChange}, as {@link SliderOverlay#setValue} does.
 * <p>
 * Colors come from the theme: the groove from {@link Theme#getScrollTrack()}, the value arc and notch from {@link
 * Theme#getAccent()}, the knob face from {@link Theme#getFieldBackground()} and the label from {@link Theme#getText()}.
 */
public class KnobOverlay extends Overlay implements Themeable, Draggable, Focusable, Scrollable, FontScalable {
    /** Where the sweep starts: 135 degrees clockwise from 3 o'clock is the lower left. */
    static final float START_DEGREES = 135f;
    /** How far the sweep goes: to the lower right. */
    static final float SWEEP_DEGREES = 270f;
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;

    private double min;
    private double max;
    private double value;
    private double defaultValue;
    private boolean hasDefault;
    private double step; // <= 0 means continuous
    private double dragRange = 200; // points of vertical drag for the whole range

    private float arcThickness = 5f;
    private boolean showValueText = true;
    private Function<Double, String> labelFormatter = v -> String.valueOf(Math.round(v * 100.0) / 100.0);
    private float labelFontSize = 12f;
    private final Typeface typeface = Font.getDefault();
    private org.jetbrains.skia.Font labelFont;
    private boolean labelFontDirty = true;

    private final Overridable<Paint> grooveColor = new Overridable<>(Color.LIGHT_GRAY);
    private final Overridable<Paint> arcColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> faceColor = new Overridable<>(Color.WHITE);
    private final Overridable<Paint> notchColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> labelColor = new Overridable<>(Color.BLACK);

    private Consumer<Double> onValueChange;
    private boolean dragging;
    private double dragStartY;
    private double dragStartValue;
    private boolean focused;
    private long lastPressNanos;

    /**
     * A knob {@code diameter} points across over {@code [min, max]}, starting at {@code min}.
     */
    public KnobOverlay(double diameter, double min, double max) {
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        this.value = this.min;
        setWidth(diameter);
        setHeight(diameter);
        setCursorMode(CursorMode.POINTER);
        setFocusTraversable(true);
        // Drawn entirely by hand, so Element's opaque box would only paint behind it. See SliderOverlay.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        ThemeManager.register(this);
    }

    /* Value */

    public double getMin() {
        return min;
    }

    public void setMin(double min) {
        this.min = Math.min(min, max);
        value = snap(value);
    }

    public double getMax() {
        return max;
    }

    public void setMax(double max) {
        this.max = Math.max(max, min);
        value = snap(value);
    }

    public double getValue() {
        return value;
    }

    /**
     * Sets the value, clamped to {@code [min, max]} and snapped to the {@link #setStep step}. Does not fire {@link
     * #onValueChange}.
     */
    public void setValue(double value) {
        this.value = snap(value);
    }

    /**
     * The value as a fraction of the range, 0 at {@code min} and 1 at {@code max}.
     */
    public double getRatio() {
        return max <= min ? 0 : (value - min) / (max - min);
    }

    public double getStep() {
        return step;
    }

    /**
     * The grid the value snaps to; 1 restricts it to whole numbers. 0 or less, the default, is continuous. Also the size of
     * one wheel notch or arrow press; without a step that is a hundredth of the range.
     */
    public void setStep(double step) {
        this.step = step;
        value = snap(value);
    }

    /**
     * The value a double-click goes back to. Without one a double-click does nothing.
     */
    public void setDefaultValue(double defaultValue) {
        this.defaultValue = snap(defaultValue);
        this.hasDefault = true;
    }

    public double getDefaultValue() {
        return defaultValue;
    }

    /**
     * How many points of vertical drag cover the whole range; 200 by default. Dragging with Shift held is five times finer.
     */
    public void setDragRange(double dragRange) {
        this.dragRange = Math.max(1, dragRange);
    }

    /**
     * Fired with the new value when the user changes it, by dragging, the wheel, keys or a double-click. Not fired by {@link
     * #setValue}.
     */
    public void onValueChange(Consumer<Double> onValueChange) {
        this.onValueChange = onValueChange;
    }

    private double snap(double raw) {
        double v = Math.max(min, Math.min(max, raw));
        if (step > 0) v = Math.max(min, Math.min(max, min + Math.round((v - min) / step) * step));
        return v;
    }

    private void change(double raw) {
        double next = snap(raw);
        if (next == value) return;
        value = next;
        if (onValueChange != null) onValueChange.accept(value);
    }

    private double unit() {
        return step > 0 ? step : (max - min) / 100.0;
    }

    /* Look */

    public float getArcThickness() {
        return arcThickness;
    }

    public void setArcThickness(float arcThickness) {
        this.arcThickness = arcThickness;
    }

    public boolean isShowValueText() {
        return showValueText;
    }

    /**
     * Whether the formatted value is drawn on the knob's face. On by default; a knob under 40 points looks better without.
     */
    public void setShowValueText(boolean showValueText) {
        this.showValueText = showValueText;
    }

    /**
     * Turns the value into the text drawn on the face. By default the value rounded to two decimals.
     */
    public void setLabelFormatter(Function<Double, String> labelFormatter) {
        this.labelFormatter = labelFormatter == null ? v -> String.valueOf(v) : labelFormatter;
    }

    public float getLabelFontSize() {
        return labelFontSize;
    }

    public void setLabelFontSize(float labelFontSize) {
        this.labelFontSize = labelFontSize;
        labelFontDirty = true;
    }

    @Override
    public float[] getFontSizes() {
        return new float[]{labelFontSize};
    }

    @Override
    public void setFontSizes(float[] sizes) {
        setLabelFontSize(sizes[0]);
    }

    public Paint getGrooveColor() {
        return grooveColor.get();
    }

    public void setGrooveColor(Paint grooveColor) {
        this.grooveColor.set(grooveColor);
    }

    public Paint getArcColor() {
        return arcColor.get();
    }

    /**
     * The color of the arc that shows the value.
     */
    public void setArcColor(Paint arcColor) {
        this.arcColor.set(arcColor);
    }

    public Paint getFaceColor() {
        return faceColor.get();
    }

    public void setFaceColor(Paint faceColor) {
        this.faceColor.set(faceColor);
    }

    public Paint getNotchColor() {
        return notchColor.get();
    }

    public void setNotchColor(Paint notchColor) {
        this.notchColor.set(notchColor);
    }

    public Paint getLabelColor() {
        return labelColor.get();
    }

    public void setLabelColor(Paint labelColor) {
        this.labelColor.set(labelColor);
    }

    /* Interaction */

    public boolean isDragging() {
        return dragging;
    }

    public boolean isFocused() {
        return focused;
    }

    @Override
    public void onDragStart(double x, double y) {
        long now = System.nanoTime();
        boolean doubleClick = lastPressNanos != 0 && now - lastPressNanos < DOUBLE_CLICK_NANOS;
        lastPressNanos = doubleClick ? 0 : now;
        if (doubleClick && hasDefault) {
            change(defaultValue);
            return;
        }
        dragging = true;
        dragStartY = y;
        dragStartValue = value;
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (!dragging) return;
        // The press is measured from where it began, not from the last move, so a snapped step does not lose the drag.
        double fine = (heldModifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? 0.2 : 1.0;
        change(dragStartValue + (dragStartY - y) / dragRange * (max - min) * fine);
    }

    @Override
    public void onDragEnd(double x, double y) {
        dragging = false;
    }

    /**
     * The GLFW modifier bits held now, from the window's input. Separate so a test can say so without a window.
     */
    protected int heldModifiers() {
        me.piitex.engine.Window window = getWindow();
        return window == null ? 0 : window.getInput().getModifiers();
    }

    @Override
    public void onScroll(double deltaX, double deltaY) {
        change(value + Math.signum(deltaY) * unit());
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
        if (action == GLFW.GLFW_RELEASE) return;
        double unit = unit() * ((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? 10 : 1);
        switch (key) {
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP -> change(value + unit);
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_DOWN -> change(value - unit);
            case GLFW.GLFW_KEY_HOME -> change(min);
            case GLFW.GLFW_KEY_END -> change(max);
            default -> {
            }
        }
    }

    /* Theme */

    @Override
    public void applyTheme(Theme theme) {
        grooveColor.applyTheme(theme.getScrollTrack());
        arcColor.applyTheme(theme.getAccent());
        faceColor.applyTheme(theme.getFieldBackground());
        notchColor.applyTheme(theme.getAccent());
        labelColor.applyTheme(theme.getText());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        grooveColor.clearExplicit();
        arcColor.clearExplicit();
        faceColor.clearExplicit();
        notchColor.clearExplicit();
        labelColor.clearExplicit();
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        AnimatedPaint.advanceIfAnimated(grooveColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(arcColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(faceColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(notchColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(labelColor.get(), delta);
    }

    /* Rendering */

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);

        float cx = (float) (getX() + getWidth() / 2.0), cy = (float) (getY() + getHeight() / 2.0);
        float outer = (float) (Math.min(getWidth(), getHeight()) / 2.0) - 2f;
        float ringR = outer - arcThickness / 2f;
        float faceR = Math.max(0f, outer - arcThickness - 3f);

        float[] groove = Arcs.arc(cx, cy, ringR, START_DEGREES, SWEEP_DEGREES);
        renderer.drawPolyline(groove, Arcs.count(groove), grooveColor.get(), arcThickness);
        double ratio = getRatio();
        if (ratio > 0) {
            float[] arc = Arcs.arc(cx, cy, ringR, START_DEGREES, (float) (SWEEP_DEGREES * ratio));
            renderer.drawPolyline(arc, Arcs.count(arc), arcColor.get(), arcThickness);
        }

        renderer.drawCircle(cx, cy, faceR, faceColor.get());
        double angle = Math.toRadians(START_DEGREES + SWEEP_DEGREES * ratio);
        float nx = (float) Math.cos(angle), ny = (float) Math.sin(angle);
        renderer.drawLine(cx + nx * faceR * 0.45f, cy + ny * faceR * 0.45f, cx + nx * faceR * 0.9f, cy + ny * faceR * 0.9f,
                notchColor.get(), Math.max(2f, arcThickness * 0.6f));
        if (focused) {
            renderer.drawBorder(cx - outer - 2f, cy - outer - 2f, (outer + 2f) * 2f, (outer + 2f) * 2f, arcColor.get(), 1.5f, outer + 2f);
        }

        if (showValueText && faceR >= 16f) {
            String label = labelFormatter.apply(value);
            if (label != null && !label.isEmpty()) {
                org.jetbrains.skia.Font font = getLabelFont();
                FontMetricsCompat metrics = FontMetricsCompat.of(font);
                float w = TextShaping.width(font, label);
                float h = -metrics.getAscent() + metrics.getDescent();
                renderer.drawText(label, cx - w / 2f, cy + faceR * 0.25f - h / 2f, font, resolveTextColor(labelColor.get()));
            }
        }
    }

    private org.jetbrains.skia.Font getLabelFont() {
        if (labelFontDirty || labelFont == null) {
            if (labelFont != null) labelFont.close();
            labelFont = new org.jetbrains.skia.Font(typeface, labelFontSize);
            labelFontDirty = false;
        }
        return labelFont;
    }

    /**
     * Frees the font used for the label, if one was drawn.
     */
    public void dispose() {
        if (labelFont != null) {
            labelFont.close();
            labelFont = null;
        }
    }
}
