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

The camera holds the view and nothing else. Following the player, easing towards a target, shaking on impact and
staying inside the level are the game's own code, setting the position each frame:

```java nocompile
@Override
protected void update(float delta) {
    // Where the player is drawn this frame, between its last two fixed-step positions
    float targetX = lerp(player.prevX, player.x, GameTime.getAlpha());
    float targetY = lerp(player.prevY, player.y, GameTime.getAlpha());
    // Ease towards it, the same way at any frame rate
    float follow = 1f - (float) Math.exp(-8f * delta);
    camera.setPosition(lerp(camera.getX(), targetX, follow), lerp(camera.getY(), targetY, follow));
}
```

Follow the position the player is **drawn** at, not the one `fixedUpdate` last set. Otherwise the camera moves 60
times per second while the player moves every frame, and the player appears to shake. See
[Drawing between steps](../game/game-time.md#drawing-between-steps).

## Converting between the window and the world

| Method | Returns |
|---|---|
| `screenToWorld(x, y)` | The world point shown at window point (x, y), such as the one under the mouse. |
| `worldToScreen(x, y)` | Where a world point is drawn in the window, for putting a label over a character. |
| `getVisibleBounds()` | The part of the world in view, as a `Rect`. A little larger than the view when the camera is turned, since it is the upright rectangle around it. |

Window points here are the coordinates mouse events, `MouseTracker` and `Scene.render` use:

```java nocompile
getContainer().onMouseClick(event -> {
    Vec2 world = camera.screenToWorld((float) event.getMouseX(), (float) event.getMouseY());
    placeTower(world.x(), world.y());
});
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

- The camera draws through `Renderer.pushTransform`, which a custom `Renderer` must implement for rotation to work.
  The built-in renderers do.
- Elements in the scene's container (buttons, text, the HUD) are not affected by the camera. They are drawn after
  `render`, in window coordinates.
