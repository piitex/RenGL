# Debug drawing

[Documentation](../README.md) › [Graphics](README.md)

`DebugDraw` (`me.piitex.engine.graphics`) draws lines, shapes and labels for seeing what the game is doing: hit boxes,
rays, paths, velocities, the state of an enemy. Queue shapes from anywhere in the game code, in the same coordinates as
the thing they describe, and draw the queue once a frame:

```java nocompile
private final DebugDraw debug = new DebugDraw();

@Override
protected void fixedUpdate(float step) {
    // ... move things ...
    debug.rect(enemy.getHitBox(), Color.RED);
    debug.arrow(enemy.x, enemy.y, enemy.x + enemy.vx, enemy.y + enemy.vy, Color.YELLOW);
    debug.text(enemy.x, enemy.y - 20, enemy.state.name(), Color.WHITE);
}

@Override
protected void render(Renderer renderer, float alpha) {
    camera.begin(renderer);
    batch.draw(renderer, camera.getVisibleBounds());
    camera.end(renderer);
    debug.draw(renderer, camera); // on top, in world coordinates
}
```

## Shapes

Every shape takes a `Color`. A null color draws nothing, as elsewhere in the engine.

| Method | Draws |
|---|---|
| `line(x0, y0, x1, y1, color)` | A line |
| `arrow(x0, y0, x1, y1, color)` | A line with a head at (x1, y1): a direction, a velocity, a force |
| `rect(x, y, w, h, color)`, `rect(Rect, color)` | A rectangle's outline, such as `sprite.getBounds()` |
| `fillRect(x, y, w, h, color)`, `fillRect(Rect, color)` | A filled rectangle. Give the color some transparency to see what's under it |
| `circle(x, y, radius, color)`, `fillCircle(...)` | A circle's outline, or a filled one, around (x, y) |
| `polygon(points, pointCount, color)` | The closed outline through packed `x0, y0, x1, y1, ...` points: a turned box, a collision shape, a path. The points are copied |
| `point(x, y, color)` | A dot |
| `text(x, y, text, color)` | A label with its top-left at (x, y), in the [default font](../styling/fonts.md), with a dark shadow so it reads over anything |

## Drawing

| Call | Coordinates |
|---|---|
| `draw(renderer, camera)` | World coordinates, through the [camera](camera.md) and inside its viewport. Call it outside `camera.begin`/`end`; it does that itself |
| `draw(renderer)` | Screen coordinates, as given |

Through a camera, sizes that are only there to be seen stay the same on screen at any zoom: line widths, dots, arrow
heads and text. Text also stays upright when the camera turns. Everything else scales and turns with the world.

For shapes in both spaces, use two `DebugDraw`s, one drawn with the camera and one without.

## How long a shape stays

Shapes are meant to be queued afresh each step or frame, like drawing. How long one stays depends on where it was
queued:

| Queued in | Stays until |
|---|---|
| `fixedUpdate` | The next fixed step. It shows on every frame in between, so there's no flicker when the frame rate is above the [tick rate](../game/game-time.md), and it stays while the game is paused |
| Anywhere else: `update`, `render`, an input handler | The end of the frame. Every `draw` in that frame shows it, so a main view and a minimap can draw the same queue |
| Anything, through `hold(seconds)` | At least that many seconds of game time |

`hold` is for events too short to see, such as a hit, a pick-up or a raycast that happens once:

```java nocompile
debug.hold(0.5f).circle(hit.x, hit.y, 12, Color.RED);
```

`hold` returns a view of the same `DebugDraw`, sharing its queue and settings, and is cheap enough to call inline. Held
shapes use game time, so they wait while the game is paused and last longer in slow motion.

## Settings

| Method | Default | What it does |
|---|---|---|
| `setEnabled(boolean)` | `true` | While off, shape calls return at once and `draw` draws nothing. Turning it off empties the queue |
| `setLineWidth(float)` | 1 | Line and outline width, in screen points |
| `setPointSize(float)` | 3 | Dot width, in screen points |
| `setTextSize(float)` | 12 | Text height, in screen points |
| `clear()` | | Empties the queue, held shapes included |

Since a disabled `DebugDraw` does nothing, debug calls can stay in the game and be switched on with a key. Building a
label string still costs something, so guard expensive ones with `if (debug.isEnabled())`.

## Things to know

- **Render thread only,** which includes every `Scene` hook.
- Shapes are drawn in the order they were queued, filled or not. Text is drawn last, above the rest.
- A shape queued in `render` after `draw` shows in the next frame instead.
- There's no global instance. Keep one where the code that queues shapes can reach it, such as a field of the scene or
  a static field of the game.

`SpriteDemo` in `me.piitex.examples.demo` has a debug view on F1. It shows each slime's bounds and feet, queued in
`render`; the player's pick-up box and heading, queued in `fixedUpdate`; a ring where a coin was picked up, held for a
moment; and the coordinates of the tile under the mouse.
