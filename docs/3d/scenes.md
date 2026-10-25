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
          ├── lights (up to 64 shaded at once), up to 8 of them can cast shadows
          ├── skybox, background colour, ambient light
          └── animation players, update handler
```

Everything is drawn by one forward renderer: classic (Lambert and Blinn-Phong) or [physically based](#material)
shading with normal, metallic-roughness, occlusion and emissive maps, [shadows](lighting.md#shadows) from directional, spot
and point lights (several at once, with cascades for the sun), a [skybox](lighting.md#skybox) that can [light the scene and be reflected](lighting.md#light-from-the-sky),
mipmapped and anisotropically filtered textures, alpha blending, [instanced](instancing.md) drawing, [skinned meshes and
animation](animation.md), depth testing, back-face culling and [frustum culling](#frustum-culling). For models from
files see [Models](models.md); for your own shaders see [Custom rendering](custom-rendering.md).

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
| `lights()` | The live `List<Light>`: add and remove directly. Up to `Scene3D.MAX_LIGHTS` (64) are shaded in a frame; with more, the ones that matter are chosen, see [Lighting](lighting.md#gotchas) |
| `setBackground(Color)` / `getBackground()` | The colour the picture is cleared to. Default dark blue-grey |
| `setAmbient(Color, float intensity)` | Light that reaches every surface from every side, so nothing is pitch black. Intensity `0` turns it off |
| `getAmbient()` / `getAmbientIntensity()` | Read them back |
| `setSkybox(Skybox)` / `getSkybox()` | The sky drawn behind everything, see [Skybox](lighting.md#skybox) |
| `setFog(Color, float density)` / `setFog(Color, density, heightFalloff)` | Fades distant things into a colour, see [Fog](lighting.md#fog). Off by default |
| `setShadowMapSize(int)`, `setShadowDistance(float)`, `setShadowBias(float)`, `setShadowSoftness(float)` | The default quality of [shadows](lighting.md#shadows), for lights that don't set their own with `Light.setShadows` |
| `setShadowAtlasSize(int)` | The largest width and height of the picture all shadows share (`4096`), see [Which lights get shadows](lighting.md#which-lights-get-shadows) |
| `setToneMapping(ToneMapping)` / `setExposure(float)` | Bends light brighter than white into the screen's range (`NONE`, `REINHARD`, `ACES`), after scaling it by the exposure; see [Tone mapping](lighting.md#tone-mapping-and-exposure). `NONE` and `1` by default, which change nothing |
| `setEnvironmentLighting(float)` / `getEnvironmentLighting()` | Light and reflections from the skybox, `0` (off, the default) or more, see [Light from the sky](lighting.md#light-from-the-sky) |
| `getStats()` | A `Scene3D.Stats(nodes, culled, instances, drawCalls, shadowViews, shadowAtlasWidth, shadowAtlasHeight)` for the last time the scene was drawn, see [Frustum culling](#frustum-culling) and [Which lights get shadows](lighting.md#which-lights-get-shadows) |
| `animate(AnimationPlayer)` / `stopAnimating(AnimationPlayer)` | Registers a player that advances once per frame, see [Animation](animation.md) |
| `animate(LightAnimation)` / `stopAnimating(LightAnimation)` | Registers a blinking-light animation that advances once per frame, see [Blinking lights](lighting.md#blinking-lights) |
| `setUpdateHandler(UpdateHandler)` | Runs once per frame before the scene is drawn, see below |
| `preload(Node3D)` / `preload(Skybox)` | Uploads meshes and textures ahead of the frame that shows them, see [Preloading](#preloading) |
| `setPreloadBudget(float)` / `getPreloadBudget()` | Milliseconds per frame spent on preloading, 4 by default |
| `pick(x, y, width, height)` | The nearest [`Hit`](#picking) under a point of the picture, or `null` |
| `pick(Ray)` / `pickAll(Ray)` | The nearest hit along a ray, or all of them nearest first. See [Picking](#picking) |

### Preloading

A mesh or texture goes to the GPU the first time a frame draws it, so a big model added to a running scene stalls
that frame while its buffers and textures upload. `preload` does the same work a little each frame instead:

```java nocompile
model.root().setVisible(false);          // hidden nodes are uploaded too
scene.add(model.root());
Preload loading = scene.preload(model.root());
loading.onDone(() -> model.root().setVisible(true));
bar.setProgress(loading.getProgress());  // 0 to 1, from the render thread
```

Each frame uploads items until the budget (`setPreloadBudget`, 4 ms) is spent, and always at least one, so a single
huge texture still takes its whole time in one frame. A mesh or texture shared by several nodes counts once.
`preload(Skybox)` does the same for a sky's pictures, and also works out its light when
[environment lighting](lighting.md#light-from-the-sky) is on. A [viewport that is not continuous](viewport-3d.md#drawing-only-when-needed)
keeps drawing frames until the preload is done.

| `Preload` | Description |
|---|---|
| `isDone()` | True once everything is uploaded (also true when there was nothing to upload) |
| `getProgress()` / `getTotal()` | A fraction from 0 to 1, and the number of meshes and textures |
| `onDone(Runnable)` | Runs on the render thread when it finishes, or at once if it already has |

Shaders are a separate cost: a feature the scene has not used yet, such as the first shadow or a new custom filter,
compiles its program on the frame that first needs it. Pipelines for the plain scene are built when it starts.

### The update handler

```java nocompile
scene.setUpdateHandler((s, delta) -> cube.rotate(Vec3.UP, delta * 0.6f));
```

The handler is where scene logic goes: it runs on the render thread right before the scene draws, **once per frame**,
with `delta` in seconds, after the [animation players](animation.md) have advanced.
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
| `setCastShadow(boolean)` / `setReceiveShadow(boolean)` | Whether the mesh throws a [shadow](lighting.md#shadows) and whether shadows fall on it. Both on by default |
| `setInstances(Instances)` / `getInstances()` | Draws the mesh once per [instance](instancing.md), in one call |
| `setSkin(Skin)` / `getSkin()` | Makes the mesh follow the joints of a [skin](animation.md) |
| `setMorphWeights(float...)` / `setMorphWeight(i, w)` / `getMorphWeights()` | Mixes the mesh's [blend shapes](animation.md#morph-targets) |
| `setFrustumCulled(boolean)` | Whether the node is skipped when outside the view (on by default), see [Frustum culling](#frustum-culling) |
| `setSortTriangles(boolean)` | Draws the triangles of a see-through mesh far to near each frame, for shapes that fold over themselves. Off by default; see [Material](#material) |
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
can be shared by any number of nodes, viewports and windows, and can be built on any thread. A mesh's geometry is
immutable; what moves it is a node's transform, a skin or morph weights.

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

**Lines.** `Mesh.lines(positions, segments)` makes a mesh of line segments, two indices each, drawn one pixel wide: an
outline, a path, a grid, a debug shape (the [collider view](physics.md#seeing-the-colliders) is made of them).

```java nocompile
Mesh cross = Mesh.lines(new float[]{-1, 0, 0,  1, 0, 0,  0, -1, 0,  0, 1, 0}, new int[]{0, 1, 2, 3});
scene.add(new Node3D("cross", cross, new Material(Color.RED).setUnlit(true)));
```

A node with a line mesh is drawn unlit in its material's colour whatever the material says, casts no shadow, and can't be
picked or turned into a physics shape. `mesh.isLines()` tells the two kinds apart.

**A second set of texture coordinates** (`setUvs2`) is for a texture laid out apart from the colour texture, usually a
baked light or occlusion map: give the textures that read it `Material.setTexCoordSet(slot, 1)`.

A mesh can also carry [morph targets](animation.md#morph-targets) (`setMorphTargets`) and [skinning](animation.md#skins)
(`setSkinning`). Set skinning before the mesh is first drawn, as the scene reads it once when it uploads the mesh; morph
targets can be added at any time.

The constructor checks its arrays and throws `IllegalArgumentException` for a mismatch or an index out of range.
`bounds()`, `vertexCount()` and `indexCount()` read the result. Triangles that are counter-clockwise seen from outside
are front faces, and texture coordinates start at the top-left of the image.

## Material

How a surface looks: a colour, an optional texture, and how it reacts to light. The final colour is the **texture times
the colour**, so a white colour shows the texture as it is. Every setter returns the material, so calls chain.

There are two ways to shade a surface:

- **Classic** (the default) is a Lambert diffuse term plus a Blinn-Phong highlight, controlled by `setSpecular` and
  `setShininess`. It is simple and cheap, and good for flat-coloured shapes.
- **Physically based (PBR)** is the metallic-roughness model glTF uses: a surface has a *metallic* amount and a
  *roughness*, and the maps below can vary them across it. It looks right under any light, and is what glTF models load
  as. Setting any PBR property (`setMetallic`, `setRoughness`, a map, or an emissive colour) switches the material to
  PBR; `setPbr(true)` switches to it with the defaults, `setPbr(false)` goes back.

```java nocompile
Material blue = new Material(new Color(0.25f, 0.6f, 0.95f, 1f)).setSpecular(0.5f);                // classic
Material gold = new Material(new Color(1f, 0.78f, 0.34f, 1f)).setMetallic(1f).setRoughness(0.25f);   // PBR
Material brick = new Material(TextureData.load(Path.of("brick_color.png")))
        .setNormalTexture(TextureData.load(Path.of("brick_normal.png")))
        .setRoughness(0.9f);
Material lamp = new Material(Color.BLACK).setEmissive(Color.WHITE, 3f);                           // glows
Material glass = new Material(new Color(0.6f, 0.8f, 1f, 0.35f)).setBlend(true).setDoubleSided(true);
```

| Setter | Default | Description |
|---|---|---|
| `new Material()` / `new Material(Color)` / `new Material(TextureData)` | white | The colour, or the texture to start from |
| `setColor(Color)` / `setColor(r, g, b, a)` | white | Colours are written like the UI's, what you see on screen |
| `setTexture(TextureData)` | none | The base colour picture, see [TextureData](#texturedata) |
| `setSpecular(float)` | `0.3` | Classic: highlight strength, `0` matte to `1` |
| `setShininess(float)` | `32` | Classic: highlight tightness, a few for a broad glow, a few hundred for a pin-point shine |
| `setUnlit(boolean)` | `false` | Skips lighting; the surface is drawn in its plain colour |
| `setDoubleSided(boolean)` | `false` | Draws the back of triangles too, lit from the side that faces the camera. Off skips them |
| `setAdditive(boolean)` | `false` | Adds the colour, times its alpha, to what is behind instead of mixing: for light-like things (flames, sparks, glows). Turns blending on; drawn with the blended nodes, casts no shadow, and fades out in fog |
| `setBlend(boolean)` | `false` | Mixes the surface with what is behind it by its alpha. Blended nodes are drawn after the solid ones, farthest first, **ordered by the middle of each one's mesh** (and each copy of an [instanced](instancing.md) node on its own), so overlapping see-through nodes can still look wrong where they interpenetrate. A single mesh whose own triangles fold over each other is drawn right with `node.setSortTriangles(true)`, which sorts them every frame on the CPU. They test against the depth buffer but don't write to it. Blended surfaces do not cast shadows |
| `setUvTransform(offsetU, offsetV, scaleU, scaleV, rotation)` / `setUvScale(float)` | none | Moves, turns and stretches all of the material's textures over the surface (glTF's `KHR_texture_transform`): scale, then turn by the rotation in radians, then offset. A scale above 1 tiles the texture; changing the offset every frame scrolls it (water, a screen, a conveyor belt) |
| `setTexCoordSet(Slot, 0 or 1)` | `0` | Makes one texture (`BASE_COLOR`, `NORMAL`, `METALLIC_ROUGHNESS`, `OCCLUSION` or `EMISSIVE`) read the mesh's second set of texture coordinates (`Mesh.setUvs2`), for a baked light or occlusion map laid out apart from the colour texture. A mesh without a second set gives set 1 the first |
| `setAlphaCutoff(float)` | `0` | Pixels whose alpha (texture times colour) is below this are not drawn at all: a cut-out for leaves and fences that needs no sorting. `0` draws everything. Cut-outs cast shadows with their holes |
| `setPbr(boolean)` | `false` | Physically based shading, see above |
| `setMetallic(float)` | `0` | PBR: `0` for plastic, wood and stone, `1` for bare metal, which tints its reflections with the colour. Multiplied with the blue channel of the metallic-roughness map |
| `setRoughness(float)` | `0.5` | PBR: `0` for a mirror-sharp highlight to `1` for fully matte. Multiplied with the green channel of the metallic-roughness map |
| `setNormalTexture(TextureData)` | none | PBR: a tangent-space normal map, for bumps and dents on a flat surface. Needs the mesh's texture coordinates. Green is up (the glTF and OpenGL convention) |
| `setNormalScale(float)` | `1` | How strongly the normal map bends the surface, `0` for flat |
| `setMetallicRoughnessTexture(TextureData)` | none | PBR: roughness in green, metallic in blue (the glTF layout) |
| `setOcclusionTexture(TextureData)` | none | PBR: baked shadowing in creases, in the red channel. It dims the ambient light |
| `setEmissive(Color, strength)` / `setEmissive(r, g, b)` | black | PBR: light the surface gives off whatever the lights do. The `Color` form takes a screen colour; the `float` form is linear light, like glTF |
| `setEmissiveTexture(TextureData)` | none | PBR: a picture of that glow, times the emissive colour |

Emissive surfaces are bright but do not light their surroundings. The normal, metallic-roughness and occlusion maps hold
data, not colours, and are read as they are; the base colour and emissive pictures are sRGB colours. Materials can be
shared by any number of nodes.

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
| `update(byte[] rgba)` | Replaces every pixel with a copy of the array, which must be `width * height * 4` bytes. See [animated screens](#animated-screens) |
| `edit(Consumer<byte[]>)` | Changes the pixels in place, with no array made per frame. The consumer gets the live RGBA8 array and must not keep it |
| `version()` | How many times it has been changed with `update` or `edit`, `0` for a texture never changed |

Textures are sampled with linear filtering and repeat wrapping. The scene makes **mipmaps** for them when it uploads
them (smaller copies of the picture that are blended between by distance), so a detailed texture stays smooth on a
floor that runs off into the distance instead of shimmering. That costs a third more GPU memory per texture. They are also
**anisotropically filtered** where the GPU can (up to 8x), which keeps a floor seen at a slant sharp instead of blurring it;
the system property `rengl.3d.anisotropy` changes the level, and `1` turns it off.

### Animated screens

A `TextureData` can change while it is on screen. Change it with `update` or `edit`, and every scene that draws it sends
the new picture to the GPU before its next frame, mipmaps included. A computer screen is one texture on a material, an
emissive one so it glows with [bloom](lighting.md) and shows in the dark, rewritten whenever the picture should change:

```java nocompile
TextureData screen = new TextureData(256, 128, new byte[256 * 128 * 4]);
Material glass = new Material(Color.BLACK).setEmissiveTexture(screen).setEmissive(Color.WHITE, 2f);
terminal.setMaterial(glass);

scene.setUpdateHandler((s, delta) -> screen.edit(px -> drawNextFrame(px, 256, 128)));
```

The size is fixed when the texture is made. Changing a texture costs one copy of the whole picture to the GPU plus
rebuilding its mipmaps, so a few screens of a few hundred pixels updating every frame are cheap, and a texture that
only changes now and then costs nothing in between. Nothing is uploaded for a frame in which it did not change, so
update it only when the picture really changed. Cube-map and sky textures can not be changed this way.

To show a RenGL `Container` (buttons, text, lists) on a screen, with hover and clicks, use
[`UiScreen`](#an-interface-on-a-screen). To produce the pixels yourself, with your own drawing code, an image decoder or a
video frame, pass them to `update` or `edit`.

### Clicking on a screen

A [`Hit`](#hit) carries `uv()`, the spot on the mesh's texture that was under the pointer, so a click on a screen can be
turned into a click on the picture drawn there: multiply `uv().x()` and `uv().y()` by the texture's width and height.
(For a picture drawn by a `Container`, `UiScreen` does this for you.)

```java nocompile
view.setInputListener(new Viewport3D.InputListener() {
    @Override
    public void clicked(double x, double y) {
        Hit hit = scene.pick((float) x, (float) y, view.getWidth(), view.getHeight());
        if (hit != null && hit.node() == terminal) {
            int px = (int) (hit.uv().x() * screen.width());
            int py = (int) (hit.uv().y() * screen.height());
            terminalUi.click(px, py);   // your own code: which button of the picture is at that pixel
        }
    }
});
```

### An interface on a screen

`UiScreen` puts a [`Container`](../containers/container.md) on a surface in the scene: anything a window can show,
buttons, text, lists, animated backgrounds. It draws the container into a `TextureData`, and the player hovers and clicks
it by aiming at the surface. The drawing is done on the CPU by the renderer that takes a
[snapshot](../core/snapshots.md), so it needs no window and works the same on every graphics API.

```java nocompile
Container ui = new Container(320, 200);                       // give it its size first
ui.getStyling().setBackgroundColor(new Color(0.02f, 0.05f, 0.09f, 1f));
ButtonOverlay open = new ButtonOverlay("Open the door", 200, 40);
open.setX(60);
open.setY(80);
open.onMouseClick(e -> unlockDoor());                           // your own code
ui.addElement(open);

UiScreen screen = new UiScreen(ui, 2f);                         // a 640 x 400 texture
terminal.setMaterial(new Material(Color.BLACK).setEmissiveTexture(screen.texture()).setEmissive(Color.WHITE, 2f));

scene.setUpdateHandler((s, delta) -> screen.refresh(delta));    // draw it as it looks now

view.setInputListener(new Viewport3D.InputListener() {
    @Override
    public void pointerMoved(double x, double y) {
        screen.pointAt(scene.pick(x, y, view.getWidth(), view.getHeight()), terminal);   // hovers the button
    }

    @Override
    public void pointerExited() {
        screen.pointAway();
    }

    @Override
    public void clicked(double x, double y) {
        screen.click(scene.pick(x, y, view.getWidth(), view.getHeight()), terminal);
    }

    @Override
    public void scrolled(double dx, double dy, double x, double y) {
        screen.scroll(dx, dy);
    }
});
```

#### Typing on a screen

A text field on a screen works like one in a window. Pressing it gives it the keyboard, with the caret where it was
pressed, and holding and moving the pointer selects text. Your game then has to send it the keys: while
`screen.hasFocus()` is true, typed characters and keys go to the screen instead of moving the player.

```java nocompile
ui.addElement(new TextFieldOverlay(20, 20, 280, 30));          // a field on the screen

// in the same InputListener:
@Override
public void charTyped(int codepoint) {
    if (screen.typeChar(codepoint)) screen.refresh();            // false when no field has the keyboard
}

@Override
public void keyPressed(int key, int action, int mods) {
    if (screen.key(key, action, mods)) {
        screen.refresh();                                        // the field took the key
    } else {
        movePlayer(key, action);                                 // not a field's: the game's
    }
}
```

`key` returns true only for a press or repeat that a field used, so a movement key the player had already
pressed still comes up for the game. Arrows, Home, End, Backspace, Delete, Enter (a field's `onSubmit` runs), select all,
copy, cut, paste, undo and redo all work, as in a window. Tab and Shift+Tab move to the next and previous field on the
screen (in the order a window uses, `setTabIndex` first, a disabled field skipped), and Escape lets go of the keyboard.
Pressing anywhere else on the screen, or away from it, also lets go.

| Method | Description |
|---|---|
| `hasFocus()` / `focused()` | Whether a field has the keyboard, and which one. A field disabled while it has it loses it |
| `typeChar(codepoint)` | Types a character into the focused field. Returns whether a field had the keyboard |
| `key(key, action, mods)` | Sends a key (`GLFW_PRESS`, `GLFW_REPEAT` or `GLFW_RELEASE`). Returns whether a field used it; a release is passed on but never reported as used |
| `focus(Element)` / `clearFocus()` | Gives the keyboard to a field in code, for example to start typing when the player walks up to the terminal, or takes it away when they walk off |
| `focusNext()` / `focusPrevious()` | Moves to the next or previous field in Tab order. With none focused, the first or the last |

Things to know about typing:

- **Call `refresh` after a key or character** (as above), and a few times a second while a field is focused, so the caret
  blinks: `refresh(delta)` advances the blink.
- **Tab with nothing focused is left to the game**, so Tab does not steal the keyboard from your controls. Use
  `focusNext()` or `focus(field)` to start typing from code.
- **Paste uses the window's clipboard**, so it works in a game running on the engine's main loop, as a field in a window does.
- The text field is drawn flat on the surface: there is no input-method (IME) window for composing text, and no on-screen keyboard.

| Method | Description |
|---|---|
| `new UiScreen(container)` / `new UiScreen(container, scale)` | Shows the container. The texture is its size times `scale` (texture pixels per point, `1` if left out): `2` for a screen seen from close up, `0.5` for a cheap one |
| `texture()` | The `TextureData` to put on a material. The same object for the life of the screen |
| `root()` | The container |
| `setBackground(Color)` | What fills the texture behind the container. Opaque black by default, so a container with no background of its own is a dark screen, not a see-through one |
| `refresh()` | Draws the container into the texture as it looks now |
| `refresh(delta)` | The same, first advancing the container's own animations (hover fades, animated backgrounds) by `delta` seconds, as a window does every frame |
| `pointAt(Hit, Node3D)` | Aims the pointer where a pick landed if it landed on that node, and hovers what is there. A hit on anything else, or none, takes the pointer away |
| `pointAt(Vec2 uv)` / `pointAway()` | The same from a spot on the texture (top-left origin, 0 to 1), or taking the pointer away |
| `click(Hit, Node3D)` | `pointAt`, then a left press and release. Returns whether it landed on something that takes clicks |
| `press()` / `release()` (and `press(button)`, `release(button)`) | A press and a release apart, for a button that is held. `press` returns whether the press was used, so you know it was not the game's |
| `scroll(dx, dy)` | Turns the wheel over whatever scrolls under the pointer. Returns whether something did |
| `hovered()` / `isPointing()` | The element under the pointer, and whether the pointer is on the screen |
| `close()` | Frees the drawing surface. The texture stays valid |

Hover, clicks, the disabled state and scrolling follow the window's rules, because they are found by the same code
(`UiHitTest`): a click goes to the nearest element at or above the one pointed at that listens for it, a button clicks when
it is let go over, and letting go somewhere else cancels the click.

Things to know:

- **It costs a CPU draw and a texture upload each time you call `refresh`.** A screen with a clock or a scrolling list can
  be refreshed a few times a second, not every frame. A screen that only changes when the player clicks needs a refresh
  after the click and no more.
- **The size is taken when the screen is made.** A container resized later is still drawn into the old texture size.
- **Not done: dragging (other than selecting text), popups (a dropdown's list) and context menus.** A dropdown on a
  screen shows but its list does not open.
- **Use it from one thread**, as a window's interface is used: the render thread, in the handlers above.
- The mesh needs texture coordinates covering the whole texture, and the surface must be pickable (the default). A hit on
  a mesh without them gives no spot, so the pointer goes away.

## Light

A scene shades up to 64 lights at once: directional, point or spot. They are made with factory methods, and the setters
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
| `Light.spot(position, direction, color, intensity, range, innerDegrees, outerDegrees)` | A point light that shines only inside a cone: full strength inside the inner half-angle, gone at the outer one (at most 85) |
| `getType()` | `Light.Type.DIRECTIONAL`, `POINT` or `SPOT` |
| `setVector(Vec3)` / `getVector()` | The direction of a directional light, or the position of a point or spot light. Move a light every frame from the update handler |
| `setDirection(Vec3)`, `setCone(inner, outer)` | Where a spot light points and how wide it is |
| `setColor(Color)`, `setIntensity(float)`, `setRange(float)` | Change it later |
| `setShadows(Shadows)` / `setShadows(ShadowQuality)` | Any light can cast [shadows](lighting.md#shadows): pick a preset (`LOW` to `ULTRA`) and override the size, bias, softness, distance, cascades or filter |
| `setCastShadow(boolean)` | `true` is the same as `setShadows(Shadows.SCENE)`: shadows with the scene's settings (`false` turns them off) |
| `setFalloff(LightFalloff)` | How a point or spot light fades with distance: `WINDOWED_INVERSE_SQUARE` (default), `LINEAR`, `NONE` or a [custom GLSL one](lighting.md#custom-filters-and-falloffs) |

[Lighting](lighting.md) covers shadows, the skybox and how the lights add up.

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
| `instance()` | Which of the node's [instances](instancing.md) was hit, or `-1` for a node that is not instanced |
| `uv()` | The mesh's texture coordinates at the hit (a `Vec2`, origin at the top-left of the image, `0` to `1` across it): the spot of a texture that was under the pointer, see [clicking on a screen](#clicking-on-a-screen). It is the mesh's raw coordinate, before the material's UV transform. `null` for a triangle with no area; `(0, 0)` for a mesh with no texture coordinates |

### How it decides

- Only **visible** nodes with a mesh and `isPickable()` are tested. A node under an invisible parent is skipped.
- Picking matches what is drawn: a triangle facing away from the ray is skipped, like back-face culling, unless the
  node's material is [double-sided](#material). From inside a closed shape you hit nothing.
- A mesh's box in scene space is tested first, then its triangles through a hierarchy of boxes that the mesh builds the
  first time it is picked, so a ray only tests the triangles near its path. Even a model with hundreds of thousands of
  triangles answers a click at once. An [instanced](instancing.md) node is tested once per instance.
- A [skinned](animation.md) mesh has no stored hierarchy, because its shape changes with every pose. It is posed on the
  CPU and searched afresh, so it follows the animation exactly but costs a pass over its vertices per pick: fine for a
  click, not for thousands of rays per frame.
- It reads the mesh in memory, not the GPU, so it works on any thread while the scene isn't changing, and it hits
  exactly the triangles that are drawn, scaled, rotated and moved by their nodes (a non-uniform scale is fine).
- Alpha is ignored: a see-through material is hit like a solid one. Give it `setPickable(false)` or use `pickAll` to
  look behind it.

## Math types

In `me.piitex.engine.render3d.math`. They are small immutable value classes; every operation returns a new one.

| Type | What it is |
|---|---|
| `Vec3` | A record `(x, y, z)` with `add`, `sub`, `mul(float)`, `negate`, `dot`, `cross`, `length`, `normalized`, `lerp`, and the constants `ZERO`, `ONE`, `UP`, `X`, `Z` |
| `Quat` | A unit quaternion: `fromAxisAngle(axis, radians)`, `fromEuler(pitch, yaw, roll)`, `mul`, `slerp(other, t)`, `rotate(Vec3)`, `normalized`, `toMatrix`, and `IDENTITY`. `a.mul(b)` applies `b` first, then `a` |
| `Mat4` | A column-major 4x4: `identity`, `translation`, `scale`, `compose(position, rotation, scale)`, `perspective`, `orthographic`, `lookAt`, `mul`, `inverse` (null when it can't be undone), `transformPoint`, `transformDirection`, `normalMatrix`, `decompose`, `toArray`, `put(ByteBuffer)` |
| `Bounds` | An axis-aligned box `(min, max)` with `center`, `radius`, `union`, and `transform(Mat4)` |
| `Ray` | A record `(origin, direction)` with `at(distance)` and `distanceTo(Bounds)` (-1 for a miss). `Ray.of(origin, direction)` normalizes the direction, so distances are in scene units |

Projection matrices are Vulkan-style on both APIs (+Y up in clip space, depth `0` to `1`); see
[Custom rendering](custom-rendering.md#conventions).

## Under the hood

A scene makes one `SceneRenderer` per GPU device it is shown on, shared by that scene's viewports. Each frame it
collects the visible nodes, sorts the solid ones by material and mesh, and writes one record per node (or per
[instance](instancing.md)) into a single vertex buffer that the GPU reads once per instance: the transform, tint and
material parameters. Nodes that share a mesh and a material become **one instanced draw**, so the cost is the mesh and the
material's textures, not a descriptor set per node. Shadow-casting lights add a depth pass first (one picture per cascade, spot light or side of a point light, all into one shared atlas); the skybox is drawn
before the solid nodes, and the blended ones last, far to near. GPU meshes and textures are cached and freed after 600
frames without being drawn. You never touch it directly; it is how the scene follows its `Content` lifecycle: `init`
makes the renderer, `render` advances the animation players and runs the update handler (once per frame) and draws,
`dispose` frees it when the last viewport lets go.

## Frustum culling

A node whose mesh is wholly outside the camera's view is not drawn, so a big world with the camera in a corner of it costs
what is in front of the camera. The test is cheap and conservative: a sphere around the mesh against the six sides of the
view volume, so a node just outside may still be drawn, and one inside is never dropped.

- A node with [`Instances`](instancing.md) is tested as a whole first (one sphere around all of its copies), then copy by
  copy: a copy out of view is not drawn.
- **Shadows don't care.** A node out of view that casts a shadow into the view is still drawn into the shadow picture.
- A skinned mesh is bounded by its **joints**: each joint is the middle of a sphere as big as the farthest vertex it moves
  (worked out once from the bind pose), so the mesh is culled when every one of them is out of view, whatever the
  animation does. `node.setFrustumCulled(false)` turns culling off for a node whose picture is not where its mesh's box
  is (a mesh bent by a shader of your own, say).
- `scene.getStats()` says what the last frame did: nodes drawn, nodes culled, instance records and draw calls. It is the
  quickest way to see that culling and batching are working.

## Gotchas

- **A node needs a mesh to draw anything.** A node with no mesh is only a group.
- **Mirrored nodes work.** A negative scale (a model exported with a flipped axis) turns the triangles inside out, and the scene turns the culling around for it, so the model looks as it should.
- **Loading and building never need the GPU**, so it is safe to build a big scene on a worker thread with
  [`Scheduler.async`](../core/scheduler.md) and add it from the result callback. The first frame that draws it will pause
  while it uploads, unless you [preload](#preloading) it first.
- **Colours are not linear.** Pick them as you would for the UI. Lights multiply in linear light internally.
- **Many nodes are fine, many distinct materials cost more.** Nodes that share a mesh and a material draw together;
  each change of material binds its textures again. Use [`Instances`](instancing.md) for thousands of copies.
- **Skinned meshes ignore their node's transform.** The joints place them, as in glTF; move the skeleton's nodes instead.
- **A scene shades at most 64 lights at once,** the ones nearest the camera when it has more. Up to 8 of them can cast
  shadows, at a pass each.
