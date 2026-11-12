package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.text.TextShaping;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A slider with two thumbs that picks a range: a price filter's from-to, the levels a quest is open to, the part of a clip
 * to keep. The track between the thumbs is filled.
 * <pre>{@code
 * RangeSliderOverlay price = new RangeSliderOverlay(280, 0, 500);
 * price.setRange(50, 200);
 * price.setStep(10);
 * price.setLabelFormatter(v -> "$" + Math.round(v));
 * price.onRangeChange((low, high) -> applyFilter(low, high));
 * }</pre>
 * Pressing the track moves the nearer thumb there and keeps dragging it; the thumbs never cross, and stay at least
 * {@link #setMinSpan} apart. {@link #setRange} sets the two values programmatically without firing {@link
 * #onRangeChange}, the same convention {@link SliderOverlay#setValue} uses. Like {@link SliderOverlay} it can show
 * {@linkplain #setTickSpacing tick marks}, snap to a {@linkplain #setStep step}, and show a {@linkplain
 * #setLabelFormatter label} over each thumb.
 * <p>
 * With the keyboard (Tab to it), Left and Right move the thumb that has the keyboard by a step (a hundredth of the range
 * when there is no step), Shift moves ten times as far, Home and End jump it to its limit, and Up, Down or Space pick the
 * other thumb.
 * <p>
 * Colors come from the theme like {@link SliderOverlay}'s: the track from {@link Theme#getScrollTrack()}, the fill and
 * thumbs from {@link Theme#getAccent()}, the labels from {@link Theme#getText()}.
 */
public class RangeSliderOverlay extends Overlay implements Themeable, Draggable, Focusable, FontScalable {
    private double min;
    private double max;
    private double low;
    private double high;
    private double step; // <= 0 means continuous
    private double minSpan; // the least the thumbs can be apart

    private float trackThickness = 6f;
    private float thumbRadius = 8f;
    private double tickSpacing; // <= 0 means no ticks

    private Thumb dragging; // null when not dragging
    private Thumb active = Thumb.LOW; // the one the keyboard moves
    private boolean focused;

    private final Overridable<Paint> trackColor = new Overridable<>(Color.LIGHT_GRAY);
    private final Overridable<Paint> fillColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> thumbColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> tickColor = new Overridable<>(Color.LIGHT_GRAY);
    private final Overridable<Paint> labelColor = new Overridable<>(Color.BLACK);
    private Paint thumbActiveColor;

    private Function<Double, String> labelFormatter;
    private float labelFontSize = 12f;
    private Typeface typeface;
    private org.jetbrains.skia.Font labelFont;
    private boolean labelFontDirty = true;

    private BiConsumer<Double, Double> onRangeChange;

    private enum Thumb {LOW, HIGH}

    /**
     * A range slider {@code width} points long over {@code [min, max]}, with both thumbs at the ends.
     */
    public RangeSliderOverlay(double width, double min, double max) {
        this(width, 20, min, max, min, max);
    }

    /**
     * A range slider {@code width}x{@code height} points over {@code [min, max]}, starting on {@code [low, high]}
     * (clamped like {@link #setRange}).
     */
    public RangeSliderOverlay(double width, double height, double min, double max, double low, double high) {
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        this.typeface = Font.getDefault();
        setWidth(width);
        setHeight(height);
        setCursorMode(CursorMode.POINTER);
        setFocusTraversable(true);
        setRange(low, high);
        // Drawn entirely by hand, so Element's opaque box would only paint behind it. See SliderOverlay.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        ThemeManager.register(this);
    }

    /* Range */

    public double getMin() {
        return min;
    }

    /**
     * Also re-clamps the current range into the new limits.
     */
    public void setMin(double min) {
        this.min = Math.min(min, max);
        setRange(low, high);
    }

    public double getMax() {
        return max;
    }

    public void setMax(double max) {
        this.max = Math.max(max, min);
        setRange(low, high);
    }

    public double getLow() {
        return low;
    }

    public double getHigh() {
        return high;
    }

    /**
     * Sets both thumbs, clamped to {@code [min, max]}, snapped to the {@link #setStep step} and kept {@link #setMinSpan}
     * apart (the high thumb gives way first). If {@code low} is more than {@code high} they swap. Does not fire {@link
     * #onRangeChange}.
     */
    public void setRange(double low, double high) {
        double a = snap(Math.min(low, high)), b = snap(Math.max(low, high));
        double span = Math.min(minSpan, max - min);
        if (b - a < span) {
            if (a + span <= max) b = snap(a + span);
            else a = snap(b - span);
        }
        this.low = clamp(a);
        this.high = clamp(Math.max(this.low, b));
    }

    public double getStep() {
        return step;
    }

    /**
     * The grid both values snap to; 1 restricts them to whole numbers. 0 or less, the default, is continuous.
     */
    public void setStep(double step) {
        this.step = step;
        setRange(low, high);
    }

    public double getMinSpan() {
        return minSpan;
    }

    /**
     * The least the thumbs can be apart, in value units. Default 0, so they can meet.
     */
    public void setMinSpan(double minSpan) {
        this.minSpan = Math.max(0, minSpan);
        setRange(low, high);
    }

    private double clamp(double v) {
        return Math.max(min, Math.min(max, v));
    }

    private double snap(double raw) {
        double clamped = clamp(raw);
        if (step > 0) {
            clamped = clamp(min + Math.round((clamped - min) / step) * step);
        }
        return clamped;
    }

    /**
     * Fired with {@code (low, high)} when the user changes either thumb, by dragging, clicking the track or keys. Not fired
     * by {@link #setRange}.
     */
    public void onRangeChange(BiConsumer<Double, Double> onRangeChange) {
        this.onRangeChange = onRangeChange;
    }

    /* Geometry and look */

    public float getTrackThickness() {
        return trackThickness;
    }

    public void setTrackThickness(float trackThickness) {
        this.trackThickness = trackThickness;
    }

    public float getThumbRadius() {
        return thumbRadius;
    }

    /**
     * The thumbs' radius. The track is inset by this at both ends so a thumb never leaves the slider's box.
     */
    public void setThumbRadius(float thumbRadius) {
        this.thumbRadius = thumbRadius;
    }

    public double getTickSpacing() {
        return tickSpacing;
    }

    /**
     * The value spacing between tick marks; 0 or less, the default, draws none.
     */
    public void setTickSpacing(double tickSpacing) {
        this.tickSpacing = tickSpacing;
    }

    public Paint getTrackColor() {
        return trackColor.get();
    }

    public void setTrackColor(Paint trackColor) {
        this.trackColor.set(trackColor);
    }

    public Paint getFillColor() {
        return fillColor.get();
    }

    /**
     * The color of the part of the track between the thumbs.
     */
    public void setFillColor(Paint fillColor) {
        this.fillColor.set(fillColor);
    }

    public Paint getThumbColor() {
        return thumbColor.get();
    }

    public void setThumbColor(Paint thumbColor) {
        this.thumbColor.set(thumbColor);
    }

    public Paint getThumbActiveColor() {
        return thumbActiveColor;
    }

    /**
     * The color of the thumb being dragged; null, the default, leaves it as it is.
     */
    public void setThumbActiveColor(Paint thumbActiveColor) {
        this.thumbActiveColor = thumbActiveColor;
    }

    public Paint getTickColor() {
        return tickColor.get();
    }

    public void setTickColor(Paint tickColor) {
        this.tickColor.set(tickColor);
    }

    public Paint getLabelColor() {
        return labelColor.get();
    }

    public void setLabelColor(Paint labelColor) {
        this.labelColor.set(labelColor);
    }

    /**
     * Formats a thumb's value into the text drawn above it, every frame. Null, the default, draws no labels. Leave
     * headroom above the track for them, as with {@link SliderOverlay#setLabelFormatter}.
     */
    public void setLabelFormatter(Function<Double, String> labelFormatter) {
        this.labelFormatter = labelFormatter;
    }

    public Function<Double, String> getLabelFormatter() {
        return labelFormatter;
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

    /* Interaction */

    public boolean isDragging() {
        return dragging != null;
    }

    /**
     * A press on the track moves the nearer thumb there; when the thumbs are together, the one on the side the press is.
     */
    @Override
    public void onDragStart(double x, double y) {
        double mx = toLocalX(x);
        double lowX = valueToX(low), highX = valueToX(high);
        double dl = Math.abs(mx - lowX), dh = Math.abs(mx - highX);
        dragging = dl < dh ? Thumb.LOW : dh < dl ? Thumb.HIGH : (mx < lowX ? Thumb.LOW : Thumb.HIGH);
        active = dragging;
        moveThumbTo(dragging, mx);
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (dragging != null) moveThumbTo(dragging, toLocalX(x));
    }

    @Override
    public void onDragEnd(double x, double y) {
        dragging = null;
    }

    private void moveThumbTo(Thumb thumb, double mouseX) {
        setThumb(thumb, xToValue(mouseX));
    }

    private void setThumb(Thumb thumb, double raw) {
        double span = Math.min(minSpan, max - min);
        double newLow = low, newHigh = high;
        double v = snap(raw);
        if (thumb == Thumb.LOW) newLow = Math.min(v, Math.max(min, high - span));
        else newHigh = Math.max(v, Math.min(max, low + span));
        // A step can leave the clamped value off the grid; the grid wins.
        newLow = snap(newLow);
        newHigh = snap(newHigh);
        if (newLow != low || newHigh != high) {
            low = newLow;
            high = newHigh;
            if (onRangeChange != null) onRangeChange.accept(low, high);
        }
    }

    public boolean isFocused() {
        return focused;
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
        double unit = step > 0 ? step : (max - min) / 100.0;
        if ((mods & GLFW.GLFW_MOD_SHIFT) != 0) unit *= 10;
        double current = active == Thumb.LOW ? low : high;
        double lowBefore = low, highBefore = high;
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> setThumb(active, current - unit);
            case GLFW.GLFW_KEY_RIGHT -> setThumb(active, current + unit);
            case GLFW.GLFW_KEY_HOME -> setThumb(active, min);
            case GLFW.GLFW_KEY_END -> setThumb(active, max);
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_SPACE -> {
                if (action == GLFW.GLFW_PRESS) {
                    active = active == Thumb.LOW ? Thumb.HIGH : Thumb.LOW;
                    me.piitex.engine.Window window = getWindow();
                    if (window != null) window.announce(active == Thumb.LOW ? "Adjusting the lower value" : "Adjusting the upper value");
                }
            }
            default -> {
            }
        }
        if (low != lowBefore || high != highBefore) notifyAccessibleChange();
    }

    /**
     * Like a slider, a Tab stop that does not take the keyboard from a game on a click, and keeps only the keys it
     * moves with.
     */
    @Override
    public boolean grabsKeyboard() {
        return false;
    }

    @Override
    public boolean handlesKey(int key, int mods) {
        return switch (key) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN,
                 GLFW.GLFW_KEY_HOME, GLFW.GLFW_KEY_END, GLFW.GLFW_KEY_SPACE -> true;
            default -> false;
        };
    }

    // --- Accessibility ---

    @Override
    protected me.piitex.engine.access.AccessibleRole defaultAccessibleRole() {
        return me.piitex.engine.access.AccessibleRole.SLIDER;
    }

    /**
     * Both ends, such as "50 to 200", through the label formatter when there is one.
     */
    @Override
    public String getAccessibleValue() {
        return accessibleText(low) + " to " + accessibleText(high);
    }

    private String accessibleText(double v) {
        if (labelFormatter != null) return labelFormatter.apply(v);
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    @Override
    protected boolean usesFocusRing() {
        return true;
    }

    /* Value <-> pixel */

    private double trackStartX() {
        return getX() + thumbRadius;
    }

    private double trackWidth() {
        return Math.max(0, getWidth() - 2 * thumbRadius);
    }

    private double valueToX(double v) {
        double range = max - min;
        return trackStartX() + (range <= 0 ? 0 : (v - min) / range) * trackWidth();
    }

    private double xToValue(double x) {
        double width = trackWidth();
        double ratio = width <= 0 ? 0 : (x - trackStartX()) / width;
        return min + Math.max(0, Math.min(1, ratio)) * (max - min);
    }

    /* Theme */

    @Override
    public void applyTheme(Theme theme) {
        trackColor.applyTheme(theme.getScrollTrack());
        fillColor.applyTheme(theme.getAccent());
        thumbColor.applyTheme(theme.getAccent());
        tickColor.applyTheme(theme.getFieldBorder());
        labelColor.applyTheme(theme.getText());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        trackColor.clearExplicit();
        fillColor.clearExplicit();
        thumbColor.clearExplicit();
        tickColor.clearExplicit();
        labelColor.clearExplicit();
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        AnimatedPaint.advanceIfAnimated(trackColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(fillColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(thumbColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(thumbActiveColor, delta);
        AnimatedPaint.advanceIfAnimated(tickColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(labelColor.get(), delta);
    }

    /* Rendering */

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);

        float trackY = (float) (getY() + getHeight() / 2.0);
        float tx = (float) trackStartX(), tw = (float) trackWidth(), th = trackThickness;
        renderer.drawQuad(tx, trackY - th / 2f, tw, th, trackColor.get(), th / 2f);

        float lowX = (float) valueToX(low), highX = (float) valueToX(high);
        renderer.drawQuad(lowX, trackY - th / 2f, Math.max(0f, highX - lowX), th, fillColor.get(), th / 2f);

        if (tickSpacing > 0 && max > min) {
            float tickHeight = th + 6f;
            for (double v = min; v < max; v += tickSpacing) {
                float x = (float) valueToX(v);
                renderer.drawLine(x, trackY - tickHeight / 2f, x, trackY + tickHeight / 2f, tickColor.get(), 1.5f);
            }
            float x = (float) valueToX(max);
            renderer.drawLine(x, trackY - tickHeight / 2f, x, trackY + tickHeight / 2f, tickColor.get(), 1.5f);
        }

        drawThumb(renderer, Thumb.LOW, lowX, trackY);
        drawThumb(renderer, Thumb.HIGH, highX, trackY);

        if (labelFormatter != null) {
            drawLabel(renderer, low, lowX, trackY);
            drawLabel(renderer, high, highX, trackY);
        }
    }

    private void drawThumb(Renderer renderer, Thumb thumb, float x, float trackY) {
        Paint paint = dragging == thumb && thumbActiveColor != null ? thumbActiveColor : thumbColor.get();
        renderer.drawCircle(x, trackY, thumbRadius, paint);
        if (focused && active == thumb) {
            renderer.drawBorder(x - thumbRadius - 3f, trackY - thumbRadius - 3f, (thumbRadius + 3f) * 2f, (thumbRadius + 3f) * 2f,
                    fillColor.get(), 2f, thumbRadius + 3f);
        }
    }

    private void drawLabel(Renderer renderer, double value, float thumbX, float trackY) {
        String label = labelFormatter.apply(value);
        if (label == null || label.isEmpty()) return;
        org.jetbrains.skia.Font font = getLabelFont();
        FontMetricsCompat metrics = FontMetricsCompat.of(font);
        float textWidth = TextShaping.width(font, label);
        float textHeight = -metrics.getAscent() + metrics.getDescent();
        renderer.drawText(label, thumbX - textWidth / 2f, trackY - thumbRadius - 4f - textHeight, font, resolveTextColor(labelColor.get()));
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
     * Frees the font used for the labels, if any were drawn.
     */
    public void dispose() {
        if (labelFont != null) {
            labelFont.close();
            labelFont = null;
        }
    }
}
