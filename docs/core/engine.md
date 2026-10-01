# Engine
[Documentation](../README.md) › [Core](README.md)

`me.piitex.engine.Engine` is the entry point that turns [`Window`](window.md) objects into real, running windows.

```java nocompile
public void start(Window window);  // opens the first window and runs until it closes
public void open(Window window);   // opens another window, from any thread
public List<Window> getWindows();  // the windows open now
public void setPacingWindow(Window window); // the window whose vsync and max FPS pace the loop (null: the first)
public static Engine getRunning(); // the running engine, or null
```

## What `start` does
1. Creates the GLFW window at the size and title from the window's [`WindowOptions`](window.md#windowoptions).
   The window is created **hidden**.
2. Attaches the clipboard and the mouse-cursor bridges to the window.
3. Creates the graphics context:
   - **macOS:** a Metal layer on the window's view.
   - **Windows/Linux:** an OpenGL 3.3 core context.
   - **Any platform, if the window asks for [`RenderAPI.VULKAN`](rendering-apis.md):** a
     Vulkan instance, GPU and swapchain. If that can't be had, the platform default is used and a warning says why.
4. Starts the **render thread** (see [Architecture](../architecture.md#two-threads)).
5. Loops on the main thread, delivering GLFW events and servicing the clipboard and cursor, until the
   window is closed.
6. Shows the window once the first frame has been drawn, so you never see a blank or garbage frame.

**`start` blocks** until the user closes the window. Build the window's UI before calling it; other windows can be
[opened](#several-windows) at any time.

```java
Window window = new Window(new WindowOptions("My App").setDimensions(1280, 720));
// ... add containers and elements ...
new Engine().start(window);
// Nothing after this line runs until the window is closed.
```

## What the render thread does every frame
In order: apply a pending resize, process input, advance an animated window background, run
[`Scheduler`](scheduler.md) tasks and queued menu and file-dialog callbacks, run the [scenes'](../game/scenes.md)
fixed steps and updates on the [game clock](../game/game-time.md), call `update(delta)`
on every enabled top-level container, update tooltips, clear and draw the window background, draw every
enabled top-level container, draw the tooltip, and present. See [Architecture](../architecture.md#one-frame)
for the full list.

## Resizing
When the user resizes the OS window, the engine recreates its drawing surface and keeps drawing. The
window's contents are **not** re-laid-out automatically. A [`ResizableContainer`](../containers/resizable-container.md)
follows the window on its own; for anything else, resize it yourself from
[`window.onResize`](window.md#resize-event). [`WindowOptions`](window.md#windowoptions)' `getWidth()`/`getHeight()`
always hold the current size. See [Known limitations](../reference/known-limitations.md).

## Several windows
One engine can drive any number of windows. The first is the one given to `start`; open more with
`engine.open(window)` or `window.open()`, from any thread, before or after `start`. Each window has its own
containers, [scenes](../game/scenes.md), input, focus, popups and tooltips.

```java nocompile
Window game = new Window(new WindowOptions("Game").setDimensions(960, 540));
game.getSceneManager().set(EntityScene.load("scenes/level1.json"));
game.onClose(() -> System.out.println("game window closed"));
game.open(); // opens on the next pass of the main loop and shows once its first frame is drawn
```

- **Closing.** Closing a window (its close button or `window.close()`) closes only it, and its `onClose` runs.
  Closing the first window closes every other window too and ends the engine; `start` then returns.
- **Reopening.** A closed window can be opened again with `open()`. Its containers and scenes are where they were
  left.
- **One render thread.** Every window is updated and drawn on the same render thread, one after another each frame,
  so a slow window slows the others.
- **One game clock.** [Game time](../game/game-time.md) is shared: pausing it or changing its time scale affects the
  scenes of every window.
- **Frame rate.** One window paces the loop with its vsync and max-FPS settings: the first, unless
  `engine.setPacingWindow(window)` picks another. Only that window waits for the display; the others present as
  soon as they are drawn, at its rate, since several windows each waiting would slow or unsettle the frame rate.
  Their own vsync and max-FPS settings are ignored until they pace. An editor makes the game window it opens on
  Play the pacing window, so the game runs at its own settings; when that window closes, the first paces again.
- **Per-window helpers.** [`MouseTracker`](../input/input-and-focus.md#reading-the-mouse-directly) answers for the window
  being processed, and cursor changes go to the window they come from.

Create a single `Engine` per process; `start` refuses to run a second one. `me.piitex.examples.demo.MultiWindowDemo`
opens game windows from a button.
