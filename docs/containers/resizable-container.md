# ResizableContainer

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.containers.ResizableContainer` is a [`Container`](container.md) that can change size and scales
its children with it. It can follow the window, and the user can drag its corners. Every other container keeps its
size. A runnable example is `me.piitex.examples.demo.ResizableContainerDemo`.

```java
ResizableContainer panel = new ResizableContainer(60, 60, 360, 260);
panel.setMinSize(200, 140); // never shrink below this
panel.setMaxSize(800, 600); // never grow beyond this
panel.setUserResizable(true); // the user can drag the corners

ButtonOverlay ok = new ButtonOverlay("OK", 100, 32);
panel.addElement(ok);
panel.setFixedSize(ok, true); // the button keeps its 100x32 size

window.addContainer(panel);
```

## What it does

| Feature | Details |
|---|---|
| [Follows the window](#following-the-window) | A top-level container keeps its share of the window as the window resizes |
| [Min and max size](#min-and-max-size) | Clamps the size, however it is driven |
| [Scales children](#children-and-fonts) | Positions, sizes and font sizes scale with the container |
| [Resizes by dragging](#resizing-by-dragging) | Grab the corners (or any edges) with the matching cursor |

## Following the window

A **top-level** `ResizableContainer` (added with `Window.addContainer`) tracks the window size
**proportionally**. A container covering half the window's width keeps covering half, and its position scales the
same way. It measures from its size when the first frame runs.

- Turn this off with `setResizeWithWindow(false)`, then drive it yourself with `resizeTo(width, height)`.
- A `ResizableContainer` **nested** in another container never follows the window. If its parent is a
  `ResizableContainer`, the parent scales it as one of its children.
- After a drag, `resizeTo`, or a min/max change, following restarts from the new size.

## Min and max size

`setMinSize(w, h)` and `setMaxSize(w, h)` apply right away. Past a minimum, the container stops shrinking and so
do its children. Defaults are no minimum and no maximum.

## Children and fonts

Each child, at any depth, has its position (relative to the container) and its size scaled by the same factors
as the container. Text scales too, by the **smaller** of the width and height factors so it still fits.

| Method | Effect |
|---|---|
| `setScaleChildren(false)` | Resize only the container. Children stay where they were |
| `setFixedSize(element, true)` | That element keeps its width, height and font size. Its top-left corner still follows |
| `setScaleFonts(false)` | Scale boxes but keep every font size as it is |
| `setFontSizeLimits(min, max)` | Clamp scaled font sizes, in points, so text stays readable |
| `resizeTo(w, h)` | Resize now (clamped), scaling the children. Position stays put |

- **Fonts scale** for text, buttons, text flows, text fields, text areas, dropdowns and sliders (label and tick
  labels), and for any custom element that implements `FontScalable`
  (`getFontSizes()` / `setFontSizes(float[])`).
- The font scale is the container's current size divided by its size before its first resize. Shrinking then
  growing back restores the original font size.
- If you change an element's font size yourself, the new size becomes its baseline at the current scale.
- A [layout](../layouts/README.md) child re-places its own children every frame, so for those only the sizes are scaled.
- Icons and images scale as pictures. An `IconOverlay` is rasterized once, so a large enlargement looks soft.

## Resizing by dragging

```java
panel.setUserResizable(true); // the four corners
panel.setResizeHandles(ResizableContainer.Handle.SOUTH_EAST); // or pick exactly which
panel.setResizeHandles(ResizableContainer.Handle.EDGES); // or a ready-made set
panel.setResizeGrip(14); // how far inside the border a handle reaches
panel.setShowResizeHandles(true); // draw the grab zones (useful while tuning)
```

- Handles are `NORTH_WEST`, `NORTH`, `NORTH_EAST`, `WEST`, `EAST`, `SOUTH_WEST`, `SOUTH`, `SOUTH_EAST`. `Handle.CORNERS`
  and `Handle.EDGES` are ready-made sets. `setUserResizable(true)` enables the corners. Passing none turns it off,
  which is the default.
- A handle is a strip just **inside** the border (10 points by default). It takes the mouse ahead of any child under
  it, so keep that margin clear of anything clickable.
- Hovering a handle shows the matching resize cursor, and the cursor is kept for the whole drag even once the mouse
  is past the container's edge.
- Dragging a left or top handle keeps the opposite edge fixed. The drag stops at `setMinSize`/`setMaxSize` and
  scales children and fonts like any other resize.
- It combines with `setDraggable(true)`: a press on a handle resizes, a press on the container's empty space moves it.
  A child layout filling the container passes the press through to it (see
  [Dragging](../input/input-and-focus.md#dragging)).

### Where to put the handles

The handles sit on the container's **own** border. A full-window container has them at the window's corners,
right beside the OS window border, which makes them hard to hit. Make the visible panel the resizable one
instead, and keep the full-window root a plain `Container`:

```text
Container                    full window, not resizable
  Layout                     main content
    ResizableContainer       the panel
      Layout                 form content
```

A layout that positions the panel (the centering `Layout` above) re-places it every frame. Only the bottom and
right handles behave naturally there, since a top or left drag would be undone by the layout. Use
`setResizeHandles(SOUTH_EAST, SOUTH, EAST)`, or place the panel yourself in a plain container.
