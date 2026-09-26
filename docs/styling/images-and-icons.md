# Images and icons

[Documentation](../README.md) › [Styling](README.md)

Three small classes back [`ImageOverlay`](../overlays/image-overlay.md) and
[`IconOverlay`](../overlays/icon-overlay.md). You rarely call them directly, but they explain the loading behavior
and errors.

## ImageLoader

`me.piitex.engine.ui.image.ImageLoader` decodes an image file once and shares the decoded `ImageLoader` among every
overlay that uses the same file.

```java
Image image = ImageLoader.load("src/main/resources/demo.png"); // File or String
```

- The key is the file's **absolute path**, so relative paths work.
- The format is detected from the file's **bytes**, not its extension. PNG, JPEG, WEBP, GIF, BMP, ICO and WBMP
  all go through the same call, limited by which codecs the Skia build includes.
- Errors: `UncheckedIOException` if the file cannot be read, `IllegalArgumentException` if it is not a decodable
  image.
- Cached images are **never closed**. Decoding is expensive and drawing from a decoded image is cheap, which is a
  reasonable trade for a typical UI. If you load huge numbers of unique large images, this cache will grow.

An image drawn at a size other than its own is sampled smoothly, blending neighbouring pixels. For pixel art that
should keep hard edges when scaled, draw it as a [sprite](../graphics/sprites.md) from a texture with
`setSmooth(false)`.

## IconCache

`me.piitex.engine.ui.image.IconCache` rasterizes an **SVG** into an `Image` using Skia's built-in SVG renderer.
No third-party SVG library is needed.

```java
Image fromFile = IconCache.load(new File("assets/logo.svg"), 64, 64);
Image fromClasspath = IconCache.loadResource("icons/feather/alert-triangle.svg", 24, 24);
```

An SVG has no pixel size of its own, so it is re-rendered at whatever size is requested. The cache key is
**the source and the size**. The same icon at two sizes is rasterized (and cached) twice.

`IconCache.rasterizeFile` and `IconCache.rasterizeResource` render without caching the result. You own the returned
`Image` and should close it when nothing draws it any more. This is what a resized
[`IconOverlay`](../overlays/icon-overlay.md#changing-size) uses, since caching every size it passes through would grow without bound.

`loadResource` resolves the path through the classloader, so it works for icons inside your own jar or any jar on
the classpath.

## IconAsset

A record that names a classpath SVG:

```java nocompile
public record IconAsset(String resourcePath) {}
```

```java
IconAsset logo = new IconAsset("icons/mine/logo.svg");
IconOverlay icon = new IconOverlay(logo, 32, 32);
```

## Feather

`me.piitex.engine.ui.icons.Feather` is the built-in icon set: about 290 constants (`Feather.ACTIVITY`,
`Feather.ALERT_TRIANGLE`, `Feather.CHECK`, `Feather.SETTINGS`, `Feather.X`, ...), each an `IconAsset` pointing at
a bundled SVG. Feather is MIT-licensed (Copyright (c) 2013-2023 Cole Bemis), and the license text is bundled on the
classpath at `icons/feather/LICENSE`.

Icons ship as `stroke="currentColor"`, which the rasterizer resolves to **black**. Recolor with a tint:

```java
IconOverlay check = new IconOverlay(Feather.CHECK_CIRCLE, 20, 20);
check.setTint(Color.GREEN);
```

To browse the set, read the constants in `Feather`, or list `src/main/resources/icons/feather/`. A constant is the
file name in SCREAMING_SNAKE_CASE (`arrow-up-circle.svg` is `Feather.ARROW_UP_CIRCLE`).

## Using an icon pack of your own

1. Put the SVGs on the classpath (for example `src/main/resources/icons/mine/`).
2. Wrap each path in an `IconAsset`.
3. Construct [`IconOverlay`](../overlays/icon-overlay.md)s from them and tint to taste.

```java
public final class Icons {
    private Icons() {}
    public static final IconAsset HOME = new IconAsset("icons/mine/home.svg");
    public static final IconAsset USER = new IconAsset("icons/mine/user.svg");
}
```

Check the license of any pack you ship.

## Errors you may see

| Situation | Result |
|---|---|
| Image file missing/unreadable | `UncheckedIOException` |
| File is not an image | `IllegalArgumentException("Not a decodable image file: ...")` |
| Classpath icon missing | An exception from `loadResource` naming the path |
| Font file invalid | `IllegalArgumentException` from `Font.load` |

All are thrown when the widget is constructed (or when you change the file), not later during drawing.
