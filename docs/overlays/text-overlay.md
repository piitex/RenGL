# TextOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.TextOverlay` draws **one line of static text**. `x`/`y` is the top-left corner,
like every other element. Its width and height are computed from the text, the font and the size.

```java
TextOverlay title = new TextOverlay("Hello, RenGL!", 48f, Color.BLACK);
title.setPosition(50, 50);
root.addElement(title);
```

## Constructors

| Constructor | Notes |
|---|---|
| `TextOverlay(String text)` | 16pt, black, default font |
| `TextOverlay(String text, float fontSize, Paint textColor)` | Default font |
| `TextOverlay(String text, File fontFile, float fontSize, Paint textColor)` | Custom font, cached by path |

The "default font" is `Font.getDefault()`. Set it once at startup with `Font.setDefaultFont(...)`. See
[Fonts](../styling/fonts.md).

> **The constructor's color is only a baseline.** `TextOverlay` is themed, and the current theme's text color
> replaces it immediately. To force a color, call `setTextColor(...)` after construction. That pins it
> against theme switches.

```java
TextOverlay error = new TextOverlay("Something went wrong", 16f, Color.RED);
error.setTextColor(Color.RED); // pinned: survives Dark/Light theme switches
```

## Changing text, font and size

```java
title.setText("New text"); // size is recomputed
title.setFontSize(24f);
title.setFontFile("src/main/resources/Roboto-Regular.ttf"); // File or String path
String s = title.getText();
float size = title.getFontSize();
```

`setText`, `setFontSize` and `setFontFile` all recompute the overlay's width/height so layouts and
alignment see the new size.

## Text that does not fit

A `TextOverlay` sizes itself to its text, so a long file name or status line makes a long overlay. To cap that:

```java
name.setMaxWidth(240);            // never wider than 240; shorter text still takes only what it needs
status.setWidth(300);             // a box of a set width...
status.setEllipsize(true);        // ...shortens text that is wider than it, instead of running past
name.setEllipsizeMiddle(true);    // "quarterl….xlsx": cut the middle, keep both ends (suits file names)
```

Text that is cut ends in an ellipsis (`…`). `getText()` still returns the whole string, so nothing is lost; only
what is drawn is shortened. `setMaxWidth` is for an overlay that sizes itself, `setEllipsize` is for one given a width with
`setWidth`; if both are set the narrower wins. Set a [tooltip](../core/element.md) to the full text if the person may
need to read it.

`TextFit.ellipsize(String, Font, float maxWidth[, boolean middle])` in `me.piitex.engine.ui.text` does the same for text you
draw yourself. It never splits a surrogate pair, so an emoji is cut whole or not at all.

## Colors and gradients

The text color is a [`Paint`](../styling/colors-and-paints.md): a solid [`Color`](../styling/colors-and-paints.md#color), or a gradient stretched across the text's own bounds.

```java
title.setTextColor(Color.BLUE);

title.setTextColor(new LinearGradient(Color.BLUE, Color.GREEN));

// A gradient that scrolls over time. Give each element its own instance.
title.setTextColor(new ScrollingGradient(Color.BLUE, Color.GREEN, 50f)); // speed in px/second

// Cycles through the colour wheel (RGB). One instance can be shared by any number of elements.
title.setTextColor(new RainbowColor());
```

See [Colors and paints](../styling/colors-and-paints.md).

## Background and border

A `TextOverlay` is **transparent with no border** by default. Style it like any element:

```java
TextOverlay badge = new TextOverlay("NEW", 12f, Color.WHITE);
badge.getStyling().setBackgroundColor(new Color(0.2f, 0.4f, 1f, 1f));
badge.getStyling().setCornerRadius(6f);
```

The background is drawn at the overlay's own bounds, which are exactly the text's size, so add your own
padding by placing the text inside a container or layout.

## Weight and slant (bold and italic)

```java
TextOverlay heading = new TextOverlay("Section Title", 20f, Color.BLACK);
heading.setBold(true);
heading.setItalic(true); // both can be on at once
```

`setBold`/`setItalic` synthesize the weight/slant on whatever typeface is already loaded (Skia's font
embolden and skew), instead of requiring a separate bold or italic cut of the font file. It won't look quite
as clean as a real bold font, but works with any typeface — including one you only ever loaded a single
Regular file for. This is what [`FormattedTextOverlay`](formatted-text-overlay.md) uses internally to draw
headings bold without asking for a second font.

## Text is one line

`TextOverlay` does not wrap and does not interpret `\n`. For multiple lines use several overlays in a
[`VerticalLayout`](../layouts/vertical-layout.md), or a [`TextFlowOverlay`](text-flow-overlay.md) for wrapped paragraphs.

```java
VerticalLayout lines = new VerticalLayout(20, 20, 300, 80);
lines.setSpacing(4);
lines.addElement(new TextOverlay("Line one", 16f, Color.BLACK));
lines.addElement(new TextOverlay("Line two", 16f, Color.BLACK));
root.addElement(lines);
```

## Clicks and tooltips

```java
title.onMouseClick(e -> System.out.println("clicked"));
title.setCursorMode(CursorMode.POINTER);
title.setTooltip("Click me");
```

Hit-testing uses the overlay's computed bounds, so only the area covered by the text is clickable.

## Cleanup

```java
title.dispose(); // release the native Font when permanently discarding this overlay
```

`dispose()` does not close the shared `Typeface`.

## Method reference

| Method | Description |
|---|---|
| `getText()/setText(String)` | The string |
| `getFontSize()/setFontSize(float)` | Point size |
| `setFontFile(File)` / `setFontFile(String)` | Custom font file |
| `getTextColor()/setTextColor(Paint)` | Solid or gradient fill; setting pins it |
| `isBold()/setBold(boolean)` | Synthesized bold weight, no separate font file needed |
| `isItalic()/setItalic(boolean)` | Synthesized oblique slant, no separate font file needed |
| `getMaxWidth()/setMaxWidth(double)` | The widest an overlay that sizes itself may be; longer text is shortened with `…` |
| `isEllipsize()/setEllipsize(boolean)` | Shorten text wider than a width set with `setWidth` |
| `isEllipsizeMiddle()/setEllipsizeMiddle(boolean)` | Put the `…` in the middle, keeping both ends |
| `render(Renderer)`, `update(float)` | Overridable; `update` also advances an animated text paint |
| `dispose()` | Releases the native `Font` |
| Protected | `drawText(Renderer)`, `getOrBuildFont()`, `updateComputedSize()`, `textLimit()`, `fit(String, Font, float)` (used by [`ButtonOverlay`](button-overlay.md)) |
