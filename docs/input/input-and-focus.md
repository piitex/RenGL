# Input and focus

[Documentation](../README.md) › [Input](README.md)

Input is captured by GLFW on the main thread, queued, and handled by the render thread at the start of each frame.
Your callbacks therefore run on the render thread. See [Architecture](../architecture.md#how-input-finds-its-target)
for the pipeline.

## Mouse clicks

```java
element.onMouseClick(event -> {
    double x = event.getMouseX();
    double y = event.getMouseY();
    int button = event.getButton(); // GLFW.GLFW_MOUSE_BUTTON_LEFT (0), RIGHT (1), MIDDLE (2)
});
```

- Fires on **press** for **every** mouse button. Check `getButton()` if you only care about one.
- Only the **topmost** element under the cursor gets it. There is no event bubbling. A press on a child never
  reaches its parent container's listener.
- One listener per element. Setting another replaces it.
- Widgets with their own click behavior (button, checkbox, radio, dropdown, rich text area) install their handler
  themselves, so do not replace it. See [Element](../core/element.md#onmouseclick).

```java
import org.lwjgl.glfw.GLFW;

canvas.onMouseClick(event -> {
    if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
        System.out.println("left click at " + event.getMouseX() + ", " + event.getMouseY());
    } else if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
        System.out.println("context menu at " + event.getMouseX() + ", " + event.getMouseY());
    }
});
```

## Hover and cursor shape

Hovered state is set on the topmost element under the cursor and on every container on the path to it.

```java
boolean over = element.isHovered();
element.getStyling().setHoverColor(new Color(0.9f, 0.9f, 1f, 1f)); // background switches on hover
element.setCursorMode(CursorMode.POINTER); // OS cursor while hovered
```

`CursorMode` values: `DEFAULT`, `TEXT` (I-beam), `POINTER` (hand), `CROSSHAIR`, `RESIZE_HORIZONTAL`,
`RESIZE_VERTICAL`, `RESIZE_NWSE`, `RESIZE_NESW`, `RESIZE_ALL`, `NOT_ALLOWED`. Set it once per element and it just
works. A `null` mode falls back to `DEFAULT`. Text fields and areas set `TEXT`, buttons and dropdowns set
`POINTER`, and the split divider sets a resize cursor.

When the mouse leaves the window all hover state is cleared.

### Reading the mouse directly

`MouseTracker` is a static holder for the last mouse position, for the rare element that needs it outside
hit-testing (for example a mouse-following effect):

```java
if (MouseTracker.isInWindow()) {
    double x = MouseTracker.getX();
    double y = MouseTracker.getY();
}
```

`getX()/getY()` are stale while `isInWindow()` is false. Only read it from the render thread (an `onUpdate` or
`onRender` hook).

## Dragging

An element that implements `Draggable` gets continuous drag events:

```java
public interface Draggable {
    void onDragStart(double x, double y); // press landed on this element (topmost hit)
    void onDragUpdate(double x, double y); // mouse moved while the button is held
    void onDragEnd(double x, double y); // button released
}
```

`onDragUpdate` keeps firing even after the cursor leaves the element, and the dragged element keeps control of the
cursor shape for the whole drag. It fires **independently** of
`onMouseClick`, so implementing `Draggable` does not consume your click listener. [`Container`](../containers/container.md),
[`ScrollContainer`](../containers/scroll-container.md), [`TextFieldOverlay`](../overlays/text-field-overlay.md), [`TextAreaOverlay`](../overlays/text-area-overlay.md), [`SliderOverlay`](../overlays/slider-overlay.md) and the split divider implement it.

`Draggable` also has `default boolean canDrag(double x, double y)`, which is only consulted for a
[`Container`](../containers/container.md). A press that lands on a container returning `false` (a plain layout that
fills its parent) **passes up** to the nearest ancestor container that returns `true`, instead of being swallowed.
A non-container element, such as a button, always keeps its own presses. A `Container` returns `true` only after
`setDraggable(true)`.

A container can also claim the mouse ahead of its children with
`public boolean interceptsPointer(double x, double y)` (default `false`). `ResizableContainer` uses it for its
drag-to-resize handles, so they work even where a child sits under them.

For a draggable panel you rarely implement it yourself. Use `container.setDraggable(true)`. See
[Container](../containers/container.md#dragging).

The `x`/`y` passed to these callbacks (and to `onMouseClick`) are **window coordinates**, whichever container the
element is in. To compare one with the element's own geometry, use `toLocalX(x)`/`toLocalY(y)` (the same space as
`getX()`), or `getAbsoluteX()`/`getAbsoluteY()`.

A minimal custom draggable overlay:

```java
public class Handle extends Overlay implements Draggable {
    private double grabDx, grabDy;

    public Handle() {
        setBounds(0, 0, 24, 24);
        setCursorMode(CursorMode.RESIZE_ALL);
    }

    @Override
    public void onDragStart(double x, double y) {
        grabDx = x - getX();
        grabDy = y - getY();
    }

    @Override
    public void onDragUpdate(double x, double y) {
        setPosition(x - grabDx, y - grabDy);
    }

    @Override
    public void onDragEnd(double x, double y) {
    }
}
```

## Scrolling

An element that implements `Scrollable` receives wheel and trackpad input:

```java
public interface Scrollable {
    void onScroll(double deltaX, double deltaY); // raw GLFW offsets; +y = up/away from the user
}
```

The engine routes a scroll to the **innermost** `Scrollable` under the cursor, so a scrollable list nested inside
another scrolls whichever the cursor is over. It fires whatever child is directly under the cursor. It is not
limited to the scrollable's own empty background. [`ScrollContainer`](../containers/scroll-container.md), [`TextAreaOverlay`](../overlays/text-area-overlay.md), [`SpinnerOverlay`](../overlays/spinner-overlay.md)
and the dropdown's open list implement it. While a [modal](../containers/modal-container.md) is up, scrolling is limited
to it.

## Keyboard focus

At most one element holds keyboard focus. Only elements implementing `Focusable` can:

```java
public interface Focusable {
    void onFocusGained();
    void onFocusLost();
    void onCharTyped(int codepoint); // actual typed text (layout, shift and dead keys resolved)
    void onKeyPressed(int key, int action, int mods); // raw GLFW key; control keys and shortcuts
}
```

- **Clicking** a `Focusable` gives it focus. Clicking anything else, including empty space, clears focus.
- While focused, it receives every char and key event. `onKeyPressed` is never called for key *release*.
- `mods` is a GLFW modifier bitmask (`GLFW.GLFW_MOD_SHIFT`, `_CONTROL`, `_ALT`, `_SUPER`).
- For text, use `onCharTyped`. Use `onKeyPressed` for backspace, arrows, Enter and shortcuts.

```java
window.requestFocus(field); // give focus from code
window.requestFocus(null); // clear focus
Element focused = window.getFocusedElement();
```

`requestFocus` fires `onFocusLost` on the old element and `onFocusGained` on the new one. It is a no-op if the
element already has focus.

A custom focusable overlay:

```java
public class KeyLogger extends Overlay implements Focusable {
    public KeyLogger() {
        setBounds(0, 0, 200, 40);
        setFocusTraversable(true); // reachable with Tab
    }

    @Override public void onFocusGained() { getStyling().setBorderColor(Color.BLUE); }
    @Override public void onFocusLost() { getStyling().setBorderColor(Color.LIGHT_GRAY); }

    @Override
    public void onCharTyped(int codepoint) {
        System.out.println("typed: " + new String(Character.toChars(codepoint)));
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) System.out.println("escape");
    }
}
```

### Tab order

`Tab` moves focus to the next tab stop, `Shift+Tab` to the previous one, wrapping at the ends. An element is a
tab stop if it is **enabled**, `isFocusTraversable()` is true, **and** it implements `Focusable`.

```java
element.setFocusTraversable(true); // text fields, areas and dropdowns already do this
element.setTabIndex(0); // explicit order; -1 (default) = natural order
```

- **Natural order** is the order elements were added, walked depth-first through the window's containers in
  draw order.
- Elements with a **non-negative `tabIndex`** come first, in ascending order (ties keep natural order),
  followed by all natural-order elements. This is the same convention as HTML's `tabindex`.
- With a [modal](../containers/modal-container.md) open, Tab is trapped inside the modal.
- A mouse click grants focus regardless of `focusTraversable`, which only gates Tab.

### Reserved keys

- **Tab / Shift+Tab** are consumed for focus traversal before any focused widget sees them.
- **Escape** first closes an open popup, then an open modal (if it allows dismissing on Escape). Only if neither
  is open does it reach the focused widget.

## Keyboard shortcuts in text widgets

Ctrl **or** Cmd on any platform:

| Keys | Action |
|---|---|
| Ctrl/Cmd + A | Select all |
| Ctrl/Cmd + C | Copy |
| Ctrl/Cmd + X | Cut |
| Ctrl/Cmd + V | Paste |
| Shift + arrows / Home / End | Extend the selection |

Enter submits a [`TextFieldOverlay`](../overlays/text-field-overlay.md) (`onSubmit`); Ctrl/Cmd + Enter submits a [`TextAreaOverlay`](../overlays/text-area-overlay.md). Copy, cut and paste
can be disabled per widget with [`TextEditingOptions`](../text/text-editing-options.md).

## Clipboard

`me.piitex.engine.input.Clipboard` is a thread-safe bridge to the OS clipboard (GLFW only allows clipboard access
on the main thread, and your callbacks run on the render thread).

```java
Clipboard.copy("hello"); // queues the write; safe from any thread
String text = Clipboard.paste(); // blocks briefly while the main thread reads; safe from any thread
```

`paste()` returns an empty string (never `null`) if the clipboard does not currently hold plain text, or if the main thread does not answer within about 200 ms.

## Reference: the input types

| Type | Package | Role |
|---|---|---|
| `InputManager` | `input` | GLFW callback sink; queues `InputEvent`s (`window.getInputManager()`) |
| `InputProcessor` | `input` | Drains the queue each frame; hit-testing, hover, click, drag, scroll, keys |
| `InputEvent` | `input` | One queued event. `Type` is `MOUSE_MOVE`, `MOUSE_BUTTON`, `KEY`, `MOUSE_ENTER`, `CHAR` or `SCROLL` |
| `MouseTracker` | `input` | Last known mouse position |
| `CursorManager` | `input` | Main-thread bridge that applies the requested OS cursor |
| `Clipboard` | `input` | Main-thread bridge for copy/paste |
| `Draggable`, `Scrollable`, `Focusable` | `ui` | Opt-in interfaces described above |
| `CursorMode` | `ui` | Cursor shapes |
