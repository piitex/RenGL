package me.piitex.engine.ui.charts;

/**
 * A point of a chart that was clicked: which series and category it belongs to and its value. For a pie chart the
 * category is the slice and the series is always 0.
 *
 * @param series        the index of the series, in the order they were added
 * @param category      the index of the category: the x position of a line, the group of a bar, the slice of a pie
 * @param seriesName    the series' name
 * @param categoryLabel the category's label, or its number counting from 1 when the chart has none
 * @param value         the value there
 */
public record ChartHit(int series, int category, String seriesName, String categoryLabel, double value) {
}
