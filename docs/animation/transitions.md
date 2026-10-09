# Animation and transitions

[Documentation](../README.md) › Animation

RenGL animates elements through **transitions**: small self-contained objects you attach to any element with
`playTransition`. The engine drives each one every frame until it says it is finished, then drops it. To animate
anything else, such as a camera, a sprite or a number your game draws, use a
[tween or a sequence](tweens-and-sequences.md).

```java
element.fadeIn(0.3f); // built-in convenience
element.playTransition(new FadeTransition(0.5f, FadeTransition.Direction.IN)); // any Transition
```

Everything time-based in RenGL is driven by **delta time**: the seconds since the previous frame (capped at
0.25 s). Multiply speeds by it and your animation runs at the same real-world speed on every machine.

```java
double x = 0, speedPerSecond = 200;
float delta = 1f / 60f; // seconds since the last frame (from the update event)
x += speedPerSecond * delta; // correct at 30, 60, or 240 fps
```

## The Transition interface

```java nocompile
public interface Transition {
    void update(Element target, float delta); // advance and apply the effect
    default void renderBackground(Renderer r, Element target) {} // draw BEHIND the element
    void render(Renderer r, Element target); // draw ON TOP of the element
    boolean isFinished(); // done? then it is dropped
    default boolean stop() { return false; } // stop early; true if it was running
    default boolean isLocking() { return false; } // lock the game while it plays?
    default boolean isSkippable() { return false; } // may a key press or click end it?
    default boolean finish(Element target) { return false; } // end it now, in its final state
}
```

Each frame, for every running transition:

1. `update(target, delta)` runs during the update pass.
2. `renderBackground` runs before the element draws.
3. The element draws.
4. `render` runs after the element draws, outside any opacity layer.

When `isFinished()` returns `true`, the transition still gets **one more `render` call** at its final state and is
then removed. Transitions are updated only while the element is enabled and the engine is updating it.

### Stopping one early

`transition.stop()` ends a transition where it is, leaving the element as it currently is and skipping any completion
callback. It returns `true` if the transition was running and is now stopped, and `false` if it could not be stopped
(already finished or stopped, or a custom transition that does not override `stop()`). It never throws.

```java nocompile
FadeTransition fade = panel.fadeOut(2f);
boolean stopped = fade.stop(); // panel stays at whatever opacity it had reached
```

### Finishing one early

`transition.finish(target)` ends a transition **as if its time were up**: the element is left in the final state and
the completion callback fires. It returns `true` if it was running, and `false` if it had already ended or the
transition cannot be finished (the default, and `SweepTransition`, `StripeTransition` and `SpinTransition`, which never
end). `stop()` and `finish()` differ in where the element is left:

| | `stop()` | `finish(target)` |
|---|---|---|
| Element is left | Where it had got to | In the final state |
| `onComplete` | Skipped | Fires |

`element.finishTransitions()` finishes every running transition on one element, and `element.hasLockingTransition()`
says whether any of them locks the game.

### Locking the game

A transition can hold the game still until it ends, for a title card, a level intro or a fade to black. This is
**off by default**; a transition only locks if you ask. `FadeTransition`, `ScaleTransition` and `ProgressTransition`
have the switches, which chain off the constructor or off `fadeIn`/`fadeOut`:

```java
title.fadeIn(2f).lock();               // frozen until the fade is over
title.fadeIn(2f).lock().skippable();   // ...unless the player presses a key or clicks
card.playTransition(new ScaleTransition(0.5f, 1f, 0.4f, Easing.EASE_OUT, null).lock());
```

While a locking transition is playing on an enabled element, the window:

- Does not update the [scenes](../game/scenes.md): no `fixedUpdate`, `update` or timers. Elements still animate, and
  so does the `Scheduler`, so the picture keeps moving.
- Ignores key presses and repeats, typed characters, mouse button presses, the wheel and hover. Key and button
  **releases** still get through, so nothing is left stuck down.
- With `skippable()`, ends the transition on a fresh key press or mouse button press (not a held key's repeat and not
  the wheel), as `finish` does. That press is used up skipping; it does not also reach the game.

`window.isInputLocked()` says whether anything is locking, a [scene transition](../game/transitions.md#locking-the-game-and-skipping)
or an element one. Things to know:

- The window looks for locking transitions once a frame, so one is noticed on the frame after it starts.
- A disabled element, or one inside a disabled container, does not lock, because its transitions do not advance.
- `skippable()` does nothing without `lock()`. A transition that is not locking never blocks or skips anything.
- A locking transition has to end, or the window stays locked. Give it a finite duration, and do not lock a looping
  one. Make anything that locks skippable unless it is short.
- Your own `Transition` opts in by overriding `isLocking()`, `isSkippable()` and `finish(target)`. Extend
  `LockableTransition<T>` to get the `lock()` and `skippable()` switches and a `finish` built on one method,
  `jumpToEnd()`, which sets the transition to its last moment.

Prefer a transition to an `onUpdate` hook for animation. An element holds only **one** `onUpdate` listener
(registering a second replaces the first), while it can run any number of transitions at once.

## Fades

```java
element.fadeIn(0.3f); // transparent -> opaque
element.fadeIn(0.3f, () -> System.out.println("shown")); // with a completion callback
element.fadeOut(0.3f); // opaque -> transparent
element.fadeOut(0.3f, () -> element.setEnabled(false));

element.fadeIn(Color.BLACK, 1f); // dissolve out of a solid color
element.fadeOut(Color.WHITE, 1f); // dissolve into a solid color
```

All eight overloads return the `FadeTransition`, so you can read `getProgress()` (0 to 1, regardless of
direction), `getDirection()` (`FadeTransition.Direction.IN` or `OUT`) and `getColor()`.

Things to know:

- A fade changes the element's **overall opacity**. The entire element, including background, border, content and
  all children, fades **together as one unit** (it is composited in an offscreen layer, so overlapping parts do not
  double-blend).
- A fully transparent element is **not drawn, but is still enabled and clickable.** Disable it in the completion
  callback if it should stop interacting.
- Starting a fade while another is running **replaces** the running one. Clicking mid-fade is safe.
- The color versions crossfade with a solid color instead of plain transparency.
- Layers are skipped at full opacity, so a normal, non-fading element pays nothing.

You can also set a static opacity: `element.setOpacity(0.5f)`.

### Chaining

The completion callback runs on the frame the fade finishes, so chaining is just nesting:

```java
panel.fadeOut(0.25f, () -> {
    panel.setEnabled(false);
    next.setEnabled(true);
    next.fadeIn(0.25f);
});
```

#### Running something after any transition

A transition built ahead of time, such as a scene's end and start transitions, has no callback of its own. Give `playTransition` a second argument and it runs once the transition is done, whatever its type:

```java
// End transition first, then swap scenes and play the start transition.
Runnable show = () -> {
    scene.render(window, true, events);
    scene.getContainer().playTransition(scene.getStartTransition());
};
if (current != null && current.getEndTransition() != null) {
    current.getContainer().playTransition(current.getEndTransition(), show);
} else {
    show.run();
}
```

It runs when the transition runs out or is ended early (a skip, `finishTransitions()`). It does not run if the transition is `stop()`ped or the element is dropped first, and it runs at once for a transition that is already finished. Without it, both transitions start in the same frame, since `playTransition` returns immediately.

## ScaleTransition

`ScaleTransition` animates an element's [scale](../core/element.md#scale) from one value to another, shaped by an
`Easing`. The element and all of its children are drawn scaled around the element's scale origin, which is its
center unless you set one with `setScaleOrigin`.

```java
// Pop in: grow from 80% to full size while fading in.
card.playTransition(new ScaleTransition(0.8f, 1f, 0.2f, Easing.EASE_OUT, null));
card.fadeIn(0.2f);

// Shrink into the top-left corner, then hide.
card.setScaleOrigin(card.getX(), card.getY());
card.playTransition(new ScaleTransition(1f, 0f, 0.3f, Easing.EASE_IN, () -> card.setEnabled(false)));
```

- Constructor: `ScaleTransition(from, to, durationSeconds, easing, onComplete)`. A `null` easing is `LINEAR`, and
  `onComplete` is optional (`null` for none). It runs once, on the frame the transition finishes.
- Scaling only changes how the element is drawn. Hit-testing, layout and the element's size stay the same.
- It runs next to a fade, since scale and opacity are separate properties. Unlike fades, starting a second
  `ScaleTransition` does not replace the first, so `stop()` the old one if you retarget mid-animation.
- `stop()` leaves the element at the scale it had reached and skips `onComplete`.
- [`ModalContainer`](../containers/modal-container.md#close-animations)'s `shrink` close animation is built on it.

## Easing

`Easing` reshapes a linear 0-1 time into an eased 0-1 progress curve.

```java
Easing linear = Easing.LINEAR; // constant speed
Easing easeIn = Easing.EASE_IN; // slow start, fast end (t * t)
Easing easeOut = Easing.EASE_OUT; // fast start, slow settle
Easing easeInOut = Easing.EASE_IN_OUT; // slow at both ends

Easing custom = t -> t * t * t; // any lambda; should map 0 to 0 and 1 to 1
float y = Easing.EASE_OUT.apply(0.5f);
```

More curves:

| Constant | Shape |
|---|---|
| `EASE_IN_CUBIC`, `EASE_OUT_CUBIC`, `EASE_IN_OUT_CUBIC` | As the plain ones, more strongly |
| `EASE_OUT_BACK` | Shoots about 10% past the end, then settles back: a pop |
| `EASE_OUT_ELASTIC` | Wobbles past the end and back before it settles, like a spring |
| `EASE_OUT_BOUNCE` | Lands and bounces a few times, each lower |

`EASE_OUT_BACK` and `EASE_OUT_ELASTIC` go outside 0-1 on the way, so a value eased with them overshoots its target.

It is a functional interface (`float apply(float t)`). `ProgressBarOverlay.animateTo`, [`Tween`](tweens-and-sequences.md)
and [scene transitions](../game/transitions.md) take one. Your own transitions can use it the same way, and
[`MathUtil.progress(time, delay, duration, easing)`](../game/geometry.md#mathutil) eases a value worked out from a
clock.

## Built-in transitions used by widgets

| Class | Used by | What it does |
|---|---|---|
| `FadeTransition` | `Element.fadeIn/fadeOut` | Fades opacity, optionally through a solid color |
| `ScaleTransition` | `ModalContainer.CloseAnimation.shrink`, and your own code | Tweens `Element.setScale` with an `Easing`; see [above](#scaletransition) |
| `ProgressTransition` | `ProgressBarOverlay.animateTo` | Tweens a bar's progress with an `Easing`; `stop()` (or `cancel()`) stops it without firing its callback |
| `SweepTransition` | `ProgressBarOverlay.setIndeterminate` | Looping bouncing segment; `stop()` ends it |
| `StripeTransition` | `ProgressBarOverlay.setStripes` | Advances the stripe offset; the bar draws the stripes. `stop()` ends it |
| `SpinTransition` | [`LoadingSpinnerOverlay`](../overlays/loading-spinner-overlay.md) | Looping ring of dots with a fading trail; `stop()` ends it |

`SweepTransition` and `SpinTransition` never finish on their own. They draw from `render` rather than changing a
property, which is the seam any custom "draw something animated over this element" effect uses.

## Writing a custom transition

Implement `Transition`, do the work in `update`, and return `true` from `isFinished()` when done.

### A slide-in

```java
public class SlideIn implements Transition {
    private final double fromX;
    private final double toX;
    private final float duration;
    private final Easing easing;
    private float elapsed;

    public SlideIn(double fromX, double toX, float duration, Easing easing) {
        this.fromX = fromX;
        this.toX = toX;
        this.duration = duration;
        this.easing = easing;
    }

    @Override
    public void update(Element target, float delta) {
        elapsed = Math.min(duration, elapsed + delta);
        double t = easing.apply(elapsed / duration);
        double x = fromX + (toX - fromX) * t;
        if (target instanceof Container container) {
            container.moveTo(x, container.getY()); // respects drag bounds and collision
        } else {
            target.setX(x);
        }
    }

    @Override
    public void render(Renderer renderer, Element target) {
        // nothing to draw; the effect is the movement
    }

    @Override
    public boolean isFinished() {
        return elapsed >= duration;
    }
}
```

Play it:

```java
panel.playTransition(new SlideIn(-300, 40, 0.4f, Easing.EASE_OUT));
```

Children are positioned relative to their container, so `setX` moves a container's contents too. `Container.moveTo` additionally applies drag bounds and collision.

### A pulse drawn on top

```java
public class Pulse implements Transition {
    private float time;

    @Override
    public void update(Element target, float delta) {
        time += delta;
    }

    @Override
    public void render(Renderer renderer, Element target) {
        float a = 0.25f + 0.25f * (float) Math.sin(time * 5f);
        renderer.drawBorder((float) target.getX(), (float) target.getY(),
                (float) target.getWidth(), (float) target.getHeight(),
                new Color(1f, 1f, 0.3f, a), 3f, target.getStyling().getCornerRadius());
    }

    @Override
    public boolean isFinished() {
        return false; // loops until the program ends
    }
}
```

```java
button.playTransition(new Pulse());
```

## Animation without transitions

For a one-off, an `onUpdate` hook and delta time is enough:

```java
container.onUpdate(event -> {
    double x = container.getX() + 250 * event.getDelta();
    if (x > window.getWindowOptions().getWidth() - container.getWidth()) x = 0;
    container.moveTo(x, container.getY());
});
```

## Animated paints and backgrounds

Two other kinds of motion do not use transitions:

- [`ScrollingGradient`](../styling/colors-and-paints.md#scrolling-animated-gradients) and other [`AnimatedPaint`](../styling/colors-and-paints.md#animatedpaint)s advance once per frame automatically. See
  [Colors and paints](../styling/colors-and-paints.md). [`RainbowColor`](../styling/colors-and-paints.md#rainbowcolor-rgb-cycling)
  cycles its hue from a clock instead, so it needs no advancing and can be shared.
- [`Background`](../styling/backgrounds.md) effects have their own `update` and their own `fadeIn`/`fadeOut`. See
  [Backgrounds](../styling/backgrounds.md).

## Method reference

| Member | Description |
|---|---|
| `Element.playTransition(Transition)` | Attach a transition |
| `Element.playTransition(Transition, Runnable)` | Attach it and run the callback once it finishes, including when skipped |
| `Element.isTransitioning()` | Whether any transition is running |
| `Element.hasLockingTransition()` | Whether a running transition locks the game |
| `Element.finishTransitions()` / `skipLockingTransitions()` | Finish every running transition, or only the locking skippable ones |
| `Transition.isLocking()`, `isSkippable()`, `finish(target)` | Lock the game, allow a press to end it, end it in its final state (all off or unsupported by default) |
| `LockableTransition<T>` | Base of the fade, scale and progress transitions: `lock()`, `skippable()` and `finish` |
| `Window.isInputLocked()` | Whether a scene or element transition is locking |
| `Element.fadeIn/fadeOut(...)` | Convenience fades (8 overloads) |
| `Element.getOpacity/setOpacity` | Whole-element opacity (0..1) |
| `Element.getScale/setScale`, `setScaleOrigin`, `clearScaleOrigin` | Whole-element drawing scale (default 1) and the point it scales around |
| `ScaleTransition(from, to, seconds, easing, onComplete)` | Animates the scale |
| `Transition` | `update`, `renderBackground` (default no-op), `render`, `isFinished`, `stop` (default returns `false`) |
| `Easing` | `LINEAR`, `EASE_IN`, `EASE_OUT`, `EASE_IN_OUT`, the `_CUBIC` variants, `EASE_OUT_BACK`, `EASE_OUT_ELASTIC`, `EASE_OUT_BOUNCE`, `apply(float)` |
