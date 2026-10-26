# GaugeOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.GaugeOverlay` is a round read-out of one number: a speedometer, a temperature, a health or fuel dial.
Unlike a [`KnobOverlay`](knob-overlay.md) the user does not turn it; the game sets the value and the gauge eases to it.

```java
GaugeOverlay speed = new GaugeOverlay(160, 0, 240);   // diameter, min, max
speed.setTitle("km/h");
speed.setStyle(GaugeOverlay.Style.NEEDLE);
speed.addZone(0, 160, Color.GREEN);
speed.addZone(160, 200, Color.YELLOW);
speed.addZone(200, 240, Color.RED);
speed.setTickCount(7);                 // marks at 0, 40, 80 ... 240
speed.setValue(car.getSpeed());        // every frame is fine
```

## Styles

| Style | The value is |
|---|---|
| `Style.ARC` (default) | An arc along the rim, in the color of the [zone](#zones) it is in, or the accent where there is none |
| `Style.NEEDLE` | A needle from the hub over a rim that shows the zones |

The dial sweeps 270 degrees, from the lower left to the lower right. `setSweep(startDegrees, sweepDegrees)` changes that, in degrees
clockwise from 3 o'clock: `setSweep(180, 180)` is a half-circle over the top.

## Easing

`setValue` sets the value the gauge is heading for; the **displayed** value follows it. `setSmoothing(seconds)` is how long it takes to cover
about two thirds of the way (0.15 by default; 0 or less jumps at once), so a value read every frame moves smoothly even when it changes in
steps. `getValue()` is the real value and `getDisplayedValue()` what is drawn now. The text in the middle counts up with the dial.

## Zones

```java
gauge.addZone(0, 60, Color.GREEN).addZone(60, 80, Color.YELLOW).addZone(80, 100, Color.RED);
Paint color = gauge.getZoneColor(75);     // yellow; null when no zone has the value
gauge.clearZones();
```

A zone includes its start and excludes its end, except the last one, which includes the maximum.

## Text and look

```java
gauge.setTitle("RPM");                        // small text under the value
gauge.setLabelFormatter(v -> Math.round(v) + "°C");
gauge.setShowValueText(false);
gauge.setArcThickness(12);
gauge.setArcColor(Color.CYAN);                // each setter pins its color against theme switches
gauge.setNeedleColor(Color.WHITE);
gauge.setGrooveColor(Color.DARK_GRAY);
```

Colors come from the theme: the groove from the scroll track, the arc and needle from the accent, ticks from the field border and text
from the text color. It takes no input. Call `dispose()` when permanently discarding one.
