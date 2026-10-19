package me.piitex.engine.ui.charts;

/**
 * An axis range with round numbers at its ends and an even, round step between its ticks: 0 to 50 in tens rather than
 * 3.2 to 47.9 in 4.4s. The "nice numbers" method: the step is 1, 2, 5 or 10 times a power of ten.
 *
 * @param min  the first tick, at or below the data's lowest value
 * @param max  the last tick, at or above its highest
 * @param step the distance between ticks
 */
record NiceScale(double min, double max, double step) {

    /**
     * A scale covering {@code lo} to {@code hi} with at most about {@code maxTicks} ticks. Equal ends (one value, or
     * all the same) open up to a sensible range instead of dividing by zero.
     */
    static NiceScale of(double lo, double hi, int maxTicks) {
        if (!(lo <= hi)) { // NaN or reversed
            lo = 0;
            hi = 1;
        }
        if (lo == hi) {
            double pad = lo == 0 ? 1 : Math.abs(lo) * 0.1;
            lo -= pad;
            hi += pad;
        }
        maxTicks = Math.max(2, maxTicks);
        double range = niceNumber(hi - lo, false);
        double step = niceNumber(range / (maxTicks - 1), true);
        double min = Math.floor(lo / step + 1e-9) * step;
        double max = Math.ceil(hi / step - 1e-9) * step;
        if (max <= min) max = min + step;
        return new NiceScale(min, max, step);
    }

    /**
     * How many decimals the ticks need: none for a step of 5, one for 0.5, two for 0.25.
     */
    int decimals() {
        int decimals = 0;
        double scaled = step;
        while (decimals < 8 && Math.abs(scaled - Math.rint(scaled)) > 1e-9 * Math.max(1, Math.abs(scaled))) {
            scaled *= 10;
            decimals++;
        }
        return decimals;
    }

    private static double niceNumber(double range, boolean round) {
        double exponent = Math.floor(Math.log10(range));
        double fraction = range / Math.pow(10, exponent);
        double nice;
        if (round) {
            nice = fraction < 1.5 ? 1 : fraction < 3 ? 2 : fraction < 7 ? 5 : 10;
        } else {
            nice = fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10;
        }
        return nice * Math.pow(10, exponent);
    }
}
