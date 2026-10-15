package me.piitex.engine.ui.charts;

import me.piitex.engine.ui.color.Color;

import java.util.Arrays;

/**
 * One named row of numbers in a chart: the revenue of a year, the temperature of a city. A line chart draws it as a line,
 * a bar chart as one bar in each group. {@code NaN} is a gap: a line breaks there and a bar is left out.
 * <p>
 * A chart keeps the series it is given and reads them every frame, so changing one is enough: {@link #setValues} or
 * {@link #set} and the chart eases to the new numbers. The series that were added to a chart come from
 * {@link ChartOverlay#addSeries}.
 */
public class ChartSeries {
    private String name;
    private Color color;
    private double[] values;
    private boolean visible = true;

    // What is drawn: it follows values, so a chart can ease toward a change.
    private double[] shown = new double[0];
    private float fade = 1f;

    public ChartSeries(String name, double... values) {
        this.name = name == null ? "" : name;
        this.values = values.clone();
    }

    public String getName() {
        return name;
    }

    public ChartSeries setName(String name) {
        this.name = name == null ? "" : name;
        return this;
    }

    /**
     * The series' own color, or null (the default) for the next one of the chart's palette.
     */
    public Color getColor() {
        return color;
    }

    public ChartSeries setColor(Color color) {
        this.color = color;
        return this;
    }

    /**
     * Whether the series is drawn. Hidden series also stop counting toward the axis range. The legend switches this on a click.
     */
    public boolean isVisible() {
        return visible;
    }

    public ChartSeries setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    public int size() {
        return values.length;
    }

    /**
     * The {@code index}th value, or {@code NaN} past the end.
     */
    public double get(int index) {
        return index >= 0 && index < values.length ? values[index] : Double.NaN;
    }

    /**
     * A copy of the values.
     */
    public double[] getValues() {
        return values.clone();
    }

    /**
     * Replaces every value. The chart eases from what it shows to these.
     */
    public ChartSeries setValues(double... values) {
        this.values = values.clone();
        return this;
    }

    /**
     * Changes one value, growing the series with gaps if the index is past its end.
     */
    public ChartSeries set(int index, double value) {
        if (index < 0) throw new IndexOutOfBoundsException("index " + index);
        if (index >= values.length) {
            int old = values.length;
            values = Arrays.copyOf(values, index + 1);
            Arrays.fill(values, old, index, Double.NaN);
        }
        values[index] = value;
        return this;
    }

    /**
     * Adds a value at the end. Together with {@link ChartOverlay#addCategory} this is how a live chart grows.
     */
    public ChartSeries add(double value) {
        values = Arrays.copyOf(values, values.length + 1);
        values[values.length - 1] = value;
        return this;
    }

    /**
     * Drops the first {@code count} values, for a chart that scrolls: add one at the end, remove one at the start.
     */
    public ChartSeries removeFirst(int count) {
        count = Math.clamp(count, 0, values.length);
        values = Arrays.copyOfRange(values, count, values.length);
        shown = shown.length >= count ? Arrays.copyOfRange(shown, count, shown.length) : new double[0];
        return this;
    }

    /**
     * The value as drawn right now: {@link #get} while the chart is still easing toward it.
     */
    double shown(int index) {
        return index >= 0 && index < shown.length ? shown[index] : Double.NaN;
    }

    /**
     * 1 while the series is shown, 0 once it has been hidden and the fade finished.
     */
    float fade() {
        return fade;
    }

    /**
     * Moves what is drawn toward the values.
     *
     * @param k        the share of the remaining distance to cover, 0 to 1 (1 arrives at once)
     * @param baseline where a value that is new starts from, or NaN to start at the value itself
     * @param epsilon  a distance small enough to snap the rest of the way
     */
    void advance(float k, double baseline, double epsilon) {
        if (shown.length != values.length) {
            double[] grown = new double[values.length];
            for (int i = 0; i < grown.length; i++) {
                grown[i] = i < shown.length ? shown[i] : start(values[i], baseline, k);
            }
            shown = grown;
        }
        for (int i = 0; i < values.length; i++) {
            double target = values[i];
            if (Double.isNaN(target)) {
                shown[i] = Double.NaN;
                continue;
            }
            double current = shown[i];
            if (Double.isNaN(current)) current = start(target, baseline, k);
            current += (target - current) * k;
            shown[i] = Math.abs(target - current) <= epsilon ? target : current;
        }
        float goal = visible ? 1f : 0f;
        fade += (goal - fade) * k;
        if (Math.abs(goal - fade) < 0.005f) fade = goal;
    }

    private static double start(double target, double baseline, float k) {
        if (k >= 1f || Double.isNaN(baseline) || Double.isNaN(target)) return target;
        return baseline;
    }
}
