# Scenes

[Documentation](../README.md) › [Game](README.md)

A `Scene` (`me.piitex.engine.game`) is one screen or state of a game: the main menu, a level, the pause menu, a
loading screen. The window keeps its scenes in a stack, the `SceneManager` from `window.getSceneManager()`. The top
scene is the one being played. The ones below wait until it is removed.

```java nocompile
SceneManager scenes = window.getSceneManager();
scenes.set(new MainMenu());     // remove every scene, then show this one
scenes.push(new PauseMenu());   // on top of the game, which pauses
scenes.pop();                   // remove the top scene; the game resumes
```

## Writing a scene

Extend `Scene` and override the hooks you need. All of them run on the render thread.

```java nocompile
public class Level extends Scene {
    private float x, prevX;

    @Override
    protected void onEnter() {
        // Build the UI here. The container already fills the window.
        getContainer().addElement(new TextOverlay("Level 1", 20f, Color.WHITE));
        every(2f, this::spawnEnemy); // a timer on game time, stopped while the scene is paused
    }

    @Override
    protected void fixedUpdate(float step) {
        prevX = x;
        x += 200 * step; // 200 points per second, the same on every machine
    }

    @Override
    protected void render(Renderer renderer, float alpha) {
        float drawX = prevX + (x - prevX) * alpha; // smooth between fixed steps
        renderer.drawQuad(drawX, 300, 32, 32, Color.WHITE, 4f);
    }

    @Override
    protected boolean onKey(int key, int action, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
            getSceneManager().push(new PauseMenu());
            return true;
        }
        return false;
    }
}
```

| Hook | When it runs |
|---|---|
| `onEnter()` | The scene was added to the stack. Build its UI here. |
| `onExit()` | The scene was removed. Its container is already off the window, and its timers, tweens, sequences and event subscriptions are cancelled. With a [transition](transitions.md), this comes when the transition ends. |
| `onPause()` / `onResume()` | A scene above started or stopped pausing this one. |
| `fixedUpdate(step)` | At the fixed tick rate (60 per second by default), while the scene is running. For game logic and physics. See [Game time](game-time.md). |
| `update(delta)` | Once per frame after the fixed steps, while the scene is running. `delta` is game time. |
| `render(renderer, alpha)` | Every frame the scene is visible, paused or not, and while a transition draws it going out. Draws below the scene's elements. Draw only; don't change state. |
| `onKey(key, action, mods)` | A key no text field, popup or modal took. Return true to consume it. For controls, polling an [input context](../input/game-input.md) is usually simpler. |
| `onResize(width, height)` | The window changed size. The container has already been resized. To keep elements in place, [anchor](../core/element.md#anchoring) them rather than moving them here. |

`onExit` is not called when the application closes. Save the game from [`window.onClose`](../core/window.md) instead.

## The scene's container

`getContainer()` is an ordinary [`Container`](../containers/container.md) that covers the whole window and follows its
size. Add buttons, text and other elements to it. They are drawn above what `render` draws, get mouse input as usual,
and run on real time, so they keep animating while the scene is paused. It has no background unless you give it one.
[Anchor](../core/element.md#anchoring) them, as the pause menu below does, to keep them in place when the window is
resized.

Scenes are drawn **below** every container added to the window with `addContainer`. A container added directly (an
FPS counter, a debug panel) stays on top of whatever scene is showing, and so do popups, modals and tooltips.

## Stacking: overlays and pausing

Two settings on the scene that is on top decide what happens to the scenes below it.

| Setting | Default | Effect on the scenes below |
|---|---|---|
| `setOverlay(boolean)` | `false` | `true`: they stay visible behind this scene. `false`: they are hidden. |
| `setPausesBelow(boolean)` | `true` | `true`: they are paused (no `update`, `fixedUpdate` or timers). `false`: they keep running. |

A pause menu is an overlay that pauses: the frozen game shows through, dimmed by a translucent background.

```java nocompile
public class PauseMenu extends Scene {
    public PauseMenu() {
        setOverlay(true);
    }

    @Override
    protected void onEnter() {
        getContainer().getStyling().setBackgroundColor(new Color(0f, 0f, 0f, 0.55f));
        ButtonOverlay resume = new ButtonOverlay("Resume", 180, 40);
        resume.setAnchor(Anchor.CENTER); // stays centered when the window is resized
        resume.onAction(() -> getSceneManager().pop());
        getContainer().addElement(resume);
    }
}
```

A HUD or chat box that should run alongside the game is an overlay that doesn't pause: `setOverlay(true)` and
`setPausesBelow(false)`.

`isVisible()` and `isRunning()` tell you where a scene stands, and `isActive()` whether it is on a stack at all.

## Popup scenes

A popup scene, such as a level-up choice, an "are you sure?" box or a tutorial tip, is an overlay that pauses the game.
(It is a scene, not one of the window's own popups, such as a dropdown's list.) A few settings save writing the same
code in each one.

| Method | What it does |
|---|---|
| `setBackdrop(color[, fadeInSeconds])` | Fills the window with `color` behind the scene's drawing, to dim the game below. It fades in from clear over `fadeInSeconds` after the scene enters. Null for none, the default |
| `setInputDelay(seconds)` | For that long after it enters, the scene takes no input, so a click or key meant for the game doesn't pick something in it. See below. 0, the default, for none |
| `isReady()` | Whether the input delay is over |
| `getAge()` | Real seconds since the scene entered, counting on while it is paused or game time is slowed. For animating it as it opens. Starts from 0 each time it is pushed |
| `dismiss()` / `dismiss(transition)` | Removes the scene from its stack. Returns `false` and does nothing if it isn't on one, so a close button and Escape can both call it |
| `queue(scene[, transition])` | Pushes `scene` above this one once this one is on top and no transition is playing. See below |
| `hasQueued()` | Whether scenes this one queued are still waiting |

```java nocompile
public class LevelUpPopup extends Scene {
    public LevelUpPopup() {
        setOverlay(true);
        setBackdrop(new Color(0f, 0f, 0f, 0.5f), 0.25f);
        setInputDelay(0.4f);
    }

    @Override
    protected void onEnter() {
        ButtonOverlay ok = new ButtonOverlay("OK", 180, 40);
        ok.setPosition(390, 300);
        ok.onAction(this::dismiss);
        getContainer().addElement(ok);
    }

    @Override
    protected void render(Renderer renderer, float alpha) {
        renderer.pushAlpha(Math.min(1f, getAge() * 4f)); // fade in over a quarter of a second
        renderer.drawText("LEVEL UP", 480, 200, font, Color.WHITE, TextAlign.CENTER);
        renderer.popAlpha();
    }
}
```

**The input delay.** Until it is over, key presses skip the scene's `onKey`, presses its
[input contexts](../input/game-input.md) bind are dropped (they don't go on to the scenes below), and its elements take
no clicks and show no hover. A key or button held from before counts only once it is pressed again, as with any context
that has just become active.

**The queue.** A game scene calls `queue(popup)` instead of pushing it. The popup is pushed at the end of the frame,
unless something is above the game scene (a pause menu, another popup) or a transition is playing, in which case it
waits until they are gone. Several queued popups show one after another, in order, each once the one before has been
dismissed. Scenes queued but not yet pushed are dropped when the scene that queued them is removed.

```java nocompile
// In the game scene:
queue(new LevelUpPopup());   // over the game at the end of this frame, or once the pause menu closes
queue(new NewWeaponPopup()); // after the level-up popup is dismissed
```

Queuing a popup from `onEnter` of a scene that comes in with a transition shows it once the transition is over.

## Changing scenes

| Method | What it does |
|---|---|
| `push(scene)` | Adds `scene` on top. |
| `pop()` | Removes the top scene and returns it. The one below resumes. |
| `replace(scene)` | Swaps the top scene for `scene`. The scenes below are not resumed in between. |
| `set(scene)` | Removes every scene, then shows `scene`. If `scene` is already on the stack it stays and the others go. |
| `remove(scene)` | Removes `scene` from anywhere in the stack. |
| `clear()` | Removes every scene. |
| `getTop()`, `getScenes()`, `size()`, `isEmpty()` | Inspect the stack. |

Changes apply at once: after `push` returns, the new scene has entered and is `getTop()`. They are safe from any hook,
button handler or timer, including one belonging to the scene being removed. Call them on the render thread, or
before `Engine.start`. From another thread, use [`Scheduler.runLater`](../core/scheduler.md).

The order of calls is: on `push`, the scene below gets `onPause`, then the new one gets `onEnter`. On `pop`, the old
scene gets `onExit`, then the one below gets `onResume`.

`push`, `pop`, `replace`, `set`, `remove` and `clear` also take a [`SceneTransition`](transitions.md), which shows the change
with a fade, slide or wipe:

```java nocompile
scenes.set(new Level(), SceneTransition.fadeThrough(Color.BLACK, 0.6f));
scenes.push(new PauseMenu(), SceneTransition.fade(0.2f));
```

The stack still changes at once. Scenes that leave get `onExit` only when the transition ends, and
`isTransitioning()` tells you whether one is playing. See [Scene transitions](transitions.md).

## Keyboard input

Key presses reach a scene's `onKey` when nothing in the UI wants them: no text field or other element has keyboard
focus, and no popup or modal is open. They go to the running scenes top first, until one returns `true`, skipping any
scene whose [input delay](#popup-scenes) isn't over.

Key releases are always delivered to every scene on the stack, paused or not, even while a text field has focus. A
scene that tracks which keys are held therefore never misses one being let go, for example when the player opens
the pause menu while holding an arrow key.

Tab moves focus between elements while an element has focus or a popup or modal is open. Otherwise it comes to
the scenes first, and moves focus only if no scene consumes it and no [input context](../input/game-input.md) binds it.

### Polling instead

Most game controls are easier to poll than to track from events. Give the scene an
[`InputContext`](../input/game-input.md) with `addInputContext(context)`: it receives input while the scene is running,
before the contexts of the scenes below, and game code asks it in `fixedUpdate` or `update` whether an action is held
or was just pressed. Presses a scene's `onKey` consumes don't reach any context.

| Method | What it does |
|---|---|
| `addInputContext(context)` | The context receives input while this scene runs. The last added gets presses first |
| `removeInputContext(context)` | Removes it |
| `getInputContexts()` | The scene's contexts, in the order they were added |

## Timers, tweens and sequences

`after`, `every` and `every(initialDelay, interval, ...)` on a scene work like the [`Scheduler`](../core/scheduler.md)
ones but run on the scene's game time. So does `play`, which runs a [tween or a sequence](../animation/tweens-and-sequences.md).
They slow down with the [time scale](game-time.md), stop while the scene is paused, and are cancelled when it is
removed. They run each frame just before the scene's `update`. Use `Scheduler` for ones that should keep real time.

```java nocompile
play(new Sequence()
        .run(() -> banner.setText("Wave 2"))
        .delay(2f)
        .run(this::spawnWave));
```

## Events

`subscribe(bus, type, listener)` listens on an [`EventBus`](events.md) until the scene is removed, so a scene that
subscribes in `onEnter` needs no clean-up.

## Things to know

- A scene can be on one stack at a time. Pushing one that is already on a stack throws `IllegalStateException`.
  Once removed, it can be pushed again.
- An exception thrown by `onEnter`, `onExit`, `onPause`, `onResume`, `onKey` or `onResize` is logged and the stack
  change still completes.
- A full-window scene on top takes every click, including over its empty areas, so the elements of a scene below it
  cannot be clicked. See [Known limitations](../reference/known-limitations.md#containers-and-layouts).
