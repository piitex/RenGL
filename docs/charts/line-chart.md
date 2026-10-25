# LineChartOverlay

[Documentation](../README.md) › [Charts](README.md)

`me.piitex.engine.ui.charts.LineChartOverlay` draws each series as a line through its values, over category labels and a
value axis with round-number ticks. Read [Charts](README.md) first for the data, legend, colors, formats and
interaction every chart shares.

```java
LineChartOverlay chart = new LineChartOverlay(640, 360);
chart.setTitle("Visitors per day");
chart.setCategories("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");
chart.addSeries("Web", 120, 180, 150, 240, 210, 300, 260);
chart.addSeries("App", 80, 95, 140, 130, 190, 170, 220);
layout.addElement(chart);
```

## The value axis

The axis fits the data and rounds its ends to numbers a person would pick: data from 3.2 to 47.9 gets an axis of 0 to 50
in tens. Unlike a bar chart, a line chart does not have to include zero, so a price that moves between 112 and 118 is
drawn across the whole height. It re-fits, with an ease, whenever the data or the visible series change.

```java
chart.setBeginAtZero(true);       // always include zero
chart.setValueRange(0, 100);      // fix both ends...
chart.setValueMax(100);           // ...or only one (NaN goes back to fitting)
chart.setShowGrid(false);         // no lines across the plot
chart.setValueAxisTitle("Visitors");      // written beside the axis, turned
chart.setCategoryAxisTitle("Day");        // written under it
```

The labels along the bottom are thinned out when they would overlap.

## A logarithmic value axis

When the numbers span orders of magnitude (a balance that grows from a few hundred to millions) a straight axis squashes
the small ones into the floor. A **log axis** gives each power of ten the same height:

```java
chart.setLogScale(true);                 // or chart.setValueScale(AxisScale.LOG)
chart.setValueFormat(ChartFormat.currency("$"));
```

- The ticks are the powers of ten (1, 10, 100, ...), thinned to every second or third power when there are too many for the
  height, with fainter grid lines at 2 to 9 times each power when there is room.
- **Zero and negative values cannot be placed.** The line breaks there like a gap, and they do not count toward the axis range.
  A balance that falls to 0 is a gap on a log chart, not a line that falls off the bottom.
- The axis fits whole powers of ten around the data, `setValueMin` and `setValueMax` give exact ends (above zero), and it eases
  between ranges by powers of ten. `setBeginAtZero` is ignored, since a log axis has no zero.
- An area chart fills down to the bottom of the plot.

## A time axis

A line chart usually spaces its categories evenly. When the values happened at **moments**, so that two values a week apart should
be further from each other than two a day apart, give the chart the times instead of category labels:

```java
chart.setDates(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 9));   // midnights
chart.setTimes(Instant.parse("2026-03-05T10:20:00Z"), Instant.parse("2026-03-05T10:45:00Z"));   // or Instants
chart.setTimes(1_772_706_000_000L, 1_772_709_600_000L);                                         // or epoch milliseconds
chart.addSeries("Income", 120, 180, 150);            // one value for each time, in the same order
```

- **One time for each value**, earliest first. The axis runs from the first to the last, and the points sit where their times
  put them. A value with no time is left out.
- **Ticks fall on round moments**: every 15 minutes, every 6 hours, each Monday, the 1st of every second month, every 5 years,
  whichever suits the width. They follow the calendar of a time zone, so midnight is midnight and a day with a daylight saving
  change still has its tick. The labels get as much detail as the range needs (`14:30` within a day, `Mar 5 06:00` across several,
  `Mar 2026` across a year, `2026` across decades).
- **Pointing** finds the point nearest the pointer in time, and the tooltip names the moment, to the minute or the second if
  the times have them, or just the date if they are all midnights. `ChartHit.categoryLabel()` is that text too.
- It is for **line charts only**. `BarChartOverlay.setTimes` throws; bars sit in category bands.

```java
chart.setTimeZone(ZoneId.systemDefault());                    // UTC by default, so a chart reads the same everywhere
chart.setTimeFormat(DateTimeFormatter.ofPattern("d MMM"));    // your own text for the axis and the tooltip
chart.setTimeRange(from, to);                                 // show exactly this window, such as the last hour; clearTimeRange() to fit again
chart.clearTimes();                                           // back to equal steps and category labels
```

A game with a calendar of its own (a tycoon's in-game days) can pass `LocalDate`s, or turn its dates into epoch milliseconds in
any way it likes: the axis only needs numbers that grow with time. Set the zone before `setDates`, because a date becomes midnight in
the zone the chart has at that moment.

A **scrolling** chart adds a time and a value and drops the oldest of each, as with categories:

```java
chart.addTime(now);                 // or addDate(date) / addTime(instant)
income.add(nextReading());
chart.removeFirstTimes(1);
income.removeFirst(1);
```

The axis eases as the window moves, so the points slide instead of jumping.

## Look

```java
chart.setSmooth(true);        // curves instead of straight segments
chart.setFillArea(true);      // a faint wash under each line
chart.setShowMarkers(false);  // a dot on each value is the default
chart.setLineWidth(3f);       // default 2.5
```

`setSmooth` uses a monotone curve: between two points it never swings above the higher one or below the lower one, so
the curve never shows a peak that is not in the data.

A **gap** (`NaN`) breaks the line. A value with a gap on each side is drawn as its marker alone, so with markers off it does not show.

```java
chart.addSeries("API", 40, 60, 55, 90, 85, Double.NaN, 120);
```

## Pointing and clicking

Pointing at the chart draws a guide line at the nearest category with a larger dot on each series there, and a tooltip
that lists every visible series' value. A click reports the series closest to the pointer at that category, through
[`onPointClick`](README.md#interaction).

## Method reference

| Method | Description |
|---|---|
| `LineChartOverlay(double width, double height)` | |
| `isSmooth()` / `setSmooth(boolean)` | Curved lines |
| `isFillArea()` / `setFillArea(boolean)` | Fill between each line and the zero line |
| `isShowMarkers()` / `setShowMarkers(boolean)` | A dot on each value |
| `getLineWidth()` / `setLineWidth(float)` | Line thickness in points |
| `addSeries(...)`, `getSeries(...)`, `removeSeries`, `clearSeries` | [Series](README.md#data) |
| `setBeginAtZero(boolean)` / `setValueMin(double)` / `setValueMax(double)` / `setValueRange(double, double)` | The value axis' range |
| `setLogScale(boolean)` / `setValueScale(AxisScale)` / `getValueScale()` | A [logarithmic](#a-logarithmic-value-axis) value axis |
| `getAxisRange()` | The range drawn this frame, as `{min, max}` |
| `setTimes(long...)` / `setTimes(Instant...)` / `setDates(LocalDate...)` | Place the values at moments: a [time axis](#a-time-axis) |
| `addTime(...)` / `addDate(LocalDate)` / `removeFirstTimes(int)` / `clearTimes()` / `getTimes()` | Grow, scroll or drop the times |
| `setTimeZone(ZoneId)` / `setTimeFormat(DateTimeFormatter)` | The calendar and the text of the time axis |
| `setTimeRange(long, long)` / `clearTimeRange()` / `getTimeRange()` | Fix the span shown, or fit the first to the last time |
| `setShowGrid(boolean)` | Grid lines at each tick |
| `setValueAxisTitle(String)` / `setCategoryAxisTitle(String)` | Axis titles |

Everything in [Charts](README.md) (title, categories, legend, palette, formats, animation) applies too.
