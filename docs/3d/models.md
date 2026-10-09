# Models

[Documentation](../README.md) › [3D](README.md)

`me.piitex.engine.render3d.scene.Model` loads 3D files into the [scene layer](scenes.md) through LWJGL's Assimp:
glTF, GLB, OBJ, FBX and Collada, plus whatever else the bundled Assimp reads.

```java
import me.piitex.engine.render3d.scene.Model;
import me.piitex.engine.render3d.scene.OrbitControls;
import me.piitex.engine.render3d.scene.Scene3D;

import java.io.IOException;
import java.nio.file.Path;

public class LoadShip {
    static void show(Scene3D scene, OrbitControls orbit) throws IOException {
        Model model = Model.load(Path.of("assets/ship.glb"));
        scene.add(model.root());
        orbit.frame(model.bounds());   // aim the camera at it and size the clip planes
    }
}
```

## API

| Method | Description |
|---|---|
| `Model.load(Path)` | Loads a file. Textures and other files it points at (an OBJ's `.mtl`, a glTF's `.bin` and images) are looked up beside it. Throws `IOException` if the file is missing or Assimp can't read it |
| `Model.load(String name, byte[] data)` | Loads from memory. Works for self-contained files, such as `.glb` and `.gltf` with embedded data, not for ones that point at other files. `name` is the file name, whose ending tells Assimp the format (`"ship.glb"`). Handy for a model bundled in the jar |
| `root()` | A [`Node3D`](scenes.md#node3d) tree matching the file's hierarchy. Add it to a scene |
| `meshes()` | The distinct meshes in the file; a mesh used by several nodes is listed once |
| `materials()` | The materials, which you can edit (`setSpecular`, `setUnlit`, ...) before or after adding the model |
| `bounds()` | The box around the whole model in its own space, or `null` for a model with no geometry |
| `animations()` | The file's [animation clips](../3d/animation.md#playing-a-models-animations), which move the nodes of `root()` |
| `player()` | An `AnimationPlayer` for those clips. Register it with `scene.animate(player)` and call `play` |

The model's own units and up axis are kept. glTF is Y-up, which is what the scene uses; a file that is Z-up or in other
units needs `root().setScale(...)` and `root().setRotation(...)` to fit.

## What carries over

| Carries over | Does not |
|---|---|
| Geometry; normals (made smooth when the file has none); texture coordinates | Lights and cameras from the file |
| The base colour and its texture | A material's texture transforms and UV sets other than the first |
| For glTF: the **metallic-roughness** material with its **normal, metallic-roughness, occlusion and emissive** maps and factors; it loads as a [PBR material](scenes.md#material) | Cubic-spline animation keys (Assimp hands over only the values, so they are used as plain keys) |
| **Skeletons**: up to four bones a vertex and 256 per mesh, as a [skin](animation.md#skins) | |
| **Morph targets** (blend shapes), with their default weights, as [morph targets](animation.md#morph-targets) | |
| **Animations**: translation, rotation, scale and morph weights of nodes, as [clips](animation.md) | |
| Transparency; two-sided surfaces; alpha cut-outs | |
| Textures from beside the file, or embedded (`.glb` and embedded images) | |

Colours in glTF files are linear; the loader converts them, so a model looks as its author intended. OBJ and other formats
load with the classic material (`setSpecular`, `setShininess`) and their base colour map.

## Several copies

A loaded model costs GPU memory once, however many times you use it. `Node3D.copy()` copies the node tree but shares
the meshes, materials and textures:

```java nocompile
Model tree = Model.load(Path.of("assets/tree.glb"));
for (int i = 0; i < 20; i++) {
    Node3D copy = tree.root().copy();
    copy.setPosition(i * 3f, 0, 0);
    scene.add(copy);
}
```

Copies of one model that share meshes and materials are drawn together in one call per mesh. For a very large crowd of one mesh, [`Instances`](instancing.md) is lighter still. A copy of a skinned model follows its own copied skeleton, but only the original's `player()` animates the original: see [Animation](animation.md#playing-a-models-animations).

## Loading in the background

Loading reads files and decodes images but **never touches the GPU**, so it is safe on a worker thread. Meshes and
textures upload the first time the scene draws them. Use [`Scheduler.async`](../core/scheduler.md) and add the model
in the result callback, which runs on the render thread:

```java nocompile
Scheduler.async(() -> {                                 // worker thread
            try {
                return Model.load(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        })
        .onSuccess(model -> {                           // render thread
            scene.add(model.root());
            orbit.frame(model.bounds());
        })
        .onError(e -> log.error("Could not load {}", path, e));
```

For a loading screen with progress, see [`Loader`](../core/loading.md).

Uploading happens on the first frame that draws it, so a big model would stall that frame. To spread it over many
frames, [preload](scenes.md#preloading) the model before showing it:

```java nocompile
.onSuccess(model -> {
    model.root().setVisible(false);
    scene.add(model.root());
    scene.preload(model.root()).onDone(() -> model.root().setVisible(true));
})
```

## Gotchas

- **Missing textures** are not an error: the loader logs a warning (`uses the texture ..., which isn't beside it; drawing without it`) and the surface falls back to its colour. Check the log if a model looks plain.
- **A model with no geometry** has `bounds() == null`; `OrbitControls.frame(null)` does nothing.
- **Materials are shared inside a model**, so changing one changes every node that uses it. `Node3D.copy()` shares them
  too: give a copy its own material with `copy.setMaterial(new Material(...))`.
