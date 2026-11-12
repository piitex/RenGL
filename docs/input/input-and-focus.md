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
- Buttons, checkboxes, radio buttons and toggle switches instead fire when the left button is **released** over
  them, and only if the press began on them. Give your own element that behavior with `setClickOnRelease(true)`.
  See [Element](../core/element.md#onmouseclick).
- The **topmost** element under the cursor gets it if it has a listener. If it has none, the press goes up to the
  nearest container above it that has one, so a press on the label or icon inside a clickable row counts as a press
  on the row. Only one listener runs per press: a child with its own listener stops the press there, unless it
  calls `setPropagateClicks(true)`, which passes the press on to the next ancestor with a listener.
- One listener per element. Setting another replaces it.
- Widgets with their own click behavior (button, checkbox, radio, toggle switch, dropdown, color and date pickers,
  menu bar, rich text area, table and tree views) handle clicks themselves. That is kept apart from `onMouseClick`, so setting yours does not replace it and it runs after. See [Element](../core/element.md#onmouseclick).

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
works. A `null` mode takes the cursor of the nearest container above that has one, and `DEFAULT` if none does, so
children of a `POINTER` row show the pointer too. Text fields and areas set `TEXT`, buttons and dropdowns set
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
`onRender` hook). With [several windows](../core/engine.md#several-windows) open, it answers for the window whose
elements are being updated or drawn.

## Dragging

An element that implements `Draggable` gets continuous drag events:

```java
public interface Draggable {
    void onDragStart(double x, double y); // left press landed on this element or a child that passed it up
    void onDragUpdate(double x, double y); // mouse moved while the button is held
    void onDragEnd(double x, double y); // left button released
}
```

`onDragUpdate` keeps firing even after the cursor leaves the element, and the dragged element keeps control of the
cursor shape for the whole drag. It fires **independently** of
`onMouseClick`, so implementing `Draggable` does not consume your click listener. [`Container`](../containers/container.md),
[`ScrollContainer`](../containers/scroll-container.md), [`TextFieldOverlay`](../overlays/text-field-overlay.md), [`TextAreaOverlay`](../overlays/text-area-overlay.md), [`SliderOverlay`](../overlays/slider-overlay.md) and the split divider implement it.

Only the **left** button drags. A right-click never starts a drag or a text selection, and releasing another
button mid-drag does not end it.

A press goes to the nearest `Draggable` at or above the element under the cursor. An element that is not
`Draggable` at all (a label, an image) **passes it up** to its container. `Draggable` also has
`default boolean canDrag(double x, double y)`, which is only consulted for a [`Container`](../containers/container.md):
a container returning `false` (a plain layout that fills its parent) passes the press up too. A `Container` returns
`true` only after `setDraggable(true)`.

Passing up stops at an element with a click listener. A button, checkbox or clickable row keeps its press, so
pressing a button on a draggable panel clicks the button without moving the panel. An element that should also
work as a handle, such as a title bar with a click listener, calls `setDragsContainer(true)`: a press released
in place still clicks it (on release), and moving the mouse a few points drags the panel instead.

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
    default boolean canScroll(double deltaX, double deltaY) { return true; } // would onScroll move anything?
}
```

The engine routes a scroll to the **innermost** `Scrollable` under the cursor, so a scrollable list nested inside
another scrolls whichever the cursor is over. It fires whatever child is directly under the cursor. It is not
limited to the scrollable's own empty background. A `Scrollable` whose `canScroll` returns false for the event is
skipped, so the wheel passes on to the next one outward. A `TextAreaOverlay` already at its top or bottom, for example,
lets a form's `ScrollContainer` scroll on instead of trapping the wheel. Override `canScroll` in your own
`Scrollable` if it can run out of room. [`ScrollContainer`](../containers/scroll-container.md), [`TextAreaOverlay`](../overlays/text-area-overlay.md), [`SpinnerOverlay`](../overlays/spinner-overlay.md)
and the dropdown's open list implement it. While a [modal](../containers/modal-container.md) is up, scrolling is limited
to it.

## PointerInput

For an element that shows someone else's interface (a web page, a remote desktop, an emulator) clicks and drags are not
enough. An element that implements `PointerInput` gets the raw pointer:

```java
public interface PointerInput {
    void onPointerMove(double x, double y, int buttons, int mods);                  // also with no button held
    void onPointerButton(double x, double y, int button, boolean pressed, int mods); // every button, press and release
    void onPointerScroll(double x, double y, double deltaX, double deltaY, int mods);
    default void onPointerLeave() {}
}
```

Positions are in the element's own coordinates. A press on it is its own: it opens no context menu, does not start a
drag of the draggable it sits in, and is not passed to the game. Once a button goes down the element keeps the pointer
until every button is released, so a drag that leaves its bounds (or the window) still reaches it, with `buttons` saying
which are held. The wheel goes to it before any `Scrollable` under the same point. A disabled element, and any element
while a popup is open, hears nothing. [`WebViewOverlay`](../webview/README.md) is built on it.

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

- **Clicking** a `Focusable` gives it focus, unless it is one that does not [take the keyboard](#controls-that-leave-the-keyboard-to-the-game)
  (a button, check box, radio button, link or slider). Clicking anything else, including empty space, clears focus. The
  exception is a click listener (or a menu item it runs) that gives focus to something itself, such as a Rename item
  that opens a field to type in: that focus stays.
- While focused, it receives every char and key event except Tab, which moves focus (unless `wantsTab()` is
  overridden to return true, as a code editor does), and an Escape that closes an
  open popup or modal. `onKeyPressed` gets presses, repeats and releases; `action` says which, and most controls
  ignore `GLFW_RELEASE`.
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
element.setFocusTraversable(true); // text fields, areas, dropdowns, buttons, check boxes, radio buttons, links and sliders already do this
element.setTabIndex(0); // explicit order; -1 (default) = natural order
```

- **Natural order** is the order elements were added, walked depth-first through the window's containers in
  draw order.
- Elements with a **non-negative `tabIndex`** come first, in ascending order (ties keep natural order),
  followed by all natural-order elements. This is the same convention as HTML's `tabindex`.
- With a [modal](../containers/modal-container.md) open, Tab is trapped inside the modal.
- A mouse click grants focus regardless of `focusTraversable`, which only gates Tab.

### Controls that leave the keyboard to the game

A text field takes every key while it has focus, which is right for typing and wrong for a button in a game's HUD:
clicking it would stop WASD working. `ButtonOverlay`, `CheckBoxOverlay`, `RadioButtonOverlay`, `LinkOverlay` and
`SliderOverlay` are `Focusable` and Tab stops, but they:

- **do not take focus from a click**, so a mouse user never notices them;
- **use only a few keys while Tab has focused them**: Space and Enter (a gamepad's A button is Enter) for the first
  four, the arrows, Home and End for a slider (Shift moves ten steps). Every other key goes to the game as if
  nothing had focus;
- **draw a focus ring** around themselves, only when Tab or a key brought focus there, as a browser does.

Your own control does the same by implementing `Activatable` (one `activate()` method, for a thing that does one
action), or by implementing `Focusable` and overriding `grabsKeyboard()` to return false and `handlesKey(key, mods)` to
name the keys it uses. Override `usesFocusRing()` on the element to get the ring. For screen readers and spoken
output, see [Accessibility](accessibility.md).

### Reserved keys

- **Tab / Shift+Tab** are consumed for focus traversal before any focused widget sees them, unless the focused widget
  says `wantsTab()` (a `TextAreaOverlay` with an indent size does; no popup or modal may be open). While nothing has focus
  and no popup or modal is open, the game gets Tab first, and it moves focus only if no scene's `onKey` consumes it and
  no [input context](game-input.md) binds it.
- **Escape** first closes an open popup, then an open modal (if it allows dismissing on Escape). Only if neither
  is open does it reach the focused widget.
- **A popup that implements `PopupKeyListener`** (the [menus](menus.md) do, for arrow-key navigation) gets every key
  press and repeat first, Tab and Escape included, and nothing else sees them while it is open. Implement
  `onPopupKey(int key, int mods)` on your own popup container to do the same.

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
| `Input`, `InputContext`, `Binding` | `input` | Polled game input, named actions and rebinding. See [Game input](game-input.md) |
| `MouseTracker` | `input` | Last known mouse position |
| `CursorManager` | `input` | Main-thread bridge that applies the requested OS cursor |
| `Clipboard` | `input` | Main-thread bridge for copy/paste |
| `Draggable`, `Scrollable`, `Focusable` | `ui` | Opt-in interfaces described above |
| `PopupKeyListener` | `ui` | Lets an open popup take the keyboard (see [Reserved keys](#reserved-keys)) |
| `CursorMode` | `ui` | Cursor shapes |
