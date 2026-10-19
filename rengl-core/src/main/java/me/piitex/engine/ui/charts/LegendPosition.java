package me.piitex.engine.ui.charts;

/**
 * Where a chart puts its legend: the row (or column) of colored swatches and names that also switches a series on and off.
 */
public enum LegendPosition {
    /** No legend. */
    NONE,
    /** A row above the plot, under the title. Wraps onto more rows when it is too wide. */
    TOP,
    /** A row below the plot. The default. */
    BOTTOM,
    /** A column to the right of the plot. */
    RIGHT
}
