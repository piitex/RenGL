# Fonts

[Documentation](../README.md) › [Styling](README.md)

`me.piitex.engine.ui.text.Font` loads and caches fonts. It is a static utility, in the same shape as
[`ThemeManager`](theming.md), [`Clipboard`](../input/input-and-focus.md#clipboard) and `CursorManager`.

Parsing a font file is expensive. Building a Skia `Font` from an already-loaded `Typeface` is cheap. The cache
loads each file once, keyed by absolute path, and every widget using that file shares the same `Typeface`.

## Set one default font

Do this once, early, right after creating your window. Every text widget built without an explicit font
file then uses it.

```java
Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));

TextOverlay a = new TextOverlay("Uses Roboto");
TextFieldOverlay b = new TextFieldOverlay(200, 28); // also Roboto
```

```java
Font.setDefaultFont(null); // go back to the platform default
```

`setDefaultFont` only affects widgets constructed **afterwards** that use the no-file constructors. It
does not change widgets already built, and it never overrides a widget given its own font file.

If you never set a default, the platform's default typeface is used (`Font.systemDefault()`: Helvetica on macOS).
If the OS font manager cannot resolve one, a warning is printed to stderr and text can fail to draw. Shipping and setting a
font of your own is the safe choice. The demo bundles `Roboto-Regular.ttf` in `src/main/resources`.

## Per-widget fonts

Text-bearing widgets have a constructor that takes a font file, and most have a setter:

```java
TextOverlay heading = new TextOverlay("Title", new File("fonts/Display.ttf"), 40f, Color.BLACK);
heading.setFontFile("fonts/Other.ttf"); // File or String path

TextFieldOverlay field = new TextFieldOverlay(0, 0, 200, 28, new File("fonts/Mono.ttf"), 14f, Color.BLACK);
```

Cheap to call repeatedly with the same file, since the cache returns the same `Typeface`.

## The API

| Method | Description |
|---|---|
| `Font.load(File)` / `load(String)` | Loads (or returns cached) `Typeface`. Throws `IllegalArgumentException` if the file is not a valid font |
| `Font.getDefault()` | The default set with `setDefaultFont`, or `systemDefault()` |
| `Font.setDefaultFont(File)` | Sets (or with `null`, clears) the default font file |
| `Font.systemDefault()` | The platform default typeface. May return `null` (with a warning) if none resolves |
| `Font.sized(size)` | A Skia `Font` of the default typeface at `size` points, for drawing text with the `Renderer` directly. Make it once, after `setDefaultFont`, not every frame |

## Sizing and metrics

A widget's font size is in points and is set per widget (`setFontSize(float)`, or the constructor). A `Font` is
built lazily and only rebuilt when the size or typeface actually changes.

## Cleanup

- Typefaces are cached process-wide and **never closed**.
- Each widget owns its own native `Font`. Call `dispose()` on [`TextOverlay`](../overlays/text-overlay.md), [`ButtonOverlay`](../overlays/button-overlay.md),
  [`TextFieldOverlay`](../overlays/text-field-overlay.md), [`TextAreaOverlay`](../overlays/text-area-overlay.md), [`RichTextAreaOverlay`](../overlays/rich-text-area-overlay.md) and [`SliderOverlay`](../overlays/slider-overlay.md) when you permanently discard
  one.
- The tooltip and the spell-check popup build their own small fonts from `Font.getDefault()`.
