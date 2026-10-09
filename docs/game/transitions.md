# Scene transitions

[Documentation](../README.md) › [Game](README.md)

A `SceneTransition` (`me.piitex.engine.game`) shows a change to the [scene stack](scenes.md) as an effect, such as a
fade or a slide, instead of a cut. Pass one to `push`, `pop`, `replace`, `set` or `clear`:

```java nocompile
SceneManager scenes = window.getSceneManager();
scenes.set(new Level(), SceneTransition.fadeThrough(Color.BLACK, 0.6f)); // fade to black and back
scenes.push(new PauseMenu(), SceneTransition.fade(0.2f));                // the menu fades in over the game
scenes.pop(SceneTransition.fade(0.2f));                                  // and out again
scenes.set(new MainMenu(), SceneTransition.slide(SceneTransition.Direction.RIGHT, 0.5f));
```

Passing `null`, or a transition of 0 seconds, changes the stack with no transition.

## Presets

| Factory | Effect |
|---|---|
| `fade(duration)` | The outgoing scenes fade out while the incoming ones fade in. |
| `fadeThrough(color, duration)` | Everything fades to `color` over the first half, then from it to the new scenes. |
| `slide(direction, duration)` | The incoming scenes push the outgoing ones off the window, moving in `direction`. |
| `wipe(direction, duration)` | An edge sweeps across in `direction`, uncovering the incoming scenes. Nothing moves. |

`direction` is where things move: `LEFT` brings the new scenes in from the right. Each factory also takes an
[`Easing`](../animation/transitions.md) as a last argument. The default is `Easing.EASE_IN_OUT`.

## What happens during a transition

The stack changes at once, exactly as without a transition. After `set(level, transition)` returns, `level` has
entered, is `getTop()`, and is running. Only the picture takes time to catch up.

- **Incoming scenes** run as usual from the start. If one should wait for the transition to finish, for example
  before starting a countdown, it can check `getSceneManager().isTransitioning()`.
- **Outgoing scenes** leave the stack, the window and the input at once, and their timers are cancelled. They stop
  updating, but they are still drawn, frozen, until the transition ends. Only then do they get `onExit`, so release
  anything their `render` uses in `onExit`. While they are drawn going out, `getSceneManager()` and `getWindow()`
  return null.
- Transitions run on **real time**, so they play out while game time is paused or slowed down.
- Any other change to the stack while a transition plays finishes it first, as `finishTransition()` does. The
  outgoing scenes get `onExit` right away and the new change starts from a settled screen. Call `finishTransition()`
  yourself to end a long transition from code.

## Locking the game and skipping

By default the scenes keep running and taking input while a transition plays. For a cutscene, a level intro or a
title card, lock them instead:

```java
scenes.set(new Level(), SceneTransition.fadeThrough(Color.BLACK, 3f).lock());               // frozen until it is over
scenes.set(new Level(), SceneTransition.fadeThrough(Color.BLACK, 3f).lock().skippable());   // ...unless the player presses a key or clicks
```

While a locking transition plays:

- No scene gets `fixedUpdate`, `update`, its timers or a key press, so the incoming scene does not start until the
  transition is over. Window-level containers and the `Scheduler` keep running, so the picture is animated.
- The window ignores key presses and repeats, typed characters, mouse button presses, the wheel and hover. Key and
  button **releases** still get through, so nothing is left stuck down.
- With `skippable()`, a fresh key press or mouse button press (not a held key's repeat, and not the wheel) ends it as
  `finishTransition()` does. That press is used up skipping; it does not also reach the game.
- `finishTransition()` ends it from code whether or not it is skippable, and any other change to the stack does too.

Locking is **off by default**. The same two switches exist on [element transitions](../animation/transitions.md#locking-the-game),
and `window.isInputLocked()` covers both kinds.

`scenes.isInputLocked()` says whether a locking scene transition is playing. The settings belong to the transition
instance, so a `SceneTransition` kept in a field applies them to every change that uses it.

## Base, outgoing and incoming scenes

A transition draws the visible scenes in three groups:

| Group | Which scenes | Example: pushing a pause menu over the game |
|---|---|---|
| Base | Visible before and after the change, at the bottom | The game |
| Outgoing | Visible before the change, not after | None |
| Incoming | Visible after the change, not before | The pause menu |

This is why pushing an [overlay](scenes.md#stacking-overlays-and-pausing) with `fade` fades in only the overlay while
the game stays put, and why `slide` moves a pause menu over the game rather than moving the game too. Scenes that
are hidden before and after the change aren't drawn.

## Writing your own

Extend `SceneTransition` and draw the groups in `render`. `progress` runs from 0, where the screen should look as it
did before the change, to 1, where it should look as it does after. It has already been shaped by the transition's
easing. Use the renderer's opacity layers, clips, translations and scales, and draw anything else you like on top.

```java nocompile
public class ZoomIn extends SceneTransition {
    public ZoomIn(float duration) {
        super(duration, Easing.EASE_OUT);
    }

    @Override
    protected void render(Renderer renderer, Frame frame, float progress) {
        frame.drawBase();
        frame.drawOutgoing();
        float w = frame.getWidth(), h = frame.getHeight();
        renderer.pushScale(0.5f + progress / 2f, w / 2f, h / 2f);
        renderer.pushOpacityLayer(0, 0, w, h, progress);
        frame.drawIncoming();
        renderer.popOpacityLayer();
        renderer.popScale();
    }
}
```

| `Frame` method | What it does |
|---|---|
| `drawBase()`, `drawOutgoing()`, `drawIncoming()` | Draws that group, bottom first. Each scene draws its `render` and then its elements. |
| `getBase()`, `getOutgoing()`, `getIncoming()` | The scenes in each group, to treat them one at a time. |
| `draw(scene)` | Draws one scene. |
| `getWidth()`, `getHeight()` | The window size. |

Pop everything you push. A transition should keep no state between frames, since one instance can be used for any
number of changes. If `render` throws, the error is logged, the new scenes are drawn plainly, and the transition
ends on the next frame.

## Things to know

- Scenes are drawn where they are moving to, but they take the mouse where they really are. During a `slide`, a
  click lands on the incoming scene's final layout. Transitions are short, so this rarely matters.
- `remove(scene)` has no transition version, since it can take a scene from the middle of the stack.
- Changing `setOverlay` or `setPausesBelow` while a transition plays doesn't change which scenes it draws.
