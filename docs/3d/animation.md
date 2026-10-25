# Animation and skinning

[Documentation](../README.md) › [3D](README.md)

Three things make a model move: **animation clips**, which change the position, rotation, scale and blend-shape weights
of nodes over time, **skins**, which bend a mesh to follow a skeleton of nodes, and **morph targets**, which blend a
mesh between shapes (a smile, a blink). glTF files bring all three; you can also make your own.

## Playing a model's animations

```java nocompile
Model model = Model.load(Path.of("assets/soldier.glb"));
scene.add(model.root());

AnimationPlayer player = model.player();
scene.animate(player);                 // advances once per frame, before the update handler
player.play("Idle");

// later, from a key press or the update handler:
player.play("Run", 0.3f);              // fade from the current pose to Run over 0.3 seconds
```

`model.animations()` lists the clips (`name()`, `duration()`, `channels()`). A [`Viewport3D`](viewport-3d.md) draws every
frame by default, which an animation needs; in a [non-continuous](viewport-3d.md#drawing-only-when-needed) one the
animation only advances when the viewport draws.

| `AnimationPlayer` method | Description |
|---|---|
| `play(String name)` / `play(String name, float fadeSeconds)` | Starts a clip from its beginning. With a fade, the old pose blends into the new one. Throws `IllegalArgumentException` for an unknown name, listing the ones there are |
| `play(AnimationClip, float fadeSeconds)` | The same for a clip object |
| `stop()` | Stops and leaves the nodes in their current pose |
| `setPlaying(boolean)` / `isPlaying()` | Pause and resume. A clip that is not looped stops by itself at its end |
| `setLoop(boolean)` | Start again at the end (the default) or stop on the last pose |
| `setSpeed(float)` | `1` is the recorded speed, `2` double, negative backwards |
| `setTime(float)` / `getTime()` | Jump to a time in the current clip, and pose the nodes there |
| `getCurrent()` / `clip(String)` / `clips()` | The playing clip, a clip by name, all of them |
| `update(float delta)` | Advances it yourself. A player registered with `scene.animate` is advanced for you |

A player moves the nodes of **the model it came from**, so use `model.root()` itself. A [`copy()`](scenes.md#node3d) of a
model is a separate set of nodes: to animate copies, or any other character with the same skeleton, make a player for each
with [`retarget`](#sharing-a-skeleton-and-animations).

## Skins

A **skinned mesh** has, for each vertex, up to four joints and a weight for each, saying how much that joint's movement
the vertex follows. The joints are ordinary nodes, usually a skeleton. When the skeleton's nodes move (by an animation, or
by your code), the mesh bends. A model file with bones sets all this up; to make it by hand:

```java nocompile
Mesh tube = new Mesh(positions, null, uvs, indices);
tube.setSkinning(
        new int[]{0,0,0,0,  0,1,0,0,  1,0,0,0},       // four joint numbers per vertex
        new float[]{1,0,0,0, .5f,.5f,0,0, 1,0,0,0});  // four weights per vertex, adding up to 1

Node3D shoulder = new Node3D("shoulder");
Node3D elbow = new Node3D("elbow");
elbow.setPosition(0, 1, 0);
shoulder.add(elbow);
scene.add(shoulder);

Skin skin = new Skin(List.of(shoulder, elbow),
        List.of(Mat4.identity(), Mat4.translation(new Vec3(0, -1, 0))));   // each joint's inverse bind matrix
Node3D arm = new Node3D("arm", tube, new Material(Color.ORANGE));
arm.setSkin(skin);
scene.add(arm);

elbow.setRotation(Quat.fromAxisAngle(new Vec3(0, 0, 1), 0.8f));            // the arm bends
```

- The **inverse bind matrix** of a joint takes a vertex from the mesh's space into the joint's space *in the pose the mesh
  was modelled in*; for a joint that sat at `p` in the bind pose it is a translation by `-p` (plus the rotation inverse, if
  it was rotated).
- A mesh drawn with a skin **ignores its own node's transform**: the joints place it, as in glTF. Move the skeleton
  instead, or give the skeleton a parent node and move that.
- `Skin.MAX_JOINTS` (512) is the most joints in one skin; a model with a bigger skin is drawn without skinning, with a log
  line.
- A vertex follows at most four joints. Files with more are reduced to the four strongest, which is what nearly every
  engine does.
- Skinned meshes cast [shadows](lighting.md#shadows) in their pose, and are [picked](scenes.md#picking) in their pose.
- `Node3D.copy()` of a skinned model makes a copy that follows its own copied skeleton.

## Sharing a skeleton and animations

Parts loaded from their own files (hair, clothes) bring their own copy of the skeleton, and a clip points straight at the
nodes of the skeleton it came from. Three tools tie them together by **bone name**.

```java nocompile
// 1. Bind a part to the body's skeleton
Model hair = Model.load(Path.of("assets/hair_long.glb"));
hair.root().bindSkeleton(body.root().find("Armature"));   // every skinned mesh in it now follows the body's bones
scene.add(hair.root());

// 2. Drive more than one character from one animation library
AnimationPlayer library = body.player();
for (Node3D npc : crowd) {                                // crowd: copies made with body.root().copy()
    AnimationPlayer player = library.retarget(npc.find("Armature"));
    scene.animate(player);
    player.play("Walk");
}

// 3. Keep proportion sliders through every animation
BoneOffsets proportions = new BoneOffsets(body.root().find("Armature"));   // right after loading
player.setBoneOffsets(proportions);
proportions.scale("head", 1.15f);
proportions.position("shin_l", new Vec3(0, 0.05f, 0));
```

| Method | Description |
|---|---|
| `Node3D.bindSkeleton(Node3D skeletonRoot)` | Rebinds every skin in the node and its descendants to the same-named bones under `skeletonRoot`. Parts that shared a skin keep sharing one |
| `Skin.rebind(Node3D skeletonRoot)` | The same for one skin: a new `Skin` with the joints swapped and the inverse bind matrices kept. Throws `IllegalArgumentException` naming the bones the skeleton lacks |
| `AnimationClip.retarget(Node3D skeletonRoot)` | A clip whose channels move the same-named nodes under `skeletonRoot` |
| `AnimationPlayer.retarget(Node3D skeletonRoot)` | A new player with every clip retargeted. Speed and loop are not copied |
| `AnimationPlayer.setBoneOffsets(BoneOffsets)` | Applies the offsets after every pose; `null` removes them |
| `BoneOffsets.scale(bone, Vec3 / float)` | Multiplies the bone's scale after the animation, so the bones under it scale too |
| `BoneOffsets.position(bone, Vec3)` | Adds to the bone's position, relative to its parent: moving a bone away from its parent lengthens the limb |
| `BoneOffsets.clear(bone)` / `clear()` / `getScale(bone)` / `getPosition(bone)` | Remove and read offsets |
| `BoneOffsets.apply()` | Poses the adjusted bones at rest plus the offsets, for a skeleton no clip moves |

- **Names must match.** The hair and the body have to be modelled on the same skeleton in the same pose. A bone name that
  appears twice resolves to the first one found, as with `find`, so pass the armature node and not a node that also holds
  the part's own copy of the skeleton.
- A **retargeted clip leaves out channels** for nodes the other skeleton lacks (a prop, a bone it doesn't have), and throws
  only when none match. The keyframes are **shared**, not copied, so a hundred players cost a hundred small channel lists,
  not a hundred copies of the animation.
- **Offsets are applied on top of the pose**, which is reset to the skeleton's rest position and scale first, so they don't
  compound frame after frame and a bone the clip doesn't animate keeps its rest value. `BoneOffsets` notes the rest values
  when it is made, so make it before anything moves the skeleton. Moving a slider while the animation is paused shows at
  once. Rotation isn't touched, and a bone with offsets shouldn't also be moved by your own code, since the next pose
  overwrites it.
- Each player has its own `BoneOffsets`, so crowd members can have different proportions.

## Morph targets

A mesh can carry blend shapes: for each shape, how far every vertex moves when it is fully on. A node mixes them with
**weights**, one per shape, usually 0 to 1:

```java nocompile
Model face = Model.load(Path.of("assets/face.glb"));
Node3D head = face.root().find("head");
head.setMorphWeights(0f, 0.8f, 0f);   // shape 1 at 80%
head.setMorphWeight(2, 0.5f);         // just shape 2
float[] now = head.getMorphWeights();
```

A glTF file's targets load by themselves (`mesh.morphTargets()` lists them with the names the file gives), and the file's
animations that move the weights play like any other clip. The weights belong to the **node**, so two nodes with the same
mesh can show different expressions, and a model with a mesh in several parts (one per material) blends all of them
together. To make your own:

```java nocompile
Mesh plane = Mesh.plane(2, 2);
float[] lift = new float[4 * 3];                     // x, y, z per vertex, as many vertices as the mesh
for (int v = 0; v < 4; v++) lift[v * 3 + 1] = 1f;    // every vertex up by 1
plane.setMorphTargets(List.of(new Mesh.MorphTarget("lift", lift, null)), null);   // offsets, optional normal changes
node.setMorphWeight(0, 0.5f);                        // half way up
```

- The blending happens on the GPU, in the vertex shader. The mesh's shapes are uploaded once, as a texture of offsets
  (kept while the mesh is drawn), and every frame only the weights go up, so any number of nodes can share a mesh and
  animate their weights for the price of a few floats each. A mesh whose weights are all zero costs nothing extra.
- That works for up to 16 shapes with a non-zero weight at a time (a face rig has dozens of shapes but rarely more than a
  handful switched on) and for meshes up to about two million shape-vertices (vertices times shapes; 64 MB of texture).
  A node past either limit is blended on the CPU instead, into a copy of the mesh's vertices that is refilled only when its
  weights change: the same picture, but a cost per vertex when the weights change every frame. The shapes' offsets are
  stored in half precision on the GPU, which is plenty for a smile and not for a shape that moves a vertex by thousands of
  units.
- Morphing runs before [skinning](#skins), so a face can smile while the head turns. This holds on the GPU, in a shadow,
  when picking, and in the culling bound, which counts the farthest each shape can push a vertex.
- A target may also change the normals; when it doesn't, the normals stay as authored.
- The mesh's `bounds()` grow to hold the shapes at full weight, which keeps [culling](scenes.md#frustum-culling) and
  [picking](scenes.md#picking) from clipping a morphed mesh. Picking uses the blended shape.
- `Node3D.copy()` gives the copy its own weights, and keeps parts that shared them sharing.

## Your own clips

```java nocompile
AnimationClip spin = new AnimationClip("spin", 2f, List.of(
        new AnimationClip.Channel(node, AnimationClip.Path.ROTATION,
                new float[]{0f, 1f, 2f},                         // key times in seconds
                new float[]{0,0,0,1,  0,1,0,0,  0,0,0,-1},        // x, y, z, w of a rotation per key
                AnimationClip.Interpolation.LINEAR)));
AnimationPlayer player = new AnimationPlayer(List.of(spin));
scene.animate(player);
player.play("spin");
```

| Part | Description |
|---|---|
| `AnimationClip(name, duration, channels)` | A named movement of some length |
| `AnimationClip.Channel(target, path, times, values, interpolation)` | One property of one node: `TRANSLATION` (3 numbers a key), `ROTATION` (4, a unit quaternion), `SCALE` (3) or `WEIGHTS` (the node's [morph weights](#morph-targets): one number per shape, the same count at every key) |
| `Interpolation.LINEAR` | Blends between keys (rotations by `slerp`, the short way round) |
| `Interpolation.STEP` | Holds each key until the next |
| `Interpolation.CUBICSPLINE` | A cubic Hermite curve between keys, from the in and out slopes at each key. Built with the channel constructor that takes the tangents |

## Limits

- Clips from files keep their glTF interpolation: `STEP` and `CUBICSPLINE` tracks are read from the file's own JSON,
  because Assimp turns both into plain blends.
- **Morph targets** are blended on the GPU for up to 16 shapes switched on at a time; past that (or past about two million
  shape-vertices) the node falls back to blending on the CPU, which costs per vertex for every shape with a non-zero weight.
- An animation changes the **local transforms** of the nodes it targets; a node animated by a clip and moved by your code
  in the same frame takes whichever came last.
- Skinning is done on the GPU for drawing and shadows, and on the CPU for [picking](scenes.md#picking).
