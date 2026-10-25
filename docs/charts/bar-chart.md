# BarChartOverlay

[Documentation](../README.md) › [Charts](README.md)

`me.piitex.engine.ui.charts.BarChartOverlay` compares amounts across categories. Each category is a group with one bar per
series, side by side or stacked. Read [Charts](README.md) first for the data, legend, colors, formats and interaction every
chart shares.

```java
BarChartOverlay chart = new BarChartOverlay(640, 360);
chart.setTitle("Sales by region");
chart.setCategories("North", "South", "East", "West");
chart.addSeries("2025", 42, 31, 55, 28);
chart.addSeries("2026", 51, 36, 49, 40);
layout.addElement(chart);
```

## Grouped, stacked, horizontal

```java
chart.setStacked(true);      // each category's series piled into one bar; positive values up, negative down
chart.setHorizontal(true);   // bars run left to right, categories down the side
chart.setShowValues(true);   // the value at each bar's end, when it fits (not for stacked bars)
```

A horizontal chart is the one to use for **long category names** or many categories: the labels are written along
the side instead of being thinned out. The first category is at the top.

Bars grow from the **zero line**, and negative values hang below it. The axis always includes zero, since the length of a bar
is its number, but the end can be fixed:

```java
chart.setValueRange(0, 100);  // e.g. percentages
chart.setValueMax(100);
chart.setBeginAtZero(false);  // lets the axis fit the data instead. Bars then start at the axis' foot
```

## A logarithmic value axis

```java
chart.setLogScale(true);   // or chart.setValueScale(AxisScale.LOG)
```

For amounts that span orders of magnitude (the population of a town, a city and a region), where on a straight axis the small
bars would be a hair. Each power of ten gets the same height, so a bar is as tall as the **logarithm** of its value: a bar twice as
tall is not twice the amount. A log axis has no zero, so bars grow from the **bottom** of the axis, and the axis starts at a
power of ten below the smallest value.

- A value of zero or less has no bar.
- A **stacked** chart stays linear, since the parts of a stack add; the setting is kept for when it is unstacked.
- Read [the line chart's](line-chart.md#a-logarithmic-value-axis) notes on ticks and ranges; they are the same.

## Look

```java
chart.setBarCornerRadius(6f);  // the end of each bar away from zero; default 4. Stacked bars are square
chart.setGroupGap(0.4f);       // empty share of each category's room between groups, 0 to 0.9; default 0.3
chart.setLegendPosition(LegendPosition.NONE); // a single series needs no legend
chart.setValueFormat(ChartFormat.currency("$"));
```

Hiding a series through the legend takes it out of the groups: the other bars widen to fill the space and the axis re-fits.

## Pointing and clicking

Pointing at a group lights its band, lifts the bar under the pointer, and shows a tooltip with every series' value in that
category. A click on a bar reports it through [`onPointClick`](README.md#interaction); a click between bars reports nothing.

```java
chart.onPointClick(hit -> open(hit.categoryLabel(), hit.seriesName()));
```

## Method reference

| Method | Description |
|---|---|
| `BarChartOverlay(double width, double height)` | |
| `isStacked()` / `setStacked(boolean)` | Pile each category's series up |
| `isHorizontal()` / `setHorizontal(boolean)` | Bars run left to right |
| `isShowValues()` / `setShowValues(boolean)` | Value at each bar's end |
| `getCornerRadius()` / `setBarCornerRadius(float)` | Roundness of a bar's outer end |
| `setGroupGap(float)` | Space between groups, as a share of a category's room |
| `addSeries(...)`, `getSeries(...)`, `removeSeries`, `clearSeries` | [Series](README.md#data) |
| `setBeginAtZero(boolean)` / `setValueMin(double)` / `setValueMax(double)` / `setValueRange(double, double)` | The value axis' range |
| `setLogScale(boolean)` / `setValueScale(AxisScale)` | A [logarithmic](#a-logarithmic-value-axis) value axis |
| `getAxisRange()` | The range drawn this frame, as `{min, max}` |
| `setShowGrid(boolean)` | Grid lines at each tick |
| `setValueAxisTitle(String)` / `setCategoryAxisTitle(String)` | Axis titles |

Everything in [Charts](README.md) (title, categories, legend, palette, formats, animation) applies too.
