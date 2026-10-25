package me.piitex.engine.ui.charts;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Round moments for the ticks of a time axis: every 15 minutes, every 6 hours, the first of each month. A tick falls where a
 * person would expect it (on the hour, at midnight, on a Monday, on the 1st), by the calendar of a time zone.
 */
final class TimeScale {
    private TimeScale() {
    }

    /**
     * @param at     the ticks, in milliseconds since the epoch, ascending
     * @param format how to write one
     */
    record Ticks(long[] at, DateTimeFormatter format) {
    }

    private record Step(ChronoUnit unit, int count, double approxMillis) {
    }

    private static final double SECOND = 1000, MINUTE = 60 * SECOND, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
    private static final List<Step> STEPS = steps();

    private static List<Step> steps() {
        List<Step> out = new ArrayList<>();
        for (int n : new int[]{1, 2, 5, 10, 15, 30}) out.add(new Step(ChronoUnit.SECONDS, n, n * SECOND));
        for (int n : new int[]{1, 2, 5, 10, 15, 30}) out.add(new Step(ChronoUnit.MINUTES, n, n * MINUTE));
        for (int n : new int[]{1, 2, 3, 6, 12}) out.add(new Step(ChronoUnit.HOURS, n, n * HOUR));
        out.add(new Step(ChronoUnit.DAYS, 1, DAY));
        out.add(new Step(ChronoUnit.DAYS, 2, 2 * DAY));
        out.add(new Step(ChronoUnit.WEEKS, 1, 7 * DAY));
        for (int n : new int[]{1, 2, 3, 6}) out.add(new Step(ChronoUnit.MONTHS, n, n * 30.44 * DAY));
        for (long power = 1; power <= 1_000_000L; power *= 10) {
            for (int m : new int[]{1, 2, 5}) {
                long years = power * m;
                if (years <= 5_000_000L) out.add(new Step(ChronoUnit.YEARS, (int) years, years * 365.25 * DAY));
            }
        }
        return List.copyOf(out);
    }

    private static DateTimeFormatter pattern(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
    }

    /**
     * Ticks between {@code min} and {@code max} (inclusive), at most about {@code maxTicks} of them, on the biggest round
     * step that gives that many.
     */
    static Ticks of(long min, long max, ZoneId zone, int maxTicks) {
        maxTicks = Math.max(2, maxTicks);
        double span = Math.max(1, max - min);
        Step chosen = STEPS.get(STEPS.size() - 1);
        for (Step step : STEPS) {
            if (span / step.approxMillis() + 1 <= maxTicks) {
                chosen = step;
                break;
            }
        }
        List<Long> out = new ArrayList<>();
        ZonedDateTime t = first(Instant.ofEpochMilli(min).atZone(zone), chosen, zone);
        for (int guard = 0; guard < 5000; guard++) {
            long ms = t.toInstant().toEpochMilli();
            if (ms > max) break;
            if (ms >= min) out.add(ms);
            t = next(t, chosen, zone);
        }
        long[] at = new long[out.size()];
        for (int i = 0; i < at.length; i++) at[i] = out.get(i);
        return new Ticks(at, formatFor(chosen, span));
    }

    private static DateTimeFormatter formatFor(Step step, double span) {
        return switch (step.unit()) {
            case SECONDS -> span > 20 * HOUR ? pattern("MMM d HH:mm:ss") : pattern("HH:mm:ss");
            case MINUTES, HOURS -> span > 20 * HOUR ? pattern("MMM d HH:mm") : pattern("HH:mm");
            case DAYS, WEEKS -> span > 330 * DAY ? pattern("MMM d, yyyy") : pattern("MMM d");
            case MONTHS -> pattern("MMM yyyy");
            default -> pattern("yyyy");
        };
    }

    /** The latest round moment at or before {@code t}; the caller drops it if it is before the range. */
    private static ZonedDateTime first(ZonedDateTime t, Step step, ZoneId zone) {
        int n = step.count();
        switch (step.unit()) {
            case SECONDS -> {
                t = t.truncatedTo(ChronoUnit.SECONDS);
                return t.withSecond(t.getSecond() - t.getSecond() % n);
            }
            case MINUTES -> {
                t = t.truncatedTo(ChronoUnit.MINUTES);
                return t.withMinute(t.getMinute() - t.getMinute() % n);
            }
            case HOURS -> {
                t = t.truncatedTo(ChronoUnit.HOURS);
                return t.withHour(t.getHour() - t.getHour() % n);
            }
            case DAYS -> {
                LocalDate d = t.toLocalDate();
                return d.minusDays(Math.floorMod(d.toEpochDay(), n)).atStartOfDay(zone);
            }
            case WEEKS -> {
                return t.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone);
            }
            case MONTHS -> {
                LocalDate d = t.toLocalDate().withDayOfMonth(1);
                return d.minusMonths((d.getMonthValue() - 1) % n).atStartOfDay(zone);
            }
            default -> {
                int year = t.getYear();
                return LocalDate.of(year - Math.floorMod(year, n), 1, 1).atStartOfDay(zone);
            }
        }
    }

    private static ZonedDateTime next(ZonedDateTime t, Step step, ZoneId zone) {
        int n = step.count();
        return switch (step.unit()) {
            case SECONDS, MINUTES, HOURS -> t.plus(n, step.unit());
            case DAYS -> t.toLocalDate().plusDays(n).atStartOfDay(zone);
            case WEEKS -> t.toLocalDate().plusWeeks(1).atStartOfDay(zone);
            case MONTHS -> t.toLocalDate().plusMonths(n).atStartOfDay(zone);
            default -> t.toLocalDate().plusYears(n).atStartOfDay(zone);
        };
    }

    /**
     * How much of a moment to write when it stands alone, as in a tooltip: only the date when every time is at midnight, down
     * to the minute when none has seconds, else to the second.
     */
    static DateTimeFormatter detail(long[] times, int count, ZoneId zone) {
        boolean minutes = false, seconds = false;
        for (int i = 0; i < count; i++) {
            ZonedDateTime t = Instant.ofEpochMilli(times[i]).atZone(zone);
            if (t.getSecond() != 0 || t.getNano() != 0) seconds = true;
            else if (t.getMinute() != 0 || t.getHour() != 0) minutes = true;
        }
        return seconds ? pattern("yyyy-MM-dd HH:mm:ss") : minutes ? pattern("yyyy-MM-dd HH:mm") : pattern("yyyy-MM-dd");
    }

    static String format(DateTimeFormatter format, long millis, ZoneId zone) {
        return format.format(Instant.ofEpochMilli(millis).atZone(zone));
    }
}
