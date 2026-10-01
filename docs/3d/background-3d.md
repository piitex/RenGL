# Background3D

[Documentation](../README.md) › [3D](README.md)

`me.piitex.engine.render3d.Background3D` puts 3D behind the **whole window**, with every container, overlay and popup
drawn over it. It is a window [`Background`](../styling/backgrounds.md), so it uses the existing
`Window.setBackground` hook and nothing in `Window` or `Engine` knows about 3D:

```java nocompile
Scene3D scene = new Scene3D();
// ... build the scene ...
Background3D background = new Background3D(window, scene);
background.setInputListener(new OrbitControls(scene.camera()));
window.setBackground(background);
```

It takes the same [`Viewport3D.Content`](viewport-3d.md#content) a viewport takes, so a [`Scene3D`](scenes.md) or your own
[custom content](custom-rendering.md) works in a viewport or behind the window. It draws exactly like a viewport that
covers the window: the content renders into an offscreen target the size of the window in pixels, and the UI layer
draws that as an image. It needs a window on OpenGL or Vulkan, see [What you need](README.md#what-you-need).

Each frame the engine updates the background, then (after clearing the canvas) renders it **before every container**,
so the UI always draws over the 3D. See [One frame](../architecture.md#one-frame).

## Methods

| Method | Description |
|---|---|
| `new Background3D(Window, Content)` | The window is the one you will set it on: input is read from its `getInput()`. The content may be `null` and set later |
| `setContent(Content)` / `getContent()` | What it shows. Takes effect next frame; the old content is disposed then |
| `setInputListener(InputListener)` | Where input goes. `null` (the default) means the content, if it implements `InputListener`, and nobody otherwise |
| `setResolutionScale(float)` / `getResolutionScale()` | Picture pixels per window pixel, `0.1` to `4`, default `1`. `0.5` renders a quarter as many pixels and lets the UI layer scale it up |
| `setEnabled(boolean)` / `isEnabled()` | Hides or shows the 3D without removing the background. A hidden background does not render, so it costs nothing |
| `setContinuous(boolean)` / `isContinuous()` | `true` (the default) draws every frame. `false` draws only after `requestRender()`, a window resize, new content, or input that reaches the listener, the same as [a viewport](viewport-3d.md#drawing-only-when-needed) |
| `requestRender()` | Asks for a redraw on the next frame, when it isn't continuous |
| `getDevice()` | The GPU device, `null` before the first frame |
| `close()` | Frees the content and target. For a background removed before the window closes: call `window.setBackground(null)` first. It happens by itself when the window closes |

## Input

A background has no place in the UI's hit testing, so `Background3D` is fed from the window's polled
[game `Input`](../input/game-input.md): what the interface **left alone**. See
[What reaches the game](../input/game-input.md#what-reaches-the-game). In practice:

| You do | Reaches the 3D? |
|---|---|
| Press on empty space, or on a panel or image with **no click listener** | Yes: it starts a left-button drag |
| Press on a button, slider, text field, or anything with `onMouseClick` | No: that element takes it |
| Scroll the wheel over empty space | Yes: `scrolled`. Not over a scroll area, text area or anything else that scrolls |
| Move the mouse | Yes: `pointerMoved` when not dragging (also while the mouse is over UI) |
| Press a key no text field, popup or modal took | Yes: `keyPressed` as press, repeat and release (releases always) |

The listener gets the same calls a viewport sends, with positions in points from the window's top-left corner:
a left-button `dragStarted`, `dragged` (with the movement), `dragEnded` when the button comes up, `scrolled`,
`pointerMoved` and `keyPressed`.

The important consequence: **a full-window root container with a click listener, or a draggable container under the
mouse, takes the press and the background never sees it.** Give the root container that covers the window no click
listener, as the demo does, or put the listener on smaller elements.

```java nocompile
Container root = new Container(1000, 650);  // covers the window, no background, no click listener
window.addContainer(root);
// buttons, sliders and so on go in it and keep their own clicks
```

To keep clicks on a HUD panel from rotating the camera, give that panel a do-nothing click listener:
`panel.onMouseClick(e -> {})`.

## Example

`Background3DDemo` (`me.piitex.examples.demo`) shows a cube, a sphere and a floor with a small control panel over
them: an ambient-light slider, a check box that spins the cube, a "Show 3D" box wired to `setEnabled`, and a
"Reset camera" button. Drag empty space to orbit, scroll to zoom.

```java nocompile
CheckBoxOverlay showBox = new CheckBoxOverlay(20, true);
showBox.onToggle(background::setEnabled);
root.addElement(showBox);
```

Run it with `-XstartOnFirstThread`, and `-Drengl.demo.api=VULKAN` or `OPENGL` to pick the API.

## Gotchas

- **Not a focus target.** A background gets no keyboard focus and no `charTyped`. The listener receives keys the UI
  did not use, nothing more.
- **There is no `pointerExited`.** `pointerMoved` also fires while the mouse is over UI, so don't treat it as "the
  mouse is over the 3D".
- **It re-renders a full-window picture every frame,** an offscreen target plus a GPU copy. Lower
  `setResolutionScale`, `setEnabled(false)` while a full-screen menu covers it, or `setContinuous(false)` if the scene
  only changes when you say so.
- **Window backgrounds replace each other.** `window.setBackground(...)` holds one `Background`, so setting a
  `Background3D` removes an effect such as a [`ConstellationBackground`](../styling/backgrounds.md) you set before.
- **Untested as an element background.** `Background` can also be set on an `Element`, but a `Background3D`'s input
  assumes its area is the window.
