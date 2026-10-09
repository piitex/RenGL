# Known limitations

[Documentation](../README.md) › [Reference](README.md)

Things that are not implemented, or that behave in a way that may surprise you, and that RenGL could change. Choices
that are the point of a feature, and behavior that its own page already describes, are not repeated here. If you hit
something not listed, please add it.

## Graphics

- **Tile maps are orthogonal only.** [`TileMap`](../graphics/tile-maps.md#loading) reads Tiled's square and
  rectangular grids, not isometric, staggered or hexagonal maps. Text objects are drawn in the engine's default font,
  whatever family Tiled shows.
- **Post effects on a turned camera are resampled.** Inside a turned `Camera`, `pushEffect`'s layer is drawn upright
  and turned afterwards, so its content is slightly softer than without the effect. Push effects outside the camera,
  in window coordinates, to avoid it.

## Rendering and 3D

The 2D UI looks the same on every [rendering API](../core/rendering-apis.md). These limits are about Vulkan and the
optional [3D layer](../3d/README.md); the [3D status document](../3d-status.md) has the reasons and the plans.

- **Vulkan has only been run on one machine:** macOS on Apple silicon, through MoltenVK. Windows and Linux are untested.
- **The demos have never run on OpenGL** on a platform where the engine really uses it (Windows or Linux). The OpenGL
  path of the 3D layer was only tested through a macOS harness.
- **Light from the sky is an approximation.** [Environment lighting](../3d/lighting.md#light-from-the-sky) lights surfaces
  from nine spherical-harmonic terms and blurs reflections with the sky's mipmaps instead of filtering a real
  environment map, and the sky pictures are 8-bit, so there is no bright sun in them and a sharp reflection of a small
  bright thing is out of reach.
- **Shadows have a soft edge of the same width everywhere** ([`setShadowSoftness`](../3d/lighting.md#shadows) scales
  it), and every shadow-casting light is a pass over the casters.
- **A node with more than 16 blend shapes switched on at once is blended on the CPU,** as is a mesh with more than about
  two million shape-vertices, so those cost frame time when their weights change every frame
  ([details](../3d/animation.md#morph-targets)).
- **Frustum culling is by spheres** (a node, each copy of an [instanced](../3d/instancing.md) node, a skinned mesh's joints),
  so something just outside the view can still be drawn. There is no occlusion culling, so things hidden behind other
  things are still drawn.
- **Skinned and morphed meshes are picked by posing them on the CPU,** once per pose (the result is kept while nothing moves),
  which follows the animation but costs a pass over the vertices when it changes.
- **Blended surfaces are sorted per node (and per copy of an instanced node), by the centre of its mesh,** so overlapping
  see-through objects can still draw in the wrong order where they interpenetrate or one is much bigger than the other.
  Split a large see-through surface into pieces if that shows; a single mesh that folds over itself is fixed with
  `Node3D.setSortTriangles(true)`.
- **A [`Background3D`](../3d/background-3d.md) sees game input only** (by design: the UI has priority).** A container with a click listener, or a draggable
  or scrollable element under the mouse, takes the event first and the 3D never sees it. Using it as an `Element`
  background (3D behind one panel) is untested.

## File dialogs

- **The dialog is modal to the whole app.** The engine keeps rendering under it, but the main loop is busy until it
  closes, so other queued dialogs, the clipboard and cursor changes wait for it.
- **No dialog title or parent-window option, and no in-engine file browser.** The native file dialog library has no way
  to set a title or prompt text. A "Choose where to extract to" prompt has to go in your own UI beside the dialog.

## Operating system integration

- **No hover feedback while files are dragged over the window.** GLFW reports a [file drop](../core/window.md#files-dropped-on-the-window)
  only when it happens, so a drop target cannot light up as the drag approaches.
- **No dragging rows out of the app into Finder or Explorer.** There is no native drag source.
- **No Dock or taskbar progress, and no system notification** when a long job finishes in the background.

## Audio

- **Occlusion is not worked out for you.** `Playback.occlusion(amount)` applies the amount you give it; nothing in the
  engine ray-tests your walls.
- **One reverb for the whole world,** switched by hand as the player changes room; it is not tied to the listener's
  position, and it does not blend between two rooms.

## Video

- **There is no Windows decoder for H.264, HEVC and AAC.** They play only on macOS, where the system decoder reads
  them. A Windows backend (Media Foundation) is not written yet.
- **A seek restarts the sound,** which leaves a brief gap while the decoder fills the first part (about 25 ms at 1080p,
  85 ms at 4K). Looping has no gap.
- **Tested with files the engine's own test writer produces,** covering all three AVI index layouts, not yet with
  FFmpeg or camera output. If a real file fails to open or looks wrong, that is a bug to report.
