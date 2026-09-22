package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.FontMetrics;
import org.jetbrains.skia.Typeface;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A draggable numeric slider: a track, filled progress, a round thumb, and three
 * optional extras. Those extras are evenly-spaced tick marks ({@link #setTickSpacing}),
 * a small label under each tick ({@link #setTickLabelFormatter}, independent of the
 * tick marks themselves), and a text label that follows the thumb
 * ({@link #setLabelFormatter}). Dragging moves the thumb and fires
 * {@link #onValueChange}, as does a single click anywhere along the track through
 * {@link Draggable#onDragStart}, which gives the same "click jumps, then drags"
 * behavior {@link me.piitex.engine.ui.scroll.ScrollContainer}'s own scrollbar thumb
 * has. {@link #setValue} sets the value programmatically without firing that callback,
 * the same convention {@link CheckBoxOverlay#setChecked} and
 * {@link RadioButtonOverlay} use.
 * <p>
 * Two independent kinds of snapping are available during a drag: {@link #setStep}, a
 * hard snap straight to the nearest grid value every time, and
 * {@link #setSnapSpacing}, a soft magnetic pull toward one that the thumb can still be
 * dragged straight through. See that method's docs for how the two differ.
 * <p>
 * This is the generic base, with the minimum, maximum, and step all open. A narrower,
 * pre-configured use case, such as a 0-100 slider with a "N%" label, belongs in its own
 * small subclass that calls into this one's setters from its own constructor rather
 * than in further constructor overloads here. {@link PercentSliderOverlay} is an
 * example of that shape.
 * <p>
 * Track, fill, thumb, and tick colors use {@link Theme#getScrollTrack()},
 * {@link Theme#getScrollThumb()}, and {@link Theme#getFieldBorder()} as their baseline.
 * These are the tokens a scrollbar already defines for the same "groove with a
 * draggable thumb on it" concept, so no new ones are introduced here. The label uses
 * {@link Theme#getText()}, the same as every other themed label in this engine.
 */
public class SliderOverlay extends Overlay implements Themeable, Draggable, FontScalable {
    private double min;
    private double max;
    private double value;
    private double step; // <= 0 means continuous, no snapping

    private double snapSpacing; // <= 0 means no magnetic snapping
    private float snapStrength = 0.4f; // fraction of the half-distance-to-neighbor that's magnetized

    private float trackThickness = 6f;
    private float thumbRadius = 8f;
    private double tickSpacing; // <= 0 means no ticks drawn

    private boolean dragging;

    private final Overridable<Color> trackColor = new Overridable<>(Color.LIGHT_GRAY);
    private final Overridable<Color> fillColor = new Overridable<>(Color.GRAY);
    private final Overridable<Color> thumbColor = new Overridable<>(Color.GRAY);
    private final Overridable<Color> tickColor = new Overridable<>(Color.LIGHT_GRAY);
    private final Overridable<Paint> labelColor = new Overridable<>(Color.BLACK);
    /**
     * Overrides {@link #getThumbColor()} while {@link #isDragging()}. Null, the default, leaves the thumb the same color throughout the drag.
     */
    private Color thumbActiveColor;

    /**
     * Formats {@link #getValue()} into the text drawn above the thumb. Null, the default, draws no label at all. See {@link #setLabelFormatter}.
     */
    private Function<Double, String> labelFormatter;
    private float labelFontSize = 12f;
    private Typeface typeface;
    private org.jetbrains.skia.Font labelSkiaFont;
    private boolean labelFontDirty = true;

    /**
     * Formats each tick's own value into the small text drawn under it. Null, the default, draws no tick labels at all, even while {@link #getTickSpacing()} draws the tick marks themselves. See {@link #setTickLabelFormatter}.
     */
    private Function<Double, String> tickLabelFormatter;
    private float tickLabelFontSize = 10f;
    private final Overridable<Paint> tickLabelColor = new Overridable<>(Color.GRAY);
    private org.jetbrains.skia.Font tickLabelSkiaFont;
    private boolean tickLabelFontDirty = true;

    private Consumer<Double> onValueChange;

    /**
     * A 0-100 slider, {@code width} points long, starting at 0.
     */
    public SliderOverlay(double width) {
        this(width, 20, 0, 100, 0);
    }

    /**
     * A slider over {@code [min, max]}, {@code width} points long, starting at {@code min}.
     */
    public SliderOverlay(double width, double min, double max) {
        this(width, 20, min, max, min);
    }

    /**
     * A slider over {@code [min, max]}, {@code width} points long, starting at {@code initialValue}.
     */
    public SliderOverlay(double width, double min, double max, double initialValue) {
        this(width, 20, min, max, initialValue);
    }

    /**
     * A slider over {@code [min, max]}, {@code width}x{@code height} points, starting at {@code initialValue} (clamped/snapped like {@link #setValue}).
     */
    public SliderOverlay(double width, double height, double min, double max, double initialValue) {
        this.min = min;
        this.max = max;
        this.typeface = Font.getDefault();
        setWidth(width);
        setHeight(height);
        setCursorMode(CursorMode.POINTER);
        setValue(initialValue);

        // Seeded as a baseline, not pinned. See Overridable. This Element draws its
        // own track, fill, and thumb entirely by hand below; Element's default
        // opaque-white background and border box would otherwise paint behind all of it.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        ThemeManager.register(this);
    }

    // --- Range/value -----------------------------------------------------------------------------

    public double getMin() {
        return min;
    }

    /**
     * Also re-clamps/re-snaps the current {@link #getValue()} into the new range.
     */
    public void setMin(double min) {
        this.min = min;
        this.value = clampAndSnap(value);
    }

    public double getMax() {
        return max;
    }

    /**
     * Also re-clamps/re-snaps the current {@link #getValue()} into the new range.
     */
    public void setMax(double max) {
        this.max = max;
        this.value = clampAndSnap(value);
    }

    public double getValue() {
        return value;
    }

    /**
     * Sets the value programmatically, clamped to {@code [min, max]} and snapped to
     * {@link #getStep()}. This does not fire {@link #onValueChange}, the same convention
     * {@link CheckBoxOverlay#setChecked} and {@link RadioButtonOverlay} use for their
     * own programmatic setters. Dragging the thumb, or clicking the track, goes through
     * {@link #onDragStart} instead, which does fire it.
     */
    public void setValue(double value) {
        this.value = clampAndSnap(value);
    }

    /**
     * {@code (value - min) / (max - min)}, in [0, 1], or 0 if {@code max <= min}.
     */
    public double getPercentage() {
        return max <= min ? 0 : (value - min) / (max - min);
    }

    /**
     * The grid {@link #getValue()} snaps to on every drag and every programmatic set.
     * A step of 1 restricts it to whole numbers, and 25 restricts it to
     * {@code min, min+25, min+50, ...}. A value {@code <= 0}, the default, means
     * continuous, with no snapping at all.
     * <p>
     * This is a hard snap: the value jumps straight to the nearest step every time, with
     * nothing in between. For a soft, magnetic pull toward a grid of values that the
     * thumb can still be dragged past rather than being clamped to, see
     * {@link #setSnapSpacing}. The two can also be used together, in which case
     * {@link #getStep()} applies last.
     */
    public double getStep() {
        return step;
    }

    public void setStep(double step) {
        this.step = step;
        this.value = clampAndSnap(value);
    }

    private double clampAndSnap(double raw) {
        double clamped = Math.max(min, Math.min(max, raw));
        if (step > 0) {
            double steps = Math.round((clamped - min) / step);
            clamped = Math.max(min, Math.min(max, min + steps * step));
        }
        return clamped;
    }

    /**
     * The value-space grid a drag is gently pulled toward. A value {@code <= 0}, the
     * default, disables this entirely. Unlike {@link #setStep}, this is not a hard snap.
     * Within a magnetized zone around each grid point, sized by
     * {@link #setSnapStrength}, the dragged value is smoothly biased toward that point
     * while remaining continuous and draggable straight through it. There is no
     * threshold the mouse has to cross, only a little more drag distance needed to leave
     * the pull. This applies to drag-driven and click-driven changes only (see
     * {@link #onDragStart}); {@link #setValue} bypasses it, just as it bypasses
     * {@link #onValueChange}.
     * <p>
     * A common pairing is the same spacing as {@link #setTickSpacing}, for example
     * {@code setTickSpacing(25); setSnapSpacing(25);}, so the drawn ticks are exactly
     * the values the thumb is pulled toward. The two remain independent, though: ticks
     * with no snapping, and snapping with no ticks drawn, both work.
     */
    public double getSnapSpacing() {
        return snapSpacing;
    }

    public void setSnapSpacing(double snapSpacing) {
        this.snapSpacing = snapSpacing;
    }

    /**
     * How wide the magnetic zone around each {@link #getSnapSpacing()} grid point is,
     * as a fraction (0, 1] of the half-distance to its neighbor. A value of 1 means the
     * pull covers the entire span between grid points, still smoothly and never as a
     * hard snap, but with the thumb always at least slightly biased toward the nearer
     * point. Smaller values leave a neutral, unbiased zone around each midpoint.
     * Defaults to 0.4, a noticeable but easily escaped pull. Has no effect while
     * {@link #getSnapSpacing()} is {@code <= 0}.
     */
    public float getSnapStrength() {
        return snapStrength;
    }

    public void setSnapStrength(float snapStrength) {
        this.snapStrength = Math.max(0.0001f, Math.min(1f, snapStrength));
    }

    /**
     * Smoothly biases {@code raw} toward the nearest {@link #getSnapSpacing()} grid
     * point when it falls within the magnetized zone. See {@link #setSnapSpacing}. This
     * uses a smoothstep ease, with zero slope at both ends of the zone, rather than a
     * linear blend, so there is no sudden change in how the thumb tracks the mouse as it
     * crosses into or out of the zone.
     */
    private double applyMagneticSnap(double raw) {
        if (snapSpacing <= 0) return raw;

        double nearest = Math.max(min, Math.min(max, min + Math.round((raw - min) / snapSpacing) * snapSpacing));
        double threshold = snapStrength * (snapSpacing / 2.0);
        double distance = Math.abs(raw - nearest);
        if (threshold <= 0 || distance >= threshold) return raw;

        double t = distance / threshold; // 0 at the grid point, 1 at the edge of the zone
        double eased = t * t * (3 - 2 * t); // smoothstep
        return nearest + (raw - nearest) * eased;
    }

    // --- Geometry --------------------------------------------------------------------------------

    /**
     * How thick the track/fill bar is drawn, in logical points.
     */
    public float getTrackThickness() {
        return trackThickness;
    }

    public void setTrackThickness(float trackThickness) {
        this.trackThickness = trackThickness;
    }

    /**
     * The thumb circle's radius, in logical points. The track itself is inset by this on both ends so the thumb never draws outside this Element's own bounds.
     */
    public float getThumbRadius() {
        return thumbRadius;
    }

    public void setThumbRadius(float thumbRadius) {
        this.thumbRadius = thumbRadius;
    }

    /**
     * The value-space spacing between tick marks. A spacing of 10 on a 0-100 slider draws 11 ticks. A value {@code <= 0}, the default, draws no ticks.
     */
    public double getTickSpacing() {
        return tickSpacing;
    }

    public void setTickSpacing(double tickSpacing) {
        this.tickSpacing = tickSpacing;
    }

    // --- Colors ----------------------------------------------------------------------------------

    public Color getTrackColor() {
        return trackColor.get();
    }

    /**
     * Pins the track color against every future theme switch. See {@link Overridable}.
     */
    public void setTrackColor(Color trackColor) {
        this.trackColor.set(trackColor);
    }

    public Color getFillColor() {
        return fillColor.get();
    }

    /**
     * Pins the filled-progress color against every future theme switch. See {@link Overridable}.
     */
    public void setFillColor(Color fillColor) {
        this.fillColor.set(fillColor);
    }

    public Color getThumbColor() {
        return thumbColor.get();
    }

    /**
     * Pins the thumb color against every future theme switch. See {@link Overridable}.
     */
    public void setThumbColor(Color thumbColor) {
        this.thumbColor.set(thumbColor);
    }

    /**
     * See {@link #thumbActiveColor}.
     */
    public Color getThumbActiveColor() {
        return thumbActiveColor;
    }

    public void setThumbActiveColor(Color thumbActiveColor) {
        this.thumbActiveColor = thumbActiveColor;
    }

    public Color getTickColor() {
        return tickColor.get();
    }

    /**
     * Pins the tick color against every future theme switch. See {@link Overridable}.
     */
    public void setTickColor(Color tickColor) {
        this.tickColor.set(tickColor);
    }

    public Paint getLabelColor() {
        return labelColor.get();
    }

    /**
     * Pins the label color against every future theme switch. See {@link Overridable}.
     */
    public void setLabelColor(Paint labelColor) {
        this.labelColor.set(labelColor);
    }

    // --- Label -----------------------------------------------------------------------------------

    /**
     * Formats {@link #getValue()} into the text drawn centered above the thumb, re-evaluated every frame. For example, {@code v -> Math.round(v) + "%"} gives a percentage readout. Null, the default, draws no label. See {@link PercentSliderOverlay} for a subclass that sets this once in its constructor.
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

    /**
     * Uses a custom font file, cached through {@link Font}, for both the label and the tick labels instead of the default font. The two differ only in size, set by {@link #setLabelFontSize} and {@link #setTickLabelFontSize}, never in typeface.
     */
    public void setLabelFontFile(java.io.File fontFile) {
        this.typeface = Font.load(fontFile);
        labelFontDirty = true;
        tickLabelFontDirty = true;
    }

    // --- Tick labels -----------------------------------------------------------------------------

    /**
     * Formats each tick's own value into the small text drawn under it, re-evaluated
     * every frame, for example {@code v -> String.valueOf(Math.round(v))}. Null, the
     * default, draws no tick labels. It has no effect while {@link #getTickSpacing()} is
     * {@code <= 0}, since there are no ticks to label. Tick labels are drawn below the
     * track, so a slider using them needs extra {@code height} the same way one using
     * {@link #setLabelFormatter} needs headroom above.
     */
    public void setTickLabelFormatter(Function<Double, String> tickLabelFormatter) {
        this.tickLabelFormatter = tickLabelFormatter;
    }

    public Function<Double, String> getTickLabelFormatter() {
        return tickLabelFormatter;
    }

    public float getTickLabelFontSize() {
        return tickLabelFontSize;
    }

    public void setTickLabelFontSize(float tickLabelFontSize) {
        this.tickLabelFontSize = tickLabelFontSize;
        tickLabelFontDirty = true;
    }

    /**
     * {label size, tick label size}.
     */
    @Override
    public float[] getFontSizes() {
        return new float[]{labelFontSize, tickLabelFontSize};
    }

    @Override
    public void setFontSizes(float[] sizes) {
        setLabelFontSize(sizes[0]);
        setTickLabelFontSize(sizes[1]);
    }

    public Paint getTickLabelColor() {
        return tickLabelColor.get();
    }

    /**
     * Pins the tick label color against every future theme switch. See {@link Overridable}.
     */
    public void setTickLabelColor(Paint tickLabelColor) {
        this.tickLabelColor.set(tickLabelColor);
    }

    // --- Interaction -----------------------------------------------------------------------------

    /**
     * Whether the thumb is currently being dragged. This drives {@link #getThumbActiveColor()}.
     */
    public boolean isDragging() {
        return dragging;
    }

    /**
     * Registers the callback fired with the new value every time it changes via a drag
     * or a track click, but not through {@link #setValue}. Only one is held at a time;
     * calling this again replaces the previous one.
     */
    public void onValueChange(Consumer<Double> onValueChange) {
        this.onValueChange = onValueChange;
    }

    /**
     * A press anywhere along the track jumps the thumb there, same as {@link me.piitex.engine.ui.scroll.ScrollContainer}'s own thumb-drag behavior.
     */
    @Override
    public void onDragStart(double x, double y) {
        dragging = true;
        moveThumbTo(toLocalX(x));
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (dragging) {
            moveThumbTo(toLocalX(x));
        }
    }

    @Override
    public void onDragEnd(double x, double y) {
        dragging = false;
    }

    private void moveThumbTo(double mouseX) {
        double newValue = clampAndSnap(applyMagneticSnap(xToValue(mouseX)));
        if (newValue != value) {
            value = newValue;
            if (onValueChange != null) {
                onValueChange.accept(value);
            }
        }
    }

    // --- Value <-> pixel mapping -----------------------------------------------------------------

    private double trackStartX() {
        return getX() + thumbRadius;
    }

    private double trackEndX() {
        return getX() + getWidth() - thumbRadius;
    }

    private double trackWidth() {
        return Math.max(0, trackEndX() - trackStartX());
    }

    private double valueToX(double v) {
        double range = max - min;
        double ratio = range <= 0 ? 0 : (v - min) / range;
        return trackStartX() + ratio * trackWidth();
    }

    private double xToValue(double x) {
        double width = trackWidth();
        double ratio = width <= 0 ? 0 : (x - trackStartX()) / width;
        ratio = Math.max(0, Math.min(1, ratio));
        return min + ratio * (max - min);
    }

    // --- Theme -----------------------------------------------------------------------------------

    /**
     * Themes the track, fill, thumb, tick, and label colors as a baseline, skipping
     * whichever property its own setter has already pinned. See {@link Overridable}.
     * Tick labels theme from {@link Theme#getPlaceholderText()} rather than
     * {@link Theme#getText()}, the same dimmer secondary-text token a themed
     * {@link TextFieldOverlay}'s placeholder uses, because a tick label is an axis
     * annotation rather than the slider's value readout. The readout is
     * {@link #getLabelColor()}.
     */
    @Override
    public void applyTheme(Theme theme) {
        trackColor.applyTheme(theme.getScrollTrack());
        fillColor.applyTheme(theme.getScrollThumb());
        thumbColor.applyTheme(theme.getScrollThumb());
        tickColor.applyTheme(theme.getFieldBorder());
        labelColor.applyTheme(theme.getText());
        tickLabelColor.applyTheme(theme.getPlaceholderText());
    }

    // --- Rendering -------------------------------------------------------------------------------

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;

        // Transparent background/border (see constructor) + fires the user's onRender hook.
        super.render(renderer);

        float trackY = (float) (getY() + getHeight() / 2.0);
        float tx = (float) trackStartX();
        float tw = (float) trackWidth();
        float th = trackThickness;

        renderer.drawQuad(tx, trackY - th / 2f, tw, th, trackColor.get(), th / 2f);

        float thumbX = (float) valueToX(value);
        float filledWidth = Math.max(0, thumbX - tx);
        renderer.drawQuad(tx, trackY - th / 2f, filledWidth, th, fillColor.get(), th / 2f);

        drawTicks(renderer, trackY);
        drawTickLabels(renderer, trackY);

        Color thumbPaint = dragging && thumbActiveColor != null ? thumbActiveColor : thumbColor.get();
        renderer.drawCircle(thumbX, trackY, thumbRadius, thumbPaint);

        if (labelFormatter != null) {
            drawLabel(renderer, thumbX, trackY);
        }
    }

    /**
     * Calls {@code action} with each tick's value, from {@code min} up to and always including {@code max}. Shared by {@link #drawTicks} and {@link #drawTickLabels} so both draw at exactly the same x positions. A no-op if ticks are off ({@link #getTickSpacing()} {@code <= 0}) or the range is empty.
     */
    private void forEachTickValue(java.util.function.DoubleConsumer action) {
        if (tickSpacing <= 0 || max <= min) return;
        for (double v = min; v < max; v += tickSpacing) {
            action.accept(v);
        }
        // Always include the final tick at exactly `max`, rather than relying on the
        // loop to land on it, since tickSpacing rarely divides (max - min) evenly.
        action.accept(max);
    }

    private void drawTicks(Renderer renderer, float trackY) {
        float tickHeight = trackThickness + 6f;
        forEachTickValue(v -> {
            float x = (float) valueToX(v);
            renderer.drawLine(x, trackY - tickHeight / 2f, x, trackY + tickHeight / 2f, tickColor.get(), 1.5f);
        });
    }

    private void drawTickLabels(Renderer renderer, float trackY) {
        if (tickLabelFormatter == null) return;

        float tickHeight = trackThickness + 6f;
        float drawY = trackY + tickHeight / 2f + 2f;
        org.jetbrains.skia.Font font = getOrBuildTickLabelFont();
        Paint color = tickLabelColor.get();
        forEachTickValue(v -> {
            String text = tickLabelFormatter.apply(v);
            if (text == null || text.isEmpty()) return;
            float x = (float) valueToX(v);
            float textWidth = font.measureTextWidth(text);
            renderer.drawText(text, x - textWidth / 2f, drawY, font, color);
        });
    }

    private void drawLabel(Renderer renderer, float thumbX, float trackY) {
        String label = labelFormatter.apply(value);
        if (label == null || label.isEmpty()) return;

        org.jetbrains.skia.Font font = getOrBuildLabelFont();
        FontMetrics metrics = font.getMetrics();
        float textWidth = font.measureTextWidth(label);
        float textHeight = -metrics.getAscent() + metrics.getDescent();

        float drawX = thumbX - textWidth / 2f;
        float drawY = trackY - thumbRadius - 4f - textHeight;
        renderer.drawText(label, drawX, drawY, font, resolveTextColor(labelColor.get()));
    }

    private org.jetbrains.skia.Font getOrBuildLabelFont() {
        if (labelFontDirty || labelSkiaFont == null) {
            if (labelSkiaFont != null) {
                labelSkiaFont.close(); // native resource, so release the old one
            }
            labelSkiaFont = new org.jetbrains.skia.Font(typeface, labelFontSize);
            labelFontDirty = false;
        }
        return labelSkiaFont;
    }

    private org.jetbrains.skia.Font getOrBuildTickLabelFont() {
        if (tickLabelFontDirty || tickLabelSkiaFont == null) {
            if (tickLabelSkiaFont != null) {
                tickLabelSkiaFont.close(); // native resource, so release the old one
            }
            tickLabelSkiaFont = new org.jetbrains.skia.Font(typeface, tickLabelFontSize);
            tickLabelFontDirty = false;
        }
        return tickLabelSkiaFont;
    }

    /**
     * Advances the base Element's Styling paints via {@code super.update(...)}, then
     * additionally advances {@link #getLabelColor()}/{@link #getTickLabelColor()} if
     * either is an {@link AnimatedPaint}, for the same reason as
     * {@link TextOverlay#update}, which this mirrors.
     */
    @Override
    public void update(float delta) {
        super.update(delta);
        if (labelColor.get() instanceof AnimatedPaint animated) {
            animated.advance(delta);
        }
        if (tickLabelColor.get() instanceof AnimatedPaint animated) {
            animated.advance(delta);
        }
    }

    /**
     * Releases the native Fonts this slider owns, if a label/tick labels were ever
     * drawn. It does not close the underlying Typeface; see
     * {@link TextOverlay#dispose()}, which this mirrors.
     */
    public void dispose() {
        if (labelSkiaFont != null) {
            labelSkiaFont.close();
            labelSkiaFont = null;
        }
        if (tickLabelSkiaFont != null) {
            tickLabelSkiaFont.close();
            tickLabelSkiaFont = null;
        }
    }
}
