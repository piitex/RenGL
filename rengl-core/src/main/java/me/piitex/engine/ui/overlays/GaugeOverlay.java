package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A round read-out of one number: a speedometer, a temperature, a health or fuel dial. Unlike a {@link KnobOverlay} the
 * user does not turn it; the game sets {@link #setValue} and the gauge eases to it.
 * <pre>{@code
 * GaugeOverlay speed = new GaugeOverlay(160, 0, 240);
 * speed.setTitle("km/h");
 * speed.setStyle(GaugeOverlay.Style.NEEDLE);
 * speed.addZone(0, 160, Color.GREEN);
 * speed.addZone(160, 200, Color.YELLOW);
 * speed.addZone(200, 240, Color.RED);
 * speed.setTickCount(7);                  // 0, 40, 80 ... 240
 * speed.setValue(car.getSpeed());         // every frame is fine
 * }</pre>
 * In the {@link Style#ARC} style, the default, the value is an arc around the rim; in {@link Style#NEEDLE} it is a needle
 * over a rim that shows the {@linkplain #addZone zones}. The dial sweeps 270 degrees by default; {@link #setSweep} makes
 * it a half-circle or any other part of one. {@link #setSmoothing} sets how quickly the displayed value follows the real
 * one (0 jumps at once), so a value read every frame moves smoothly even if it changes in steps.
 * <p>
 * Colors come from the theme: the groove from {@link Theme#getScrollTrack()}, the arc and needle from {@link
 * Theme#getAccent()}, the text from {@link Theme#getText()}. A zone has its own color. It takes no input.
 */
public class GaugeOverlay extends Overlay implements Themeable, FontScalable {
    public enum Style {
        /** The value is an arc along the rim, in the color of the zone it is in. */
        ARC,
        /** The value is a needle; the rim shows the zones. */
        NEEDLE
    }

    private record Zone(double from, double to, Paint paint) {
    }

    private double min;
    private double max;
    private double target;
    private double shown;
    private float smoothing = 0.15f;

    private Style style = Style.ARC;
    private float startDegrees = KnobOverlay.START_DEGREES;
    private float sweepDegrees = KnobOverlay.SWEEP_DEGREES;
    private float arcThickness = 10f;
    private int tickCount;
    private final List<Zone> zones = new ArrayList<>();

    private String title = "";
    private boolean showValueText = true;
    private Function<Double, String> labelFormatter = v -> String.valueOf(Math.round(v));
    private float valueFontSize = 20f;
    private float titleFontSize = 12f;
    private final Typeface typeface = Font.getDefault();
    private org.jetbrains.skia.Font valueFont;
    private org.jetbrains.skia.Font titleFont;
    private boolean fontsDirty = true;

    private final Overridable<Paint> grooveColor = new Overridable<>(Color.LIGHT_GRAY);
    private final Overridable<Paint> arcColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> needleColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> tickColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> textColor = new Overridable<>(Color.BLACK);

    /**
     * A gauge {@code diameter} points across over {@code [min, max]}, showing {@code min}.
     */
    public GaugeOverlay(double diameter, double min, double max) {
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        this.target = this.min;
        this.shown = this.min;
        setWidth(diameter);
        setHeight(diameter);
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

    public double getMax() {
        return max;
    }

    public void setRange(double min, double max) {
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        this.target = clamp(target);
        this.shown = clamp(shown);
    }

    /**
     * The value the gauge is heading for.
     */
    public double getValue() {
        return target;
    }

    /**
     * Sets the value, clamped to the range. The displayed value eases to it over {@link #setSmoothing the smoothing time}.
     */
    public void setValue(double value) {
        this.target = clamp(value);
        if (smoothing <= 0) shown = target;
    }

    /**
     * The value drawn right now, between the old value and {@link #getValue()} while the gauge is easing.
     */
    public double getDisplayedValue() {
        return shown;
    }

    /**
     * The displayed value as a fraction of the range, 0 at {@code min} and 1 at {@code max}.
     */
    public double getRatio() {
        return max <= min ? 0 : (shown - min) / (max - min);
    }

    /**
     * Seconds the displayed value takes to cover about two thirds of the way to a new value; 0.15 by default. 0 or less
     * makes it follow at once.
     */
    public void setSmoothing(float smoothing) {
        this.smoothing = smoothing;
        if (smoothing <= 0) shown = target;
    }

    public float getSmoothing() {
        return smoothing;
    }

    private double clamp(double v) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Moves the displayed value toward the real one by {@code delta} seconds' worth. Called every frame by {@link #update};
     * public so a test or a paused game can step it.
     */
    public void advance(float delta) {
        if (smoothing <= 0 || shown == target) {
            shown = target;
            return;
        }
        shown += (target - shown) * (1.0 - Math.exp(-delta / smoothing));
        if (Math.abs(target - shown) < (max - min) * 0.0005) shown = target;
    }

    /* Zones */

    /**
     * Colors the part of the range from {@code from} to {@code to}: on the rim behind a needle, or as the color of the arc
     * while the value is inside it (a zone includes {@code from} and excludes {@code to}, except the last, which includes
     * the maximum). Zones may be added in any order.
     */
    public GaugeOverlay addZone(double from, double to, Paint paint) {
        zones.add(new Zone(Math.min(from, to), Math.max(from, to), paint));
        return this;
    }

    public void clearZones() {
        zones.clear();
    }

    /**
     * The color of the zone {@code value} is in, or null when it is in none.
     */
    public Paint getZoneColor(double value) {
        Paint found = null;
        for (Zone z : zones) {
            if (value >= z.from && (value < z.to || (value == z.to && z.to >= max))) found = z.paint;
        }
        return found;
    }

    /* Look */

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style == null ? Style.ARC : style;
    }

    /**
     * Where the dial starts and how far it goes, in degrees clockwise from 3 o'clock (the way y grows on screen): the
     * default is 135 and 270, from the lower left to the lower right; {@code 180, 180} is a half-circle over the top.
     */
    public void setSweep(float startDegrees, float sweepDegrees) {
        this.startDegrees = startDegrees;
        this.sweepDegrees = sweepDegrees;
    }

    public float getArcThickness() {
        return arcThickness;
    }

    public void setArcThickness(float arcThickness) {
        this.arcThickness = arcThickness;
    }

    public int getTickCount() {
        return tickCount;
    }

    /**
     * How many tick marks are drawn inside the rim, evenly from {@code min} to {@code max} (so 5 marks are 4 intervals); 0,
     * the default, draws none.
     */
    public void setTickCount(int tickCount) {
        this.tickCount = Math.max(0, tickCount);
    }

    public String getTitle() {
        return title;
    }

    /**
     * Small text under the value, such as the unit.
     */
    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public boolean isShowValueText() {
        return showValueText;
    }

    public void setShowValueText(boolean showValueText) {
        this.showValueText = showValueText;
    }

    /**
     * Turns the value into the text at the center; by default the value rounded to a whole number. It is given the
     * displayed value, so the text counts up with the dial.
     */
    public void setLabelFormatter(Function<Double, String> labelFormatter) {
        this.labelFormatter = labelFormatter == null ? v -> String.valueOf(v) : labelFormatter;
    }

    public float getValueFontSize() {
        return valueFontSize;
    }

    public void setValueFontSize(float valueFontSize) {
        this.valueFontSize = valueFontSize;
        fontsDirty = true;
    }

    public float getTitleFontSize() {
        return titleFontSize;
    }

    public void setTitleFontSize(float titleFontSize) {
        this.titleFontSize = titleFontSize;
        fontsDirty = true;
    }

    @Override
    public float[] getFontSizes() {
        return new float[]{valueFontSize, titleFontSize};
    }

    @Override
    public void setFontSizes(float[] sizes) {
        setValueFontSize(sizes[0]);
        setTitleFontSize(sizes[1]);
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
     * The color of the value arc where no zone applies.
     */
    public void setArcColor(Paint arcColor) {
        this.arcColor.set(arcColor);
    }

    public Paint getNeedleColor() {
        return needleColor.get();
    }

    public void setNeedleColor(Paint needleColor) {
        this.needleColor.set(needleColor);
    }

    public Paint getTickColor() {
        return tickColor.get();
    }

    public void setTickColor(Paint tickColor) {
        this.tickColor.set(tickColor);
    }

    public Paint getTextColor() {
        return textColor.get();
    }

    public void setTextColor(Paint textColor) {
        this.textColor.set(textColor);
    }

    /* Theme */

    @Override
    public void applyTheme(Theme theme) {
        grooveColor.applyTheme(theme.getScrollTrack());
        arcColor.applyTheme(theme.getAccent());
        needleColor.applyTheme(theme.getAccent());
        tickColor.applyTheme(theme.getFieldBorder());
        textColor.applyTheme(theme.getText());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        grooveColor.clearExplicit();
        arcColor.clearExplicit();
        needleColor.clearExplicit();
        tickColor.clearExplicit();
        textColor.clearExplicit();
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        advance(delta);
        AnimatedPaint.advanceIfAnimated(grooveColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(arcColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(needleColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(tickColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(textColor.get(), delta);
    }

    /* Rendering */

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);

        float cx = (float) (getX() + getWidth() / 2.0), cy = (float) (getY() + getHeight() / 2.0);
        float outer = (float) (Math.min(getWidth(), getHeight()) / 2.0) - 2f;
        float ringR = outer - arcThickness / 2f;

        if (style == Style.NEEDLE && !zones.isEmpty()) {
            for (Zone z : zones) {
                float from = (float) ((Math.max(z.from, min) - min) / (max - min));
                float to = (float) ((Math.min(z.to, max) - min) / (max - min));
                if (to <= from) continue;
                float[] arc = Arcs.arc(cx, cy, ringR, startDegrees + sweepDegrees * from, sweepDegrees * (to - from));
                renderer.drawPolyline(arc, Arcs.count(arc), z.paint, arcThickness);
            }
        } else {
            float[] groove = Arcs.arc(cx, cy, ringR, startDegrees, sweepDegrees);
            renderer.drawPolyline(groove, Arcs.count(groove), grooveColor.get(), arcThickness);
        }

        double ratio = getRatio();
        if (style == Style.ARC && ratio > 0) {
            Paint zone = getZoneColor(shown);
            float[] arc = Arcs.arc(cx, cy, ringR, startDegrees, (float) (sweepDegrees * ratio));
            renderer.drawPolyline(arc, Arcs.count(arc), zone != null ? zone : arcColor.get(), arcThickness);
        }

        if (tickCount >= 2) {
            float inner = ringR - arcThickness / 2f - 3f, tickLen = Math.max(4f, outer * 0.08f);
            for (int i = 0; i < tickCount; i++) {
                double a = Math.toRadians(startDegrees + sweepDegrees * i / (tickCount - 1));
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                renderer.drawLine(cx + cos * inner, cy + sin * inner, cx + cos * (inner - tickLen), cy + sin * (inner - tickLen),
                        tickColor.get(), 1.5f);
            }
        }

        if (style == Style.NEEDLE) {
            double a = Math.toRadians(startDegrees + sweepDegrees * ratio);
            float len = ringR - arcThickness / 2f - Math.max(8f, outer * 0.15f);
            renderer.drawLine(cx, cy, cx + (float) Math.cos(a) * len, cy + (float) Math.sin(a) * len, needleColor.get(), 3f);
            renderer.drawCircle(cx, cy, Math.max(4f, outer * 0.06f), needleColor.get());
        }

        drawTexts(renderer, cx, cy, outer);
    }

    private void drawTexts(Renderer renderer, float cx, float cy, float outer) {
        ensureFonts();
        Paint color = resolveTextColor(textColor.get());
        float valueY = style == Style.NEEDLE ? cy + outer * 0.45f : cy;
        if (showValueText) {
            String text = labelFormatter.apply(shown);
            if (text != null && !text.isEmpty()) {
                FontMetricsCompat m = FontMetricsCompat.of(valueFont);
                float w = valueFont.measureTextWidth(text), h = -m.getAscent() + m.getDescent();
                renderer.drawText(text, cx - w / 2f, valueY - h / 2f - (title.isEmpty() ? 0f : 6f), valueFont, color);
            }
        }
        if (!title.isEmpty()) {
            FontMetricsCompat m = FontMetricsCompat.of(titleFont);
            float w = titleFont.measureTextWidth(title), h = -m.getAscent() + m.getDescent();
            renderer.pushAlpha(0.7f);
            renderer.drawText(title, cx - w / 2f, valueY + h / 2f + (showValueText ? 4f : -h / 2f), titleFont, color);
            renderer.popAlpha();
        }
    }

    private void ensureFonts() {
        if (!fontsDirty && valueFont != null && titleFont != null) return;
        dispose();
        valueFont = new org.jetbrains.skia.Font(typeface, valueFontSize);
        titleFont = new org.jetbrains.skia.Font(typeface, titleFontSize);
        fontsDirty = false;
    }

    /**
     * Frees the fonts used for the text, if any were drawn.
     */
    public void dispose() {
        if (valueFont != null) {
            valueFont.close();
            valueFont = null;
        }
        if (titleFont != null) {
            titleFont.close();
            titleFont = null;
        }
    }
}
