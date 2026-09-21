# SeparatorOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.SeparatorOverlay` is a thin dividing line, for example to split a form into
sections. It draws a single line **centered within its bounds** rather than filling them, so the line keeps a
fixed thickness even if the overlay itself is taller (for a horizontal separator).

```java
form.addElement(new SeparatorOverlay(350, 2.5f)); // length, thickness
```

Its background and border are transparent and zero-thickness by default.

## Constructors

| Constructor | Result |
|---|---|
| `SeparatorOverlay(double length)` | Horizontal, `length` points long, 1 pt thick |
| `SeparatorOverlay(double length, float thickness)` | Horizontal, custom thickness |
| `SeparatorOverlay(double length, Orientation orientation)` | `length` along its own axis, 1 pt thick |

`SeparatorOverlay.Orientation` is `HORIZONTAL` or `VERTICAL`.

```java
SeparatorOverlay horizontal = new SeparatorOverlay(300); // a rule
SeparatorOverlay vertical = new SeparatorOverlay(120, SeparatorOverlay.Orientation.VERTICAL); // a bar
```

For a `HORIZONTAL` separator, `length` becomes its width. For a `VERTICAL` one, it becomes its height.

## Look

```java
sep.setThickness(3f); // width of the drawn line, independent of the overlay's size
sep.setLineColor(Color.BLUE); // pins the color
```

By default the line uses the theme's container-border color, the same neutral tone a themed container's border
uses.

## Example: sections in a settings panel

```java
VerticalLayout settings = new VerticalLayout(40, 40, 340, 260);
settings.setSpacing(10);
settings.setPadding(16);
settings.useTheme();

settings.addElement(new TextOverlay("Audio", 18f, Color.BLACK));
settings.addElement(new PercentSliderOverlay(220, 80));
settings.addElement(new SeparatorOverlay(300, 1f));
settings.addElement(new TextOverlay("Display", 18f, Color.BLACK));
settings.addElement(new CheckBoxOverlay(18, true));
root.addElement(settings);
```

## Method reference

| Method | Description |
|---|---|
| `getOrientation()` | `HORIZONTAL` or `VERTICAL` |
| `getThickness()` / `setThickness(float)` | Line width |
| `getLineColor()` / `setLineColor(Color)` | Line color; setting pins it |
