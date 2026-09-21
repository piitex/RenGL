# IconOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.IconOverlay` is an [`ImageOverlay`](image-overlay.md) whose pixels come from
**rasterizing an SVG** at the overlay's own size (through Skia's built-in SVG renderer) instead of decoding
a PNG or JPEG. Everything else about [`ImageOverlay`](image-overlay.md) (opacity, corner radius, tint, hit-testing, styling)
is identical.

Most icon packs ship single-color SVGs meant to be recolored by the app, so `setTint` is usually the first
thing you reach for.

```java
IconOverlay warning = new IconOverlay(Feather.ALERT_TRIANGLE); // bundled Feather icon, 24 x 24
warning.setTint(Color.ORANGE);
layout.addElement(warning);
```

## Two sources

| Source | Constructors |
|---|---|
| An icon **bundled on the classpath** ([`IconAsset`](../styling/images-and-icons.md#iconasset)) | `IconOverlay(IconAsset)` (24 × 24) and `IconOverlay(IconAsset, double width, double height)` |
| An **SVG file** on disk | `IconOverlay(File, double width, double height)` and `IconOverlay(String path, double width, double height)` |

The file constructors require a size. An arbitrary external SVG has no size the class can assume is
reasonable.

```java
IconOverlay big = new IconOverlay(Feather.SETTINGS, 48, 48);
IconOverlay disk = new IconOverlay("assets/logo.svg", 64, 64);
```

## The Feather icon set

The engine bundles the [Feather](https://feathericons.com) icon set (MIT-licensed, license text bundled at
`icons/feather/LICENSE`). Every icon is a constant in `me.piitex.engine.ui.icons.Feather`, named after the file in
SCREAMING_SNAKE_CASE (`alert-triangle.svg` becomes `Feather.ALERT_TRIANGLE`).

```text
Feather.ACTIVITY
Feather.ALERT_CIRCLE
Feather.ALERT_TRIANGLE
Feather.CHECK
Feather.CHECK_CIRCLE
Feather.SETTINGS
// ... about 290 in total; see the Feather class for the full list
```

Feather icons use `stroke="currentColor"`, which this engine's rasterizer resolves to **plain black**. Use
`setTint` to match your theme:

```java
IconOverlay icon = new IconOverlay(Feather.CHECK_CIRCLE);
icon.setTint(new Color(0.2f, 0.8f, 0.4f, 1f));
```

## Your own bundled icons

[`IconAsset`](../styling/images-and-icons.md#iconasset) is a record wrapping a classpath resource path. Declare constants the same way for icons you ship
in your own jar:

```java
public final class MyIcons {
    public static final IconAsset LOGO = new IconAsset("icons/mine/logo.svg");
}
```

```java
IconOverlay logo = new IconOverlay(MyIcons.LOGO, 32, 32);
```

The path is resolved with the classloader, so it works for icons inside any jar on the classpath.

## Changing size

An icon is **rasterized once at the size you construct it with**. Calling `setWidth` afterwards stretches
that raster (which can look soft), it does not re-render the SVG. Construct a new `IconOverlay` at the size you
actually want.

## Icon as a clickable button

```java
IconOverlay close = new IconOverlay(Feather.X, 20, 20);
close.setCursorMode(CursorMode.POINTER);
close.setTooltip("Close");
close.onMouseClick(e -> System.out.println("closed"));
```

## Method reference

`IconOverlay` adds only its constructors. Everything else is inherited from
[`ImageOverlay`](image-overlay.md): `setTint`, `setOpacity`, `getNaturalWidth`, `resetToNaturalSize`, and so on.

Related classes: [`IconAsset`](../styling/images-and-icons.md#iconasset) (`me.piitex.engine.ui.image`), [`IconCache`](../styling/images-and-icons.md#iconcache) (the SVG rasterizer/cache), and
[`Feather`](../styling/images-and-icons.md#feather) (`me.piitex.engine.ui.icons`). See [Images and icons](../styling/images-and-icons.md).
