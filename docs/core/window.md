# Window and WindowOptions

[Documentation](../README.md) › [Core](README.md)

A `Window` is the top level of your UI. It holds the list of top-level containers, the window background, and
the state for keyboard focus, popups and modals. You create exactly one and pass it to
[`Engine.start`](engine.md).

```java
Window window = new Window(new WindowOptions("My App").setDimensions(1280, 720));
```

## WindowOptions

A fluent builder. Every setter returns the same `WindowOptions`, so calls chain.

```java
WindowOptions options = new WindowOptions("My App") // title is final
        .setDimensions(1280, 720)
        .setResizable(true);
```

| Setter | Default | Currently honored by the engine? |
|---|---|---|
| `new WindowOptions(title)` | none | Yes, becomes the OS window title |
| `setDimensions(width, height)` | `0, 0` | **Yes.** Set this, or you get a zero-size window |
| `setFullscreen(boolean)` | `false` | **Yes.** Covers the primary monitor (exclusive), creation-time only |
| `setStyle(WindowStyle)` | `STANDARD` | **Yes.** `BORDERLESS` removes the title bar, title and close/minimize/maximize controls. Can be changed at runtime with `Window.setStyle` |
| `setWindowed(boolean)` | `true` | **Yes.** `false` is `BORDERLESS`, `true` is `STANDARD`. With `setFullscreen(true)` it gives borderless fullscreen |
| `setAlwaysOnTop(boolean)` | `false` | **Yes.** Keeps the window above others. Can be changed at runtime with `Window.setAlwaysOnTop` |
| `setVsync(boolean)` | `true` | **Yes**, and can be changed while running. On: frames follow the display refresh and `maxFPS` is ignored. Off: the loop runs at `maxFPS` |
| `setShowCursor(boolean)` | `true` | **Yes.** `false` hides the OS cursor over the window (it still moves freely) |
| `setMaxFPS(int)` | `60` | **Yes**, and can be changed while running. Only used while vsync is off. `0` or less means unlimited |
| `setHandleFocus(boolean)` | `false` | **Yes.** Clears keyboard focus when the OS window loses focus |
| `setResizable(boolean)` | `true` | **Yes.** `false` locks the window size |
| `setIcon(Image)` | `null` | **Yes.** An image from `ImageLoader.load(...)`. Wins over `setIconPath`. On macOS it sets the Dock icon. Can be changed at runtime with `Window.setIcon` |
| `setIconPath(String)` | `null` | **Yes.** File path or classpath resource. Same platform behavior as `setIcon` |

The getters (`getTitle`, `getWidth`, `getHeight`, `isFullscreen`, ...) return what was stored. The
engine uses `getWidth()`/`getHeight()` for the window background, for clamping the mouse position, and for
[`ModalContainer`](../containers/modal-container.md) centering. `getWidth()`/`getHeight()` follow the real
window size, including after a user resize or a fullscreen switch.

Except for the ones with a `Window` method in the table below, the applied options are read once, when the window is created. Changing them afterwards has no effect.

## Window styles at runtime

`WindowStyle` is `STANDARD` (normal OS frame) or `BORDERLESS` (no title bar, title or window controls). These `Window` methods work while the app is running, from any thread:

| Method | Description |
|---|---|
| `setStyle(WindowStyle)` | Adds or removes the OS frame of the live window |
| `setSize(width, height)` | Resizes the window; the UI follows |
| `center()` | Centers on the primary monitor |
| `setTitle(String)` | Changes the OS title |
| `setAlwaysOnTop(boolean)` | Floats above other windows |
| `setIcon(Image)` | Changes the icon (Dock icon on macOS) |
| `setResizable(boolean)` | Allows or forbids user resizing; `setSize` still works |
| `setFullscreen(boolean)` | Enters or leaves fullscreen and remembers the windowed bounds. Follows the style: `BORDERLESS` is borderless fullscreen, `STANDARD` is exclusive |
| `setPosition(x, y)` | Moves the top-left corner, in screen coordinates |
| `setSizeLimits(minW, minH, maxW, maxH)` | Limits user resizing. A negative value means no limit on that side |
| `setOpacity(float)` | 0 to 1 for the whole window. Not supported everywhere |
| `setCursorVisible(boolean)` | Shows or hides the OS cursor over the window |
| `setVsync(boolean)`, `setMaxFPS(int)` | Same as the options, picked up next frame |
| `setMinimized(boolean)`, `setMaximized(boolean)` | Set the state directly, so you can pass a flag instead of picking between `minimize()` / `maximize()` / `restore()`. A no-op if already in that state |
| `minimize()`, `maximize()`, `restore()`, `focus()` | The usual window actions |
| `hide()`, `show()`, `isHidden()` | Hide the window while the app keeps running (see below) |
| `close()` | Ends the engine loop, like clicking the close button |

A splash screen that becomes the main window, all in one OS window:

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

## Minimize and maximize events

```java
window.onMinimize(() -> pauseMusic());
window.onMaximize(() -> log.info("maximized"));
window.onRestore(() -> resumeMusic()); // back to normal size from either state
```

They fire whether the user clicked the title bar button or your code called `minimize()` / `maximize()`, and run on the render thread like other callbacks. `isMinimized()` and `isMaximized()` give the current state. Un-minimizing a window that was maximized before returns it to maximized, and fires `onRestore` only, not `onMaximize`. Each listener is a single slot: setting one replaces the previous.

## Close event

```java
window.onClose(() -> {
    saveSettings();
    database.close();
});
```

Fires once however the window closes: the user's close button, `window.close()`, or the engine stopping. It runs after the last frame has rendered and before `Engine.start` returns, so it is before the JVM exits when `start` is the last call in `main`. It runs on the main thread with the window already hidden, and exit waits for it to return.

`Scheduler` tasks no longer run at that point, so do the cleanup directly instead of through `runLater` or `async`. An exception is logged and shutdown continues. It does not run if the process is killed or something calls `System.exit`.

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
window.setIcon(otherIcon); // at runtime
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
| `addContainer(Container)` | Appends at `lastIndex + 1` (or `0` if empty) |
| `addContainers(Container...)` / `addContainers(Collection)` | Appends several in order, each at `lastIndex + 1`. Null entries are skipped |
| `addContainer(Container, int index)` | Puts it at `index`, replacing whatever was there |
| `removeContainer(int index)` | Removes by index; returns whether something was removed |
| `removeContainer(Container)` | Removes every index that holds that container |
| `removeContainer(int index, Container)` | Removes only if that container is at that index |
| `getContainers()` | The live `ConcurrentSkipListMap<Integer, Container>` |

Adding a container to a window calls `container.setWindow(window)`. That is how a nested element later finds its
window (`element.getWindow()`), which widgets such as the dropdown need to open popups.

The map is concurrent, so adding or removing a container from another thread will not throw. The frame
loop can still observe the change mid-frame.

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
  yourself **pins** it, exactly like every other themed property, so it survives later theme switches. There
  is no unpin; construct a fresh `Window` to follow the theme again.
- **Your root container covers the window background.** [`Element`](element.md) defaults to an opaque white fill, so a
  full-window container must be made transparent to let the window background show:

```java
root.getStyling().setBackgroundColor(Color.TRANSPARENT);
root.getStyling().setHoverColor(Color.TRANSPARENT);
root.getStyling().setBorderThickness(0f);
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

## Modals

```java
ModalContainer modal = new ModalContainer(320, 160);
modal.show(window); // calls window.showModal(modal)
modal.close(); // calls window.closeModal()
```

`showModal`/`closeModal`/`getModal` exist on `Window`, but you normally use the [`ModalContainer`](../containers/modal-container.md) methods.
Showing a modal closes any popup and any previous modal, and clears focus. See
[ModalContainer](../containers/modal-container.md).

## Input manager

`window.getInputManager()` returns the [`InputManager`](../input/input-and-focus.md#reference-the-input-types) that GLFW callbacks feed. You rarely need it.
The [`InputProcessor`](../input/input-and-focus.md#reference-the-input-types) drains its event queue every frame. See
[Input and focus](../input/input-and-focus.md).
