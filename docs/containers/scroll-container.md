# ScrollContainer

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.scroll.ScrollContainer` is a [`Container`](container.md) that **clips its children to its
box and lets them scroll**: with the mouse wheel or trackpad, by dragging the scrollbar thumb, or from code.

```java
ScrollContainer list = new ScrollContainer(900, 100, 250, 300); // x, y, width, height
list.useTheme();
for (int i = 0; i < 12; i++) {
    TextOverlay row = new TextOverlay("Item " + (i + 1), 18f, Color.BLACK);
    row.setPosition(20, 10 + i * 36); // relative to the list
    list.addElement(row);
}
root.addElement(list);
```

Constructors: `ScrollContainer(width, height)` and `ScrollContainer(x, y, width, height)`.

## How scrolling works

Scrolling moves the space the children live in, not the children. A child's `x`/`y` stay exactly what you
gave them (relative to the container, unscrolled). The scroll offset (`getScrollOffsetX()`/`getScrollOffsetY()`) is
applied when the children are drawn and hit-tested, and `getAbsoluteY()` of a child includes it.

The scrollable range is **derived from the children's current bounds** each time (how far the last child's
bottom or right edge sits beyond the viewport). Nothing needs to be kept in sync by hand when you add or
resize children.

Two consequences:

1. **Only direct children count.** The range comes from each direct child's own bounds. A
   [`VerticalLayout`](../layouts/vertical-layout.md) placed in a scroll container counts as one child whose height is whatever you gave the
   layout, so give it the *full content height*:

   ```java
   ScrollContainer scroll = new ScrollContainer(20, 20, 300, 200);

   VerticalLayout rows = new VerticalLayout(20, 20, 280, 12 * 30); // tall enough for 12 rows
   rows.setSpacing(0);
   for (int i = 0; i < 12; i++) {
       rows.addElement(new TextOverlay("Row " + i, 16f, Color.BLACK));
   }
   scroll.addElement(rows);
   ```

2. Content that starts away from the container's top-left (padding, a header) is handled correctly. The range
   is measured from the actual last edge.

## Scrolling options

```java
list.setVerticalScrollEnabled(true); // wheel scrolls vertically (default true)
list.setHorizontalScrollEnabled(true); // wheel/trackpad scrolls horizontally (default false)
list.setScrollSpeed(30f); // points per unit of wheel offset
list.setScrollbarVisible(false); // hide the bar; wheel and code still work
```

## Scrolling from code

```java
list.scrollBy(0, 100); // relative, clamped
list.scrollTo(0, 0); // absolute, clamped to [0, max]

double y = list.getScrollOffsetY();
double max = list.getMaxScrollY();
double x = list.getScrollOffsetX();
double maxX = list.getMaxScrollX();
```

`scrollBy`/`scrollTo` do nothing on an axis that is not enabled.

## Scrollbar style

```java
ScrollBarStyle style = list.getScrollBarStyle();
style.setThickness(8f);
style.setMargin(2f);
style.setCornerRadius(4f);
style.setMinThumbLength(24f);
style.setTrackColor(new Color(0.9f, 0.9f, 0.9f, 1f)); // null = no track
style.setThumbColor(Color.LIGHT_GRAY); // null = no visible bar
style.setThumbActiveColor(Color.GRAY); // while being dragged
```

One `ScrollBarStyle` covers both bars. Assign a different instance with `setScrollBarStyle(...)` to restyle.
On a container with a corner radius, each bar is shortened at both ends just enough to stay inside the rounded
corners, so a small margin never lets the bar poke past the curve.
Scrollbar colors follow the theme (`scrollThumb`, `scrollTrack`) unless you set them. Colors are plain
[`Color`](../styling/colors-and-paints.md#color), not [`Paint`](../styling/colors-and-paints.md), so a gradient scrollbar is not supported.

## Interaction with other features

- **Dragging the whole container:** `setDraggable(true)` still works. A press on a scrollbar thumb scrolls
  first, and a press elsewhere on empty area drags the container.
- **Nested scrollables:** the wheel goes to the *innermost* [`Scrollable`](../input/input-and-focus.md#scrolling) under the cursor.
- **Clipping** is always on for this container.

## Gotchas

- The scrollbar is drawn over the container's right/bottom edge and does **not** reserve a gutter. A child
  that overlaps that strip wins clicks over the thumb, so leave the strip clear in your content sizing.
- [`TextAreaOverlay`](../overlays/text-area-overlay.md) scrolls by itself and does not need a `ScrollContainer` around it.
- A [`SpinnerOverlay`](../overlays/spinner-overlay.md) only reacts to the wheel when its field is focused, so scrolling past one inside a
  scroll container does not change its value by accident.
