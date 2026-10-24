package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.charts.BarChartOverlay;
import me.piitex.engine.ui.charts.ChartFormat;
import me.piitex.engine.ui.charts.ChartOverlay;
import me.piitex.engine.ui.charts.ChartSeries;
import me.piitex.engine.ui.charts.LegendPosition;
import me.piitex.engine.ui.charts.LineChartOverlay;
import me.piitex.engine.ui.charts.PieChartOverlay;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The charts on one dashboard:
 * <ul>
 *   <li><b>Top left</b>: a line chart fed live. A value arrives four times a second and the oldest leaves, so the
 *       chart scrolls; point at it for the values, click a legend entry to hide a line.</li>
 *   <li><b>Top right</b>: grouped bars that can be stacked or turned on their side. "Randomize" gives them new
 *       numbers, and they (and the axis) ease to them.</li>
 *   <li><b>Bottom left</b>: a donut. Point at a slice to lift it and see its share, click one to select it, and
 *       "Donut" turns it into a pie.</li>
 *   <li><b>Bottom right</b>: horizontal bars ranked by a percentage, with their values written at the ends.</li>
 * </ul>
 */
public class ChartDemo {
    private static final Random RANDOM = new Random();
    private static final Color PANEL = new Color(0.14f, 0.14f, 0.16f, 1f);

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Chart Demo").setDimensions(1280, 860));

        Container root = new Container(1280, 860);
        window.addContainer(root);

        TextOverlay title = new TextOverlay("Charts", 26f, Color.WHITE);
        title.setPosition(24, 14);
        root.addElement(title);
        TextOverlay status = new TextOverlay("Point at a chart. Click a legend entry to hide its series.", 14f, Color.GRAY);
        status.setPosition(24, 52);
        root.addElement(status);

        LineChartOverlay live = liveChart();
        live.setPosition(24, 84);
        root.addElement(live);

        BarChartOverlay sales = salesChart();
        sales.setPosition(652, 84);
        root.addElement(sales);

        PieChartOverlay donut = diskChart(status);
        donut.setPosition(24, 468);
        root.addElement(donut);

        BarChartOverlay ranking = rankingChart();
        ranking.setPosition(652, 468);
        root.addElement(ranking);

        // controls along the top
        addCheckBox(root, "Stacked bars", 640, 50, checked -> sales.setStacked(checked));
        addCheckBox(root, "Horizontal bars", 790, 50, checked -> sales.setHorizontal(checked));
        addCheckBox(root, "Donut", 960, 50, true, checked -> donut.setHoleRatio(checked ? 0.62f : 0f));
        ButtonOverlay randomize = new ButtonOverlay("Randomize", 100, 28);
        randomize.setPosition(1080, 50);
        randomize.onAction(() -> {
            for (ChartSeries s : sales.getSeries()) {
                double[] values = new double[sales.categoryCount()];
                for (int i = 0; i < values.length; i++) values[i] = 10 + RANDOM.nextInt(90);
                s.setValues(values);
            }
            for (int i = 0; i < donut.getSliceCount(); i++) donut.setSliceValue(i, 20 + RANDOM.nextInt(400));
        });
        root.addElement(randomize);

        new Engine().start(window);
    }

    private static LineChartOverlay liveChart() {
        LineChartOverlay chart = new LineChartOverlay(604, 360);
        style(chart, "Live signal");
        chart.setSmooth(true);
        chart.setFillArea(true);
        chart.setShowMarkers(false);
        chart.setValueRange(-2, 2);
        chart.setLegendPosition(LegendPosition.TOP);
        chart.setValueFormat(ChartFormat.decimals(1));
        ChartSeries wave = chart.addSeries("Wave");
        ChartSeries noisy = chart.addSeries("Noisy");

        int[] tick = {0};
        for (int i = 0; i < 40; i++) feed(chart, wave, noisy, tick);
        Scheduler.every(0.25f, () -> {
            feed(chart, wave, noisy, tick);
            chart.removeFirstCategories(1);
            wave.removeFirst(1);
            noisy.removeFirst(1);
        });
        return chart;
    }

    private static void feed(LineChartOverlay chart, ChartSeries wave, ChartSeries noisy, int[] tick) {
        double t = tick[0]++ * 0.18;
        chart.addCategory(String.valueOf(tick[0]));
        wave.add(Math.sin(t) * 1.4);
        noisy.add(Math.cos(t * 0.7) * 0.9 + (RANDOM.nextDouble() - 0.5) * 0.7);
    }

    private static BarChartOverlay salesChart() {
        BarChartOverlay chart = new BarChartOverlay(604, 360);
        style(chart, "Sales by quarter");
        chart.setCategories("Q1", "Q2", "Q3", "Q4");
        chart.addSeries("North", 42, 55, 61, 74);
        chart.addSeries("South", 31, 38, 52, 49);
        chart.addSeries("West", 24, 41, 35, 66);
        chart.setValueFormat(ChartFormat.currency("$"));
        chart.setValueAxisTitle("Thousands");
        return chart;
    }

    private static PieChartOverlay diskChart(TextOverlay status) {
        PieChartOverlay chart = new PieChartOverlay(604, 360);
        style(chart, "Disk usage");
        chart.addSlice("Video", 420);
        chart.addSlice("Photos", 180);
        chart.addSlice("Music", 90);
        chart.addSlice("Documents", 60);
        chart.addSlice("Other", 35);
        chart.setHoleRatio(0.62f);
        chart.setValueFormat(value -> ChartFormat.plain(value) + " GB");
        chart.onPointClick(hit -> status.setText("Clicked " + hit.categoryLabel() + ": " + ChartFormat.plain(hit.value()) + " GB"));
        return chart;
    }

    private static BarChartOverlay rankingChart() {
        BarChartOverlay chart = new BarChartOverlay(604, 360);
        style(chart, "Language share");
        chart.setHorizontal(true);
        chart.setShowValues(true);
        chart.setLegendPosition(LegendPosition.NONE);
        chart.setCategories(List.of("Java", "Python", "C++", "Rust", "Go", "Kotlin"));
        chart.addSeries("Share", 27.5, 24.1, 12.3, 9.6, 7.2, 4.8);
        chart.setValueRange(0, 30);
        chart.setValueFormat(ChartFormat.percent());
        return chart;
    }

    /** The look all four share: a rounded dark panel with room around the plot. */
    private static void style(ChartOverlay chart, String title) {
        chart.setTitle(title);
        chart.setPadding(18f);
        chart.getStyling().setBackgroundColor(PANEL);
        chart.getStyling().setHoverColor(PANEL);
        chart.getStyling().setCornerRadius(12f);
    }

    private static void addCheckBox(Container root, String label, double x, double y, Consumer<Boolean> listener) {
        addCheckBox(root, label, x, y, false, listener);
    }

    private static void addCheckBox(Container root, String label, double x, double y, boolean checked, Consumer<Boolean> listener) {
        CheckBoxOverlay box = new CheckBoxOverlay(20, checked);
        box.setPosition(x, y + 4);
        box.onToggle(listener);
        root.addElement(box);
        TextOverlay text = new TextOverlay(label, 14f, Color.WHITE);
        text.setPosition(x + 28, y + 5);
        root.addElement(text);
    }
}
