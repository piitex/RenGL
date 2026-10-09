# Scene files

[Documentation](../README.md) › [3D](README.md) › Scene files

A **scene file** holds a [`Scene3D`](scenes.md) as JSON, so a 3D scene can be built in the RenGL Editor and loaded by the
game. `Scene3DFile` (in `me.piitex.engine.render3d.scene.file`) reads and writes them.

```java nocompile
Scene3D scene = Scene3DFile.load("scenes/main.json");
window.setBackground(new Background3D(window, scene));       // or a Viewport3D
Node3D player = Scene3DFile.find(scene, "player");
scene.setUpdateHandler((s, delta) -> player.rotate(Vec3.UP, delta));
```

`load` makes the real scene, so everything else in the engine works on it: picking, animation, your own update handler.
A node is found by the name it has in the file (`Scene3DFile.find`), a light by its name too (`Scene3DFile.findLight`). The
first match, looking through the nodes in order, wins. The nodes inside a model's own file are not looked in.

## The format

```json
{
  "format": 1,
  "world": { "background": [0.1, 0.11, 0.14, 1], "skyKind": "GRADIENT", "fogDensity": 0.01 },
  "camera": { "position": [3.5, 2.8, 5], "target": [0, 0.4, 0], "fov": 55 },
  "lights": [
    { "type": "DIRECTIONAL", "name": "Sun", "intensity": 0.9, "direction": [-0.32, -0.81, -0.49], "shadows": "HIGH" },
    { "type": "POINT", "name": "Lamp", "position": [0, 3, 0], "color": [1, 0.7, 0.4, 1], "range": 8 }
  ],
  "nodes": [
    { "name": "Floor", "shape": "PLANE", "width": 20, "depth": 20, "material": { "color": [0.5, 0.55, 0.6, 1] } },
    { "name": "Crate", "shape": "CUBE", "size": 1.2, "position": [-1, 0.6, 0], "rotation": [0, 30, 0],
      "material": { "texture": "assets/crate.png", "pbr": true, "roughness": 0.8 },
      "children": [ { "name": "Marker", "shape": "SPHERE", "radius": 0.1, "position": [0, 0.8, 0] } ] },
    { "name": "Statue", "shape": "MODEL", "model": "assets/statue.glb", "scale": [2, 2, 2], "animation": "Idle" }
  ]
}
```

What is missing takes its default, and a value that is the default is left out when saving. Colors are `[r, g, b, a]`
from 0 to 1, or `"#RRGGBB"`; positions, directions and scales are `[x, y, z]`; enums are written by name. A node's
`rotation` is three angles in degrees (X nods, Y turns, Z tilts), which `Euler` converts to and from a `Quat`.

| Part | What it holds |
|---|---|
| `world` | The background color; the sky (`skyKind` `NONE`, `GRADIENT`, `PANORAMA` or `CUBE`, with its colors or pictures); ambient light and sky lighting; fog; the default size, distance, bias and softness of shadows; `gravity`, the solver's `velocityIterations` and `substeps` |
| `camera` | `position`, `target`, `fov` (degrees), `near`, `far` |
| `lights` | A `type` of `DIRECTIONAL`, `POINT` or `SPOT`, a `name`, `color`, `intensity`, and what the type needs: a `direction`, a `position`, a `range`, a cone (`innerAngle`, `outerAngle`), a `falloff`, and `shadows` (`OFF`, `SCENE` for the scene's settings, or `LOW` to `ULTRA`) |
| `nodes` | A tree. Each has a `name`, `position`, `rotation`, `scale`, `visible`, `pickable`, `castShadow` and `receiveShadow`, a `shape`, a `material` for shapes that draw something, `children`, and a `body` with its settings if physics moves it (see [Physics](#physics)) |
| `joints` | Joints between the nodes' bodies, each with a `type` of `BALL`, `HINGE`, `SLIDER`, `DISTANCE` or `FIXED` (see [Physics](#physics)) |

A node's `shape` is `NONE` (a group, which draws nothing and moves what is in it), `CUBE` (`size`), `SPHERE` (`radius`,
`segments`, `rings`), `PLANE` (`width`, `depth`) or `MODEL`. A model node loads the file named by `model`
([Models](models.md): glTF, GLB, OBJ, FBX or Collada) as the nodes inside it; they come from the file each time and are not saved
in the scene, though a model node can have children of its own. Its `animation` names one of the model's clips, which
`Scene3DFile.load` starts playing, over and over; `Scene3DFile.animations(node)` lists the names.

A `material` has the properties of [`Material`](scenes.md#material): `color`, `texture`, `unlit`, `doubleSided`, `blend`,
`additive`, `alphaCutoff`, `pbr`, `specular`, `shininess`, `metallic`, `roughness`, `normalTexture`, `normalScale`,
`metallicRoughnessTexture`, `occlusionTexture`, `emissiveTexture`, and a glow as `emissiveColor` and `emissiveStrength`.
Every node has a material of its own.

Picture and model paths are relative to the folder `load` is given (`Scene3DFile.load(path, base)`), the working folder
for `load(String)`. One that can't be read is left out of the scene and its path kept when the file is saved again;
`Scene3DFile.modelProblem(node)` and `skyProblem(scene)` say why.

## Physics

A node with a `body` gets a [rigid body](physics.md#bodies) or a [character](physics.md#characters) when the scene loads,
and the scene gets a `PhysicsWorld` with them in it (`scene.getPhysics()`), which runs with the scene:

```json
{
  "world": { "gravity": [0, -9.81, 0] },
  "nodes": [
    { "name": "Floor", "shape": "PLANE", "width": 20, "depth": 20, "body": "STATIC" },
    { "name": "Crate", "shape": "CUBE", "position": [0, 3, 0], "body": "DYNAMIC", "mass": 20, "bounce": 0.2 },
    { "name": "Door", "shape": "CUBE", "position": [0, 1, -4], "scale": [1, 2, 0.1], "body": "DYNAMIC" },
    { "name": "Player", "shape": "MODEL", "model": "assets/hero.glb", "body": "CHARACTER", "stepHeight": 0.25 }
  ],
  "joints": [
    { "type": "HINGE", "name": "Door hinge", "bodyA": "Door", "anchor": [-0.5, 1, -4], "limited": true, "lower": 0, "upper": 100 }
  ]
}
```

```java nocompile
Scene3D scene = Scene3DFile.load("scenes/level.json");
CharacterController player = Scene3DFile.findCharacter(scene, "Player");
RigidBody crate = Scene3DFile.findBody(scene, "Crate");
HingeJoint hinge = (HingeJoint) Scene3DFile.findJoint(scene, "Door hinge");
```

A node's `body` is `NONE` (the default), `STATIC` (never moves), `DYNAMIC` (falls and is pushed), `KINEMATIC` (moved by
the game, pushing what is in its way) or `CHARACTER` (walked by the game, standing at the node's position).

| Settings | For | What they are |
|---|---|---|
| `collider` | static, dynamic and kinematic | `AUTO` (the default) fits what the node draws: a cube's box, a sphere's ball, a plane's sheet as a 2 cm box under it, a model's own triangles if it stays put or a hull around them if it moves, and a box of `colliderSize` for a group. `BOX` (`colliderSize`, full lengths), `SPHERE` (`colliderRadius`), `CAPSULE` (`colliderRadius`, `colliderHeight`, standing along Y), each moved by `colliderOffset`; `HULL` and `MESH` from what the node draws; `colliderWeld` (metres) joins the corners of a mesh that lie that close, so pieces that only nearly meet have no gap |
| `mass` (kg), `linearDamping`, `angularDamping`, `canSleep` | dynamic | |
| `friction`, `bounce`, `trigger` | static, dynamic and kinematic | |
| `gravityScale` | dynamic and characters | |
| `layer`, `mask` | every body | Collision layers, as bits |
| `characterRadius`, `characterHeight`, `characterMass`, `slopeLimit` (degrees), `stepHeight` | characters | |

Sizes are in the node's own units: its scale stretches the collider, as it does the mesh. A collider is made from what
the node itself draws, not from its children, which have bodies of their own if they collide. A `MESH` can't move, so a
dynamic body asking for one gets a hull.

A joint names the nodes it joins by `bodyA` and `bodyB` (left out to hold `bodyA` to the world); points (`anchor`, and
`anchorB` for a distance joint) and an `axis` are in the world, as the nodes stand in the file. A hinge or slider can be
`limited` between `lower` and `upper` (degrees for a hinge, metres for a slider) and have a `motor` with `motorSpeed`
(degrees or metres a second) and `motorStrength` (newton metres or newtons). A distance joint keeps its points as far
apart as they start, or `length`; `rope` lets them come closer, and `springFrequency` with `springDamping` makes it a
spring. A ball joint has an `axis` along the limb and can limit its swing (`swingLimited`, `swing` in degrees: a cone around
the axis) and its twist (`limited` with `lower` and `upper` degrees). Any joint can have `collideConnected` and a `breakForce`. A joint whose nodes aren't there, or have no body,
is left out with a warning in the log, and kept in the file.

Loading for an editor (`load(path, base, false)`) builds no world, so nothing falls while the scene is edited;
`Scene3DFile.buildPhysics(scene)` builds one on demand, to try the scene out or to show its colliders in a
[`ColliderView`](physics.md#seeing-the-colliders). `Scene3DFile.joints(scene)` is the list of joints to edit. A scene
with no bodies gets no world.

## Properties

`SceneKinds.node()`, `material()`, `light(type)`, `camera()`, `world()` and `joint(kind)` list the properties of each part, in the order an
editor shows them, each a `SceneProperty` with its name, type, group, range and a way to read and change it. The file
format is just those lists written out, and the editor's inspector is built from them. `SceneKinds.newNode(shape, name)`,
`newLight(type)` and `newJoint(kind)` make parts the way the editor adds them. A node's physics settings are node
properties in the "Physics", "Collider" and "Character" groups.

## Limits

- `Scene3DFile` saves what it loaded, and what an editor made with it. A node built in code is saved as a group, since the
  file has no way to say what its mesh is, and a skybox built in code is not saved.
- [Instancing](instancing.md), skins and morph weights of your own are not in the file; models bring their own.
- A model's animations are only started when the scene loads. Start others from code with `Model.player()`.
