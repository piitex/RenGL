# ImageOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.ImageOverlay` draws an image loaded from disk, **scaled to fill the element's
bounds**.

```java
ImageOverlay logo = new ImageOverlay("src/main/resources/demo.png", 200, 120);
logo.setPosition(40, 40);
root.addElement(logo);
```

## Constructors

| Constructor | Size |
|---|---|
| `ImageOverlay(File imageFile)` / `ImageOverlay(String path)` | The image's natural pixel size |
| `ImageOverlay(File imageFile, double width, double height)` / `ImageOverlay(String path, double width, double height)` | Scaled to the given size |

Loading goes through [`ImageLoader`](../styling/images-and-icons.md#imageloader), which detects the format from the file's own bytes rather than trusting the
extension. PNG, JPEG, WEBP, GIF, BMP and ICO load through the same constructors, limited by which codecs the
Skia build includes. See [Images and icons](../styling/images-and-icons.md).

## Size

```java
int w = logo.getNaturalWidth(); // the file's own pixel size, whatever the overlay is set to
int h = logo.getNaturalHeight();
logo.resetToNaturalSize(); // undo any explicit size
logo.setWidth(100); // stretches the image to the new bounds; no aspect-ratio lock
logo.setHeight(100);
```

The image is **stretched to fill** the overlay's width and height. To keep the aspect ratio, compute the
size yourself:

```java
double aspect = (double) logo.getNaturalWidth() / logo.getNaturalHeight();
logo.setWidth(300);
logo.setHeight(300 / aspect);
```

## Swapping the image

```java
logo.setImageFile("other.png"); // File or String; keeps the current width/height
Image raw = logo.getImage(); // the decoded Skia image, for advanced use
```

## Opacity, tint and rounded corners

```java
logo.setOpacity(0.6f); // 0..1 (see the note below)
logo.setTint(Color.BLUE); // recolor RGB to blue, keeping the image's own alpha as a mask
logo.setTint(null); // back to the image's own colors
logo.getStyling().setCornerRadius(16f); // rounded image corners
```

**Tint** recolors the image's RGB while keeping its alpha as a mask. That is the standard way to recolor a
monochrome asset such as an icon. It is not suited to photographs.

**Opacity note.** `ImageOverlay` overrides `getOpacity()/setOpacity()` from [`Element`](../core/element.md) so that the value fades the
image pixels themselves. `fadeIn`/`fadeOut` drive that same setter, so on an image they fade the image. An
image's own background fill and border (if you added any) are not faded along with it. See
[Animation](../animation/transitions.md).

## Background and border

An image overlay is transparent with zero border by default. Add a backing color (for example, letterboxing
behind a transparent PNG) through its styling:

```java
logo.getStyling().setBackgroundColor(Color.BLACK);
logo.getStyling().setBorderThickness(2f);
logo.getStyling().setBorderColor(Color.WHITE);
```

## Making an image clickable

```java
logo.setCursorMode(CursorMode.POINTER);
logo.onMouseClick(e -> System.out.println("logo clicked"));
logo.setTooltip("Open the website");
```

## Memory

There is no `dispose()`. The decoded image belongs to [`ImageLoader`](../styling/images-and-icons.md#imageloader), is shared by every overlay using the same
file, and is never closed.

## Method reference

| Method | Description |
|---|---|
| `setImageFile(File)` / `setImageFile(String)` | Swap the image |
| `getImage()` | The Skia `ImageLoader` |
| `getNaturalWidth()` / `getNaturalHeight()` | Pixel size of the source |
| `resetToNaturalSize()` | Resize to natural size |
| `getOpacity()` / `setOpacity(float)` | Image opacity (0..1) |
| `getTint()` / `setTint(Color)` | Recolor the image's RGB |
