package me.piitex.engine.ui.charts;

import java.util.Locale;
import java.util.function.DoubleFunction;

/**
 * Ready-made number formatters for {@link ChartOverlay#setValueFormat}: axis labels, tooltips and data labels all use
 * the one the chart is given. All of them write with a point for the decimal mark and commas between thousands, so a
 * chart reads the same on every machine.
 */
public final class ChartFormat {
    private ChartFormat() {
    }

    /**
     * Whole numbers as they are, others to up to four decimals with trailing zeros dropped: {@code 1,234}, {@code 0.25}.
     * This is what a chart uses until it is given another.
     */
    public static String plain(double value) {
        if (Double.isNaN(value)) return "";
        if (Math.abs(value - Math.rint(value)) < 1e-9 && Math.abs(value) < 1e15) return fixed(value, 0);
        String text = fixed(value, 4);
        int end = text.length();
        while (text.charAt(end - 1) == '0') end--;
        if (text.charAt(end - 1) == '.') end--;
        return text.substring(0, end);
    }

    /**
     * Exactly {@code decimals} decimals: {@code decimals(1)} gives {@code 12.5} and {@code 3.0}.
     */
    public static DoubleFunction<String> decimals(int decimals) {
        return value -> Double.isNaN(value) ? "" : fixed(value, Math.max(0, decimals));
    }

    /**
     * Thousands, millions and billions shortened: {@code 950}, {@code 1.2K}, {@code 3.4M}, {@code 5B}.
     */
    public static DoubleFunction<String> compact() {
        return value -> {
            if (Double.isNaN(value)) return "";
            double abs = Math.abs(value);
            if (abs < 1000) return plain(value);
            String suffix;
            double scaled;
            if (abs < 1e6) {
                suffix = "K";
                scaled = value / 1e3;
            } else if (abs < 1e9) {
                suffix = "M";
                scaled = value / 1e6;
            } else {
                suffix = "B";
                scaled = value / 1e9;
            }
            String text = fixed(scaled, 1);
            if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
            return text + suffix;
        };
    }

    /**
     * The value followed by a percent sign: 12.5 reads {@code 12.5%}. The value is already a percent, not a fraction.
     */
    public static DoubleFunction<String> percent() {
        return value -> Double.isNaN(value) ? "" : plain(value) + "%";
    }

    /**
     * A symbol in front: {@code currency("$")} gives {@code $1,200}, and a negative {@code -$40}.
     */
    public static DoubleFunction<String> currency(String symbol) {
        return value -> {
            if (Double.isNaN(value)) return "";
            String text = plain(Math.abs(value));
            return (value < 0 ? "-" : "") + symbol + text;
        };
    }

    /**
     * A unit after: {@code unit(" ms")} gives {@code 12 ms}.
     */
    public static DoubleFunction<String> unit(String unit) {
        return value -> Double.isNaN(value) ? "" : plain(value) + unit;
    }

    static String fixed(double value, int decimals) {
        return String.format(Locale.ROOT, "%,." + decimals + "f", value);
    }
}
