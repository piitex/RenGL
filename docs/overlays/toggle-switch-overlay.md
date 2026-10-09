# ToggleSwitchOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.ToggleSwitchOverlay` is an on/off switch: a pill-shaped track with a knob that slides from one
end to the other. It is the touch-style counterpart of [`CheckBoxOverlay`](checkbox-overlay.md). It draws no label. Put a
[`TextOverlay`](text-overlay.md) next to it.

```java
ToggleSwitchOverlay notifications = new ToggleSwitchOverlay();
notifications.onToggle(on -> System.out.println("notifications: " + on));
layout.addElement(notifications);
```

## Constructors

| Constructor | Result |
|---|---|
| `ToggleSwitchOverlay()` | Off, 44 × 24 |
| `ToggleSwitchOverlay(double width, double height)` | Off, custom size |
| `ToggleSwitchOverlay(double width, double height, boolean on)` | Initial state chosen |

## State

```java
boolean on = sw.isOn();
sw.setOn(true); // silent: the knob jumps there and onToggle does NOT fire
sw.toggle(); // flips (animated) and fires onToggle, which is what a click runs
sw.onToggle(on -> ...); // user click or keyboard only; one listener is held at a time
```

Clicking anywhere on the switch flips it, and the knob slides across in about 0.16 seconds while the track color fades
between its two colors. The switch is [`Focusable`](../input/input-and-focus.md#keyboard-focus) and a Tab stop. Space or
Enter flips it, and a focused switch draws a ring around the track.

## Look

| Part | Theme token | Pin it with |
|---|---|---|
| Off track | `Theme.getFieldBorder()` | `setOffColor(Paint)` |
| On track | `Theme.getSelection()` at full opacity | `setOnColor(Paint)` |
| Knob | White | `setKnobColor(Paint)` |

The engine has no separate accent token, so the on color is derived from the selection color. It is a pale blue in the light
theme and a stronger blue in the dark theme. Set `setOnColor` for a brand color. See [Theming](../styling/theming.md) for
the pinned rule.

```java
sw.setOnColor(new Color(0.20f, 0.70f, 0.35f, 1f));
sw.setOnColor(new LinearGradient(Color.GREEN, Color.CYAN, 0f)); // or any paint
```

Between two plain colors the track blends them. When either is a gradient, the on paint is drawn over the off paint
at the opacity the knob has travelled, which looks the same for opaque paints.

The switch draws its own track, so its `getStyling()` background and border are unused.

> **Do not call `onMouseClick` on a switch.** The constructor registers an internal handler that toggles it, and replacing it
> breaks the widget. Use `onToggle`.

## Method reference

| Group | Methods |
|---|---|
| State | `isOn`, `setOn`, `toggle`, `onToggle` |
| Look | `get/setOffColor`, `get/setOnColor`, `get/setKnobColor` |
