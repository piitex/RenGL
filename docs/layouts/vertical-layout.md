# VerticalLayout

[Documentation](../README.md) › [Layouts](README.md)

`me.piitex.engine.ui.layout.VerticalLayout` arranges its children in a single column, top to bottom, in the
order they were added. Each child is spaced `getSpacing()` below the previous one, and the whole column is
inset from the layout's edges by `getPadding()`.

`getAlignment()` places the column within the layout: `TOP_LEFT` (default), `TOP_CENTER`, `CENTER`,
`BOTTOM_RIGHT`, and so on. Its vertical part positions the column, its horizontal part each child within it.

```java
VerticalLayout menu = new VerticalLayout(50, 50, 220, 260); // x, y, width, height
menu.setSpacing(10);
menu.setPadding(16);
menu.setAlignment(Layout.Alignment.TOP_CENTER);
menu.useTheme();

menu.addElement(new TextOverlay("Main menu", 22f, Color.BLACK));
menu.addElement(new ButtonOverlay("New game", 160, 34));
menu.addElement(new ButtonOverlay("Options", 160, 34));
menu.addElement(new ButtonOverlay("Quit", 160, 34));

root.addElement(menu);
```

## Constructors

| Constructor | Notes |
|---|---|
| `VerticalLayout(double width, double height)` | Positioned at `0, 0` |
| `VerticalLayout(double x, double y, double width, double height)` | |

## Alignment

```java
menu.setAlignment(Layout.Alignment.TOP_LEFT); // top, children flush left (default)
menu.setAlignment(Layout.Alignment.TOP_CENTER); // top, children centered
menu.setAlignment(Layout.Alignment.CENTER); // column centered in the layout both ways
menu.setAlignment(Layout.Alignment.BOTTOM_RIGHT); // column at the bottom, children flush right
```

Alignment is measured inside the padded area (`layoutSize - 2 * padding`). If the content is taller than the layout it starts at the top padding edge.

## Behavior notes

- The layout **does not grow** to fit its children. If the children total more height than the layout,
  they continue down past its box and are clipped. Size it generously, or wrap it in a
  [`ScrollContainer`](../containers/scroll-container.md).
- A child's own width and height are never changed. Give widgets the width you want them to have.
- The order is the child index order. `addElement(child, index)` lets you insert at a specific slot.
- Child positions are recalculated every frame, so a child's `setPosition` is overwritten.

## Example: a settings panel

```java
VerticalLayout settings = new VerticalLayout(40, 40, 320, 300);
settings.setSpacing(14);
settings.setPadding(20);
settings.useTheme();

settings.addElement(new TextOverlay("Settings", 24f, Color.BLACK));
settings.addElement(new SeparatorOverlay(280));

CheckBoxOverlay music = new CheckBoxOverlay(18, true);
settings.addElement(music);

PercentSliderOverlay volume = new PercentSliderOverlay(200, 60);
volume.onValueChange(v -> System.out.println("Volume " + Math.round(v)));
settings.addElement(volume);

ButtonOverlay apply = new ButtonOverlay("Apply", 120, 32);
apply.onAction(() -> System.out.println("music=" + music.isChecked()));
settings.addElement(apply);

root.addElement(settings);
```
