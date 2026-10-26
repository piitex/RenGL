# Physics

[Documentation](../README.md) › [3D](README.md)

Rigid bodies for 3D scenes, in `me.piitex.engine.render3d.physics`: gravity, velocity, mass and inertia, forces and
impulses, collisions with friction and bounce, stacking, sleeping, raycasts and trigger zones, with loaded meshes as
ground and convex hulls for objects of any shape, joints (hinges, ropes, springs, sliders, welds) and a character
controller that walks a player around, and a view that draws the colliders. It is plain Java with no native library, and like the rest of the 3D
layer it costs nothing until you use it.

A **`PhysicsWorld`** holds **`RigidBody`** objects. Each body moves a [`Node3D`](scenes.md#node3d): the world works out
where the body goes and writes the position and rotation into the node, so the scene draws it there.

## A first world

Crates dropping onto a floor, and a ball shot at them with a click:

```java
import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.physics.BodyType;
import me.piitex.engine.render3d.physics.BoxShape;
import me.piitex.engine.render3d.physics.PhysicsWorld;
import me.piitex.engine.render3d.physics.PlaneShape;
import me.piitex.engine.render3d.physics.RigidBody;
import me.piitex.engine.render3d.physics.SphereShape;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;

public class FirstPhysics {
    static void main() {
        Window window = new Window(new WindowOptions("Physics").setDimensions(900, 600).setRenderer(RenderAPI.VULKAN));
        Container root = new Container(900, 600);
        window.addContainer(root);

        Scene3D scene = new Scene3D();
        scene.camera().lookAt(new Vec3(0, 4, 12), new Vec3(0, 1, 0));
        PhysicsWorld world = new PhysicsWorld();
        scene.setPhysics(world);                          // runs once per frame from now on

        Node3D floor = new Node3D("floor", Mesh.plane(20, 20), new Material(new Color(0.5f, 0.5f, 0.5f, 1f)));
        scene.add(floor);
        world.add(new RigidBody(floor, BodyType.STATIC).addShape(new PlaneShape()));

        Mesh cube = Mesh.cube(1f);
        Material wood = new Material(new Color(0.75f, 0.5f, 0.3f, 1f));
        for (int i = 0; i < 5; i++) {
            Node3D crate = new Node3D("crate", cube, wood);
            crate.setPosition(i * 0.2f, 1f + i * 1.5f, 0);   // a wobbly column that will topple
            scene.add(crate);
            world.add(new RigidBody(crate).addShape(BoxShape.ofSize(1, 1, 1)).setMass(10f));
        }

        Mesh ballMesh = Mesh.sphere(0.3f, 32, 16);
        Material red = new Material(new Color(0.9f, 0.2f, 0.2f, 1f));
        Viewport3D view = new Viewport3D(900, 600);
        view.setContent(scene);
        view.setInputListener(new Viewport3D.InputListener() {
            @Override
            public void clicked(double x, double y) {
                Ray ray = scene.camera().screenRay(x, y, view.getWidth(), view.getHeight());
                Node3D ball = new Node3D("ball", ballMesh, red);
                ball.setPosition(ray.origin());
                scene.add(ball);
                world.add(new RigidBody(ball).addShape(new SphereShape(0.3f)).setMass(5f)
                        .setVelocity(ray.direction().normalized().mul(25f)));
            }
        });
        root.addElement(view);
        new Engine().start(window);
    }
}
```

`me.piitex.examples.demo.PhysicsDemo` is a fuller version: a pyramid and a tower of crates, a ramp, balls, and a panel
that counts the bodies that are awake. `me.piitex.examples.demo.DominoDemo` is a quick one: a chain of dominoes along a
curve, a button or a click to push one over, and slow motion. `me.piitex.examples.demo.TerrainDemo` drops rocks (convex
hulls) and crates on a hilly terrain (a triangle mesh). `me.piitex.examples.demo.PlaygroundDemo` walks a character
around stairs, ramps, a lift, a rope bridge of hinged planks, a door, a seesaw and a wrecking ball on a rope.

## Units and conventions

Metres, kilograms and seconds; forces in newtons, torques in newton metres, impulses in newton seconds. +Y is up, and
gravity is (0, -9.81, 0) unless set with `world.setGravity(...)`. A 1 m crate of wood weighs about 600 kg, so if you
want crates you can push around easily, give them a smaller mass: what matters for the look is the ratio between bodies.

## The world

| `PhysicsWorld` method | Description |
|---|---|
| `add(RigidBody)` / `remove(RigidBody)` / `bodies()` | The bodies. A body starts where its node is when added |
| `setGravity(Vec3)` | The pull on every body |
| `update(float delta)` | Moves the world on by a frame's time. A scene with `setPhysics(world)` calls it for you, after its animations and before its update handler |
| `step(float dt)` | Runs exactly one step of `dt` seconds, for tests or your own timing |
| `setFixedStep(float)` | The length of one step, 1/60 s by default |
| `setMaxSteps(int)` | The most steps one `update` runs (8). After a long stall the world drops the rest instead of trying to catch up |
| `setInterpolation(boolean)` | Places the nodes between the last two steps, for smooth motion at any frame rate (on) |
| `setStepHandler(StepHandler)` | Runs before every step: the place for forces that act over time |
| `setVelocityIterations(int)` / `setPositionIterations(int)` | How hard the solver works (10 and 4). 10 holds a stack of ten boxes; 20 holds fifteen. Joints get twice the position passes |
| `setSubsteps(int)` | Cuts each step into smaller ones (1; up to 32), [below](#substeps) |
| `RigidBody.setContinuous(boolean)` | Whether a body with a long step for how thin it is is [swept](#fast-bodies) against static bodies so it can't pass through them (on) |
| `setSolverThreads(int)` | Extra threads for collision tests and for solving separate piles of bodies (up to 7, fewer on a smaller CPU; 0 keeps everything on the calling thread). The result is identical whatever the number. Many scattered bodies scale with the cores; one big heap still solves on one thread |
| `setSleeping(boolean)` | Whether resting bodies may fall asleep (on) |
| `addJoint(Joint)` / `removeJoint(Joint)` / `joints()` | The [joints](#joints). Add the bodies first |
| `addCharacter(CharacterController)` / `removeCharacter(...)` / `characters()` | The [characters](#characters) |
| `getContacts()` | Every pair of shapes touching or about to, with points and normal |
| `raycast`, `raycastAll`, `overlap`, `overlapSphere`, `overlapBox` | Queries, below |
| `addContactListener(ContactListener)` | Hears contacts begin and end, and triggers entered and left |
| `getTime()` / `getStepCount()` / `getInterpolationAlpha()` | Simulated seconds, steps run, and how far the nodes sit between the last two steps |

### Fixed steps

The world always moves in steps of the same length, whatever the frame rate, so a game behaves the same on a 60 Hz and
a 144 Hz screen. A frame runs as many steps as fit in its time, usually zero, one or two. With interpolation on (the
default), each node is then placed between the last two steps by how far the frame got into the next one. Nodes are
therefore up to one step behind the bodies, which is invisible; read the body (`getPosition()`) for the exact place.

### Substeps

`setSubsteps(4)` runs each fixed step as four steps a quarter as long, each with its own collision detection and solving.
Bodies then move a quarter as far between two looks at them, which is what a tall stack, a heavy weight on a light
chain (past about 600 to 1; see [Joints](#joints)), and a thin stick spinning fast ask for. A stack of twenty boxes
with a 200 kg one on top falls over with one step and stands with four. Try 2 to 4 before raising the solver's passes;
it costs about as much more as there are substeps. Forces, the step handler and events still work on the whole step, so nothing else in a game changes.

### Fast bodies

A body that moves more in one step than it is thick (a bullet, a thrown dart, a thin blade or sheet, a long stick spinning
fast) could be on one side of a thin wall at the start of a step and on the other at the end. Contacts reach a little past
a body's box, which holds anything that covers a few metres a step, but a bullet covers dozens. So for such a body the
world also **sweeps** the step's motion: it walks the body along its path, as far as it can without touching anything
static, and moves it only that far, to within half a centimetre of the surface. The next step has a contact there, and the
solver bounces, stops or slides it as it would any other. The check is on by default and only runs for a body whose step is longer than half
its narrowest width (a ball moving a third of its radius a step costs nothing).

```java nocompile
RigidBody bullet = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.02f)).setMass(0.01f);
bullet.setVelocity(0, 0, -600);            // ten metres a step; it stops at the wall, not behind it
bullet.setContinuous(false);               // or let it through, to save the little the check costs
```

It sweeps against static bodies, and kinematic ones that are standing still: spheres, capsules, boxes and hulls, planes and the
triangles of meshes. It leaves out triggers, bodies whose layers and masks don't meet, bodies a joint says not to collide with,
other dynamic bodies and kinematic platforms that are moving (those still rely on the contacts' reach). A body that
starts a step touching a surface isn't held back by it, and neither is one the solver has already brought to the surface, so
resting, sliding and landing are as before.

## Bodies

```java nocompile
RigidBody body = new RigidBody(node)                  // dynamic by default
        .addShape(BoxShape.ofSize(1, 1, 1))
        .setMass(20f)
        .setMaterial(PhysicsMaterial.WOOD);
world.add(body);
```

| Type | Moves how |
|---|---|
| `BodyType.DYNAMIC` | By the simulation: gravity, forces, impulses and collisions |
| `BodyType.KINEMATIC` | Only by its velocity (or by you), never pushed back: lifts, doors, moving platforms. It pushes dynamic bodies, and carries them along by friction |
| `BodyType.STATIC` | Never: floors, walls, scenery |

A body can also be made without a node, `new RigidBody(BodyType.DYNAMIC)`, and placed with `setPosition`.

### Following the node

When a body is added, it reads its node's **world** transform: the position and rotation become the body's, and the
node's **scale stretches the shapes**. Shapes are given in the node's own units, so a `BoxShape.ofSize(1, 1, 1)` on a
`Mesh.cube(1f)` fits it whatever the node's scale. After each update the body writes its position and rotation back
into the node (through the parent's transform, if the node has a parent); the node's own scale is left alone.

If you move or turn the node yourself, the body **teleports** there on the next update and keeps its velocity. After
changing a node's scale, call `body.syncFromNode()` to rescale the shapes.

### Shapes

| Shape | Description |
|---|---|
| `new SphereShape(radius)` | A ball |
| `new BoxShape(halfX, halfY, halfZ)` / `BoxShape.ofSize(w, h, d)` | A box, by half extents or by full size |
| `new CapsuleShape(radius, halfHeight)` / `CapsuleShape.ofHeight(radius, height)` | A cylinder with rounded ends, standing along Y: the usual character shape. `halfHeight` is half the straight part |
| `new PlaneShape(normal)` / `new PlaneShape()` | An endless floor through the body, facing `normal` (up by default). Static and kinematic bodies only |
| `ConvexHullShape.of(points)` / `.of(mesh)` / `.of(node)` | The smallest convex solid around some points, as if shrink-wrapped: rocks, bevelled crates, car bodies. See [below](#meshes-and-hulls) |
| `MeshShape.of(mesh)` / `.of(node)` / `new MeshShape(positions, indices)` | A surface of triangles: levels, terrain, caves. Static and kinematic bodies only. See [below](#meshes-and-hulls) |

A body can have several shapes, each moved and turned from the body's origin, which makes one solid object:

```java nocompile
RigidBody table = new RigidBody(tableNode)
        .addShape(new BoxShape(1f, 0.05f, 0.5f), new Vec3(0, 0.75f, 0))      // the top
        .addShape(new BoxShape(0.05f, 0.35f, 0.05f), new Vec3(-0.9f, 0.35f, -0.4f))
        .addShape(new BoxShape(0.05f, 0.35f, 0.05f), new Vec3(0.9f, 0.35f, -0.4f))
        .addShape(new BoxShape(0.05f, 0.35f, 0.05f), new Vec3(-0.9f, 0.35f, 0.4f))
        .addShape(new BoxShape(0.05f, 0.35f, 0.05f), new Vec3(0.9f, 0.35f, 0.4f));
```

### Meshes and hulls

A **`MeshShape`** makes a loaded level or terrain the ground. `MeshShape.of(node)` takes the triangles of the node's mesh
and of every mesh under it, in the node's own space, so a body on that node lines up with what is drawn:

```java nocompile
Model level = Model.load(Path.of("assets/level.glb"));
Node3D root = level.root();
scene.add(root);
world.add(new RigidBody(root, BodyType.STATIC).addShape(MeshShape.of(root)));
```

A mesh has no inside, so it can only be on a static or kinematic body. Things touch its triangles from either side.
The triangles are kept in a tree, so a large level costs little more per contact than a small one. Corners at the same
place are joined, which tells the mesh where triangles meet: the edge between two triangles that lie flat or fold
inward is a seam, and nothing catches on it, so a box slides across a floor of many triangles as across one face.
Hidden nodes count too, so a level can carry a simple invisible collision mesh beside the detailed one it draws; that
is also quicker.

A **`ConvexHullShape`** is the shape for a moving object that isn't a box, ball or capsule. Dents and holes are filled
in (a mug's hull has no hole for coffee), and points inside are dropped, so only the corners remain:

```java nocompile
Node3D rock = rockModel.root().copy();
scene.add(rock);
world.add(new RigidBody(rock).addShape(ConvexHullShape.of(rock)).setDensity(2500f));
```

Like a mesh, a hull stays where its points are rather than being centred on the body, and its mass is spread through
its volume; `centerOfMass()` says where its middle is. Every corner costs a little in each contact test, so a hull of a
few dozen corners is quicker than one of a few hundred (two hulls of 200 corners take about 3 ms to test and two of 400
about 5 ms; a hull of 30 takes 0.01 ms): build it from a low-detail version of the model when there is one.
For an object with dents that matter (a chair, an L-shaped beam), give the body several hulls or boxes, or let
[`ConvexDecomposition`](#concave-moving-bodies) cut the model into hulls for you.

### Concave moving bodies

A mesh can't move and a hull fills in every dent, so a moving chair, a bowl, or an L-shaped corridor segment is made of
several hulls. `ConvexDecomposition` cuts a model into them:

```java nocompile
RigidBody chair = new RigidBody(chairNode).setMass(8f);
for (ConvexHullShape piece : ConvexDecomposition.of(chairNode, 12)) chair.addShape(piece);
world.add(chair);
```

`of(mesh, maxHulls)`, `of(node, maxHulls)` and `of(positions, indices, maxHulls, tolerance)` return the hulls in the model's
own space, so a body on the model's node lines up with what is drawn. The cutting is by planes along the X, Y and Z axes,
through the deepest dent in the part with the worst one, until no part's surface is more than `tolerance` inside its hull
(a fiftieth of the model's size when you leave it out) or `maxHulls` is reached. A solid model gives solid pieces, a thin
sheet gives thin slabs, and a convex model stays one hull. The body then collides with static meshes, planes, boxes and other
bodies' hulls through the same tests as any hull, so a decomposed chair can fall onto a mesh floor, slide down a mesh ramp and
hook into another chair's notch. That is how a moving body gets to touch a mesh: through the pieces it is made of.

Pieces overlap a little along the cuts, so the body is a little heavier than its model: the mass you set is shared by the pieces by
volume. Every piece costs a contact test, so keep `maxHulls` low (a chair is 6 to 12) and cut a low-detail copy of the model when
there is one. A model that is a thin shell around a hollow (a bowl, a mug) comes out right only if the pieces are small enough
to follow the hollow; use a higher count for those. Cutting is a tool for building the shapes, not something to do each
frame: do it once when the model loads.

### Mass and inertia

| Method | Description |
|---|---|
| `setMass(float)` | Kilograms, 1 by default. Spread through the shapes by their volume |
| `setDensity(float)` | Sets the mass from a density (kg/m³) and the shapes' volume: water 1000, wood about 600, steel 7850 |
| `getInertia()` / `setInertia(Vec3)` | How hard the body is to spin around each of its own axes, worked out from the shapes. Set your own, or `null` for the worked-out one |
| `getCenterOfMass()` / `setCenterOfMass(Vec3)` | The point the body spins around: the middle of the shapes by volume, or one you choose (a low one makes a body hard to tip) |

Inertia is why a long plank spins easily around its length and hardly at all end over end, and why a rolling ball runs
down a slope at five sevenths of the speed a sliding block would.

### Moving bodies

| Method | Description |
|---|---|
| `setVelocity(Vec3)` / `getVelocity()` | Metres per second |
| `setAngularVelocity(Vec3)` / `getAngularVelocity()` | The spin: an axis whose length is radians per second |
| `applyForce(Vec3)` / `applyForceAtPoint(Vec3, Vec3)` | A push for the **next step only**. Off the centre of mass it also spins the body |
| `applyTorque(Vec3)` | A twist for the next step |
| `applyImpulse(Vec3)` / `applyImpulseAtPoint(Vec3, Vec3)` / `applyTorqueImpulse(Vec3)` | An instant change of velocity or spin: a jump, a hit, an explosion |
| `getPointVelocity(Vec3)` | How fast a point of the body is moving, spin included |
| `setPosition` / `setRotation` | Teleport, keeping the velocity |
| `setLinearDamping(float)` / `setAngularDamping(float)` | Motion dying away by itself, like air resistance (0 and 0.05 by default) |
| `setGravityScale(float)` | 0 floats, 1 falls normally, negative rises |
| `lockPosition(x, y, z)` / `lockRotation(x, y, z)` | Stop movement or turning along world axes: lock X and Z rotation to keep a character upright, lock Z position for a game on a plane |
| `getKineticEnergy()` | Energy of motion, in joules |

Forces are cleared after every step, so a steady push belongs in the step handler, which runs before each step:

```java nocompile
world.setStepHandler((w, dt) -> {
    if (thrusting) rocket.applyForce(rocket.getRotation().rotate(Vec3.UP).mul(4000f));
    balloon.applyForce(new Vec3(0, 30f, 0));          // buoyancy
});
```

Impulses change the velocity at once, so they are safe to apply from anywhere, such as a click or a key press:

```java nocompile
if (onGround && jumpPressed) player.applyImpulse(new Vec3(0, 5f * player.getMass(), 0));   // +5 m/s upward
```

### Friction and bounce

Each body has a `PhysicsMaterial(friction, restitution)`: `setMaterial(...)`, or `setFriction(float)` and
`setRestitution(float)` to change one part. **Friction** resists sliding: a box stays on a slope while the friction is
above the slope's tangent (0.36 for 20°). **Restitution** is the share of the speed that comes back in a bounce: 0 stops
dead, 1 bounces back as high as it fell. When two bodies touch, their frictions combine as the square root of the
product and the higher restitution wins.

| Preset | Friction | Restitution |
|---|---|---|
| `PhysicsMaterial.DEFAULT` | 0.5 | 0 |
| `PhysicsMaterial.ICE` | 0.02 | 0.05 |
| `PhysicsMaterial.WOOD` | 0.5 | 0.2 |
| `PhysicsMaterial.METAL` | 0.35 | 0.1 |
| `PhysicsMaterial.RUBBER` | 0.9 | 0.7 |

Collisions slower than 0.5 m/s don't bounce, so resting bodies stay still.

### Sleep

A body that has barely moved for half a second falls **asleep**, together with everything it rests on or against. A
sleeping body costs almost nothing, so a settled pile of a thousand crates is cheap. It wakes when something hits it,
when you push, move or change it, or when what it rests on is removed. `isSleeping()`, `wakeUp()` and `sleep()` do what
they say; `setSleepingAllowed(false)` keeps a body awake, for a player you move by setting its velocity every frame.

### Collision layers

Each body has a **layer** (the groups it is in, as bits, 1 by default) and a **mask** (the groups it collides with,
all by default). Two bodies collide only if each one's layer shares a bit with the other's mask:

```java nocompile
static final int WORLD = 1, PLAYERS = 2, PICKUPS = 4;
player.setCollisionLayer(PLAYERS).setCollisionMask(WORLD | PICKUPS);   // players walk through each other
```

## Queries

```java nocompile
// What was clicked:
Ray ray = scene.camera().screenRay(x, y, view.getWidth(), view.getHeight());
RaycastHit hit = world.raycast(ray, 100f);
if (hit != null) hit.body().applyImpulseAtPoint(ray.direction().mul(50f), hit.point());

// Is the player standing on something? A short ray down from the feet:
boolean onGround = world.raycast(new Ray(player.getPosition(), new Vec3(0, -1, 0)), 1.0f, WORLD) != null;

// Who an explosion reaches:
for (RigidBody body : world.overlapSphere(center, 5f)) { ... }
```

| Method | Description |
|---|---|
| `raycast(Ray, maxDistance)` / `raycast(Ray, maxDistance, mask)` | The nearest hit, or null: the `RaycastHit` has the body, the shape, the point, the surface normal, the distance and, for a mesh, which triangle |
| `raycastAll(Ray, maxDistance, mask)` | Every body hit, nearest first |
| `overlapSphere(center, radius)` / `overlapBox(center, halfExtents, rotation)` / `overlap(shape, position, rotation, mask)` | The bodies a shape overlaps |
| `setQueriesHitTriggers(boolean)` | Whether queries see triggers (off by default) |

A ray that starts inside a body doesn't hit that body, so a ray from a character's centre finds the ground, not the
character. Distances are in units of the ray direction's length, so give it a direction of length 1 (`Ray.of`) to
measure in metres. The world keeps its bodies in a tree of boxes, so a query only visits the branches it passes
through and then tests those shapes exactly: a raycast in a level of 20,000 bodies takes about a microsecond.

## Contacts, events and triggers

`world.getContacts()` lists the pairs of shapes touching, or about to: each `Contact` has its two bodies and shapes, the
normal from body A to body B, up to four `ContactPoint`s (position, depth, impulse) and `getImpulse()`, how hard the
bodies pushed on each other in the last step. Against a mesh, the triangles facing the same way make one contact, and
`getTriangle()` names one of them; a body on a bumpy mesh can have a contact for each slope it rests on.

A `ContactListener` hears changes. Give one to the world for every body, or to a body (`setContactListener`) for its own:

```java nocompile
world.addContactListener(new ContactListener() {
    @Override
    public void contactBegin(Contact contact) {
        if (contact.getImpulse() > 20f) playThud(contact.getPoints().getFirst().position());
    }

    @Override
    public void triggerEnter(RigidBody trigger, RigidBody other) {
        if (trigger == finishLine && other == player) win();
    }
});
```

| Method | When |
|---|---|
| `contactBegin(Contact)` | Two bodies started touching |
| `contactStay(Contact)` | Every later step while they touch |
| `contactEnd(RigidBody, RigidBody)` | They stopped touching, or one left the world |
| `triggerEnter(trigger, other)` / `triggerExit(trigger, other)` | A body entered or left a trigger |

Events come once per pair of bodies, after each step, and only for real touches. Sleeping bodies keep touching, so a
crate resting on the floor doesn't end its contact when it falls asleep. It is safe to add or remove bodies from a
listener.

A **trigger** (`body.setTrigger(true)`) reports who enters and leaves but pushes nothing: a finish line, a pickup, a
zone of water. A static trigger is a fixed zone; a kinematic one can move. Dynamic and kinematic bodies set triggers
off, characters included, and a kinematic body standing still in a trigger stays in it.

## Joints

A joint holds two bodies together, or one body to a point in the world (pass `null` for the second body):

```java nocompile
world.add(door);
world.addJoint(new HingeJoint(door, null, hingePoint, Vec3.UP).setLimits(0f, (float) Math.PI / 2));

world.add(lamp);
world.addJoint(new BallJoint(lamp, null, ceilingPoint));                 // swings freely from the ceiling

world.addJoint(new DistanceJoint(bucket, handle, null, hook).setMinLength(0f));  // a rope: slack, never stretches
```

| Joint | What it does |
|---|---|
| `BallJoint(a, b, anchor)` | Pins the bodies together at a point; they turn freely around it. Chains, pendulums, hitches |
| `BallJoint(a, b, anchor, axis)` | The same with an axis along the limb, for [limits](#cone-and-twist-limits): a shoulder, a hip or a neck |
| `HingeJoint(a, b, anchor, axis)` | Turns around one axis only. Doors, wheels, levers. `setLimits(lower, upper)` in radians stops it, `setMotor(speed, maxTorque)` drives it; `getAngle()`, `getAngularSpeed()` |
| `SliderJoint(a, b, anchor, axis)` | Slides along one axis without turning. Drawers, pistons, lifts. `setLimits` in metres, `setMotor(speed, maxForce)`; `getTranslation()`, `getSpeed()` |
| `DistanceJoint(a, anchorA, b, anchorB)` | Keeps two points apart by a length: a rod by default, a rope with `setMinLength(0)`, a spring with `setSpring(frequency, damping)`, a bungee with both and `setMaxLength` |
| `FixedJoint(a, b)` | Welds the bodies as they are. For parts that never come apart one body with several shapes is cheaper; a weld suits parts that should break off |

Anchors and axes are in world space and are read against where the bodies are when the joint is added, so add and place
the bodies first. Angles and translations count from there: a hinge starts at angle 0. For a body held to the world,
angles, speeds and the reaction force are that body's own.

### Cone and twist limits

A ball joint turns freely unless it is given limits, which make it a shoulder, a hip or a neck. Give it the direction of
the limb (the joint's **axis**, pointing from the joint along body B) and two limits:

```java nocompile
BallJoint shoulder = new BallJoint(arm, torso, shoulderPoint, armDirection)
        .setSwingLimit((float) Math.toRadians(80))                       // the arm leans at most 80 degrees from where it was
        .setTwistLimit((float) Math.toRadians(-30), (float) Math.toRadians(45));  // and turns around its own length -30 to 45
world.addJoint(shoulder);
```

| Method | Description |
|---|---|
| `setSwingLimit(radians)` | How far body B's axis may lean from body A's, in a cone around it (0 to pi) |
| `setTwistLimit(lower, upper)` | How far B may turn around its axis, against where it started |
| `clearLimits()` | Frees the joint again |
| `getSwing()`, `getTwist()` | Where it is now |

The stops act around the pivot, so a limb meets its limit without bouncing. An elbow or a knee is a hinge, not a ball
joint with a narrow cone: use `HingeJoint` with limits. A ragdoll is bodies for the bones, ball joints with limits at the
shoulders, hips and neck, and hinges with limits at the elbows and knees.

Every joint has:

| Method | Description |
|---|---|
| `setCollideConnected(boolean)` | Whether the two joined bodies collide with each other (off: joined parts usually overlap where they meet) |
| `setBreakForce(newtons)` / `setBreakTorque(newtonMetres)` | Breaks the joint when it is pulled or twisted harder; the world drops it. A 10 kg lamp pulls with about 100 N |
| `setBreakHandler(Consumer<Joint>)` / `isBroken()` | Hears the break, after the world has dropped the joint |
| `getReactionForce()` / `getReactionTorque()` | What holding the bodies together took in the last step, on body B |

A motor with a speed of 0 holds the joint still against whatever pushes it, up to its torque or force: a stiff hinge, a
brake. A spring's frequency is how many times a second it bounces, whatever the masses, and its damping ratio how quickly
it stops (0 bounces forever, 1 settles without overshooting).

Joined bodies sleep and wake together, like a stack. Ball joints, hinges, sliders and fixed joints (the pivot, the hinge's axis,
the slider's rail, the weld, and the stops and motors) are solved exactly, all the joints of a connected tree at once, so a
chain, a ragdoll or a door on a heavy block doesn't stretch whatever the masses: a 100 kg weight on six 0.5 kg links
(200 to 1) holds the chain at its length, a hinge stop or motor holds a 400 kg load on a 0.2 kg arm, and the time grows in
proportion to the number of joints (a stop or motor that is being hit or let go of costs a few more solves that step; a
60-link whip with stops and a motor takes about twice as long as one without). Joints that close
a loop of bodies, ropes and springs are solved a pass at a time with
the contacts, so keep the masses behind those within about 10 to 1, or use [substeps](#substeps). Past about 600 to 1 even a tree needs them: a taut chain whips faster than a
step can follow (2 substeps hold 2000 to 1, 4 hold 10000 to 1). Links should also be about as long as the gap they span; a small ball with joints far from it acts much
lighter at them than its mass.

## Characters

A `CharacterController` walks a character around: a standing capsule that goes where it's told rather than where it's
pushed. It slides along walls, walks up slopes and steps, stays on the ground going down them, falls, jumps, rides
moving platforms and pushes loose objects out of its way.

```java nocompile
CharacterController player = new CharacterController(playerNode, 0.35f, 1.8f);  // radius, height
world.addCharacter(player);

// Every frame, from the input:
player.setWalkVelocity(forward.mul(ahead).add(right.mul(side)).mul(running ? 7f : 3.5f));
if (input.isKeyJustPressed(GLFW_KEY_SPACE)) player.jump(5f);
```

The node's origin is the character's feet and the capsule stands on it along the node's own +Y axis, which is the world's
unless you change it. Turn a child node (the model) to face where it walks rather than the node itself, which the
character moves. The walk velocity keeps applying until changed, so set it every frame; only the part across up counts
(X and Z, for the usual up).

| Method | Description |
|---|---|
| `setWalkVelocity(Vec3)` | Where to walk, in metres per second |
| `setUp(Vec3)` / `getUp()` | Which way is up for it, [below](#walking-on-a-wall) |
| `jump(speed)` | Jumps if on the ground; returns whether it did. 5 m/s rises about 1.3 m |
| `setVelocity(Vec3)` | A knock back: carries on in the air until it lands |
| `setPosition(Vec3)` / `getPosition()` | Teleports the feet / where they are |
| `isGrounded()`, `getGroundNormal()`, `getGroundBody()` | What it stands on after the last step |
| `isTouchingWall()`, `isTouchingCeiling()` | Whether it walked into something too steep, or bumped its head |
| `getVelocity()` | How fast it actually went in the last step |
| `setSlopeLimit(degrees)` | The steepest slope it walks up (45); steeper ground is a wall it slides down |
| `setStepHeight(metres)` | The tallest step it walks up, and the furthest it drops to stay on the ground going down (0.3) |
| `setAirAcceleration(m/s²)` | How quickly walking steers it in the air (instant by default). With no walk velocity it keeps going as it was |
| `setMass(kilograms)` | How hard it pushes loose bodies and weighs on what it stands on (80) |
| `setGravityScale(float)` | How much of the world's gravity pulls it (1) |
| `getBody()` | Its kinematic body, for collision layers, a contact listener or user data |

It moves in the world's fixed steps, before the bodies, and its node is placed between steps like theirs. Walking into a
crate shoves it as an 80 kg character would: a 20 kg crate moves off almost at walking speed, a 2000 kg one hardly
moves. Standing on a loose body puts the character's weight on it, so a rope bridge sags and a seesaw tips. Platforms
carry it, kinematic or dynamic, and jumping off one keeps the platform's speed.

Inside it is a kinematic body, so rays and triggers find it and objects falling on it land on it. A loose body that hits
it knocks it sideways: what the kinematic body took from the collision, divided by the character's mass, becomes speed
for the character, which fades in about an eighth of a second on the ground (and slowly in the air). A thrown crate pushes
it back; a crate pressed against it by a steady force drives it along at a speed that falls as its mass rises. Landing on
it, or standing under it, is not a knock. A mass of 0 takes no knocks (and pushes nothing).

### Walking on a wall

Up is wherever the character's node's +Y axis points. `player.setUp(direction)` turns the character by the shortest turn
to stand along `direction`, keeping the way it faces:

```java nocompile
world.setGravity(new Vec3(9.81f, 0f, 0f));        // pulls towards +X
player.setUp(new Vec3(-1f, 0f, 0f));              // so up is -X: it stands on a wall at x = 5
```

Everything is measured against up: the slope limit, steps, jumping and the walk velocity. Gravity pulls it by as much of
the world's gravity as points opposite to up, so match the two, or scale the character's gravity. To walk round a small
planet, call `setUp` each frame with the direction away from its centre (and set the gravity the same way, in a step
handler). Turn the node itself and the character's up follows it.

## Scene files

A [scene file](scene-files.md#physics) can say which nodes are bodies or characters, with their colliders and settings,
and which joints join them. `Scene3DFile.load` builds the world and gives it to the scene, and `findBody`,
`findCharacter` and `findJoint` find the parts by name, so a level made in the editor runs as it was set up.

## Seeing the colliders

A **`ColliderView`** draws what the world collides with, as faint see-through surfaces outlined with real lines, where
they really are: the way to find a collider that doesn't fit its mesh, a body that sleeps when it shouldn't, or a trigger
in the wrong place.

```java nocompile
ColliderView colliders = new ColliderView(world);
scene.add(colliders.getNode());
colliders.setVisible(false);             // and true again from a key or a check box
colliders.setShowStatic(false);          // hide the level's own colliders
```

The colour says what a body is: green for an awake dynamic body, grey-blue for a sleeping one, blue for kinematic, light
grey for static, yellow for a trigger and pink for a character. The outlines are line segments (a [line mesh](scenes.md#mesh), one pixel wide): the twelve edges of a box, three
circles round a ball, the rings and rods of a capsule, every edge of a hull, the real edges of a mesh (not the seams
between its flat triangles) and a grid for a plane. Red dots mark where bodies touch, with a short line along the push (`setShowContacts`); orange dots
mark where joints hold their bodies, with a line between them for a rope or spring and along a hinge's or slider's axis
(`setShowJoints`). The view follows the world each time it updates, smoothed between steps like the bodies' nodes;
`refresh()` follows it while the world is paused, and `dispose()` takes it out of the scene. An endless plane is drawn
as a 100 m square around its body. A hidden view costs nothing, and the view's nodes are never picked or shadowed.

The Physics, Terrain and Playground demos have it on a check box (or the V key).

## How it works

Each step: the step handler runs; the **broadphase** asks the world's tree of boxes what each moving body might touch
(bodies at rest don't ask, so a big level with a few things moving costs a few small queries); the **narrowphase** tests each pair of shapes exactly (separating axes and face clipping
for boxes and hulls, closest points for spheres and capsules, found by GJK against hulls and triangles; a mesh's tree
gives the triangles near each shape), giving up to four contact points; gravity and forces change the
velocities; the **solver** makes ten passes over the joints and contacts, applying impulses so joined bodies don't
move apart, touching bodies don't move into each other and friction resists sliding; bounces are added; overlaps are
pushed apart without adding speed; the velocities move the bodies; and the joints pull their bodies back together, since
turning in a straight-line step moves an anchor a little off. Contacts and joints remember last step's impulses, which
is what lets stacks settle and chains hang still. Characters move first, each step: they push out of whatever they
walk into, in pieces no longer than half their radius, so they can't pass through thin walls.

Shapes a little apart already get a contact, sized by how far the bodies could move in the step. That stops falling
bodies at the surface and keeps a ball from passing through a thin wall. For a body that moves more in a step than it is
thick, the end of the step is also checked by a sweep (see [fast bodies](#fast-bodies)): conservative advancement, which
asks GJK for the gap and divides it by the fastest the body's points can close it.

## Limits

- Meshes can't move freely: a dynamic body needs hulls (or boxes, spheres and capsules) instead, which
  [`ConvexDecomposition`](#concave-moving-bodies) makes from a mesh.
- A mesh joins its corners only where they are at exactly the same place unless you give it a weld distance
  (`new MeshShape(positions, indices, 0.002f)`, `MeshShape.of(node, 0.002f)`, or the collider's Weld in a scene file);
  a level whose pieces merely nearly meet keeps real edges along the gaps, which a sliding box can catch on, until welded.
- Joints that close a loop of bodies, ropes and springs are solved a pass
  at a time with the contacts, so the masses behind them should stay within about 10 to 1 at one step ([substeps](#substeps)
  take that further). Trees of ball joints, hinges, sliders and fixed joints, stops and motors included, are solved exactly, up to about 600 to 1 in one step.
- A character isn't turned by a turning platform (it is carried round, facing the same way).
- A fast body is [swept](#fast-bodies) against what stands still, and stopped at the surface. What happens next is the
  solver's, and a thin sheet that hits a thin wall on a corner picks up so much spin from the hit that it can
  swing through the wall in the following step. This is a known bug, not a design limit. A sheet 80 cm across and 2 cm thick
  got through from 60 m/s up, at turns of about 0.6 to 1.3 rad; flat on and edge on it was stopped at 600 m/s. Substeps shrink the step and help; so does a thicker wall. Bodies
  moving at each other are not swept, only the moving one against the still.
- A mesh can't be on a dynamic body, so mesh against mesh and mesh against plane are never tested: neither moves.
  A moving body that is concave is [cut into hulls](#concave-moving-bodies), which do meet meshes and planes.
- Gyroscopic effects are left out: a spinning body keeps its angular velocity rather than its angular momentum, which
  only differs for an unevenly shaped body spinning freely.
- One thread.
