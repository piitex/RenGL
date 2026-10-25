package me.piitex.engine.ui.charts;

import java.util.Arrays;

/**
 * One named cloud of points in a {@link ScatterChartOverlay}: each point has an x and a y, and may have a size, which
 * makes the chart a bubble plot. {@code NaN} in x or y leaves the point out.
 * <p>
 * It is a {@link ChartSeries} whose values are the y numbers, so the legend, colors, visibility and easing work as they do for
 * the other charts. What a series of single numbers can do that a point cannot (an index for an x) is refused: use
 * {@link #add(double, double)}, {@link #set(int, double, double)} and {@link #setPoints}.
 */
public class ScatterSeries extends ChartSeries {
    private double[] xs;
    private double[] sizes;
    private double[] shownXs = new double[0];

    public ScatterSeries(String name) {
        super(name);
        xs = new double[0];
        sizes = new double[0];
    }

    public ScatterSeries(String name, double[] xs, double[] ys) {
        this(name, xs, ys, null);
    }

    /**
     * @param sizes the size of each point (area on screen is in proportion to it), or null for none
     */
    public ScatterSeries(String name, double[] xs, double[] ys, double[] sizes) {
        super(name);
        this.xs = new double[0];
        this.sizes = new double[0];
        setPoints(xs, ys, sizes);
    }

    // ---- points -------------------------------------------------------------------------------------------------

    /**
     * Replaces every point. The chart eases from where the points were.
     */
    public ScatterSeries setPoints(double[] xs, double[] ys) {
        return setPoints(xs, ys, null);
    }

    public ScatterSeries setPoints(double[] xs, double[] ys, double[] sizes) {
        if (xs.length != ys.length) throw new IllegalArgumentException(xs.length + " x values but " + ys.length + " y values");
        if (sizes != null && sizes.length != ys.length) throw new IllegalArgumentException(sizes.length + " sizes but " + ys.length + " points");
        this.xs = xs.clone();
        this.sizes = sizes == null ? new double[0] : sizes.clone();
        super.setValues(ys);
        return this;
    }

    /**
     * Adds a point at the end.
     */
    public ScatterSeries add(double x, double y) {
        return append(x, y, Double.NaN);
    }

    /**
     * Adds a point with a size, which draws it as a bubble.
     */
    public ScatterSeries add(double x, double y, double size) {
        return append(x, y, size);
    }

    private ScatterSeries append(double x, double y, double size) {
        int n = size();
        if (!Double.isNaN(size) && sizes.length == 0 && n > 0) { // the earlier points had no sizes
            sizes = new double[n];
            Arrays.fill(sizes, Double.NaN);
        }
        xs = Arrays.copyOf(xs, n + 1);
        xs[n] = x;
        if (sizes.length > 0 || !Double.isNaN(size)) {
            sizes = Arrays.copyOf(sizes, n + 1);
            sizes[n] = size;
        }
        super.add(y);
        return this;
    }

    /**
     * Moves one point.
     */
    public ScatterSeries set(int index, double x, double y) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException("index " + index + " of " + size());
        xs[index] = x;
        super.set(index, y);
        return this;
    }

    /**
     * The x of point {@code index}, or NaN past the end.
     */
    public double getX(int index) {
        return index >= 0 && index < xs.length ? xs[index] : Double.NaN;
    }

    /**
     * The y of point {@code index}, or NaN past the end. The same as {@link #get}.
     */
    public double getY(int index) {
        return get(index);
    }

    /**
     * The size of point {@code index}, or NaN when it has none.
     */
    public double getSize(int index) {
        return index >= 0 && index < sizes.length ? sizes[index] : Double.NaN;
    }

    public boolean hasSizes() {
        return sizes.length > 0;
    }

    @Override
    public ChartSeries removeFirst(int count) {
        count = Math.clamp(count, 0, size());
        super.removeFirst(count);
        xs = Arrays.copyOfRange(xs, count, xs.length);
        if (sizes.length > 0) sizes = Arrays.copyOfRange(sizes, Math.min(count, sizes.length), sizes.length);
        shownXs = shownXs.length >= count ? Arrays.copyOfRange(shownXs, count, shownXs.length) : new double[0];
        return this;
    }

    // ---- what a point does not allow ----------------------------------------------------------------------------

    @Override
    public ChartSeries setValues(double... values) {
        throw new UnsupportedOperationException("A scatter series has points: use setPoints or add(x, y)");
    }

    @Override
    public ChartSeries set(int index, double value) {
        throw new UnsupportedOperationException("A scatter series has points: use set(index, x, y)");
    }

    @Override
    public ChartSeries add(double value) {
        throw new UnsupportedOperationException("A scatter series has points: use add(x, y)");
    }

    // ---- easing -------------------------------------------------------------------------------------------------

    double shownX(int index) {
        return index >= 0 && index < shownXs.length ? shownXs[index] : Double.NaN;
    }

    @Override
    void advance(float k, double baseline, double epsilon) {
        super.advance(k, Double.NaN, epsilon); // a point appears where it is; it is the axes that move
        if (shownXs.length != xs.length) {
            double[] grown = new double[xs.length];
            for (int i = 0; i < grown.length; i++) grown[i] = i < shownXs.length && !Double.isNaN(shownXs[i]) ? shownXs[i] : xs[i];
            shownXs = grown;
        }
        for (int i = 0; i < xs.length; i++) {
            double target = xs[i];
            if (Double.isNaN(target)) {
                shownXs[i] = Double.NaN;
                continue;
            }
            double current = Double.isNaN(shownXs[i]) ? target : shownXs[i];
            current += (target - current) * k;
            shownXs[i] = Math.abs(target - current) <= Math.max(1.0, Math.abs(target)) * 1e-9 ? target : current;
        }
    }
}
