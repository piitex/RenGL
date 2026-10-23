package me.piitex.engine.ui.charts;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.animation.Easing;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.text.TextAlign;
import me.piitex.engine.ui.text.Font;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * A circle divided into slices, each as wide as its share of the total: where a budget goes, how a disk is used. Give
 * it a hole ({@link #setHoleRatio}) and it is a donut, with room in the middle for the total or the slice being pointed at.
 * <pre>{@code
 * PieChartOverlay chart = new PieChartOverlay(360, 360);
 * chart.setTitle("Disk usage");
 * chart.addSlice("Video", 420);
 * chart.addSlice("Photos", 180);
 * chart.addSlice("Other", 60);
 * chart.setHoleRatio(0.6f); // a donut
 * }</pre>
 * The slices sweep open when the chart first appears and ease when their numbers change. Pointing at a slice (or its
 * legend entry) lifts it out of the circle; clicking a legend entry takes the slice out and the rest close up.
 * Values should not be negative: a negative or {@code NaN} value counts as empty.
 */
public class PieChartOverlay extends ChartOverlay {
    private static final float LIFT = 9f;

    private final List<Color> sliceColors = new ArrayList<>(); // null entries take the palette
    private final BitSet hidden = new BitSet();
    private float holeRatio;
    private float startAngle = -90f;
    private float sliceGap = 2f;
    private boolean showLabels = true;
    private String centerText, centerCaption;

    private org.jetbrains.skia.Font centerFont;
    private float centerFontSize;

    // geometry in chart coordinates
    private float centerX, centerY, radius;

    // per-slice easing
    private float[] present = new float[0]; // 1 shown, 0 hidden
    private float[] lift = new float[0];
    private float revealTime;
    private float reveal;
    private int hoverSlice = -1;
    private int lastHoverSlice = -1;

    public PieChartOverlay(double width, double height) {
        super(width, height);
        series.add(new ChartSeries("Value"));
        setLegendPosition(LegendPosition.RIGHT);
    }

    // ---- data ---------------------------------------------------------------------------------------------------

    /**
     * Replaces every slice. {@code labels} and {@code values} line up one to one.
     */
    public void setSlices(List<String> labels, double... values) {
        categories.clear();
        categories.addAll(labels);
        sliceColors.clear();
        for (int i = 0; i < labels.size(); i++) sliceColors.add(null);
        hidden.clear();
        series.getFirst().setValues(values);
    }

    /**
     * Adds a slice at the end, in the next palette color.
     */
    public void addSlice(String label, double value) {
        addSlice(label, value, null);
    }

    public void addSlice(String label, double value, Color color) {
        padColors();
        categories.add(label == null ? "" : label);
        sliceColors.add(color);
        series.getFirst().set(categories.size() - 1, value);
    }

    /**
     * Changes the value of slice {@code index}; the slice eases to its new share.
     */
    public void setSliceValue(int index, double value) {
        series.getFirst().set(index, value);
    }

    public double getSliceValue(int index) {
        return series.getFirst().get(index);
    }

    public int getSliceCount() {
        return categories.size();
    }

    /**
     * Gives slice {@code index} a color of its own, or null for the palette's.
     */
    public void setSliceColor(int index, Color color) {
        padColors();
        if (index < 0 || index >= sliceColors.size()) throw new IndexOutOfBoundsException("slice " + index);
        sliceColors.set(index, color);
    }

    public Color sliceColor(int index) {
        padColors();
        Color own = index >= 0 && index < sliceColors.size() ? sliceColors.get(index) : null;
        return own != null ? own : getPalette().get(Math.floorMod(index, getPalette().size()));
    }

    /**
     * Takes slice {@code index} out of the circle, as the legend does on a click, or puts it back.
     */
    public void setSliceVisible(int index, boolean visible) {
        hidden.set(index, !visible);
        invalidateLayout();
    }

    public boolean isSliceVisible(int index) {
        return !hidden.get(index);
    }

    /**
     * The sum of the visible slices' values.
     */
    public double getTotal() {
        double total = 0;
        for (int i = 0; i < categories.size(); i++) {
            if (!hidden.get(i)) total += Math.max(0, finite(series.getFirst().get(i)));
        }
        return total;
    }

    private void padColors() {
        while (sliceColors.size() < categories.size()) sliceColors.add(null);
    }

    private static double finite(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    // ---- look ---------------------------------------------------------------------------------------------------

    public float getHoleRatio() {
        return holeRatio;
    }

    /**
     * The size of the hole as a share of the radius: 0 (the default) for a pie, 0.55 to 0.7 for a donut. At most 0.9.
     */
    public void setHoleRatio(float ratio) {
        this.holeRatio = Math.clamp(ratio, 0f, 0.9f);
        invalidateLayout();
    }

    public float getStartAngle() {
        return startAngle;
    }

    /**
     * Where the first slice begins, in degrees clockwise from the right. The default, -90, is straight up.
     */
    public void setStartAngle(float degrees) {
        this.startAngle = degrees;
    }

    /**
     * The width of the gap between slices, in points. Default 2.
     */
    public void setSliceGap(float gap) {
        this.sliceGap = Math.max(0f, gap);
    }

    public boolean isShowLabels() {
        return showLabels;
    }

    /**
     * Whether each slice that is wide enough carries its share as a percentage. On by default.
     */
    public void setShowLabels(boolean showLabels) {
        this.showLabels = showLabels;
    }

    /**
     * The text in the middle of a donut, with a smaller line under it. Null (the default) shows the total, and the
     * slice being pointed at while one is.
     */
    public void setCenterText(String text, String caption) {
        this.centerText = text;
        this.centerCaption = caption;
    }

    // ---- hooks --------------------------------------------------------------------------------------------------

    @Override
    protected int legendCount() {
        return categories.size();
    }

    @Override
    protected String legendLabel(int index) {
        return categories.get(index);
    }

    @Override
    protected Color legendColor(int index) {
        return sliceColor(index);
    }

    @Override
    protected boolean legendOn(int index) {
        return !hidden.get(index);
    }

    @Override
    protected void toggleLegend(int index) {
        hidden.flip(index);
    }

    @Override
    protected long signature() {
        return super.signature() * 31 + hidden.hashCode() + 7L * sliceColors.hashCode();
    }

    @Override
    protected float focusDim() {
        return 0.5f;
    }

    @Override
    protected int focusedEntry() {
        return hoverSlice >= 0 ? hoverSlice : super.focusedEntry();
    }

    @Override
    protected void layoutPlot(float left, float top, float right, float bottom) {
        float room = Math.min(right - left, bottom - top);
        radius = Math.max(8f, room / 2f - LIFT - 2f);
        centerX = (left + right) / 2f;
        centerY = (top + bottom) / 2f;
    }

    @Override
    protected void advanceExtra(float k, float delta) {
        int n = categories.size();
        if (present.length != n) {
            float[] grown = new float[n];
            float[] lifted = new float[n];
            java.util.Arrays.fill(grown, 1f);
            System.arraycopy(present, 0, grown, 0, Math.min(present.length, n));
            System.arraycopy(lift, 0, lifted, 0, Math.min(lift.length, n));
            present = grown;
            lift = lifted;
        }
        for (int i = 0; i < n; i++) {
            float goal = hidden.get(i) ? 0f : 1f;
            present[i] += (goal - present[i]) * k;
            if (Math.abs(goal - present[i]) < 0.003f) present[i] = goal;
            float up = i == hoverSlice || (hoverSlice < 0 && i == legendHoverIndex()) ? 1f : 0f;
            lift[i] += (up - lift[i]) * Math.max(k, 0f) * 1.6f;
            lift[i] = Math.clamp(lift[i], 0f, 1f);
            if (Math.abs(up - lift[i]) < 0.005f) lift[i] = up;
        }
        if (k >= 1f && delta == 0f) {
            reveal = 1f;
            revealTime = 1f;
        } else {
            revealTime = Math.min(1f, revealTime + delta / 1.0f);
            reveal = Easing.EASE_OUT_CUBIC.apply(isAnimated() ? revealTime : 1f);
        }
    }

    private int legendHoverIndex() {
        return super.focusedEntry();
    }

    @Override
    protected void onDispose() {
        if (centerFont != null) centerFont.close();
        centerFont = null;
    }

    // ---- geometry -----------------------------------------------------------------------------------------------

    /** The share of the whole that slice {@code index} takes as drawn, 0 to 1. */
    private double share(int index, double totalShown) {
        if (totalShown <= 0) return 0;
        return Math.max(0, finite(series.getFirst().shown(index))) * present[index] / totalShown;
    }

    private double shownTotal() {
        double total = 0;
        for (int i = 0; i < categories.size() && i < present.length; i++) {
            total += Math.max(0, finite(series.getFirst().shown(i))) * present[i];
        }
        return total;
    }

    @Override
    protected void pointerMoved(double x, double y) {
        hoverSlice = -1;
        if (!Double.isNaN(x) && radius > 0 && present.length == categories.size()) {
            double dx = x - centerX, dy = y - centerY;
            double distance = Math.hypot(dx, dy);
            double inner = radius * holeRatio;
            if (distance <= radius + LIFT && distance >= inner - 2) {
                double angle = Math.toDegrees(Math.atan2(dy, dx)) - startAngle;
                angle = ((angle % 360) + 360) % 360;
                double total = shownTotal();
                double cumulative = 0;
                for (int i = 0; i < categories.size(); i++) {
                    double sweep = share(i, total) * 360;
                    if (sweep > 0 && angle >= cumulative && angle < cumulative + sweep) {
                        hoverSlice = i;
                        break;
                    }
                    cumulative += sweep;
                }
            }
        }
        if (hoverSlice >= 0) lastHoverSlice = hoverSlice;
        showTooltip(hoverSlice >= 0);
    }

    @Override
    protected ChartHit hitAt(double x, double y) {
        if (hoverSlice < 0) return null;
        return new ChartHit(0, hoverSlice, series.getFirst().getName(), categoryLabel(hoverSlice), series.getFirst().get(hoverSlice));
    }

    // ---- drawing ------------------------------------------------------------------------------------------------

    @Override
    protected void drawPlot(Renderer renderer) {
        int n = categories.size();
        if (n == 0 || present.length != n) return;
        double total = shownTotal();
        if (total <= 0) {
            drawEmptyRing(renderer);
            return;
        }
        float limit = startAngle + 360f * reveal;
        double cumulative = startAngle;
        for (int i = 0; i < n; i++) {
            double sweep = share(i, total) * 360;
            double from = cumulative, to = cumulative + sweep;
            cumulative = to;
            if (sweep <= 0.01) continue;
            double visibleTo = Math.min(to, limit);
            if (visibleTo - from < 0.05) continue;

            Color color = sliceColor(i);
            float strength = focus(i) * Math.max(present[i], 0f);
            renderer.pushAlpha(strength);
            double mid = Math.toRadians((from + to) / 2);
            float offset = LIFT * lift[i];
            float cx = centerX + (float) Math.cos(mid) * offset, cy = centerY + (float) Math.sin(mid) * offset;
            drawSlice(renderer, cx, cy, from, visibleTo, color);
            if (showLabels && reveal >= 1f && sweep >= 18) drawSliceLabel(renderer, i, cx, cy, from, to, color, total);
            renderer.popAlpha();
        }
        drawCenter(renderer, total);
        if (tooltipAlpha() > 0.01f && lastHoverSlice >= 0 && lastHoverSlice < n) {
            drawSliceTooltip(renderer, lastHoverSlice, total);
        }
    }

    private void drawEmptyRing(Renderer renderer) {
        float inner = radius * holeRatio;
        renderer.pushAlpha(0.25f);
        if (inner > 0) {
            float[] ring = ringPolygon(centerX, centerY, inner, radius, 0, 360, 0f, 0f);
            renderer.drawPolygon(ring, ring.length / 2, grid());
        } else {
            renderer.drawCircle(centerX, centerY, radius, grid());
        }
        renderer.popAlpha();
    }

    private void drawSlice(Renderer renderer, float cx, float cy, double fromDegrees, double toDegrees, Color color) {
        float inner = radius * holeRatio;
        float[] polygon = sliceShape(cx, cy, inner, radius, fromDegrees, toDegrees);
        if (polygon != null) renderer.drawPolygon(polygon, polygon.length / 2, color);
    }

    /**
     * The outline of a slice, with its sides pulled in by half the gap so neighbours are separated by parallel edges
     * of equal width, not by a wedge that widens toward the rim.
     */
    private float[] sliceShape(float cx, float cy, float inner, float outer, double fromDegrees, double toDegrees) {
        double sweep = toDegrees - fromDegrees;
        double half = sliceGap / 2.0;
        boolean full = sweep >= 359.9;
        double outerInset = full ? 0 : Math.toDegrees(Math.asin(Math.min(1.0, half / outer)));
        if (sweep <= outerInset * 2 + 0.1 && !full) return null;
        return ringPolygon(cx, cy, inner, outer, fromDegrees, toDegrees, (float) half, (float) outerInset);
    }

    /**
     * A ring sector as a polygon: the outer arc one way and the inner arc back, or for a pie, a point at the center
     * (moved out along the slice so the gap stays the same width right to the middle).
     */
    private float[] ringPolygon(float cx, float cy, float inner, float outer, double fromDegrees, double toDegrees, float halfGap, float outerInsetDegrees) {
        double sweep = toDegrees - fromDegrees;
        boolean full = sweep >= 359.9;
        double a0 = Math.toRadians(fromDegrees + outerInsetDegrees), a1 = Math.toRadians(toDegrees - outerInsetDegrees);
        int outerSteps = Math.max(2, (int) Math.ceil((a1 - a0) * outer / 3.5));
        float[] pts;
        int n = 0;
        if (inner > 0.5f) {
            double innerInset = full ? 0 : Math.toDegrees(Math.asin(Math.min(1.0, halfGap / inner)));
            double b0 = Math.toRadians(fromDegrees + innerInset), b1 = Math.toRadians(toDegrees - innerInset);
            if (b1 <= b0) return null;
            int innerSteps = Math.max(2, (int) Math.ceil((b1 - b0) * inner / 3.5));
            pts = new float[(outerSteps + innerSteps + 2) * 2];
            for (int i = 0; i <= outerSteps; i++) {
                double a = a0 + (a1 - a0) * i / outerSteps;
                pts[n++] = cx + (float) (Math.cos(a) * outer);
                pts[n++] = cy + (float) (Math.sin(a) * outer);
            }
            for (int i = 0; i <= innerSteps; i++) {
                double a = b1 - (b1 - b0) * i / innerSteps;
                pts[n++] = cx + (float) (Math.cos(a) * inner);
                pts[n++] = cy + (float) (Math.sin(a) * inner);
            }
            return pts;
        }
        pts = new float[(outerSteps + 2) * 2];
        float ox = cx, oy = cy;
        if (!full && halfGap > 0) {
            double halfSweep = Math.toRadians(sweep / 2);
            double push = Math.min(outer * 0.6, halfGap / Math.max(Math.sin(halfSweep), 0.05));
            double mid = (a0 + a1) / 2;
            ox += (float) (Math.cos(mid) * push);
            oy += (float) (Math.sin(mid) * push);
        }
        pts[n++] = ox;
        pts[n++] = oy;
        for (int i = 0; i <= outerSteps; i++) {
            double a = a0 + (a1 - a0) * i / outerSteps;
            pts[n++] = cx + (float) (Math.cos(a) * outer);
            pts[n++] = cy + (float) (Math.sin(a) * outer);
        }
        return pts;
    }

    private void drawSliceLabel(Renderer renderer, int index, float cx, float cy, double from, double to, Color color, double total) {
        double mid = Math.toRadians((from + to) / 2);
        float inner = radius * holeRatio;
        float r = inner > 0 ? (inner + radius) / 2f : radius * 0.64f;
        String text = ChartFormat.plain(Math.round(share(index, total) * 1000) / 10.0) + "%";
        float x = cx + (float) Math.cos(mid) * r, y = cy + (float) Math.sin(mid) * r;
        renderer.drawText(text, x, y - labelHeight() / 2f, labelFont(), contrastOn(color), TextAlign.CENTER);
    }

    private void drawCenter(Renderer renderer, double total) {
        float inner = radius * holeRatio;
        if (inner < 24f) return;
        String primary = centerText, caption = centerCaption;
        if (primary == null) {
            int shown = tooltipAlpha() > 0.5f ? lastHoverSlice : -1;
            if (shown >= 0 && shown < categories.size() && hoverSlice >= 0) {
                primary = formatValue(series.getFirst().get(shown));
                caption = categories.get(shown);
            } else {
                primary = formatValue(getTotal());
                caption = "Total";
            }
        }
        float size = Math.clamp(inner * 0.42f, 12f, 34f);
        float available = inner * 1.6f;
        if (centerFont == null || Math.abs(size - centerFontSize) > 0.5f) {
            if (centerFont != null) centerFont.close();
            centerFont = Font.sized(size);
            centerFontSize = size;
        }
        while (size > 10f && centerFont.measureTextWidth(primary) > available) {
            size -= 2f;
            centerFont.close();
            centerFont = Font.sized(size);
            centerFontSize = size;
        }
        float primaryHeight = me.piitex.engine.ui.text.FontMetricsCompat.of(centerFont).getHeight();
        boolean hasCaption = caption != null && !caption.isEmpty();
        float block = primaryHeight + (hasCaption ? labelHeight() + 1f : 0f);
        float top = centerY - block / 2f;
        renderer.drawText(primary, centerX, top, centerFont, text(), TextAlign.CENTER);
        if (hasCaption) {
            renderer.drawText(caption, centerX, top + primaryHeight + 1f, labelFont(), text().withAlpha(0.7f), TextAlign.CENTER);
        }
    }

    private void drawSliceTooltip(Renderer renderer, int index, double total) {
        List<TooltipRow> rows = new ArrayList<>();
        double value = series.getFirst().get(index);
        double percent = total > 0 ? Math.max(0, finite(series.getFirst().shown(index))) * present[index] / total * 100 : 0;
        rows.add(new TooltipRow(sliceColor(index), categories.get(index) + ": " + formatValue(value)));
        rows.add(new TooltipRow(null, ChartFormat.plain(Math.round(percent * 10) / 10.0) + "% of total"));
        float ax = Double.isNaN(pointerX) ? centerX : (float) pointerX;
        float ay = Double.isNaN(pointerY) ? centerY : (float) pointerY;
        drawTooltipBox(renderer, null, rows, ax, ay);
    }
}
