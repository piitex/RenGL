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

## Animated GIFs

A GIF (or animated WEBP) with more than one frame **plays by itself**: it starts when the overlay is created, the
engine advances it every frame, and it loops as often as the file asks (usually forever). Nothing else is needed.

```java
ImageOverlay cat = new ImageOverlay("assets/cat.gif", 320, 240);
root.addElement(cat);
```

It also works like a small video. `getPlayer()` returns an [`ImagePlayer`](../styling/images-and-icons.md#animated-images-and-gifs-as-video)
with seek, loop count, speed and callbacks, and the overlay has shortcuts for the common controls:

```java
cat.pause();   cat.play();   cat.stop();   // stop rewinds to the first frame
cat.isPlaying();

ImagePlayer player = cat.getPlayer();      // null for a still image
player.setLoopCount(1);                    // play once instead of forever
player.onFinish(() -> cat.fadeOut(0.5f));
player.setSpeed(2f);
player.seek(1.5);                          // seconds
```

Playback runs from `update(delta)`, which a container calls for each element every frame, so a GIF follows
whatever time the scene gives it. An overlay drawn without being updated stays on its first frame. A GIF with a
single frame is treated as a still image (`isAnimated()` is false and `getPlayer()` is null).

Each animated overlay has its own decoder and position, so two overlays of the same file play independently.
Call `dispose()` to free the decoder early; it is freed on garbage collection anyway.

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

A still image has nothing to dispose. The decoded image belongs to [`ImageLoader`](../styling/images-and-icons.md#imageloader), is shared by every overlay using the same
file. The cache lets go of the pictures used longest ago once it holds more than its limit (256 MiB of pixels by default,
`ImageLoader.setCacheLimit`); one an overlay still shows is never closed, and its memory is freed when nothing draws it.
An animation is the exception: `dispose()` frees its decoder (see above).

## Method reference

| Method | Description |
|---|---|
| `setImageFile(File)` / `setImageFile(String)` | Swap the image |
| `getImage()` | The Skia image (for an animation, the frame showing now; draw it, don't keep it) |
| `isAnimated()` / `getPlayer()` | Whether a GIF is playing, and its `ImagePlayer` (null for stills) |
| `play()` / `pause()` / `stop()` / `isPlaying()` | Shortcuts for the player; no-ops on a still image |
| `dispose()` | Free an animation's decoder |
| `ImageOverlay.of(AnimatedImage, w, h)` | Play an animation you decoded yourself |
| `getNaturalWidth()` / `getNaturalHeight()` | Pixel size of the source |
| `resetToNaturalSize()` | Resize to natural size |
| `getOpacity()` / `setOpacity(float)` | Image opacity (0..1) |
| `getTint()` / `setTint(Color)` | Recolor the image's RGB |
