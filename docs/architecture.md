# Architecture

This page explains what happens between "the user moves the mouse" and "pixels change". Knowing it makes the
rest of the API predictable.

## The object model

```text
Window                       one per program
 └─ containers (by index)    a sorted map: lower index draws first, higher draws on top
     └─ Container            is itself an Element
         └─ Element / Overlay / nested Container
```

| Class | Role |
|---|---|
| [`Engine`](core/engine.md) | Owns the OS window, the two threads and the frame loop |
| [`Window`](core/window.md) | The top-level list of containers plus focus, popup and modal state |
| [`Element`](core/element.md) | A rectangle with styling, events, tooltip and transitions |
| [`Container`](containers/container.md) | An [`Element`](core/element.md) that also holds children |
| [`Layout`](layouts/README.md) | A [`Container`](containers/container.md) that positions its children automatically |
| [`Overlay`](core/overlay.md) | An `Element` that draws something specific (text, button, image, ...) |

A [`Layout`](layouts/README.md) is a `Container`, and a `Container` is an `Element`. RenEngine treated layouts and containers as
separate renderers; RenGL does not.

## Two threads

`Engine.start(window)` runs on the **main thread** and starts a **render thread**:

| Thread | Does |
|---|---|
| **Main** | Creates the GLFW window, calls `glfwPollEvents` in a tight loop, shows the window after the first frame, and services the clipboard and mouse cursor (both are main-thread-only in GLFW) |
| **Render** | Runs the frame loop: drains input events, updates every element, draws everything, presents |

GLFW callbacks only enqueue events. The render thread drains that queue at the start of each frame.

**Practical consequence:** every callback you register (`onAction`, `onTextChanged`, `onUpdate`, ...)
runs on the render thread, right in the middle of a frame. Don't block in them. If you start your own
worker threads and want to change the UI from them, be aware the render thread may be reading the same
element at the same time. Post the work with [`Scheduler.runLater`](core/scheduler.md), which runs it on the render
thread at the start of the next frame.

## One frame

```text
 1. Handle window resize                (recreate the drawing surface if needed)
 2. InputProcessor.processEvents        (mouse move, click and scroll, keys, typed chars)
 3. Advance the window background       (an animated paint, and the window's Background effect if set)
 4. Deliver queued callbacks            (native menu choices, file dialog results)
 5. Scheduler.update                    (runLater tasks, timers, async results)
 6. Scene fixed steps                   (Scene.fixedUpdate, as many ticks as this frame's game time covers)
 7. Scene updates                       (scene transition clock, then scene timers and Scene.update with the frame's game time)
 8. container.update(delta)             (every enabled top-level container, recursively)
 9. TooltipManager.update
10. Clear the canvas; draw the window background and its Background effect
11. container.renderWithTransitions     (every enabled top-level container, in index order; scenes, or a scene transition, come first)
12. TooltipManager.render               (above every container)
13. Present
14. Pace the frame                      (vsync waits for the display; with vsync off, sleep to WindowOptions.maxFPS)
```

Steps 6 and 7 run on the game clock, which can be scaled and paused, except for scene transitions. Everything
else runs on real time. See
[Game time](game/game-time.md) and [Scenes](game/scenes.md).

`delta` is the seconds since the previous frame, capped at 0.25 s so a debugger pause or GC stall does not
make animations jump. Use it for anything time-based:

```java
container.onUpdate(event -> {
    // 200 points per second regardless of frame rate
    container.setX(container.getX() + 200 * event.getDelta());
});
```

### Update vs. render

- `update(delta)` advances state: animated paints, backgrounds, transitions, layouts, your `onUpdate`
  hook.
- `render(renderer)` only draws. Do not change state there.

For a [`Container`](containers/container.md), both methods recurse into children. A disabled element is skipped entirely
(`setEnabled(false)` hides it, stops its updates, and removes it from hit-testing).

## Coordinates and drawing order

- Origin top-left, units are logical points, `x`/`y` is an element's top-left corner.
- A child's `x`/`y` is **relative to the container it is in**: `(0, 0)` is that container's top-left corner. A
  top-level container's `x`/`y` are window coordinates. Moving a [`Container`](containers/container.md) (drag,
  `moveTo`, `setX`) moves everything inside it, because children only ever store their own offset. Use
  `getAbsoluteX()`/`getAbsoluteY()` for the position in the window. A container translates the canvas by its
  position before drawing its children, which is why an element draws itself at its own `getX()`/`getY()`.
  Layouts set child positions for you.
- Children of a container draw in ascending index order, so **later children draw on top**.
  `addElement(el)` appends. `addElement(el, index)` picks the slot. Top-level containers work the same
  way through `Window.addContainer`.
- Containers **clip** their children to their own box by default (`setClipping(false)` turns that off).

## How input finds its target

The [`InputProcessor`](input/input-and-focus.md#reference-the-input-types) runs once per frame on the render thread.

**Hit-testing.** Starting from the highest-index top-level container that contains the point, it descends
into children, always preferring the highest-index child that contains the point. The result is the
*topmost element* under the cursor. If that element has no click listener, the click goes up to the nearest
container above it that has one, so clicking a `Label` inside a clickable `Panel` fires the panel's listener. Only
one listener runs: if the label has a listener of its own, the panel's does not fire. Drags and the cursor shape
go up the same way (see the table).

| Input | What happens |
|---|---|
| Mouse move | Topmost element becomes `hovered` (and every container on the path to it); the OS cursor follows its [`CursorMode`](input/input-and-focus.md#hover-and-cursor-shape), or the nearest ancestor's if it has none; an active drag receives `onDragUpdate` |
| Mouse press | Runs the `onMouseClick` listener of the topmost element, or of the nearest ancestor with one (on **press**, for any button); moves keyboard focus if the element is [`Focusable`](input/input-and-focus.md#keyboard-focus), otherwise clears focus; with the **left** button, starts a drag on the nearest [`Draggable`](input/input-and-focus.md#dragging) at or above the element |
| Mouse release | The left button ends the drag; other buttons do not |
| Scroll | Goes to the innermost [`Scrollable`](input/input-and-focus.md#scrolling) under the cursor, whatever child is directly beneath |
| Key / char | Goes to the focused `Focusable`, except Tab (focus traversal) and Escape (closes a popup/modal first) |

Two special layers sit above normal containers:

- A **popup** (dropdown list, spell-check suggestions) is one temporary container. A press *outside* it
  closes it, and the press is consumed.
- A **modal** dims the window (or the [area](containers/modal-container.md#limiting-the-modal-to-part-of-the-window) it is limited to) and traps clicks, scrolling and Tab inside itself.

Details on each: [Input and focus](input/input-and-focus.md), [Window](core/window.md),
[ModalContainer](containers/modal-container.md).

## Rendering backends

You never call these directly, but it helps to know what draws your UI:

- **Skia (via Skiko)** is the 2D engine on every platform. All widgets draw through the [`Renderer`](reference/custom-elements.md#the-renderer)
  interface (`drawQuad`, `drawBorder`, `drawLine`, `drawPolyline`, `drawCircle`, `drawText`, `drawGradient`, `drawGradientBorder`, `drawImage`,
  clips, translations, scales and opacity layers).
- **OpenGL 3.3 core** backs Skia on Windows and Linux (`SkikoRenderer`).
- **Metal** backs Skia on macOS (`MetalSkikoRenderer`). macOS windows are created without an OpenGL
  context.

`Renderer` calls are only legal on the render thread, between `begin()` and `end()`. That is
already true inside `render`, `onRender` and `Transition.render`.

## Memory and lifetime

- Fonts (`Typeface`) and decoded images are cached process-wide and shared. They are never closed.
- A text overlay owns a native Skia `Font`. Call `dispose()` when permanently discarding a [`TextOverlay`](overlays/text-overlay.md),
  [`TextFieldOverlay`](overlays/text-field-overlay.md), [`TextAreaOverlay`](overlays/text-area-overlay.md) or [`SliderOverlay`](overlays/slider-overlay.md) to release it.
- Themeable elements register with [`ThemeManager`](styling/theming.md) through a weak set, so you do not need to unregister them.
