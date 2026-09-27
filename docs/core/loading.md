# Loading

[Documentation](../README.md) › [Core](README.md)

A `Loader` (`me.piitex.engine.scheduler`) loads things in the background in named steps, and reports how far it has
got. Use it for a game's loading screen, a splash screen, or any long startup behind a progress bar. It only does the
work and keeps count. What the screen looks like is up to you.

```java nocompile
Loader loader = new Loader()
        .add("Map", progress -> map = Map.read(path))              // worker thread
        .add("Sounds", 3f, progress -> {                           // weighs 3 times as much as the others
            for (int i = 0; i < files.size(); i++) {
                sounds.add(Sound.load(files.get(i)));
                progress.set(i + 1, files.size());                 // progress within the step
            }
        })
        .addOnRenderThread("Scene", progress -> level = new Level(map, sounds))
        .onComplete(() -> scenes.set(level, SceneTransition.fade(0.4f)))
        .onError(error -> status.setText("Couldn't load: " + error.getMessage()))
        .start();
```

## Steps

| Method | Runs on |
|---|---|
| `add(name, step)` | A worker thread. Must not touch the UI. |
| `addOnRenderThread(name, step)` | The render thread, one such step per frame so the screen keeps drawing between them. |

Both take an optional `weight` after the name, 1 by default. It is the step's share of the progress bar: give a step
that takes three times as long a weight of 3. A step with a weight of 0 doesn't move the bar.

Steps run one at a time, in the order they were added, each after the previous one has finished. Whatever a step
stores in a field can be used by the steps after it and by the `onComplete` callbacks, with no extra
synchronization. To load several files at once, do it inside one step.

A step receives a `Progress`:

| Method | What it does |
|---|---|
| `set(fraction)` | How far through this step, 0 to 1. |
| `set(done, total)` | The same, as `done` parts of `total`. |
| `isCancelled()` | True once the loader was cancelled. A long step should check it and return. |

A step may throw anything, including checked exceptions like `IOException`.

## Showing progress

Read these each frame, for example from the loading scene's `update`:

| Method | Value |
|---|---|
| `getProgress()` | 0 to 1, weighted by the steps' weights. Never goes backwards. |
| `getCurrentStep()` | The name of the step running now; null before `start()` and once loading has ended. |
| `getCompletedSteps()`, `getStepCount()` | Steps finished, and steps in total. |
| `isComplete()`, `getError()` | Whether every step finished, and the error that stopped loading, if any. |

A loading screen is an ordinary [scene](../game/scenes.md):

```java nocompile
public class LoadingScreen extends Scene {
    private final ProgressBarOverlay bar = new ProgressBarOverlay(360, 10);
    private final Loader loader;

    public LoadingScreen(Loader loader) {
        this.loader = loader;
    }

    @Override
    protected void onEnter() {
        getContainer().addElement(bar);
        loader.start();
    }

    @Override
    protected void update(float delta) {
        bar.setProgress(loader.getProgress());
    }

    @Override
    protected void onExit() {
        loader.cancel(); // no effect once loading has finished
    }
}
```

`GameLoopDemo` in `me.piitex.examples.demo` has a complete one, with the step name under the bar.

## Ending

| Outcome | What happens |
|---|---|
| Every step finished | `onComplete` callbacks run. |
| A step threw | Loading stops at that step and `onError` callbacks run with the error. With none registered, it is logged. |
| `cancel()` | Loading stops after the current step, and a step on a worker thread is interrupted. No callback runs. |

Callbacks always run on the render thread, on the frame after loading ended, so they can change scenes and touch the
UI. One added after loading has ended still runs if its outcome happened.

## Things to know

- A loader runs once. `start()` a second time, or adding a step after `start()`, throws.
- Everything except the steps themselves can be called from any thread.
- Worker steps share the [`Scheduler`](scheduler.md)'s worker threads, which `Scheduler.shutdown()` stops when the
  engine exits.
- `Loader` extends `Task`, so `isDone()` and `isCancelled()` work as for other scheduled work.
