# PhysX

[Documentation](../README.md) › [3D](README.md)

The `rengl-physx` module steps a [`PhysicsWorld`](physics.md) with **NVIDIA PhysX 5** in place of the built-in solver.
The game keeps talking to the same `PhysicsWorld`, `RigidBody`, joints, `CharacterController`, queries and listeners;
only what moves the bodies changes. So an existing game switches with one line and can switch back.

```java nocompile
if (PhysX.isAvailable()) PhysX.install();   // once, at startup: every PhysicsWorld made from now on steps with PhysX

PhysicsWorld world = new PhysicsWorld();    // a PhysX world, as is the world of a loaded scene file
scene.setPhysics(world);
world.add(new RigidBody(crate).addShape(new BoxShape(0.5f, 0.5f, 0.5f)));
```

Or one world at a time:

```java nocompile
PhysicsWorld world = new PhysicsWorld().setBackend(new PhysXBackend());
...
world.close();                              // PhysX memory is outside the Java heap; free it when the world is done
world.setBackend(null);                     // or hand the same bodies back to the built-in solver
```

## Adding it

`rengl-physx` is not part of `RenGL`, the all-in-one artifact. Add it and the PhysX natives for each platform the
program ships on. The natives come from [physx-jni](https://github.com/fabmax/physx-jni), the binding this module is
built on, about 3 MB a platform:

```xml
<dependency>
    <groupId>io.github.piitex</groupId>
    <artifactId>rengl-physx</artifactId>
</dependency>
<dependency>
    <groupId>de.fabmax</groupId>
    <artifactId>physx-jni</artifactId>
    <version>2.9.0</version>
    <classifier>natives-windows</classifier>   <!-- natives-linux, natives-macos, natives-macos-arm64 -->
    <scope>runtime</scope>
</dependency>
```

There are natives for Windows, Linux and macOS on x86-64 and for macOS on Apple silicon. There are none for Windows or
Linux on arm64: there `PhysX.isAvailable()` is false (`PhysX.getFailure()` says why) and the built-in solver is what a
game falls back to, which is why the first example asks before installing.

| `PhysX` method | Description |
|---|---|
| `isAvailable()` / `getFailure()` | Whether the native library loaded, and why not |
| `install()` / `uninstall()` | Makes PhysX the solver of every world created afterwards, or stops doing so |
| `setThreads(int)` | Worker threads PhysX steps with, shared by all worlds (cores minus one, up to 8). Before the first world |
| `setQuiet(boolean)` | Keeps PhysX's own warnings off the error stream |
| `version()` | The PhysX version |

## What stays and what changes

The world still does these itself, the same code as without PhysX: fixed steps and node interpolation, the step handler,
[characters](physics.md#characters), [raycasts and overlap queries](physics.md#queries) (against where PhysX left the
bodies), the [collider view](physics.md#seeing-the-colliders), and calling the
[contact listeners](physics.md#contacts-events-and-triggers).

PhysX does these: finding contacts, solving them and the joints, sleeping, continuous collision, moving the bodies.

| In the world | With PhysX |
|---|---|
| Gravity, fixed step, substeps, sleeping on or off | The same |
| `setVelocityIterations(n)` | PhysX's solver passes per body (its position iterations); it uses the TGS solver |
| `setPositionIterations`, `setSolverThreads` | Not used. Threads are `PhysX.setThreads` |
| Shapes | Sphere, box, capsule and plane as PhysX's own; a `ConvexHullShape` and a `MeshShape` are cooked once and shared by every body that uses them |
| Mass, inertia, centre of mass and their overrides | The same: mass spread evenly through the shapes |
| Friction and restitution | Combined as the built-in solver does (root of the product; the larger) |
| Collision layers and masks | The same rule: both bodies have to agree. Up to 32 different layer and mask pairs in one world |
| Triggers | PhysX triggers. A `MeshShape` can't be one and is left out |
| `setContinuous(true)` | PhysX's swept continuous collision, which also covers two moving bodies |
| Locked axes, damping, gravity scale, `setSleepingAllowed` | The same |
| Kinematic bodies | Moved by their velocity as before, as PhysX kinematic targets, so they push what they meet |
| Hinge | A PhysX revolute joint, with limits and motor |
| Slider, ball | PhysX six-axis joints: travel limits and a motor for the slider, swing and twist limits for the ball |
| Distance joint | A PhysX distance joint: a rod, a rope, or a spring |
| Fixed joint | A PhysX fixed joint |
| Breaking, `setCollideConnected`, `getReactionForce` / `getReactionTorque` | The same |
| `getContacts()`, `Contact` in listeners | Filled from PhysX's contact reports, up to four points a pair, deepest first |

Changes a game makes between steps reach PhysX at the next step: moving or turning a body, setting its velocity,
forces and impulses, waking and sleeping, new shapes, a new type or material, and a joint's limits or motor.

## Differences to know about

- A stop on a hinge, slider or ball joint takes hold once it is passed, so a fast swing overshoots a limit by up to a
  step's travel before it is brought back.
- A spring `DistanceJoint` holds its length with the spring alone; its own shortest and longest lengths are not applied.
- `HingeJoint.getMotorTorque()` and `SliderJoint.getMotorForce()` read 0.
- A body with a `PlaneShape` is a PhysX static actor even when it is kinematic; it still moves when told to.
- A trigger's `Contact` has one point and no real normal; triggers only say who entered and left.
- Results differ from the built-in solver in the details: it is another solver. A pile settles to the same place, not
  the same last millimetre.
- The built-in solver gives the same result on every machine and thread count. PhysX with worker threads does not
  promise that; `PhysX.setThreads(0)` is the closest to it.
- GPU PhysX is not available: the binding has not carried it since PhysX 5.6.

## Speed

Reading every lasting contact back each step is the larger part of what the module itself costs. A game that only
listens for contacts beginning and ending can turn it off:

```java nocompile
world.setBackend(new PhysXBackend().setContactUpdates(false));   // a contact keeps the points it began with
```

3000 boxes dropped into one pile, 300 steps of 1/60 s, on a 12-core, 24-thread desktop: the built-in solver took 19.5 ms a step,
PhysX 7.0 ms, and PhysX without contact updates 3.4 ms.

## Writing another backend

`PhysXBackend` is an implementation of `PhysicsBackend`, an interface in `rengl-3d`. A backend is told when bodies and
joints come and go, steps the world when asked, and writes results back through `PhysicsBridge` (`place` for a body,
`contact` for a touching pair, `reaction` for a joint). Another engine can be put behind the same world that way.

## Licenses

PhysX is NVIDIA's, under the BSD 3-Clause license; physx-jni is under the MIT license. Their natives jar also contains
V-HACD (BSD 3-Clause). A program that ships the natives ships those notices with them: see
[licenses/physx-jni-NOTICE.md](../../licenses/physx-jni-NOTICE.md).

## Demo

`PhysicsDemo` runs on PhysX with `-Drengl.demo.physics=physx`; its status line then names the solver.
