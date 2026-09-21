# SplitContainer

[Documentation](../README.md) › [Layouts](../layouts/README.md)

`me.piitex.engine.ui.containers.SplitContainer` shows **two panes separated by a draggable divider**. The user drags
the divider to trade space between the panes. It plays the role of JavaFX's `SplitPane`. It is limited to two
panes, so nest one split inside another to get more.

```java
SplitContainer split = new SplitContainer(SplitContainer.Orientation.HORIZONTAL, 20, 100, 600, 320);
split.setDividerPosition(0.4);
split.setMinPaneSize(80);
split.onDividerMoved(p -> System.out.println("Split: " + Math.round(p * 100) + "%"));

split.setFirst(pane("Left"));
split.setSecond(pane("Right"));
root.addElement(split);
```

with a helper that makes a themed panel:

```java
static VerticalLayout pane(String label) {
    VerticalLayout pane = new VerticalLayout(100, 100);
    pane.setPadding(8);
    pane.useTheme();
    pane.addElement(new TextOverlay(label, 14f, Color.BLACK));
    return pane;
}
```

## Orientation

| Value | Panes | Divider |
|---|---|---|
| `Orientation.HORIZONTAL` | Side by side (first = left, second = right) | Vertical bar |
| `Orientation.VERTICAL` | Stacked (first = top, second = bottom) | Horizontal bar |

## Constructors

```java
new SplitContainer(SplitContainer.Orientation.HORIZONTAL, 600, 320);
new SplitContainer(SplitContainer.Orientation.HORIZONTAL, 20, 100, 600, 320);
```

A `null` orientation is treated as `HORIZONTAL`.

## Setting the panes

```java
split.setFirst(leftPane); // left/top, replaces any existing pane
split.setSecond(rightPane); // right/bottom
Element a = split.getFirst();
Element b = split.getSecond();

split.addElement(leftPane); // fills the first empty pane, then the second
```

- `addElement(Element)` fills first, then second. A **third** call throws `IllegalStateException`.
- `addElement(Element, int)` throws `UnsupportedOperationException`. Use `setFirst`/`setSecond`.
- **Each pane is resized to fill its side every frame.** Anything that lays itself out inside (a [`Layout`](../layouts/README.md), a
  [`ScrollContainer`](scroll-container.md)) gets more or less room as the divider moves.
- A pane that is a [`Container`](container.md) is set to clip its children so they cannot spill across the divider.

## Divider

```java
split.setDividerPosition(0.3); // 0..1 as a fraction of the space; does NOT fire onDividerMoved
double p = split.getDividerPosition();

split.setDividerThickness(8f); // width of the bar and of its grab area (min 1)
split.setMinPaneSize(60f); // neither pane can be dragged smaller than this (points)
split.onDividerMoved(pos -> System.out.println(pos)); // fires while the *user* drags
```

| Setting | Default |
|---|---|
| Divider position | `0.5` |
| Divider thickness | `6` |
| Minimum pane size | `40` |

The position is stored as a fraction, so the divider keeps its proportion when the split container itself is
resized. The divider gets a resize cursor on hover, and its colors follow the theme (`scrollTrack` at rest,
`scrollThumb` on hover).

## Nesting for more than two panes

```java
SplitContainer outer = new SplitContainer(SplitContainer.Orientation.HORIZONTAL, 20, 100, 700, 400);
SplitContainer right = new SplitContainer(SplitContainer.Orientation.VERTICAL, 100, 100);

outer.setDividerPosition(0.35);
outer.setFirst(pane("Files"));
right.setFirst(pane("Editor"));
right.setSecond(pane("Console"));
outer.setSecond(right); // the inner split is resized to fill the right pane
```
