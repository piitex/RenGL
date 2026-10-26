# Known limitations

[Documentation](../README.md) › [Reference](README.md)

Things that are not implemented, or that behave in a way that may surprise you, and that RenGL could change. Choices
that are the point of a feature, and behavior that its own page already describes, are not repeated here. If you hit
something not listed, please add it.

## Graphics

- **Tile maps: no infinite isometric or hexagonal maps, no rotated hexagonal tiles.** [`TileMap`](../graphics/tile-maps.md#isometric-staggered-and-hexagonal-maps)
  reads all four of Tiled's orientations, but an infinite map must be orthogonal and a hexagonal tile's 60 and 120 degree
  rotations are drawn unrotated. Text objects are drawn in the engine's default font, whatever family Tiled shows.
- **Post effects on a turned camera are resampled.** Inside a turned `Camera`, `pushEffect`'s layer is drawn upright
  and turned afterwards, so its content is slightly softer than without the effect. Push effects outside the camera,
  in window coordinates, to avoid it.

## Rendering and 3D

The 2D UI looks the same on every [rendering API](../core/rendering-apis.md). These limits are about Vulkan and the
optional [3D layer](../3d/README.md); the [3D status document](../3d-status.md) has the reasons and the plans.

- **Vulkan and OpenGL have been run on two machines:** macOS on Apple silicon (Vulkan through MoltenVK, OpenGL only in
  a harness) and Windows 11 on an NVIDIA RTX 4090 (both natively). Linux, AMD and Intel GPUs, and arm64 Windows are
  untested.
- **On Windows,** the 3D and 2D demos, the GPU tests and several apps were run on both APIs with no errors in the logs
  (see the [3D status document](../3d-status.md#verification-so-far)). Only a few of them were looked at by eye, and
  nobody has driven the demos with a real mouse and keyboard there. What is checked is a click, a check box and typing
  posted to a real window as Windows messages (`PostedInputTest` in `rengl-core`), which is how the system hands input
  to GLFW.
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

- **On Windows the system decoder is new and lightly tested.** H.264 and AAC play through Media Foundation; the
  tests ran only on small clips (640x360 and 320x180) on one machine, and speed on 1080p and 4K was not measured.
  Decoding is done by the CPU: the reader is not given a GPU device, so Windows' hardware decoders are not used.
  **HEVC plays only where Windows has the HEVC Video Extensions** (a free Store add-on that some editions lack); without
  them the file fails to open with the usual conversion hint. A machine without Media Foundation (a Windows "N"
  edition without the Media Feature Pack) has no system decoder at all.
- **The Linux decoder (GStreamer) has never been run.** It was written from GStreamer's headers on a Windows machine,
  with no Linux to try it on: the logic around it is tested with a pipeline that is made up, and the real calls are
  checked only by `SystemMediaSourceTest` and `VideoPlayerTest` when they run on Linux. Expect to find a mistake in the
  first run. It needs GStreamer 1.10 or newer and a plug-in for each codec (`gst-libav` for H.264 and AAC). The time of
  each picture is read from a fixed place in GStreamer's buffer structure (64-bit builds only), and times that do not
  make sense are replaced by counting frames. A stream that changes its picture size part way through is not followed.
  A machine without GStreamer plays only the formats the engine decodes itself.
- **A seek restarts the sound,** which leaves a brief gap while the decoder fills the first part (about 25 ms at 1080p,
  85 ms at 4K). Looping has no gap.
- **Tested with files the engine's own test writer produces,** covering all three AVI index layouts, not yet with
  FFmpeg or camera output. If a real file fails to open or looks wrong, that is a bug to report.
