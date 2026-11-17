# Levels of detail

[Documentation](../README.md) › [3D](README.md) › Levels of detail

> Needs the `rengl-3d` [module](../modules.md).

A mesh far from the camera covers a few dozen pixels, but without help it is still drawn with every triangle it has. A
**level of detail** is a simpler version of the mesh drawn in its place when the difference can't be seen. The scene
picks a level for every node and every instance each frame, so the rest of the game does not change: picking, physics,
bounds and animation all keep using the mesh itself.

```java
Mesh tree = model.root().find("tree").getMesh();
tree.generateLods();          // simpler levels, made with meshoptimizer
tree.setImpostor(true);       // and a picture card for the far distance
```

## Where the levels come from

| How | Call | Notes |
|---|---|---|
| Made for you | `mesh.generateLods()`, `mesh.generateLods(LodSettings)` | meshoptimizer takes away triangles while keeping the shape, the shading and the texture as well as it can. By default four levels with a half, a quarter, an eighth and a sixteenth of the triangles, stopping at 64 triangles or when a level would be more than a tenth of the mesh's size off. Skinning, blend shapes and the second texture coordinates come along. A few milliseconds per hundred thousand triangles, on any thread. |
| For a whole model | `Model.load(path, new LodSettings())`, `model.generateLods(settings)` | Every mesh of the file, with its full mesh reordered for the GPU too. In a scene file, the "Detail Levels" box of a model node. |
| Made by hand | `mesh.addLod(level, error)`, `mesh.addLod(level)` | Your own simpler mesh. Without an error, it is measured: the farthest a sample of either mesh's vertices is from the other's surface. |
| From the model file | nodes named `rock_LOD0`, `rock_LOD1`, ... (the Unity and Unreal way); glTF `MSFT_lod` | Read on every load. The first becomes `rock`, the others leave the tree and become its levels. `MSFT_screencoverage` gives the size on a 1080 pixel high screen at which each level starts, and the last value the size below which nothing is drawn. |

A level made by hand takes the place of the made level with the same number, so `generateLods` after `addLod` keeps it.

## How a level is picked

Each level knows its **error**: how far, in the mesh's units, its surface is from the full mesh's. The scene draws the
simplest level whose error, seen from where the camera is, is under `Scene3D.setLodPixelError` pixels (1 by default).
So the switch happens at the distance where it can't be seen, whatever the size of the object, the window or the field
of view, with no distances to tune. A node keeps its level until the error is 15% past the limit either way, so one at
the border does not flick. The pick is made for the picture as shown, so an upscaled frame keeps the same levels.

| Setting | What it does |
|---|---|
| `Scene3D.setLodEnabled(boolean)` | Off, every node draws its mesh itself (apart from forced levels). On by default. |
| `Scene3D.setLodPixelError(pixels)` | The quality knob. 2 to 4 still looks right in a busy scene and draws far fewer triangles. |
| `Scene3D.setLodBias(bias)`, `Node3D.setLodBias(bias)` | Each 1 doubles the error allowed (simpler sooner); -1 halves it. The node's adds to the scene's. |
| `Node3D.setForcedLod(level)` | Always this level; -1 lets the scene pick. |
| `Node3D.setCullScreenSize(share)` | Stops drawing a node (or an instance), shadow and all, once it is shorter than this share of the screen's height. |
| `Scene3D.setLodDebugColors(boolean)` | Paints each node by the level it draws: white, green, yellow, orange, red, magenta, blue, cyan. |

`Scene3D.getStats()` has `triangles()` and `recordsAtLevel(level)`.

## Changing level without a pop

`Scene3D.setLodTransition(LodTransition.DITHER)`, the default, draws both levels for `setLodFadeTime` seconds (a quarter
by default), each in a scattered half of the pixels that moves from the old level to the new. The two together always
cover the surface once, so nothing is see-through. With a temporal upscaler the pattern changes every frame and becomes a
smooth fade. `NONE` changes at once. A node blended on the CPU (more than 16 blend shapes on) changes at once too.

## Shadows and rays

- `Scene3D.setShadowLodBias(levels)`: shadows are drawn this many levels simpler than the picture, 1 by default. A shadow
  shows an outline only, and blurred. Set it to 0 in a scene with many cheap instances, where the extra shadow draws cost
  more than the triangles they save.
- `Scene3D.setRayTracingLodBias(levels)`: rays hit the level the camera sees, this many simpler; 0 by default, so traced
  shadows fit the shapes on screen. Each level has its own acceleration structure. For a mesh drawn from clusters, see
  [Virtual geometry](#virtual-geometry).
- The occlusion picture of occluders (`Node3D.setOccluder`) uses the simplest level.

## Picture cards

`mesh.setImpostor(true)` (or `LodSettings.impostor(true)`) adds a last level after the simplest mesh: a card facing the
camera with pictures of the mesh from 64 directions spread all round. Each pixel blends the four views nearest the
direction it is seen from. The pictures hold the colour, the direction the surface faces and its depth, so the card is lit
and shadowed like the mesh, and hides and is hidden by what is in front. A forest of thousands of trees costs two triangles
a tree in the distance.

- The level number of the card is one past the simplest mesh (`chain.size()`).
- It is drawn once its outline error (a twentieth of the mesh's radius, for how far apart the views are) is under the pixel
  error: about 40 pixels across at the default.
- The pictures are taken on the GPU the first time a card is needed, once per material, and hold 8 MB (two 1024 pixel
  square atlases).
- No card for a skinned or morphed node, a see-through or additive material, or lines. The material's other maps (metal and
  roughness, occlusion, glow) are their plain values on a card.
- Outside the picture itself (shadows, depth, motion, bounced light) the simplest mesh stands in for the card.

## Virtual geometry

On Vulkan, a mesh can also be split into **clusters** of up to 124 triangles at many levels at once, the way Nanite does
it. Each frame, a compute shader on the GPU picks the detail of every part of every instance: a big mesh is fine where it
faces the camera and coarse at its back and far end, with no crack between parts at different levels and no change of level
to fade.

```java
rock.buildClusters();               // or LodSettings.clusters(true) when a model loads
scene.setVirtualGeometry(true);     // Vulkan only; elsewhere the classic levels are drawn
```

- **How the clusters are picked.** The finest clusters are grouped by fours. Each group is simplified to half with its
  outline held in place, then split again, over and over. A cluster is drawn when its own error is under the pixel error on
  screen and its coarser replacement's is not. The compute shader makes that choice for every cluster of every instance and
  writes one draw command per cluster. The passes then draw the list with `vkCmdDrawIndexedIndirectCount`. The commands use
  the mesh's own vertices and the scene's own shaders, so materials, lights, shadows, fades and motion are as for any mesh.
- **Shadows** get a list of their own, picked with the error doubled for each step of `setShadowLodBias`, from every
  instance, in view or not.
- **A tree over the clusters.** Each level's clusters sit under a tree that splits them by place, eight ways, down to leaves
  of 16. Each node knows the spheres around what is under it, the finest error under it and the coarsest replacement. Where
  many instances times many clusters would be tested (about 4 million or more), the GPU walks the tree a depth at a time
  instead. It skips each node whose clusters are all replaced, all too coarse, or out of view, and tests only the clusters of
  the leaves it reaches. It picks the same clusters. Smaller jobs test every cluster, which costs less than the walk's passes
  there.
- **Hidden clusters** (`Scene3D.setVirtualGeometryOcclusion`, off by default). Each cluster is first tested against a depth
  pyramid of last frame. What it does not hide is drawn, with the scene's solid things, into a depth picture of this frame.
  The rest is then tested again against a pyramid of that picture. What the camera turns to see is found in the same frame,
  and nothing in view is left out. It costs an extra depth pass of what is in view, so it pays only where much is hidden.
- **With classic levels too.** A mesh that has both draws its clusters while the chain would need the full mesh, and the
  chain's simpler levels and its card once it is smaller than that. A simpler level of the chain has fewer triangles than
  clusters cut to the same error: measured on a lumpy ball of 9,000 triangles, 1,104 against 2,380 at 12 units. Each cluster
  carries the error of every simplification below it, and the held outlines simplify less. A mesh without levels always
  draws its clusters.
- **One node off.** `Node3D.setVirtualGeometry(false)` (the `virtualGeometry` property in a scene file) draws that node's
  classic levels, or its mesh where it has none, while the rest of the scene draws clusters.
- **Rays** can't change detail along a mesh as the picture does. A mesh with clusters and no levels is traced as an even cut
  of its clusters: every part at the error the mesh's nearest point allows, so nowhere coarser than the picture. Each step
  of `setRayTracingLodBias` doubles that error. The cuts are made the first time they are needed and kept, each with its own
  acceleration structure. With virtual geometry off, rays hit the whole mesh, as the picture does.
- `Scene3D.isUsingVirtualGeometry()` says whether the last frame drew any. Its `getStats().triangles()` includes the
  clusters' triangles as the GPU counted them a frame or two before.

| Measured (4090, 192 pixel view) | |
|---|---|
| GPU's pick against the CPU's reference cut, ten balls of 9,024 triangles | 3,096 triangles both |
| A wall hiding half the row, hidden clusters off and on | 3,108 and 1,253 triangles; the same picture |
| A ball close enough to mix levels | no pixel shows the sky through it |
| A fine ball half behind a wall whose edge moves a sixteenth of a pixel at a time, hidden clusters off and on, 1, 4 and 8 samples, the wall straight and turned (256 pixel view) | about 790,000 and 270,000 triangles over the 16 steps; not one pixel differs |
| 1,600 balls of 65,000 triangles with shadows, every cluster tested and the tree walked | 271,308 triangles both; not one pixel differs |
| Clusters the tree's walk tests, over 400 views from close to far | 28% of a 231 cluster graph, 13% of a 1,714 cluster one |
| The pick for 100,000 balls of 9,000 triangles (23 million cluster tests) | 0.39 ms testing every cluster, 0.14 ms walking the tree |

Limits:

- Vulkan only, on a GPU with multi-draw indirect and draw indirect count, and drawn only for nodes that are not skinned,
  morphed or see-through. Everywhere else the classic levels are drawn. `-Drengl.3d.virtualgeometry=false` turns it off.
- One indirect draw per visible cluster; the GPU's command processor takes them, not the CPU. At most about a million
  cluster draws a frame (20 MB of commands). Each list gets room for every finest cluster of every instance; past the
  million, each gets the same share of that. A list that fills its share leaves clusters out, and a warning is logged.
- The pick itself is cheap. On a 4090, smaller jobs take less time than the CPU takes to record the work, about a tenth of a
  millisecond. The tree's walk only shows its gain in the biggest scenes.
- The finest cut of every instance in view is a draw per finest cluster: tens of thousands of instances of a big mesh, all
  close, are what this handles worst.
- Rays trace the classic level picked for the node, or an even cut of its clusters where it has no levels; picking uses the
  whole mesh. A traced shadow can change at once where the even cut changes, as it does when a classic level changes.
- Hidden clusters are found from depth drawn at one sample per pixel. With multisampling no edge sample was lost in the
  measurement above the limits: the finest texels the test reads hold two by two pixels, and a cluster's box reaches past
  its triangles.

## Limits

- Each level is a mesh of its own on the GPU. A level picked once is uploaded the first time it is drawn, as any mesh is.
  `Scene3D.preload` uploads the levels too.
- Picking a level costs about 0.1 microseconds an instance on the render thread (measured: 7,200 instances add 0.7 ms a
  frame). On a fast GPU a few million triangles cost less than that, so levels pay off on slower GPUs, bigger meshes or
  more instances.
- Hysteresis is kept per node per renderer: two viewports of one scene with different cameras share it.
