package me.piitex.engine.ui.charts;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.text.TextAlign;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A chart with categories along one axis and numbers up the other: {@link LineChartOverlay} and {@link BarChartOverlay}.
 * It adds what they share: the series, the value axis with round-number ticks that rescales as the data changes (linear or
 * logarithmic), grid lines, axis titles, and finding the category under the pointer. A line chart can also place its
 * categories by time instead of in equal steps.
 */
public abstract class AxisChartOverlay extends ChartOverlay {
    private double valueMin = Double.NaN, valueMax = Double.NaN;
    private boolean beginAtZero;
    private boolean showGrid = true;
    private String valueAxisTitle = "", categoryAxisTitle = "";
    private AxisScale valueScale = AxisScale.LINEAR;

    // the value axis: the scale the data asks for, and the range actually drawn, which eases toward it
    private final NumericAxis value = new NumericAxis();

    // the time axis, when the categories sit at moments instead of in equal steps
    private final NumericAxis time = new NumericAxis();
    private boolean timed;
    private long[] times = new long[0];
    private int timeCount;
    private ZoneId zone = ZoneOffset.UTC;
    private DateTimeFormatter timeFormat;
    private double timeFrom = Double.NaN, timeTo = Double.NaN;
    private DateTimeFormatter detailFormat = DateTimeFormatter.ISO_LOCAL_DATE;

    protected float plotX, plotY, plotW, plotH;
    private float labelStride = 1f;

    /** The category under the pointer, or -1. */
    protected int hoverCategory = -1;
    private int lastHoverCategory = -1;

    protected AxisChartOverlay(double width, double height, boolean beginAtZero) {
        super(width, height);
        this.beginAtZero = beginAtZero;
    }

    // ---- series -------------------------------------------------------------------------------------------------

    /**
     * Adds a series. Its color comes from the palette unless it was given one. Returns it, so it can be changed later.
     */
    public ChartSeries addSeries(ChartSeries series) {
        this.series.add(series);
        return series;
    }

    public ChartSeries addSeries(String name, double... values) {
        return addSeries(new ChartSeries(name, values));
    }

    public ChartSeries addSeries(String name, Color color, double... values) {
        return addSeries(new ChartSeries(name, values).setColor(color));
    }

    /**
     * The series called {@code name}, or null.
     */
    public ChartSeries getSeries(String name) {
        for (ChartSeries s : series) {
            if (s.getName().equals(name)) return s;
        }
        return null;
    }

    public ChartSeries getSeries(int index) {
        return series.get(index);
    }

    public List<ChartSeries> getSeries() {
        return List.copyOf(series);
    }

    public void removeSeries(ChartSeries series) {
        this.series.remove(series);
    }

    public void clearSeries() {
        series.clear();
    }

    // ---- axis ---------------------------------------------------------------------------------------------------

    public boolean isBeginAtZero() {
        return beginAtZero;
    }

    /**
     * Whether the value axis always includes zero. Bars start there, so a bar chart does by default; a line chart
     * fits the axis to its data unless this is set. A logarithmic axis has no zero and ignores it.
     */
    public void setBeginAtZero(boolean beginAtZero) {
        this.beginAtZero = beginAtZero;
        invalidateLayout();
    }

    /**
     * Fixes the low end of the value axis, instead of fitting it to the data. NaN goes back to fitting.
     */
    public void setValueMin(double min) {
        valueMin = min;
        invalidateLayout();
    }

    /**
     * Fixes the high end of the value axis, instead of fitting it to the data. NaN goes back to fitting.
     */
    public void setValueMax(double max) {
        valueMax = max;
        invalidateLayout();
    }

    public void setValueRange(double min, double max) {
        valueMin = min;
        valueMax = max;
        invalidateLayout();
    }

    public AxisScale getValueScale() {
        return valueScale;
    }

    /**
     * How the value axis spreads numbers: {@link AxisScale#LINEAR} (the default) or {@link AxisScale#LOG}. On a log axis the
     * ticks are powers of ten, with fainter lines between, and a value of zero or less cannot be placed, so it is drawn like a
     * gap. A bar chart's bars start at the bottom of the axis. A stacked bar chart stays linear, since the parts of a stack add.
     */
    public void setValueScale(AxisScale scale) {
        if (scale != AxisScale.LINEAR && scale != AxisScale.LOG) {
            throw new IllegalArgumentException("The value axis is linear or logarithmic, not " + scale);
        }
        this.valueScale = scale;
        invalidateLayout();
    }

    public boolean isLogScale() {
        return valueScale == AxisScale.LOG;
    }

    /**
     * Shorthand for {@link #setValueScale} with {@link AxisScale#LOG} or {@link AxisScale#LINEAR}.
     */
    public void setLogScale(boolean log) {
        setValueScale(log ? AxisScale.LOG : AxisScale.LINEAR);
    }

    public boolean isShowGrid() {
        return showGrid;
    }

    /**
     * Whether lines run across the plot at each tick of the value axis. On by default.
     */
    public void setShowGrid(boolean showGrid) {
        this.showGrid = showGrid;
    }

    /**
     * The title written beside the value axis, or empty for none.
     */
    public void setValueAxisTitle(String title) {
        valueAxisTitle = title == null ? "" : title;
        invalidateLayout();
    }

    /**
     * The title written along the category axis, or empty for none.
     */
    public void setCategoryAxisTitle(String title) {
        categoryAxisTitle = title == null ? "" : title;
        invalidateLayout();
    }

    public String getValueAxisTitle() {
        return valueAxisTitle;
    }

    public String getCategoryAxisTitle() {
        return categoryAxisTitle;
    }

    /**
     * The value axis' range as drawn this frame (it eases when the data changes), as {min, max}.
     */
    public double[] getAxisRange() {
        return value.range();
    }

    // ---- time axis ----------------------------------------------------------------------------------------------

    private void requireLine() {
        if (categoriesInBands()) throw new UnsupportedOperationException("Only a line chart has a time axis");
    }

    /**
     * Places the categories at moments in time instead of in equal steps: one time for each value of the series, in milliseconds since
     * 1970-01-01T00:00Z, earliest first. The axis then runs from the first to the last, with ticks on round times (every 6
     * hours, on the 1st of the month, each year), and a gap in the data shows as a gap in the line. The category labels are not
     * used; the tooltip names the moment. Line charts only.
     */
    public void setTimes(long... times) {
        requireLine();
        this.times = times.clone();
        timeCount = times.length;
        timed = true;
        invalidateLayout();
    }

    public void setTimes(Instant... instants) {
        long[] millis = new long[instants.length];
        for (int i = 0; i < millis.length; i++) millis[i] = instants[i].toEpochMilli();
        setTimes(millis);
    }

    /**
     * Places the categories on days, each at midnight in the chart's {@link #setTimeZone time zone} (UTC by default; set the zone first).
     * A game with a calendar of its own can give its dates here.
     */
    public void setDates(LocalDate... dates) {
        long[] millis = new long[dates.length];
        for (int i = 0; i < millis.length; i++) millis[i] = dates[i].atStartOfDay(zone).toInstant().toEpochMilli();
        setTimes(millis);
    }

    /**
     * Adds a moment at the end. For a chart that grows, add the time and then a value to each series.
     */
    public void addTime(long millis) {
        requireLine();
        if (timeCount == times.length) times = Arrays.copyOf(times, Math.max(8, times.length * 2));
        times[timeCount++] = millis;
        timed = true;
        invalidateLayout();
    }

    public void addTime(Instant instant) {
        addTime(instant.toEpochMilli());
    }

    public void addDate(LocalDate date) {
        addTime(date.atStartOfDay(zone).toInstant().toEpochMilli());
    }

    /**
     * Removes the first {@code count} moments, for a chart that scrolls. Remove the same number from each series with
     * {@link ChartSeries#removeFirst}.
     */
    public void removeFirstTimes(int count) {
        count = Math.clamp(count, 0, timeCount);
        System.arraycopy(times, count, times, 0, timeCount - count);
        timeCount -= count;
        invalidateLayout();
    }

    /**
     * Goes back to equal steps and category labels.
     */
    public void clearTimes() {
        timed = false;
        timeCount = 0;
        invalidateLayout();
    }

    /**
     * A copy of the moments, in milliseconds.
     */
    public long[] getTimes() {
        return Arrays.copyOf(times, timeCount);
    }

    public boolean isTimeAxis() {
        return timed;
    }

    public ZoneId getTimeZone() {
        return zone;
    }

    /**
     * The calendar the time axis' ticks and labels follow: midnight, the 1st of a month and the hour are those of this zone.
     * UTC by default, so a chart reads the same on every machine; use {@code ZoneId.systemDefault()} for local time.
     */
    public void setTimeZone(ZoneId zone) {
        this.zone = zone == null ? ZoneOffset.UTC : zone;
        invalidateLayout();
    }

    /**
     * How moments are written, in the tooltip and on the axis, or null (the default) for what suits the range: {@code 14:30}
     * across a day, {@code Mar 5} across a month, {@code 2026} across decades.
     */
    public void setTimeFormat(DateTimeFormatter format) {
        this.timeFormat = format;
        invalidateLayout();
    }

    /**
     * Fixes the span the time axis shows instead of fitting the first and last time, for a window that moves with the clock.
     */
    public void setTimeRange(long from, long to) {
        timeFrom = from;
        timeTo = to;
        invalidateLayout();
    }

    /**
     * Goes back to showing the first time to the last.
     */
    public void clearTimeRange() {
        timeFrom = Double.NaN;
        timeTo = Double.NaN;
        invalidateLayout();
    }

    /**
     * The time axis' range as drawn this frame, as {from, to} in milliseconds.
     */
    public double[] getTimeRange() {
        return time.range();
    }

    @Override
    public String categoryLabel(int index) {
        if (timed && index >= 0 && index < timeCount) {
            return TimeScale.format(timeFormat != null ? timeFormat : detailFormat, times[index], zone);
        }
        return super.categoryLabel(index);
    }

    @Override
    public int categoryCount() {
        return timed ? Math.max(super.categoryCount(), timeCount) : super.categoryCount();
    }

    @Override
    protected long signature() {
        long hash = super.signature();
        if (timed) {
            hash = hash * 31 + timeCount;
            for (int i = 0; i < timeCount; i++) hash = hash * 31 + Long.hashCode(times[i]);
        }
        return hash * 31 + (timed ? 1 : 0);
    }

    // ---- what the chart types decide ----------------------------------------------------------------------------

    /**
     * The lowest and highest value of the visible series, as {low, high}, or null when there are none. On a log axis only
     * values {@linkplain #usable usable} on it count.
     */
    protected abstract double[] dataExtent();

    /**
     * Whether the value axis runs left to right (a horizontal bar chart) instead of bottom to top.
     */
    protected boolean valueAxisHorizontal() {
        return false;
    }

    /**
     * Whether categories sit in the middle of equal bands (bars) or on the ticks from edge to edge (lines).
     */
    protected abstract boolean categoriesInBands();

    /**
     * Whether the value axis may be logarithmic. A stacked bar chart says no.
     */
    protected boolean supportsLogScale() {
        return true;
    }

    protected abstract void drawSeries(Renderer renderer);

    /**
     * Narrows the hovered category down further, such as to one bar of the group.
     */
    protected void refineHover(double x, double y) {
    }

    /**
     * Whether the value axis is logarithmic right now.
     */
    protected final boolean logAxis() {
        return valueScale == AxisScale.LOG && supportsLogScale();
    }

    /**
     * Whether {@code v} can be placed on the value axis: a number, and positive on a log axis.
     */
    protected final boolean usable(double v) {
        return !Double.isNaN(v) && (!logAxis() || v > 0);
    }

    // ---- geometry -----------------------------------------------------------------------------------------------

    /**
     * The value axis position of {@code value}, in chart coordinates: y for a vertical axis, x for a horizontal one. NaN
     * when it cannot be placed (zero or less on a log axis).
     */
    protected float valuePos(double value) {
        double t = this.value.fraction(value);
        if (Double.isNaN(t)) return Float.NaN;
        return valueAxisHorizontal() ? (float) (plotX + t * plotW) : (float) (plotY + plotH - t * plotH);
    }

    /**
     * The category axis position of the middle of category {@code index}. NaN for a category with no time on a time axis.
     */
    protected float categoryPos(int index) {
        if (timed) {
            if (index < 0 || index >= timeCount) return Float.NaN;
            return plotX + (float) (time.fraction(times[index]) * plotW);
        }
        int n = Math.max(1, categoryCount());
        float start = valueAxisHorizontal() ? plotY : plotX;
        float length = valueAxisHorizontal() ? plotH : plotW;
        if (categoriesInBands()) return start + (index + 0.5f) * length / n;
        return n == 1 ? start + length / 2f : start + index * length / (n - 1);
    }

    /**
     * The length along the category axis one category has.
     */
    protected float categoryStep() {
        int n = Math.max(1, categoryCount());
        float length = valueAxisHorizontal() ? plotH : plotW;
        if (categoriesInBands()) return length / n;
        return n == 1 ? length : length / (n - 1);
    }

    /**
     * Where zero is drawn: the baseline of bars and the floor of an area. Clamped onto the plot when the axis doesn't reach
     * zero; on a log axis, which has no zero, the low end of the axis.
     */
    protected float zeroPos() {
        if (logAxis()) return valueAxisHorizontal() ? plotX : plotY + plotH;
        double[] range = value.range();
        double at = Math.clamp(0.0, range[0], range[1]);
        return valuePos(at);
    }

    @Override
    protected double baseline() {
        double[] range = value.targetRange();
        return logAxis() ? range[0] : Math.clamp(0.0, range[0], range[1]);
    }

    @Override
    protected double epsilon() {
        return value.epsilon();
    }

    // ---- layout -------------------------------------------------------------------------------------------------

    @Override
    protected void layoutPlot(float left, float top, float right, float bottom) {
        boolean horizontal = valueAxisHorizontal();
        int categoryCount = categoryCount();
        boolean log = logAxis();

        // The scale the data asks for. The room it needs for labels is worked out from it.
        double[] extent = dataExtent();
        double low = extent == null ? Double.NaN : extent[0], high = extent == null ? Double.NaN : extent[1];
        value.configure(log ? AxisScale.LOG : AxisScale.LINEAR, valueMin, valueMax, beginAtZero && !log);
        float room = horizontal ? right - left : bottom - top;
        int maxTicks = Math.clamp((int) (room / (horizontal ? 80f : 46f)), 2, 11);
        value.fit(low, high, maxTicks, 0);

        float tickLabelWidth = value.widest(this::textWidth, getValueFormat());
        float categoryLabelWidth = 0f;
        if (!timed) {
            for (int i = 0; i < categoryCount; i++) {
                categoryLabelWidth = Math.max(categoryLabelWidth, textWidth(categoryLabel(i)));
            }
        }
        float lh = labelHeight();
        float valueTitle = valueAxisTitle.isEmpty() ? 0f : lh + 6f;
        float categoryTitle = categoryAxisTitle.isEmpty() ? 0f : lh + 6f;

        float leftMargin, bottomMargin;
        if (horizontal) {
            leftMargin = categoryLabelWidth + 10f + categoryTitle;
            bottomMargin = lh + 8f + valueTitle;
        } else {
            leftMargin = tickLabelWidth + 10f + valueTitle;
            bottomMargin = lh + 8f + categoryTitle;
        }
        float firstHalf = !timed && categoryCount > 0 ? textWidth(categoryLabel(0)) / 2f : 0f;
        float lastHalf = !timed && categoryCount > 0 ? textWidth(categoryLabel(categoryCount - 1)) / 2f : 0f;
        if (!horizontal && !categoriesInBands()) leftMargin = Math.max(leftMargin, firstHalf + 2f);
        float rightMargin = !horizontal && !categoriesInBands() ? lastHalf + 2f : horizontal ? 16f : 4f;
        float topMargin = horizontal ? 4f : lh / 2f + 2f;

        if (timed) {
            double[] span = timeSpan();
            time.configure(AxisScale.TIME, timeFrom, timeTo, false);
            time.setTime(zone, timeFormat);
            float widest = time.fitAcross(span[0], span[1], right - left - leftMargin, this::textWidth, null, 0);
            leftMargin = Math.max(leftMargin, widest / 2f + 2f);
            rightMargin = widest / 2f + 2f;
            detailFormat = TimeScale.detail(times, timeCount, zone);
        }

        plotX = left + leftMargin;
        plotY = top + topMargin;
        plotW = Math.max(1f, right - rightMargin - plotX);
        plotH = Math.max(1f, bottom - bottomMargin - plotY);

        // Labels along the category axis that would overlap are thinned out.
        float along = horizontal ? plotH : plotW;
        float perCategory = categoryCount <= 1 ? along : along / (categoriesInBands() ? categoryCount : categoryCount - 1);
        float labelExtent = horizontal ? lh + 4f : categoryLabelWidth + 10f;
        labelStride = Math.max(1f, (float) Math.ceil(labelExtent / Math.max(1f, perCategory)));
    }

    /** The first and last time, in milliseconds, or NaN when there are none. */
    private double[] timeSpan() {
        double lo = Double.NaN, hi = Double.NaN;
        for (int i = 0; i < timeCount; i++) {
            lo = Double.isNaN(lo) ? times[i] : Math.min(lo, times[i]);
            hi = Double.isNaN(hi) ? times[i] : Math.max(hi, times[i]);
        }
        return new double[]{lo, hi};
    }

    @Override
    protected void advanceExtra(float k, float delta) {
        value.ease(k);
        if (timed) time.ease(k);
    }

    // ---- pointer ------------------------------------------------------------------------------------------------

    @Override
    protected void pointerMoved(double x, double y) {
        hoverCategory = -1;
        int n = categoryCount();
        boolean inside = x >= plotX - 4 && x <= plotX + plotW + 4 && y >= plotY - 4 && y <= plotY + plotH + 4;
        if (inside && n > 0) {
            boolean horizontal = valueAxisHorizontal();
            double along = horizontal ? y - plotY : x - plotX;
            double length = horizontal ? plotH : plotW;
            int index;
            if (timed) {
                index = nearestTime(x);
            } else if (categoriesInBands()) {
                index = (int) Math.floor(along / (length / n));
            } else {
                index = n == 1 ? 0 : (int) Math.round(along / (length / (n - 1)));
            }
            hoverCategory = index < 0 ? -1 : Math.clamp(index, 0, n - 1);
            if (hoverCategory >= 0) refineHover(x, y);
        }
        if (hoverCategory >= 0 && !hasValues(hoverCategory)) hoverCategory = -1;
        if (hoverCategory >= 0) lastHoverCategory = hoverCategory;
        showTooltip(hoverCategory >= 0 && focusedEntry() < 0);
    }

    /** The category whose time is nearest the chart x, among those that have values. */
    private int nearestTime(double x) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < timeCount; i++) {
            if (!hasValues(i)) continue;
            double distance = Math.abs(categoryPos(i) - x);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private boolean hasValues(int category) {
        for (ChartSeries s : series) {
            if (s.isVisible() && !Double.isNaN(s.get(category))) return true;
        }
        return false;
    }

    protected void drawHoverTooltip(Renderer renderer) {
        if (tooltipAlpha() <= 0.01f || lastHoverCategory < 0 || lastHoverCategory >= categoryCount()) return;
        List<TooltipRow> rows = new ArrayList<>();
        for (int i = 0; i < series.size(); i++) {
            ChartSeries s = series.get(i);
            double v = s.get(lastHoverCategory);
            if (!s.isVisible() || Double.isNaN(v)) continue;
            rows.add(new TooltipRow(seriesColor(i), s.getName() + ": " + formatValue(v)));
        }
        if (rows.isEmpty()) return;
        float ax = valueAxisHorizontal() ? (float) (Double.isNaN(pointerX) ? plotX + plotW / 2 : pointerX) : categoryPos(lastHoverCategory);
        float ay = valueAxisHorizontal() ? categoryPos(lastHoverCategory) : (float) (Double.isNaN(pointerY) ? plotY + plotH / 2 : pointerY);
        if (Float.isNaN(ax) || Float.isNaN(ay)) return;
        drawTooltipBox(renderer, categoryLabel(lastHoverCategory), rows, ax, ay);
    }

    @Override
    protected ChartHit hitAt(double x, double y) {
        if (hoverCategory < 0) return null;
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        double pointer = valueAxisHorizontal() ? x : y;
        for (int i = 0; i < series.size(); i++) {
            ChartSeries s = series.get(i);
            double v = s.get(hoverCategory);
            if (!s.isVisible() || Double.isNaN(v)) continue;
            double distance = Math.abs(valuePos(v) - pointer);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best < 0 ? null : hitFor(best, hoverCategory);
    }

    protected ChartHit hitFor(int seriesIndex, int category) {
        ChartSeries s = series.get(seriesIndex);
        return new ChartHit(seriesIndex, category, s.getName(), categoryLabel(category), s.get(category));
    }

    // ---- drawing ------------------------------------------------------------------------------------------------

    @Override
    protected void drawPlot(Renderer renderer) {
        drawAxes(renderer);
        renderer.pushClip(plotX - 6f, plotY - 6f, plotW + 12f, plotH + 12f);
        drawSeries(renderer);
        renderer.popClip();
        drawHoverTooltip(renderer);
    }

    private void drawAxes(Renderer renderer) {
        boolean horizontal = valueAxisHorizontal();
        Color grid = grid(), text = text();
        float lh = labelHeight();
        boolean log = logAxis();
        double[] range = value.range();

        for (NumericAxis.Tick tick : value.ticks()) {
            double v = tick.value();
            float pos = valuePos(v);
            if (Float.isNaN(pos)) continue;
            if (horizontal ? (pos < plotX - 0.5f || pos > plotX + plotW + 0.5f) : (pos < plotY - 0.5f || pos > plotY + plotH + 0.5f)) continue;
            if (showGrid) {
                boolean zero = v == 0 && !log && range[0] < 0;
                Color line = zero ? text.withAlpha(0.35f) : tick.major() ? grid : grid.withAlpha(grid.getA() * 0.5f);
                if (horizontal) renderer.drawLine(pos, plotY, pos, plotY + plotH, line, 1f);
                else renderer.drawLine(plotX, pos, plotX + plotW, pos, line, 1f);
            }
            if (!tick.major()) continue;
            String label = value.label(v, getValueFormat());
            if (horizontal) drawLabel(renderer, label, pos, plotY + plotH + 6f, TextAlign.CENTER);
            else drawLabel(renderer, label, plotX - 8f, pos - lh / 2f, TextAlign.RIGHT);
        }
        // the category axis' own line
        if (horizontal) renderer.drawLine(zeroPos(), plotY, zeroPos(), plotY + plotH, text.withAlpha(0.35f), 1f);
        else renderer.drawLine(plotX, zeroPos(), plotX + plotW, zeroPos(), text.withAlpha(0.35f), 1f);

        if (timed) {
            for (NumericAxis.Tick tick : time.ticks()) {
                double f = time.fraction(tick.value());
                if (f < -0.001 || f > 1.001) continue;
                float x = plotX + (float) (f * plotW);
                renderer.drawLine(x, plotY + plotH, x, plotY + plotH + 4f, text.withAlpha(0.35f), 1f);
                drawLabel(renderer, time.label(tick.value(), null), x, plotY + plotH + 6f, TextAlign.CENTER);
            }
        } else {
            int n = categoryCount();
            int stride = (int) labelStride;
            for (int i = 0; i < n; i += stride) {
                float pos = categoryPos(i);
                if (horizontal) drawLabel(renderer, categoryLabel(i), plotX - 8f, pos - lh / 2f, TextAlign.RIGHT);
                else drawLabel(renderer, categoryLabel(i), pos, plotY + plotH + 6f, TextAlign.CENTER);
            }
        }

        if (!valueAxisTitle.isEmpty()) {
            if (horizontal) {
                drawLabel(renderer, valueAxisTitle, plotX + plotW / 2f, plotY + plotH + lh + 10f, TextAlign.CENTER);
            } else {
                renderer.pushTransform(padding() + lh / 2f, plotY + plotH / 2f, -90f, 1f, 1f, 0f, 0f);
                drawLabel(renderer, valueAxisTitle, 0f, -lh / 2f, TextAlign.CENTER);
                renderer.popTransform();
            }
        }
        if (!categoryAxisTitle.isEmpty()) {
            if (horizontal) {
                renderer.pushTransform(padding() + lh / 2f, plotY + plotH / 2f, -90f, 1f, 1f, 0f, 0f);
                drawLabel(renderer, categoryAxisTitle, 0f, -lh / 2f, TextAlign.CENTER);
                renderer.popTransform();
            } else {
                drawLabel(renderer, categoryAxisTitle, plotX + plotW / 2f, plotY + plotH + lh + 10f, TextAlign.CENTER);
            }
        }
    }

    private float padding() {
        return getPadding();
    }
}
