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
not flicker the window, and can be a different size than what is on screen.

For the same reason a snapshot **leaves out [3D](../3d/README.md)**: a [`Viewport3D`](../3d/viewport-3d.md) or
[`Background3D`](../3d/background-3d.md) is drawn by the GPU, so the CPU renderer has nothing to draw there (a viewport logs
a warning once). To save what a 3D view shows, read the picture back from its target with `RenderTarget.readPixels()` in a
[custom content](../3d/custom-rendering.md).

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
