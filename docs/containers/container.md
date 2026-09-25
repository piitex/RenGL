# Container

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.containers.Container` holds child elements. It is also an [`Element`](../core/element.md), so it has
its own background, border, hover color, tooltip, click listener and transitions. It draws its own
background first and then its children.

Every layout, scroll container, split container and modal is a `Container` subclass.

```java
Container panel = new Container(20, 20, 300, 200); // x, y, width, height
panel.getStyling().setBackgroundColor(Color.WHITE);
panel.getStyling().setCornerRadius(8f);
window.addContainer(panel); // or parent.addElement(panel)
```

Constructors: `Container(width, height)` (positioned at `0, 0`) and `Container(x, y, width, height)`.

## Adding children

```java
panel.addElement(label); // appended: index = last index + 1
panel.addElement(background, 0); // explicit index: lower draws first
panel.addElements(title, field, button); // several at once, in order; also takes a List
```

- **A child's position is relative to the container's top-left corner, always.** `(0, 0)` is the container's own
  origin, wherever it sits. If the panel is at `(20, 20)` and you want a label 10 points inside it, put the label at
  `(10, 10)`. `getX()`/`getY()` keep returning that relative position. The window position is
  `getAbsoluteX()`/`getAbsoluteY()`. It makes no difference whether the container is positioned yet or moves later,
  or in which order you build the tree. A plain `Container` does not lay out its children; for that use a
  [Layout](../layouts/README.md). A top-level container's x/y are window coordinates.
- Adding a child whose index is already taken **replaces** the existing child.
- `getElements()` returns the live `ConcurrentSkipListMap<Integer, Element>`. `getParent()` returns the
  container this one was added to, or `null` for a top-level container.
- `removeElement(Element)` and `removeElement(int index)` detach a child. Remaining children keep their indices, so
  gaps are left in the key space. Use `child.setEnabled(false)` to hide a child without removing it.

```java
Container card = new Container(40, 40, 260, 120);
card.getStyling().setBackgroundColor(new Color(0.95f, 0.95f, 0.95f, 1f));
card.getStyling().setCornerRadius(10f);

TextOverlay heading = new TextOverlay("Settings", 20f, Color.BLACK);
heading.setPosition(16, 12); // relative to the card
card.addElement(heading);

TextOverlay hint = new TextOverlay("Changes apply immediately", 13f, Color.GRAY);
hint.setTextColor(Color.GRAY); // the constructor color is only a baseline the theme replaces
hint.setPosition(16, 44);
card.addElement(hint);
```

## Draw order

Children draw in ascending index order, so later children appear on top and receive clicks first.

```java
panel.addElement(bottom, 0);
panel.addElement(top, 1); // draws over `bottom` and wins hit-testing where they overlap
```

`bringToFront()` moves a container to the last slot among its siblings. Dragging a draggable container calls it
automatically, so the panel you pick up comes to the front.

## Clipping

By default children are clipped to the container's box, so a child dragged or scrolled outside it is cut
off at the edge.

```java
panel.setClipping(false); // let a badge hang outside its parent
boolean clipped = panel.isClipping();
```

[`ScrollContainer`](scroll-container.md) always clips, regardless of this flag.

## Moving a container

```java
panel.moveTo(200, 100);
```

Everything inside a container moves with it, because children only store their offset from it. `setX`/`setY`
do the same. The difference is that `moveTo` also respects [drag bounds](#drag-bounds) and
[collision](#collision) if they are configured. Its `x`/`y` are in the same space as `getX()`, relative to the
container this one is in.

## Dragging

Containers can be dragged with the mouse. It is **off by default**, so an ordinary grouping container does
not start moving when a user drags across its background.

```java
panel.setDraggable(true);
```

- A left-button press anywhere on the container drags it: on its own area, on a child container (such as a layout
  filling it), or on a plain child such as a label or image. Each passes the press up to the nearest draggable
  ancestor. Only a child that is itself `Draggable` and wants the drag (a text field, a slider, a scroll
  container's thumb, another draggable container) keeps it.
- A child with a click listener, such as a button, still gets its click, but the press *also* starts dragging the
  panel. If that matters, keep buttons out of the draggable area, or use a
  [`ReorderableGrid`](reorderable-grid.md), which leaves presses on its items' controls alone.
- The container comes to the front when the drag starts.
- By default a draggable container cannot be dragged past the window's edges. Each edge is enforced
  independently, so it slides along an edge instead of sticking.

### Drag bounds

```java
panel.setDragBounds(0, 0, 800, 600); // stay inside this rectangle (same space as getX())
panel.setDragBoundsToParent(); // stay inside getParent()'s current box (read live)
panel.setDragBoundsToWindow(); // stay inside the window (the default for draggables)
panel.clearDragBounds(); // allow anywhere, including off-screen
```

Bounds apply to every `moveTo`, not only mouse drags.

### Collision

```java
panel.addCollideable(sidebar); // panel cannot be moved into sidebar
sidebar.addCollideable(panel); // ...and the reverse, if you want it mutual

panel.removeCollideable(sidebar);
panel.clearCollideables();
List<Container> obstacles = panel.getCollideables(); // live list
```

- Collision is **one-directional**. Adding `sidebar` to `panel` does not stop `sidebar` from being dragged
  into `panel`.
- The container stops flush against the obstacle and can still slide along its edge.
- With nothing registered, a container moves straight through anything.

## Theming

Containers are [`Themeable`](../styling/theming.md) but **do not register automatically**, because most containers only group
children and should stay transparent. A fresh container has a transparent background, and its hover color follows
the background, so it shows no hover feedback until you set a hover color.

```java
panel.useTheme(); // opt in: background, border, radius follow the current Theme
```

Call `useTheme()` **before** customizing the container's styling. Any property you set afterwards is
pinned. See [Theming](../styling/theming.md).

## Update and render

You normally do not call these. The engine does.

- `update(delta)` updates the container as an element, then every child.
- `render(renderer)` draws the background (`renderBackground`), pushes a clip if `isClipping()`, draws
  children (`renderChildren`) and pops the clip. Subclasses can override those protected pieces.

## Method reference

| Group | Methods |
|---|---|
| Children | `addElement(Element)`, `addElement(Element, int)`, `getElements()`, `getParent()` |
| Moving | `moveTo(x, y)`, `bringToFront()` |
| Dragging | `setDraggable`, `isDraggable`, `onDragStart/Update/End` |
| Bounds | `setDragBounds`, `setDragBoundsToParent`, `setDragBoundsToWindow`, `clearDragBounds` |
| Collision | `addCollideable`, `removeCollideable`, `clearCollideables`, `getCollideables` |
| Clipping | `setClipping`, `isClipping` |
| Theming | `useTheme`, `applyTheme` |
| Internal | `setWindow` (called by `Window.addContainer`) |
