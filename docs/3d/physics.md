# Physics

[Documentation](../README.md) › [3D](README.md)

Rigid bodies for 3D scenes, in `me.piitex.engine.render3d.physics`: gravity, velocity, mass and inertia, forces and
impulses, collisions with friction and bounce, stacking, sleeping, raycasts and trigger zones, with loaded meshes as
ground and convex hulls for objects of any shape. It is plain Java with no native library, and like the rest of the 3D
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
hulls) and crates on a hilly terrain (a triangle mesh).

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
| `setVelocityIterations(int)` / `setPositionIterations(int)` | How hard the solver works (10 and 4). 10 holds a stack of ten boxes; 20 holds fifteen |
| `setSleeping(boolean)` | Whether resting bodies may fall asleep (on) |
| `getContacts()` | Every pair of shapes touching or about to, with points and normal |
| `raycast`, `raycastAll`, `overlap`, `overlapSphere`, `overlapBox` | Queries, below |
| `addContactListener(ContactListener)` | Hears contacts begin and end, and triggers entered and left |
| `getTime()` / `getStepCount()` / `getInterpolationAlpha()` | Simulated seconds, steps run, and how far the nodes sit between the last two steps |

### Fixed steps

The world always moves in steps of the same length, whatever the frame rate, so a game behaves the same on a 60 Hz and
a 144 Hz screen. A frame runs as many steps as fit in its time, usually zero, one or two. With interpolation on (the
default), each node is then placed between the last two steps by how far the frame got into the next one. Nodes are
therefore up to one step behind the bodies, which is invisible; read the body (`getPosition()`) for the exact place.

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
few dozen corners is quicker than one of hundreds: build it from a low-detail version of the model when there is one.
For an object with dents that matter (a chair, an L-shaped beam), give the body several hulls or boxes.

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
measure in metres. Queries look at every body's box first and then test the shapes exactly; that is fine for a few
hundred bodies and a few rays a frame.

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
zone of water. A static trigger is a fixed zone; a kinematic one can move.

## How it works

Each step: the step handler runs; the **broadphase** finds bodies whose boxes overlap (sweep and prune along the axis
the bodies spread most along); the **narrowphase** tests each pair of shapes exactly (separating axes and face clipping
for boxes and hulls, closest points for spheres and capsules, found by GJK against hulls and triangles; a mesh's tree
gives the triangles near each shape), giving up to four contact points; gravity and forces change the
velocities; the **solver** makes ten passes over the contacts, applying impulses so bodies don't move into each other
and friction resists sliding; bounces are added; overlaps are pushed apart without adding speed; and the velocities
move the bodies. Contacts remember last step's impulses, which is what lets stacks settle.

Shapes a little apart already get a contact, sized by how far the bodies could move in the step. That stops falling
bodies at the surface and keeps a fast ball from passing through a thin wall, without a separate sweep test.

## Limits

- Meshes can't move freely: a dynamic body needs hulls (or boxes, spheres and capsules) instead.
- A mesh joins its corners only where they are at exactly the same place; a level whose pieces merely nearly meet keeps
  real edges along the gaps, which a sliding box can catch on.
- No joints (hinges, ropes, ragdolls) and no character controller yet.
- No continuous collision against spinning bodies: speculative contacts cover fast straight-line motion, not a long
  thin stick spinning very fast.
- Queries test every body's bounding box, with no tree.
- Gyroscopic effects are left out: a spinning body keeps its angular velocity rather than its angular momentum, which
  only differs for an unevenly shaped body spinning freely.
- One thread.
