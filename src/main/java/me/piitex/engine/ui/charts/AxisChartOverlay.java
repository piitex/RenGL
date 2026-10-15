package me.piitex.engine.ui.charts;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.text.TextAlign;

import java.util.ArrayList;
import java.util.List;

/**
 * A chart with categories along one axis and numbers up the other: {@link LineChartOverlay} and {@link BarChartOverlay}.
 * It adds what they share: the series, the value axis with round-number ticks that rescales as the data changes, grid
 * lines, axis titles, and finding the category under the pointer.
 */
public abstract class AxisChartOverlay extends ChartOverlay {
    private double valueMin = Double.NaN, valueMax = Double.NaN;
    private boolean beginAtZero;
    private boolean showGrid = true;
    private String valueAxisTitle = "", categoryAxisTitle = "";

    // the scale the data asks for, and the range actually drawn, which eases toward it
    private double targetMin = 0, targetMax = 1, tickStep = 1;
    private int tickDecimals;
    private double axisMin = 0, axisMax = 1;
    private boolean axisPlaced;

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
     * fits the axis to its data unless this is set.
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
        return new double[]{axisMin, axisMax};
    }

    // ---- what the chart types decide ----------------------------------------------------------------------------

    /**
     * The lowest and highest value of the visible series, as {low, high}, or null when there are none.
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

    protected abstract void drawSeries(Renderer renderer);

    /**
     * Narrows the hovered category down further, such as to one bar of the group.
     */
    protected void refineHover(double x, double y) {
    }

    // ---- geometry -----------------------------------------------------------------------------------------------

    /**
     * The value axis position of {@code value}, in chart coordinates: y for a vertical axis, x for a horizontal one.
     */
    protected float valuePos(double value) {
        double span = axisMax - axisMin;
        if (span <= 0) span = 1;
        double t = (value - axisMin) / span;
        return valueAxisHorizontal() ? (float) (plotX + t * plotW) : (float) (plotY + plotH - t * plotH);
    }

    /**
     * The category axis position of the middle of category {@code index}.
     */
    protected float categoryPos(int index) {
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
     * Where zero is drawn: the baseline of bars and the floor of an area. Clamped onto the plot when the axis doesn't reach zero.
     */
    protected float zeroPos() {
        double at = Math.clamp(0.0, axisMin, axisMax);
        return valuePos(at);
    }

    @Override
    protected double baseline() {
        return Math.clamp(0.0, targetMin, targetMax);
    }

    @Override
    protected double epsilon() {
        return Math.max(targetMax - targetMin, 1e-9) * 1e-4;
    }

    // ---- layout -------------------------------------------------------------------------------------------------

    @Override
    protected void layoutPlot(float left, float top, float right, float bottom) {
        boolean horizontal = valueAxisHorizontal();
        int categoryCount = categoryCount();

        // The scale the data asks for. The room it needs for labels is worked out from it.
        double[] extent = dataExtent();
        double low = extent == null ? 0 : extent[0], high = extent == null ? 1 : extent[1];
        if (beginAtZero) {
            low = Math.min(low, 0);
            high = Math.max(high, 0);
        }
        if (!Double.isNaN(valueMin)) low = Math.min(low, valueMin);
        if (!Double.isNaN(valueMax)) high = Math.max(high, valueMax);
        float room = horizontal ? right - left : bottom - top;
        int maxTicks = Math.clamp((int) (room / (horizontal ? 80f : 46f)), 2, 11);
        NiceScale nice = NiceScale.of(low, high, maxTicks);
        targetMin = Double.isNaN(valueMin) ? nice.min() : valueMin;
        targetMax = Double.isNaN(valueMax) ? nice.max() : valueMax;
        if (targetMax <= targetMin) targetMax = targetMin + nice.step();
        tickStep = nice.step();
        tickDecimals = nice.decimals();
        if (!axisPlaced) {
            axisMin = targetMin;
            axisMax = targetMax;
            axisPlaced = true;
        }

        float tickLabelWidth = 0f;
        for (long k = firstTick(); k <= lastTick(); k++) {
            tickLabelWidth = Math.max(tickLabelWidth, textWidth(tickLabel(k * tickStep)));
        }
        float categoryLabelWidth = 0f;
        for (int i = 0; i < categoryCount; i++) {
            categoryLabelWidth = Math.max(categoryLabelWidth, textWidth(categoryLabel(i)));
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
        float firstHalf = categoryCount > 0 ? textWidth(categoryLabel(0)) / 2f : 0f;
        float lastHalf = categoryCount > 0 ? textWidth(categoryLabel(categoryCount - 1)) / 2f : 0f;
        if (!horizontal && !categoriesInBands()) leftMargin = Math.max(leftMargin, firstHalf + 2f);
        float rightMargin = !horizontal && !categoriesInBands() ? lastHalf + 2f : horizontal ? 16f : 4f;
        float topMargin = horizontal ? 4f : lh / 2f + 2f;

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

    private long firstTick() {
        return (long) Math.ceil(targetMin / tickStep - 1e-9);
    }

    private long lastTick() {
        return (long) Math.floor(targetMax / tickStep + 1e-9);
    }

    private String tickLabel(double value) {
        if (getValueFormat() != null) return getValueFormat().apply(value);
        return ChartFormat.fixed(value, tickDecimals);
    }

    @Override
    protected void advanceExtra(float k, float delta) {
        double epsilon = (targetMax - targetMin) * 1e-4;
        axisMin += (targetMin - axisMin) * k;
        axisMax += (targetMax - axisMax) * k;
        if (Math.abs(targetMin - axisMin) <= epsilon) axisMin = targetMin;
        if (Math.abs(targetMax - axisMax) <= epsilon) axisMax = targetMax;
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
            if (categoriesInBands()) index = (int) Math.floor(along / (length / n));
            else index = n == 1 ? 0 : (int) Math.round(along / (length / (n - 1)));
            hoverCategory = Math.clamp(index, 0, n - 1);
            refineHover(x, y);
        }
        if (hoverCategory >= 0 && !hasValues(hoverCategory)) hoverCategory = -1;
        if (hoverCategory >= 0) lastHoverCategory = hoverCategory;
        showTooltip(hoverCategory >= 0 && focusedEntry() < 0);
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
        long first = firstTick(), last = lastTick();

        for (long k = first; k <= last; k++) {
            double value = k * tickStep;
            float pos = valuePos(value);
            if (horizontal ? (pos < plotX - 0.5f || pos > plotX + plotW + 0.5f) : (pos < plotY - 0.5f || pos > plotY + plotH + 0.5f)) continue;
            if (showGrid) {
                boolean zero = value == 0 && axisMin < 0;
                Color line = zero ? text.withAlpha(0.35f) : grid;
                if (horizontal) renderer.drawLine(pos, plotY, pos, plotY + plotH, line, 1f);
                else renderer.drawLine(plotX, pos, plotX + plotW, pos, line, 1f);
            }
            String label = tickLabel(value);
            if (horizontal) drawLabel(renderer, label, pos, plotY + plotH + 6f, TextAlign.CENTER);
            else drawLabel(renderer, label, plotX - 8f, pos - lh / 2f, TextAlign.RIGHT);
        }
        // the category axis' own line
        if (horizontal) renderer.drawLine(zeroPos(), plotY, zeroPos(), plotY + plotH, text.withAlpha(0.35f), 1f);
        else renderer.drawLine(plotX, zeroPos(), plotX + plotW, zeroPos(), text.withAlpha(0.35f), 1f);

        int n = categoryCount();
        int stride = (int) labelStride;
        for (int i = 0; i < n; i += stride) {
            float pos = categoryPos(i);
            if (horizontal) drawLabel(renderer, categoryLabel(i), plotX - 8f, pos - lh / 2f, TextAlign.RIGHT);
            else drawLabel(renderer, categoryLabel(i), pos, plotY + plotH + 6f, TextAlign.CENTER);
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
