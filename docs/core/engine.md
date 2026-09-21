# Engine
[Documentation](../README.md) › [Core](README.md)

`me.piitex.engine.Engine` is the entry point that turns a [`Window`](window.md) object into a real, running window.
It has one public method.

```java nocompile
public void start(Window window);
```

## What `start` does
1. Creates the GLFW window at the size and title from the window's [`WindowOptions`](window.md#windowoptions).
   The window is created **hidden**.
2. Attaches the clipboard and the mouse-cursor bridges to the window.
3. Creates the graphics context:
   - **macOS:** a Metal layer on the window's view.
   - **Windows/Linux:** an OpenGL 3.3 core context.
4. Starts the **render thread** (see [Architecture](../architecture.md#two-threads)).
5. Loops on the main thread, delivering GLFW events and servicing the clipboard and cursor, until the
   window is closed.
6. Shows the window once the first frame has been drawn, so you never see a blank or garbage frame.

**`start` blocks** until the user closes the window. Build all of your UI before calling it.

```java
Window window = new Window(new WindowOptions("My App").setDimensions(1280, 720));
// ... add containers and elements ...
new Engine().start(window);
// Nothing after this line runs until the window is closed.
```

## What the render thread does every frame
In order: apply a pending resize, process input, advance an animated window background, call `update(delta)`
on every enabled top-level container, update tooltips, clear and draw the window background, draw every
enabled top-level container, draw the tooltip, and present. See [Architecture](../architecture.md#one-frame)
for the full list.

## Resizing
When the user resizes the OS window, the engine recreates its drawing surface and keeps drawing. The
window's contents are **not** re-laid-out automatically. If you want a container to follow the window size,
handle that yourself in an `onUpdate` hook. [`WindowOptions`](window.md#windowoptions) keeps the size the window was *created* with.
See [Known limitations](../reference/known-limitations.md).

## One engine, one window
An `Engine` drives exactly one [`Window`](window.md). Static helpers such as [`MouseTracker`](../input/input-and-focus.md#reading-the-mouse-directly), [`TooltipManager`](../input/tooltips.md), [`Clipboard`](../input/input-and-focus.md#clipboard)
and `CursorManager` assume that. Create a single `Engine` per process.
