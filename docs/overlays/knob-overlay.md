# KnobOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.KnobOverlay` is a round dial the user turns by dragging up and down: a volume knob, a brightness control, a
synth's filter cutoff. It sweeps 270 degrees, with the value shown as an arc around the rim and a notch on the knob.

```java
KnobOverlay volume = new KnobOverlay(64, 0, 100);    // diameter, min, max
volume.setValue(70);
volume.setDefaultValue(70);
volume.setLabelFormatter(v -> Math.round(v) + "%");
volume.onValueChange(v -> audio.setVolume(v / 100));
layout.addElement(volume);
```

## Using it

| Input | What happens |
|---|---|
| Drag up or down | Raises or lowers the value; the whole range takes 200 points of drag ([`setDragRange`](#settings)) |
| Drag with Shift held | Five times finer |
| Wheel | One step per notch |
| Arrow keys (Tab to it) | One step; Shift makes it ten steps |
| Home, End | Jump to the minimum or maximum |
| Double-click | Back to the [default value](#settings), if one was set |

The drag is measured from where the press began, so a value snapped to a step does not lose the movement between steps.

## Settings

```java
knob.setValue(40);            // clamped and snapped; silent (does not fire onValueChange)
knob.setStep(5);              // the grid the value snaps to; also the size of a wheel notch or arrow press
knob.setDefaultValue(50);     // what a double-click goes back to
knob.setDragRange(300);       // points of drag for the whole range
knob.setShowValueText(false); // the number on the face; a knob under 40 points looks better without
knob.setLabelFormatter(v -> String.format("%.1f dB", v));
knob.onValueChange(v -> {});  // the user changed it, by any input
double ratio = knob.getRatio();   // 0 at min, 1 at max
```

Without a step, a wheel notch or arrow press moves a hundredth of the range.

## Look

Colors come from the theme: the groove from the scroll track, the value arc and the notch from the accent, the face from the field
background and the label from the text color. Each setter pins its color against theme switches.

```java
knob.setArcColor(Color.ORANGE);
knob.setGrooveColor(Color.DARK_GRAY);
knob.setFaceColor(Color.BLACK);
knob.setNotchColor(Color.WHITE);
knob.setArcThickness(6);
```

Call `dispose()` when permanently discarding one that drew its label. For a read-only dial, use a [`GaugeOverlay`](gauge-overlay.md).
