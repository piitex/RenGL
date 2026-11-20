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

- **Where it has been run:** the demos work on macOS on Apple silicon (Vulkan through MoltenVK, OpenGL only in a
  harness), on Windows 11 with an NVIDIA RTX 4090 (Vulkan and OpenGL, both natively) and on Linux (Arch). The detailed
  checks in the [3D status document](../3d-status.md#verification-so-far) were made on the first two, and the newer
  features (upscaling, frame generation, ray tracing, virtual geometry, PhysX) were checked on the RTX 4090 only; for
  Linux the GPU and API used were not recorded. AMD and Intel GPUs on Windows, and arm64 Windows, are untested.
- **No 3D in a Metal window.** A [`Viewport3D`](../3d/viewport-3d.md) or `Background3D` needs OpenGL or Vulkan to share
  the GPU with, so on macOS, where the UI draws with Metal by default, pick `RenderAPI.VULKAN` for a window with 3D.
- **Ray tracing is Vulkan only.** OpenGL has no [ray tracing](../3d/ray-tracing.md). A GPU without ray queries (a Mac
  through MoltenVK, older GPUs) traces the same effects in shader code, which is slower. Not done: soft shadows from a
  light with a size, reflections of reflections, rough reflections that blur, skinned, see-through and alpha-cut nodes in
  the rays, a specular hit distance for DLSS Ray Reconstruction (so a mirror's reflection moves with the mirror), and a
  denoiser of another vendor (NRD).
- **Upscalers and frame generation have gaps.** There is no XeSS, and FSR 4 has not been run (it needs an AMD RDNA 4
  GPU). For a temporal [upscaler](../3d/upscaling.md#limits), skinned and morphed meshes carry only the camera's motion,
  so a fast character can ghost, and see-through surfaces take the motion of what is behind them. Frame generation is for
  one window whose 3D view fills it, the UI is interpolated with the scene, and DLSS frame generation needs vsync off.
  The vendors' libraries are not shipped; the [upscaling page](../3d/upscaling.md) says where each comes from.
- **Virtual geometry is Vulkan only.** On OpenGL a mesh with [clusters](../3d/lod.md) draws its classic levels or its
  full mesh.
- **On Windows,** the 3D and 2D demos, the GPU tests and several apps were run on both APIs with no errors in the logs
  (see the [3D status document](../3d-status.md#verification-so-far)). Only a few of them were looked at by eye, and
  nobody has driven the demos with a real mouse and keyboard there. What is checked is a click, a check box and typing
  posted to a real window as Windows messages (`PostedInputTest` in `rengl-core`), which is how the system hands input
  to GLFW.
- **Light from the sky is an approximation.** [Environment lighting](../3d/lighting.md#light-from-the-sky) lights surfaces
  from nine spherical-harmonic terms and blurs reflections with the sky's mipmaps instead of filtering a real
  environment map. Skies other than an [HDR panorama](../3d/lighting.md#hdr-skies) are 8-bit, so a bright sun in them
  adds no more light than white does, and a sharp reflection of a small bright thing is out of reach without ray tracing.
- **Shadow maps have a soft edge of the same width everywhere** ([`setShadowSoftness`](../3d/lighting.md#shadows) scales
  it), at most 8 lights cast them at a time, and every shadow-casting light is a pass over the casters. Ray-traced
  shadows are hard.
- **A node with more than 16 blend shapes switched on at once is blended on the CPU,** as is a mesh with more than about
  two million shape-vertices, so those cost frame time when their weights change every frame
  ([details](../3d/animation.md#morph-targets)).
- **Frustum culling is by spheres** (a node, each copy of an [instanced](../3d/instancing.md) node, a skinned mesh's joints),
  so something just outside the view can still be drawn. [Occlusion culling](../3d/scenes.md#occlusion-culling) is
  opt-in and only knows the nodes you mark as occluders, so other hidden things are still drawn. Only
  [virtual geometry](../3d/lod.md)'s clusters (Vulkan) are culled against depth on the GPU.
- **Skinned and morphed meshes are picked by posing them on the CPU,** once per pose (the result is kept while nothing moves),
  which follows the animation but costs a pass over the vertices when it changes.
- **Blended surfaces are sorted per node (and per copy of an instanced node), by the centre of its mesh,** so overlapping
  see-through objects can still draw in the wrong order where they interpenetrate or one is much bigger than the other.
  Split a large see-through surface into pieces if that shows; a single mesh that folds over itself is fixed with
  `Node3D.setSortTriangles(true)`.
- **A [`Background3D`](../3d/background-3d.md) sees game input only** (by design: the UI has priority). A container
  with a click listener, or a draggable or scrollable element under the mouse, takes the event first and the 3D never
  sees it. As an `Element`'s background (3D behind one panel) its input is checked only without a window.

## Physics

The [built-in solver](../3d/physics.md#limits) and the [PhysX backend](../3d/physx.md#differences-to-know-about) list
their own limits; these are the ones most likely to matter.

- **The built-in solver runs on one thread,** and leaves out gyroscopic effects.
- **Fast bodies are swept only against what stands still.** Two fast bodies meeting each other, or one meeting a moving
  kinematic platform, are left to the contacts.
- **A mesh can't be on a moving body.** A concave moving body is cut into hulls with `ConvexDecomposition`, which cuts
  only along the X, Y and Z axes.
- **PhysX has no natives for Windows or Linux on arm64** (the built-in solver is used there) and no GPU PhysX.

## File dialogs

- **The dialog is modal to the whole app.** The engine keeps rendering under it, but the main loop is busy until it
  closes, so other queued dialogs, the clipboard and cursor changes wait for it.
- **No dialog title or parent-window option, and no in-engine file browser.** The native file dialog library has no way
  to set a title or prompt text. A "Choose where to extract to" prompt has to go in your own UI beside the dialog.

## Operating system integration

- **Hover feedback while files are dragged over the window, and dragging files out of it, are Windows only.** On macOS
  and Linux GLFW reports a [file drop](../core/window.md#files-dropped-on-the-window) only when it happens, so a drop
  target cannot light up as the drag approaches, and `Window.startFileDrag` answers `UNSUPPORTED`.
- **A drag out of the window carries the files as paths, not as a stream.** The files must exist when the user lets
  go, so an archive's folder is extracted at the drop, and the receiver waits while that happens. The drag shows the
  system's standard cursor with no picture of the files.
- **Only files on disk can be dragged in.** Files that exist only inside another program (the contents of a zip
  folder in Explorer) are not taken.
- **No Dock or taskbar progress, and no system notification** when a long job finishes in the background.
- **Web views need an installed browser outside Windows, by default.** On macOS and Linux a [web view](../webview/README.md)
  runs an installed Chrome, Edge, Chromium or Brave headless and draws it as pictures, with no native-window mode. That
  path has been run only on Windows, against Edge. Engines of the system's own, WKWebView (macOS) and WebKitGTK (Linux),
  exist and are opt-in with `setEngine`, but were written without a Mac or a Linux desktop and have never run; `AUTO`
  does not pick them. The web view page lists what is untested and has a self-check to run on a real machine.

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
- **The Linux decoder (GStreamer) is not confirmed.** It was written from GStreamer's headers on a Windows machine:
  the logic around it is tested with a pipeline that is made up, and the real calls are checked only by
  `SystemMediaSourceTest` and `VideoPlayerTest` when they run on Linux (they skip when GStreamer lacks the MP4 demuxer
  or an H.264 decoder). The engine has since been run on Arch Linux, but playing real H.264 there was not recorded, so
  expect a mistake until it is. It needs GStreamer 1.10 or newer and a plug-in for each codec (`gst-libav` for H.264 and AAC). The time of
  each picture is read from a fixed place in GStreamer's buffer structure (64-bit builds only), and times that do not
  make sense are replaced by counting frames. A stream that changes its picture size part way through is not followed.
  A machine without GStreamer plays only the formats the engine decodes itself.
- **A seek restarts the sound,** which leaves a brief gap while the decoder fills the first part (about 25 ms at 1080p,
  85 ms at 4K). Looping has no gap.
- **Tested with files the engine's own test writer produces,** covering all three AVI index layouts, not yet with
  FFmpeg or camera output. If a real file fails to open or looks wrong, that is a bug to report.
