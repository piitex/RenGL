# Window and WindowOptions

[Documentation](../README.md) › [Core](README.md)

A `Window` is the top level of your UI. It holds the list of top-level containers, the window background, and
the state for keyboard focus, popups and modals. You create exactly one and pass it to
[`Engine.start`](engine.md).

```java
Window window = new Window(new WindowOptions("My App").setDimensions(1280, 720));
```

## WindowOptions

The settings of a window, in one place. Every setter returns the same `WindowOptions`, so calls chain. Build it
before the window opens and the window opens with those settings.

```java
WindowOptions options = new WindowOptions("My App")
        .setDimensions(1280, 720)
        .setResizable(true);
```

The options stay connected to their window. Call a setter on a running window and the window changes, from any
thread:

```java
window.getWindowOptions().setWidth(1280);           // resizes the window
window.getWindowOptions().setTitle("My App - Level 2");
window.getWindowOptions().setFullscreen(true);
```

The [`Window` methods](#window-styles-at-runtime) (`setSize`, `setTitle`, ...) do the same thing. One `WindowOptions`
drives one `Window`: passing the same instance to a second window throws.

| Setter | Default | Currently honored by the engine? |
|---|---|---|
| `new WindowOptions(title)` / `setTitle(String)` | none | The OS window title. **Live** |
| `setDimensions(width, height)`, `setWidth(int)`, `setHeight(int)` | `0, 0` | The size of the window. Set this, or you get a zero-size window. **Live:** resizes the window, and `onResize` fires |
| `setFullscreen(boolean)` | `false` | Covers the primary monitor. On Windows and Linux a `STANDARD` window takes the monitor exclusively and a `BORDERLESS` one is borderless fullscreen; macOS always uses a frameless window the size of the screen (no display mode switch). **Live:** leaving restores the earlier position and size, and the maximized state |
| `setMaximized(boolean)` | `false` | Starts maximized, or maximizes and restores the live window. Ignored by a window that isn't resizable. During fullscreen the request waits until fullscreen ends. `isMaximized()` also follows the user's own title bar clicks. **Live** |
| `setStyle(WindowStyle)` | `STANDARD` | `BORDERLESS` removes the title bar, title and close/minimize/maximize controls. **Live** |
| `setWindowed(boolean)` | `true` | `false` is `BORDERLESS`, `true` is `STANDARD`. With `setFullscreen(true)` it gives borderless fullscreen. **Live** |
| `setAlwaysOnTop(boolean)` | `false` | Keeps the window above others. **Live** |
| `setVsync(boolean)` | `true` | On: frames follow the display refresh and `maxFPS` is ignored. Off: the loop runs at `maxFPS`. **Live,** picked up next frame |
| `setShowCursor(boolean)` | `true` | `false` hides the OS cursor over the window (it still moves freely). **Live** |
| `setMaxFPS(int)` | `60` | Only used while vsync is off. `0` or less means unlimited. **Live,** picked up next frame |
| `setHandleFocus(boolean)` | `false` | Clears keyboard focus when the OS window loses focus. **Live,** checked every frame |
| `setRenderer(RenderAPI)` | `AUTO` | **Live, but the window is rebuilt:** a window is made for one API, so on a running window the engine closes the OS window and opens it again with the new one (see [Switching the renderer](#switching-the-renderer)). `AUTO` is Metal on macOS and OpenGL elsewhere (or the `rengl.backend` system property); `METAL` and `VULKAN` ask for that API and fall back to the default, with a warning, where it can't be had. **`OPENGL` on macOS is a fatal error** (`IllegalStateException`) that says to use `VULKAN`, because macOS windows have no OpenGL context. `Window.getRenderer()` reports what was used. [3D](../3d/README.md) needs `OPENGL` or `VULKAN`. See [Rendering APIs](rendering-apis.md) |
| `setResizable(boolean)` | `true` | `false` locks the window size (`setDimensions` still works). **Live** |
| `setIcon(Image)` | `null` | An image from `ImageLoader.load(...)`. Wins over `setIconPath`. On macOS it sets the Dock icon. **Live** |
| `setIconPath(String)` | `null` | File path or classpath resource. Same platform behavior as `setIcon`. **Live** |
| `setSplashEnabled(boolean)` | `true` | Whether a [splash screen](engine.md#the-splash-screen) plays over the first window before the application appears. `false` opts out. Read once when `Engine.start` opens the window, so it is **not live**; windows opened later have no splash |
| `setSplashSoundEnabled(boolean)` | `true` | Whether the RenGL splash plays [its sound](engine.md#the-splashs-sound). Needs `rengl-audio` on the classpath. **Not live** |
| `setSplash(Splash)` | `null` | Your own `Splash` instead of the RenGL one (see [your own splash](engine.md#your-own-splash)). `null` is the RenGL splash. Plays only while the splash is enabled. **Not live** |
| `addWarmUp(WarmUp)` | none | Work to finish under the splash, such as preparing a 3D viewport, so the application's first frame does not stall (see [warm-ups](engine.md#loading-under-the-splash)). Runs only while there is a splash. **Not live** |
| `setWarmUpTimeLimit(float)` | `30` | Seconds all the warm-ups may take together before the rest are abandoned and the application starts |
| `setWarmUpDelay(float)` | `2` | Seconds the splash plays before the warm-ups' render-thread steps begin, so a stall lands on a settled picture. Their background `prepare` work is not held back. `0` starts the steps at once |

The getters (`getTitle`, `getWidth`, `getHeight`, `isFullscreen`, ...) return the current state. The
engine uses `getWidth()`/`getHeight()` for the window background, for clamping the mouse position, and for
[`ModalContainer`](../containers/modal-container.md) centering. `getWidth()`/`getHeight()` follow the real
window size, including after a user resize or a fullscreen switch.

A live setter asks the OS for the change, and the OS can adjust it: a window can't be bigger than the screen allows,
for example. `getWidth()`/`getHeight()` then report the size the window really has.

## Switching the renderer

```java nocompile
window.getWindowOptions().setRenderer(RenderAPI.VULKAN); // on a running window
```

A window is made for one graphics API, so the engine does not change it in place. It closes the OS window and opens a
new one with the API asked for, at the same size and position. The `Window` itself carries on: its containers,
[scenes](../game/scenes.md), focus and settings are kept, and `onClose` does **not** run. Safe from any thread.

- The window **flickers**: it disappears for a few frames and comes back once the new renderer has drawn.
- [3D viewports](../3d/README.md) free their GPU objects with the old renderer and start afresh on the new one. On
  Metal they draw nothing.
- It works on the first window too; the engine keeps running.
- `Window.getRenderer()` reports the old API until the new window is up.
- If the new API can't start, the old one is restored and an error is logged. Asking for `OPENGL` on macOS throws
  `IllegalStateException` from the setter and leaves the option as it was.
- Setting the API a window already uses does nothing.

## Window styles at runtime

`WindowStyle` is `STANDARD` (normal OS frame) or `BORDERLESS` (no title bar, title or window controls). These `Window` methods work while the app is running, from any thread. The ones that match a `WindowOptions` setter are
the same call by another name:

| Method | Description |
|---|---|
| `setStyle(WindowStyle)` | Adds or removes the OS frame of the live window |
| `setSize(width, height)` | Resizes the window; the UI follows |
| `center()` | Centers on the primary monitor |
| `setTitle(String)` | Changes the OS title |
| `setAlwaysOnTop(boolean)` | Floats above other windows |
| `setIcon(Image)` | Changes the icon (Dock icon on macOS) |
| `setResizable(boolean)` | Allows or forbids user resizing; `setSize` still works |
| `setFullscreen(boolean)` | Enters or leaves fullscreen and remembers the windowed bounds. See the `setFullscreen` row of the options table for what each style gives |
| `setPosition(x, y)` | Moves the top-left corner, in screen coordinates |
| `setSizeLimits(minW, minH, maxW, maxH)` | Limits user resizing. A negative value means no limit on that side |
| `setOpacity(float)` | 0 to 1 for the whole window. Not supported everywhere |
| `setCursorVisible(boolean)` | Shows or hides the OS cursor over the window |
| `setCursorLocked(boolean)`, `isCursorLocked()` | Locks the mouse to the window: the OS cursor is hidden and can't leave, and `Input` reports only how far the mouse moves. See [locking the mouse](../input/game-input.md#locking-the-mouse) |
| `setCursorPosition(x, y)` | Moves the OS cursor, in window points. Only while the window has focus |
| `isFocused()` | Whether the window has the OS focus |
| `setVsync(boolean)`, `setMaxFPS(int)` | Same as the options, picked up next frame |
| `setMinimized(boolean)`, `setMaximized(boolean)` | Set the state directly, so you can pass a flag instead of picking between `minimize()` / `maximize()` / `restore()`. A no-op if already in that state. `setMaximized` is the same as `getWindowOptions().setMaximized(...)` |
| `minimize()`, `maximize()`, `restore()`, `focus()` | The usual window actions |
| `hide()`, `show()`, `isHidden()` | Hide the window while the app keeps running (see below) |
| `close()` | Closes the window, like clicking its close button. Closing the first window ends the engine |
| `open()` | Opens the window beside the running engine's others, or again after it was closed. See [Several windows](engine.md#several-windows) |

(The engine plays a [splash](engine.md#the-splash-screen), the RenGL one unless you set your own or switch it off, over
the first window before any of this appears; the code below is for an application's own loading screen after that.)

A loading screen that becomes the main window, all in one OS window:

```java
Window window = new Window(new WindowOptions("My App")
        .setDimensions(480, 270)
        .setStyle(WindowStyle.BORDERLESS)
        .setResizable(false));
// ... add the splash container, then Engine.start(window) ...

// later, when loading finishes (any thread):
window.setStyle(WindowStyle.STANDARD);
window.setSize(1280, 720);
window.center();
window.removeContainer(splash);
window.addContainer(mainUi);
```

The engine drives one OS window, so "replacing a window" means changing this one in place as above. The window is not recreated and a `Window` object cannot be swapped out for another while running.

## Frame rate

The engine measures its own frame rate. These getters are safe from any thread:

| Method | Description |
|---|---|
| `getFps()` | Frames per second, averaged over half a second and updated that often. `0` until the engine has run for half a second |
| `getFrameTime()` | The last frame's length in milliseconds, including any wait for vsync or `maxFPS`. Unlike the update `delta`, it is not capped at 0.25 s, so a stall shows in full |
| `getFrameCount()` | Frames started since the engine began |

An FPS label that refreshes itself:

```java
TextOverlay fps = new TextOverlay("", 13f, Color.WHITE);
fps.setPosition(8, 8);
fps.onUpdate(event -> fps.setText(Math.round(window.getFps()) + " FPS"));
root.addElement(fps);
```

With vsync on, the rate follows the display, so it measures whether frames keep up rather than how fast they
could go.

## Minimize and maximize events

```java
window.onMinimize(() -> pauseMusic());
window.onMaximize(() -> log.info("maximized"));
window.onRestore(() -> resumeMusic()); // back to normal size from either state
```

They fire whether the user clicked the title bar button or your code called `minimize()` / `maximize()`, and run on the render thread like other callbacks. `isMinimized()` and `isMaximized()` give the current state. Un-minimizing a window that was maximized before returns it to maximized, and fires `onRestore` only, not `onMaximize`. Each listener is a single slot: setting one replaces the previous. Pass `null` to remove one.

## Dragging files to and from the window

### Files dropped on the window

```java
window.onFileDrop(paths -> {
    for (Path path : paths) archive.add(path);
});
```

Fires when the user drops files or folders on the window from Finder / Explorer, with their paths in the order the OS
gave them. It runs on the render thread, is a single slot, and `null` removes it; with no listener a drop does nothing.
The paths are only names: nothing is read, and a folder is not expanded into its files.
`me.piitex.examples.demo.TableDemo` lists the folder that is dropped on it.

To know **what** the files landed on, use `onFileDropAt`, which also carries the pointer in window coordinates:

```java
window.onFileDropAt(drop -> {
    Folder target = folderAt(drop.x(), drop.y());      // the row, panel or folder under the pointer
    archive.add(drop.files(), target);
});
```

Both listeners run for a drop if both are set.

### Hover feedback (Windows)

```java
window.onFileDragOver(hover -> highlight(folderAt(hover.x(), hover.y())));
window.onFileDragLeave(() -> highlight(null));
window.onFileDropAt(drop -> { highlight(null); archive.add(drop.files(), folderAt(drop.x(), drop.y())); });
```

`onFileDragOver` runs while files are being dragged over the window, with where they are and which files they are, so
the folder or panel a drop would land on can light up. A fast pointer reaches it as the newest position, not every step.
`onFileDragLeave` runs when the files leave or the drag is cancelled. **A drop does not call it**, so clear the
highlight in the drop listener as well. The pointer shows the "not allowed" sign over a window with no drop listener.

On Windows these are delivered by an OLE drop target the window registers for itself. **Elsewhere GLFW reports a drop
only when it happens**: `onFileDragOver` and `onFileDragLeave` never run, and `onFileDropAt` still gives the position
of the drop. Show a standing "Drop files here" hint if the application must work there.

Only files that exist on disk arrive. A drag of something that is not a list of files (text, a picture), or of files
that only exist inside another program such as the contents of a zip folder opened in Explorer, is not taken.

### Dragging files out of the window (Windows)

```java
window.startFileDrag(() -> {
    Path folder = Files.createTempDirectory("zip-drag");
    archive.extractTo(entry, folder);
    return List.of(folder.resolve(entry.name()));
}, outcome -> log.info("drag ended: {}", outcome));
```

Call it from the press or move that begins the drag, while the left button is held: the user then carries the files
with the pointer and lets go over an Explorer window or the desktop, which copy them. Called with the button already
up, it does nothing and reports `CANCELLED`. A list of existing paths works too: `window.startFileDrag(paths, done)`.

The first argument is a `FileDrag.Source`, asked for the files **only when the user lets go** over something that
takes them. It is the right place for extracting from an archive: a drag that is cancelled or dropped nowhere
extracts nothing. It runs on the engine's main thread, in the middle of Explorer's request, so it must not touch the
UI, and Explorer waits for it. It is asked once; if it throws, the drop fails and `done` gets `FAILED`. The paths it
returns must go on existing after the drop, because the receiver may still be copying them: put them in a temporary
folder and delete it when the application exits.

The second argument, which may be null, is told how it ended, on the render thread: `DROPPED`, `CANCELLED` (Escape, or
let go over something that refused), `UNSUPPORTED` (this platform cannot do it) or `FAILED`. The files are copied,
never moved. While the drag lasts the window keeps drawing and updating; only the engine's main thread, which handles
the operating system's window events, is held by it. Dropping the files back onto the window that started the drag is
ignored, so an application does not add its own extraction to itself.

`FileDrag.isSupported()` says whether the platform can do this.
[`TreeView.setFileDragSource`](../containers/tree-view.md#dragging-rows-out-of-the-window) starts one for you when a
row is dragged past the edge of the window. Another element can do the same by implementing `FileDraggable` next to
`Draggable`: when the pointer leaves the window in the middle of its drag, `fileDragOut()` is asked for a source, and
its own drag is cancelled with `onDragCancel()` if it gives one.

The drag shows the system's standard cursor, with no picture of the files under it.

## Resize event

```java
window.onResize((width, height) -> {
    sidebar.setHeight(height);
    content.setBounds(240, 0, width - 240, height);
});
```

Fires with the new size whenever the window's logical size changes, whether the user dragged the border or
your code called `setSize`. `WindowOptions.getWidth()`/`getHeight()` already hold the new size when it runs.
It also fires once when the window opens at a different size than you asked for (maximized, or shrunk to fit the
screen); `getDesignWidth()`/`getDesignHeight()` on the options still hold the size you asked for. It does not fire
for a DPI or monitor change that leaves the logical size the same. It runs on the render
thread, is a single slot, and `null` removes it. [`ResizableContainer`](../containers/resizable-container.md)
follows the window on its own, so you only need this for layouts you size by hand.

## Close event

```java
window.onClose(() -> {
    saveSettings();
    database.close();
});
```

Fires once however the window closes: the user's close button, `window.close()`, or the engine stopping. It runs after the last frame has rendered and before `Engine.start` returns, so it is before the JVM exits when `start` is the last call in `main`. It runs on the main thread with the window already hidden, and exit waits for it to return.

`Scheduler` tasks no longer run at that point, so do the cleanup directly instead of through `runLater` or `async`. An exception is logged and shutdown continues. It does not run if the process is killed or something calls `System.exit`.

### Asking before closing

```java nocompile
window.onCloseRequest(() -> {
    if (!hasUnsavedChanges()) window.close();
    else askToSave(window::close); // a dialog that closes the window once the user has chosen
});
```

With a listener set, the close button (and Quit on macOS) no longer closes the window. The listener runs on the render
thread instead, and closes the window with `window.close()` when it is ready. `close()` itself, and the engine
stopping, still close at once. Null gives the close button back.

## Hiding the window

`hide()` removes the window from the screen without stopping the app. The engine, [`Scheduler`](scheduler.md) timers and background tasks keep running, so a hidden window can bring itself back:

```java
window.hide();
Scheduler.after(5f, window::show);
```

Nothing else can bring a hidden window back: there is no tray icon support, and on macOS clicking the Dock icon does not reopen it. Make sure a timer, a background task or a [native menu](../input/input-and-focus.md) item calls `show()`. Calling `hide()` before `Engine.start` makes the window open hidden.

## Icons

```java
Image icon = ImageLoader.load("src/main/resources/icon.png");
new WindowOptions("My App").setIcon(icon); // at startup
window.getWindowOptions().setIcon(otherIcon); // at runtime, same as window.setIcon(otherIcon)
```

Windows and Linux set a real window icon. macOS windows have no icon of their own, so the image becomes the Dock and app-switcher icon instead. Use a square PNG, 256x256 or larger.

## Top-level containers

A window holds its containers in a sorted map keyed by an `int` index. **Lower indexes draw first;
higher indexes draw on top and win hit-testing.**

```java
Container background = new Container(1280, 720);
Container hud = new Container(1280, 720);

window.addContainer(background); // index 0
window.addContainer(hud); // index 1 (last index + 1)
window.addContainer(new Container(1280, 720), 100); // pick an explicit index
```

| Method | Description |
|---|---|
| `addContainer(Container)` | Appends at `lastIndex + 1`, never below `0` (`0` if empty) |
| `addContainers(Container...)` / `addContainers(Collection)` | Appends several in order, each at `lastIndex + 1`. Null entries are skipped |
| `addContainer(Container, int index)` | Puts it at `index`, replacing whatever was there |
| `removeContainer(int index)` | Removes by index; returns whether something was removed |
| `removeContainer(Container)` | Removes every index that holds that container |
| `removeContainer(int index, Container)` | Removes only if that container is at that index |
| `clear()` | Removes every top-level container |
| `getContainers()` | The live `ConcurrentSkipListMap<Integer, Container>` |

Adding a container to a window calls `container.setWindow(window)`. That is how a nested element later finds its
window (`element.getWindow()`), which widgets such as the dropdown need to open popups.

The map is concurrent, so adding or removing a container from another thread will not throw. The frame
loop can still observe the change mid-frame.

[Scenes](../game/scenes.md) from `window.getSceneManager()` put their containers in this map too, at large negative
indexes, so they draw below every container you add and appear in `getContainers()`. `clear()` takes them off the
window as well; use the scene manager's `clear()` to remove scenes.

## Background

The window background is what the canvas is cleared to before any container draws.

```java
window.setBackgroundColor(Color.BLACK); // solid color (cheap)
window.setBackgroundColor(new LinearGradient(Color.BLUE, Color.BLACK)); // static gradient
window.setBackgroundColor(new ScrollingGradient(Color.BLUE, Color.BLACK, 500f)); // animated
window.setBackgroundColor(new RainbowColor(20f, 0.6f, 0.15f, 1f)); // slow, dark RGB cycle
```

- A plain [`Color`](../styling/colors-and-paints.md#color), including a [`RainbowColor`](../styling/colors-and-paints.md#rainbowcolor-rgb-cycling), is cleared directly. A gradient is drawn as a full-window quad every frame.
- `null` resets to white.
- `Window` is [`Themeable`](../styling/theming.md) and registers itself, so by default the background follows
  `Theme.getWindowBackground()` on every `ThemeManager.setCurrent(...)`. Calling `setBackgroundColor`
  yourself **pins** it, exactly like every other themed property, so it survives later theme switches.
  `followThemeBackground()` unpins it and applies the current theme's color right away.
- **A root container only covers the window background if you give it one.** [`Element`](element.md) defaults to
  a transparent fill, so a plain full-window `Container` lets the window background show through. A container
  that has opted into the theme (`useTheme()`), or that you gave a background color, paints over it.

### Background effects

```java
window.setBackground(new ConstellationBackground());
window.setBackground(null); // remove it
```

`setBackground` attaches one of the animated [background effects](../styling/backgrounds.md) to the whole
window. It draws over the flat `setBackgroundColor` fill and behind every container, at the window's size.

It is themed like the color: a theme can ask for an effect (`Theme.getWindowEffect()`), which the window makes for itself
and keeps up to date, until you call `setBackground`, which **pins** it, `null` included. `followThemeBackground()` unpins
both the color and the effect.

A window holds **one** background effect, so `setBackground` replaces the previous one. The same hook takes a
[`Background3D`](../3d/background-3d.md), which puts a live 3D scene behind the whole window with the UI drawn over it:

```java nocompile
window.setBackground(new Background3D(window, scene)); // needs a window on OpenGL or Vulkan
```

## Focus

At most one element has keyboard focus. Only [`Focusable`](../input/input-and-focus.md#keyboard-focus)
elements can hold it.

```java
Element focused = window.getFocusedElement(); // or null
window.requestFocus(field); // gives focus, firing onFocusLost/onFocusGained
window.requestFocus(null); // clears focus
window.focusNext(); // Tab
window.focusPrevious(); // Shift+Tab
```

The engine already handles clicks (focus goes to the clicked [`Focusable`](../input/input-and-focus.md#keyboard-focus), or is cleared) and Tab.
Tab order is described in [Input and focus](../input/input-and-focus.md#tab-order).

## Popups

A popup is a single floating container above everything else. The dropdown list and the spell-check
suggestion list are popups. Use it for context menus and similar transient UI.

```java
// A layout positions its own children, so the menu items need no manual x/y.
VerticalLayout menu = new VerticalLayout(100, 100, 140, 60); // x, y, width, height in window space
menu.setPadding(8);
menu.setSpacing(6);
menu.useTheme();

TextOverlay copy = new TextOverlay("Copy", 14f, Color.BLACK);
copy.setCursorMode(CursorMode.POINTER);
copy.onMouseClick(e -> {
    System.out.println("copy chosen");
    window.closePopup();
});
menu.addElement(copy);
menu.addElement(new TextOverlay("Paste", 14f, Color.BLACK));

window.showPopup(menu, () -> System.out.println("menu closed"));
```

- `showPopup(container, onDismiss)` closes any existing popup first, then adds `container`. Only one popup
  is open at a time.
- The popup closes on a mouse press **outside** it or on **Escape**. The outside press is consumed, so it
  does not also click whatever is underneath.
- `onDismiss` runs however the popup closes (outside click, Escape, `closePopup()`, or another popup
  replacing it). Use it to reset your own state.
- `closePopup()` closes it programmatically. `getPopup()` returns the current one or `null`.
- Popups are not clipped by parent containers such as a [`ScrollContainer`](../containers/scroll-container.md).
- A `Window` is a `PopupHost`, the interface the built-in dropdown, menus and pickers open through, so they work in the same
  way on a [screen in a 3D scene](../3d/scenes.md#dragging-and-popups). Elements find theirs with `getPopupHost()`.

### Following an element

A popup stays where you placed it. To keep it with the element that opened it, pass that element as the anchor:

```java
menu.setPosition(button.getAbsoluteX(), button.getAbsoluteY() + button.getHeight()); // place it first
window.showPopup(menu, button, () -> System.out.println("menu closed"));
```

- The popup keeps the offset from the anchor it had when shown, so it moves along when the anchor is dragged, scrolled
  or re-laid out.
- It stays inside the window, including when the window shrinks. A popup shown below its anchor flips above it when
  there is no room below (and there is above), and one shown above flips below the same way. Otherwise it is pushed
  back inside the edge. It returns to its normal place once there is room again.
- It closes on its own once the anchor is removed, disabled, or clipped entirely out of view (scrolled out of its
  scroll container, say). `onDismiss` runs as usual.
- `getPopupAnchor()` returns the anchor, or `null`.

The built-in popups (dropdown lists, pickers, context menus, menu bar menus, spelling suggestions) all follow the
element that opened them.

## Modals

```java
ModalContainer modal = new ModalContainer(320, 160);
modal.show(window); // calls window.showModal(modal)
modal.close(); // plays the modal's close animation, if any, then calls window.closeModal()
```

`showModal`/`closeModal`/`getModal` exist on `Window`, but you normally use the [`ModalContainer`](../containers/modal-container.md) methods.
`window.closeModal()` removes the top modal at once and skips its close animation; `closeModal(modal)` removes
a particular one, wherever it is in the stack.
Modals stack: showing one closes any popup and clears focus, but leaves the modals already open beneath it.
`getModal()` is the topmost, `getModals()` all of them bottom to top. See
[ModalContainer](../containers/modal-container.md).

## Input manager

`window.getInputManager()` returns the [`InputManager`](../input/input-and-focus.md#reference-the-input-types) that GLFW callbacks feed. You rarely need it.
The [`InputProcessor`](../input/input-and-focus.md#reference-the-input-types) drains its event queue every frame. See
[Input and focus](../input/input-and-focus.md).

`window.getInput()` returns the window's [`Input`](../input/game-input.md): the keyboard and mouse state a game polls
each frame, and the input contexts that are always active.
