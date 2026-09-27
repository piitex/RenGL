# Tweens and sequences

[Documentation](../README.md) › [Animation](transitions.md)

A `Tween` moves any value from one number, point or color to another over time. A `Sequence` plays steps one after
another: "wait 2 seconds, then open the door, then zoom in". Both are in `me.piitex.engine.scheduler`.

[Transitions](transitions.md) animate UI elements. Tweens are for everything else: a camera, a sprite, a score
counting up, a value your `render` reads.

## Playing one

Nothing moves until you play it, and where you play it decides which clock it runs on:

| Call | Clock | Paused with the game? |
|---|---|---|
| `scene.play(playable)` | The scene's [game time](../game/game-time.md): follows the time scale | Yes, and cancelled when the scene is removed |
| `Scheduler.play(playable)` | Real time, like the [`Scheduler`](../core/scheduler.md) timers | No |

Both return a `Task` to `cancel()`. For a tween or a sequence, that is the object itself.

```java nocompile
public class Level extends Scene {
    private float flash;

    @Override
    protected void onEnter() {
        play(Tween.of(1f, 0f, 0.5f, v -> flash = v)); // fade a white flash out over half a second
    }
}
```

Played from the render thread, which includes every scene hook and UI callback, a tween starts at once, so its
starting value is set before the next frame is drawn. Played from another thread, it starts on the next frame.

## Tweens

```java nocompile
Tween.of(0f, 1f, 0.4f, v -> alpha = v);                            // a number, from and to
Tween.to(camera::getZoom, camera::setZoom, 2f, 0.5f);               // from wherever it is now
Tween.of(Color.WHITE, Color.RED, 1f, Lerp.COLOR, c -> tint = c);    // any type, with a Lerp
Tween.of(start, end, 2f, Lerp.VEC2, p -> sprite.setPosition(p.x(), p.y()));
```

| Factory | What it tweens |
|---|---|
| `of(from, to, seconds, setter)` | A `float`. `setter` is `v -> field = v` or a method such as `camera::setZoom` |
| `to(getter, setter, to, seconds)` | A `float`, starting from what `getter` reads when the tween begins |
| `of(from, to, seconds, lerp, setter)` | Any type, blended by a `Lerp`: `Lerp.VEC2`, `Lerp.COLOR`, or your own `(from, to, t) -> ...` |
| `to(getter, setter, to, seconds, lerp)` | Any type, starting from what `getter` reads |

Options chain, and are set before playing:

| Option | Effect |
|---|---|
| `easing(Easing)` | The curve the value follows; `LINEAR` by default. See [Easing](transitions.md#easing) |
| `delay(seconds)` | Wait first. Nothing is set during the wait, and `to` reads its start value when the wait ends |
| `repeat(times)` | Play this many times in all |
| `loop()` | Play until cancelled |
| `yoyo()` | Every second play runs backwards: `repeat(2).yoyo()` goes there and back |
| `onComplete(Runnable)` | Runs once the end value is set. Not called when cancelled, nor for a `loop` |

```java nocompile
// A coin that bobs forever
play(Tween.of(0f, -6f, 0.6f, v -> coinOffset = v).easing(Easing.EASE_IN_OUT).loop().yoyo());

// A score that counts up, then pops
play(Tween.of(shown, score, 0.8f, v -> shown = v).easing(Easing.EASE_OUT)
        .onComplete(() -> play(Tween.of(1.3f, 1f, 0.3f, v -> scoreScale = v).easing(Easing.EASE_OUT_BACK))));
```

`cancel()` stops a tween where it is. A tween that finished can be played again, and it starts over. A cancelled one
cannot.

## Sequences

```java nocompile
play(new Sequence()
        .delay(2f)
        .run(door::open)
        .then(Tween.to(camera::getZoom, camera::setZoom, 2f, 1f).easing(Easing.EASE_IN_OUT))
        .waitUntil(() -> player.x > door.x)
        .run(door::close)
        .onComplete(() -> saveCheckpoint()));
```

| Method | Adds |
|---|---|
| `then(playable)` | Any step: a tween, another sequence, your own `Playable` |
| `delay(seconds)` | A wait |
| `run(Runnable)` | An action that runs and moves straight on |
| `waitUntil(condition)` | A wait until the condition is true, checked once per update |
| `parallel(playables...)` | Several steps at once. It moves on when the last one finishes |
| `repeat(times)`, `loop()` | Plays the whole sequence again |
| `onComplete(Runnable)` | Runs when the last step finishes. Not called when cancelled, nor for a `loop` |

Things to know:

- **Timing is exact.** Time a step doesn't use goes on to the next, so `delay(0.3f)` then a tween, updated by 0.5
  seconds, leaves the tween 0.2 seconds in. A sequence plays out the same at any frame rate.
- Steps that take no time, such as `run`, all happen on the same update. A looping sequence whose steps take no time
  at all goes round once per update rather than forever.
- A `run` action that throws is logged, and the sequence carries on.
- A tween cancelled while it is a step in a sequence is skipped, and the sequence moves on.
- A step is started each time the sequence reaches it, so a `Tween.to` inside a repeating sequence reads its start
  value afresh each time round.

## Your own steps

`Playable` is the interface every step implements. `update(delta)` returns `Playable.RUNNING` while there is more to
do, and once it has finished, the part of `delta` it did not use:

```java nocompile
Playable waitForAnimation = delta -> player.isFinished() ? delta : Playable.RUNNING;

play(new Sequence().run(() -> player.play(attack)).then(waitForAnimation).run(this::dealDamage));
```

A step with state resets it in `start()`, which is called each time the step begins. The static helpers
`Playable.delay`, `Playable.run`, `Playable.waitUntil` and `Playable.parallel` build the same steps the sequence
methods add. `update` may be called with a `delta` of 0, when a step starts at once.

## Things to know

- A tween or sequence plays in one place at a time. Don't play the same one twice at once, or add it to two
  sequences.
- A tween's setter, or your own `Playable`, that throws is logged, and that tween or sequence stops.
- Everything runs on the render thread, so setters can change elements and anything else the frame uses.
- `scene.after` and `every` timers, tweens and sequences all run together on the scene's clock, before the scene's
  `update`. See [Scenes](../game/scenes.md#timers-tweens-and-sequences).
