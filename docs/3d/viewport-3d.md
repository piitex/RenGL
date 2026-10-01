# Viewport3D

[Documentation](../README.md) › [3D](README.md)

`me.piitex.engine.render3d.Viewport3D` is an [`Element`](../core/element.md) that shows 3D. Add it to a container
like any other element and give it a `Content`, which is the thing that draws the picture. A [`Scene3D`](scenes.md) is a
ready-made `Content`; for your own drawing see [Custom rendering](custom-rendering.md).

```java nocompile
Viewport3D view = new Viewport3D(640, 360);
view.setPosition(20, 20);
view.setContent(scene);
container.addElement(view);
```

It needs a window on OpenGL or Vulkan, see [What you need](README.md#what-you-need).

## How it draws

Each frame the content renders into an offscreen target the size of the viewport **in pixels** (its size in points
times the display's pixel scale times the [resolution scale](#resolution)). The UI layer then draws that target as an
image, so everything else in the UI draws around and over the viewport normally: a [`TextOverlay`](../overlays/text-overlay.md)
on top of it, a rounded corner, a border.

- The target is made again whenever the viewport is resized, so the picture stays sharp. `Content.resize` tells the
  content.
- The picture is opaque. Whatever alpha the content leaves in the target is drawn as premultiplied alpha.
- The 3D device is made the first time a viewport draws, and **shared by every viewport in the window**. A window with
  no viewport never starts it.
- When the window closes, every viewport's content is disposed and the device freed.

Like any element it has a position, size, [anchor](../core/element.md#anchoring), opacity and enabled state.
`getStyling().setCornerRadius(12f)` rounds the picture's corners. A disabled viewport (`setEnabled(false)`) is hidden,
does not render and costs nothing.

## Setting the content

| Method | Description |
|---|---|
| `setContent(Content)` | What the viewport shows. Takes effect on the next frame, when the previous content is disposed and the new one initialised. Also clears an earlier failure |
| `getContent()` | The current content |
| `setInputListener(InputListener)` | Where [input](#input) goes. `null` (the default) means the content itself, if it implements `InputListener` |
| `setResolutionScale(float)` / `getResolutionScale()` | See [Resolution](#resolution) |
| `setContinuous(boolean)` / `isContinuous()` | `true` (the default) draws every frame. See [Drawing only when needed](#drawing-only-when-needed) |
| `requestRender()` | Asks for a redraw on the next frame, for a viewport that is not continuous. Safe from any thread |
| `getPixelWidth()` / `getPixelHeight()` | The target's size in pixels, `0` before the first frame |
| `getDevice()` | The [`GpuDevice`](custom-rendering.md#the-gpu-device) the content draws with, `null` before the first frame |
| `close()` | Frees the content and target now, for a viewport you remove before the window closes. It can draw again afterwards, starting afresh |

One `Content` can be shown by several viewports, in the same window or in different ones. `init` and `dispose` are
called once per viewport that uses it.

## Content

```java nocompile
public interface Content {
    default void init(GpuDevice device) {}
    default void resize(GpuDevice device, int width, int height) {}
    void render(GpuDevice device, RenderTarget target, float delta);
    default void dispose(GpuDevice device) {}
}
```

| Method | When | Use it to |
|---|---|---|
| `init` | Once before the first `render`, and again if the content moves to another window's viewport | Create buffers, textures and pipelines |
| `resize` | After the target is created: before the first frame, and after every resize | Update a camera's aspect ratio. `width` and `height` are in pixels |
| `render` | Every frame. `delta` is the seconds since the previous frame | Begin a pass on `target`, draw, end it. The device's frame is already begun and ends after you return |
| `dispose` | When the content is replaced, the viewport is closed, or the window closes | Free what `init` made |

All four run on the render thread with the window's graphics context current.

If a content's method throws, the viewport switches itself off, logs the error and stays off until you call
`setContent` again. The rest of the UI keeps working.

## Input

A content (or a separate object) that implements `Viewport3D.InputListener` receives the mouse and keyboard. Every
method has an empty default, so implement only what you use. Positions are **points from the viewport's top-left
corner**, the same space as `getWidth()`.

| Method | Called when |
|---|---|
| `pointerMoved(x, y)` | The mouse moves over the viewport with no drag going on |
| `pointerExited()` | The mouse leaves it |
| `dragStarted(x, y)` · `dragged(x, y, dx, dy)` · `dragEnded(x, y)` | A left-button drag. `dx`/`dy` is the movement since the last call |
| `scrolled(dx, dy, x, y)` | The wheel or trackpad scrolls over it. `dy` is the usual zoom axis |
| `keyPressed(key, action, mods)` | A key goes down, repeats or comes up while the viewport has focus. `action` is GLFW's `GLFW_PRESS`, `GLFW_REPEAT` or `GLFW_RELEASE` |
| `charTyped(codepoint)` | A character is typed while it has focus |
| `focusChanged(focused)` | The viewport gains or loses [keyboard focus](../input/input-and-focus.md#keyboard-focus) |

How it fits the UI's input rules:

- A viewport is [`Focusable`, `Draggable` and `Scrollable`](../input/input-and-focus.md). A press on it focuses it and
  starts a drag; a click elsewhere takes the focus away.
- **Only the left button drags.** For the other buttons, set an [`onMouseClick`](../core/element.md#onmouseclick)
  listener on the viewport.
- Keys go to the focused viewport and **not** to the game behind the UI.
- The viewport only takes the wheel when it has a listener. With none, the wheel passes on to whatever is under it,
  such as a [`ScrollContainer`](../containers/scroll-container.md).

[`OrbitControls`](scenes.md#orbit-controls) is a ready-made listener for a scene. Your own is a few lines:

```java nocompile
view.setInputListener(new Viewport3D.InputListener() {
    @Override
    public void dragged(double x, double y, double dx, double dy) {
        yaw += (float) dx * 0.01f;
    }

    @Override
    public void keyPressed(int key, int action, int mods) {
        if (key == GLFW.GLFW_KEY_R && action == GLFW.GLFW_PRESS) yaw = 0;
    }
});
```

## Resolution

`setResolutionScale(scale)` sets the picture's pixels relative to the viewport's pixels, clamped to `0.1` to `4`:

| Scale | Effect |
|---|---|
| `1` (default) | One rendered pixel per screen pixel |
| `0.5` | A quarter as many pixels. The UI layer scales the picture up, so it is softer but cheaper |
| `2` | Four times as many pixels: supersampling, which hides jagged edges because there is no [anti-aliasing](../reference/known-limitations.md#rendering-and-3d) yet |

## Several viewports and several windows

```java nocompile
Viewport3D main = new Viewport3D(640, 480);
main.setContent(sceneA);

Viewport3D thumbnail = new Viewport3D(200, 150);
thumbnail.setContent(sceneB);          // its own scene...
thumbnail.getStyling().setCornerRadius(10f);
```

Viewports in one window share a device, so scenes and their GPU meshes are shared too. A scene shown in several
viewports is drawn once per viewport per frame, and runs its [update handler](scenes.md#the-update-handler) once per
frame, before the first of them draws.

## Drawing only when needed

A viewport redraws its content every frame by default, which a moving scene needs. A viewport that shows something
still, such as a model viewer or a preview, can skip that work:

```java nocompile
view.setContinuous(false);   // draw on request
// ... later, after changing the scene from code:
view.requestRender();
```

A viewport that is not continuous draws when:

- it draws for the first time, is resized, or gets new content;
- the [`InputListener`](#input) receives anything (drag, scroll, a key, the mouse moving), so an
  [`OrbitControls`](scenes.md#orbit-controls) camera still redraws as you turn it;
- `requestRender()` is called, which is what you do after changing a node, light or camera yourself.

Between those frames it shows the last picture again, which costs no 3D work and no GPU copy. A scene with an
[update handler](scenes.md#the-update-handler) that animates it needs a continuous viewport, because the handler only
runs when the scene draws.

## Gotchas

- **Metal windows and snapshots draw nothing.** The first time a viewport draws on a renderer that can't share the GPU
  (Metal, or the CPU renderer behind [snapshots](../core/snapshots.md)), it logs
  `A Viewport3D can only draw in a window running on OpenGL or Vulkan` once and leaves the rectangle empty. A
  snapshot of the UI therefore leaves a viewport out.
- **It re-renders every frame by default,** even when nothing changed. For a still picture, use
  [`setContinuous(false)`](#drawing-only-when-needed).
- **Don't keep the `GpuDevice` or its objects past `dispose`.** The device belongs to the window.
- A viewport removed from its container without `close()` keeps its GPU objects until the window closes.
