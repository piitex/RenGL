# LoadingSpinnerOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.LoadingSpinnerOverlay` is a "working on it" indicator: a ring of dots with a
fading comet trail chasing around it. Use it when you do not know how long something will take. If you do know
the amount done, use a [`ProgressBarOverlay`](progress-bar-overlay.md) instead.

```java
LoadingSpinnerOverlay spinner = new LoadingSpinnerOverlay(32);
layout.addElement(spinner);
```

It starts spinning as soon as it is constructed.

## Constructors

| Constructor | Result |
|---|---|
| `LoadingSpinnerOverlay()` | 32 × 32, spinning |
| `LoadingSpinnerOverlay(double size)` | `size` × `size`, spinning |

It draws in the smaller of its width and height. It has no track or box of its own (only the dots are drawn).

## Control

```java
spinner.setSpinning(false); // stop; a stopped spinner draws nothing
spinner.setSpinning(true); // start again
boolean spinning = spinner.isSpinning();

spinner.setEnabled(false); // hide it entirely (also stops its update and hit-testing)
```

`setSpinning(false)` keeps the overlay in its layout slot but draws nothing. Use `setEnabled(false)` to hide it
completely. A layout still reserves space for a disabled child, so the position of later children does not
change.

## Color

```java
spinner.setColor(Color.BLUE); // pins the dot color
```

By default the dots use the theme's scroll-thumb color. Setting a color pins it against theme switches.

## Example: toggle while loading

```java
LoadingSpinnerOverlay busy = new LoadingSpinnerOverlay(24);
busy.setSpinning(false);
layout.addElement(busy);

ButtonOverlay load = new ButtonOverlay("Load", 100, 32);
load.onAction(() -> {
    busy.setSpinning(true);
    // ... start your work; when it finishes, call busy.setSpinning(false) from an onUpdate hook
});
layout.addElement(load);
```

Because callbacks run on the render thread, if your loading work is on another thread, flip a flag from that
thread and apply it on the render thread from an `onUpdate` hook:

```java
AtomicBoolean done = new AtomicBoolean(false);
busy.onUpdate(event -> {
    if (done.get()) {
        busy.setSpinning(false);
    }
});
new Thread(() -> {
    // ... long-running work ...
    done.set(true);
}).start();
```

## Overlaying a spinner on other content

`StackLayout` layers children. See [StackLayout](../layouts/stack-layout.md) for a spinner pinned to the end of
a button.

## Method reference

| Method | Description |
|---|---|
| `isSpinning()` / `setSpinning(boolean)` | Start or stop the animation |
| `getColor()` / `setColor(Color)` | Dot color; setting pins it |
