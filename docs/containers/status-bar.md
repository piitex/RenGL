# StatusBar

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.containers.StatusBar` is the strip along the bottom of a window that says what is going on: a message on the left
("Ready", "3 files selected") and small readouts at the sides (a cursor position, a zoom level, a progress bar).

```java
StatusBar status = new StatusBar(800);
status.setPosition(0, 572);
status.setMessage("Ready");
TextOverlay position = status.addRightText("Ln 1, Col 1");
status.addRight(new ProgressBarOverlay(120, 10));
root.addElement(status);

// later
position.setText("Ln 12, Col 4");
status.setMessage("Saved");
```

| Method | What it does |
|---|---|
| `setMessage(String)`, `getMessage()` | The message at the far left; null or empty clears it |
| `addLeftText(String)`, `addLeft(Element)` | A readout after the message, divided from the one before |
| `addRightText(String)`, `addRight(Element)` | A readout at the right, after the ones already there |

The text methods return the [`TextOverlay`](../overlays/text-overlay.md), so it can be updated whenever the value changes. Neighbours on the
same side are separated by a thin divider. Any widget can go in: a progress bar, a small button, an icon.

It is a [`HorizontalLayout`](../layouts/horizontal-layout.md) (28 high, 6 padding, 8 spacing) with a flexible
[`SpacerOverlay`](../overlays/spacer-overlay.md) in the middle, and follows the theme like a container that `useTheme()`s. To anchor it to the bottom
of a resizable window, use `setAnchor(Anchor.BOTTOM_LEFT)` with a relative width (see [`Anchor`](../core/element.md)).

See also [`ToolBar`](tool-bar.md) for the strip along the top.
