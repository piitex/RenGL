package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.physics.BallJoint;
import me.piitex.engine.render3d.physics.BodyType;
import me.piitex.engine.render3d.physics.BoxShape;
import me.piitex.engine.render3d.physics.CapsuleShape;
import me.piitex.engine.render3d.physics.CharacterController;
import me.piitex.engine.render3d.physics.ConvexHullShape;
import me.piitex.engine.render3d.physics.DistanceJoint;
import me.piitex.engine.render3d.physics.FixedJoint;
import me.piitex.engine.render3d.physics.HingeJoint;
import me.piitex.engine.render3d.physics.Joint;
import me.piitex.engine.render3d.physics.MeshShape;
import me.piitex.engine.render3d.physics.PhysicsMaterial;
import me.piitex.engine.render3d.physics.PhysicsWorld;
import me.piitex.engine.render3d.physics.RigidBody;
import me.piitex.engine.render3d.physics.SliderJoint;
import me.piitex.engine.render3d.physics.SphereShape;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.file.SceneMeta.NodeInfo;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The physics a scene file gives a scene: each node's body settings, the world's, and the joints, kept beside the parts
 * as {@link SceneMeta} keeps the rest, and the {@link PhysicsWorld} built from them.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
final class ScenePhysics {
    private static final Logger log = LogManager.getLogger("Engine");
    static final Vec3 DEFAULT_GRAVITY = new Vec3(0f, -9.81f, 0f);
    /** How thick the box a plane collides as is, under its surface. */
    private static final float PLANE_THICKNESS = 0.02f;

    /** A node's body settings. */
    static final class BodyInfo {
        BodyKind kind = BodyKind.NONE;
        ColliderKind collider = ColliderKind.AUTO;
        Vec3 size = Vec3.ONE, offset = Vec3.ZERO;
        float radius = 0.5f, height = 2f, weld;
        float mass = 1f, friction = PhysicsMaterial.DEFAULT.friction(), bounce = PhysicsMaterial.DEFAULT.restitution();
        float linearDamping = 0f, angularDamping = 0.05f, gravityScale = 1f;
        boolean trigger, canSleep = true;
        int layer = 1, mask = -1;
        float characterRadius = 0.3f, characterHeight = 1.8f, characterMass = 80f, slopeLimit = 45f, stepHeight = 0.3f;

        boolean rigid() {
            return kind == BodyKind.STATIC || kind == BodyKind.DYNAMIC || kind == BodyKind.KINEMATIC;
        }
    }

    /** The world's settings. */
    static final class WorldInfo {
        Vec3 gravity = DEFAULT_GRAVITY;
        int velocityIterations = 10;
        int substeps = 1;
    }

    /** What a build made, so a node's body and a joint can be found by name. */
    static final class Built {
        final Map<Node3D, RigidBody> bodies = new IdentityHashMap<>();
        final Map<Node3D, CharacterController> characters = new IdentityHashMap<>();
        final Map<String, Joint> joints = new HashMap<>();
    }

    private static final Map<Node3D, BodyInfo> BODIES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Scene3D, WorldInfo> WORLDS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Scene3D, List<SceneJoint>> JOINTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<PhysicsWorld, Built> BUILT = Collections.synchronizedMap(new WeakHashMap<>());

    private ScenePhysics() {
    }

    static BodyInfo body(Node3D node) {
        return BODIES.computeIfAbsent(node, n -> new BodyInfo());
    }

    static BodyInfo peek(Node3D node) {
        return BODIES.get(node);
    }

    static WorldInfo world(Scene3D scene) {
        return WORLDS.computeIfAbsent(scene, s -> new WorldInfo());
    }

    static List<SceneJoint> joints(Scene3D scene) {
        return JOINTS.computeIfAbsent(scene, s -> new ArrayList<>());
    }

    static Built built(PhysicsWorld world) {
        return world == null ? null : BUILT.get(world);
    }

    // ---- Building ---------------------------------------------------------------------------------------------------

    /**
     * A new world with a body for each node that has one, where the node is now, and the joints between them. Parts
     * that can't be made (a joint naming a node that isn't there, a model with no meshes to collide as) are left out
     * and logged, so a scene with a mistake in it still loads.
     */
    static PhysicsWorld build(Scene3D scene) {
        WorldInfo settings = world(scene);
        PhysicsWorld world = new PhysicsWorld().setGravity(settings.gravity).setVelocityIterations(Math.max(1, settings.velocityIterations)).setSubsteps(settings.substeps);
        Built built = new Built();
        addBodies(scene.root(), world, built);
        for (SceneJoint spec : joints(scene)) {
            try {
                Joint joint = joint(scene, spec, built);
                if (joint == null) continue;
                world.addJoint(joint);
                if (!spec.name.isEmpty()) built.joints.putIfAbsent(spec.name, joint);
            } catch (RuntimeException e) {
                log.warn("Left out the joint {}: {}", spec, e.getMessage());
            }
        }
        BUILT.put(world, built);
        return world;
    }

    private static void addBodies(Node3D node, PhysicsWorld world, Built built) {
        BodyInfo info = node.getParent() == null ? null : peek(node);
        if (info != null && info.kind != BodyKind.NONE) {
            try {
                if (info.kind == BodyKind.CHARACTER) {
                    CharacterController character = new CharacterController(node, Math.max(0.01f, info.characterRadius), Math.max(0.02f, info.characterHeight));
                    character.setSlopeLimit(info.slopeLimit).setStepHeight(info.stepHeight).setMass(info.characterMass).setGravityScale(info.gravityScale);
                    character.getBody().setCollisionLayer(info.layer).setCollisionMask(info.mask);
                    world.addCharacter(character);
                    built.characters.put(node, character);
                } else {
                    RigidBody body = rigidBody(node, info);
                    world.add(body);
                    built.bodies.put(node, body);
                }
            } catch (RuntimeException e) {
                log.warn("Left out the body of \"{}\": {}", node.getName(), e.getMessage());
            }
        }
        NodeInfo meta = SceneMeta.peek(node);
        for (Node3D child : node.getChildren()) {
            if (meta == null || child != meta.modelRoot) addBodies(child, world, built); // a model's own nodes are the file's
        }
    }

    private static RigidBody rigidBody(Node3D node, BodyInfo info) {
        BodyType type = switch (info.kind) {
            case DYNAMIC -> BodyType.DYNAMIC;
            case KINEMATIC -> BodyType.KINEMATIC;
            default -> BodyType.STATIC;
        };
        RigidBody body = new RigidBody(node, type);
        addCollider(body, node, info);
        if (type == BodyType.DYNAMIC) {
            body.setMass(Math.max(1e-4f, info.mass)).setLinearDamping(info.linearDamping).setAngularDamping(info.angularDamping)
                    .setGravityScale(info.gravityScale).setSleepingAllowed(info.canSleep);
        }
        return body.setMaterial(new PhysicsMaterial(Math.max(0f, info.friction), Math.clamp(info.bounce, 0f, 1f)))
                .setTrigger(info.trigger).setCollisionLayer(info.layer).setCollisionMask(info.mask);
    }

    private static void addCollider(RigidBody body, Node3D node, BodyInfo info) {
        NodeInfo meta = SceneMeta.peek(node);
        Shape drawn = meta == null ? Shape.NONE : meta.shape;
        boolean moves = info.kind == BodyKind.DYNAMIC;
        ColliderKind kind = info.collider;
        if (kind == ColliderKind.AUTO) {
            switch (drawn) {
                case CUBE -> {
                    float h = meta.size / 2f;
                    body.addShape(new BoxShape(h, h, h));
                    return;
                }
                case SPHERE -> {
                    body.addShape(new SphereShape(meta.radius));
                    return;
                }
                case PLANE -> {
                    // A sheet has no thickness to collide with; a thin box under it stands in, and can move.
                    body.addShape(new BoxShape(meta.width / 2f, PLANE_THICKNESS / 2f, meta.depth / 2f), new Vec3(0f, -PLANE_THICKNESS / 2f, 0f));
                    return;
                }
                case CAPSULE -> {
                    body.addShape(CapsuleShape.ofHeight(Math.max(1e-3f, meta.radius), Math.max(2f * meta.radius, meta.height)));
                    return;
                }
                case CYLINDER, CONE -> kind = ColliderKind.HULL;
                case MODEL, TERRAIN -> kind = moves ? ColliderKind.HULL : ColliderKind.MESH;
                default -> kind = ColliderKind.BOX;
            }
        }
        if (kind == ColliderKind.MESH && moves) {
            log.warn("\"{}\" is dynamic, so it collides as a hull of its mesh rather than the mesh itself", node.getName());
            kind = ColliderKind.HULL;
        }
        switch (kind) {
            case BOX -> body.addShape(new BoxShape(Math.abs(info.size.x()) / 2f, Math.abs(info.size.y()) / 2f, Math.abs(info.size.z()) / 2f), info.offset, Quat.IDENTITY);
            case SPHERE -> body.addShape(new SphereShape(Math.max(1e-3f, info.radius)), info.offset, Quat.IDENTITY);
            case CAPSULE -> body.addShape(CapsuleShape.ofHeight(Math.max(1e-3f, info.radius), Math.max(2f * info.radius, info.height)), info.offset, Quat.IDENTITY);
            case HULL -> body.addShape(ConvexHullShape.of(drawnPoints(node, meta)));
            case MESH -> {
                List<float[]> parts = new ArrayList<>();
                List<int[]> indices = new ArrayList<>();
                drawn(node, meta, parts, indices);
                body.addShape(merge(node, parts, indices, info.weld));
            }
            default -> throw new IllegalStateException();
        }
    }

    /** The corners of what the node draws, in its own space, as x, y, z. */
    private static float[] drawnPoints(Node3D node, NodeInfo meta) {
        List<float[]> parts = new ArrayList<>();
        drawn(node, meta, parts, new ArrayList<>());
        int total = 0;
        for (float[] p : parts) total += p.length;
        if (total == 0) throw new IllegalArgumentException("it draws nothing to make a hull from");
        float[] all = new float[total];
        int at = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, all, at, p.length);
            at += p.length;
        }
        return all;
    }

    /**
     * The meshes the node draws, in its own space: its own, and its model's. Its other children are nodes of the scene,
     * with bodies of their own if they collide.
     */
    private static void drawn(Node3D node, NodeInfo meta, List<float[]> parts, List<int[]> indices) {
        if (node.getMesh() != null) add(node.getMesh(), null, parts, indices);
        if (meta != null && meta.modelRoot != null) collect(meta.modelRoot, meta.modelRoot.localMatrix(), parts, indices);
    }

    private static void collect(Node3D node, Mat4 transform, List<float[]> parts, List<int[]> indices) {
        if (node.getMesh() != null) add(node.getMesh(), transform, parts, indices);
        for (Node3D child : node.getChildren()) collect(child, transform.mul(child.localMatrix()), parts, indices);
    }

    private static void add(Mesh mesh, Mat4 transform, List<float[]> parts, List<int[]> indices) {
        float[] v = mesh.vertices();
        float[] positions = new float[mesh.vertexCount() * 3];
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int o = i * Mesh.FLOATS_PER_VERTEX;
            Vec3 p = new Vec3(v[o], v[o + 1], v[o + 2]);
            if (transform != null) p = transform.transformPoint(p);
            positions[i * 3] = p.x();
            positions[i * 3 + 1] = p.y();
            positions[i * 3 + 2] = p.z();
        }
        parts.add(positions);
        indices.add(mesh.indices());
    }

    private static MeshShape merge(Node3D node, List<float[]> parts, List<int[]> indices, float weld) {
        int corners = 0, total = 0;
        for (int i = 0; i < parts.size(); i++) {
            corners += parts.get(i).length;
            total += indices.get(i).length;
        }
        if (total == 0) throw new IllegalArgumentException("it draws no triangles to collide with");
        float[] positions = new float[corners];
        int[] all = new int[total];
        int at = 0, base = 0, k = 0;
        for (int i = 0; i < parts.size(); i++) {
            System.arraycopy(parts.get(i), 0, positions, at, parts.get(i).length);
            for (int index : indices.get(i)) all[k++] = index + base;
            at += parts.get(i).length;
            base += parts.get(i).length / 3;
        }
        return new MeshShape(positions, all, weld);
    }

    private static Joint joint(Scene3D scene, SceneJoint spec, Built built) {
        RigidBody a = bodyOf(scene, spec.bodyA, built);
        if (a == null) {
            log.warn("Left out the joint {}: no body on a node called \"{}\"", spec, spec.bodyA);
            return null;
        }
        RigidBody b = null;
        if (!spec.bodyB.isEmpty()) {
            b = bodyOf(scene, spec.bodyB, built);
            if (b == null) {
                log.warn("Left out the joint {}: no body on a node called \"{}\"", spec, spec.bodyB);
                return null;
            }
        }
        Joint joint = switch (spec.kind) {
            case BALL -> {
                BallJoint ball = new BallJoint(a, b, spec.anchor, spec.axis);
                if (spec.swingLimited) ball.setSwingLimit((float) Math.toRadians(spec.swing));
                if (spec.limited) ball.setTwistLimit((float) Math.toRadians(spec.lower), (float) Math.toRadians(spec.upper));
                yield ball;
            }
            case HINGE -> {
                HingeJoint hinge = new HingeJoint(a, b, spec.anchor, spec.axis);
                if (spec.limited) hinge.setLimits((float) Math.toRadians(spec.lower), (float) Math.toRadians(spec.upper));
                if (spec.motor) hinge.setMotor((float) Math.toRadians(spec.motorSpeed), spec.motorStrength);
                yield hinge;
            }
            case SLIDER -> {
                SliderJoint slider = new SliderJoint(a, b, spec.anchor, spec.axis);
                if (spec.limited) slider.setLimits(spec.lower, spec.upper);
                if (spec.motor) slider.setMotor(spec.motorSpeed, spec.motorStrength);
                yield slider;
            }
            case DISTANCE -> {
                DistanceJoint link = new DistanceJoint(a, spec.anchor, b, spec.anchorB);
                if (spec.length > 0f) link.setLength(spec.length);
                if (spec.springFrequency > 0f) link.setSpring(spec.springFrequency, spec.springDamping);
                if (spec.rope) link.setMinLength(0f);
                yield link;
            }
            case FIXED -> new FixedJoint(a, b);
        };
        joint.setCollideConnected(spec.collideConnected);
        if (spec.breakForce > 0f) joint.setBreakForce(spec.breakForce);
        return joint;
    }

    private static RigidBody bodyOf(Scene3D scene, String name, Built built) {
        if (name.isEmpty()) return null;
        Node3D node = Scene3DFile.find(scene, name);
        if (node == null) return null;
        RigidBody body = built.bodies.get(node);
        if (body != null) return body;
        CharacterController character = built.characters.get(node);
        return character == null ? null : character.getBody();
    }
}
