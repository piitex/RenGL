# Known limitations

[Documentation](../README.md) › [Reference](README.md)

Things that are not implemented, or that behave in a way that may surprise you, and that RenGL could change. Choices
that are the point of a feature, and behavior that its own page already describes, are not repeated here. If you hit
something not listed, please add it.

## Graphics

- **Smooth sprites turned at an angle can leave a few see-through pixels between them in snapshots.** Only the CPU
  renderer that [snapshots](../core/snapshots.md) use does this, and only for sprites of a smooth texture drawn
  turned. On screen, and for pixel-art textures, sprites side by side never leave gaps.
- **Tile maps are orthogonal only.** [`TileMap`](../graphics/tile-maps.md#loading) reads Tiled's square and
  rectangular grids, not isometric, staggered or hexagonal maps, nor layers compressed with zstd or images embedded
  in the file. Layer blend modes are ignored and text objects aren't drawn.
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
- **At most 4 lights cast shadows at once,** with a soft edge of the same width everywhere
  ([`setShadowSoftness`](../3d/lighting.md#shadows) scales it).
- **Morph targets are blended on the CPU,** so a mesh with very many vertices whose weights change every frame costs
  frame time ([details](../3d/animation.md#morph-targets)). glTF cubic-spline animation arrives as plain keys,
  because Assimp drops the tangents.
- **Frustum culling is per node,** by a sphere: an [instanced](../3d/instancing.md) node is kept or dropped as a whole,
  and a skinned mesh is never culled. There is no occlusion culling, so things hidden behind other things are still drawn.
- **Skinned and morphed meshes are picked by posing them on the CPU,** which follows the animation but costs a pass over
  the vertices per pick.
- **Blended surfaces are sorted per node, by the centre of its mesh,** so overlapping see-through objects can still draw
  in the wrong order where they interpenetrate or one is much bigger than the other. Split a large see-through surface into
  pieces if that shows.
- **A [`Background3D`](../3d/background-3d.md) sees game input only.** A container with a click listener, or a draggable
  or scrollable element under the mouse, takes the event first and the 3D never sees it. Using it as an `Element`
  background (3D behind one panel) is untested.

## File dialogs

- **The dialog is modal to the whole app.** The engine keeps rendering under it, but the main loop is busy until it
  closes, so other queued dialogs, the clipboard and cursor changes wait for it.
- **No dialog title or parent-window option, and no in-engine file browser.**

## Audio

- **No sound cones, occlusion by walls, or reverb.**

## Video

- **There is no Windows decoder for H.264, HEVC and AAC.** They play only on macOS, where the system decoder reads
  them. A Windows backend (Media Foundation) is not written yet.
- **Looping and seeking restart the sound,** which can leave a brief gap.
- **Tested with files the engine's own test writer produces,** covering all three AVI index layouts, not yet with
  FFmpeg or camera output. If a real file fails to open or looks wrong, that is a bug to report.
