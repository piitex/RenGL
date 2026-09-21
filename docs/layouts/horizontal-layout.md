# HorizontalLayout

[Documentation](../README.md) › [Layouts](README.md)

`me.piitex.engine.ui.layout.HorizontalLayout` arranges its children in a single row, left to right, in the
order they were added. Each child is spaced `getSpacing()` to the right of the previous one, and the row is
inset from the layout's edges by `getPadding()`.

`getAlignment()` places the row within the layout: `TOP_LEFT` (default), `CENTER_LEFT`, `CENTER`,
`BOTTOM_RIGHT`, and so on. Its horizontal part positions the row, its vertical part each child within it.

```java
HorizontalLayout toolbar = new HorizontalLayout(20, 20, 400, 40); // x, y, width, height
toolbar.setSpacing(8);
toolbar.setPadding(4);
toolbar.setAlignment(Layout.Alignment.CENTER_LEFT);
toolbar.useTheme();

toolbar.addElement(new ButtonOverlay("Open", 80, 30));
toolbar.addElement(new ButtonOverlay("Save", 80, 30));
toolbar.addElement(new ButtonOverlay("Close", 80, 30));

root.addElement(toolbar);
```

## Constructors

| Constructor | Notes |
|---|---|
| `HorizontalLayout(double width, double height)` | Positioned at `0, 0` |
| `HorizontalLayout(double x, double y, double width, double height)` | |

## Example: a checkbox with a label

`CENTER_LEFT` alignment keeps the label vertically centered against the (usually taller) box no matter
what the font metrics are. Make the row itself invisible so it does not paint a white box over its parent.

```java
HorizontalLayout row = new HorizontalLayout(200, 24);
row.setSpacing(8);
row.setAlignment(Layout.Alignment.CENTER_LEFT);
row.getStyling().setBackgroundColor(Color.TRANSPARENT);
row.getStyling().setHoverColor(Color.TRANSPARENT);
row.getStyling().setBorderThickness(0f);

CheckBoxOverlay box = new CheckBoxOverlay(18);
box.onToggle(checked -> System.out.println("subscribed: " + checked));
row.addElement(box);
row.addElement(new TextOverlay("Subscribe to updates", 14f, Color.BLACK));

root.addElement(row);
```

## Behavior notes

- The layout does not resize itself. Children that total more width than the layout is wide run past its
  right edge and are clipped.
- A child's size is never changed.
- Positions are recomputed every frame, so a child's `setPosition` is overwritten.
- `SpinnerOverlay` is a `HorizontalLayout` subclass. See [SpinnerOverlay](../overlays/spinner-overlay.md).
