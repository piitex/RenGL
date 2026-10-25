package me.piitex.engine.ui.charts;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.theme.LightTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Log value axes, the time axis of a line chart, and the scatter and bubble chart. */
class ChartAxesTest {
    @AfterEach
    void restoreTheme() {
        ThemeManager.setCurrent(new LightTheme());
    }

    private static Bitmap draw(ChartOverlay chart) {
        int w = (int) chart.getWidth(), h = (int) chart.getHeight();
        try (OffscreenRenderer r = new OffscreenRenderer(w, h, 1f)) {
            r.begin();
            r.drawQuad(0, 0, w, h, Color.WHITE, 0f);
            chart.render(r);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                return bmp;
            }
        }
    }

    private static void click(ChartOverlay chart, double x, double y) {
        chart.getMouseClickListenerConsumer().accept(new MouseClickEvent(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE));
    }

    private static boolean near(int argb, Color expected, int tolerance) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return Math.abs(r - expected.getR() * 255) <= tolerance && Math.abs(g - expected.getG() * 255) <= tolerance
                && Math.abs(b - expected.getB() * 255) <= tolerance;
    }

    // ---- log value axis -----------------------------------------------------------------------------------------

    @Test
    void aLogAxisSpreadsPowersOfTenEvenly() {
        LineChartOverlay line = new LineChartOverlay(500, 300);
        line.setLogScale(true);
        line.addSeries("a", 1, 10, 100, 1000);
        line.finishAnimation();
        assertEquals(1, line.getAxisRange()[0], 1e-9);
        assertEquals(1000, line.getAxisRange()[1], 1e-6);
        float step = line.valuePos(1) - line.valuePos(10);
        assertTrue(step > 20, "decades are apart: " + step);
        assertEquals(step, line.valuePos(10) - line.valuePos(100), 0.01f);
        assertEquals(step, line.valuePos(100) - line.valuePos(1000), 0.01f);
        assertTrue(Float.isNaN(line.valuePos(0)));
    }

    @Test
    void valuesThatCannotBePlacedOnALogAxisAreGapsAndDoNotStretchIt() {
        LineChartOverlay line = new LineChartOverlay(500, 300);
        line.setLogScale(true);
        line.addSeries("a", 0, 50, -3, 5000, Double.NaN);
        line.finishAnimation();
        double[] range = line.getAxisRange();
        assertEquals(10, range[0], 1e-9, "only 50 and 5000 count");
        assertEquals(10000, range[1], 1e-6);
        try (Bitmap bmp = draw(line)) {
            assertNotNull(bmp);
        }
    }

    @Test
    void aLogAxisIgnoresBeginAtZero() {
        BarChartOverlay bar = new BarChartOverlay(500, 300);
        bar.setLogScale(true);
        bar.addSeries("a", 20, 200, 2000);
        bar.finishAnimation();
        assertEquals(10, bar.getAxisRange()[0], 1e-9, "a bar chart starts at zero, but a log axis has none");
        assertEquals(10000, bar.getAxisRange()[1], 1e-6);
    }

    @Test
    void barsOnALogAxisGrowFromTheBottom() {
        BarChartOverlay bar = new BarChartOverlay(600, 300);
        bar.setLegendPosition(LegendPosition.NONE);
        bar.setBarCornerRadius(0);
        bar.setGroupGap(0.5f);
        bar.setCategories("a", "b", "c");
        bar.addSeries("s", Color.RED, 20, 200, 2000);
        bar.setLogScale(true);
        bar.finishAnimation();
        float bottom = bar.plotY + bar.plotH;
        try (Bitmap bmp = draw(bar)) {
            for (int c = 0; c < 3; c++) {
                int x = Math.round(bar.categoryPos(c));
                double value = 20 * Math.pow(10, c);
                int top = Math.round(bar.valuePos(value));
                assertTrue(near(bmp.getColor(x, (int) bottom - 3), Color.RED, 6), "bar " + c + " is solid at the bottom");
                assertTrue(near(bmp.getColor(x, top + 4), Color.RED, 6), "and up to its value");
                assertFalse(near(bmp.getColor(x, top - 5), Color.RED, 30), "and no further");
            }
        }
    }

    @Test
    void aStackedBarChartStaysLinear() {
        BarChartOverlay bar = new BarChartOverlay(500, 300);
        bar.setLogScale(true);
        bar.setStacked(true);
        bar.addSeries("a", 40, 25);
        bar.addSeries("b", 30, 10);
        bar.finishAnimation();
        assertTrue(bar.getAxisRange()[0] <= 0, "the parts of a stack add, which a log axis cannot show");
        assertTrue(bar.isLogScale(), "the setting is kept for when the chart is unstacked");
        bar.setStacked(false);
        bar.finishAnimation();
        assertTrue(bar.getAxisRange()[0] > 0);
    }

    @Test
    void theValueAxisIsLinearOrLog() {
        LineChartOverlay line = new LineChartOverlay(400, 300);
        assertThrows(IllegalArgumentException.class, () -> line.setValueScale(AxisScale.TIME));
        line.setValueScale(AxisScale.LOG);
        assertEquals(AxisScale.LOG, line.getValueScale());
        line.setLogScale(false);
        assertEquals(AxisScale.LINEAR, line.getValueScale());
    }

    @Test
    void aLogLineChartPointsAtTheNearestSeries() {
        LineChartOverlay line = new LineChartOverlay(600, 300);
        line.setLogScale(true);
        line.setCategories("a", "b", "c");
        line.addSeries("small", 1, 2, 3);
        line.addSeries("big", 1000, 2000, 3000);
        line.setLegendPosition(LegendPosition.NONE);
        line.finishAnimation();
        List<ChartHit> hits = new ArrayList<>();
        line.onPointClick(hits::add);
        double x = line.categoryPos(1);
        double y = line.valuePos(2000);
        line.hoverAt(x, y);
        line.finishAnimation();
        click(line, x, y);
        assertEquals(1, hits.size());
        assertEquals("big", hits.getFirst().seriesName());
        assertEquals(2000, hits.getFirst().value());
    }

    // ---- time axis ----------------------------------------------------------------------------------------------

    @Test
    void aTimeAxisSpacesPointsByWhenTheyHappened() {
        LineChartOverlay line = new LineChartOverlay(600, 300);
        line.setDates(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 11));
        line.addSeries("a", 5, 6, 7, 8);
        line.finishAnimation();
        float day = line.categoryPos(1) - line.categoryPos(0);
        assertTrue(day > 5, "a day is apart: " + day);
        assertEquals(day * 5, line.categoryPos(2) - line.categoryPos(0), 0.05f);
        assertEquals(day * 10, line.categoryPos(3) - line.categoryPos(0), 0.05f);
        assertTrue(line.isTimeAxis());
        assertEquals(4, line.categoryCount());
    }

    @Test
    void theTimeAxisFitsTheFirstToTheLastMoment() {
        LineChartOverlay line = new LineChartOverlay(600, 300);
        long start = LocalDate.of(2026, 3, 1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli();
        line.setTimes(start, start + 3_600_000L, start + 7_200_000L);
        line.addSeries("a", 1, 2, 3);
        line.finishAnimation();
        double[] range = line.getTimeRange();
        assertEquals(start, range[0], 1);
        assertEquals(start + 7_200_000L, range[1], 1);

        line.setTimeRange(start - 3_600_000L, start + 10_800_000L);
        line.finishAnimation();
        assertEquals(start - 3_600_000L, line.getTimeRange()[0], 1, "a fixed window");
        line.clearTimeRange();
        line.finishAnimation();
        assertEquals(start, line.getTimeRange()[0], 1);
    }

    @Test
    void pointingAtATimeChartFindsTheNearestMomentAndNamesIt() {
        LineChartOverlay line = new LineChartOverlay(600, 300);
        line.setLegendPosition(LegendPosition.NONE);
        line.setDates(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 20));
        line.addSeries("a", 10, 20, 30);
        line.finishAnimation();
        List<ChartHit> hits = new ArrayList<>();
        line.onPointClick(hits::add);

        // nearer the second day than the third, though the third is the nearest by index
        double x = line.categoryPos(1) + 40, y = line.valuePos(20);
        line.hoverAt(x, y);
        line.finishAnimation();
        click(line, x, y);
        assertEquals(1, hits.size());
        assertEquals(1, hits.getFirst().category());
        assertEquals("2026-03-02", hits.getFirst().categoryLabel());
        assertEquals(20, hits.getFirst().value());
    }

    @Test
    void aTimeChartScrollsByDroppingTheOldestMoment() {
        LineChartOverlay line = new LineChartOverlay(600, 300);
        ChartSeries s = line.addSeries("a");
        long hour = 3_600_000L;
        for (int i = 0; i < 5; i++) {
            line.addTime(i * hour);
            s.add(i);
        }
        line.finishAnimation();
        assertEquals(5, line.getTimes().length);
        line.addTime(5 * hour);
        s.add(5);
        line.removeFirstTimes(1);
        s.removeFirst(1);
        line.finishAnimation();
        assertArrayEquals(new long[]{hour, 2 * hour, 3 * hour, 4 * hour, 5 * hour}, line.getTimes());
        assertEquals(hour, line.getTimeRange()[0], 1);
        assertEquals(0, line.categoryPos(0) - line.plotX, 0.01f, "the oldest moment is at the left edge");
    }

    @Test
    void valuesWithoutATimeAreLeftOut() {
        LineChartOverlay line = new LineChartOverlay(600, 300);
        line.setTimes(0L, 3_600_000L);
        line.addSeries("a", 1, 2, 3);
        line.finishAnimation();
        assertTrue(Float.isNaN(line.categoryPos(2)));
        try (Bitmap bmp = draw(line)) {
            assertNotNull(bmp);
        }
    }

    @Test
    void aTimeChartDrawsItsTicksUnderThePlot() {
        LineChartOverlay line = new LineChartOverlay(600, 300);
        line.setLegendPosition(LegendPosition.NONE);
        line.setDates(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        line.addSeries("a", 1, 2);
        line.finishAnimation();
        int below = (int) (line.plotY + line.plotH) + 5;
        int ink = 0;
        try (Bitmap bmp = draw(line)) {
            for (int y = below; y < below + 16; y++) {
                for (int x = 0; x < 600; x++) {
                    if (bmp.getColor(x, y) != 0xFFFFFFFF) ink++;
                }
            }
        }
        assertTrue(ink > 40, "labels are written there: " + ink);
    }

    @Test
    void onlyALineChartHasATimeAxis() {
        BarChartOverlay bar = new BarChartOverlay(400, 300);
        assertThrows(UnsupportedOperationException.class, () -> bar.setTimes(1L, 2L));
        LineChartOverlay line = new LineChartOverlay(400, 300);
        line.setTimes(1L, 2L);
        line.clearTimes();
        assertFalse(line.isTimeAxis());
    }

    @Test
    void aTimeFormatOfYourOwnNamesTheMoment() {
        LineChartOverlay line = new LineChartOverlay(600, 300);
        line.setDates(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2));
        line.setTimeFormat(java.time.format.DateTimeFormatter.ofPattern("'Day' d"));
        line.addSeries("a", 1, 2);
        line.finishAnimation();
        assertEquals("Day 2", line.categoryLabel(1));
    }

    // ---- scatter ------------------------------------------------------------------------------------------------

    private static ScatterChartOverlay scatter() {
        ScatterChartOverlay chart = new ScatterChartOverlay(600, 400);
        chart.setLegendPosition(LegendPosition.NONE);
        chart.setAnimated(false);
        return chart;
    }

    @Test
    void scatterPointsLandWhereTheirNumbersSay() {
        ScatterChartOverlay chart = scatter();
        ScatterSeries s = chart.addSeries("a", new double[]{0, 50, 100}, new double[]{0, 50, 100});
        s.setColor(Color.RED);
        chart.setXRange(0, 100);
        chart.setYRange(0, 100);
        chart.finishAnimation();
        float[] a = chart.pointPosition(0, 0), b = chart.pointPosition(0, 1), c = chart.pointPosition(0, 2);
        assertNotNull(a);
        assertEquals((a[0] + c[0]) / 2, b[0], 0.01f);
        assertEquals((a[1] + c[1]) / 2, b[1], 0.01f);
        assertTrue(c[0] > a[0] && c[1] < a[1], "x grows to the right and y grows upward");
        try (Bitmap bmp = draw(chart)) {
            assertTrue(near(bmp.getColor(Math.round(b[0]), Math.round(b[1])), Color.RED, 40), "the point is drawn in its color");
        }
    }

    @Test
    void bothScatterAxesFitTheirDataWithRoomAtTheEdges() {
        ScatterChartOverlay chart = scatter();
        chart.addSeries("a", new double[]{10, 20, 30}, new double[]{100, 400, 250});
        chart.finishAnimation();
        double[] x = chart.getXRange(), y = chart.getYRange();
        assertTrue(x[0] <= 10 && x[1] >= 30);
        assertTrue(y[0] <= 100 && y[1] >= 400);
        assertTrue(x[0] > -50 && x[1] < 60, "rounded, not stretched: " + x[0] + " to " + x[1]);
        float[] first = chart.pointPosition(0, 0);
        assertTrue(first[0] > 20, "the extreme point is not on the axis");
    }

    @Test
    void aHiddenScatterSeriesNoLongerStretchesTheAxes() {
        ScatterChartOverlay chart = scatter();
        chart.addSeries("small", new double[]{1, 2, 3}, new double[]{1, 2, 3});
        ScatterSeries big = chart.addSeries("big", new double[]{100, 200}, new double[]{100, 200});
        chart.finishAnimation();
        assertTrue(chart.getXRange()[1] >= 200);
        big.setVisible(false);
        chart.finishAnimation();
        assertTrue(chart.getXRange()[1] <= 5, "now only the small series counts: " + chart.getXRange()[1]);
    }

    @Test
    void logAxesOnAScatterPlotSpreadOrdersOfMagnitude() {
        ScatterChartOverlay chart = scatter();
        chart.setXScale(AxisScale.LOG);
        chart.setYScale(AxisScale.LOG);
        chart.addSeries("a", new double[]{1, 10, 100, 0, 1000}, new double[]{5, 50, 500, 5, -1});
        chart.finishAnimation();
        float[] p0 = chart.pointPosition(0, 0), p1 = chart.pointPosition(0, 1), p2 = chart.pointPosition(0, 2);
        assertEquals(p1[0] - p0[0], p2[0] - p1[0], 0.05f);
        assertEquals(p0[1] - p1[1], p1[1] - p2[1], 0.05f);
        assertNull(chart.pointPosition(0, 3), "x of zero has no place on a log axis");
        assertNull(chart.pointPosition(0, 4), "nor has a negative y");
        assertEquals(1, chart.getXRange()[0], 1e-9);
        assertEquals(100, chart.getXRange()[1], 1e-6, "the unplaceable points do not stretch the axes");
    }

    @Test
    void theYAxisOfAScatterPlotIsNotATimeAxis() {
        ScatterChartOverlay chart = scatter();
        assertThrows(IllegalArgumentException.class, () -> chart.setYScale(AxisScale.TIME));
    }

    @Test
    void aScatterPlotCanHaveATimeAxis() {
        ScatterChartOverlay chart = scatter();
        chart.setXScale(AxisScale.TIME);
        long day = 86_400_000L;
        chart.addSeries("a", new double[]{0, day, 3 * day}, new double[]{1, 5, 3});
        chart.setAxisTitles("When", "Count");
        chart.finishAnimation();
        float[] a = chart.pointPosition(0, 0), b = chart.pointPosition(0, 1), c = chart.pointPosition(0, 2);
        assertEquals((c[0] - a[0]) / 3, b[0] - a[0], 0.05f);
        List<ChartHit> hits = new ArrayList<>();
        chart.onPointClick(hits::add);
        chart.hoverAt(b[0], b[1]);
        chart.finishAnimation();
        click(chart, b[0], b[1]);
        assertEquals("1970-01-02T00:00:00", hits.getFirst().categoryLabel());
    }

    @Test
    void clickingAScatterPointReportsIt() {
        ScatterChartOverlay chart = scatter();
        chart.addSeries("left", new double[]{1, 2, 3}, new double[]{1, 2, 3});
        chart.addSeries("right", new double[]{1, 2, 3}, new double[]{3, 2, 1});
        chart.finishAnimation();
        List<ChartHit> hits = new ArrayList<>();
        chart.onPointClick(hits::add);

        float[] p = chart.pointPosition(1, 2); // right, the third point: (3, 1)
        chart.hoverAt(p[0] + 2, p[1] - 2);
        chart.finishAnimation();
        click(chart, p[0] + 2, p[1] - 2);
        assertEquals(1, hits.size());
        assertEquals(1, hits.getFirst().series());
        assertEquals(2, hits.getFirst().category());
        assertEquals("right", hits.getFirst().seriesName());
        assertEquals("3", hits.getFirst().categoryLabel(), "the x of the point");
        assertEquals(1, hits.getFirst().value());

        hits.clear();
        click(chart, 300, 40); // empty space
        assertTrue(hits.isEmpty());
    }

    @Test
    void theValueFormatOfAScatterChartIsForYAndXHasItsOwn() {
        ScatterChartOverlay chart = scatter();
        chart.setValueFormat(ChartFormat.currency("$"));
        chart.addSeries("a", new double[]{1200, 2400}, new double[]{5, 6});
        chart.finishAnimation();
        List<ChartHit> hits = new ArrayList<>();
        chart.onPointClick(hits::add);
        float[] p = chart.pointPosition(0, 0);
        chart.hoverAt(p[0], p[1]);
        chart.finishAnimation();
        click(chart, p[0], p[1]);
        assertEquals("1,200", hits.getFirst().categoryLabel(), "x is a plain number, without the dollar sign, unless it is given a format");

        chart.setXFormat(ChartFormat.unit(" m2"));
        chart.finishAnimation();
        hits.clear();
        click(chart, p[0], p[1]);
        assertEquals("1,200 m2", hits.getFirst().categoryLabel());
    }

    @Test
    void ofTwoOverlappingPointsTheNearerCentreIsPointedAt() {
        ScatterChartOverlay chart = scatter();
        chart.setMarkerRadius(12f);
        chart.addSeries("a", new double[]{5, 5.1}, new double[]{5, 5.1});
        chart.setXRange(0, 10);
        chart.setYRange(0, 10);
        chart.finishAnimation();
        float[] p1 = chart.pointPosition(0, 1);
        List<ChartHit> hits = new ArrayList<>();
        chart.onPointClick(hits::add);
        chart.hoverAt(p1[0], p1[1]);
        chart.finishAnimation();
        click(chart, p1[0], p1[1]);
        assertEquals(1, hits.getFirst().category());
    }

    @Test
    void bubblesGrowWithTheirSizeByArea() {
        ScatterChartOverlay chart = scatter();
        chart.setBubbleRadius(4f, 40f);
        chart.addSeries("a", new double[]{1, 2, 3}, new double[]{1, 2, 3}, new double[]{100, 25, 0});
        chart.finishAnimation();
        assertEquals(40f, chart.pointRadius(0, 0), 1e-4f, "the biggest takes the biggest radius");
        float quarter = chart.pointRadius(0, 1);
        assertEquals(4f + 36f * 0.5f, quarter, 1e-3f, "a quarter of the area is half the radius");
        assertEquals(4f, chart.pointRadius(0, 2), 1e-4f, "none is the smallest bubble");

        ScatterChartOverlay plain = scatter();
        plain.setMarkerRadius(7f);
        plain.addSeries("a", new double[]{1}, new double[]{1});
        plain.finishAnimation();
        assertEquals(7f, plain.pointRadius(0, 0), 1e-4f);
    }

    @Test
    void aBubbleIsDrawnAsBigAsItsRadius() {
        ScatterChartOverlay chart = scatter();
        chart.setBubbleRadius(4f, 40f);
        chart.setShowGrid(false);
        ScatterSeries s = chart.addSeries("a", new double[]{1, 9}, new double[]{5, 5}, new double[]{100, 1});
        s.setColor(Color.BLUE);
        chart.setXRange(0, 10);
        chart.setYRange(0, 10);
        chart.finishAnimation();
        float[] big = chart.pointPosition(0, 0), small = chart.pointPosition(0, 1);
        try (Bitmap bmp = draw(chart)) {
            int bx = Math.round(big[0]), by = Math.round(big[1]);
            assertTrue(near(bmp.getColor(bx + 30, by), Color.BLUE, 120), "inside the big bubble, 30 from its centre");
            assertFalse(near(bmp.getColor(bx + 46, by), Color.BLUE, 120), "and not beyond its edge");
            int sx = Math.round(small[0]), sy = Math.round(small[1]);
            assertFalse(near(bmp.getColor(sx + 15, sy), Color.BLUE, 120), "the small one is small");
        }
    }

    @Test
    void aScatterSeriesTakesPointsOneAtATime() {
        ScatterChartOverlay chart = scatter();
        ScatterSeries s = chart.addSeries("live");
        s.add(1, 10).add(2, 20).add(3, 30, 8);
        assertEquals(3, s.size());
        assertEquals(2, s.getX(1));
        assertEquals(20, s.getY(1));
        assertTrue(Double.isNaN(s.getSize(0)), "the earlier points had no size");
        assertEquals(8, s.getSize(2));
        assertTrue(s.hasSizes());

        s.set(1, 5, 50);
        assertEquals(5, s.getX(1));
        assertEquals(50, s.get(1));

        s.removeFirst(1);
        assertEquals(2, s.size());
        assertEquals(5, s.getX(0));
        assertEquals(3, s.getX(1));
        assertEquals(8, s.getSize(1));

        chart.finishAnimation();
        assertNotNull(chart.pointPosition(0, 0));
    }

    @Test
    void aScatterSeriesRefusesWhatHasNoXValue() {
        ScatterSeries s = new ScatterSeries("a", new double[]{1}, new double[]{1});
        assertThrows(UnsupportedOperationException.class, () -> s.add(5));
        assertThrows(UnsupportedOperationException.class, () -> s.setValues(1, 2));
        assertThrows(UnsupportedOperationException.class, () -> s.set(0, 3));
        assertThrows(IllegalArgumentException.class, () -> s.setPoints(new double[]{1, 2}, new double[]{1}));
        assertThrows(IllegalArgumentException.class, () -> s.setPoints(new double[]{1}, new double[]{1}, new double[]{1, 2}));
    }

    @Test
    void movingThePointsMovesTheAxes() {
        ScatterChartOverlay chart = scatter();
        ScatterSeries s = chart.addSeries("a", new double[]{1, 2}, new double[]{1, 2});
        chart.finishAnimation();
        assertTrue(chart.getXRange()[1] < 10);
        s.setPoints(new double[]{1000, 2000}, new double[]{1, 2});
        chart.finishAnimation();
        assertTrue(chart.getXRange()[1] >= 2000, "a change of x alone refits: " + chart.getXRange()[1]);
    }

    @Test
    void anEmptyScatterChartDrawsWithoutFailing() {
        ScatterChartOverlay chart = new ScatterChartOverlay(400, 300);
        chart.setTitle("nothing yet");
        chart.finishAnimation();
        try (Bitmap bmp = draw(chart)) {
            assertNotNull(bmp);
        }
        chart.addSeries("empty");
        chart.finishAnimation();
        chart.hoverAt(200, 150);
        chart.finishAnimation();
    }
}
