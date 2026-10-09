# FlowLayout

[Documentation](../README.md) › [Layouts](README.md)

`me.piitex.engine.ui.layout.FlowLayout` places its children **left to right and wraps** to a new row when the next
child would not fit in the width that is left. Use it for tags and chips, a wall of thumbnails, or a toolbar that must
wrap on a narrow window.

```java
FlowLayout tags = new FlowLayout(260, 0);
tags.setSpacing(8);       // between children in a row
tags.setLineSpacing(8);   // between rows
tags.setSizeToContent(false, true); // the height follows the rows

for (String name : List.of("Action", "Puzzle", "Co-op", "Racing", "Story"))
    tags.addElement(new ButtonOverlay(name, 70, 28));

root.addElement(tags);
```

## Constructors

| Constructor | Notes |
|---|---|
| `FlowLayout(double width, double height)` | Positioned at `0, 0` |
| `FlowLayout(double x, double y, double width, double height)` | |

## Settings

| Setting | Meaning | Default |
|---|---|---|
| `setSpacing(float)` | Gap between children in a row | `0` |
| `setLineSpacing(float)` | Gap between rows | `0` |
| `setPadding(float)` | Inset from the layout's edges | `0` |
| `setAlignment(Alignment)` | The horizontal part places each row within the width, the vertical part places the whole block of rows | `TOP_LEFT` |

## Behavior notes

- Children keep their own size, and sit at the top of their row.
- A child wider than the layout gets a row to itself and overflows.
- Sizing to content in **height** is the useful case: the layout grows as rows are added. Sizing it to content in
  **width** gives it no width to wrap at, so it becomes a single row.
- Child order is the order they are placed in. A layout file saves it as `flow`.
