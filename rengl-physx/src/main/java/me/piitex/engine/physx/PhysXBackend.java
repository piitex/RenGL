package me.piitex.engine.physx;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.physics.BodyShape;
import me.piitex.engine.render3d.physics.BodyType;
import me.piitex.engine.render3d.physics.BoxShape;
import me.piitex.engine.render3d.physics.CapsuleShape;
import me.piitex.engine.render3d.physics.Contact;
import me.piitex.engine.render3d.physics.ConvexHullShape;
import me.piitex.engine.render3d.physics.Joint;
import me.piitex.engine.render3d.physics.MeshShape;
import me.piitex.engine.render3d.physics.PhysicsBackend;
import me.piitex.engine.render3d.physics.PhysicsBridge;
import me.piitex.engine.render3d.physics.PhysicsMaterial;
import me.piitex.engine.render3d.physics.PhysicsWorld;
import me.piitex.engine.render3d.physics.PlaneShape;
import me.piitex.engine.render3d.physics.RigidBody;
import me.piitex.engine.render3d.physics.Shape;
import me.piitex.engine.render3d.physics.SphereShape;
import org.lwjgl.system.MemoryUtil;
import physx.PxTopLevelFunctions;
import physx.common.PxBase;
import physx.common.PxBoundedData;
import physx.common.PxQuat;
import physx.common.PxTransform;
import physx.common.PxVec3;
import physx.cooking.PxConvexFlagEnum;
import physx.cooking.PxConvexFlags;
import physx.cooking.PxConvexMeshDesc;
import physx.cooking.PxTriangleMeshDesc;
import physx.extensions.PxRigidBodyExt;
import physx.geometry.PxBoxGeometry;
import physx.geometry.PxCapsuleGeometry;
import physx.geometry.PxConvexMesh;
import physx.geometry.PxConvexMeshGeometry;
import physx.geometry.PxGeometry;
import physx.geometry.PxPlaneGeometry;
import physx.geometry.PxSphereGeometry;
import physx.geometry.PxTriangleMesh;
import physx.geometry.PxTriangleMeshGeometry;
import physx.physics.PxActor;
import physx.physics.PxActorFlagEnum;
import physx.physics.PxCombineModeEnum;
import physx.physics.PxContactPair;
import physx.physics.PxContactPairFlagEnum;
import physx.physics.PxContactPairFlags;
import physx.physics.PxContactPairHeader;
import physx.physics.PxContactPairPoint;
import physx.physics.PxFilterData;
import physx.physics.PxForceModeEnum;
import physx.physics.PxMaterial;
import physx.physics.PxPairFilteringModeEnum;
import physx.physics.PxPairFlagEnum;
import physx.physics.PxPairFlags;
import physx.physics.PxPhysics;
import physx.physics.PxRigidActor;
import physx.physics.PxRigidBodyFlagEnum;
import physx.physics.PxRigidDynamic;
import physx.physics.PxRigidDynamicLockFlagEnum;
import physx.physics.PxRigidDynamicLockFlags;
import physx.physics.PxScene;
import physx.physics.PxSceneDesc;
import physx.physics.PxSceneFlagEnum;
import physx.physics.PxShape;
import physx.physics.PxShapeFlagEnum;
import physx.physics.PxShapeFlags;
import physx.physics.PxSimulationEventCallbackImpl;
import physx.physics.PxSolverTypeEnum;
import physx.physics.PxTriggerPair;
import physx.physics.PxTriggerPairFlagEnum;
import physx.support.PxArray_PxContactPairPoint;
import physx.support.PxArray_PxU32;
import physx.support.PxArray_PxVec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Steps a {@link PhysicsWorld} with NVIDIA PhysX 5. The world keeps its bodies, joints, characters, queries and
 * events; this keeps a PhysX actor for each body and a PhysX joint for each joint, sends them whatever the game
 * changed since the last step, simulates, and writes the result back.
 * <pre>
 * PhysicsWorld world = new PhysicsWorld().setBackend(new PhysXBackend());
 * ...
 * world.close();   // when the world is done with: PhysX memory is outside the Java heap
 * </pre>
 * How the world's settings carry over: gravity, substeps, sleeping and the fixed step as they are;
 * {@link PhysicsWorld#getVelocityIterations()} becomes PhysX's solver passes per body (its "position iterations") and
 * {@link PhysicsWorld#getPositionIterations()} and {@link PhysicsWorld#getSolverThreads()} are not used (the threads
 * are {@link PhysX#setThreads}). Friction and restitution combine as in the built-in solver. A body with
 * {@link RigidBody#isContinuous()} gets PhysX's swept continuous collision.
 * <p>
 * Not carried over: a {@link MeshShape} on a trigger (PhysX has no mesh triggers; the shape is left out), a spring
 * {@link me.piitex.engine.render3d.physics.DistanceJoint} with its own shortest and longest lengths (the spring holds
 * the length alone), the motor force and torque read-outs of hinges and sliders, and more than 32 different
 * combinations of collision layer and mask in one world.
 */
public final class PhysXBackend implements PhysicsBackend {
    private static final int MAX_POINTS = 64;

    private record PairKey(int a, int shapeA, int b, int shapeB) {
    }

    private static final class Mirror {
        final RigidBody body;
        PxRigidActor actor;
        PxRigidDynamic dynamic; // null when the actor is a PxRigidStatic
        boolean fresh = true;
        PxShape[] shapes = new PxShape[0];
        long[] shapeAddresses = new long[0];
        int[] shapeIndices = new int[0];
        List<BodyShape> source = List.of();
        List<Shape> cooked = new ArrayList<>();
        BodyType type;
        PhysicsMaterial material;
        boolean trigger, continuous, sweeping, canSleep, gravityOff;
        int layer, mask, filterEpoch = -1, locks = -1, iterations = -1;
        float mass, linearDamping = Float.NaN, angularDamping = Float.NaN, sleepThreshold;
        Vec3 inertiaOverride, centerOverride, localCenter;
        Vec3 position, velocity, angular;
        Quat rotation;
        boolean sleeping;

        Mirror(RigidBody body) {
            this.body = body;
        }

        int shapeIndex(long address) {
            for (int i = 0; i < shapeAddresses.length; i++) if (shapeAddresses[i] == address) return shapeIndices[i];
            return -1;
        }
    }

    private static final class Cooked {
        final PxBase mesh;
        int users;

        Cooked(PxBase mesh) {
            this.mesh = mesh;
        }
    }

    private PhysicsWorld world;
    private PxScene scene;
    private PxSimulationEventCallbackImpl events;
    private boolean closed;
    private boolean contactUpdates = true;

    private final Map<RigidBody, Mirror> mirrors = new IdentityHashMap<>();
    private final List<Mirror> all = new ArrayList<>();
    private final Map<Long, Mirror> byActor = new HashMap<>();
    final Map<Joint, PhysXJoints.Link> joints = new IdentityHashMap<>();
    private final Map<PhysicsMaterial, PxMaterial> materials = new HashMap<>();
    private final Map<Shape, Cooked> cooked = new IdentityHashMap<>();
    private final Map<PairKey, Contact> touching = new LinkedHashMap<>();
    private final List<Joint> broken = new ArrayList<>();

    // Each different pair of collision layer and mask is a class; a class's bit goes in word 0 of a shape's filter
    // data and the classes it collides with in word 1, so PhysX's own filter keeps the rule that both bodies must agree.
    private final Map<Long, Integer> classes = new HashMap<>();
    private final List<int[]> classMasks = new ArrayList<>(); // {layer, mask} per class
    private final int[] allowed = new int[32];
    private int filterEpoch;
    private boolean warnedClasses, warnedMeshTrigger;

    private Vec3 gravity;
    private boolean worldSleeps = true;

    // Reused across calls, so a step allocates nothing outside the Java heap.
    private PxVec3 v1, v2;
    private PxQuat q1;
    private PxTransform t1, t2;
    private PxFilterData filter;
    private PxArray_PxContactPairPoint points;
    private PxContactPairPoint pointsBegin;
    private long pointsAddress;
    private static final int POINT_BYTES = 48;

    /**
     * Whether contacts that stay are read again every step (on by default), so {@link Contact#getPoints()} and
     * {@link Contact#getImpulse()} are current in {@code contactStay}. Off, a contact keeps the points it began with,
     * which saves time in scenes with thousands of resting contacts. Set it before bodies are added.
     */
    public PhysXBackend setContactUpdates(boolean updates) {
        contactUpdates = updates;
        filterEpoch++;
        return this;
    }

    public boolean isContactUpdates() {
        return contactUpdates;
    }

    @Override
    public String name() {
        return "PhysX " + PhysX.version();
    }

    @Override
    public void attach(PhysicsWorld world) {
        if (this.world != null || closed) throw new IllegalStateException("A PhysXBackend steps one world; make another for " + world);
        this.world = world;
        PxPhysics physics = PhysX.physics();
        v1 = new PxVec3();
        v2 = new PxVec3();
        q1 = new PxQuat();
        t1 = new PxTransform();
        t2 = new PxTransform();
        filter = new PxFilterData();
        points = new PxArray_PxContactPairPoint(MAX_POINTS);
        pointsBegin = points.begin();
        pointsAddress = pointsBegin.getAddress();
        if (PxContactPairPoint.SIZEOF != POINT_BYTES) throw new IllegalStateException("This PhysX lays out its contact points in " + PxContactPairPoint.SIZEOF + " bytes, not " + POINT_BYTES);

        events = new Events();
        PxSceneDesc desc = new PxSceneDesc(PhysX.scale());
        gravity = world.getGravity();
        desc.setGravity(vec(v1, gravity));
        desc.setCpuDispatcher(PhysX.dispatcher());
        desc.setFilterShader(PxTopLevelFunctions.DefaultFilterShader());
        desc.setSimulationEventCallback(events);
        desc.setSolverType(PxSolverTypeEnum.eTGS);
        // A kinematic body sets off a trigger that stands still, so those pairs have to reach the filter.
        desc.setKineKineFilteringMode(PxPairFilteringModeEnum.eKEEP);
        desc.setStaticKineFilteringMode(PxPairFilteringModeEnum.eKEEP);
        desc.getFlags().raise(PxSceneFlagEnum.eENABLE_CCD);
        scene = physics.createScene(desc);
        desc.destroy();

        for (RigidBody body : world.bodies()) bodyAdded(body);
        for (Joint joint : world.joints()) jointAdded(joint);
    }

    // ---- Bodies ---------------------------------------------------------------------------------------------------

    @Override
    public void bodyAdded(RigidBody body) {
        if (closed || mirrors.containsKey(body)) return;
        Mirror m = new Mirror(body);
        mirrors.put(body, m);
        all.add(m);
        push(m);
    }

    @Override
    public void bodyRemoved(RigidBody body) {
        Mirror m = mirrors.remove(body);
        if (m == null) return;
        all.remove(m);
        destroyActor(m);
        release(m.cooked);
    }

    PxRigidActor actorOf(RigidBody body) {
        Mirror m = mirrors.get(body);
        return m == null ? null : m.actor;
    }

    /**
     * Has PhysX decide again which of the body's neighbours it collides with.
     */
    void refilter(RigidBody body) {
        Mirror m = mirrors.get(body);
        if (m != null && m.actor != null && !m.fresh) scene.resetFiltering(m.actor);
    }

    boolean canMove(RigidBody body) {
        Mirror m = mirrors.get(body);
        return m != null && m.dynamic != null;
    }

    private static boolean hasPlane(RigidBody body) {
        for (BodyShape s : body.getWorldScaledShapes()) if (s.shape() instanceof PlaneShape) return true;
        return false;
    }

    private void createActor(Mirror m) {
        RigidBody b = m.body;
        PxPhysics physics = PhysX.physics();
        pose(t1, b.getPosition(), b.getRotation());
        // PhysX takes a plane only on an actor that never moves by itself.
        if (b.getType() == BodyType.STATIC || hasPlane(b)) {
            m.actor = physics.createRigidStatic(t1);
            m.dynamic = null;
        } else {
            m.dynamic = physics.createRigidDynamic(t1);
            m.actor = m.dynamic;
            m.sleepThreshold = m.dynamic.getSleepThreshold();
        }
        byActor.put(m.actor.getAddress(), m);
        m.fresh = true;
    }

    private void destroyActor(Mirror m) {
        if (m.actor == null) return;
        // PhysX joints die with their actors; the ones on this body are made again when it has a new one.
        for (PhysXJoints.Link link : joints.values()) if (link.touches(m.body)) link.release();
        forget(m.body);
        byActor.remove(m.actor.getAddress());
        scene.removeActor(m.actor);
        m.actor.release();
        m.actor = null;
        m.dynamic = null;
        m.shapes = new PxShape[0];
        m.shapeAddresses = new long[0];
        m.shapeIndices = new int[0];
        m.source = List.of();
    }

    /**
     * Drops the contacts of a body whose shapes are going away; PhysX reports the ones that still touch again.
     */
    private void forget(RigidBody body) {
        int id = PhysicsBridge.id(body);
        touching.keySet().removeIf(key -> key.a() == id || key.b() == id);
    }

    /**
     * Sends PhysX whatever changed on the body since the last step.
     */
    private void push(Mirror m) {
        RigidBody b = m.body;
        BodyType type = b.getType();
        boolean fixed = type == BodyType.STATIC || hasPlane(b);
        if (m.actor != null && fixed != (m.dynamic == null)) destroyActor(m);
        if (m.actor == null) createActor(m);
        boolean fresh = m.fresh;
        PxRigidDynamic dyn = m.dynamic;
        boolean massStale = fresh;

        if (fresh || type != m.type) {
            if (dyn != null) {
                if (type == BodyType.KINEMATIC && m.sweeping) {
                    dyn.setRigidBodyFlag(PxRigidBodyFlagEnum.eENABLE_CCD, false);
                    m.sweeping = false;
                }
                dyn.setRigidBodyFlag(PxRigidBodyFlagEnum.eKINEMATIC, type == BodyType.KINEMATIC);
            }
            m.type = type;
            massStale = true;
            m.filterEpoch = -1;
        }

        List<BodyShape> shapes = b.getWorldScaledShapes();
        boolean reshaped = fresh || !sameShapes(shapes, m.source) || b.isTrigger() != m.trigger || !b.getMaterial().equals(m.material);
        if (reshaped) {
            buildShapes(m, shapes);
            massStale = true;
        }
        m.material = b.getMaterial();
        m.trigger = b.isTrigger();

        boolean sweeps = dyn != null && type == BodyType.DYNAMIC && b.isContinuous();
        if (dyn != null && sweeps != m.sweeping) {
            dyn.setRigidBodyFlag(PxRigidBodyFlagEnum.eENABLE_CCD, sweeps);
            m.sweeping = sweeps;
            m.filterEpoch = -1;
        }
        if (reshaped || m.filterEpoch != filterEpoch || b.getCollisionLayer() != m.layer || b.getCollisionMask() != m.mask
                || b.isContinuous() != m.continuous) {
            m.layer = b.getCollisionLayer();
            m.mask = b.getCollisionMask();
            m.continuous = b.isContinuous();
            int cls = filterClass(m.layer, m.mask); // may move the epoch on
            m.filterEpoch = filterEpoch;
            int flags = PxPairFlagEnum.eNOTIFY_TOUCH_FOUND.value | PxPairFlagEnum.eNOTIFY_TOUCH_LOST.value | PxPairFlagEnum.eNOTIFY_CONTACT_POINTS.value;
            if (contactUpdates) flags |= PxPairFlagEnum.eNOTIFY_TOUCH_PERSISTS.value;
            if (m.continuous) flags |= PxPairFlagEnum.eDETECT_CCD_CONTACT.value;
            filter.setWord0(1 << cls);
            filter.setWord1(allowed[cls]);
            filter.setWord2(flags);
            filter.setWord3(0);
            for (PxShape shape : m.shapes) shape.setSimulationFilterData(filter);
            if (!fresh) scene.resetFiltering(m.actor);
        }

        if (dyn != null) {
            Vec3 inertia = PhysicsBridge.inertiaOverride(b), center = PhysicsBridge.centerOverride(b);
            if (type == BodyType.DYNAMIC && (massStale || b.getMass() != m.mass || !Objects.equals(inertia, m.inertiaOverride)
                    || !Objects.equals(center, m.centerOverride) || !b.getLocalCenterOfMass().equals(m.localCenter))) {
                m.mass = b.getMass();
                m.inertiaOverride = inertia;
                m.centerOverride = center;
                m.localCenter = b.getLocalCenterOfMass();
                applyMass(m);
            }
            if (b.getLinearDamping() != m.linearDamping) dyn.setLinearDamping(m.linearDamping = b.getLinearDamping());
            if (b.getAngularDamping() != m.angularDamping) dyn.setAngularDamping(m.angularDamping = b.getAngularDamping());
            boolean gravityOff = b.getGravityScale() != 1f;
            if (fresh || gravityOff != m.gravityOff) {
                dyn.setActorFlag(PxActorFlagEnum.eDISABLE_GRAVITY, gravityOff);
                m.gravityOff = gravityOff;
            }
            boolean canSleep = b.isSleepingAllowed() && worldSleeps;
            if (fresh || canSleep != m.canSleep) {
                dyn.setSleepThreshold(canSleep ? m.sleepThreshold : 0f);
                m.canSleep = canSleep;
            }
            int locks = locks(b);
            if (locks != m.locks) {
                PxRigidDynamicLockFlags flags = new PxRigidDynamicLockFlags((byte) locks);
                dyn.setRigidDynamicLockFlags(flags);
                flags.destroy();
                m.locks = locks;
            }
            int iterations = Math.clamp(world.getVelocityIterations(), 1, 255);
            if (iterations != m.iterations) {
                dyn.setSolverIterationCounts(iterations, 1);
                m.iterations = iterations;
            }
        }

        Vec3 position = b.getPosition(), velocity = b.getVelocity(), angular = b.getAngularVelocity();
        Quat rotation = b.getRotation();
        boolean moved = fresh || !position.equals(m.position) || !rotation.equals(m.rotation);
        if (moved) {
            pose(t1, position, rotation);
            if (dyn != null && type == BodyType.DYNAMIC) dyn.setGlobalPose(t1, false);
            else m.actor.setGlobalPose(t1);
        }
        if (dyn != null && type == BodyType.DYNAMIC) {
            boolean pushed = fresh || !velocity.equals(m.velocity) || !angular.equals(m.angular);
            if (pushed) {
                dyn.setLinearVelocity(vec(v1, velocity), false);
                dyn.setAngularVelocity(vec(v1, angular), false);
            }
            boolean asleep = b.isSleeping();
            if (fresh) {
                scene.addActor(m.actor);
                if (asleep) dyn.putToSleep();
            } else if (asleep != m.sleeping || ((moved || pushed) && !asleep)) {
                if (asleep) dyn.putToSleep();
                else dyn.wakeUp();
            }
            m.sleeping = asleep;
        } else if (fresh) {
            scene.addActor(m.actor);
        }
        m.position = position;
        m.rotation = rotation;
        m.velocity = velocity;
        m.angular = angular;
        m.fresh = false;
    }

    private static int locks(RigidBody b) {
        int locks = 0;
        if (b.isPositionLocked(0)) locks |= PxRigidDynamicLockFlagEnum.eLOCK_LINEAR_X.value;
        if (b.isPositionLocked(1)) locks |= PxRigidDynamicLockFlagEnum.eLOCK_LINEAR_Y.value;
        if (b.isPositionLocked(2)) locks |= PxRigidDynamicLockFlagEnum.eLOCK_LINEAR_Z.value;
        if (b.isRotationLocked(0)) locks |= PxRigidDynamicLockFlagEnum.eLOCK_ANGULAR_X.value;
        if (b.isRotationLocked(1)) locks |= PxRigidDynamicLockFlagEnum.eLOCK_ANGULAR_Y.value;
        if (b.isRotationLocked(2)) locks |= PxRigidDynamicLockFlagEnum.eLOCK_ANGULAR_Z.value;
        return locks;
    }

    /**
     * The mass spread evenly through the shapes, as the built-in solver spreads it, around the body's own centre of
     * mass so both sides mean the same point by "the velocity of the body".
     */
    private void applyMass(Mirror m) {
        RigidBody b = m.body;
        PxRigidDynamic dyn = m.dynamic;
        boolean solid = false;
        for (BodyShape s : m.source) solid |= s.shape().volume() > 0f;
        vec(v1, m.localCenter);
        if (!solid || m.shapes.length == 0 || !PxRigidBodyExt.setMassAndUpdateInertia(dyn, m.mass, v1, true)) {
            dyn.setMass(m.mass);
            identity(t1, m.localCenter);
            dyn.setCMassLocalPose(t1);
            dyn.setMassSpaceInertiaTensor(vec(v1, b.getInertia()));
        }
        if (m.inertiaOverride != null) {
            identity(t1, m.localCenter);
            dyn.setCMassLocalPose(t1);
            dyn.setMassSpaceInertiaTensor(vec(v1, m.inertiaOverride));
        }
    }

    private static boolean sameShapes(List<BodyShape> now, List<BodyShape> before) {
        if (now == before) return true;
        if (now.size() != before.size()) return false;
        for (int i = 0; i < now.size(); i++) {
            BodyShape a = now.get(i), b = before.get(i);
            if (a == b) continue;
            if (!a.offset().equals(b.offset()) || !a.rotation().equals(b.rotation())) return false;
            Shape sa = a.shape(), sb = b.shape();
            if (sa == sb) continue;
            // A hull or a mesh is the same only as the same object: comparing their points would cost what cooking does.
            if (sa instanceof ConvexHullShape || sa instanceof MeshShape || !sa.equals(sb)) return false;
        }
        return true;
    }

    private void buildShapes(Mirror m, List<BodyShape> shapes) {
        RigidBody b = m.body;
        for (PxShape old : m.shapes) m.actor.detachShape(old);
        forget(b);
        List<Shape> held = new ArrayList<>();
        PxMaterial material = material(b.getMaterial());
        boolean trigger = b.isTrigger();
        PxShapeFlags flags = new PxShapeFlags((byte) (trigger ? PxShapeFlagEnum.eTRIGGER_SHAPE.value : PxShapeFlagEnum.eSIMULATION_SHAPE.value));
        List<PxShape> made = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < shapes.size(); i++) {
            BodyShape placed = shapes.get(i);
            Shape shape = placed.shape();
            Quat turn = placed.rotation();
            PxGeometry geometry;
            switch (shape) {
                case SphereShape s -> geometry = new PxSphereGeometry(s.radius());
                case BoxShape s -> geometry = new PxBoxGeometry(s.halfExtents().x(), s.halfExtents().y(), s.halfExtents().z());
                case CapsuleShape s -> {
                    if (s.halfHeight() <= 1e-6f) {
                        geometry = new PxSphereGeometry(s.radius());
                    } else {
                        geometry = new PxCapsuleGeometry(s.radius(), s.halfHeight());
                        turn = turn.mul(X_TO_Y); // PhysX capsules lie along X
                    }
                }
                case PlaneShape s -> {
                    geometry = new PxPlaneGeometry();
                    turn = turn.mul(between(Vec3.X, s.normal())); // and its planes face along X
                }
                case ConvexHullShape s -> {
                    PxConvexMesh mesh = (PxConvexMesh) cook(s, held);
                    if (mesh == null) continue;
                    geometry = new PxConvexMeshGeometry(mesh);
                }
                case MeshShape s -> {
                    if (trigger) {
                        if (!warnedMeshTrigger) System.err.println("[PhysX] A mesh can't be a trigger in PhysX; the mesh shape of " + b + " is left out");
                        warnedMeshTrigger = true;
                        continue;
                    }
                    PxTriangleMesh mesh = (PxTriangleMesh) cook(s, held);
                    if (mesh == null) continue;
                    geometry = new PxTriangleMeshGeometry(mesh);
                }
            }
            PxShape px = PhysX.physics().createShape(geometry, material, true, flags);
            geometry.destroy();
            px.setLocalPose(pose(t1, placed.offset(), turn));
            m.actor.attachShape(px);
            px.release(); // the actor keeps it
            made.add(px);
            indices.add(i);
        }
        flags.destroy();
        release(m.cooked);
        m.cooked = held;
        m.shapes = made.toArray(PxShape[]::new);
        m.shapeAddresses = new long[made.size()];
        m.shapeIndices = new int[made.size()];
        for (int i = 0; i < made.size(); i++) {
            m.shapeAddresses[i] = made.get(i).getAddress();
            m.shapeIndices[i] = indices.get(i);
        }
        m.source = shapes;
    }

    private PxMaterial material(PhysicsMaterial material) {
        return materials.computeIfAbsent(material, key -> {
            // The built-in solver takes the root of the two frictions multiplied and the larger restitution. PhysX can
            // multiply, so each side brings the root of its own.
            float friction = (float) Math.sqrt(key.friction());
            PxMaterial px = PhysX.physics().createMaterial(friction, friction, key.restitution());
            px.setFrictionCombineMode(PxCombineModeEnum.eMULTIPLY);
            px.setRestitutionCombineMode(PxCombineModeEnum.eMAX);
            return px;
        });
    }

    /**
     * The PhysX mesh of a hull or a triangle mesh, cooked the first time a body uses it and kept while any does.
     */
    private PxBase cook(Shape shape, List<Shape> held) {
        Cooked entry = cooked.get(shape);
        if (entry == null) {
            PxBase mesh = shape instanceof ConvexHullShape hull ? cookHull(hull) : cookMesh((MeshShape) shape);
            if (mesh == null) {
                System.err.println("[PhysX] Could not cook " + shape + "; the shape is left out");
                return null;
            }
            entry = new Cooked(mesh);
            cooked.put(shape, entry);
        }
        entry.users++;
        held.add(shape);
        return entry.mesh;
    }

    private void release(List<Shape> held) {
        for (Shape shape : held) {
            Cooked entry = cooked.get(shape);
            if (entry != null && --entry.users == 0) {
                cooked.remove(shape);
                entry.mesh.release();
            }
        }
        held.clear();
    }

    private PxConvexMesh cookHull(ConvexHullShape hull) {
        PxArray_PxVec3 vertices = new PxArray_PxVec3();
        for (Vec3 v : hull.vertices()) vertices.pushBack(vec(v1, v));
        PxConvexMeshDesc desc = new PxConvexMeshDesc();
        PxConvexFlags flags = new PxConvexFlags((short) PxConvexFlagEnum.eCOMPUTE_CONVEX.value);
        PxBoundedData data = desc.getPoints();
        data.setCount(vertices.size());
        data.setStride(PxVec3.SIZEOF);
        data.setData(vertices.begin());
        desc.setFlags(flags);
        PxConvexMesh mesh = PxTopLevelFunctions.CreateConvexMesh(PhysX.cooking(), desc);
        desc.destroy();
        flags.destroy();
        vertices.destroy();
        return mesh == null || mesh.getAddress() == 0L ? null : mesh;
    }

    private PxTriangleMesh cookMesh(MeshShape shape) {
        int triangles = shape.triangleCount();
        PxArray_PxVec3 vertices = new PxArray_PxVec3();
        PxArray_PxU32 indices = new PxArray_PxU32();
        for (int i = 0; i < triangles; i++) {
            for (Vec3 corner : shape.triangle(i)) {
                indices.pushBack(vertices.size());
                vertices.pushBack(vec(v1, corner));
            }
        }
        PxTriangleMeshDesc desc = new PxTriangleMeshDesc();
        PxBoundedData data = desc.getPoints();
        data.setCount(vertices.size());
        data.setStride(PxVec3.SIZEOF);
        data.setData(vertices.begin());
        PxBoundedData faces = desc.getTriangles();
        faces.setCount(triangles);
        faces.setStride(12);
        faces.setData(indices.begin());
        PxTriangleMesh mesh = PxTopLevelFunctions.CreateTriangleMesh(PhysX.cooking(), desc);
        desc.destroy();
        indices.destroy();
        vertices.destroy();
        return mesh == null || mesh.getAddress() == 0L ? null : mesh;
    }

    private int filterClass(int layer, int mask) {
        long key = ((long) layer << 32) | (mask & 0xffffffffL);
        Integer known = classes.get(key);
        if (known != null) return known;
        if (classMasks.size() == 32) {
            if (!warnedClasses) System.err.println("[PhysX] More than 32 combinations of collision layer and mask in one world; the rest collide as the 32nd does");
            warnedClasses = true;
            return 31;
        }
        int index = classMasks.size();
        classMasks.add(new int[]{layer, mask});
        classes.put(key, index);
        for (int i = 0; i <= index; i++) {
            int[] a = classMasks.get(i);
            boolean agree = (a[0] & mask) != 0 && (layer & a[1]) != 0;
            if (!agree) continue;
            allowed[i] |= 1 << index;
            allowed[index] |= 1 << i;
        }
        filterEpoch++; // the classes that collide with the new one have another bit to carry
        return index;
    }

    // ---- Joints ---------------------------------------------------------------------------------------------------

    @Override
    public void jointAdded(Joint joint) {
        if (closed || joints.containsKey(joint)) return;
        joints.put(joint, new PhysXJoints.Link(this, joint));
    }

    @Override
    public void jointRemoved(Joint joint) {
        PhysXJoints.Link link = joints.remove(joint);
        if (link != null) link.release();
    }

    // ---- Stepping -------------------------------------------------------------------------------------------------

    @Override
    public void step(PhysicsWorld world, float dt) {
        if (closed) throw new IllegalStateException("This PhysXBackend is closed");
        broken.clear();
        if (!world.getGravity().equals(gravity)) {
            gravity = world.getGravity();
            scene.setGravity(vec(v1, gravity));
        }
        worldSleeps = world.isSleeping();
        int epoch = filterEpoch;
        for (int i = 0; i < all.size(); i++) push(all.get(i));
        // A layer and mask not seen before changes what the shapes pushed ahead of it have to carry.
        if (filterEpoch != epoch) for (int i = 0; i < all.size(); i++) push(all.get(i));
        for (PhysXJoints.Link link : joints.values()) link.push();

        int pieces = world.getSubsteps();
        float h = dt / pieces;
        for (int piece = 0; piece < pieces; piece++) {
            for (int i = 0; i < all.size(); i++) drive(all.get(i), h);
            scene.simulate(h);
            scene.fetchResults(true);
        }

        for (int i = 0; i < all.size(); i++) pull(all.get(i));
        for (PhysXJoints.Link link : joints.values()) if (link.pull(v1, v2)) broken.add(link.joint);
    }

    /**
     * What has to be said again before each piece of a step: where a kinematic body goes next, and the forces, which
     * PhysX drops after every simulate call.
     */
    private void drive(Mirror m, float h) {
        RigidBody b = m.body;
        if (m.type == BodyType.KINEMATIC) {
            if (m.velocity.lengthSquared() == 0f && m.angular.lengthSquared() == 0f) return;
            m.position = m.position.add(m.velocity.mul(h));
            float spin = m.angular.length();
            if (spin > 0f) m.rotation = Quat.fromAxisAngle(m.angular.mul(1f / spin), spin * h).mul(m.rotation).normalized();
            pose(t1, m.position, m.rotation);
            if (m.dynamic != null) m.dynamic.setKinematicTarget(t1);
            else m.actor.setGlobalPose(t1);
        } else if (m.type == BodyType.DYNAMIC && m.dynamic != null) {
            Vec3 force = b.getForce(), torque = b.getTorque();
            if (force.lengthSquared() != 0f) m.dynamic.addForce(vec(v1, force), PxForceModeEnum.eFORCE, true);
            if (torque.lengthSquared() != 0f) m.dynamic.addTorque(vec(v1, torque), PxForceModeEnum.eFORCE, true);
            if (m.gravityOff && b.getGravityScale() != 0f && !m.dynamic.isSleeping()) {
                m.dynamic.addForce(vec(v1, gravity.mul(b.getGravityScale())), PxForceModeEnum.eACCELERATION, false);
            }
        }
    }

    /**
     * Reads where the step left the body and puts the {@link RigidBody} there.
     */
    private void pull(Mirror m) {
        RigidBody b = m.body;
        if (m.type == BodyType.STATIC) return;
        if (m.type == BodyType.KINEMATIC) {
            if (m.velocity.lengthSquared() == 0f && m.angular.lengthSquared() == 0f) return;
            PhysicsBridge.place(b, m.position, m.rotation, m.velocity, m.angular, false);
        } else {
            PxRigidDynamic dyn = m.dynamic;
            if (dyn == null) return;
            boolean asleep = dyn.isSleeping();
            if (asleep && m.sleeping) return; // slept through the step
            PxTransform at = dyn.getGlobalPose();
            PxVec3 p = at.getP();
            PxQuat q = at.getQ();
            Vec3 position = new Vec3(p.getX(), p.getY(), p.getZ());
            Quat rotation = new Quat(q.getX(), q.getY(), q.getZ(), q.getW());
            Vec3 velocity = asleep ? Vec3.ZERO : vec(dyn.getLinearVelocity());
            Vec3 angular = asleep ? Vec3.ZERO : vec(dyn.getAngularVelocity());
            PhysicsBridge.place(b, position, rotation, velocity, angular, asleep);
        }
        // What the body says now is what PhysX has: anything different next step was changed by the game.
        m.position = b.getPosition();
        m.rotation = b.getRotation();
        m.velocity = b.getVelocity();
        m.angular = b.getAngularVelocity();
        m.sleeping = b.isSleeping();
    }

    @Override
    public List<Contact> contacts() {
        return new ArrayList<>(touching.values());
    }

    @Override
    public List<Joint> brokenJoints() {
        return broken;
    }

    // ---- Events ---------------------------------------------------------------------------------------------------

    /**
     * PhysX calls these from inside fetchResults, on the thread stepping the world. They only keep the list of what
     * touches; the world tells the listeners after the step.
     */
    private final class Events extends PxSimulationEventCallbackImpl {
        private final Vec3[] positions = new Vec3[MAX_POINTS];
        private final float[] depths = new float[MAX_POINTS], impulses = new float[MAX_POINTS];

        @Override
        public void onContact(PxContactPairHeader header, PxContactPair pairs, int count) {
            Mirror first = mirrorOf(header.getActors(0)), second = mirrorOf(header.getActors(1));
            if (first == null || second == null) return;
            // The built-in solver has no contacts between two bodies neither of which can be pushed.
            if (!first.body.isDynamic() && !second.body.isDynamic()) return;
            // The lower id is body A, as the built-in solver has it.
            boolean swap = PhysicsBridge.id(first.body) > PhysicsBridge.id(second.body);
            Mirror a = swap ? second : first, b = swap ? first : second;
            for (int i = 0; i < count; i++) {
                PxContactPair pair = PxContactPair.arrayGet(pairs.getAddress(), i);
                PxContactPairFlags flags = pair.getFlags();
                if (flags.isSet(PxContactPairFlagEnum.eREMOVED_SHAPE_0) || flags.isSet(PxContactPairFlagEnum.eREMOVED_SHAPE_1)) continue;
                int shape0 = first.shapeIndex(pair.getShapes(0).getAddress()), shape1 = second.shapeIndex(pair.getShapes(1).getAddress());
                if (shape0 < 0 || shape1 < 0) continue;
                int shapeA = swap ? shape1 : shape0, shapeB = swap ? shape0 : shape1;
                PairKey key = new PairKey(PhysicsBridge.id(a.body), shapeA, PhysicsBridge.id(b.body), shapeB);
                PxPairFlags what = pair.getEvents();
                if (what.isSet(PxPairFlagEnum.eNOTIFY_TOUCH_LOST)) {
                    touching.remove(key);
                    continue;
                }
                if (!what.isSet(PxPairFlagEnum.eNOTIFY_TOUCH_FOUND) && !what.isSet(PxPairFlagEnum.eNOTIFY_TOUCH_PERSISTS)) continue;
                int n = Math.min(pair.extractContacts(pointsBegin, MAX_POINTS), MAX_POINTS);
                if (n == 0 && touching.containsKey(key)) continue; // nothing new to say about it
                Vec3 normal = Vec3.UP;
                for (int k = 0; k < n; k++) {
                    // Read straight from PhysX's buffer: a call into the library for each number costs more than the
                    // rest of a step put together once a few thousand bodies touch. A point is its position, the
                    // gap, the normal (from the second shape to the first), a face index, the impulse and another
                    // face index, four bytes a number.
                    long at = pointsAddress + (long) k * POINT_BYTES;
                    Vec3 towardFirst = new Vec3(MemoryUtil.memGetFloat(at + 16), MemoryUtil.memGetFloat(at + 20), MemoryUtil.memGetFloat(at + 24));
                    if (k == 0) normal = swap ? towardFirst : towardFirst.negate();
                    positions[k] = new Vec3(MemoryUtil.memGetFloat(at), MemoryUtil.memGetFloat(at + 4), MemoryUtil.memGetFloat(at + 8));
                    depths[k] = -MemoryUtil.memGetFloat(at + 12);
                    impulses[k] = Math.abs(MemoryUtil.memGetFloat(at + 32) * towardFirst.x() + MemoryUtil.memGetFloat(at + 36) * towardFirst.y()
                            + MemoryUtil.memGetFloat(at + 40) * towardFirst.z());
                }
                if (n == 0) {
                    positions[0] = a.body.getCenterOfMass().lerp(b.body.getCenterOfMass(), 0.5f);
                    depths[0] = 0f;
                    impulses[0] = 0f;
                    n = 1;
                }
                touching.put(key, PhysicsBridge.contact(a.body, shapeA, b.body, shapeB, normal, positions, depths, impulses, n));
            }
        }

        @Override
        public void onTrigger(PxTriggerPair pairs, int count) {
            for (int i = 0; i < count; i++) {
                PxTriggerPair pair = PxTriggerPair.arrayGet(pairs.getAddress(), i);
                if (pair.getFlags().isSet(PxTriggerPairFlagEnum.eREMOVED_SHAPE_TRIGGER) || pair.getFlags().isSet(PxTriggerPairFlagEnum.eREMOVED_SHAPE_OTHER)) continue;
                Mirror trigger = mirrorOf(pair.getTriggerActor()), other = mirrorOf(pair.getOtherActor());
                if (trigger == null || other == null) continue;
                int shapeTrigger = trigger.shapeIndex(pair.getTriggerShape().getAddress()), shapeOther = other.shapeIndex(pair.getOtherShape().getAddress());
                if (shapeTrigger < 0 || shapeOther < 0) continue;
                boolean swap = PhysicsBridge.id(trigger.body) > PhysicsBridge.id(other.body);
                Mirror a = swap ? other : trigger, b = swap ? trigger : other;
                int shapeA = swap ? shapeOther : shapeTrigger, shapeB = swap ? shapeTrigger : shapeOther;
                PairKey key = new PairKey(PhysicsBridge.id(a.body), shapeA, PhysicsBridge.id(b.body), shapeB);
                if (pair.getStatus() == PxPairFlagEnum.eNOTIFY_TOUCH_LOST) {
                    touching.remove(key);
                } else {
                    positions[0] = other.body.getPosition();
                    depths[0] = 0f;
                    impulses[0] = 0f;
                    touching.put(key, PhysicsBridge.contact(a.body, shapeA, b.body, shapeB, Vec3.UP, positions, depths, impulses, 1));
                }
            }
        }

        private Mirror mirrorOf(PxActor actor) {
            return actor == null ? null : byActor.get(actor.getAddress());
        }
    }

    // ---- Lifetime -------------------------------------------------------------------------------------------------

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (scene == null) return;
        for (PhysXJoints.Link link : joints.values()) link.release();
        joints.clear();
        for (Mirror m : all) {
            if (m.actor == null) continue;
            scene.removeActor(m.actor);
            m.actor.release();
            m.actor = null;
        }
        all.clear();
        mirrors.clear();
        byActor.clear();
        touching.clear();
        for (Cooked entry : cooked.values()) entry.mesh.release();
        cooked.clear();
        for (PxMaterial material : materials.values()) material.release();
        materials.clear();
        scene.release();
        scene = null;
        events.destroy();
        points.destroy();
        filter.destroy();
        t1.destroy();
        t2.destroy();
        q1.destroy();
        v1.destroy();
        v2.destroy();
    }

    // ---- Small conversions ----------------------------------------------------------------------------------------

    /** A quarter turn around Z, which stands PhysX's capsule (along X) up along Y. */
    private static final Quat X_TO_Y = Quat.fromAxisAngle(Vec3.Z, (float) (Math.PI / 2));

    /**
     * The shortest turn taking the unit vector {@code from} to the unit vector {@code to}.
     */
    static Quat between(Vec3 from, Vec3 to) {
        float dot = from.dot(to);
        if (dot > 0.999999f) return Quat.IDENTITY;
        if (dot < -0.999999f) {
            Vec3 side = Math.abs(from.x()) < 0.9f ? Vec3.X : Vec3.UP;
            return Quat.fromAxisAngle(from.cross(side).normalized(), (float) Math.PI);
        }
        return Quat.fromAxisAngle(from.cross(to).normalized(), (float) Math.acos(dot));
    }

    PxVec3 vec(PxVec3 target, Vec3 v) {
        target.setX(v.x());
        target.setY(v.y());
        target.setZ(v.z());
        return target;
    }

    static Vec3 vec(PxVec3 v) {
        return new Vec3(v.getX(), v.getY(), v.getZ());
    }

    PxTransform pose(PxTransform target, Vec3 position, Quat rotation) {
        target.setP(vec(v2, position));
        q1.setX(rotation.x());
        q1.setY(rotation.y());
        q1.setZ(rotation.z());
        q1.setW(rotation.w());
        target.setQ(q1);
        return target;
    }

    private PxTransform identity(PxTransform target, Vec3 position) {
        return pose(target, position, Quat.IDENTITY);
    }

    PxTransform scratchA() {
        return t1;
    }

    PxTransform scratchB() {
        return t2;
    }
}
