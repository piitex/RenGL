# 3D

[Documentation](../README.md) › 3D

> Needs the `rengl-3d` [module](../modules.md).

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
| [Scenes](scenes.md) | `Scene3D`, `Node3D`, `Mesh`, `Material` (classic and PBR), `Light`, `Camera`, `TextureData`, `UiScreen` (a `Container` on a surface in the scene), `OrbitControls`, picking and the math types: a ready-made scene to put in a viewport |
| [Scene files](scene-files.md) | `Scene3DFile`: a scene built in the editor, saved as JSON and loaded by the game |
| [Lighting](lighting.md) | How lights add up, **blinking lights**, **shadows**, and the **skybox** |
| [Models](models.md) | `Model.load`: glTF, GLB, OBJ, FBX and Collada files, and what carries over |
| [Animation](animation.md) | Clips, `AnimationPlayer`, and skinned meshes that bend with a skeleton |
| [Physics](physics.md) | Rigid bodies: gravity, velocity, mass and inertia, forces, collisions with friction and bounce, sleeping, raycasts and trigger zones, mesh and hull colliders, joints, a character controller |
| [Instancing](instancing.md) | Thousands of copies of one mesh in a single draw |
| [Upscaling](upscaling.md) | Drawing a scene smaller and enlarging it: render scale, the built-in temporal upscaler, and the `Upscaler` interface for FSR, DLSS and XeSS modules |
| [Ray tracing](ray-tracing.md) | Shadows, reflections and ambient occlusion traced with rays through the scene, on Vulkan GPUs with ray queries, and the acceleration structures behind them |
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
  been drawn for a while (600 frames), so building a scene never needs the GPU. [`Scene3D.preload`](scenes.md#preloading) uploads them a few per frame ahead of time.

## Running the demos

The repo has runnable examples in `me.piitex.examples.demo`, which also show every call used here:

| Class | Shows |
|---|---|
| `Scene3DDemo` | A main viewport with a floor, textured cube, specular and glass spheres, a loaded model and an orbiting light; a second small viewport with its own scene; sliders and check boxes for the lights; a file dialog that loads a model |
| `Background3DDemo` | A cube, a sphere and a floor behind a control panel, with the orbit controls working through the UI |
| `HdrSkyDemo` | An HDR sky lighting chrome, gold, plastic and matte spheres, with a shadow-casting sun light aimed by `HdrTextureData.sunDirection()`; sliders for exposure, sky lighting, bloom and sun, a tone mapping switch, and "Load sky..." (or `-Drengl.demo.sky=file.hdr`) for any Radiance file. It makes its own sky when none is given |
| `LightsDemo` | A night city: a slider adds up to 1500 coloured point lights along the streets, a button cycles the [light grid](lighting.md#many-lights) between `AUTO`, `DIRECT` and `CLUSTERED`, a check box makes the 64 lamps [glow](lighting.md#glowing-surfaces) onto the street, and another turns on [occlusion culling](scenes.md#occlusion-culling) against the buildings, with the drawn, hidden and grid counts beside it |
| `WorldScreenDemo` | A control panel on a [screen in the world](scenes.md#an-interface-on-a-screen): drag its slider, open its dropdown and colour picker, tick its check box, all with the mouse through `scene.pick`, while a drag anywhere else orbits the camera |
| `FastBodiesDemo` | A 2 cm wall and a thin sheet, ball, plank or cube fired at it at up to 1000 m/s, with [continuous collision](physics.md#fast-bodies) switched on and off and a count of what got through; and six L-shaped bodies, cut into hulls by [`ConvexDecomposition`](physics.md#concave-moving-bodies), dropped on a staircase |
| `UpscalingDemo` | A yard of thin fences, tiled ground, spinning rotors and glowing orbs, flown through by a camera, for [upscaling and frame generation](upscaling.md#the-demo): pick the upscaler (smooth, built-in, FSR, DLSS), the render scale and the scene load, switch frame generation, freeze the motion, and read the frames drawn against the frames shown. It draws with Vulkan only |

All pick Vulkan on macOS and OpenGL elsewhere, and `-Drengl.demo.api=VULKAN` or `OPENGL` overrides that. The demos
have been run on Windows (both APIs) and started on Arch Linux.

## Limits at a glance

The scene layer is a forward renderer with classic and physically based materials (normal, metallic-roughness, occlusion
and emissive maps), shadows from several lights at once (directional with cascades, spot and point), a skybox that can light and be reflected, instancing,
skinned animation, morph targets (blend shapes), frustum culling, mipmapped and anisotropically filtered textures, 4x
anti-aliasing by default, [bloom, ambient occlusion, a colour grade and lens effects](lighting.md#bloom), and ray
[picking](scenes.md#picking). It has **no variable-width soft shadows, depth of field or motion blur**. It was written and tested on macOS on Apple silicon (Vulkan through MoltenVK and, in a harness,
OpenGL 3.3) and on Windows 11 with an NVIDIA GPU (native Vulkan and OpenGL); Linux has been compiled and run on Arch Linux but not checked as closely. The full list, with the reasons, is in
[Known limitations](../reference/known-limitations.md#rendering-and-3d). The code that checks it all is in `rengl-3d/src/test`: run
`./mvnw test`, which skips the GPU tests when the machine has no usable GPU.
