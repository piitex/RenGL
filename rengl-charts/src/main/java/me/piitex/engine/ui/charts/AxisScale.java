package me.piitex.engine.ui.charts;

/**
 * How numbers are spread along a chart axis.
 */
public enum AxisScale {
    /**
     * Equal steps for equal differences. The default.
     */
    LINEAR,
    /**
     * Equal steps for equal ratios, so 1, 10, 100 and 1,000 are evenly spaced. For data that spans several orders of
     * magnitude, such as a balance that grows from a few hundred to millions. Zero and negative values cannot be placed on
     * it and are left out like gaps.
     */
    LOG,
    /**
     * Values are moments, in milliseconds since 1970-01-01T00:00Z, and the ticks fall on seconds, minutes, hours, days,
     * months or years, whichever suits the range.
     */
    TIME
}
