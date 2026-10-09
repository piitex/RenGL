# Camera

[Documentation](../README.md) › [Graphics](README.md)

A `Camera` (`me.piitex.engine.graphics`) is a view of a 2D world: which world point is in the middle of the view, how
far it is zoomed in, how it is turned, and which rectangle of the window it is shown in. Everything drawn between
`begin` and `end` is in world coordinates.

```java nocompile
public class Level extends Scene {
    private final Camera camera = new Camera();

    @Override
    protected void onEnter() {
        camera.setViewport(0, 0, (float) getContainer().getWidth(), (float) getContainer().getHeight());
        camera.setZoom(3f);
    }

    @Override
    protected void onResize(int width, int height) {
        camera.setViewport(0, 0, width, height);
    }

    @Override
    protected void render(Renderer renderer, float alpha) {
        camera.begin(renderer);
        // draw the world in world coordinates: sprites, a SpriteBatch, or any Renderer call
        camera.end(renderer);
    }
}
```

## The view

| Method | What it sets |
|---|---|
| `setPosition(x, y)`, `move(dx, dy)` | The world point shown in the middle of the viewport. |
| `setZoom(zoom)` | Window points per world unit, 1 by default. 2 shows everything twice as big. Must be above 0. |
| `setRotation(degrees)` | How far the camera is turned, clockwise, around the middle of the view. The world turns the other way on screen. |
| `setViewport(x, y, width, height)` | The rectangle of the window the view is shown in. Drawing is clipped to it. |
| `setPixelSnap(true)` | Rounds the view's offset to whole points, so pixel art at a whole-number zoom stays on the screen's pixel grid while the camera moves. |

World units are whatever the game uses. At a zoom of 1 a unit is one window point; a game measuring in tiles or
metres zooms by how many points one unit should cover.

A camera's viewport starts empty, so set it before drawing, and again in `onResize` when it should follow the window.
`new Camera(width, height)` starts with a viewport of that size at the window's top-left corner.

## Moving it

Set the position, zoom and rotation whenever you like. For the usual moves, the camera has helpers:

```java nocompile
@Override
protected void update(float delta) {
    // Where the player is drawn this frame, between its last two fixed-step positions
    float targetX = lerp(player.prevX, player.x, GameTime.getAlpha());
    float targetY = lerp(player.prevY, player.y, GameTime.getAlpha());
    camera.follow(targetX, targetY, 6f, delta); // ease towards it, the same way at any frame rate
    camera.update(delta);                       // move the shake on
}

private void onExplosion(float x, float y) {
    camera.shake(0.6f);
}
```

| Method | What it does |
|---|---|
| `follow(x, y, rate, delta)` | Moves part of the way to the target, so that calling it every frame eases in on it. The gap shrinks by a factor of e every `1 / rate` seconds: about 5 keeps up closely, 2 drifts behind. To jump there instead, `setPosition`. |
| `setDeadzone(width, height)` | A box around the middle of the view, in window points, that a followed target can move in without the camera moving. Past its edges, the camera follows only the part outside. |
| `setBounds(rect)` | Keeps the view inside a world rectangle, such as the level: the view stops at the edges, and a level smaller than the view is shown in its middle. The position stays where it was aimed, so the view moves off the edge again as soon as the target does. `null` removes them. |
| `shake(trauma)` | Adds trauma, capped at 1. The view moves and turns by the square of it, so small knocks stay small: about 0.1 for a shot, 0.3 for a hit, 0.6 for an explosion nearby, 1 for the biggest. |
| `update(delta)` | Moves the shake on and lets the trauma die away. Call it once a frame. |
| `setMaxShakeOffset(points)`, `setMaxShakeRotation(degrees)`, `setShakeRecovery(perSecond)` | How far the view moves (20 window points) and turns (2 degrees) at full trauma, and how much trauma dies away each second (1.5). |
| `getTrauma()`, `setTrauma(trauma)` | The trauma now. `setTrauma(0)` stops a shake at once, such as when a level restarts. |

Follow the position the player is **drawn** at, not the one `fixedUpdate` last set. Otherwise the camera moves 60
times per second while the player moves every frame, and the player appears to shake. See
[Drawing between steps](../game/game-time.md#drawing-between-steps).

The shake and the bounds only change what is shown. `getX`, `getY` and `getRotation` keep returning what you set,
so code that follows or aims from the camera's position isn't thrown about by the shake. `screenToWorld`,
`worldToScreen` and `getVisibleBounds` describe the view as it is drawn, shake and bounds included, so the mouse
still points at what is under it.

**Which clock.** Pass `update` the scene's delta for a shake that slows down and stops with the
[game time](../game/game-time.md), or `GameTime.getRealDelta()` for one that settles in slow motion and pauses too.
`follow` takes its own delta, so the camera can follow on game time and shake on real time.

## Converting between the window and the world

| Method | Returns |
|---|---|
| `screenToWorld(x, y)` | The world point shown at window point (x, y), such as the one under the mouse. |
| `worldToScreen(x, y)` | Where a world point is drawn in the window, for putting a label over a character. |
| `getVisibleBounds()` | The part of the world in view, as a `Rect`. A little larger than the view when the camera is turned, since it is the upright rectangle around it. |

Window points here are the coordinates mouse events, `MouseTracker`, `Input.getMouseX/Y` and `Scene.render` use:

```java nocompile
getContainer().onMouseClick(event -> {
    Vec2 world = camera.screenToWorld((float) event.getMouseX(), (float) event.getMouseY());
    placeTower(world.x(), world.y());
});
```

To poll instead, as a game usually does, read the mouse from [`Input`](../input/game-input.md):

```java nocompile
Input input = window.getInput();
if (input.isMouseJustPressed(GLFW_MOUSE_BUTTON_LEFT)) {
    Vec2 world = camera.screenToWorld(input.getMouseX(), input.getMouseY());
    placeTower(world.x(), world.y());
}
```

`Vec2` and `Rect` are small value types in `me.piitex.engine.math`.

## Several cameras

Cameras are independent, so several can draw in one frame, each into its own viewport:

- **Split screen:** two cameras, one per half of the window, each following its own player.
- **Minimap:** a second camera with a small viewport in a corner and a low zoom, drawing a simpler version of the
  world. `SpriteDemo` draws its map once into an image at startup and shows it through a minimap camera.

`begin`/`end` pairs nest with the renderer's other pushes, so a camera can also draw inside a container's clip or a
[scene transition](../game/transitions.md).

## Things to know

- The camera draws through `Renderer.pushTransform`, which a custom `Renderer` must implement for rotation, and the
  shake's turning, to work. The built-in renderers do.
- Bounds hold the whole view even when it is turned or shaking, so the camera stops further in from the edges then,
  and a level only a little larger than the view has no room to move.
- Elements in the scene's container (buttons, text, the HUD) are not affected by the camera. They are drawn after
  `render`, in window coordinates.
