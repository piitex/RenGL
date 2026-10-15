# PieChartOverlay

[Documentation](../README.md) › [Charts](README.md)

`me.piitex.engine.ui.charts.PieChartOverlay` divides a circle into slices, each as wide as its share of the total. Give
it a hole and it is a donut, with room in the middle for the total or for the slice under the pointer. Read
[Charts](README.md) first for the legend, colors, formats and interaction every chart shares.

```java
PieChartOverlay chart = new PieChartOverlay(480, 340);
chart.setTitle("Disk usage");
chart.addSlice("Video", 420);
chart.addSlice("Photos", 180);
chart.addSlice("Other", 60);
chart.setHoleRatio(0.6f); // a donut
layout.addElement(chart);
```

## Slices

A pie has slices rather than series: each has a label and a value, and the legend lists the slices.

```java
chart.addSlice("Video", 420);
chart.addSlice("Music", 90, Color.PURPLE);  // with a color of its own
chart.setSlices(List.of("A", "B", "C"), 10, 20, 30); // replace them all

chart.setSliceValue(1, 150);    // eases: the slices swing to their new shares
chart.setSliceColor(0, null);   // back to the palette's
chart.getTotal();               // the sum of the slices that are showing
```

A negative or `NaN` value counts as empty. The slices **sweep open** when the chart first appears and ease to new values.

A slice can be taken out, and the rest close up to fill the circle. The legend does this on a click; in code:

```java
chart.setSliceVisible(2, false);
```

## Pie or donut

```java
chart.setHoleRatio(0.6f);   // 0 (the default) is a pie; 0.55 to 0.7 is a donut; at most 0.9
chart.setStartAngle(-90f);  // where the first slice begins, in degrees clockwise from the right. -90 is straight up
chart.setSliceGap(2f);      // empty width between slices. Parallel edges, also right to a pie's middle
chart.setShowLabels(false); // the percentage written on each slice that is wide enough (default on)
```

The middle of a donut shows the **total** and, while the pointer is on a slice, that slice's value and name. To write your
own there (it does not change on pointing):

```java
chart.setCenterText("$1.2M", "Revenue"); // text, and a smaller line under it
chart.setCenterText(null, null);         // back to the total
```

The text is made smaller if it does not fit the hole. A hole with a radius under 24 points has no text.

## Pointing and clicking

Pointing at a slice, or at its legend entry, lifts it out of the circle while the others fade back, and shows a tooltip with
its value and its share of the total. The hole of a donut is not a slice. A click on a slice reports it through
[`onPointClick`](README.md#interaction), with the slice as the category and 0 as the series.

```java
chart.onPointClick(hit -> select(hit.categoryLabel()));
```

## Method reference

| Method | Description |
|---|---|
| `PieChartOverlay(double width, double height)` | |
| `addSlice(String, double)` / `addSlice(String, double, Color)` | A slice at the end |
| `setSlices(List<String>, double...)` | Replace all slices |
| `setSliceValue(int, double)` / `getSliceValue(int)` / `getSliceCount()` | One slice's value, and how many there are |
| `setSliceColor(int, Color)` / `sliceColor(int)` | A slice's own color, and the one it ends up with |
| `setSliceVisible(int, boolean)` / `isSliceVisible(int)` | Take a slice out of the circle |
| `getTotal()` | The sum of the visible slices |
| `getHoleRatio()` / `setHoleRatio(float)` | 0 for a pie, up to 0.9 |
| `getStartAngle()` / `setStartAngle(float)` | Where the first slice starts |
| `setSliceGap(float)` | Width of the gaps |
| `isShowLabels()` / `setShowLabels(boolean)` | Percentages on the slices |
| `setCenterText(String, String)` | The donut's center text, or null, null for the total |

Everything in [Charts](README.md) (title, legend, palette, formats, animation) applies too. The legend is on the right by default.
Categories are the slice labels: `setCategories` and `addCategory` change them without touching the values, so use `addSlice` instead.
