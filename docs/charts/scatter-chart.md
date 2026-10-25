# ScatterChartOverlay

[Documentation](../README.md) › [Charts](README.md)

`me.piitex.engine.ui.charts.ScatterChartOverlay` places points by two numbers, one across and one up: the rent of a building
against its floor area, a company's staff against its revenue. Give the points a size and the chart is a **bubble plot**. Read
[Charts](README.md) first for the legend, colors, formats and interaction every chart shares.

```java
ScatterChartOverlay chart = new ScatterChartOverlay(640, 360);
chart.setTitle("Rent against floor area");
chart.setAxisTitles("Area (m²)", "Rent");                 // x, then y
chart.setValueFormat(ChartFormat.currency("$"));           // the value format is for y
chart.addSeries("Offices", new double[]{40, 85, 120, 200}, new double[]{900, 1700, 2100, 3900});  // xs, then ys
chart.addSeries("Shops", new double[]{25, 60, 90}, new double[]{1100, 1500, 2300});
layout.addElement(chart);
```

## Series

A series is a `ScatterSeries`: a name, a color, and points with an **x**, a **y** and optionally a **size**. It is a
`ChartSeries`, so the legend, the palette, `setVisible` and the easing work as they do for the other charts. A point with
`NaN` in x or y is left out.

```java
ScatterSeries live = chart.addSeries("Sales");                  // empty, for points that arrive one at a time
live.add(12, 340).add(15, 410);                                 // x, y
live.add(20, 500, 8);                                           // x, y, size: this one is a bubble
live.set(0, 13, 360);                                           // move point 0
live.setPoints(xs, ys);                                         // or replace them all, with sizes as a third array
live.removeFirst(1);                                            // drop the oldest, for a chart that scrolls
double x = live.getX(0), y = live.getY(0), size = live.getSize(0);
```

`ChartSeries`' methods for a row of single numbers (`add(double)`, `setValues`, `set(int, double)`) throw
`UnsupportedOperationException` here, since a point needs an x. The chart reads the points every frame, so changing them is
enough: the points and both axes ease to the new numbers.

## The axes

Both axes fit the data and round their ends to numbers a person would pick, with a little room so the outermost points are not on
the edge. They re-fit as the data or the visible series change.

```java
chart.setXRange(0, 250);                    // fix the span of x (NaN for an end goes back to fitting)
chart.setYRange(0, Double.NaN);
chart.setBeginAtZero(false, true);          // x: no, y: yes. Off for both by default: a cloud of points is not a length
chart.setXFormat(ChartFormat.unit(" m²"));  // how x is written on its axis and in the tooltip; plain numbers by default
chart.setShowGrid(false);                   // grid lines at the ticks of both axes
```

| Scale | |
|---|---|
| `AxisScale.LINEAR` | The default |
| `AxisScale.LOG` | Each power of ten is the same distance. `setXScale` and `setYScale` take it. A point with a zero or negative coordinate on a log axis cannot be placed, so it is not drawn and does not stretch the axis |
| `AxisScale.TIME` | `setXScale` only. The x of each point is a moment, in **milliseconds since 1970-01-01T00:00Z**. The ticks fall on round times in a [time zone](line-chart.md#a-time-axis) (`setTimeZone`, UTC by default; `setTimeFormat` for your own text) |

```java
chart.setXScale(AxisScale.LOG);
chart.setYScale(AxisScale.LOG);            // a straight line on a log-log plot is a power law
```

## Bubbles

A point with a size is drawn as a bubble whose **area** is in proportion to the size, so a value that is four times as large looks four
times as large and not sixteen. The largest size in the chart gets the largest radius and a size of zero the smallest.

```java
chart.setBubbleRadius(4f, 40f);            // smallest and largest radius in points. Default 4 and 26
chart.setMarkerRadius(6f);                 // the radius of a point with no size. Default 5
chart.addSeries("Companies", revenue, staff, profit);   // xs, ys, sizes
```

Bubbles and points are a little see-through, so a crowd of them still shows where it is dense. Sizes are compared across all the
visible series, so hiding the series with the biggest bubble makes the others grow.

## Pointing and clicking

Pointing at a point rings it and shows a tooltip with the series' name and the point's x, y and size. The axis titles name the
numbers in it (otherwise `x` and `y`). Where points overlap, the one whose centre is nearest the pointer is the one that counts. A
click reports it through [`onPointClick`](README.md#interaction):

```java
chart.onPointClick(hit -> select(hit.seriesName(), hit.category()));   // category() is the point's index in its series
```

For a scatter chart `ChartHit.categoryLabel()` is the x as the axis writes it, and `value()` is the y.

`pointPosition(series, point)` says where a point is drawn, in the chart's coordinates (null if it has no place), for putting
something over it or for tests; `pointRadius(series, point)` gives its radius.

## Method reference

| Method | Description |
|---|---|
| `ScatterChartOverlay(double width, double height)` | |
| `addSeries(String, double[] xs, double[] ys)` / `(String, double[], double[], double[] sizes)` / `(String, Color, double[], double[])` / `(String)` / `(ScatterSeries)` | Add a series; it is returned |
| `getSeries(...)`, `removeSeries`, `clearSeries` | [Series](README.md#data) |
| `setXScale(AxisScale)` / `setYScale(AxisScale)` | `LINEAR`, `LOG`, or `TIME` for x |
| `setXRange(double, double)` / `setYRange(double, double)` | Fix the span of an axis |
| `setBeginAtZero(boolean x, boolean y)` | Always include zero on a linear axis |
| `setAxisTitles(String x, String y)` | Titles written along the axes |
| `setXFormat(DoubleFunction<String>)` | How x is written; `setValueFormat` is for y |
| `setTimeZone(ZoneId)` / `setTimeFormat(DateTimeFormatter)` | For a time axis |
| `setMarkerRadius(float)` / `setBubbleRadius(float, float)` | Sizes of points and bubbles |
| `setShowGrid(boolean)` | Grid lines |
| `getXRange()` / `getYRange()` | The ranges drawn this frame, as `{min, max}` |
| `pointPosition(int, int)` / `pointRadius(int, int)` | Where and how big a point is drawn |

Everything in [Charts](README.md) (title, legend, palette, formats, animation) applies too.
