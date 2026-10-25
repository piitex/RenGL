package me.piitex.engine.ui.charts;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.ToDoubleFunction;

/**
 * One axis of numbers: where its ends are, which round numbers get a tick, and where a value lands between the ends, 0 to 1.
 * It holds a range the data asks for and the one that is drawn, which eases toward it. Linear, logarithmic and time scales share
 * it, so a chart does not care which it is drawing. Plain arithmetic, tested without a window.
 * <p>
 * Positions are worked out in <em>axis space</em>: the value itself for a linear or time axis, its base-10 logarithm for a log
 * one. That is where the range eases, so a log axis slides by decades.
 */
final class NumericAxis {
    /**
     * @param value the number the tick stands at (real units, so 100 on a log axis)
     * @param major whether it is labelled; the others are only lines
     */
    record Tick(double value, boolean major) {
    }

    private AxisScale scale = AxisScale.LINEAR;
    private double fixedMin = Double.NaN, fixedMax = Double.NaN;
    private boolean includeZero;
    private ZoneId zone = ZoneOffset.UTC;
    private DateTimeFormatter timeFormat;

    private double targetMin = 0, targetMax = 1, step = 1;
    private int decimals;
    private double min = 0, max = 1;
    private boolean placed;
    private List<Tick> ticks = List.of();
    private DateTimeFormatter tickFormat;

    // ---- settings -----------------------------------------------------------------------------------------------

    /**
     * Sets what the axis is asked to be. Changing the scale makes the axis jump to its new range instead of easing there.
     *
     * @param fixedMin    the low end in real units, or NaN to fit the data
     * @param fixedMax    the high end, or NaN
     * @param includeZero whether a linear axis always reaches zero
     */
    void configure(AxisScale scale, double fixedMin, double fixedMax, boolean includeZero) {
        if (scale != this.scale) placed = false;
        this.scale = scale;
        this.fixedMin = fixedMin;
        this.fixedMax = fixedMax;
        this.includeZero = includeZero;
    }

    void setTime(ZoneId zone, DateTimeFormatter format) {
        this.zone = zone;
        this.timeFormat = format;
    }

    AxisScale scale() {
        return scale;
    }

    boolean isLog() {
        return scale == AxisScale.LOG;
    }

    List<Tick> ticks() {
        return ticks;
    }

    // ---- fitting ------------------------------------------------------------------------------------------------

    /**
     * Works out the range and the ticks for data from {@code low} to {@code high} (real units; NaN when there is none), with
     * at most about {@code maxTicks} ticks. {@code pad} widens a linear range by that share of itself on each side, so the
     * extreme points are not on the edge.
     */
    void fit(double low, double high, int maxTicks, double pad) {
        maxTicks = Math.max(2, maxTicks);
        switch (scale) {
            case LOG -> fitLog(low, high, maxTicks);
            case TIME -> fitTime(low, high, maxTicks);
            default -> fitLinear(low, high, maxTicks, pad);
        }
        if (!placed) {
            min = targetMin;
            max = targetMax;
            placed = true;
        }
    }

    private void fitLinear(double low, double high, int maxTicks, double pad) {
        if (Double.isNaN(low) || Double.isNaN(high)) {
            low = 0;
            high = 1;
        }
        if (includeZero) {
            low = Math.min(low, 0);
            high = Math.max(high, 0);
        } else if (pad > 0 && high > low) {
            double span = high - low;
            low -= span * pad;
            high += span * pad;
        }
        if (!Double.isNaN(fixedMin)) low = Math.min(low, fixedMin);
        if (!Double.isNaN(fixedMax)) high = Math.max(high, fixedMax);
        NiceScale nice = NiceScale.of(low, high, maxTicks);
        targetMin = Double.isNaN(fixedMin) ? nice.min() : fixedMin;
        targetMax = Double.isNaN(fixedMax) ? nice.max() : fixedMax;
        if (targetMax <= targetMin) targetMax = targetMin + nice.step();
        step = nice.step();
        decimals = nice.decimals();
        List<Tick> out = new ArrayList<>();
        long first = (long) Math.ceil(targetMin / step - 1e-9), last = (long) Math.floor(targetMax / step + 1e-9);
        for (long k = first; k <= last; k++) out.add(new Tick(k * step, true));
        ticks = out;
    }

    private void fitLog(double low, double high, int maxTicks) {
        if (!(low > 0) || !(high > 0) || !(low <= high)) {
            low = 1;
            high = 10;
        }
        boolean minFixed = fixedMin > 0, maxFixed = fixedMax > 0;
        if (minFixed) low = Math.min(low, fixedMin);
        if (maxFixed) high = Math.max(high, fixedMax);
        targetMin = minFixed ? Math.log10(fixedMin) : Math.floor(Math.log10(low) + 1e-9);
        targetMax = maxFixed ? Math.log10(fixedMax) : Math.ceil(Math.log10(high) - 1e-9);
        if (targetMax - targetMin < 1e-9) {
            // one value alone: put it in the middle of two decades
            targetMin = Math.log10(low) - 1;
            targetMax = Math.log10(low) + 1;
            if (minFixed || maxFixed) targetMax = Math.max(targetMax, targetMin + 1);
        }
        step = 1;
        decimals = 0;
        ticks = logTicks(maxTicks);
    }

    private List<Tick> logTicks(int maxTicks) {
        List<Tick> out = new ArrayList<>();
        double span = targetMax - targetMin;
        if (span >= 1 - 1e-9) {
            int firstE = (int) Math.ceil(targetMin - 1e-9), lastE = (int) Math.floor(targetMax + 1e-9);
            int decades = lastE - firstE + 1;
            int stride = Math.max(1, (int) Math.ceil((decades - 1.0) / (maxTicks - 1)));
            int start = Math.floorDiv(firstE + stride - 1, stride) * stride;
            if (stride == 1 && span <= 6) {
                for (int e = firstE - 1; e <= lastE; e++) {
                    for (int m = 2; m <= 9; m++) {
                        double v = m * Math.pow(10, e), at = Math.log10(v);
                        if (at > targetMin + 1e-9 && at < targetMax - 1e-9) out.add(new Tick(v, false));
                    }
                }
            }
            for (int e = start; e <= lastE; e += stride) out.add(new Tick(Math.pow(10, e), true));
        } else {
            // under one decade: label 1 to 9 times a power of ten, fewer if they would crowd
            for (int keep : new int[]{9, 3, 1}) {
                out.clear();
                for (int e = (int) Math.floor(targetMin) - 1; e <= (int) Math.ceil(targetMax) + 1; e++) {
                    for (int m = 1; m <= 9; m++) {
                        boolean wanted = keep == 9 || (keep == 3 && (m == 1 || m == 2 || m == 5)) || m == 1;
                        double v = m * Math.pow(10, e), at = Math.log10(v);
                        if (wanted && at >= targetMin - 1e-9 && at <= targetMax + 1e-9) out.add(new Tick(v, true));
                    }
                }
                if (out.size() <= maxTicks) break;
            }
        }
        out.sort((a, b) -> Double.compare(a.value(), b.value()));
        return out;
    }

    private void fitTime(double low, double high, int maxTicks) {
        if (Double.isNaN(low) || Double.isNaN(high)) {
            low = 0;
            high = 24 * 3_600_000.0;
        }
        if (!Double.isNaN(fixedMin)) low = fixedMin;
        if (!Double.isNaN(fixedMax)) high = fixedMax;
        if (high <= low) {
            low -= 3_600_000.0;
            high = low + 2 * 3_600_000.0;
        }
        targetMin = low;
        targetMax = high;
        TimeScale.Ticks t = TimeScale.of((long) Math.ceil(low), (long) Math.floor(high), zone, maxTicks);
        List<Tick> out = new ArrayList<>(t.at().length);
        for (long at : t.at()) out.add(new Tick(at, true));
        ticks = out;
        tickFormat = timeFormat != null ? timeFormat : t.format();
        step = 0;
    }

    /**
     * Fits a horizontal axis to {@code room} points of width: the fewest ticks that keep the labels apart. Returns the widest label.
     */
    float fitAcross(double low, double high, float room, ToDoubleFunction<String> measure, DoubleFunction<String> format, double pad) {
        int ticksFor = clampTicks(room / 90f);
        fit(low, high, ticksFor, pad);
        float widest = widest(measure, format);
        int refined = clampTicks(room / (widest + 22f));
        if (refined != ticksFor) {
            fit(low, high, refined, pad);
            widest = widest(measure, format);
        }
        return widest;
    }

    private static int clampTicks(float count) {
        return Math.clamp((int) count, 2, 12);
    }

    /** The widest label among the labelled ticks. */
    float widest(ToDoubleFunction<String> measure, DoubleFunction<String> format) {
        float widest = 0f;
        for (Tick tick : ticks) {
            if (tick.major()) widest = Math.max(widest, (float) measure.applyAsDouble(label(tick.value(), format)));
        }
        return widest;
    }

    // ---- easing and reading -------------------------------------------------------------------------------------

    /** Moves the drawn range toward the one the data asks for. */
    void ease(float k) {
        double epsilon = (targetMax - targetMin) * 1e-4;
        min += (targetMin - min) * k;
        max += (targetMax - max) * k;
        if (Math.abs(targetMin - min) <= epsilon) min = targetMin;
        if (Math.abs(targetMax - max) <= epsilon) max = targetMax;
    }

    /**
     * Where {@code value} falls between the drawn ends, 0 at the low end and 1 at the high end (outside that range if it is
     * outside the axis), or NaN if it cannot be placed: zero or less on a log axis.
     */
    double fraction(double value) {
        double at = value;
        if (scale == AxisScale.LOG) {
            if (!(value > 0)) return Double.NaN;
            at = Math.log10(value);
        }
        double span = max - min;
        if (span <= 0) span = 1;
        return (at - min) / span;
    }

    /** The drawn range in real units, as {min, max}. */
    double[] range() {
        return scale == AxisScale.LOG ? new double[]{Math.pow(10, min), Math.pow(10, max)} : new double[]{min, max};
    }

    /** The range the data asks for, in real units, as {min, max}. */
    double[] targetRange() {
        return scale == AxisScale.LOG ? new double[]{Math.pow(10, targetMin), Math.pow(10, targetMax)} : new double[]{targetMin, targetMax};
    }

    /** A distance in real units small enough that the data should stop easing and snap. */
    double epsilon() {
        double[] range = targetRange();
        return scale == AxisScale.LOG ? range[0] * 1e-4 : Math.max(range[1] - range[0], 1e-9) * 1e-4;
    }

    /** The label of a tick or a value: with {@code format} if given, else in the style the scale suits. */
    String label(double value, DoubleFunction<String> format) {
        if (scale == AxisScale.TIME) {
            ZoneId z = zone;
            DateTimeFormatter f = tickFormat != null ? tickFormat : timeFormat;
            return f == null ? String.valueOf((long) value) : TimeScale.format(f, (long) value, z);
        }
        if (format != null) return format.apply(value);
        return scale == AxisScale.LOG ? ChartFormat.plain(value) : ChartFormat.fixed(value, decimals);
    }
}
