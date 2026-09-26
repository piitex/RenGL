# Scheduler

`Scheduler` runs timers and background work for the UI. It covers what JavaFX code usually reaches for with
`PauseTransition`, `Timeline`, `Platform.runLater` and `Task`.

Every callback runs on the **render thread**, so it can create, move and remove elements and call any
[`Window`](window.md) method directly. Nothing runs until `Engine.start` is looping, so you can schedule tasks
before the window opens.

| Method | What it does | JavaFX equivalent |
|---|---|---|
| `Scheduler.runLater(Runnable)` | Runs on the render thread next frame. Safe from any thread. | `Platform.runLater` |
| `Scheduler.after(seconds, Runnable)` | Runs once after a delay. | `PauseTransition` |
| `Scheduler.every(seconds, Runnable)` | Repeats until cancelled. | `Timeline` with `INDEFINITE` |
| `Scheduler.every(initialDelay, interval, Runnable)` | Same, with a different first delay. | |
| `Scheduler.async(Supplier<T>)` | Runs blocking work on a worker thread. | `Task<T>` |

All of them return a `Task` you can `cancel()`. `async` returns an `AsyncTask<T>`, which adds `onSuccess` and
`onError`. Those callbacks are delivered on the render thread.

## Splash screen

```java
window.addContainer(splash);
Scheduler.after(3f, () -> {
    window.removeContainer(splash);
    window.addContainer(main);
});
```

Cancel it if the user can skip the splash:

```java
Task auto = Scheduler.after(3f, this::showMain);
skipButton.onAction(() -> { auto.cancel(); showMain(); });
```

## Loading in the background

Do the slow part in `async` and touch the UI only in the callbacks:

```java
Scheduler.async(() -> loadSaveFile(path)) // worker thread, must not touch the UI
         .onSuccess(save -> showMenu(save)) // render thread
         .onError(e -> status.setText("Load failed: " + e.getMessage()));
```

Cancelling an `AsyncTask` interrupts the worker and drops its callbacks.

## Repeating timers

```java
Task clock = Scheduler.every(1f, () -> time.setText(LocalTime.now().toString()));
// later
clock.cancel();
```

A repeating timer fires at most once per frame. If the app falls behind it does not fire a backlog of missed runs.

## Things to know

- Timers count **frame delta**, the same clock the [transitions](../animation/transitions.md) use, so they stay in
  step with animations. Delta is capped at 0.25 seconds per frame, so a debugger pause or GC stall delays timers
  instead of firing them all at once.
- Timers run on real time: they ignore the [game time](../game/game-time.md) scale and keep going while the game is
  paused. For a timer that belongs to the game, use a [scene's](../game/scenes.md#timers) `after`/`every`, which
  follow game time and stop while the scene is paused.
- Timer precision is one frame. Use a [`Transition`](../animation/transitions.md) for smooth per-frame animation.
- An exception thrown by a callback is logged and does not stop the engine or other tasks.
- `Scheduler.shutdown()` is called by the engine on exit. It drops pending tasks and stops the worker threads.
