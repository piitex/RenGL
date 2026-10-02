# Animation and skinning

[Documentation](../README.md) › [3D](README.md)

Two things make a model move: **animation clips**, which change the position, rotation and scale of nodes over time, and
**skins**, which bend a mesh to follow a skeleton of nodes. glTF files bring both; you can also make your own.

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
model is a separate set of nodes: to animate copies, load the model again, or build clips for the copy's nodes.

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
- `Skin.MAX_JOINTS` (256) is the most joints in one skin; a model with a bigger skin is drawn without skinning, with a log
  line.
- A vertex follows at most four joints. Files with more are reduced to the four strongest, which is what nearly every
  engine does.
- Skinned meshes cast [shadows](lighting.md#shadows) in their pose, and are [picked](scenes.md#picking) in their pose.
- `Node3D.copy()` of a skinned model makes a copy that follows its own copied skeleton.

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
| `AnimationClip.Channel(target, path, times, values, interpolation)` | One property of one node: `TRANSLATION` (3 numbers a key), `ROTATION` (4, a unit quaternion) or `SCALE` (3) |
| `Interpolation.LINEAR` | Blends between keys (rotations by `slerp`, the short way round) |
| `Interpolation.STEP` | Holds each key until the next |

## Limits

- Clips from files are sampled **linearly**; cubic-spline keys from glTF are used as plain keys.
- **Morph targets** (blend shapes) are not supported; a file's morph targets are ignored.
- An animation changes the **local transforms** of the nodes it targets; a node animated by a clip and moved by your code
  in the same frame takes whichever came last.
- Skinning is done on the GPU for drawing and shadows, and on the CPU for [picking](scenes.md#picking).
