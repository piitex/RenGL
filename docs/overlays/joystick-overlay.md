# JoystickOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.JoystickOverlay` is an on-screen thumb stick, for a touch screen or a mouse-driven game that wants to steer
with a stick: press it, drag, and read how far and which way the thumb is pushed.

```java
JoystickOverlay stick = new JoystickOverlay(140);     // diameter of the base
stick.setDeadZone(0.15);
hud.addElement(stick);

// every frame, in the game loop
player.move(stick.getAxisX(), stick.getAxisY());
```

## Reading it

Like a gamepad stick it is read by polling.

| Call | Gives |
|---|---|
| `getAxisX()` | -1 (left) to 1 (right) |
| `getAxisY()` | -1 (down) to 1 (up): **positive is up**, whatever way the screen's y grows |
| `getMagnitude()` | How hard it is pushed, 0 to 1 |
| `getAngle()` | The direction in degrees: 0 right, 90 up, 180 left, 270 down; 0 at rest |
| `isActive()` | Pushed past the dead zone |

`onMove((x, y) -> ...)` reports changes instead, for code that does not poll, and `onRelease(Runnable)` runs when the press ends.

## Dead zone and directions

A push inside the **dead zone** (0.1 by default, up to 0.9) reads as 0, and the range outside it is stretched so it starts at 0 right at
the edge rather than with a jump.

`setDirections(4)` or `setDirections(8)` turns the stick into a digital pad: it reports only that many directions, at full push, snapped
to the nearest. Any other number is that many evenly spaced directions, and `0` is analog again.

## Settings

```java
stick.setSnapBack(false);       // stay where it was left instead of springing to the center
stick.setThumbRatio(0.35);      // the thumb's radius as a fraction of the base's
stick.setAxes(0.5, 0.5);        // show a value from elsewhere, e.g. a gamepad; silent
stick.setThumbColor(Color.ORANGE);
stick.setBaseColor(new Color(0, 0, 0, 0.3f));
stick.setRingColor(Color.WHITE);
```

Colors come from the theme: the base from the field background and border, the thumb from the accent.
