# 3D

[Documentation](../README.md) › 3D

An **optional** layer for showing 3D inside a RenGL program, in `me.piitex.engine.render3d`. The UI stays 2D and
Skia-drawn. The 3D layer renders into an offscreen picture with OpenGL or Vulkan, and the UI draws that picture like
an image, so 3D can sit in a panel, behind the whole window, or under any UI you put over it.

**It is never forced on you.** Basic apps and 2D games do not touch it: there is no 3D type in `Window`, `Element` or
`Engine`, nothing starts until a program asks, and a window with no 3D shown pays nothing. The GPU device is made the
first time something draws 3D, and closed with the window.

## What you need

- A window running on **OpenGL or Vulkan**. On macOS that means
  `new WindowOptions(...).setRenderer(RenderAPI.VULKAN)`: Metal can't share its GPU with the 3D layer, so a 3D element
  on a Metal window logs one warning and draws nothing. Vulkan also needs a Skiko with Vulkan support, see
  [Rendering APIs](../core/rendering-apis.md#getting-vulkan-working).
- Nothing else. The shader compiler, the model loader and the memory allocator are LWJGL libraries the project already depends on.

## The pieces

| Page | What it covers |
|---|---|
| [Viewport3D](viewport-3d.md) | The element that shows 3D in a rectangle of the UI: its `Content`, input, resolution and lifetime |
| [Background3D](background-3d.md) | 3D behind the whole window, with the UI over it: `window.setBackground(...)` |
| [Scenes](scenes.md) | `Scene3D`, `Node3D`, `Mesh`, `Material` (classic and PBR), `Light`, `Camera`, `TextureData`, `OrbitControls`, picking and the math types: a ready-made scene to put in a viewport |
| [Lighting](lighting.md) | How lights add up, **shadows**, and the **skybox** |
| [Models](models.md) | `Model.load`: glTF, GLB, OBJ, FBX and Collada files, and what carries over |
| [Animation](animation.md) | Clips, `AnimationPlayer`, and skinned meshes that bend with a skeleton |
| [Physics](physics.md) | Rigid bodies: gravity, velocity, mass and inertia, forces, collisions with friction and bounce, sleeping, raycasts and trigger zones |
| [Instancing](instancing.md) | Thousands of copies of one mesh in a single draw |
| [Custom rendering](custom-rendering.md) | Drawing your own 3D with `GpuDevice`, pipelines and one GLSL shader that runs on both APIs |

Status, known problems and plans are tracked in the [3D status document](../3d-status.md).

## A first scene

A spinning cube in a panel next to a button. `Scene3D` is a `Viewport3D.Content`, so a viewport shows it directly,
and `OrbitControls` lets the user drag to turn the camera and scroll to zoom.

```java
import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.OrbitControls;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;

public class FirstCube {
    public static void main(String[] args) {
        // Vulkan on macOS; OpenGL is the default elsewhere. See "What you need".
        Window window = new Window(new WindowOptions("First cube")
                .setDimensions(900, 520)
                .setRenderer(RenderAPI.VULKAN));

        Scene3D scene = new Scene3D();
        scene.camera().lookAt(new Vec3(3, 2.5f, 4), new Vec3(0, 0.75f, 0)); // from above and the side
        Node3D cube = new Node3D("cube", Mesh.cube(1.5f), new Material(Color.CORNFLOWER_BLUE));
        cube.setPosition(0, 0.75f, 0);
        scene.add(cube);
        scene.setUpdateHandler((s, delta) -> cube.rotate(Vec3.UP, delta)); // one radian a second

        Viewport3D view = new Viewport3D(640, 480);
        view.setPosition(20, 20);
        view.setContent(scene);
        view.setInputListener(new OrbitControls(scene.camera()));

        Container root = new Container(900, 520);
        window.addContainer(root);
        root.addElement(view);

        ButtonOverlay close = new ButtonOverlay("Close", 120, 32);
        close.setPosition(700, 20);
        close.onAction(window::close);
        root.addElement(close);

        new Engine().start(window);
    }
}
```

Run it with `-XstartOnFirstThread --enable-native-access=ALL-UNNAMED` on macOS.

The same scene can go behind the whole window instead, with one line, no matter what UI is over it:

```java nocompile
window.setBackground(new Background3D(window, scene));
```

## Conventions

- **Coordinates are right-handed with +Y up.** The camera looks down -Z by default. Units are whatever you choose;
  the lighting only cares about the `range` of point lights.
- **Triangles that are counter-clockwise seen from outside are front faces.** Back faces are skipped unless a
  material is [double-sided](scenes.md#material).
- **Colours are written as in the UI**, what you see on screen. The scene converts to linear light to shade and back.
- **Texture images and texture coordinates have their origin at the top-left**, like image files.
- **Everything runs on the render thread.** The data classes (`Mesh`, `Material`, `TextureData`, `Node3D`, `Model`) hold
  plain Java data and can be built on any thread, but changing a scene that is being drawn is a render-thread job, for
  example from its [update handler](scenes.md#the-update-handler) or from [`Scheduler.runLater`](../core/scheduler.md).
- **GPU work is lazy.** Meshes and textures upload the first time they are drawn, and are freed when they have not
  been drawn for a while (600 frames), so building a scene never needs the GPU.

## Running the demos

The repo has two runnable examples in `me.piitex.examples.demo`, which also show every call used here:

| Class | Shows |
|---|---|
| `Scene3DDemo` | A main viewport with a floor, textured cube, specular and glass spheres, a loaded model and an orbiting light; a second small viewport with its own scene; sliders and check boxes for the lights; a file dialog that loads a model |
| `Background3DDemo` | A cube, a sphere and a floor behind a control panel, with the orbit controls working through the UI |

Both pick Vulkan on macOS and OpenGL elsewhere, and `-Drengl.demo.api=VULKAN` or `OPENGL` overrides that. The OpenGL
path of the demos has not been run on Windows or Linux yet.

## Limits at a glance

The scene layer is a forward renderer with classic and physically based materials (normal, metallic-roughness, occlusion
and emissive maps), shadows from several lights at once (directional with cascades, spot and point), a skybox that can light and be reflected, instancing,
skinned animation, morph targets (blend shapes), frustum culling, mipmapped and anisotropically filtered textures, 4x
anti-aliasing by default, and ray [picking](scenes.md#picking). It has **no variable-width soft shadows and no
post-processing**. It was written and tested on one machine so far: macOS on Apple silicon, with Vulkan
(MoltenVK) and, in a harness, OpenGL 3.3; Windows and Linux are untested. The full list, with the reasons, is in
[Known limitations](../reference/known-limitations.md#rendering-and-3d). The code that checks it all is in `src/test`: run
`./mvnw test`, which skips the GPU tests when the machine has no usable GPU.
