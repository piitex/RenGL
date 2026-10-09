# Tooltips

[Documentation](../README.md) › [Input](README.md)

Any element can show a small floating bubble of text once the mouse has rested on it.

```java
ButtonOverlay submit = new ButtonOverlay("Submit", 120, 32);
submit.setTooltip("Sends the form\nto the server"); // \n starts a new line
submit.setTooltipDelay(0.25f); // seconds before it appears; default 0.5
```

Set `null` (the default) to remove a tooltip.

| Method | Description |
|---|---|
| `Element.setTooltip(String)` / `getTooltip()` | The text, or `null` for none. Use `\n` for multiple lines; a line wider than about 360 points is wrapped at spaces |
| `Element.setTooltipDelay(float)` / `getTooltipDelay()` | Rest time in seconds before it shows (minimum 0, default `0.5`) |
| `Element.setTooltipTheme(Theme)` / `getTooltipTheme()` | The theme this element's tooltip is drawn with, or `null` (the default) for the current theme |

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

Each is a [`Paint`](../styling/colors-and-paints.md), so a tooltip can be a gradient or a scrolling one.

To restyle tooltips, use a [custom theme](../styling/theming.md#making-your-own-theme):

```java
BaseTheme theme = new BaseTheme();
theme.setTooltipBackground(new Color(0.1f, 0.1f, 0.4f, 0.95f));
theme.setTooltipBorder(new LinearGradient(Color.CYAN, Color.BLUE, 0f));
theme.setTooltipText(Color.WHITE);
ThemeManager.setCurrent(theme);
```

To style one element's tooltip differently, give it its own theme. It only affects that element's tooltip, and the
theme does not need to be applied. A theme picker can use it so each option's tooltip previews that theme:

```java
darkOption.setTooltip("Dark theme");
darkOption.setTooltipTheme(new DarkTheme());
```

The tooltip font is the default font from [`Font`](../styling/fonts.md).

## Notes

- `TooltipManager` is an internal static class driven by the engine (`update` then `render` once per frame). You
  never call it.
- A disabled element, or one inside a disabled container, never shows a tooltip.
- Tooltip text is one string. There is no rich formatting.
