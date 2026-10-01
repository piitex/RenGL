# Scenes

[Documentation](../README.md) › [3D](README.md)

The scene layer (`me.piitex.engine.render3d.scene`) is a small ready-made renderer: a tree of nodes with meshes and
materials, a camera, some lights, and one forward pass that shades them. `Scene3D` is a
[`Viewport3D.Content`](viewport-3d.md#content), so a [`Viewport3D`](viewport-3d.md) or a
[`Background3D`](background-3d.md) shows it directly.

```text
Scene3D ──┬── root Node3D ──┬── Node3D (mesh + material)
          │                 └── Node3D ── Node3D ...   children move with their parent
          ├── Camera
          ├── lights (up to 8)
          └── background colour, ambient light, update handler
```

Everything is drawn with Lambert diffuse plus a Blinn-Phong highlight, textures, alpha blending, depth testing and
back-face culling. For models from files see [Models](models.md); for your own shaders see
[Custom rendering](custom-rendering.md).

## Scene3D

```java nocompile
Scene3D scene = new Scene3D();
scene.add(new Node3D("box", Mesh.cube(1f), new Material(Color.RED)));
scene.setBackground(new Color(0.08f, 0.1f, 0.17f, 1f));
scene.setAmbient(Color.WHITE, 0.15f);
```

A new scene already has a soft ambient light and one white directional light, so the first thing you add is visible.
Call `scene.lights().clear()` for different lighting.

| Method | Description |
|---|---|
| `root()` | The top of the node tree. Everything under it is drawn |
| `add(Node3D)` | Shorthand for `root().add(node)`. Returns the scene, so calls chain |
| `camera()` | The scene's [`Camera`](#camera) |
| `lights()` | The live `List<Light>`: add and remove directly. Only the first `Scene3D.MAX_LIGHTS` (8) are used |
| `setBackground(Color)` / `getBackground()` | The colour the picture is cleared to. Default dark blue-grey |
| `setAmbient(Color, float intensity)` | Light that reaches every surface from every side, so nothing is pitch black. Intensity `0` turns it off |
| `getAmbient()` / `getAmbientIntensity()` | Read them back |
| `setUpdateHandler(UpdateHandler)` | Runs once per frame before the scene is drawn, see below |
| `pick(x, y, width, height)` | The nearest [`Hit`](#picking) under a point of the picture, or `null` |
| `pick(Ray)` / `pickAll(Ray)` | The nearest hit along a ray, or all of them nearest first. See [Picking](#picking) |

### The update handler

```java nocompile
scene.setUpdateHandler((s, delta) -> cube.rotate(Vec3.UP, delta * 0.6f));
```

The handler is where scene logic goes: it runs on the render thread right before the scene draws, **once per frame**,
with `delta` in seconds.
Changing nodes, lights or the camera there is safe. The scene's data is plain Java, so it can be built on any thread,
but change it *while it is drawn* only from the render thread.

> **Several viewports:** a scene shown by more than one viewport still runs its update handler once per frame, before
> the first of them draws, so animation by `delta` runs at the right speed. The other viewports draw the updated scene.
> A viewport that is [not continuous](viewport-3d.md#drawing-only-when-needed) only runs the handler when it draws.

## Node3D

A thing in the scene: a position, rotation and scale relative to its parent, optionally a mesh drawn with a material,
and any number of children that move with it. A node with no mesh is a handle for grouping and moving things together.

```java nocompile
Node3D arm = new Node3D("arm");                 // a group
Node3D hand = new Node3D("hand", Mesh.sphere(0.2f, 24, 12), new Material(Color.ORANGE));
hand.setPosition(0, 0, 1.5f);
arm.add(hand);
scene.add(arm);
arm.rotate(Vec3.UP, 0.5f);                      // the hand swings with it
```

| Method | Description |
|---|---|
| `new Node3D(name)` / `new Node3D(name, mesh, material)` | A group, or a drawable node |
| `getName()` / `setName(String)` | Names are for `find`; they need not be unique |
| `add(Node3D)` / `remove(Node3D)` | Adding takes the child from its previous parent. A node can't be added under itself or its descendants |
| `getParent()` / `getChildren()` | Read-only view of the children |
| `setMesh` / `getMesh`, `setMaterial` / `getMaterial` | The drawable part. A node with a mesh and no material is drawn with a plain default material |
| `setVisible(boolean)` / `isVisible()` | A node that isn't visible hides itself and everything under it |
| `setPickable(boolean)` / `isPickable()` | Whether [picking](#picking) can hit this node's own mesh. On by default; its children are tested either way |
| `setPosition(Vec3)` / `setPosition(x, y, z)` | Position in the parent's space |
| `setRotation(Quat)` | Rotation in the parent's space |
| `rotate(Vec3 axis, float radians)` | Turns by that much on top of the current rotation |
| `setScale(Vec3)` / `setScale(float)` | Non-uniform or uniform |
| `setLocalMatrix(Mat4)` | Sets position, rotation and scale from a matrix; shear is dropped |
| `localMatrix()` / `worldMatrix()` | The transform relative to the parent, and in the scene |
| `find(String name)` | The first node with that name in this node and its descendants, or `null` |
| `traverse(Consumer<Node3D>)` | Visits this node and everything under it |
| `bounds()` | The box around this node's meshes and its descendants', in scene space; `null` when there is no mesh |
| `copy()` | A copy of the node and its descendants. Meshes, materials and textures are shared, so copying a loaded model costs no GPU memory |

Nodes are not thread-safe. Change them from the render thread, or while the scene isn't drawing.

## Mesh

Triangle geometry held in ordinary memory: positions, normals and texture coordinates with 32-bit indices. A mesh knows
nothing about the GPU. The scene uploads it the first time it draws it and keeps it there while it is in use, so one mesh
can be shared by any number of nodes, viewports and windows, and can be built on any thread. A mesh is immutable.

```java nocompile
Mesh cube = Mesh.cube(1.2f);              // size; flat faces, the whole texture on each
Mesh ball = Mesh.sphere(0.75f, 48, 24);   // radius, segments around, rings pole to pole
Mesh floor = Mesh.plane(14, 14);          // width (X), depth (Z); on the XZ plane facing +Y
```

| Primitive | Notes |
|---|---|
| `Mesh.cube(size)` | Centred on the origin |
| `Mesh.sphere(radius, segments, rings)` | Centred on the origin; the texture wraps like a world map. At least 3 segments and 2 rings |
| `Mesh.plane(width, depth)` | Centred on the origin; the texture's top edge is at -Z |

Your own geometry:

```java nocompile
Mesh quad = new Mesh(
        new float[]{-1, 0, 0,  1, 0, 0,  1, 2, 0,  -1, 2, 0},   // x, y, z per vertex
        null,                                                    // normals: null computes smooth ones
        new float[]{0, 1,  1, 1,  1, 0,  0, 0},                  // u, v per vertex, or null
        new int[]{0, 1, 2,  0, 2, 3});                           // three indices per triangle
```

The constructor checks its arrays and throws `IllegalArgumentException` for a mismatch or an index out of range.
`bounds()`, `vertexCount()` and `indexCount()` read the result. Triangles that are counter-clockwise seen from outside
are front faces, and texture coordinates start at the top-left of the image.

## Material

How a surface looks: a colour, an optional texture, and how shiny it is. The final colour is the **texture times the
colour**, so a white colour shows the texture as it is. Every setter returns the material, so calls chain.

```java nocompile
Material blue = new Material(new Color(0.25f, 0.6f, 0.95f, 1f)).setSpecular(0.5f);
Material floorMat = new Material(TextureData.checker(256, 14, new float[]{0.45f, 0.45f, 0.5f}, new float[]{0.22f, 0.23f, 0.28f}))
        .setSpecular(0.05f);
Material glass = new Material(new Color(0.6f, 0.8f, 1f, 0.35f)).setBlend(true).setDoubleSided(true);
```

| Setter | Default | Description |
|---|---|---|
| `new Material()` / `new Material(Color)` / `new Material(TextureData)` | white | The colour, or the texture to start from |
| `setColor(Color)` / `setColor(r, g, b, a)` | white | Colours are written like the UI's, what you see on screen |
| `setTexture(TextureData)` | none | See [TextureData](#texturedata) |
| `setSpecular(float)` | `0.3` | Highlight strength, `0` matte to `1` |
| `setShininess(float)` | `32` | Highlight tightness: a few for a broad glow, a few hundred for a pin-point shine |
| `setUnlit(boolean)` | `false` | Skips lighting; the surface is drawn in its plain colour |
| `setDoubleSided(boolean)` | `false` | Draws the back of triangles too, lit from the side that faces the camera. Off skips them |
| `setBlend(boolean)` | `false` | Mixes the surface with what is behind it by its alpha. Blended nodes are drawn after the solid ones, farthest first, **ordered by their node's position**, so overlapping see-through surfaces can still look wrong |
| `setAlphaCutoff(float)` | `0` | Pixels whose alpha (texture times colour) is below this are not drawn at all: a cut-out for leaves and fences that needs no sorting. `0` draws everything |

Materials can be shared by any number of nodes.

## TextureData

A picture for a material, held in ordinary memory as RGBA8, top row first. Like `Mesh` it is uploaded when first drawn,
so it can be loaded on any thread and shared freely. Colours are sRGB, as in any image file.

| Method | Description |
|---|---|
| `TextureData.load(Path)` | PNG, JPEG, BMP, TGA or GIF. Throws `IOException` if it can't be decoded |
| `TextureData.decode(byte[] encoded, String name)` | The same from memory, for an image bundled in the jar |
| `TextureData.solid(r, g, b, a)` | One colour, `0` to `1` per channel |
| `TextureData.checker(size, cells, colorA, colorB)` | A checkerboard, handy for seeing how a mesh is mapped. Colours are `{r, g, b}` |
| `new TextureData(width, height, byte[] rgba)` | From raw pixels, copied |
| `width()` / `height()` | Its size |

Textures are sampled with linear filtering and repeat wrapping. The scene makes **mipmaps** for them when it uploads
them (smaller copies of the picture that are blended between by distance), so a detailed texture stays smooth on a
floor that runs off into the distance instead of shimmering. That costs a third more GPU memory per texture.

## Light

A scene uses its first 8 lights, either directional or point. Both are made with factory methods, and the setters
return the light.

```java nocompile
Light sun = Light.directional(new Vec3(-0.4f, -1f, -0.6f), Color.WHITE, 0.9f);   // the way it travels
Light lamp = Light.point(new Vec3(-2.5f, 2.2f, 2f), new Color(1f, 0.65f, 0.3f, 1f), 1.5f, 9f); // position, colour, intensity, range
scene.lights().clear();
scene.lights().add(sun);
scene.lights().add(lamp);
```

| Method | Description |
|---|---|
| `Light.directional(direction, color, intensity)` | Like the sun. `direction` is the way the light *travels*, so `(0, -1, 0)` shines straight down |
| `Light.point(position, color, intensity, range)` | Fades to nothing at `range`, the distance where it is gone |
| `getType()` | `Light.Type.DIRECTIONAL` or `POINT` |
| `setVector(Vec3)` / `getVector()` | The direction of a directional light, or the position of a point light. Move a point light every frame from the update handler |
| `setColor(Color)`, `setIntensity(float)`, `setRange(float)` | Change it later |

There are no shadows yet.

## Camera

A perspective camera, owned by the scene (`scene.camera()`). The viewport sets the aspect ratio from its own size.

```java nocompile
scene.camera().lookAt(new Vec3(0, 2, 6), new Vec3(0, 0.5f, 0)).setFovYDegrees(50);
```

| Method | Default | Description |
|---|---|---|
| `lookAt(position, target)` | position `(0, 1.5, 4)`, target the origin | Moves the camera and points it |
| `setPosition(Vec3)`, `setTarget(Vec3)`, `setUp(Vec3)` | up `+Y` | One at a time |
| `setFovYDegrees(float)` | `60` | The vertical field of view, kept between 1 and 170 |
| `setClip(near, far)` | `0.1`, `200` | What the camera sees lies between these distances. Needs `0 < near < far`. Keep the ratio modest: depth precision goes with it |
| `view()` / `projection(aspect)` | | The matrices, if you are doing your own drawing |
| `screenRay(x, y, width, height)` | | The `Ray` from the camera through a point of the picture. See [Picking](#picking) |

## Orbit controls

`OrbitControls` is an [`InputListener`](viewport-3d.md#input) that turns a camera around a point: **drag to orbit,
scroll to zoom**.

```java nocompile
OrbitControls orbit = new OrbitControls(scene.camera());     // starts from the camera's position and target
orbit.setTarget(new Vec3(0, 0.7f, 0)).setDistance(7f).setAngles(0.5f, 0.35f);
view.setInputListener(orbit);
```

| Method | Description |
|---|---|
| `setTarget(Vec3)` | The point the camera circles |
| `setDistance(float)` | Distance from the target, within the limits |
| `setAngles(yaw, pitch)` | In radians. Yaw `0` looks from +Z, pitch `0` from the horizon, positive pitch from above. Pitch is kept below 89 degrees |
| `frame(Bounds)` | Points at the middle of the box from far enough to see all of it, and moves the clip planes to suit its size. `orbit.frame(node.bounds())` is the usual way to show a loaded model |
| `setRotateSpeed(float)` | Radians per point dragged. Default `0.01` |
| `setZoomSpeed(float)` | Each wheel step of 1 scales the distance by `e^-zoomSpeed`. Default `0.1` |
| `setDistanceLimits(min, max)` | Default `0.05` to `1000` |

The controls **own the camera's position** once attached: they move it whenever the mouse does, so a position set
directly on the camera is overwritten then. Steer from code with the setters above. The camera's up must stay +Y.

For another control scheme, write your own `InputListener` and move the camera in it (a first-person camera is a
few lines of `Camera.lookAt`).

## Picking

To find what is under the mouse, cast a ray from the camera through the picture and see what it meets.
`Scene3D.pick` does both, given a position and the picture's size, which for a viewport are the mouse position it hands
its [listener](viewport-3d.md#input) (points from the top-left corner) and its own size:

```java
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.scene.Hit;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.OrbitControls;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.ui.color.Color;

public class ClickToSelect {
    static Viewport3D build(Scene3D scene) {
        Node3D cube = new Node3D("cube", Mesh.cube(1f), new Material(Color.CORNFLOWER_BLUE));
        scene.add(cube);

        Viewport3D view = new Viewport3D(640, 360);
        view.setContent(scene);

        OrbitControls orbit = new OrbitControls(scene.camera());
        view.setInputListener(new Viewport3D.InputListener() {
            @Override
            public void dragged(double x, double y, double dx, double dy) {
                orbit.dragged(x, y, dx, dy);
            }

            @Override
            public void scrolled(double dx, double dy, double x, double y) {
                orbit.scrolled(dx, dy, x, y);
            }

            @Override
            public void clicked(double x, double y) {
                Hit hit = scene.pick(x, y, view.getWidth(), view.getHeight());
                if (hit != null) {
                    hit.node().getMaterial().setColor(Color.ORANGE);   // highlight what was clicked
                    System.out.println("Clicked " + hit.node().getName() + " at " + hit.point()
                            + ", " + hit.distance() + " away");
                }
            }
        });
        return view;
    }
}
```

Use `clicked` rather than `dragStarted` for this: a press can be the start of an orbit, and `clicked` only fires when
the mouse hardly moved between press and release. For hover, call `pick` from `pointerMoved`.

A [`Hit`](#hit) holds the node, how far away it is, and where and which way the surface faces. `pick(x, y, w, h)` only
finds what the camera can see (between its near and far planes); `pick(Ray)` has no limit, and `pickAll(Ray)` lists
everything the ray crosses, one hit per node, nearest first, for picking through glass or listing what is stacked.

| Method | Description |
|---|---|
| `scene.pick(x, y, width, height)` | The nearest hit under a point of the picture, or `null`. Uses the scene's camera |
| `scene.pick(Ray)` | The nearest hit along a ray with no distance limit, or `null` |
| `scene.pickAll(Ray)` | Every node the ray hits, nearest first |
| `camera.screenRay(x, y, width, height)` | The ray itself, if you want to test it against something else |
| `node.setPickable(false)` | Takes a node's own mesh out of picking, so clicks go through a floor, a glass dome or a decoration |

### Hit

| Field | Description |
|---|---|
| `node()` | The node whose mesh was hit |
| `distance()` | How far along the ray. In scene units for a ray from `screenRay` |
| `point()` | The hit position in scene space |
| `normal()` | The unit normal of the triangle in scene space, on the side the ray came from. It is the flat face normal, not the smoothed one lighting uses |
| `triangle()` | The triangle's index in the mesh, three entries of `Mesh.indices()` each |

### How it decides

- Only **visible** nodes with a mesh and `isPickable()` are tested. A node under an invisible parent is skipped.
- Picking matches what is drawn: a triangle facing away from the ray is skipped, like back-face culling, unless the
  node's material is [double-sided](#material). From inside a closed shape you hit nothing.
- A mesh's box in scene space is tested first, then its triangles, so the cost grows with the triangles of the meshes
  the ray passes through. A click or a hover is fine; thousands of rays per frame on a million-triangle model are not.
  There is no spatial index yet.
- It reads the mesh in memory, not the GPU, so it works on any thread while the scene isn't changing, and it hits
  exactly the triangles that are drawn, scaled, rotated and moved by their nodes (a non-uniform scale is fine).
- Alpha is ignored: a see-through material is hit like a solid one. Give it `setPickable(false)` or use `pickAll` to
  look behind it.

## Math types

In `me.piitex.engine.render3d.math`. They are small immutable value classes; every operation returns a new one.

| Type | What it is |
|---|---|
| `Vec3` | A record `(x, y, z)` with `add`, `sub`, `mul(float)`, `negate`, `dot`, `cross`, `length`, `normalized`, `lerp`, and the constants `ZERO`, `ONE`, `UP`, `X`, `Z` |
| `Quat` | A unit quaternion: `fromAxisAngle(axis, radians)`, `fromEuler(pitch, yaw, roll)`, `mul`, `rotate(Vec3)`, `normalized`, `toMatrix`, and `IDENTITY`. `a.mul(b)` applies `b` first, then `a` |
| `Mat4` | A column-major 4x4: `identity`, `translation`, `scale`, `compose(position, rotation, scale)`, `perspective`, `lookAt`, `mul`, `inverse` (null when it can't be undone), `transformPoint`, `transformDirection`, `normalMatrix`, `decompose`, `toArray`, `put(ByteBuffer)` |
| `Bounds` | An axis-aligned box `(min, max)` with `center`, `radius`, `union`, and `transform(Mat4)` |
| `Ray` | A record `(origin, direction)` with `at(distance)` and `distanceTo(Bounds)` (-1 for a miss). `Ray.of(origin, direction)` normalizes the direction, so distances are in scene units |

Projection matrices are Vulkan-style on both APIs (+Y up in clip space, depth `0` to `1`); see
[Custom rendering](custom-rendering.md#conventions).

## Under the hood

A scene makes one `SceneRenderer` per GPU device it is shown on, shared by that scene's viewports. It draws all opaque nodes, then the blended
ones far to near, with one dynamic uniform buffer per drawn node. GPU meshes and textures are cached and freed after
600 frames without being drawn. You never touch it directly; it is how the scene follows its `Content` lifecycle:
`init` makes the renderer, `render` runs the update handler (once per frame) and draws, `dispose` frees it when the last viewport lets go.

## Gotchas

- **A node needs a mesh to draw anything.** A node with no mesh is only a group.
- **Mirrored nodes work.** A negative scale (a model exported with a flipped axis) turns the triangles inside out, and the scene turns the culling around for it, so the model looks as it should.
- **Loading and building never need the GPU**, so it is safe to build a big scene on a worker thread with
  [`Scheduler.async`](../core/scheduler.md) and add it from the result callback. The first frame that draws it will pause
  while it uploads (see [Known limitations](../reference/known-limitations.md#rendering-and-3d)).
- **Colours are not linear.** Pick them as you would for the UI. Lights multiply in linear light internally.
- **A long loop of draws is the ceiling:** about a thousand nodes per frame on Vulkan, and there is no instancing yet.
  Merge static geometry into one `Mesh` where you can.
