package me.piitex.engine.ui.charts;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The numbers behind the axes: log decades, time ticks on the calendar, and where a value lands. No window needed. */
class AxisScaleTest {
    private static long utc(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private static List<Double> majors(NumericAxis axis) {
        return axis.ticks().stream().filter(NumericAxis.Tick::major).map(NumericAxis.Tick::value).toList();
    }

    // ---- linear -------------------------------------------------------------------------------------------------

    @Test
    void aLinearAxisHasTheTicksOfTheNiceScale() {
        NumericAxis axis = new NumericAxis();
        axis.fit(3.2, 47.9, 6, 0);
        assertEquals(List.of(0.0, 10.0, 20.0, 30.0, 40.0, 50.0), majors(axis));
        assertEquals(0.5, axis.fraction(25), 1e-9);
        assertEquals(0.0, axis.fraction(0), 1e-9);
    }

    @Test
    void paddingKeepsTheExtremePointsOffTheEdge() {
        NumericAxis axis = new NumericAxis();
        axis.fit(0, 100, 11, 0.04);
        double[] range = axis.targetRange();
        assertTrue(range[0] < 0 && range[1] > 100, "the ends move out past the data: " + range[0] + " to " + range[1]);
    }

    // ---- log ----------------------------------------------------------------------------------------------------

    @Test
    void aLogAxisPutsATickOnEveryPowerOfTen() {
        NumericAxis axis = new NumericAxis();
        axis.configure(AxisScale.LOG, Double.NaN, Double.NaN, false);
        axis.fit(3, 8000, 8, 0);
        double[] range = axis.targetRange();
        assertEquals(1, range[0], 1e-9);
        assertEquals(10000, range[1], 1e-6);
        assertEquals(List.of(1.0, 10.0, 100.0, 1000.0, 10000.0), majors(axis));
        assertEquals(0.25, axis.fraction(10), 1e-9, "equal ratios are equal distances");
        assertEquals(0.5, axis.fraction(100), 1e-9);
        assertEquals(0.75, axis.fraction(1000), 1e-9);
    }

    @Test
    void betweenThePowersTheLogAxisDrawsFainterLines() {
        NumericAxis axis = new NumericAxis();
        axis.configure(AxisScale.LOG, Double.NaN, Double.NaN, false);
        axis.fit(1, 1000, 8, 0);
        List<Double> minors = axis.ticks().stream().filter(t -> !t.major()).map(NumericAxis.Tick::value).toList();
        assertTrue(minors.containsAll(List.of(2.0, 5.0, 9.0, 20.0, 50.0, 200.0, 900.0)), "2 to 9 times each power: " + minors);
        assertFalse(minors.contains(1.0) || minors.contains(10.0), "the powers themselves are the labelled ones");
    }

    @Test
    void manyDecadesAreThinnedToEveryThirdPower() {
        NumericAxis axis = new NumericAxis();
        axis.configure(AxisScale.LOG, Double.NaN, Double.NaN, false);
        axis.fit(1, 1e12, 5, 0);
        assertEquals(List.of(1.0, 1e3, 1e6, 1e9, 1e12), majors(axis));
        assertTrue(axis.ticks().stream().allMatch(NumericAxis.Tick::major), "too many decades for lines between them");
    }

    @Test
    void aLogAxisSmallerThanADecadeLabelsTheTimesTable() {
        NumericAxis axis = new NumericAxis();
        axis.configure(AxisScale.LOG, 20, 80, false);
        axis.fit(30, 60, 10, 0);
        assertEquals(List.of(20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0), majors(axis));
        assertEquals(0.0, axis.fraction(20), 1e-9);
        assertEquals(1.0, axis.fraction(80), 1e-9);
    }

    @Test
    void aLogAxisCannotPlaceZeroOrLess() {
        NumericAxis axis = new NumericAxis();
        axis.configure(AxisScale.LOG, Double.NaN, Double.NaN, false);
        axis.fit(1, 100, 6, 0);
        assertTrue(Double.isNaN(axis.fraction(0)));
        assertTrue(Double.isNaN(axis.fraction(-5)));
        assertTrue(Double.isNaN(axis.fraction(Double.NaN)));
    }

    @Test
    void aLogAxisWithOneValueOrNoneStillHasARange() {
        NumericAxis one = new NumericAxis();
        one.configure(AxisScale.LOG, Double.NaN, Double.NaN, false);
        one.fit(100, 100, 6, 0);
        double[] range = one.targetRange();
        assertEquals(10, range[0], 1e-9);
        assertEquals(1000, range[1], 1e-6);
        assertEquals(0.5, one.fraction(100), 1e-9, "alone, it sits in the middle");

        NumericAxis none = new NumericAxis();
        none.configure(AxisScale.LOG, Double.NaN, Double.NaN, false);
        none.fit(Double.NaN, Double.NaN, 6, 0);
        assertEquals(1, none.targetRange()[0], 1e-9);
        assertEquals(10, none.targetRange()[1], 1e-6);
    }

    @Test
    void aLogAxisEasesByDecades() {
        NumericAxis axis = new NumericAxis();
        axis.configure(AxisScale.LOG, Double.NaN, Double.NaN, false);
        axis.fit(1, 10, 6, 0);
        axis.fit(1, 1000, 6, 0); // wants 1 to 1000; still drawn as 1 to 10
        axis.ease(0.5f);
        double[] range = axis.range();
        assertEquals(1, range[0], 1e-9);
        assertEquals(100, range[1], 1e-6, "halfway between 10 and 1000 is 100, not 505");
    }

    @Test
    void changingTheScaleJumpsInsteadOfEasing() {
        NumericAxis axis = new NumericAxis();
        axis.fit(0, 100, 6, 0);
        axis.configure(AxisScale.LOG, Double.NaN, Double.NaN, false);
        axis.fit(1, 1000, 6, 0);
        assertEquals(1000, axis.range()[1], 1e-6);
    }

    // ---- time ---------------------------------------------------------------------------------------------------

    @Test
    void aDayGetsTicksOnTheSixthHour() {
        TimeScale.Ticks ticks = TimeScale.of(utc(2026, 3, 5, 0, 0), utc(2026, 3, 6, 0, 0), ZoneOffset.UTC, 8);
        assertEquals(5, ticks.at().length);
        for (long at : ticks.at()) {
            ZonedDateTime t = Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC);
            assertEquals(0, t.getHour() % 6);
            assertEquals(0, t.getMinute());
        }
        assertEquals("Mar 5 06:00", TimeScale.format(ticks.format(), ticks.at()[1], ZoneOffset.UTC), "a span of a day or more names the date");

        TimeScale.Ticks morning = TimeScale.of(utc(2026, 3, 5, 6, 0), utc(2026, 3, 5, 12, 0), ZoneOffset.UTC, 7);
        assertEquals("08:00", TimeScale.format(morning.format(), morning.at()[2], ZoneOffset.UTC), "within a day only the time");
    }

    @Test
    void ticksStartAtTheFirstRoundMomentInsideTheRange() {
        // 10:20 to 14:40 with room for about 5 ticks: hourly, starting at 11:00 rather than 10:00
        TimeScale.Ticks ticks = TimeScale.of(utc(2026, 3, 5, 10, 20), utc(2026, 3, 5, 14, 40), ZoneOffset.UTC, 6);
        assertEquals(utc(2026, 3, 5, 11, 0), ticks.at()[0]);
        assertTrue(ticks.at()[ticks.at().length - 1] <= utc(2026, 3, 5, 14, 40));
    }

    @Test
    void aYearGetsTicksOnTheFirstOfEverySecondMonth() {
        TimeScale.Ticks ticks = TimeScale.of(utc(2026, 1, 1, 0, 0), utc(2026, 12, 31, 0, 0), ZoneOffset.UTC, 8);
        assertEquals(6, ticks.at().length);
        int[] months = {1, 3, 5, 7, 9, 11};
        for (int i = 0; i < months.length; i++) {
            ZonedDateTime t = Instant.ofEpochMilli(ticks.at()[i]).atZone(ZoneOffset.UTC);
            assertEquals(months[i], t.getMonthValue());
            assertEquals(1, t.getDayOfMonth());
            assertEquals(0, t.getHour());
        }
        assertEquals("Mar 2026", TimeScale.format(ticks.format(), ticks.at()[1], ZoneOffset.UTC));
    }

    @Test
    void decadesGetTicksEveryFiveYears() {
        TimeScale.Ticks ticks = TimeScale.of(utc(1990, 1, 1, 0, 0), utc(2020, 1, 1, 0, 0), ZoneOffset.UTC, 8);
        assertEquals(7, ticks.at().length);
        assertEquals("1990", TimeScale.format(ticks.format(), ticks.at()[0], ZoneOffset.UTC));
        assertEquals("2020", TimeScale.format(ticks.format(), ticks.at()[6], ZoneOffset.UTC));
    }

    @Test
    void weeksStartOnMonday() {
        TimeScale.Ticks ticks = TimeScale.of(utc(2026, 3, 2, 0, 0), utc(2026, 4, 20, 0, 0), ZoneOffset.UTC, 9);
        for (long at : ticks.at()) {
            assertEquals(java.time.DayOfWeek.MONDAY, Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).getDayOfWeek());
        }
        assertTrue(ticks.at().length >= 6);
    }

    @Test
    void ticksFollowTheCalendarOfTheZoneThroughADaylightSavingChange() {
        ZoneId newYork = ZoneId.of("America/New_York");
        long from = LocalDate.of(2026, 3, 6).atStartOfDay(newYork).toInstant().toEpochMilli();
        long to = LocalDate.of(2026, 3, 11).atStartOfDay(newYork).toInstant().toEpochMilli();
        TimeScale.Ticks ticks = TimeScale.of(from, to, newYork, 6);
        assertEquals(6, ticks.at().length, "a tick for each midnight");
        for (long at : ticks.at()) {
            ZonedDateTime t = Instant.ofEpochMilli(at).atZone(newYork);
            assertEquals(0, t.getHour(), "midnight there on " + t.toLocalDate() + ", the 8th being only 23 hours long");
        }
    }

    @Test
    void aMomentAloneIsWrittenOnlyAsPreciseAsTheDataIs() {
        long[] days = {utc(2026, 3, 5, 0, 0), utc(2026, 3, 6, 0, 0)};
        assertEquals("2026-03-05", TimeScale.format(TimeScale.detail(days, 2, ZoneOffset.UTC), days[0], ZoneOffset.UTC));
        long[] minutes = {utc(2026, 3, 5, 0, 0), utc(2026, 3, 5, 14, 30)};
        assertEquals("2026-03-05 14:30", TimeScale.format(TimeScale.detail(minutes, 2, ZoneOffset.UTC), minutes[1], ZoneOffset.UTC));
        long[] seconds = {utc(2026, 3, 5, 0, 0) + 7000, utc(2026, 3, 5, 14, 30)};
        assertEquals("2026-03-05 00:00:07", TimeScale.format(TimeScale.detail(seconds, 2, ZoneOffset.UTC), seconds[0], ZoneOffset.UTC));
    }

    @Test
    void aTimeAxisRunsFromTheFirstToTheLastMoment() {
        NumericAxis axis = new NumericAxis();
        axis.configure(AxisScale.TIME, Double.NaN, Double.NaN, false);
        double from = utc(2026, 3, 5, 0, 0), to = utc(2026, 3, 7, 0, 0);
        axis.fit(from, to, 6, 0);
        assertEquals(0.0, axis.fraction(from), 1e-9);
        assertEquals(1.0, axis.fraction(to), 1e-9);
        assertEquals(0.5, axis.fraction(utc(2026, 3, 6, 0, 0)), 1e-9);

        axis.configure(AxisScale.TIME, utc(2026, 3, 5, 0, 0), utc(2026, 3, 9, 0, 0), false);
        axis.fit(from, to, 6, 0);
        axis.ease(1f);
        assertEquals(0.5, axis.fraction(utc(2026, 3, 7, 0, 0)), 1e-9, "a fixed window overrides the data");
    }

    @Test
    void aTimeAxisWithOneMomentStillHasAWidth() {
        NumericAxis axis = new NumericAxis();
        axis.configure(AxisScale.TIME, Double.NaN, Double.NaN, false);
        double at = utc(2026, 3, 5, 12, 0);
        axis.fit(at, at, 6, 0);
        double[] range = axis.targetRange();
        assertTrue(range[1] > range[0]);
        assertEquals(0.5, axis.fraction(at), 1e-9);
    }
}
