# Element

[Documentation](../README.md) › [Core](README.md)

`me.piitex.engine.ui.Element` is the base class of everything the engine can draw and hit-test. It is a
rectangle with styling, event hooks, a tooltip and transitions. [`Container`](../containers/container.md)
extends it (so containers can be styled, clicked and animated too), and so does every
[`Overlay`](overlay.md).

You rarely instantiate `Element` directly. You use it through the subclasses, but everything on this page
works on **every** widget and container.

## Geometry

```java
element.setPosition(50, 80); // top-left corner, relative to the container it is in
element.setWidth(200);
element.setHeight(40);
element.setBounds(50, 80, 200, 40); // x, y, width, height in one call

double right = element.getX() + element.getWidth();
boolean hit = element.contains(60, 90); // a WINDOW point, inclusive of all four edges
```

There is no single `setSize` method. Use `setWidth`/`setHeight` or `setBounds`.

| Method | Notes |
|---|---|
| `getX()/setX()`, `getY()/setY()` | Top-left corner in logical points, **relative to the container it is in** (window coordinates for a top-level container) |
| `getAbsoluteX()`, `getAbsoluteY()` | The position in the window: the sum of this element's and every container above it |
| `toLocalX(windowX)`, `toLocalY(windowY)` | Converts a window point (a mouse position) into the space `getX()` lives in |
| `getWidth()/setWidth()`, `getHeight()/setHeight()` | Size in logical points |
| `setPosition(x, y)` | Sets x and y together |
| `setBounds(x, y, w, h)` | Sets all four |
| `contains(px, py)` | Whether a **window** point is inside this element. Used by hit-testing |
| `getZ()/setZ()` | Reserved; not used by rendering or hit-testing |

Some widgets compute their own size. [`TextOverlay`](../overlays/text-overlay.md) sizes itself to fit its text, and a [`Layout`](../layouts/README.md) sets its
children's positions every frame, so a manual `setPosition` on a layout child is overwritten.

## Enabled

```java
element.setEnabled(false); // invisible, no updates, ignored by hit-testing and Tab order
```

A disabled element is fully inert. To take a child out of its container entirely, use
`Container.removeElement`.

## Styling

```java
element.getStyling().setBackgroundColor(new Color(0.1f, 0.1f, 0.1f, 1f));
element.getStyling().setBorderColor(Color.WHITE);
element.getStyling().setBorderThickness(2f);
element.getStyling().setCornerRadius(8f);
```

Every element owns a [`Styling`](../styling/styling.md) with a background, hover color, border color, border thickness and corner
radius. **A fresh `Element` is opaque white with a 1pt white border.** Text, image and separator overlays
change that default to transparent. Full details: [Styling](../styling/styling.md).

## Hover, cursor and focus flags

```java
boolean over = element.isHovered(); // true while the mouse is over it
element.setCursorMode(CursorMode.POINTER); // OS cursor shape while hovered
element.setFocusTraversable(true); // reachable with Tab (only matters if Focusable)
element.setTabIndex(2); // explicit Tab order; -1 = natural order
```

- `isHovered()` is driven by the engine. When an element is hovered, its background switches from
  `Styling.getBackgroundColor()` to `Styling.getHoverColor()`.
- Every container on the path to the hovered element is hovered too.
- [`CursorMode`](../input/input-and-focus.md#hover-and-cursor-shape) values: `DEFAULT`, `TEXT`, `POINTER`, `CROSSHAIR`, `RESIZE_HORIZONTAL`,
  `RESIZE_VERTICAL`, `RESIZE_NWSE`, `RESIZE_NESW`, `RESIZE_ALL`, `NOT_ALLOWED`. `null` means "no opinion", and
  the cursor falls back to `DEFAULT`.
- `setFocusTraversable` and `setTabIndex` are covered in
  [Input and focus](../input/input-and-focus.md#tab-order).

## Events

Three per-element hooks. **Each holds one listener. Calling it again replaces the old one.**

### `onMouseClick`

```java
element.onMouseClick(event -> {
    double x = event.getMouseX(); // window coordinates
    double y = event.getMouseY();
    int button = event.getButton(); // GLFW.GLFW_MOUSE_BUTTON_LEFT / RIGHT / MIDDLE
    int action = event.getAction(); // always GLFW.GLFW_PRESS for a click
});
```

- Fires on **press**, not on release, for **any** mouse button. Check `getButton()` if you only want the
  left button.
- Only the **topmost** element under the cursor receives it. There is no bubbling to parent containers.
  A container's own click listener fires only when the press lands on the container's empty area.
- Several widgets install their own click handler ([`ButtonOverlay`](../overlays/button-overlay.md), [`CheckBoxOverlay`](../overlays/checkbox-overlay.md), [`DropdownOverlay`](../overlays/dropdown-overlay.md),
  [`RadioButtonOverlay`](../overlays/radio-button-overlay.md), [`RichTextAreaOverlay`](../overlays/rich-text-area-overlay.md), [`ModalContainer`](../containers/modal-container.md)). Replacing it breaks that widget's
  behavior. Use the widget's own callback (`onAction`, `onToggle`, ...) instead.

```java
import org.lwjgl.glfw.GLFW;

label.onMouseClick(event -> {
    if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
        System.out.println("right click at " + event.getMouseX() + ", " + event.getMouseY());
    }
});
```

### `onUpdate`

Runs at the end of every `update`, once per frame, for each enabled element.

```java
element.onUpdate(event -> {
    float dt = event.getDelta(); // seconds since the last frame
    element.setX(element.getX() + 120 * dt);
});
```

### `onRender`

Runs at the end of `render`, after the element's own background and border, so you can draw on top of it.
The [`Renderer`](../reference/custom-elements.md#the-renderer) is only valid inside this callback.

```java
element.onRender(event -> {
    Renderer r = event.getRenderer();
    r.drawCircle((float) element.getX() + 10, (float) element.getY() + 10, 4f, Color.RED);
});
```

`Renderer` calls are documented in [Architecture](../architecture.md#rendering-backends). The common ones
are `drawQuad`, `drawBorder`, `drawLine`, `drawCircle`, `drawText`, `drawGradient` and `drawImage`.

The event classes are in `me.piitex.engine.ui.events`: `MouseClickEvent` (extends `MouseEvent`),
`ElementUpdateEvent` and `ElementRenderEvent` (both extend `ElementEvent`, which has `getElement()`).

## Tooltip

```java
element.setTooltip("Sends the form\nto the server"); // \n for multiple lines
element.setTooltipDelay(0.25f); // seconds the mouse must rest; default 0.5
```

Works on any element, containers included. See [Tooltips](../input/tooltips.md).

## Background effects

```java
element.setBackground(new DotGridBackground());
```

Attaches an animated or decorative effect drawn behind the element's own content without becoming a child
(so a layout does not try to position it). See [Backgrounds](../styling/backgrounds.md).

## Opacity, fades and transitions

```java
element.setOpacity(0.5f); // static translucency, 0..1

element.fadeIn(0.3f); // transparent -> opaque
element.fadeOut(0.3f, () -> element.setEnabled(false)); // with a completion callback
element.fadeIn(Color.BLACK, 1f); // dissolve from a solid color

element.playTransition(myTransition); // any Transition
boolean busy = element.isTransitioning();
```

- The whole element, including background, border, content and (for containers) every child, fades together
  as one unit.
- Starting a fade while another is running replaces the running one.
- `fadeOut` leaves the element fully transparent but still enabled. It still receives clicks. Disable it
  in the callback if it should stop interacting.

See [Animation](../animation/transitions.md).

## Identity

```java
FormID id = element.getId(); // unique per concrete class
System.out.println(id); // e.g. ELEMENT:TextOverlay#3
```

Every element gets a `FormID` at construction. See [Utilities](../reference/utilities.md#formid).

## Window access

```java
Window window = element.getWindow(); // null until the element is inside a container on a window
```

Needed by widgets that open floating UI. Also useful for calling `window.requestFocus(this)` and similar.

## Draw order fields

`getDrawIndex()`, `getSuggestedDrawIndex()` and `setSuggestedDrawIndex(int)` exist for the draw-order
system. In practice you control order with `Container.addElement(element, index)`. See
[Container](../containers/container.md#draw-order).

## Method reference

| Group | Methods |
|---|---|
| Geometry | `getX/Y/Width/Height`, `setX/Y/Width/Height`, `setPosition`, `setBounds`, `contains` |
| State | `isEnabled/setEnabled`, `isHovered`, `getOpacity/setOpacity` |
| Look | `getStyling/setStyling`, `getBackground/setBackground`, `getCursorMode/setCursorMode` |
| Focus | `isFocusTraversable/setFocusTraversable`, `getTabIndex/setTabIndex` |
| Tooltip | `getTooltip/setTooltip`, `getTooltipDelay/setTooltipDelay` |
| Events | `onMouseClick`, `onUpdate`, `onRender` (and their `getXxxConsumer` getters) |
| Transitions | `playTransition`, `isTransitioning`, `fadeIn` (4 overloads), `fadeOut` (4 overloads) |
| Frame hooks (override in subclasses) | `update(float)`, `render(Renderer)`, `renderWithTransitions(Renderer)` |
| Misc | `getId`, `getWindow` |
