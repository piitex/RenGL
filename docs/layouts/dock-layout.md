# DockLayout

[Documentation](../README.md) › [Layouts](README.md)

`me.piitex.engine.ui.layout.DockLayout` pins each child to an **edge of the space that is left**, in the order the
children were added, and gives the child docked `FILL` whatever remains. A header across the top, a status bar along
the bottom, a side panel and a content area is four children.

```java
DockLayout screen = new DockLayout(1280, 720);
screen.setSpacing(6);

screen.addElement(header, DockLayout.Dock.TOP);      // keeps its height, takes the full width
screen.addElement(statusBar, DockLayout.Dock.BOTTOM);
screen.addElement(sidebar, DockLayout.Dock.LEFT);    // keeps its width, takes the height that is left
screen.addElement(content, DockLayout.Dock.FILL);    // takes everything that remains
```

## Docking

| `Dock` | The child |
|---|---|
| `TOP`, `BOTTOM` | keeps its own height and is stretched across the width that is left |
| `LEFT`, `RIGHT` | keeps its own width and takes the height that is left |
| `FILL` | takes the width and height that are left |

Each child takes its edge **from what the earlier children left**, so the order matters: a sidebar added before a
header runs the full height, and the header only spans what is beside it. A child added after a `FILL` child has no
space left and gets none.

`setDock(child, dock)` changes a child's edge later, and `getDock(child)` reads it. A child that was never given one is
docked `TOP`, so a dock layout of plain children is a column of full-width rows.

## Settings

`setSpacing` is the gap between docked children and `setPadding` the inset from the layout's edges. `setAlignment` is not
used. The layout sets the sizes described above itself, so setting a docked child's width or height by hand along the
stretched axis has no lasting effect.

`setSizeToContent` works as for any layout: the size is the least that holds every child in order.

A layout file saves a dock layout as `dock`, and each child's edge as its `dock` property (left out when it is `TOP`).
