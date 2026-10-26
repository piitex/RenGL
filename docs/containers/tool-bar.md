# ToolBar

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.containers.ToolBar` is a row of tool buttons under a menu bar: icon buttons for the common actions, thin dividers
between groups, and any other widget in the same row.

```java
ToolBar bar = new ToolBar(800);
bar.addButton(Feather.FOLDER, "Open (Ctrl+O)", this::open);
bar.addButton(Feather.SAVE, "Save (Ctrl+S)", this::save);
bar.addSeparator();
bar.addButton("Build", this::build);
bar.addSpacer();                          // everything after this sits at the right end
bar.addElement(searchField);
root.addElement(bar);
```

It is a [`HorizontalLayout`](../layouts/horizontal-layout.md) (40 high, 4 padding, 4 spacing, children centred vertically by default),
so everything a layout does applies, and it follows the theme like a container that `useTheme()`s.

| Method | Adds |
|---|---|
| `addButton(IconAsset icon, String tooltip, Runnable action)` | A square icon button; the tooltip says what it does (null for none) |
| `addButton(String text, Runnable action)` | A text button as wide as its label needs |
| `addIconTextButton(IconAsset icon, String text, Runnable action)` | An icon beside a label |
| `addSeparator()` | A thin vertical divider |
| `addSpacer()` | Flexible space: what follows is pushed to the far end |
| `addElement(element)` | Any other widget |

The button methods return the [`ButtonOverlay`](../overlays/button-overlay.md), so it can be disabled (`setDisabled(true)`), re-tooltipped or
restyled. The buttons are **flat**: no box until the pointer is over them. `setButtonSize(float)` (default 32) sets the size of the buttons
made after it. `Feather` has a set of icons; see [`IconOverlay`](../overlays/icon-overlay.md).

The spacer is a [`SpacerOverlay`](../overlays/spacer-overlay.md). See also [`StatusBar`](status-bar.md) for the strip along the bottom.
