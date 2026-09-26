# Game time

[Documentation](../README.md) › [Game](README.md)

`GameTime` (`me.piitex.engine.game`) is the game clock. The engine keeps two clocks:

| Clock | Drives | Pauses? | Time scale? |
|---|---|---|---|
| **Real time** | Elements, transitions, `onUpdate`, [`Scheduler`](../core/scheduler.md) timers | Never | No |
| **Game time** | [Scenes](scenes.md): `fixedUpdate`, `update`, scene timers | With `GameTime.setPaused` | Yes |

Because the UI stays on real time, menus and buttons keep animating while the game is paused or in slow motion.

## Fixed steps

Game logic belongs in `Scene.fixedUpdate(step)`. It runs at a fixed **tick rate**, 60 times per second of game time
by default, however fast or slow frames are. A frame runs as many steps as the time it covers holds: at 144 FPS most
frames run none or one, at 30 FPS two per frame. `step` is always `1 / tickRate` seconds, so movement and physics
play out the same on every machine.

```java nocompile
@Override
protected void fixedUpdate(float step) {
    velocityY += GRAVITY * step;
    y += velocityY * step;
}
```

`Scene.update(delta)` runs once per frame with the frame's game time. Use it for things that should follow the frame
rate, like visual effects or reading input state.

### Drawing between steps

Something moved in `fixedUpdate` only changes position 60 times per second. Drawn as-is on a faster display, it
stutters. Keep its position from before the last step and draw it partway between the two, using the `alpha` that
`render` receives (also `GameTime.getAlpha()`): how far game time is past the last step, from 0 to 1.

```java nocompile
@Override
protected void fixedUpdate(float step) {
    prevX = x;
    x += speed * step;
}

@Override
protected void render(Renderer renderer, float alpha) {
    renderer.drawCircle(prevX + (x - prevX) * alpha, y, 12f, Color.WHITE);
}
```

## Settings

All of them can be changed from any thread and take effect on the next frame.

| Method | Default | What it does |
|---|---|---|
| `setTickRate(int)` | 60 | Fixed steps per second of game time, 1 to 1000. |
| `setTimeScale(float)` | 1 | Game speed. 0.5 is slow motion, 2 double speed, 0 stops game time. |
| `setPaused(boolean)` | `false` | Stops game time for every scene: no fixed steps, and `update` receives 0. |
| `setMaxTicksPerFrame(int)` | 10 | Most fixed steps one frame may run. Past that, the game slows down instead of catching up. |

The time scale doesn't change the tick rate in game time. At 0.5 there are half as many steps per real second, each
still `1 / tickRate` long, so the simulation stays exactly the same, only slower.

To pause the game under a pause menu you usually don't need `setPaused`: pushing the menu as a scene pauses the
scenes below it (see [Scenes](scenes.md#stacking-overlays-and-pausing)). `setPaused` is for stopping every scene at
once, and it keeps a running pause menu's `update` from getting any time either.

## Reading the clocks

| Method | Value |
|---|---|
| `getTime()` | Seconds of game time since the engine started. |
| `getRealTime()` | Seconds of real time since the engine started. |
| `getDelta()` | Game time covered by this frame. What `Scene.update` receives. |
| `getRealDelta()` | Real time covered by this frame. What elements receive. |
| `getFixedDelta()` | Length of one fixed step: `1 / tickRate`. |
| `getTicks()` | Fixed steps completed. Inside `fixedUpdate`, the number of the current step, from 0. |
| `getAlpha()` | How far past the last fixed step, 0 to 1. |

They can be read from any thread. `reset()` sets them all back to 0 (not the settings). The engine calls it when it
starts. Call it yourself on the render thread if you want the clocks to restart with a new game.

## Things to know

- A frame counts for at most 0.25 seconds of real time, so a debugger pause or GC stall doesn't make the game jump
  ahead. The same cap applies to the UI.
- The fixed steps of a frame run after [`Scheduler`](../core/scheduler.md) callbacks and before the containers'
  update pass. See [Architecture](../architecture.md#one-frame).
- `getTicks()` counts every step the engine runs, whether or not a scene was running to receive it.
