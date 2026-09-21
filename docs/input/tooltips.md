# Tooltips

[Documentation](../README.md) › [Input](README.md)

Any element can show a small floating bubble of text once the mouse has rested on it.

```java
ButtonOverlay submit = new ButtonOverlay("Submit", 120, 32);
submit.setTooltip("Sends the form\nto the server"); // \n starts a new line
submit.setTooltipDelay(0.25f); // seconds before it appears; default 0.5
```

That is the whole API. Set `null` (the default) to remove a tooltip.

| Method | Description |
|---|---|
| `Element.setTooltip(String)` / `getTooltip()` | The text, or `null` for none. Use `\n` for multiple lines |
| `Element.setTooltipDelay(float)` / `getTooltipDelay()` | Rest time in seconds before it shows (minimum 0, default `0.5`) |

## Which tooltip shows

Tooltips work on every element, containers included.

- The **deepest hovered element that has a tooltip** wins. If a container and its child both have tooltips, the
  child's shows while the cursor is over the child, and the container's shows over the container's own empty area.
- A container's tooltip also shows while hovering a child that has **no tooltip of its own**.
- Moving to a different tooltip-bearing element restarts the timer.
- Tooltips only show while the mouse is inside the window.

```java
Container toolbar = new HorizontalLayout(20, 20, 300, 40);
toolbar.setTooltip("Toolbar");

ButtonOverlay save = new ButtonOverlay("Save", 80, 30);
save.setTooltip("Save the document");
toolbar.addElement(save); // hovering Save shows "Save the document"; hovering elsewhere on the bar shows "Toolbar"
```

## Look and position

The bubble is drawn **above every container** (and above popups and modals) by the `TooltipManager`, and
positioned just below and to the right of the cursor. If it would run off the window edge it flips above or to
the left of the cursor instead. Its colors come from the current theme's tooltip tokens:

| Token | Used for |
|---|---|
| `Theme.getTooltipBackground()` | Fill |
| `Theme.getTooltipBorder()` | Border |
| `Theme.getTooltipText()` | Text |

To restyle tooltips, use a [custom theme](../styling/theming.md#making-your-own-theme):

```java
BaseTheme theme = new BaseTheme();
theme.setTooltipBackground(new Color(0.1f, 0.1f, 0.4f, 0.95f));
theme.setTooltipText(Color.WHITE);
ThemeManager.setCurrent(theme);
```

The tooltip font is the default font from [`Font`](../styling/fonts.md).

## Notes

- `TooltipManager` is an internal static class driven by the engine (`update` then `render` once per frame). You
  never call it.
- A disabled element, or one inside a disabled container, never shows a tooltip.
- Tooltip text is one string. There is no rich formatting.
