# SliderOverlay and PercentSliderOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.SliderOverlay` is a draggable numeric slider: a track, a filled progress
portion, a round thumb, and three optional extras: tick marks, tick labels, and a label that follows the
thumb. Click anywhere on the track and the thumb jumps there; then keep dragging.

```java
SliderOverlay brightness = new SliderOverlay(200, 0, 100, 50); // width, min, max, initial
brightness.setStep(1);
brightness.setLabelFormatter(v -> Math.round(v) + "%");
brightness.onValueChange(v -> System.out.println("brightness = " + v));
layout.addElement(brightness);
```

## Constructors

| Constructor | Range | Height | Start value |
|---|---|---|---|
| `SliderOverlay(double width)` | 0-100 | 20 | 0 |
| `SliderOverlay(double width, double min, double max)` | `[min, max]` | 20 | `min` |
| `SliderOverlay(double width, double min, double max, double initialValue)` | `[min, max]` | 20 | `initialValue` |
| `SliderOverlay(double width, double height, double min, double max, double initialValue)` | `[min, max]` | given | `initialValue` |

The initial value is clamped and snapped like `setValue`.

## Value

```java
double v = slider.getValue();
slider.setValue(75); // clamped to [min, max], snapped to step; silent (no callback)
double pct = slider.getPercentage(); // (value - min) / (max - min), 0 if max <= min

slider.setMin(10); // also re-clamps the current value
slider.setMax(200);

slider.onValueChange(value -> System.out.println(value)); // fires on drag or track click, NOT on setValue
```

`onValueChange` holds one listener. Calling it again replaces it.

## Snapping: step vs. magnet

There are two independent kinds of snapping. They feel different.

| | `setStep(double)` | `setSnapSpacing(double)` |
|---|---|---|
| Behavior | **Hard snap**: the value is always a multiple of the step | **Soft magnet**: the thumb is gently pulled toward grid points, and can still be dragged straight through them |
| Effect on `getValue()` | Always on the grid | Can land anywhere, but tends to settle on grid points |
| `<= 0` (default) | Continuous | Off |

```java
slider.setStep(5); // 0, 5, 10, ... only

slider.setSnapSpacing(25); // pulled toward 0, 25, 50, 75, 100
slider.setSnapStrength(0.4f); // width of the magnetic zone, fraction (0..1] of the half-distance (default 0.4)
```

## Ticks and labels

```java
slider.setTickSpacing(25); // ticks at 0, 25, 50, 75, 100 (<= 0: none)
slider.setTickLabelFormatter(v -> Math.round(v) + "%"); // small text under each tick
slider.setLabelFormatter(v -> String.format("%.1f", v)); // text above the thumb, follows it
slider.setLabelFontSize(12f); // default 12
slider.setTickLabelFontSize(10f); // default 10
```

Formatters are re-evaluated every frame, so they can read live state. Tick labels are independent of the tick
marks themselves, but need `setTickSpacing` to have positions to draw at. A `null` formatter draws
nothing.

**Leave headroom.** The label above the thumb and the tick labels below the track are drawn outside the
slider's track and are not clipped to its bounds. The default height is 20, so give the slider more height
(`setHeight(46)`) or leave room in the layout when you use them.

## Geometry and colors

```java
slider.setTrackThickness(6f); // default 6
slider.setThumbRadius(8f); // default 8; the track is inset by this so the thumb never leaves the bounds

slider.setTrackColor(Color.LIGHT_GRAY);
slider.setFillColor(Color.BLUE);
slider.setThumbColor(Color.BLUE);
slider.setThumbActiveColor(Color.DARK_BLUE); // while dragging; null = same as thumb
slider.setTickColor(Color.GRAY);
slider.setLabelColor(Color.BLACK);
slider.setTickLabelColor(Color.GRAY);
```

Colors come from the theme (scroll track/thumb tokens and the border/text tokens) and setting one pins it. `isDragging()`
reports whether the thumb is being dragged.

## PercentSliderOverlay

A `SliderOverlay` pre-configured as a percentage control: range 0-100, whole numbers (`step 1`), and a
`"N%"` label above the thumb. Everything else on `SliderOverlay` still applies.

```java
PercentSliderOverlay volume = new PercentSliderOverlay(200, 60); // width, initial percent
volume.setHeight(46); // headroom for labels
volume.setTickSpacing(25);
volume.setTickLabelFormatter(v -> Math.round(v) + "%");
volume.setSnapSpacing(25); // soft magnet at each tick
volume.onValueChange(v -> System.out.println("Volume: " + Math.round(v) + "%"));
layout.addElement(volume);
```

| Constructor | Notes |
|---|---|
| `PercentSliderOverlay(double width)` | Starts at 0% |
| `PercentSliderOverlay(double width, double initialPercent)` | Clamped to 0-100 |

The second constructor argument is the **initial percent**, not the height.

## Cleanup

`dispose()` releases the label fonts if any were ever drawn.

## Method reference

| Group | Methods |
|---|---|
| Value | `getValue`, `setValue`, `getPercentage`, `getMin/setMin`, `getMax/setMax`, `onValueChange` |
| Snapping | `getStep/setStep`, `getSnapSpacing/setSnapSpacing`, `getSnapStrength/setSnapStrength` |
| Ticks | `getTickSpacing/setTickSpacing`, `getTickLabelFormatter/setTickLabelFormatter`, `getTickLabelFontSize/setTickLabelFontSize`, `getTickLabelColor/setTickLabelColor`, `getTickColor/setTickColor` |
| Label | `getLabelFormatter/setLabelFormatter`, `getLabelFontSize/setLabelFontSize`, `getLabelColor/setLabelColor`, `setLabelFontFile` |
| Geometry | `getTrackThickness/setTrackThickness`, `getThumbRadius/setThumbRadius` |
| Colors | `getTrackColor`, `getFillColor`, `getThumbColor`, `getThumbActiveColor` and their setters |
| State | `isDragging` |
