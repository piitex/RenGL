# RangeSliderOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.RangeSliderOverlay` is a [`SliderOverlay`](slider-overlay.md) with two thumbs that picks a **range**: a
price filter's from-to, the levels a quest is open to, the part of a clip to keep. The track between the thumbs is filled.

```java
RangeSliderOverlay price = new RangeSliderOverlay(280, 0, 500);   // width, min, max
price.setRange(50, 200);
price.setStep(10);
price.setLabelFormatter(v -> "$" + Math.round(v));
price.onRangeChange((low, high) -> applyFilter(low, high));
layout.addElement(price);
```

## Constructors

| Constructor | Height | Starts on |
|---|---|---|
| `RangeSliderOverlay(double width, double min, double max)` | 20 | the whole range |
| `RangeSliderOverlay(double width, double height, double min, double max, double low, double high)` | given | `[low, high]` |

## Range

```java
double low = price.getLow(), high = price.getHigh();
price.setRange(100, 300);       // clamped, snapped to the step, ordered; silent
price.setMin(0);                // also pulls the range into the new limits
price.setMax(1000);
price.setStep(10);              // both thumbs snap to the grid; 0 or less is continuous
price.setMinSpan(20);           // the thumbs stay at least this far apart (default 0: they can meet)
price.onRangeChange((low, high) -> {});   // by dragging, a track click or keys; not by setRange
```

`setRange` swaps its arguments if `low` is more than `high`, and when the `minSpan` leaves no room the high thumb gives way.

## Using it

A press on the track moves the **nearer** thumb there and keeps dragging it. The thumbs never cross. When they are together, the side the
press is on picks the thumb, so a pair stuck at one value can still be pulled apart either way.

With the keyboard (Tab to it): Left and Right move the active thumb by a step (a hundredth of the range when there is no step), Shift moves ten
times as far, Home and End jump it to its limit, and Up, Down or Space pick the other thumb. The active thumb has a ring while the slider has the
keyboard.

## Look

```java
price.setTickSpacing(100);                   // tick marks every 100
price.setLabelFormatter(v -> "$" + Math.round(v));   // text above each thumb; leave headroom for it
price.setLabelFontSize(12);
price.setTrackThickness(6);
price.setThumbRadius(8);
price.setTrackColor(Color.GRAY);             // each pins its color against theme switches
price.setFillColor(Color.BLUE);              // between the thumbs
price.setThumbColor(Color.BLUE);
price.setThumbActiveColor(Color.WHITE);      // the thumb being dragged
```

Colors come from the theme as `SliderOverlay`'s do: the track from the scroll track, the fill and thumbs from the accent, labels from the text color.
Call `dispose()` when permanently discarding one that drew labels.
