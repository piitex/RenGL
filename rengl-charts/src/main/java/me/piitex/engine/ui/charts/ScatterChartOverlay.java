package me.piitex.engine.ui.charts;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.text.TextAlign;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * Points placed by two numbers, one across and one up: the price of a building against its floor area, the size of a
 * company against its profit. Each series is a cloud of points; give the points a size and the chart is a bubble plot.
 * <pre>{@code
 * ScatterChartOverlay chart = new ScatterChartOverlay(640, 360);
 * chart.setTitle("Rent against floor area");
 * chart.setAxisTitles("Area (m²)", "Rent");
 * chart.addSeries("Offices", new double[]{40, 85, 120, 200}, new double[]{900, 1700, 2100, 3900});
 * }</pre>
 * Both axes fit the data and round their ends to numbers a person would pick. Either can be {@linkplain #setXScale
 * logarithmic}, and the horizontal one can be a {@linkplain AxisScale#TIME time axis}, with the x of each point the
 * moment in milliseconds. Point at a point to see its numbers; a click reports it through {@link #onPointClick}, with the
 * point's index in {@link ChartHit#category()}.
 */
public class ScatterChartOverlay extends ChartOverlay {
    private final NumericAxis xAxis = new NumericAxis(), yAxis = new NumericAxis();
    private AxisScale xScale = AxisScale.LINEAR, yScale = AxisScale.LINEAR;
    private double xMin = Double.NaN, xMax = Double.NaN, yMin = Double.NaN, yMax = Double.NaN;
    private boolean xBeginAtZero, yBeginAtZero;
    private boolean showGrid = true;
    private String xAxisTitle = "", yAxisTitle = "";
    private DoubleFunction<String> xFormat;
    private ZoneId zone = ZoneOffset.UTC;
    private DateTimeFormatter timeFormat;

    private float markerRadius = 5f;
    private float bubbleMin = 4f, bubbleMax = 26f;
    private double largestSize = 1;

    private float plotX, plotY, plotW, plotH;

    private int hoverSeries = -1, hoverPoint = -1;
    private int lastSeries = -1, lastPoint = -1;

    public ScatterChartOverlay(double width, double height) {
        super(width, height);
    }

    // ---- series -------------------------------------------------------------------------------------------------

    /**
     * Adds a series. Returns it, so points can be added later.
     */
    public ScatterSeries addSeries(ScatterSeries series) {
        this.series.add(series);
        return series;
    }

    public ScatterSeries addSeries(String name, double[] xs, double[] ys) {
        return addSeries(new ScatterSeries(name, xs, ys));
    }

    /**
     * Adds a series of bubbles: the area of each is in proportion to its size.
     */
    public ScatterSeries addSeries(String name, double[] xs, double[] ys, double[] sizes) {
        return addSeries(new ScatterSeries(name, xs, ys, sizes));
    }

    public ScatterSeries addSeries(String name, Color color, double[] xs, double[] ys) {
        return addSeries((ScatterSeries) new ScatterSeries(name, xs, ys).setColor(color));
    }

    /**
     * An empty series, for a chart whose points arrive one at a time.
     */
    public ScatterSeries addSeries(String name) {
        return addSeries(new ScatterSeries(name));
    }

    public ScatterSeries getSeries(String name) {
        for (ChartSeries s : series) {
            if (s.getName().equals(name)) return (ScatterSeries) s;
        }
        return null;
    }

    public ScatterSeries getSeries(int index) {
        return (ScatterSeries) series.get(index);
    }

    public List<ScatterSeries> getSeries() {
        List<ScatterSeries> out = new ArrayList<>();
        for (ChartSeries s : series) out.add((ScatterSeries) s);
        return List.copyOf(out);
    }

    public void removeSeries(ScatterSeries series) {
        this.series.remove(series);
    }

    public void clearSeries() {
        series.clear();
    }

    // ---- axes ---------------------------------------------------------------------------------------------------

    public AxisScale getXScale() {
        return xScale;
    }

    /**
     * How the horizontal axis spreads numbers: {@link AxisScale#LINEAR} (the default), {@link AxisScale#LOG}, or
     * {@link AxisScale#TIME}, where x is a moment in milliseconds since 1970-01-01T00:00Z.
     */
    public void setXScale(AxisScale scale) {
        xScale = scale == null ? AxisScale.LINEAR : scale;
        invalidateLayout();
    }

    public AxisScale getYScale() {
        return yScale;
    }

    /**
     * How the vertical axis spreads numbers: {@link AxisScale#LINEAR} (the default) or {@link AxisScale#LOG}. On a log axis a
     * point at zero or less cannot be placed and is left out.
     */
    public void setYScale(AxisScale scale) {
        if (scale == AxisScale.TIME) throw new IllegalArgumentException("Only the horizontal axis can be a time axis");
        yScale = scale == null ? AxisScale.LINEAR : scale;
        invalidateLayout();
    }

    /**
     * Fixes the horizontal axis' span instead of fitting it to the data. NaN for an end goes back to fitting. On a time axis the span is exactly this.
     */
    public void setXRange(double min, double max) {
        xMin = min;
        xMax = max;
        invalidateLayout();
    }

    public void setYRange(double min, double max) {
        yMin = min;
        yMax = max;
        invalidateLayout();
    }

    /**
     * Whether a linear axis always includes zero. Off by default, since a cloud of points is not a length.
     */
    public void setBeginAtZero(boolean x, boolean y) {
        xBeginAtZero = x;
        yBeginAtZero = y;
        invalidateLayout();
    }

    public boolean isShowGrid() {
        return showGrid;
    }

    /**
     * Whether lines run across the plot at each tick of either axis. On by default.
     */
    public void setShowGrid(boolean showGrid) {
        this.showGrid = showGrid;
    }

    public void setAxisTitles(String x, String y) {
        xAxisTitle = x == null ? "" : x;
        yAxisTitle = y == null ? "" : y;
        invalidateLayout();
    }

    public String getXAxisTitle() {
        return xAxisTitle;
    }

    public String getYAxisTitle() {
        return yAxisTitle;
    }

    /**
     * How x numbers are written on the horizontal axis and in the tooltip, or null (the default) for plain numbers. The
     * chart's {@link #setValueFormat value format} is for y alone, since the two are rarely the same unit. A time axis writes
     * moments instead.
     */
    public void setXFormat(DoubleFunction<String> format) {
        this.xFormat = format;
        invalidateLayout();
    }

    /**
     * The calendar of a time axis, UTC by default.
     */
    public void setTimeZone(ZoneId zone) {
        this.zone = zone == null ? ZoneOffset.UTC : zone;
        invalidateLayout();
    }

    /**
     * How moments are written on a time axis, or null for what suits the range.
     */
    public void setTimeFormat(DateTimeFormatter format) {
        this.timeFormat = format;
        invalidateLayout();
    }

    /**
     * The horizontal axis' range as drawn this frame, as {min, max}.
     */
    public double[] getXRange() {
        return xAxis.range();
    }

    /**
     * The vertical axis' range as drawn this frame, as {min, max}.
     */
    public double[] getYRange() {
        return yAxis.range();
    }

    // ---- points -------------------------------------------------------------------------------------------------

    public float getMarkerRadius() {
        return markerRadius;
    }

    /**
     * The radius of a point that has no size, in points. Default 5.
     */
    public void setMarkerRadius(float radius) {
        this.markerRadius = Math.max(1f, radius);
    }

    /**
     * The radii of the smallest and the largest bubble. The point with the largest size in the chart gets the largest, and the
     * others the area in proportion to their size, not smaller than the smallest. Default 4 and 26.
     */
    public void setBubbleRadius(float min, float max) {
        bubbleMin = Math.max(1f, min);
        bubbleMax = Math.max(bubbleMin, max);
    }

    /**
     * The point's radius: the marker's, or a bubble's by its size.
     */
    private float radius(ScatterSeries s, int index) {
        double size = s.getSize(index);
        if (Double.isNaN(size) || size < 0) return markerRadius;
        double share = Math.sqrt(size / largestSize);
        return (float) (bubbleMin + (bubbleMax - bubbleMin) * Math.min(1.0, share));
    }

    // ---- layout -------------------------------------------------------------------------------------------------

    @Override
    public int categoryCount() {
        int count = 0;
        for (ChartSeries s : series) count = Math.max(count, s.size());
        return count;
    }

    @Override
    protected long signature() {
        long hash = super.signature();
        for (ChartSeries c : series) {
            ScatterSeries s = (ScatterSeries) c;
            for (int i = 0; i < s.size(); i++) {
                hash = hash * 31 + Double.hashCode(s.getX(i));
                hash = hash * 31 + Double.hashCode(s.getSize(i));
            }
        }
        return hash;
    }

    /** The lowest and highest of one coordinate over the visible series, as {low, high}, or {NaN, NaN}. */
    private double[] extent(boolean horizontal) {
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        AxisScale scale = horizontal ? xScale : yScale;
        for (ChartSeries c : series) {
            ScatterSeries s = (ScatterSeries) c;
            if (!s.isVisible()) continue;
            for (int i = 0; i < s.size(); i++) {
                if (!placeable(s, i)) continue;
                double v = horizontal ? s.getX(i) : s.get(i);
                if (scale == AxisScale.LOG && !(v > 0)) continue;
                low = Math.min(low, v);
                high = Math.max(high, v);
            }
        }
        return low > high ? new double[]{Double.NaN, Double.NaN} : new double[]{low, high};
    }

    /** Whether point {@code i} has both its numbers and, on a log axis, both are positive. */
    private boolean placeable(ScatterSeries s, int i) {
        double x = s.getX(i), y = s.get(i);
        if (Double.isNaN(x) || Double.isNaN(y)) return false;
        if (xScale == AxisScale.LOG && !(x > 0)) return false;
        return yScale != AxisScale.LOG || y > 0;
    }

    @Override
    protected void layoutPlot(float left, float top, float right, float bottom) {
        largestSize = 0;
        for (ChartSeries c : series) {
            ScatterSeries s = (ScatterSeries) c;
            if (!s.isVisible()) continue;
            for (int i = 0; i < s.size(); i++) {
                double size = s.getSize(i);
                if (!Double.isNaN(size)) largestSize = Math.max(largestSize, size);
            }
        }
        if (largestSize <= 0) largestSize = 1;

        float lh = labelHeight();
        double[] xe = extent(true), ye = extent(false);
        yAxis.configure(yScale, yMin, yMax, yBeginAtZero);
        xAxis.configure(xScale, xMin, xMax, xBeginAtZero);
        xAxis.setTime(zone, timeFormat);

        int yTicks = Math.clamp((int) ((bottom - top) / 46f), 2, 11);
        yAxis.fit(ye[0], ye[1], yTicks, 0.04);
        float yLabelWidth = yAxis.widest(this::textWidth, getValueFormat());

        float yTitle = yAxisTitle.isEmpty() ? 0f : lh + 6f;
        float xTitle = xAxisTitle.isEmpty() ? 0f : lh + 6f;
        float leftMargin = yLabelWidth + 10f + yTitle;
        float bottomMargin = lh + 8f + xTitle;
        float topMargin = lh / 2f + 2f;

        float xWidest = xAxis.fitAcross(xe[0], xe[1], right - left - leftMargin, this::textWidth, xFormat, 0.04);
        leftMargin = Math.max(leftMargin, xWidest / 2f + 2f);
        float rightMargin = xWidest / 2f + 2f;

        plotX = left + leftMargin;
        plotY = top + topMargin;
        plotW = Math.max(1f, right - rightMargin - plotX);
        plotH = Math.max(1f, bottom - bottomMargin - plotY);
    }

    @Override
    protected void advanceExtra(float k, float delta) {
        xAxis.ease(k);
        yAxis.ease(k);
    }

    // ---- geometry -----------------------------------------------------------------------------------------------

    private float px(double x) {
        double t = xAxis.fraction(x);
        return Double.isNaN(t) ? Float.NaN : (float) (plotX + t * plotW);
    }

    private float py(double y) {
        double t = yAxis.fraction(y);
        return Double.isNaN(t) ? Float.NaN : (float) (plotY + plotH - t * plotH);
    }

    /**
     * Where point {@code point} of series {@code seriesIndex} is drawn now, as {x, y} in the chart's own coordinates (0, 0 is
     * its top-left corner), or null if it has no place: a gap, or zero or less on a log axis. For hover tests and overlays
     * placed over a point. It follows the chart as it eases, so call {@link #finishAnimation} first for the settled place.
     */
    public float[] pointPosition(int seriesIndex, int point) {
        ScatterSeries s = (ScatterSeries) series.get(seriesIndex);
        float x = px(s.shownX(point)), y = py(s.shown(point));
        return Float.isNaN(x) || Float.isNaN(y) ? null : new float[]{x, y};
    }

    /**
     * The radius point {@code point} of series {@code seriesIndex} is drawn with.
     */
    public float pointRadius(int seriesIndex, int point) {
        return radius((ScatterSeries) series.get(seriesIndex), point);
    }

    // ---- pointer ------------------------------------------------------------------------------------------------

    @Override
    protected void pointerMoved(double x, double y) {
        hoverSeries = -1;
        hoverPoint = -1;
        boolean inside = x >= plotX - 8 && x <= plotX + plotW + 8 && y >= plotY - 8 && y <= plotY + plotH + 8;
        if (inside) {
            double best = Double.MAX_VALUE;
            for (int si = 0; si < series.size(); si++) {
                ScatterSeries s = (ScatterSeries) series.get(si);
                if (!s.isVisible() || s.fade() < 0.5f) continue;
                for (int i = 0; i < s.size(); i++) {
                    float cx = px(s.shownX(i)), cy = py(s.shown(i));
                    if (Float.isNaN(cx) || Float.isNaN(cy)) continue;
                    double dx = cx - x, dy = cy - y, distance = Math.sqrt(dx * dx + dy * dy);
                    double reach = Math.max(radius(s, i) + 4.0, 10.0);
                    // among points that overlap, the one whose centre is nearest wins
                    if (distance <= reach && distance < best) {
                        best = distance;
                        hoverSeries = si;
                        hoverPoint = i;
                    }
                }
            }
        }
        if (hoverSeries >= 0) {
            lastSeries = hoverSeries;
            lastPoint = hoverPoint;
        }
        showTooltip(hoverSeries >= 0 && focusedEntry() < 0);
    }

    @Override
    protected ChartHit hitAt(double x, double y) {
        if (hoverSeries < 0) return null;
        return hitFor(hoverSeries, hoverPoint);
    }

    private ChartHit hitFor(int seriesIndex, int point) {
        ScatterSeries s = (ScatterSeries) series.get(seriesIndex);
        return new ChartHit(seriesIndex, point, s.getName(), xLabel(s.getX(point)), s.get(point));
    }

    private String xLabel(double x) {
        if (xScale == AxisScale.TIME) return TimeScale.format(timeFormat != null ? timeFormat : DateTimeFormatter.ISO_LOCAL_DATE_TIME, (long) x, zone);
        DoubleFunction<String> format = xFormat;
        return format != null ? format.apply(x) : ChartFormat.plain(x);
    }

    // ---- drawing ------------------------------------------------------------------------------------------------

    @Override
    protected void drawPlot(Renderer renderer) {
        drawAxes(renderer);
        renderer.pushClip(plotX - 10f, plotY - 10f, plotW + 20f, plotH + 20f);
        drawPoints(renderer);
        renderer.popClip();
        drawPointTooltip(renderer);
    }

    private void drawPoints(Renderer renderer) {
        boolean showHover = tooltipAlpha() > 0.01f;
        for (int si = 0; si < series.size(); si++) {
            ScatterSeries s = (ScatterSeries) series.get(si);
            float strength = s.fade() * focus(si);
            if (s.fade() <= 0.01f) continue;
            Color color = seriesColor(si);
            renderer.pushAlpha(strength);
            for (int i = 0; i < s.size(); i++) {
                float cx = px(s.shownX(i)), cy = py(s.shown(i));
                if (Float.isNaN(cx) || Float.isNaN(cy)) continue;
                float r = radius(s, i);
                boolean bubble = !Double.isNaN(s.getSize(i));
                renderer.drawCircle(cx, cy, r, color.withAlpha(bubble ? 0.62f : 0.9f));
                if (bubble) renderer.drawCircle(cx, cy, Math.max(0.5f, r - 1.5f), color.withAlpha(0.35f));
                if (showHover && si == hoverSeries && i == hoverPoint) {
                    renderer.drawCircle(cx, cy, r + 4f, Color.WHITE.withAlpha(0.9f));
                    renderer.drawCircle(cx, cy, r + 2f, color);
                }
            }
            renderer.popAlpha();
        }
    }

    private void drawPointTooltip(Renderer renderer) {
        if (tooltipAlpha() <= 0.01f || lastSeries < 0 || lastSeries >= series.size()) return;
        ScatterSeries s = (ScatterSeries) series.get(lastSeries);
        if (lastPoint < 0 || lastPoint >= s.size()) return;
        float cx = px(s.getX(lastPoint)), cy = py(s.get(lastPoint));
        if (Float.isNaN(cx) || Float.isNaN(cy)) return;
        List<TooltipRow> rows = new ArrayList<>();
        rows.add(new TooltipRow(null, (xAxisTitle.isEmpty() ? "x" : xAxisTitle) + ": " + xLabel(s.getX(lastPoint))));
        rows.add(new TooltipRow(null, (yAxisTitle.isEmpty() ? "y" : yAxisTitle) + ": " + formatValue(s.get(lastPoint))));
        if (!Double.isNaN(s.getSize(lastPoint))) rows.add(new TooltipRow(null, "size: " + ChartFormat.plain(s.getSize(lastPoint))));
        drawTooltipBox(renderer, s.getName(), rows, cx, cy);
    }

    private void drawAxes(Renderer renderer) {
        Color grid = grid(), text = text();
        float lh = labelHeight();

        for (NumericAxis.Tick tick : yAxis.ticks()) {
            float pos = py(tick.value());
            if (Float.isNaN(pos) || pos < plotY - 0.5f || pos > plotY + plotH + 0.5f) continue;
            if (showGrid) renderer.drawLine(plotX, pos, plotX + plotW, pos, tick.major() ? grid : grid.withAlpha(grid.getA() * 0.5f), 1f);
            if (tick.major()) drawLabel(renderer, yAxis.label(tick.value(), getValueFormat()), plotX - 8f, pos - lh / 2f, TextAlign.RIGHT);
        }
        for (NumericAxis.Tick tick : xAxis.ticks()) {
            float pos = px(tick.value());
            if (Float.isNaN(pos) || pos < plotX - 0.5f || pos > plotX + plotW + 0.5f) continue;
            if (showGrid) renderer.drawLine(pos, plotY, pos, plotY + plotH, tick.major() ? grid : grid.withAlpha(grid.getA() * 0.5f), 1f);
            if (tick.major()) drawLabel(renderer, xAxis.label(tick.value(), xFormat), pos, plotY + plotH + 6f, TextAlign.CENTER);
        }
        renderer.drawLine(plotX, plotY + plotH, plotX + plotW, plotY + plotH, text.withAlpha(0.35f), 1f);
        renderer.drawLine(plotX, plotY, plotX, plotY + plotH, text.withAlpha(0.35f), 1f);

        if (!yAxisTitle.isEmpty()) {
            renderer.pushTransform(getPadding() + lh / 2f, plotY + plotH / 2f, -90f, 1f, 1f, 0f, 0f);
            drawLabel(renderer, yAxisTitle, 0f, -lh / 2f, TextAlign.CENTER);
            renderer.popTransform();
        }
        if (!xAxisTitle.isEmpty()) {
            drawLabel(renderer, xAxisTitle, plotX + plotW / 2f, plotY + plotH + lh + 10f, TextAlign.CENTER);
        }
    }
}
