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
| `setAnchor(anchor[, pivot], offsetX, offsetY)`, `setAnchorMargin(margin)` | Keeps it in place in its container as that resizes. See [Anchoring](#anchoring) |
| `setRelativeSize(w, h)`, `setRelativeWidth(w)`, `setRelativeHeight(h)`, `setRelativeInset(points)`, `fill(margin)` | Keeps the size a fraction of its container's. See [Sizing by fraction](#sizing-by-fraction) |
| `setLayoutWeight(weight)` | Takes a share of the space left in a row or column. See [Sharing space](../layouts/README.md#sharing-the-space-left-over) |
| `contains(px, py)` | Whether a **window** point is inside this element. Used by hit-testing |

Some widgets compute their own size. [`TextOverlay`](../overlays/text-overlay.md) sizes itself to fit its text, and a [`Layout`](../layouts/README.md) sets its
children's positions every frame, so a manual `setPosition` on a layout child is overwritten.

### Anchoring

An anchor keeps an element at the same place in its container however the container is resized, such as a
[scene's](../game/scenes.md#the-scenes-container) when the window is. There's no need to reposition it in `onResize`.

```java
button.setAnchor(Anchor.CENTER);                   // centered
close.setAnchor(Anchor.TOP_RIGHT, -12, 12);        // 12 points in from the top-right corner
menu.setAnchor(Anchor.at(0.5, 0.3), Anchor.TOP_CENTER, 0, 0); // centered across, its top 30% of the way down
menu.setAnchorMargin(20);                          // but never closer than 20 points to an edge
```

- `Anchor` (in `me.piitex.engine.ui`) is a point in a box as fractions of its size: `TOP_LEFT`, `TOP_CENTER`,
  `TOP_RIGHT`, `CENTER_LEFT`, `CENTER`, `CENTER_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_CENTER`, `BOTTOM_RIGHT`, or any
  other with `Anchor.at(x, y)`.
- `setAnchor(anchor, offsetX, offsetY)` puts the element's own point at `anchor` on the container's point at
  `anchor`, then moves it by the offsets (positive is right and down). `setAnchor(anchor, pivot, offsetX, offsetY)`
  puts the element's point at `pivot` there instead.
- The position is updated at once when the anchor is set, the element is added, or either one is resized, and
  every frame as well. `getX()`/`getY()` return it.
- `setAnchorMargin(margin)` keeps the element at least that far inside every edge while it fits. When it doesn't
  fit, its top-left corner stays at the margin. Negative, the default, turns it off.
- Setting a position by hand (`setX`, `setY`, `setPosition`, `setBounds`, or dragging) drops the anchor, and so
  does `setAnchor(null)`.
- It has no effect without a container, such as on a container added straight to the window, nor inside a
  layout, which places its children itself.

To keep a group of elements together, put them in a plain `Container` at fixed positions and anchor the container.

### Sizing by fraction

A relative size keeps an element's width and height a fraction of its container's, the way an anchor keeps its
position. Together they cover a layout that follows the window with no `onResize` code.

```java
sidebar.setRelativeSize(0.25, 1);                   // a quarter of the width, the full height
sidebar.setAnchor(Anchor.TOP_LEFT);
content.setRelativeSize(0.75, 1);
content.setAnchor(Anchor.TOP_RIGHT);

overlay.fill(16);                                   // covers the container, 16 points in from every edge
panel.setRelativeWidth(0.5);                        // the width alone; the height stays as set
```

- A fraction is of the container's box, or of a [layout's](../layouts/README.md) area inside its padding. A negative
  fraction turns an axis back to its own size.
- `setRelativeInset(points)` takes that much off each side, so `setRelativeSize(1, 1)` with an inset of 8 leaves an
  8 point border. `fill(margin)` is that and a `CENTER` anchor in one call.
- The size is worked out when the fraction is set, when the element is added, when either is resized, and every
  frame. While a fraction is set it wins over `setWidth` and `setHeight`, and over the scaling a
  [`ResizableContainer`](../containers/resizable-container.md) does to its children.
- Like an anchor, it has no effect without a container. In a [`DockLayout`](../layouts/dock-layout.md) or
  [`GridLayout`](../layouts/grid-layout.md) the fraction is of the whole layout, not of the child's edge or cell.
- In a layout file the properties are `relativeWidth`, `relativeHeight` and `relativeInset`; 0 leaves the size alone.

## Enabled

```java
element.setEnabled(false); // invisible, no updates, ignored by hit-testing and Tab order
```

A `setEnabled(false)` element is fully inert. To take a child out of its container entirely, use
`Container.removeElement`.

## Disabled

`setEnabled(false)` hides an element. To show one that cannot be used right now, use `setDisabled(true)`. It works on
every element, from a check box to a whole panel:

```java
saveButton.setDisabled(true);
saveButton.setTooltip("Choose a folder first"); // still shows, so it can say why
saveButton.onDisabledClick(() -> status.setText("Choose a folder first."));

form.setDisabled(true); // everything inside the form is disabled too
```

A disabled element stays on screen, drawn faded (45% by default; `setDisabledOpacity(float)` changes it) and without
its hover color, with the "not allowed" cursor. It takes no clicks, drags or keyboard focus, and Tab skips it. A click
on it runs `onDisabledClick` if there is one, on release like a button's click; on a child of a disabled container
that has no handler of its own, the container's handler runs. Fading is a layer of its own, separate from `setOpacity`,
so a fade-in and a disabled element combine. `isDisabled()` is this element's own flag, `isEffectivelyDisabled()` also
counts a disabled container above it.

Disabling does not stop code: `onAction`, `setText` and the like still work if you call them. Wheel scrolling over a
disabled scroll container still scrolls it.

## Styling

```java
element.getStyling().setBackgroundColor(new Color(0.1f, 0.1f, 0.1f, 1f));
element.getStyling().setBorderColor(Color.WHITE);
element.getStyling().setBorderThickness(2f);
element.getStyling().setCornerRadius(8f);
```

Every element owns a [`Styling`](../styling/styling.md) with a background, hover color, border color, border thickness and corner
radius. **A fresh `Element` is fully transparent**: no fill, no hover color, and a 1pt border in a transparent
color, so nothing shows until you set a color (or opt into a theme). A [`Container`](../containers/container.md)'s
hover color follows its background, so hovering it never flashes a different color. Full details:
[Styling](../styling/styling.md).

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
  `RESIZE_VERTICAL`, `RESIZE_NWSE`, `RESIZE_NESW`, `RESIZE_ALL`, `NOT_ALLOWED`. `null` means "no opinion": the
  element uses the cursor of the nearest container above it that has one, and `DEFAULT` if none does. A label
  inside a `POINTER` row therefore shows the pointer too.
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
    int action = event.getAction(); // GLFW.GLFW_PRESS, or GLFW_RELEASE if it clicks on release
});
```

- Widgets that react to clicks themselves (buttons, check boxes, radio buttons, toggle switches, dropdowns, pickers, links, the menu bar, `TableView`, `TreeView`, `RichTextAreaOverlay`, modals) keep that apart from this listener. Yours runs after theirs and does not replace it.
- Fires on **press**, not on release, for **any** mouse button. Check `getButton()` if you only want the
  left button.
- `setClickOnRelease(true)` makes it fire the way a button does: when the **left** button is released, and only
  if the press began on this element and the cursor is still over it. Moving off before letting go cancels the
  click, and other buttons are taken without firing it. `ButtonOverlay`, `CheckBoxOverlay`, `RadioButtonOverlay`
  and `ToggleSwitchOverlay` turn it on. A press that started elsewhere never clicks it, including a click that
  opened a popup with a button under the cursor, or one on a popup scene still in its
  [input delay](../game/scenes.md#popup-scenes).
- The press goes to the **topmost** element under the cursor if it has a click listener. If it has none, the
  press goes up to the nearest container above it that does, so pressing the label or icon inside a clickable
  row counts as pressing the row. Only that one listener runs: once an element with a listener is found, the
  containers above it are not called. `setPropagateClicks(true)` on that element lets the press go on to the
  next ancestor with a listener as well, so a clickable card and the button inside it both hear the click.
- `setDragsContainer(true)` makes a clickable element a handle for the draggable container around it, such as a
  title bar. A press that is released without moving still clicks (on release); moving the mouse a few points
  while it is held drags the container instead and drops the click.
- Several widgets install their own click handler ([`ButtonOverlay`](../overlays/button-overlay.md), [`CheckBoxOverlay`](../overlays/checkbox-overlay.md), [`DropdownOverlay`](../overlays/dropdown-overlay.md),
  [`RadioButtonOverlay`](../overlays/radio-button-overlay.md), [`ToggleSwitchOverlay`](../overlays/toggle-switch-overlay.md), [`ColorPickerOverlay`](../overlays/color-picker-overlay.md),
  [`DateTimePickerOverlay`](../overlays/date-time-picker-overlay.md), [`RichTextAreaOverlay`](../overlays/rich-text-area-overlay.md), [`MenuBarOverlay`](../input/menus.md),
  [`ModalContainer`](../containers/modal-container.md)). Replacing it breaks that widget's
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
are `drawQuad`, `drawBorder`, `drawLine`, `drawCircle`, `drawPolygon`, `drawText`, `drawGradient`, `drawGradientBorder`,
`drawImage` and `drawImageRegion`.

The event classes are in `me.piitex.engine.ui.events`: `MouseClickEvent` (extends `MouseEvent`),
`ElementUpdateEvent` and `ElementRenderEvent` (both extend `ElementEvent`, which has `getElement()`).

## Tooltip

```java
element.setTooltip("Sends the form\nto the server"); // \n for multiple lines
element.setTooltipDelay(0.25f); // seconds the mouse must rest; default 0.5
element.setTooltipTheme(new DarkTheme()); // draw this tooltip with a theme other than the current one
```

Works on any element, containers included. See [Tooltips](../input/tooltips.md).

## Context menu

```java
element.setContextMenu(new PopupMenu(
        new MenuItem("Rename", () -> rename()),
        new MenuItem("Delete", () -> delete())));
```

Opens at the pointer on right-click. Works on any element, containers included. A right-click on a child with
no menu of its own opens the nearest ancestor's. See [Menus](../input/menus.md).

## Text alignment

```java
button.setTextAlignment(Layout.Alignment.CENTER_LEFT); // text at the left edge, vertically centered
button.setTextAlignment(null); // back to the widget's own placement
```

Where the text sits inside an overlay's own box. `null` (the default) keeps each widget's natural placement:
top-left for a [`TextOverlay`](../overlays/text-overlay.md), centered for a [`ButtonOverlay`](../overlays/button-overlay.md),
left for a text field. It only matters when the box is larger than the text, and it is unrelated to a layout's
alignment, which positions the element itself.

## Background effects

```java
element.setBackground(new DotGridBackground());
```

Attaches an animated or decorative effect drawn behind the element's own content without becoming a child
(so a layout does not try to position it). See [Backgrounds](../styling/backgrounds.md).

Like a color, an effect is a themed property: a [theme](../styling/theming.md) can give a panel one
(`Theme.getContainerEffect()`), and `setBackground` **pins** it, so a theme change no longer replaces it.
`resetBackground()` hands it back to the theme, and `isBackgroundSet()` says whether it is pinned.

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

See [Animation](../animation/transitions.md), including how a transition can [lock the game](../animation/transitions.md#locking-the-game) until it ends.

## Scale

```java
card.setScale(1.1f); // draw at 110%, around its center
card.setScaleOrigin(0, 0); // scale around a point instead, in the same space as getX()/getY()
card.clearScaleOrigin(); // back to the center
card.playTransition(new ScaleTransition(1f, 0.8f, 0.2f, Easing.EASE_OUT, null)); // animate it
```

- The element and all of its children are drawn scaled. Default `1`.
- Only the drawing changes. Hit-testing, layout and `getWidth()`/`getHeight()` stay the same, so scale suits an
  animation (a pop-in, a press effect) better than a permanently smaller element.
- Scale and opacity combine, so a [`ScaleTransition`](../animation/transitions.md#scaletransition) can run next to a fade.

## Shader effect

```java
ShaderEffect grayscale = ShaderEffect.compile("""
        uniform shader content;
        half4 main(float2 coord) {
            half4 c = content.eval(coord);
            return half4(half3(dot(c.rgb, half3(0.3, 0.59, 0.11))), c.a);
        }
        """);
panel.setEffect(grayscale); // the panel and its children, in gray
panel.setEffect(null);      // back to normal
```

- Everything the element draws, children included, goes through the shader: post-processing. The shader must declare
  `uniform shader content;`, which holds the element's drawing, or `setEffect` throws.
- `coord` is in the same space as `getX()`/`getY()`, and the result is cut to the element's bounds.
- Only the drawing changes. Hit-testing and layout stay the same.
- The effect is applied before opacity, so a fade fades the processed result. The element doesn't close the effect.

See [Shaders and post effects](../graphics/shaders.md).

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
| Geometry | `getX/Y/Width/Height`, `setX/Y/Width/Height`, `setPosition`, `setBounds`, `contains`, `setAnchor`, `getAnchor`, `getPivot`, `setAnchorMargin`, `getAnchorMargin`, `applyAnchor` |
| State | `isEnabled/setEnabled`, `isDisabled/setDisabled/isEffectivelyDisabled`, `onDisabledClick`, `getDisabledOpacity/setDisabledOpacity`, `isHovered`, `getOpacity/setOpacity`, `getScale/setScale`, `setScaleOrigin`, `clearScaleOrigin`, `getEffect/setEffect` |
| Look | `getStyling/setStyling`, `followTheme`, `getBackground/setBackground`, `getCursorMode/setCursorMode`, `getTextAlignment/setTextAlignment` |
| Focus | `isFocusTraversable/setFocusTraversable`, `getTabIndex/setTabIndex` |
| Tooltip | `getTooltip/setTooltip`, `getTooltipDelay/setTooltipDelay`, `getTooltipTheme/setTooltipTheme` |
| Context menu | `getContextMenu/setContextMenu` |
| Snapshots | `takeSnapshot()`, `takeSnapshot(scale)`, `takeSnapshotAsync` (see [Snapshots](snapshots.md)) |
| Events | `onMouseClick`, `onUpdate`, `onRender` (and their `getXxxConsumer` getters) |
| Transitions | `playTransition`, `isTransitioning`, `fadeIn` (4 overloads), `fadeOut` (4 overloads) |
| Frame hooks (override in subclasses) | `update(float)`, `render(Renderer)`, `renderWithTransitions(Renderer)` |
| Misc | `getId`, `getWindow`, `toLocalX/toLocalY`, `getAbsoluteX/getAbsoluteY` |
