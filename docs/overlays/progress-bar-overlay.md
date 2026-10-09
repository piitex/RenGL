# ProgressBarOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.ProgressBarOverlay` is a horizontal bar that **fills left to right** as a task
progresses, or **sweeps continuously** when the amount of work is unknown.

The bar itself does very little. It holds a progress value in `[0, 1]` and draws a track plus a fill.
Every moving part is a [`Transition`](../animation/transitions.md).

```java
ProgressBarOverlay bar = new ProgressBarOverlay(300, 10);
bar.setFillColor(Color.BLUE); // or a gradient: bar.setFillColor(new LinearGradient(Color.BLUE, Color.CYAN))
layout.addElement(bar);

bar.setProgress(0.25); // jump to 25%
```

## Constructors

| Constructor | Result |
|---|---|
| `ProgressBarOverlay()` | 200 × 10 bar at 0% |
| `ProgressBarOverlay(double width, double height)` | |

## Progress

```java
bar.setProgress(0.6); // jump; clamped to [0, 1]; cancels a running animateTo
double p = bar.getProgress(); // the value being drawn (mid-tween: the in-between value)

bar.animateTo(1.0, 3f); // ease to 100% over 3 seconds (EASE_IN_OUT)
bar.animateTo(1.0, 3f, Easing.EASE_OUT, () -> System.out.println("done"));
```

- `animateTo` replaces any tween already running. It starts from wherever the bar currently is, so it is safe to
  call repeatedly (for example on every incoming update).
- Any [`Easing`](../animation/transitions.md#easing): a constant such as `EASE_OUT`, or any lambda `t -> ...`.
- The completion callback fires when the tween reaches its target, not when it is cancelled.

## Indeterminate mode

When you do not know how long something will take, run the looping sweep:

```java
bar.setIndeterminate(true); // a segment bounces back and forth across the track
bar.setIndeterminate(false); // back to the determinate fill
boolean loading = bar.isIndeterminate();
```

`setProgress` and `animateTo` both leave indeterminate mode.

A common pattern is to fill up and then hand over to the sweep:

```java
bar.animateTo(1.0, 3f, Easing.EASE_OUT, () -> bar.setIndeterminate(true));
```

## Look

```java
bar.setFillColor(Color.GREEN); // pins the fill color
bar.getStyling().setCornerRadius(5f); // ends round to this, capped at half the bar's height
bar.getStyling().setBackgroundColor(Color.DARK_GRAY); // the track
```

The track uses the theme's scroll-track color, and the fill the scroll-thumb color.

## Striped fill

A moving diagonal-stripe ("barber pole") fill: the fill color and the stripe color alternate.

```java
bar.setFillColor(new Color(0.2f, 0.7f, 0.3f, 1f));
bar.setStripes(Color.LIGHT_GRAY); // default width and speed
bar.setStripes(Color.LIGHT_GRAY, 12f, -20f); // stripe width, speed (negative moves left)
bar.clearStripes(); // back to a solid fill
```

For full control, pass a [`StripeStyle`](../styling/colors-and-paints.md). The bar reads it every frame, so edits apply live:

```java
StripeStyle stripes = new StripeStyle()
        .setColor(Color.LIGHT_GRAY)
        .setWidth(10f) // points per stripe (the gap is the same)
        .setSpeed(40f); // points per second; negative moves left
bar.setStripes(stripes);

stripes.setAnimated(false); // freeze the stripes where they are (they resume from there)
stripes.setEnabled(false); // hide them but keep every setting; setEnabled(true) brings them back
```

| Switch | Effect |
|---|---|
| `StripeStyle.setEnabled(boolean)` | Draw the stripes or not |
| `StripeStyle.setAnimated(boolean)` | Move them or hold them still |
| `bar.clearStripes()` | Remove the style entirely |

The stripes appear only over the filled part, so they work at any progress. The rounded ends stay in the plain fill
color. It is driven by a [`StripeTransition`](../animation/transitions.md), and it is hidden while the bar is indeterminate.

## Custom effects

Any extra effect (a shimmer, a pulse, a glow at the leading edge) is your own [`Transition`](../animation/transitions.md), started with
`playTransition`. It can read `getProgress()` and `getFillWidth()` to line itself up with the fill.

```java
bar.playTransition(new Transition() {
    private float time;

    @Override
    public void update(Element element, float delta) {
        time += delta;
    }

    @Override
    public void render(Renderer renderer, Element element) {
        // A small bright dot riding the leading edge of the fill.
        float x = (float) (element.getX() + bar.getFillWidth());
        float y = (float) (element.getY() + element.getHeight() / 2);
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 6);
        renderer.drawCircle(x, y, 3f + 2f * pulse, Color.WHITE);
    }

    @Override
    public boolean isFinished() {
        return false; // never finishes on its own
    }
});
```

There is no method to remove a running transition from outside. Return `true` from `isFinished()` (for example, when a flag you set becomes true) when you want it to stop.

## Method reference

| Method | Description |
|---|---|
| `getProgress()` / `setProgress(double)` | Current value in `[0, 1]` |
| `animateTo(double, float)` | Eased tween with default easing |
| `animateTo(double, float, Easing, Runnable)` | Tween with easing and completion callback |
| `isIndeterminate()` / `setIndeterminate(boolean)` | Looping sweep |
| `setStripes(Color)` / `setStripes(Color, float, float)` / `setStripes(StripeStyle)` | Moving stripes over the fill |
| `getStripes()` / `clearStripes()` / `isStriped()` | The live style, removal, and whether stripes are showing |
| `getFillWidth()` | Width in points of the fill as drawn |
| `getFillColor()` / `setFillColor(Paint)` | Fill color or gradient; setting pins it |
| `applyAnimatedProgress(double)` | Used by [`ProgressTransition`](../animation/transitions.md#built-in-transitions-used-by-widgets); prefer `setProgress` |
