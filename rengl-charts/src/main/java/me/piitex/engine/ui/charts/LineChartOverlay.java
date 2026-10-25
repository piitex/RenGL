package me.piitex.engine.ui.charts;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;

/**
 * Lines through values that follow one another: a price over a year, the frame time of the last few seconds. Each
 * series is a line; they share the category labels along the bottom and the value axis up the side.
 * <pre>{@code
 * LineChartOverlay chart = new LineChartOverlay(640, 360);
 * chart.setTitle("Visitors");
 * chart.setCategories("Mon", "Tue", "Wed", "Thu", "Fri");
 * chart.addSeries("Web", 120, 180, 150, 240, 210);
 * chart.addSeries("App", 80, 95, 140, 130, 190);
 * layout.addElement(chart);
 * }</pre>
 * Point at the chart to see every series' value at that category. The lines can be smoothed ({@link #setSmooth}),
 * filled underneath ({@link #setFillArea}), and marked at each value ({@link #setShowMarkers}). A gap ({@code NaN}) breaks the line.
 */
public class LineChartOverlay extends AxisChartOverlay {
    private boolean smooth;
    private boolean fillArea;
    private boolean showMarkers = true;
    private float lineWidth = 2.5f;
    private float markerRadius = 3.5f;

    private float[] points = new float[64];
    private float[] curve = new float[256];
    private float[] fill = new float[256];

    public LineChartOverlay(double width, double height) {
        super(width, height, false);
    }

    public boolean isSmooth() {
        return smooth;
    }

    /**
     * Curves the lines through the points instead of joining them with straight segments. The curve never swings
     * above a high point or below a low one, so it doesn't show a value that isn't in the data.
     */
    public void setSmooth(boolean smooth) {
        this.smooth = smooth;
    }

    public boolean isFillArea() {
        return fillArea;
    }

    /**
     * Fills the area between each line and the zero line (or the bottom of the plot) with a faint wash of its color.
     */
    public void setFillArea(boolean fillArea) {
        this.fillArea = fillArea;
    }

    public boolean isShowMarkers() {
        return showMarkers;
    }

    /**
     * Whether a dot marks each value. On by default. Without them, the dots still appear on the category being pointed at.
     */
    public void setShowMarkers(boolean showMarkers) {
        this.showMarkers = showMarkers;
    }

    public float getLineWidth() {
        return lineWidth;
    }

    public void setLineWidth(float lineWidth) {
        this.lineWidth = Math.max(0.5f, lineWidth);
    }

    @Override
    protected double[] dataExtent() {
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (ChartSeries s : series) {
            if (!s.isVisible()) continue;
            for (int i = 0; i < s.size(); i++) {
                double v = s.get(i);
                if (!usable(v)) continue;
                low = Math.min(low, v);
                high = Math.max(high, v);
            }
        }
        return low > high ? null : new double[]{low, high};
    }

    @Override
    protected boolean categoriesInBands() {
        return false;
    }

    @Override
    protected void drawSeries(Renderer renderer) {
        int n = categoryCount();
        for (int si = 0; si < series.size(); si++) {
            ChartSeries s = series.get(si);
            float strength = s.fade() * focus(si);
            if (s.fade() <= 0.01f || n == 0) continue;
            Color color = seriesColor(si);
            renderer.pushAlpha(strength);

            int count = 0;
            for (int i = 0; i <= n; i++) {
                double v = i < n ? s.shown(i) : Double.NaN;
                // a value with no place on the axes (zero on a log axis, a point with no time) breaks the line like a gap
                float px = Double.isNaN(v) ? Float.NaN : categoryPos(i), py = Double.isNaN(v) ? Float.NaN : valuePos(v);
                if (!Float.isNaN(px) && !Float.isNaN(py)) {
                    if (count * 2 + 2 > points.length) points = java.util.Arrays.copyOf(points, points.length * 2);
                    points[count * 2] = px;
                    points[count * 2 + 1] = py;
                    count++;
                } else if (count > 0) {
                    drawRun(renderer, count, color);
                    if (showMarkers) {
                        for (int p = 0; p < count; p++) renderer.drawCircle(points[p * 2], points[p * 2 + 1], markerRadius, color);
                    }
                    count = 0;
                }
            }
            renderer.popAlpha();
        }

        if (hoverCategory >= 0 && tooltipAlpha() > 0.01f) drawCrosshair(renderer, hoverCategory);
    }

    /** Draws one unbroken run of {@code count} points held in {@link #points}. */
    private void drawRun(Renderer renderer, int count, Color color) {
        float[] path = points;
        int pathCount = count;
        if (smooth && count > 2) {
            pathCount = sampleCurve(count);
            path = curve;
        }
        if (count == 1) return; // a lone point is only its marker
        if (fillArea) {
            float floor = zeroPos();
            int total = pathCount + 2;
            if (fill.length < total * 2) fill = new float[total * 2];
            float[] polygon = fill;
            System.arraycopy(path, 0, polygon, 0, pathCount * 2);
            polygon[pathCount * 2] = path[(pathCount - 1) * 2];
            polygon[pathCount * 2 + 1] = floor;
            polygon[pathCount * 2 + 2] = path[0];
            polygon[pathCount * 2 + 3] = floor;
            renderer.drawPolygon(polygon, total, color.withAlpha(0.16f));
        }
        renderer.drawPolyline(path, pathCount, color, lineWidth);
    }

    private void drawCrosshair(Renderer renderer, int category) {
        float x = categoryPos(category);
        if (Float.isNaN(x)) return;
        renderer.pushAlpha(tooltipAlpha());
        renderer.drawLine(x, plotY, x, plotY + plotH, text().withAlpha(0.3f), 1f);
        for (int si = 0; si < series.size(); si++) {
            ChartSeries s = series.get(si);
            double v = s.shown(category);
            if (!s.isVisible() || Double.isNaN(v)) continue;
            float y = valuePos(v);
            if (Float.isNaN(y)) continue;
            renderer.drawCircle(x, y, markerRadius + 3.5f, Color.WHITE.withAlpha(0.9f));
            renderer.drawCircle(x, y, markerRadius + 1.5f, seriesColor(si));
        }
        renderer.popAlpha();
    }

    /**
     * Fills {@link #curve} with a smooth path through the first {@code count} points of {@link #points} and returns how
     * many points it holds. Monotone cubic interpolation (Fritsch and Carlson): the tangent at each point is limited
     * so a segment between two points never goes beyond them.
     */
    private int sampleCurve(int count) {
        float[] p = points;
        double[] slope = new double[count - 1];
        double[] tangent = new double[count];
        for (int i = 0; i < count - 1; i++) {
            float dx = p[(i + 1) * 2] - p[i * 2];
            slope[i] = dx == 0 ? 0 : (p[(i + 1) * 2 + 1] - p[i * 2 + 1]) / dx;
        }
        tangent[0] = slope[0];
        tangent[count - 1] = slope[count - 2];
        for (int i = 1; i < count - 1; i++) {
            tangent[i] = slope[i - 1] * slope[i] <= 0 ? 0 : (slope[i - 1] + slope[i]) / 2;
        }
        for (int i = 0; i < count - 1; i++) {
            if (slope[i] == 0) {
                tangent[i] = 0;
                tangent[i + 1] = 0;
                continue;
            }
            double a = tangent[i] / slope[i], b = tangent[i + 1] / slope[i];
            double s = a * a + b * b;
            if (s > 9) {
                double tau = 3 / Math.sqrt(s);
                tangent[i] = tau * a * slope[i];
                tangent[i + 1] = tau * b * slope[i];
            }
        }

        int out = 0;
        for (int i = 0; i < count - 1; i++) {
            float x0 = p[i * 2], y0 = p[i * 2 + 1], x1 = p[(i + 1) * 2], y1 = p[(i + 1) * 2 + 1];
            float h = x1 - x0;
            int steps = Math.clamp((int) Math.ceil(Math.abs(h) / 5f), 4, 32);
            if ((out + steps + 1) * 2 > curve.length) {
                curve = java.util.Arrays.copyOf(curve, Math.max(curve.length * 2, (out + steps + 1) * 2));
            }
            for (int s = (i == 0 ? 0 : 1); s <= steps; s++) {
                float t = (float) s / steps;
                float t2 = t * t, t3 = t2 * t;
                float h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
                curve[out * 2] = x0 + h * t;
                curve[out * 2 + 1] = h00 * y0 + (float) (h10 * h * tangent[i]) + h01 * y1 + (float) (h11 * h * tangent[i + 1]);
                out++;
            }
        }
        return out;
    }
}
