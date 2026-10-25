package me.piitex.engine.ui.charts;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.text.TextAlign;

/**
 * Bars for comparing amounts across categories: sales per region, the size of each file type. Each category is a group
 * with one bar per series, side by side or stacked into one.
 * <pre>{@code
 * BarChartOverlay chart = new BarChartOverlay(640, 360);
 * chart.setTitle("Sales by region");
 * chart.setCategories("North", "South", "East", "West");
 * chart.addSeries("2025", 42, 31, 55, 28);
 * chart.addSeries("2026", 51, 36, 49, 40);
 * }</pre>
 * Bars grow from the zero line (negative values hang below it) and the axis always includes zero, since a bar's length
 * is the number. On a {@linkplain #setLogScale log axis}, which has no zero, they grow from the bottom instead. {@link #setStacked} piles a category's series on top of each other, and {@link #setHorizontal} turns
 * the chart on its side, which suits long category names.
 */
public class BarChartOverlay extends AxisChartOverlay {
    private boolean stacked;
    private boolean horizontal;
    private boolean showValues;
    private float cornerRadius = 4f;
    private float groupGap = 0.3f;

    private final float[] rect = new float[4];
    private boolean rectPositive;
    private int hoverSeries = -1;

    public BarChartOverlay(double width, double height) {
        super(width, height, true);
    }

    public boolean isStacked() {
        return stacked;
    }

    /**
     * Puts each category's series on top of each other, positive values up and negative ones down, instead of side by side.
     */
    public void setStacked(boolean stacked) {
        this.stacked = stacked;
        invalidateLayout();
    }

    public boolean isHorizontal() {
        return horizontal;
    }

    /**
     * Draws the bars left to right with the categories down the side.
     */
    public void setHorizontal(boolean horizontal) {
        this.horizontal = horizontal;
        invalidateLayout();
    }

    public boolean isShowValues() {
        return showValues;
    }

    /**
     * Writes each bar's value at its end, when there is room. Not for stacked bars.
     */
    public void setShowValues(boolean showValues) {
        this.showValues = showValues;
    }

    public float getCornerRadius() {
        return cornerRadius;
    }

    /**
     * How round the outer end of each bar is, in points. Limited to half a bar's width. Stacked bars are square. Default 4.
     */
    public void setBarCornerRadius(float radius) {
        this.cornerRadius = Math.max(0f, radius);
    }

    /**
     * The share of a category's room left empty between groups, from 0 to 0.9. Default 0.3.
     */
    public void setGroupGap(float gap) {
        this.groupGap = Math.clamp(gap, 0f, 0.9f);
    }

    @Override
    protected boolean valueAxisHorizontal() {
        return horizontal;
    }

    @Override
    protected boolean categoriesInBands() {
        return true;
    }

    @Override
    protected boolean supportsLogScale() {
        return !stacked;
    }

    @Override
    protected double[] dataExtent() {
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        int n = categoryCount();
        for (int c = 0; c < n; c++) {
            double positive = 0, negative = 0;
            for (ChartSeries s : series) {
                double v = s.get(c);
                if (!s.isVisible() || !usable(v)) continue;
                if (stacked) {
                    if (v >= 0) positive += v;
                    else negative += v;
                } else {
                    low = Math.min(low, v);
                    high = Math.max(high, v);
                }
            }
            if (stacked) {
                low = Math.min(low, negative);
                high = Math.max(high, positive);
            }
        }
        return low > high ? null : new double[]{low, high};
    }

    private int visibleCount() {
        int count = 0;
        for (ChartSeries s : series) {
            if (s.isVisible()) count++;
        }
        return count;
    }

    /**
     * Works out where the bar of series {@code index} in {@code category} is and puts x, y, width and height in {@link #rect}.
     * Returns false when there is no bar: a gap in the data, or a hidden series.
     */
    private boolean barRect(int index, int category) {
        ChartSeries s = series.get(index);
        double v = s.shown(category);
        if (!s.isVisible() || !usable(v)) return false;

        int visible = visibleCount();
        float step = categoryStep();
        float group = step * (1f - groupGap);
        float center = categoryPos(category);
        float slot, offset;
        double from = 0, to = v;
        if (stacked) {
            slot = group;
            offset = -group / 2f;
            for (int i = 0; i < index; i++) {
                ChartSeries other = series.get(i);
                double ov = other.shown(category);
                if (!other.isVisible() || Double.isNaN(ov) || (ov >= 0) != (v >= 0)) continue;
                from += ov;
            }
            to = from + v;
        } else {
            int slotIndex = 0;
            for (int i = 0; i < index; i++) {
                if (series.get(i).isVisible()) slotIndex++;
            }
            float gap = visible > 1 ? Math.min(3f, group * 0.05f) : 0f;
            slot = (group - gap * (visible - 1)) / visible;
            offset = -group / 2f + slotIndex * (slot + gap);
        }
        rectPositive = v >= 0 || logAxis();
        // a log axis has no zero: a bar grows from the bottom of the axis
        float a = logAxis() ? zeroPos() : valuePos(from), b = valuePos(to);
        float low = Math.min(a, b), extent = Math.abs(b - a);
        if (horizontal) {
            rect[0] = low;
            rect[1] = center + offset;
            rect[2] = extent;
            rect[3] = slot;
        } else {
            rect[0] = center + offset;
            rect[1] = low;
            rect[2] = slot;
            rect[3] = extent;
        }
        return true;
    }

    @Override
    protected void refineHover(double x, double y) {
        hoverSeries = -1;
        for (int i = 0; i < series.size(); i++) {
            if (!barRect(i, hoverCategory)) continue;
            // widen thin bars so they can be pointed at, and let the whole length of the group's slot count
            float pad = 2f;
            if (horizontal ? (y >= rect[1] - pad && y <= rect[1] + rect[3] + pad && x >= Math.min(zeroPos(), rect[0]) - pad && x <= rect[0] + rect[2] + pad)
                    : (x >= rect[0] - pad && x <= rect[0] + rect[2] + pad && y >= rect[1] - pad && y <= rect[1] + rect[3] + pad)) {
                hoverSeries = i;
                break;
            }
        }
    }

    @Override
    protected ChartHit hitAt(double x, double y) {
        if (hoverCategory >= 0 && hoverSeries >= 0) return hitFor(hoverSeries, hoverCategory);
        return null;
    }

    @Override
    protected void drawSeries(Renderer renderer) {
        int n = categoryCount();
        if (hoverCategory >= 0 && tooltipAlpha() > 0.01f) {
            // a faint band behind the category being pointed at
            float step = categoryStep(), center = categoryPos(hoverCategory);
            renderer.pushAlpha(tooltipAlpha());
            if (horizontal) renderer.drawQuad(plotX, center - step / 2f, plotW, step, text().withAlpha(0.06f), 0f);
            else renderer.drawQuad(center - step / 2f, plotY, step, plotH, text().withAlpha(0.06f), 0f);
            renderer.popAlpha();
        }
        for (int si = 0; si < series.size(); si++) {
            Color color = seriesColor(si);
            float strength = focus(si);
            renderer.pushAlpha(strength);
            for (int c = 0; c < n; c++) {
                if (!barRect(si, c)) continue;
                drawBar(renderer, color, c == hoverCategory && si == hoverSeries && tooltipAlpha() > 0.01f);
                if (showValues && !stacked) drawValue(renderer, si, c);
            }
            renderer.popAlpha();
        }
    }

    private void drawBar(Renderer renderer, Color color, boolean hovered) {
        float x = rect[0], y = rect[1], w = rect[2], h = rect[3];
        if (w < 0.5f || h < 0.5f) return;
        float radius = stacked ? 0f : Math.min(cornerRadius, Math.min(w, h) / 2f);
        Color fill = hovered ? lighten(color) : color;
        if (radius <= 0f) {
            renderer.drawQuad(x, y, w, h, fill, 0f);
            return;
        }
        // Round only the end that points away from zero: draw a taller rounded rectangle and clip it to the bar.
        renderer.pushClip(x, y, w, h);
        boolean positive = rectPositive;
        if (horizontal) {
            if (positive) renderer.drawQuad(x - radius, y, w + radius, h, fill, radius);
            else renderer.drawQuad(x, y, w + radius, h, fill, radius);
        } else {
            if (positive) renderer.drawQuad(x, y, w, h + radius, fill, radius);
            else renderer.drawQuad(x, y - radius, w, h + radius, fill, radius);
        }
        renderer.popClip();
    }

    private void drawValue(Renderer renderer, int seriesIndex, int category) {
        String label = formatValue(series.get(seriesIndex).get(category));
        float lh = labelHeight(), tw = textWidth(label);
        double v = series.get(seriesIndex).shown(category);
        boolean positive = v >= 0;
        if (horizontal) {
            float end = positive ? rect[0] + rect[2] + 5f : rect[0] - 5f - tw;
            if (end + tw > plotX + plotW + 6f || end < plotX - 6f) return;
            drawLabel(renderer, label, end, rect[1] + (rect[3] - lh) / 2f, TextAlign.LEFT);
        } else {
            if (tw > categoryStep()) return;
            float y = positive ? rect[1] - lh - 3f : rect[1] + rect[3] + 3f;
            drawLabel(renderer, label, rect[0] + rect[2] / 2f, y, TextAlign.CENTER);
        }
    }

    private static Color lighten(Color color) {
        return new Color(color.getR() + (1f - color.getR()) * 0.22f, color.getG() + (1f - color.getG()) * 0.22f,
                color.getB() + (1f - color.getB()) * 0.22f, color.getA());
    }
}
