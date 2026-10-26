# SpacerOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.SpacerOverlay` is empty space in a [`HorizontalLayout`](../layouts/horizontal-layout.md) or
[`VerticalLayout`](../layouts/vertical-layout.md): it draws nothing and takes no input.

```java
row.addElement(title);
row.addElement(new SpacerOverlay());     // flexible: takes whatever room the others leave
row.addElement(closeButton);             // so this sits at the right edge

column.addElement(header);
column.addElement(new SpacerOverlay(24));   // a fixed 24 point gap
column.addElement(body);
```

| Constructor | Behaviour |
|---|---|
| `SpacerOverlay()` | Stretches: its layout weight is 1, so it shares the room the other children leave equally with any other spacer or weighted child (see `Element#setLayoutWeight`) |
| `SpacerOverlay(double size)` | A fixed gap of `size` points along the layout's axis |

[`ToolBar#addSpacer()`](../containers/tool-bar.md) and [`StatusBar`](../containers/status-bar.md) use it to push what follows to the far end.
