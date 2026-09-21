# GridLayout

[Documentation](../README.md) › [Layouts](README.md)

`me.piitex.engine.ui.layout.GridLayout` arranges its children in a table: left to right across a fixed
number of **columns**, wrapping to a new row after each full one, in the order the children were added.

- Each **column** is as wide as its widest child.
- Each **row** is as tall as its tallest child.

Mixed-size children therefore line up in clean columns. The typical use is a form of label/field pairs with two
columns.

```java
GridLayout form = new GridLayout(2, 40, 40, 420, 160); // columns, x, y, width, height
form.setColumnSpacing(12);
form.setRowSpacing(10);
form.setAlignment(Layout.Alignment.CENTER_LEFT); // children centered vertically in their cell
form.getStyling().setBackgroundColor(Color.TRANSPARENT);
form.getStyling().setHoverColor(Color.TRANSPARENT);
form.getStyling().setBorderThickness(0f);

for (String label : new String[]{"Email", "Phone", "Company name"}) {
    form.addElement(new TextOverlay(label, 14f, Color.BLACK)); // column 0
    form.addElement(new TextFieldOverlay(200, 28)); // column 1
}

root.addElement(form);
```

## Constructors

| Constructor | Notes |
|---|---|
| `GridLayout(int columns, double width, double height)` | Positioned at `0, 0` |
| `GridLayout(int columns, double x, double y, double width, double height)` | |

`columns` below 1 is treated as 1.

## Settings

| Setting | Meaning | Default |
|---|---|---|
| `setColumns(int)` | Children per row (relays out immediately) | constructor value |
| `setSpacing(float)` | Sets the column gap **and** the row gap | `0` |
| `setColumnSpacing(float)` | Gap between columns | `0` |
| `setRowSpacing(float)` | Gap between rows | `0` |
| `setAlignment(Alignment)` | Placement of a child smaller than its cell | `TOP_LEFT` |
| `setPadding(float)` | Inset of the whole grid from the layout's edges | `0` |

`setSpacing` and `setColumnSpacing`/`setRowSpacing` overwrite each other. The last call wins.

## Behavior notes

- Children fill **row-major**: index 0 is row 0/column 0, index 1 is row 0/column 1, and so on.
- The last row may be incomplete. Empty cells stay empty.
- Column widths and row heights are recomputed every frame from the current child sizes.
- The layout does not resize itself. A grid taller than its box is clipped.
- To leave a cell empty, add an invisible element such as a `TextOverlay("")`.
