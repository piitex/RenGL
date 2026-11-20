# Snapshots

[Documentation](../README.md) › [Core](README.md) › Snapshots

A `Snapshot` is a picture of a rendered [`Element`](element.md) (usually a [`Container`](../containers/container.md)) or of the whole [`Window`](window.md).
The typical use is a save slot thumbnail: capture the scene, write a PNG next to the save file, show it later in an [`ImageOverlay`](../overlays/image-overlay.md).

```java
// On the render thread (an onAction / onUpdate callback, say):
try (Snapshot shot = sceneContainer.takeSnapshot(0.25f)) { // quarter size thumbnail
    shot.savePng(Path.of("saves/slot1.png"));
}

// Later, in the load menu:
slot.addElement(new ImageOverlay(new File("saves/slot1.png"), 320, 180));
```

## Taking one

| Call | Captures |
|---|---|
| `element.takeSnapshot()` | The element and its children, at its own size, 1 pixel per point |
| `element.takeSnapshot(scale)` | Same, `scale` pixels per point: `2` is retina sharp, `0.25` a thumbnail |
| `window.takeSnapshot(scale)` | The window backdrop plus every enabled container, at the window size |
| `takeSnapshotAsync(scale, callback)` | Waits for the next frame, then calls `callback` on the render thread. Safe from any thread |

Snapshots are drawn by a CPU `OffscreenRenderer`, not read back from the screen, so they work the same on OpenGL, Vulkan and Metal, do
not flicker the window, and can be a different size than what is on screen. [3D](#3d) is the one part that comes from the GPU.

## 3D

[3D](../3d/README.md) is in snapshots too, with the same calls. A [`Viewport3D`](../3d/viewport-3d.md) or a
[`Background3D`](../3d/background-3d.md) in what is captured draws the picture it last showed in the window, copied back
from the GPU, at its place and with its rounded corners, and the 2D UI around and over it is drawn as usual:

```java
// The 3D view on its own, at its size in pixels:
try (Snapshot shot = viewport.takeSnapshot(viewport.getPixelWidth() / (float) viewport.getWidth())) {
    shot.savePng(Path.of("screenshots/scene.png"));
}

// The whole window, 3D background and viewports included, from any thread:
window.takeSnapshotAsync(1f, shot -> {
    try (shot) {
        shot.savePng(Path.of("screenshots/window.png"));
    } catch (IOException e) {
        log.warn("Could not save the screenshot", e);
    }
});
```

It works on OpenGL and Vulkan, the two APIs that draw 3D. The picture is the finished one, what is on screen:

- **Full size.** It is the viewport's output at its size in pixels, after the scene's [render scale](../3d/scenes.md) and
  upscaler have enlarged it, not the smaller picture the scene drew. `Viewport3D.setResolutionScale` below 1 makes the
  picture itself smaller, and the snapshot scales it up as the window does.
- **Anti-aliased and tone mapped.** Multisampling is resolved and HDR is tone mapped and exposed before the copy, so
  bloom, exposure and the tone curve look as they do on screen.
- **The frame last shown.** A snapshot does not draw the scene again, so it does not move animations, a temporal upscaler
  or path tracing on by a frame. `takeSnapshotAsync` runs at the start of a frame, before the viewport draws again, so it
  gets the frame on screen at that moment. With frame generation on, it is the last drawn frame, not a generated one.

A viewport that has not been drawn in a window yet, or one in a Metal window, has no picture and is left out (the area is
transparent). Each viewport in the snapshot costs one copy from the GPU, which waits for the GPU, so snapshots are for
save slots and screenshots, not for every frame.

## Using the result

| Method | Does |
|---|---|
| `savePng(path)` | Writes a PNG (transparency kept, parent folders created) |
| `toPngBytes()` | The PNG in memory |
| `toOverlay(width, height)` | An `ImageOverlay` showing the picture without touching disk |
| `getImage()` | The Skia `ImageLoader` |
| `close()` | Frees the native image. `toOverlay` shares it, so close only when that overlay is gone |

## Things to know

- **Render thread only** for the synchronous calls, because the UI is read while the render thread may be changing it. Use `takeSnapshotAsync` from anywhere else.
- **A disabled element draws nothing**, so it snapshots as empty. Capture the scene *before* hiding it to open a menu.
- **Clipped to the element.** Children sticking out of it, and tooltips and popups, are not included. Corners outside a rounded background are transparent.
- **What is captured is what would be drawn this frame**: hover colours, current opacity (a mid-fade element is captured mid-fade) and the current state of animated backgrounds.
- The element needs a size; a 0 by 0 element throws `IllegalArgumentException`.
