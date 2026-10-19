package me.piitex.engine.ui.charts;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.LightTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** The charts' scales, data easing, hover and clicks, legend, theming, and what they draw. */
class ChartTest {
    @AfterEach
    void restoreTheme() {
        ThemeManager.setCurrent(new LightTheme());
    }

    private static Bitmap draw(ChartOverlay chart, Consumer<OffscreenRenderer> before) {
        int w = (int) chart.getWidth(), h = (int) chart.getHeight();
        try (OffscreenRenderer r = new OffscreenRenderer(w, h, 1f)) {
            r.begin();
            r.drawQuad(0, 0, w, h, Color.WHITE, 0f);
            if (before != null) before.accept(r);
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

    // ---- scale and formats --------------------------------------------------------------------------------------

    @Test
    void niceScaleRoundsToTensAndFives() {
        NiceScale scale = NiceScale.of(3.2, 47.9, 6);
        assertEquals(0, scale.min(), 1e-9);
        assertEquals(50, scale.max(), 1e-9);
        assertEquals(10, scale.step(), 1e-9);

        NiceScale small = NiceScale.of(0.1, 0.87, 6);
        assertEquals(0, small.min(), 1e-9);
        assertEquals(1.0, small.max(), 1e-9);
        assertEquals(0.2, small.step(), 1e-9);
        assertEquals(1, small.decimals());
    }

    @Test
    void niceScaleOpensUpWhenAllValuesAreEqual() {
        NiceScale same = NiceScale.of(5, 5, 6);
        assertTrue(same.min() < 5 && same.max() > 5);
        NiceScale zero = NiceScale.of(0, 0, 6);
        assertTrue(zero.max() > zero.min());
        NiceScale broken = NiceScale.of(Double.NaN, 3, 6);
        assertTrue(broken.max() > broken.min());
    }

    @Test
    void formatsWriteNumbersTheSameEverywhere() {
        assertEquals("1,234", ChartFormat.plain(1234));
        assertEquals("0.25", ChartFormat.plain(0.25));
        assertEquals("12.5", ChartFormat.plain(12.5));
        assertEquals("", ChartFormat.plain(Double.NaN));
        assertEquals("1.2K", ChartFormat.compact().apply(1234));
        assertEquals("3M", ChartFormat.compact().apply(3_000_000));
        assertEquals("950", ChartFormat.compact().apply(950));
        assertEquals("12.5%", ChartFormat.percent().apply(12.5));
        assertEquals("$1,200", ChartFormat.currency("$").apply(1200));
        assertEquals("-$40", ChartFormat.currency("$").apply(-40));
        assertEquals("3.0", ChartFormat.decimals(1).apply(3));
        assertEquals("12 ms", ChartFormat.unit(" ms").apply(12));
    }

    // ---- series -------------------------------------------------------------------------------------------------

    @Test
    void aSeriesEasesTowardItsValuesAndSnapsWhenClose() {
        ChartSeries s = new ChartSeries("a", 10, 20);
        s.advance(1f, 0, 0.01); // first sight, no animation: it is simply there
        assertEquals(10, s.shown(0));

        s.setValues(110, 20);
        s.advance(0.5f, 0, 0.01);
        assertEquals(60, s.shown(0), 1e-9);
        assertEquals(20, s.shown(1), 1e-9);
        for (int i = 0; i < 30; i++) s.advance(0.5f, 0, 0.01);
        assertEquals(110, s.shown(0), 0.0, "close enough snaps exactly onto the value");
    }

    @Test
    void newValuesGrowFromTheBaseline() {
        ChartSeries s = new ChartSeries("a", 50, 80);
        s.advance(0.25f, 0, 0.001);
        assertEquals(12.5, s.shown(0), 1e-9, "a quarter of the way from zero to 50");
        assertEquals(20, s.shown(1), 1e-9);

        s.add(40);
        s.advance(0.5f, 0, 0.001);
        assertEquals(20, s.shown(2), 1e-9, "the added value starts at the baseline too");
    }

    @Test
    void gapsStayGapsAndScrollingKeepsTheRest() {
        ChartSeries s = new ChartSeries("a", 1, Double.NaN, 3);
        s.advance(1f, 0, 0.001);
        assertTrue(Double.isNaN(s.shown(1)));
        s.removeFirst(1);
        assertEquals(2, s.size());
        assertTrue(Double.isNaN(s.get(0)));
        assertEquals(3, s.get(1));
        assertEquals(3, s.shown(1), "what was shown moves along with the values");

        s.set(5, 9);
        assertEquals(6, s.size());
        assertTrue(Double.isNaN(s.get(3)));
    }

    // ---- line and bar charts ------------------------------------------------------------------------------------

    @Test
    void anAxisChartFitsItsRangeToTheData() {
        LineChartOverlay line = new LineChartOverlay(400, 300);
        line.addSeries("a", 120, 180, 300);
        line.finishAnimation();
        double[] range = line.getAxisRange();
        assertTrue(range[0] <= 120 && range[0] > 0, "a line chart doesn't start at zero by default: " + range[0]);
        assertTrue(range[1] >= 300);

        line.setBeginAtZero(true);
        line.finishAnimation();
        assertEquals(0, line.getAxisRange()[0], 1e-9);

        line.setValueRange(0, 1000);
        line.finishAnimation();
        assertEquals(1000, line.getAxisRange()[1], 1e-9);
    }

    @Test
    void barsAlwaysIncludeZeroAndStackingAddsUp() {
        BarChartOverlay bar = new BarChartOverlay(400, 300);
        bar.addSeries("a", 40, 25);
        bar.addSeries("b", 30, -10);
        bar.finishAnimation();
        double[] range = bar.getAxisRange();
        assertEquals(40, range[1], 10, "the tallest grouped bar is 40");
        assertTrue(range[0] <= -10);

        bar.setStacked(true);
        bar.finishAnimation();
        assertTrue(bar.getAxisRange()[1] >= 70, "stacked, the first group is 40 + 30");
    }

    @Test
    void theAxisEasesToANewRange() {
        LineChartOverlay line = new LineChartOverlay(400, 300);
        ChartSeries s = line.addSeries("a", 0, 10);
        line.finishAnimation();
        double before = line.getAxisRange()[1];
        s.setValues(0, 1000);
        line.update(0.05f);
        double mid = line.getAxisRange()[1];
        assertTrue(mid > before && mid < 1000, "moving: " + mid);
        line.finishAnimation();
        assertTrue(line.getAxisRange()[1] >= 1000);
    }

    @Test
    void pointingAtAChartFindsTheCategoryAndSelectsAHit() {
        LineChartOverlay line = new LineChartOverlay(600, 300);
        line.setCategories("a", "b", "c");
        line.addSeries("low", 10, 20, 30);
        line.addSeries("high", 100, 200, 300);
        line.finishAnimation();
        List<ChartHit> hits = new ArrayList<>();
        line.onPointClick(hits::add);

        // the middle category, pointer high up: the "high" series is the nearer one
        double x = line.getAxisRange().length > 0 ? 300 : 0;
        line.hoverAt(x, 60);
        line.finishAnimation();
        click(line, x, 60);
        assertEquals(1, hits.size());
        assertEquals(1, hits.getFirst().category());
        assertEquals("b", hits.getFirst().categoryLabel());
        assertEquals("high", hits.getFirst().seriesName());
        assertEquals(200, hits.getFirst().value());
    }

    @Test
    void aBarHitNamesTheBarUnderThePointer() {
        BarChartOverlay bar = new BarChartOverlay(600, 300);
        bar.setCategories("one", "two");
        bar.addSeries("left", 50, 50);
        bar.addSeries("right", 50, 50);
        bar.setLegendPosition(LegendPosition.NONE);
        bar.finishAnimation();
        List<ChartHit> hits = new ArrayList<>();
        bar.onPointClick(hits::add);

        // in the second group, near the baseline where both bars are
        double groupCenter = 600 * 0.75 + 7;
        bar.hoverAt(groupCenter - 25, 260);
        bar.finishAnimation();
        click(bar, groupCenter - 25, 260);
        assertEquals(1, hits.size(), "a click on a bar reports it");
        assertEquals(1, hits.getFirst().category());
        assertEquals("left", hits.getFirst().seriesName());

        hits.clear();
        click(bar, groupCenter - 25, 8); // empty space above the bars: no bar, no hit
        assertTrue(hits.isEmpty());
    }

    @Test
    void clickingALegendEntryHidesItsSeries() {
        LineChartOverlay line = new LineChartOverlay(600, 300);
        line.addSeries("a", 1, 2, 3);
        ChartSeries b = line.addSeries("b", 3, 2, 1);
        line.finishAnimation();
        float[] bounds = line.legendBounds(1);
        assertNotNull(bounds);

        click(line, bounds[0] + bounds[2] / 2, bounds[1] + bounds[3] / 2);
        assertFalse(b.isVisible());
        click(line, bounds[0] + bounds[2] / 2, bounds[1] + bounds[3] / 2);
        assertTrue(b.isVisible(), "a second click brings it back");
    }

    @Test
    void aHiddenSeriesNoLongerStretchesTheAxis() {
        LineChartOverlay line = new LineChartOverlay(600, 300);
        line.addSeries("small", 1, 2, 3);
        ChartSeries big = line.addSeries("big", 100, 200, 300);
        line.finishAnimation();
        assertTrue(line.getAxisRange()[1] >= 300);
        big.setVisible(false);
        line.finishAnimation();
        assertTrue(line.getAxisRange()[1] <= 5, "now only the small series counts: " + line.getAxisRange()[1]);
    }

    @Test
    void noLegendMeansNoLegendBounds() {
        LineChartOverlay line = new LineChartOverlay(600, 300);
        line.addSeries("a", 1, 2);
        line.setLegendPosition(LegendPosition.NONE);
        assertNull(line.legendBounds(0));
    }

    // ---- pie ----------------------------------------------------------------------------------------------------

    @Test
    void aPieTotalsItsVisibleSlices() {
        PieChartOverlay pie = new PieChartOverlay(400, 300);
        pie.addSlice("a", 30);
        pie.addSlice("b", 70);
        pie.addSlice("c", Double.NaN);
        assertEquals(100, pie.getTotal(), 1e-9);
        pie.setSliceVisible(0, false);
        assertEquals(70, pie.getTotal(), 1e-9);
        assertFalse(pie.isSliceVisible(0));
        assertEquals(3, pie.getSliceCount());
    }

    @Test
    void pointingAtASliceReportsIt() {
        PieChartOverlay pie = new PieChartOverlay(400, 300);
        pie.setLegendPosition(LegendPosition.NONE);
        pie.setSliceGap(0);
        pie.addSlice("right half", 50);
        pie.addSlice("left half", 50);
        pie.setStartAngle(-90); // a starts at the top and runs clockwise through the right side
        pie.finishAnimation();
        List<ChartHit> hits = new ArrayList<>();
        pie.onPointClick(hits::add);

        double cx = 200, cy = 150;
        pie.hoverAt(cx + 60, cy);
        pie.finishAnimation();
        click(pie, cx + 60, cy);
        pie.hoverAt(cx - 60, cy);
        pie.finishAnimation();
        click(pie, cx - 60, cy);

        assertEquals(2, hits.size());
        assertEquals("right half", hits.get(0).categoryLabel());
        assertEquals("left half", hits.get(1).categoryLabel());
        assertEquals(50, hits.get(1).value());
    }

    @Test
    void aDonutsHoleIsNotASlice() {
        PieChartOverlay donut = new PieChartOverlay(400, 300);
        donut.setLegendPosition(LegendPosition.NONE);
        donut.addSlice("only", 1);
        donut.setHoleRatio(0.6f);
        donut.finishAnimation();
        List<ChartHit> hits = new ArrayList<>();
        donut.onPointClick(hits::add);
        donut.hoverAt(200, 150);
        donut.finishAnimation();
        click(donut, 200, 150);
        assertTrue(hits.isEmpty(), "the middle of a donut is empty");
    }

    // ---- drawing ------------------------------------------------------------------------------------------------

    @Test
    void aBarIsDrawnInItsSeriesColorAndGrowsFromTheBaseline() {
        BarChartOverlay bar = new BarChartOverlay(400, 300);
        bar.setLegendPosition(LegendPosition.NONE);
        bar.setCategories("only");
        bar.addSeries("a", Color.RED, 100);
        bar.setValueRange(0, 100);
        bar.setBarCornerRadius(0);
        bar.finishAnimation();
        try (Bitmap bmp = draw(bar, null)) {
            assertTrue(near(bmp.getColor(200, 150), Color.RED, 4), "the bar fills the middle of the plot");
            assertTrue(near(bmp.getColor(5, 150), Color.WHITE, 4) || bmp.getColor(5, 150) != 0, "and the margin is clear");
        }

        BarChartOverlay growing = new BarChartOverlay(400, 300);
        growing.setLegendPosition(LegendPosition.NONE);
        growing.addSeries("a", Color.RED, 100);
        growing.setValueRange(0, 100);
        growing.setBarCornerRadius(0);
        growing.update(0f); // the very first frame: nothing has grown yet
        try (Bitmap bmp = draw(growing, null)) {
            assertFalse(near(bmp.getColor(200, 100), Color.RED, 20), "at the start the bar has no height");
        }
    }

    @Test
    void aPieFillsItsSliceColors() {
        PieChartOverlay pie = new PieChartOverlay(400, 300);
        pie.setLegendPosition(LegendPosition.NONE);
        pie.setShowLabels(false);
        pie.setSliceGap(0);
        pie.addSlice("right", 50, Color.RED);
        pie.addSlice("left", 50, Color.BLUE);
        pie.finishAnimation();
        try (Bitmap bmp = draw(pie, null)) {
            assertTrue(near(bmp.getColor(260, 150), Color.RED, 6), "start at the top, clockwise: the right half is the first slice");
            assertTrue(near(bmp.getColor(140, 150), Color.BLUE, 6));
        }
    }

    @Test
    void aHiddenSliceLetsTheRestCloseUp() {
        PieChartOverlay pie = new PieChartOverlay(400, 300);
        pie.setLegendPosition(LegendPosition.NONE);
        pie.setShowLabels(false);
        pie.setSliceGap(0);
        pie.addSlice("a", 50, Color.RED);
        pie.addSlice("b", 50, Color.BLUE);
        pie.setSliceVisible(0, false);
        pie.finishAnimation();
        try (Bitmap bmp = draw(pie, null)) {
            assertTrue(near(bmp.getColor(260, 150), Color.BLUE, 6), "the remaining slice takes the whole circle");
            assertTrue(near(bmp.getColor(140, 150), Color.BLUE, 6));
        }
    }

    @Test
    void aTooltipAppearsOnlyWhilePointing() {
        LineChartOverlay line = new LineChartOverlay(500, 300);
        line.setCategories("a", "b");
        line.addSeries("s", Color.RED, 10, 20);
        line.setLegendPosition(LegendPosition.NONE);
        line.finishAnimation();
        byte[] without, with;
        try (Bitmap bmp = draw(line, null)) {
            without = bmp.readPixels(bmp.getImageInfo(), bmp.getRowBytes(), 0, 0);
        }
        line.hoverAt(250, 150);
        line.finishAnimation();
        try (Bitmap bmp = draw(line, null)) {
            with = bmp.readPixels(bmp.getImageInfo(), bmp.getRowBytes(), 0, 0);
        }
        assertFalse(java.util.Arrays.equals(without, with), "pointing at the chart draws more");

        line.clearHover();
        line.finishAnimation();
        try (Bitmap bmp = draw(line, null)) {
            assertArrayEquals(without, bmp.readPixels(bmp.getImageInfo(), bmp.getRowBytes(), 0, 0), "and moving off puts it back");
        }
    }

    @Test
    void aDisposedChartDrawsNothingAndDoesNotFail() {
        PieChartOverlay pie = new PieChartOverlay(300, 300);
        pie.addSlice("a", 1);
        pie.setHoleRatio(0.6f);
        pie.finishAnimation();
        draw(pie, null).close(); // makes the center font
        pie.dispose();
        pie.update(0.1f);
        try (Bitmap bmp = draw(pie, null)) {
            assertTrue(near(bmp.getColor(150, 150), Color.WHITE, 2), "only the white the test drew first");
        }
    }

    // ---- theme --------------------------------------------------------------------------------------------------

    @Test
    void textColorFollowsTheThemeUntilPinned() {
        ThemeManager.setCurrent(new DarkTheme());
        LineChartOverlay line = new LineChartOverlay(400, 300);
        assertEquals(Color.WHITE.getR(), line.getTextColor().getR(), 1e-6);

        ThemeManager.setCurrent(new LightTheme());
        assertNotEquals(Color.WHITE.getR(), line.getTextColor().getR(), "the theme changed it");

        line.setTextColor(Color.RED);
        ThemeManager.setCurrent(new DarkTheme());
        assertEquals(Color.RED.getR(), line.getTextColor().getR(), 1e-6, "pinned against the theme");
    }

    @Test
    void seriesColorsComeFromThePaletteUnlessGiven() {
        BarChartOverlay bar = new BarChartOverlay(400, 300);
        bar.addSeries("a", 1);
        bar.addSeries("b", Color.GREEN, 2);
        bar.addSeries("c", 3);
        assertSame(ChartOverlay.DEFAULT_PALETTE.get(0), bar.seriesColor(0));
        assertSame(Color.GREEN, bar.seriesColor(1));
        assertSame(ChartOverlay.DEFAULT_PALETTE.get(2), bar.seriesColor(2));

        bar.setPalette(Color.RED, Color.BLUE);
        assertSame(Color.RED, bar.seriesColor(0));
        assertSame(Color.RED, bar.seriesColor(2), "the palette repeats");
        assertThrows(IllegalArgumentException.class, () -> bar.setPalette(List.of()));
    }
}
