# MinimapOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.MinimapOverlay` is a small map of a larger world, with markers on it: the corner mini-map of an action game,
the overview of a strategy or tycoon map, the "you are here" of a level.

```java
MinimapOverlay map = new MinimapOverlay(200, 200);
map.setWorld(0, 0, 4000, 4000);                  // the area it shows, in your game's units
map.setBackdrop(terrainImage);                   // an image of that area; optional
MinimapOverlay.Marker me = map.addMarker(playerX, playerY, Color.WHITE);
me.setShape(MinimapOverlay.Shape.ARROW);
map.setFollow(me);                               // keep the view centered on the player
map.setViewport(camX, camY, camW, camH);         // outline what the main view shows
map.onNavigate((x, y) -> camera.lookAt(x, y));   // a click or drag on the map
```

## The world and the view

World coordinates are the game's own, and `y` grows **down** the map like on screen; `setFlipY(true)` is for a game whose y grows up. The
map fits the whole world inside its box, keeping its shape, until zoomed.

| Call | What it does |
|---|---|
| `setWorld(x, y, width, height)` | The area shown; resets the view to all of it |
| `setZoom(z)` / the wheel | Zoom about the center, 1 to 16 by default; `setZoomLimits(min, max)` |
| `centerOn(x, y)` | Moves the view's center; it never leaves the world |
| `setFollow(marker)` | Keeps the center on a marker as it moves; `null` stops |
| `setViewport(x, y, w, h)` | Outlines a part of the world, such as the camera; `clearViewport()` |
| `setRound(true)` | Clips the map to a circle, compass style |
| `setBackdrop(Image)` | An image of the whole world area, drawn under the markers; the map never closes it |
| `setBackdropPainter(painter)` | Draws anything under the markers, using `worldToScreenX/Y` |

`worldToScreenX/Y`, `screenToWorldX/Y` and `getPointsPerUnit()` convert between the two.

## Markers

```java
MinimapOverlay.Marker enemy = map.addMarker(x, y, Color.RED)
        .setShape(MinimapOverlay.Shape.DIAMOND)   // DOT, SQUARE, DIAMOND, ARROW
        .setSize(5)                                // radius in points, whatever the zoom
        .setData(unit);                            // find it again from a click
enemy.setPosition(newX, newY);                     // the game moves them; the map keeps no list of what exists
enemy.setVisible(false);
map.removeMarker(enemy);
```

An `ARROW` points along `setHeading(degrees)`, clockwise on screen from pointing right.

## Input

Pressing or dragging on the map calls `onNavigate` with the world position under the pointer (held to the world's edge). A press on a marker
calls `onMarkerClick(marker)` instead, with the topmost visible one winning. The corners outside a round map take no input.

Colors come from the theme: the box from the field background and border, the camera outline from the accent (`setViewportColor`).
