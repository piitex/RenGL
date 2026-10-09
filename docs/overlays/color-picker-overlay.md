# ColorPickerOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.ColorPickerOverlay` is a color swatch that opens a picker panel. The panel has a
saturation/brightness square, a hue bar, an optional opacity bar, a hex field and a row of preset swatches.

```java
ColorPickerOverlay accent = new ColorPickerOverlay(190, 34, Color.CORNFLOWER_BLUE);
accent.onChange(c -> panel.getStyling().setBackgroundColor(c));
layout.addElement(accent);
```

The constructor is `ColorPickerOverlay(double width, double height)` (starts blue) or `(width, height, Color initial)`. The
closed box shows the current color, drawn over a checkerboard while it is translucent, and by default its hex code.

The panel is shown through `Window.showPopup`, so it paints over sibling elements, is **not clipped** by a parent
[`ScrollContainer`](../containers/scroll-container.md), and closes on an outside click or Escape. It follows the picker if the
picker moves while open, and closes if the picker is removed, disabled or scrolled out of view.

## The value

```java
Color c = picker.getColor(); // converted from hue/saturation/brightness/opacity
String hex = picker.getHex(); // "#RRGGBB", or "#RRGGBBAA" when translucent and opacity is enabled
picker.setColor(Color.ORANGE); // silent: onChange does NOT fire
picker.onChange(color -> ...); // user changes only; fires continuously while dragging
```

The color is held as hue, saturation and brightness internally. Dragging to black or grey and back therefore keeps your
hue instead of snapping to red. `setColor` with a grey or black color keeps the current hue for the same reason.

## Using the panel

| Control | What it does |
|---|---|
| Square | Drag for saturation (left to right) and brightness (bottom to top) |
| Hue bar | Drag along the rainbow |
| Opacity bar | Drag from transparent to opaque, over a checkerboard |
| Preview | The color when the panel opened (left half) against the current one (right half) |
| Hex field | Type `#RGB`, `#RRGGBB` or `#RRGGBBAA`. The color updates as soon as the text is a valid code. The `#` is optional |
| Presets | Click a swatch to pick it |

The panel has no keyboard control over the square and bars. Use the hex field, or set the color from code.

## Options

```java
picker.setAlphaEnabled(false); // hides the opacity bar and rejects 8-digit hex; the color is always opaque. Default true
picker.setShowHex(false); // the swatch fills the closed box instead of sitting next to the text. Default true
picker.setPresets(Color.RED, Color.GREEN, Color.BLUE); // eight per row, more wrap; pass none to hide the row
picker.setFontSize(14f);
picker.setTextColor(Color.WHITE); // pins the hex text color; a gradient works too
```

The default presets are eight common colors. `setAlphaEnabled` and `setPresets` close an open panel so it can be rebuilt.

## Opening and closing from code

```java
picker.open(); // no-op with no window
picker.close();
picker.toggleOpen();
boolean open = picker.isOpen();
```

## Keyboard

The overlay is [`Focusable`](../input/input-and-focus.md#keyboard-focus) and a Tab stop. Enter or Space opens and closes the panel.

## Look

The box uses the text-field theme tokens, and the panel reuses the overlay's own styling, so pinning a color on it restyles
both.

```java
picker.getStyling().setBackgroundColor(new Color(0.12f, 0.12f, 0.14f, 1f));
picker.getStyling().setCornerRadius(8f);
```

The background and border may be a [`Color`](../styling/colors-and-paints.md#color) or a gradient, on the box and the popup alike. The thin
outlines inside the popup use the border color when it is a plain `Color`, and gray otherwise.

> **Do not call `onMouseClick` on a picker.** The constructor registers an internal handler that opens the panel, and
> replacing it breaks the widget. Use `onChange`.

## Method reference

| Group | Methods |
|---|---|
| Value | `getColor`, `setColor`, `getHex`, `onChange` |
| Options | `is/setAlphaEnabled`, `setShowHex`, `setPresets`, `get/setFontSize`, `get/setTextColor` |
| Panel | `isOpen`, `open`, `close`, `toggleOpen` |
