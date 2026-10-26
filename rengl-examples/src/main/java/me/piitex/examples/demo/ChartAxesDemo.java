package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.charts.AxisScale;
import me.piitex.engine.ui.charts.ChartFormat;
import me.piitex.engine.ui.charts.ChartOverlay;
import me.piitex.engine.ui.charts.LegendPosition;
import me.piitex.engine.ui.charts.LineChartOverlay;
import me.piitex.engine.ui.charts.ScatterChartOverlay;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The chart axes beyond a straight line of numbers:
 * <ul>
 *   <li><b>Top left</b>: money that grows by a quarter every month, from 100 to millions. On a linear axis the early months are
 *       a flat line on the floor; on a {@link AxisScale#LOG log axis} equal growth is a straight line, and the ticks are powers
 *       of ten. The debt series starts at zero, which a log axis cannot place, so it begins as a gap.</li>
 *   <li><b>Top right</b>: a line chart on a {@link AxisScale#TIME time axis}: a year and a half of daily visitors, with ticks on
 *       the 1st of a month or on a Monday, whichever fits. The buttons narrow the window with {@code setTimeRange}, and the
 *       check box moves the calendar from UTC to this machine's zone.</li>
 *   <li><b>Bottom left</b>: a scatter chart of company revenue against profit, with both axes switchable to log.</li>
 *   <li><b>Bottom right</b>: a bubble chart on a time axis: when in the day each week's commits were made, the size of the
 *       bubble the number of commits.</li>
 * </ul>
 * Point at a point for its numbers and click one to read it in the line under the title.
 */
public class ChartAxesDemo {
    private static final Color PANEL = new Color(0.14f, 0.14f, 0.16f, 1f);
    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final int DAYS = 540;

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Chart axes").setDimensions(1280, 860));

        Container root = new Container(1280, 860);
        window.addContainer(root);

        TextOverlay title = new TextOverlay("Log, time and scatter charts", 26f, Color.WHITE);
        title.setPosition(24, 14);
        root.addElement(title);
        TextOverlay status = new TextOverlay("Point at a chart for its numbers. Click a point to read it here.", 14f, Color.GRAY);
        status.setPosition(24, 52);
        root.addElement(status);

        LineChartOverlay growth = growthChart();
        growth.setPosition(24, 112);
        root.addElement(growth);

        LineChartOverlay visitors = visitorsChart();
        visitors.setPosition(652, 112);
        root.addElement(visitors);

        ScatterChartOverlay companies = companiesChart();
        companies.setPosition(24, 492);
        root.addElement(companies);

        ScatterChartOverlay commits = commitsChart();
        commits.setPosition(652, 492);
        root.addElement(commits);

        for (ChartOverlay chart : new ChartOverlay[]{growth, visitors, companies, commits}) {
            chart.onPointClick(hit -> status.setText(hit.seriesName() + ", point " + (hit.category() + 1)
                    + ": " + ChartFormat.plain(hit.value())));
        }

        // controls, each above the chart it changes
        addCheckBox(root, "Log scale", 24, 82, true, growth::setLogScale);

        ButtonOverlay month = button(root, "30 days", 652, 82, 84);
        ButtonOverlay quarter = button(root, "90 days", 744, 82, 84);
        ButtonOverlay all = button(root, "All", 836, 82, 60);
        long last = START.plusDays(DAYS - 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        month.onAction(() -> visitors.setTimeRange(last - 30L * 86_400_000L, last));
        quarter.onAction(() -> visitors.setTimeRange(last - 90L * 86_400_000L, last));
        all.onAction(visitors::clearTimeRange);
        addCheckBox(root, "Local time zone", 920, 82, false,
                local -> visitors.setTimeZone(local ? ZoneId.systemDefault() : ZoneOffset.UTC));

        addCheckBox(root, "Log X", 24, 460, true, log -> companies.setXScale(log ? AxisScale.LOG : AxisScale.LINEAR));
        addCheckBox(root, "Log Y", 120, 460, true, log -> companies.setYScale(log ? AxisScale.LOG : AxisScale.LINEAR));

        new Engine().start(window);
    }

    /** 100 growing by 25 % a month; the debt series starts at zero. */
    private static LineChartOverlay growthChart() {
        LineChartOverlay chart = new LineChartOverlay(604, 340);
        style(chart, "Savings and debt");
        int months = 48;
        String[] labels = new String[months];
        double[] savings = new double[months];
        double[] debt = new double[months];
        for (int i = 0; i < months; i++) {
            labels[i] = "M" + (i + 1);
            savings[i] = 100 * Math.pow(1.25, i);
            debt[i] = i == 0 ? 0 : 2_000 + 1_500 * i;
        }
        chart.setCategories(labels);
        chart.addSeries("Savings", savings);
        chart.addSeries("Debt", debt);
        chart.setShowMarkers(false);
        chart.setValueScale(AxisScale.LOG);
        chart.setValueFormat(ChartFormat.compact());
        chart.setLegendPosition(LegendPosition.TOP);
        chart.setCategoryAxisTitle("Month");
        return chart;
    }

    /** Daily visitors: a trend, a weekly beat and noise. */
    private static LineChartOverlay visitorsChart() {
        LineChartOverlay chart = new LineChartOverlay(604, 340);
        style(chart, "Visitors per day");
        Random random = new Random(7);
        LocalDate[] dates = new LocalDate[DAYS];
        double[] visitors = new double[DAYS];
        double[] signups = new double[DAYS];
        for (int i = 0; i < DAYS; i++) {
            dates[i] = START.plusDays(i);
            boolean weekend = dates[i].getDayOfWeek().getValue() >= 6;
            visitors[i] = 800 + i * 3.2 + (weekend ? -260 : 140) + random.nextGaussian() * 60;
            signups[i] = visitors[i] * (0.05 + 0.02 * Math.sin(i / 40.0)) + random.nextGaussian() * 4;
        }
        chart.setDates(dates);
        chart.addSeries("Visitors", visitors);
        chart.addSeries("Sign-ups", signups);
        chart.setShowMarkers(false);
        chart.setSmooth(false);
        chart.setLegendPosition(LegendPosition.TOP);
        chart.setValueFormat(ChartFormat.compact());
        return chart;
    }

    /** Revenue against profit for 60 companies in two sectors, spread over four orders of magnitude. */
    private static ScatterChartOverlay companiesChart() {
        ScatterChartOverlay chart = new ScatterChartOverlay(604, 340);
        style(chart, "Revenue against profit");
        chart.setAxisTitles("Revenue (millions)", "Profit (millions)");
        chart.setXScale(AxisScale.LOG);
        chart.setYScale(AxisScale.LOG);
        chart.setXFormat(ChartFormat.compact());
        chart.setValueFormat(ChartFormat.compact());
        chart.setLegendPosition(LegendPosition.TOP);
        Random random = new Random(3);
        for (String sector : new String[]{"Software", "Shipping"}) {
            boolean software = sector.equals("Software");
            double[] xs = new double[30];
            double[] ys = new double[30];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = Math.pow(10, 0.5 + random.nextDouble() * 3.5);
                ys[i] = xs[i] * (software ? 0.25 : 0.05) * Math.exp(random.nextGaussian() * 0.4);
            }
            chart.addSeries(sector, xs, ys);
        }
        return chart;
    }

    /** Commits by week, placed at the hour of the day they were mostly made; the bubble is how many there were. */
    private static ScatterChartOverlay commitsChart() {
        ScatterChartOverlay chart = new ScatterChartOverlay(604, 340);
        style(chart, "When the commits happen");
        chart.setXScale(AxisScale.TIME);
        chart.setAxisTitles("", "Hour of the day");
        chart.setYRange(0, 24);
        chart.setBubbleRadius(4f, 20f);
        chart.setLegendPosition(LegendPosition.TOP);
        Random random = new Random(11);
        for (String team : new String[]{"Engine", "Tools"}) {
            boolean engine = team.equals("Engine");
            int weeks = 52;
            double[] xs = new double[weeks];
            double[] ys = new double[weeks];
            double[] sizes = new double[weeks];
            for (int i = 0; i < weeks; i++) {
                xs[i] = START.plusWeeks(i).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                ys[i] = Math.max(1.5, Math.min(22.5, (engine ? 13 : 19) + random.nextGaussian() * 2.5));
                sizes[i] = 2 + random.nextInt(engine ? 40 : 18);
            }
            chart.addSeries(team, xs, ys, sizes);
        }
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

    private static ButtonOverlay button(Container root, String label, double x, double y, double width) {
        ButtonOverlay button = new ButtonOverlay(label, width, 28);
        button.setPosition(x, y);
        root.addElement(button);
        return button;
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
