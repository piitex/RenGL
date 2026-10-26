package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Node3D;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A solid object in a {@link PhysicsWorld}: it has mass, a velocity and a spin, and moves the {@link Node3D} it is made
 * for. Give it one or more shapes, then add it to a world:
 * <pre>
 * Node3D crate = new Node3D("crate", Mesh.cube(1f), material);
 * scene.add(crate);
 * world.add(new RigidBody(crate, BodyType.DYNAMIC).addShape(BoxShape.ofSize(1, 1, 1)).setMass(20f));
 * </pre>
 * The body takes its starting place from the node's world transform when it is added, and after each world update it
 * writes its place back into the node. Moving or turning the node yourself teleports the body there, keeping its
 * velocity. The node's scale stretches the shapes (read when the body is made and when it is added, or by
 * {@link #syncFromNode()}).
 * <p>
 * The body's position is its node's origin, in world space and metres. It spins around its centre of mass, which is
 * the middle of its shapes weighted by their volume unless set with {@link #setCenterOfMass}. Velocities are in metres
 * and radians per second, forces in newtons and torques in newton metres.
 * <p>
 * A force acts for the next step only and is then cleared, so a steady push (a thruster, wind) belongs in the world's
 * {@link PhysicsWorld#setStepHandler step handler}, which runs before every step. An impulse changes the velocity at
 * once and suits one-off events: a jump, a hit, an explosion.
 */
public final class RigidBody {
    private static final float MAX_TURN_PER_STEP = (float) (Math.PI * 0.5);

    private final Node3D node;
    private BodyType type;
    private PhysicsWorld world;
    int id;
    int proxy = BodyTree.NONE; // its leaf in the world's tree

    private final List<BodyShape> shapes = new ArrayList<>();
    private List<BodyShape> scaledShapes = List.of();
    private Vec3 scale = Vec3.ONE;
    private float mass = 1f;
    private Vec3 inertiaOverride;
    private Vec3 centerOverride;
    private Vec3 localCenter = Vec3.ZERO;
    private Mat3 inertiaLocal = Mat3.ZERO;
    private Mat3 invInertiaLocal = Mat3.ZERO;
    private Mat3 invInertiaWorld = Mat3.ZERO;
    private float invMass;

    // Centre of mass, orientation, velocities and accumulated forces, as floats for the solver.
    float cx, cy, cz;
    float qx, qy, qz, qw = 1f;
    float vx, vy, vz;
    float wx, wy, wz;
    private float fx, fy, fz;
    private float tx, ty, tz;
    // Pseudo velocities: the solver's push out of overlaps, used for one position update and then dropped.
    float pvx, pvy, pvz, pwx, pwy, pwz;
    // 1 for a free axis, 0 for a locked one.
    float lx = 1f, ly = 1f, lz = 1f;
    float ax = 1f, ay = 1f, az = 1f;

    private float linearDamping;
    private float angularDamping = 0.05f;
    private float gravityScale = 1f;
    private PhysicsMaterial material = PhysicsMaterial.DEFAULT;
    private boolean sleeping;
    private boolean canSleep = true;
    float sleepTime;
    int islandIndex;
    float speculative;
    private Object userData;
    private boolean trigger;
    private boolean continuous = true;
    private float reach, thickness;
    private ContactListener contactListener;
    private int collisionLayer = 1;
    private int collisionMask = -1;

    private Pose[] shapePoses = new Pose[0];
    private Bounds[] shapeBounds = new Bounds[0];
    private Bounds bounds = new Bounds(Vec3.ZERO, Vec3.ZERO);

    private Vec3 previousPosition = Vec3.ZERO;
    private Quat previousRotation = Quat.IDENTITY;
    private Vec3 writtenPosition;
    private Quat writtenRotation;
    final List<Joint> joints = new ArrayList<>(0);
    CharacterController character; // the character this body is, if it is one

    /**
     * A body that moves {@code node}, which may be null for an invisible body.
     */
    public RigidBody(Node3D node, BodyType type) {
        this.node = node;
        this.type = type == null ? BodyType.DYNAMIC : type;
        // Read the scale now as well as when added, so setDensity before adding sees the shapes at their real size.
        if (node != null) scale = absolute(node.worldMatrix().decompose().scale());
        updateMassProperties();
    }

    private static Vec3 absolute(Vec3 v) {
        return new Vec3(Math.abs(v.x()), Math.abs(v.y()), Math.abs(v.z()));
    }

    /**
     * A dynamic body that moves {@code node}.
     */
    public RigidBody(Node3D node) {
        this(node, BodyType.DYNAMIC);
    }

    /**
     * A body with no node, placed by {@link #setPosition} and {@link #setRotation}.
     */
    public RigidBody(BodyType type) {
        this(null, type);
    }

    public Node3D getNode() {
        return node;
    }

    /**
     * The world this body is in, or null.
     */
    public PhysicsWorld getWorld() {
        return world;
    }

    public BodyType getType() {
        return type;
    }

    /**
     * Changes how the body moves. A body that stops being dynamic keeps its velocity only if it becomes kinematic.
     */
    public RigidBody setType(BodyType type) {
        type = type == null ? BodyType.DYNAMIC : type;
        if (type == BodyType.DYNAMIC && hasSurface()) throw new IllegalStateException("A body with a plane or mesh can't be dynamic");
        this.type = type;
        if (this.type == BodyType.STATIC) {
            setVelocity(Vec3.ZERO);
            setAngularVelocity(Vec3.ZERO);
        }
        clearForces();
        updateMassProperties();
        wakeUp();
        return this;
    }

    public boolean isDynamic() {
        return type == BodyType.DYNAMIC;
    }

    boolean isAwakeDynamic() {
        return type == BodyType.DYNAMIC && !sleeping;
    }

    /**
     * Whether the body takes part in collisions this step: an awake dynamic body, or a kinematic one on the move.
     */
    boolean isActive() {
        return isAwakeDynamic() || (type == BodyType.KINEMATIC && isMoving());
    }

    boolean isMoving() {
        return vx != 0f || vy != 0f || vz != 0f || wx != 0f || wy != 0f || wz != 0f;
    }

    // ---- Sleep ----------------------------------------------------------------------------------------------------

    /**
     * Whether the body is resting and skipped by the simulation until something touches, pushes or moves it. A body
     * falls asleep after half a second of barely moving, together with everything it rests on or against.
     */
    public boolean isSleeping() {
        return sleeping;
    }

    /**
     * Wakes the body so it moves again. Pushing, moving or changing it does this for you.
     */
    public RigidBody wakeUp() {
        sleeping = false;
        sleepTime = 0f;
        return this;
    }

    /**
     * Puts the body to sleep now, stopping it. It wakes when touched, pushed or moved.
     */
    public RigidBody sleep() {
        if (type != BodyType.DYNAMIC) return this;
        sleeping = true;
        vx = vy = vz = wx = wy = wz = 0f;
        clearForces();
        return this;
    }

    public boolean isSleepingAllowed() {
        return canSleep;
    }

    /**
     * Whether the body may fall asleep (on by default). Turn it off for something the player controls, so it reacts
     * at once to code that sets its position or velocity directly.
     */
    public RigidBody setSleepingAllowed(boolean allowed) {
        canSleep = allowed;
        if (!allowed) wakeUp();
        return this;
    }

    // ---- Material -------------------------------------------------------------------------------------------------

    public PhysicsMaterial getMaterial() {
        return material;
    }

    /**
     * Friction and bounce, {@link PhysicsMaterial#DEFAULT} unless set.
     */
    public RigidBody setMaterial(PhysicsMaterial material) {
        this.material = material == null ? PhysicsMaterial.DEFAULT : material;
        return this;
    }

    public RigidBody setFriction(float friction) {
        return setMaterial(material.withFriction(friction));
    }

    public RigidBody setRestitution(float restitution) {
        return setMaterial(material.withRestitution(restitution));
    }

    // ---- Shapes and mass ------------------------------------------------------------------------------------------

    /**
     * The shapes as given, in the node's local units, before its scale.
     */
    public List<BodyShape> getShapes() {
        return Collections.unmodifiableList(shapes);
    }

    /**
     * The shapes in metres, with the node's scale applied.
     */
    public List<BodyShape> getWorldScaledShapes() {
        return scaledShapes;
    }

    public RigidBody addShape(Shape shape) {
        return addShape(new BodyShape(shape));
    }

    public RigidBody addShape(Shape shape, Vec3 offset) {
        return addShape(new BodyShape(shape, offset, Quat.IDENTITY));
    }

    public RigidBody addShape(Shape shape, Vec3 offset, Quat rotation) {
        return addShape(new BodyShape(shape, offset, rotation));
    }

    public RigidBody addShape(BodyShape shape) {
        if (shape.shape() instanceof PlaneShape && type == BodyType.DYNAMIC) {
            throw new IllegalStateException("A plane has no volume to move; put it on a static or kinematic body");
        }
        if (shape.shape() instanceof MeshShape && type == BodyType.DYNAMIC) {
            throw new IllegalStateException("A mesh has no inside to move; put it on a static or kinematic body, or use a ConvexHullShape");
        }
        shapes.add(shape);
        updateMassProperties();
        wakeUp();
        return this;
    }

    public boolean removeShape(BodyShape shape) {
        boolean removed = shapes.remove(shape);
        if (removed) updateMassProperties();
        return removed;
    }

    public float getMass() {
        return mass;
    }

    /**
     * The mass in kilograms (1 by default), spread through the shapes by their volume. Only a dynamic body uses it;
     * kinematic and static bodies act as if their mass were infinite.
     */
    public RigidBody setMass(float mass) {
        if (!(mass > 0f) || Float.isInfinite(mass)) throw new IllegalArgumentException("The mass must be above 0 and finite, not " + mass);
        this.mass = mass;
        updateMassProperties();
        return this;
    }

    /**
     * Sets the mass from a density in kilograms per cubic metre and the volume of the shapes (as scaled now). Water is
     * 1000, wood about 600, steel about 7850.
     */
    public RigidBody setDensity(float density) {
        float volume = 0f;
        for (BodyShape shape : scaledShapes) volume += shape.shape().volume();
        if (volume <= 0f) throw new IllegalStateException("The body has no shapes to take a volume from");
        return setMass(density * volume);
    }

    /**
     * 1 over the mass, or 0 for a body that isn't dynamic.
     */
    public float getInverseMass() {
        return invMass;
    }

    /**
     * The moments of inertia around the body's own X, Y and Z axes through its centre of mass, in kg m². They come
     * from the shapes and the mass; a body without shapes is treated as a solid ball 1 m across.
     */
    public Vec3 getInertia() {
        return inertiaLocal.diagonal();
    }

    /**
     * Replaces the inertia worked out from the shapes, as moments around the body's own axes; null goes back to the
     * worked-out values. A larger value makes the body harder to spin around that axis.
     */
    public RigidBody setInertia(Vec3 inertia) {
        if (inertia != null && !(inertia.x() > 0f && inertia.y() > 0f && inertia.z() > 0f)) {
            throw new IllegalArgumentException("Every moment of inertia must be above 0, not " + inertia);
        }
        inertiaOverride = inertia;
        updateMassProperties();
        return this;
    }

    /**
     * The centre of mass in the body's own space, from its origin.
     */
    public Vec3 getLocalCenterOfMass() {
        return localCenter;
    }

    /**
     * The centre of mass in world space.
     */
    public Vec3 getCenterOfMass() {
        return new Vec3(cx, cy, cz);
    }

    /**
     * Moves the centre of mass, in the body's own space; null goes back to the middle of the shapes. A low centre of
     * mass makes a body hard to tip over.
     */
    public RigidBody setCenterOfMass(Vec3 local) {
        centerOverride = local;
        updateMassProperties();
        return this;
    }

    // ---- Place ----------------------------------------------------------------------------------------------------

    /**
     * Where the body's origin (its node's origin) is in the world.
     */
    public Vec3 getPosition() {
        return getCenterOfMass().sub(getRotation().rotate(localCenter));
    }

    /**
     * Teleports the body's origin to {@code position} in world space, keeping its velocity.
     */
    public RigidBody setPosition(Vec3 position) {
        setCenter(position.add(getRotation().rotate(localCenter)));
        teleported();
        return this;
    }

    public RigidBody setPosition(float x, float y, float z) {
        return setPosition(new Vec3(x, y, z));
    }

    public Quat getRotation() {
        return new Quat(qx, qy, qz, qw);
    }

    /**
     * Turns the body to {@code rotation} in world space around its origin, keeping its velocity.
     */
    public RigidBody setRotation(Quat rotation) {
        Vec3 origin = getPosition();
        setOrientation(rotation.normalized());
        setCenter(origin.add(getRotation().rotate(localCenter)));
        teleported();
        return this;
    }

    /**
     * Reads the node's world position, rotation and scale again, as when the body was added: the shapes are rescaled
     * and the body jumps to the node. Use it after changing the node's scale.
     */
    public RigidBody syncFromNode() {
        if (node == null) return this;
        Mat4.Trs trs = node.worldMatrix().decompose();
        scale = absolute(trs.scale());
        setOrientation(trs.rotation());
        updateMassProperties();
        setCenter(trs.position().add(getRotation().rotate(localCenter)));
        teleported();
        return this;
    }

    // ---- Motion ---------------------------------------------------------------------------------------------------

    /**
     * The velocity of the centre of mass, in metres per second.
     */
    public Vec3 getVelocity() {
        return new Vec3(vx, vy, vz);
    }

    public RigidBody setVelocity(Vec3 velocity) {
        if (type == BodyType.STATIC) return this;
        wakeUp();
        vx = velocity.x() * lx;
        vy = velocity.y() * ly;
        vz = velocity.z() * lz;
        return this;
    }

    public RigidBody setVelocity(float x, float y, float z) {
        return setVelocity(new Vec3(x, y, z));
    }

    /**
     * The spin in world space: the axis it turns around, with a length of its speed in radians per second
     * (counter-clockwise looking down the axis).
     */
    public Vec3 getAngularVelocity() {
        return new Vec3(wx, wy, wz);
    }

    public RigidBody setAngularVelocity(Vec3 velocity) {
        if (type == BodyType.STATIC) return this;
        wakeUp();
        wx = velocity.x() * ax;
        wy = velocity.y() * ay;
        wz = velocity.z() * az;
        return this;
    }

    /**
     * How fast a point fixed to the body, given in world space, is moving: the body's velocity plus its spin around
     * the centre of mass.
     */
    public Vec3 getPointVelocity(Vec3 point) {
        return getVelocity().add(getAngularVelocity().cross(point.sub(getCenterOfMass())));
    }

    /**
     * Energy of motion, in joules: from moving and from spinning.
     */
    public float getKineticEnergy() {
        if (type == BodyType.STATIC) return 0f;
        Vec3 w = getAngularVelocity();
        Mat3 r = Mat3.rotation(getRotation());
        float spin = w.dot(inertiaLocal.rotated(r).mul(w));
        return 0.5f * (mass * getVelocity().lengthSquared() + spin);
    }

    /**
     * Pushes on the centre of mass for the next step.
     */
    public RigidBody applyForce(Vec3 force) {
        if (type != BodyType.DYNAMIC) return this;
        wakeUp();
        fx += force.x();
        fy += force.y();
        fz += force.z();
        return this;
    }

    /**
     * Pushes on a point in world space for the next step. Off the centre of mass, this also spins the body.
     */
    public RigidBody applyForceAtPoint(Vec3 force, Vec3 point) {
        applyForce(force);
        return applyTorque(point.sub(getCenterOfMass()).cross(force));
    }

    /**
     * Twists the body around its centre of mass for the next step; the axis is in world space.
     */
    public RigidBody applyTorque(Vec3 torque) {
        if (type != BodyType.DYNAMIC) return this;
        wakeUp();
        tx += torque.x();
        ty += torque.y();
        tz += torque.z();
        return this;
    }

    /**
     * Changes the velocity at once by {@code impulse / mass}. An impulse is a force times how long it acts, in
     * newton seconds.
     */
    public RigidBody applyImpulse(Vec3 impulse) {
        if (type != BodyType.DYNAMIC) return this;
        wakeUp();
        vx += impulse.x() * invMass * lx;
        vy += impulse.y() * invMass * ly;
        vz += impulse.z() * invMass * lz;
        return this;
    }

    /**
     * An impulse at a point in world space, which also changes the spin when it is off the centre of mass.
     */
    public RigidBody applyImpulseAtPoint(Vec3 impulse, Vec3 point) {
        applyImpulse(impulse);
        return applyTorqueImpulse(point.sub(getCenterOfMass()).cross(impulse));
    }

    /**
     * Changes the spin at once, by an angular impulse in newton metre seconds around a world axis.
     */
    public RigidBody applyTorqueImpulse(Vec3 impulse) {
        if (type != BodyType.DYNAMIC) return this;
        wakeUp();
        Vec3 dw = invInertiaWorld.mul(impulse);
        wx += dw.x();
        wy += dw.y();
        wz += dw.z();
        return this;
    }

    public Vec3 getForce() {
        return new Vec3(fx, fy, fz);
    }

    public Vec3 getTorque() {
        return new Vec3(tx, ty, tz);
    }

    /**
     * Drops the forces and torques gathered for the next step.
     */
    public RigidBody clearForces() {
        fx = fy = fz = tx = ty = tz = 0f;
        return this;
    }

    public float getLinearDamping() {
        return linearDamping;
    }

    /**
     * How quickly movement dies away on its own, like air resistance: each second the velocity is multiplied by
     * {@code e^-damping}. 0 (the default) keeps it until something stops it.
     */
    public RigidBody setLinearDamping(float damping) {
        linearDamping = Math.max(0f, damping);
        return this;
    }

    public float getAngularDamping() {
        return angularDamping;
    }

    /**
     * As {@link #setLinearDamping}, for spin. 0.05 by default, so a spinning body slows over a minute or so.
     */
    public RigidBody setAngularDamping(float damping) {
        angularDamping = Math.max(0f, damping);
        return this;
    }

    public float getGravityScale() {
        return gravityScale;
    }

    /**
     * How much of the world's gravity pulls this body: 0 floats, 1 (the default) falls normally, negative rises.
     */
    public RigidBody setGravityScale(float scale) {
        gravityScale = scale;
        return this;
    }

    /**
     * Stops the body moving along the chosen world axes; true locks. For a game played on a plane, lock Z.
     */
    public RigidBody lockPosition(boolean x, boolean y, boolean z) {
        lx = x ? 0f : 1f;
        ly = y ? 0f : 1f;
        lz = z ? 0f : 1f;
        setVelocity(getVelocity());
        return this;
    }

    /**
     * Stops the body turning around the chosen world axes; true locks. Lock X and Z to keep a character upright.
     */
    public RigidBody lockRotation(boolean x, boolean y, boolean z) {
        ax = x ? 0f : 1f;
        ay = y ? 0f : 1f;
        az = z ? 0f : 1f;
        setAngularVelocity(getAngularVelocity());
        updateWorldInertia();
        return this;
    }

    public boolean isPositionLocked(int axis) {
        return (switch (axis) { case 0 -> lx; case 1 -> ly; case 2 -> lz; default -> throw new IllegalArgumentException("Axis " + axis); }) == 0f;
    }

    public boolean isRotationLocked(int axis) {
        return (switch (axis) { case 0 -> ax; case 1 -> ay; case 2 -> az; default -> throw new IllegalArgumentException("Axis " + axis); }) == 0f;
    }

    public boolean isTrigger() {
        return trigger;
    }

    public boolean isContinuous() {
        return continuous;
    }

    /**
     * Whether a step this body makes that is long for how thin it is (a bullet, a thin blade, a fast sheet) is checked for
     * hitting something static on the way, so it stops at the surface instead of passing through (on by default). Turn it
     * off for a body that is meant to go through, or to save the little it costs.
     */
    public RigidBody setContinuous(boolean continuous) {
        this.continuous = continuous;
        return this;
    }

    /**
     * Makes the body a trigger: other bodies pass through it, and it reports who enters and leaves to the contact
     * listeners. Use a static trigger for a zone (a finish line, a pickup, water), or a kinematic one that moves.
     */
    public RigidBody setTrigger(boolean trigger) {
        this.trigger = trigger;
        wakeUp();
        return this;
    }

    public ContactListener getContactListener() {
        return contactListener;
    }

    /**
     * Hears this body's own contacts and trigger events, alongside the world's listeners.
     */
    public RigidBody setContactListener(ContactListener listener) {
        contactListener = listener;
        return this;
    }

    public int getCollisionLayer() {
        return collisionLayer;
    }

    /**
     * The groups this body belongs to, as bits (1 by default). Two bodies collide only when each one's layer shares a
     * bit with the other's mask.
     */
    public RigidBody setCollisionLayer(int layer) {
        collisionLayer = layer;
        return this;
    }

    public int getCollisionMask() {
        return collisionMask;
    }

    /**
     * The groups this body collides with, as bits (all of them by default). For players that pass through each other:
     * put them on layer 2 and leave bit 2 out of their mask.
     */
    public RigidBody setCollisionMask(int mask) {
        collisionMask = mask;
        return this;
    }

    /**
     * The box around the body's shapes in world space, as of the last step.
     */
    public Bounds getBounds() {
        return bounds;
    }

    public Object getUserData() {
        return userData;
    }

    /**
     * Anything you want to find again from the body, such as the game object it belongs to.
     */
    public RigidBody setUserData(Object userData) {
        this.userData = userData;
        return this;
    }

    @Override
    public String toString() {
        return "RigidBody[" + (node == null ? "" : node.getName() + ", ") + type + ", " + getPosition() + "]";
    }

    // ---- World use ------------------------------------------------------------------------------------------------

    void attach(PhysicsWorld world) {
        this.world = world;
        if (node != null) syncFromNode();
        else teleported();
    }

    void detach() {
        world = null;
    }

    /**
     * Whether a shape has no volume (a plane or a mesh), so the body can't be dynamic.
     */
    boolean hasSurface() {
        for (BodyShape shape : shapes) if (shape.shape() instanceof PlaneShape || shape.shape() instanceof MeshShape) return true;
        return false;
    }

    /**
     * Places each shape in the world and boxes it, grown by {@code margin}.
     */
    /**
     * Whether placing the shapes again would give what they have now: the body hasn't moved since and can't move this
     * step, and its boxes have no room for motion in them. Moving one by hand places its shapes then and there.
     */
    boolean settled() {
        boolean still = type == BodyType.STATIC || (type == BodyType.DYNAMIC ? sleeping : !isMoving());
        return still && speculative == PhysicsWorld.CONTACT_MARGIN && shapePoses.length == scaledShapes.size();
    }

    void updateCollisionShapes(float margin, float dt, Vec3 gravity) {
        // Grow by how far the body could move this step, so contacts are found before it gets there.
        float speed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (type == BodyType.DYNAMIC && !sleeping) speed += gravity.length() * Math.abs(gravityScale) * dt;
        // A spinning body's far end moves faster than its middle: the end of a long stick turning fast covers its length
        // in a step. The margin is how far anything on the body can get.
        float spin = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (spin > 0.5f) speed += spin * reach();
        speculative = Math.min(margin + speed * dt, 4f);
        updateCollisionShapes(speculative);
    }

    /**
     * The farthest any point of the body's shapes is from its centre of mass, a little over (taken from their boxes).
     */
    float reach() {
        return reach;
    }

    /**
     * Whether this step's travel, of the centre and of the farthest point as it turns, is more than the body is thick, so it could
     * cross a wall without its contacts having seen it.
     */
    boolean sweeps(float dt) {
        if (!continuous || type != BodyType.DYNAMIC || sleeping || character != null || scaledShapes.isEmpty()) return false;
        float travel = (float) Math.sqrt((vx + pvx) * (vx + pvx) + (vy + pvy) * (vy + pvy) + (vz + pvz) * (vz + pvz)) * dt;
        float spin = (float) Math.sqrt((wx + pwx) * (wx + pwx) + (wy + pwy) * (wy + pwy) + (wz + pwz) * (wz + pwz));
        travel += Math.min(spin * dt, MAX_TURN_PER_STEP) * reach;
        return travel > thickness;
    }

    /**
     * Half of the narrowest the body is, of its thinnest shape: how far it can move in one step without passing a wall.
     */
    private float thickness() {
        float thin = Float.POSITIVE_INFINITY;
        for (BodyShape shape : scaledShapes) {
            thin = Math.min(thin, switch (shape.shape()) {
                case SphereShape s -> s.radius();
                case CapsuleShape c -> c.radius();
                case BoxShape b -> Math.min(b.halfExtents().x(), Math.min(b.halfExtents().y(), b.halfExtents().z()));
                case ConvexHullShape h -> {
                    float narrowest = Float.POSITIVE_INFINITY;
                    for (Vec3 normal : h.polytope().normals) narrowest = Math.min(narrowest, (h.polytope().max(normal) - h.polytope().min(normal)) * 0.5f);
                    yield narrowest;
                }
                case PlaneShape _, MeshShape _ -> Float.POSITIVE_INFINITY;
            });
        }
        return thin;
    }

    List<BodyShape> placedShapes() {
        return scaledShapes;
    }

    private float computeReach() {
        float far = 0f;
        Vec3 centre = getLocalCenterOfMass();
        for (BodyShape shape : scaledShapes) {
            Shape s = shape.shape();
            if (s instanceof PlaneShape) continue;
            Bounds box = s.bounds();
            for (int corner = 0; corner < 8; corner++) {
                Vec3 point = new Vec3((corner & 1) == 0 ? box.min().x() : box.max().x(),
                        (corner & 2) == 0 ? box.min().y() : box.max().y(),
                        (corner & 4) == 0 ? box.min().z() : box.max().z());
                far = Math.max(far, shape.offset().add(shape.rotation().rotate(point)).sub(centre).length());
            }
        }
        return far;
    }

    void updateCollisionShapes(float margin) {
        int n = scaledShapes.size();
        if (shapePoses.length != n) {
            shapePoses = new Pose[n];
            shapeBounds = new Bounds[n];
        }
        Quat rotation = getRotation();
        Pose body = new Pose(getPosition(), rotation);
        Vec3 min = null, max = null;
        for (int i = 0; i < n; i++) {
            BodyShape shape = scaledShapes.get(i);
            Pose pose = new Pose(body.toWorld(shape.offset()), rotation.mul(shape.rotation()));
            Bounds box = worldBounds(shape.shape(), pose, margin);
            shapePoses[i] = pose;
            shapeBounds[i] = box;
            min = min == null ? box.min() : new Vec3(Math.min(min.x(), box.min().x()), Math.min(min.y(), box.min().y()), Math.min(min.z(), box.min().z()));
            max = max == null ? box.max() : new Vec3(Math.max(max.x(), box.max().x()), Math.max(max.y(), box.max().y()), Math.max(max.z(), box.max().z()));
        }
        bounds = min == null ? new Bounds(body.position(), body.position()) : new Bounds(min, max);
        if (world != null) world.tree.update(this);
    }

    static Bounds worldBounds(Shape shape, Pose pose, float margin) {
        Vec3 grow = new Vec3(margin, margin, margin);
        return switch (shape) {
            case SphereShape s -> {
                Vec3 r = grow.add(new Vec3(s.radius(), s.radius(), s.radius()));
                yield new Bounds(pose.position().sub(r), pose.position().add(r));
            }
            case CapsuleShape c -> {
                Vec3 up = pose.axis(1).mul(c.halfHeight());
                Vec3 a = pose.position().add(up), b = pose.position().sub(up);
                Vec3 r = grow.add(new Vec3(c.radius(), c.radius(), c.radius()));
                yield new Bounds(new Vec3(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())).sub(r),
                        new Vec3(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())).add(r));
            }
            case BoxShape b -> {
                Vec3 h = b.halfExtents();
                Mat3 m = pose.basis();
                Vec3 extent = new Vec3(
                        Math.abs(m.xx()) * h.x() + Math.abs(m.xy()) * h.y() + Math.abs(m.xz()) * h.z(),
                        Math.abs(m.yx()) * h.x() + Math.abs(m.yy()) * h.y() + Math.abs(m.yz()) * h.z(),
                        Math.abs(m.zx()) * h.x() + Math.abs(m.zy()) * h.y() + Math.abs(m.zz()) * h.z()).add(grow);
                yield new Bounds(pose.position().sub(extent), pose.position().add(extent));
            }
            case ConvexHullShape h -> {
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
                for (Vec3 corner : h.polytope().vertices) {
                    Vec3 p = pose.toWorld(corner);
                    minX = Math.min(minX, p.x());
                    minY = Math.min(minY, p.y());
                    minZ = Math.min(minZ, p.z());
                    maxX = Math.max(maxX, p.x());
                    maxY = Math.max(maxY, p.y());
                    maxZ = Math.max(maxZ, p.z());
                }
                yield new Bounds(new Vec3(minX, minY, minZ).sub(grow), new Vec3(maxX, maxY, maxZ).add(grow));
            }
            case MeshShape m -> {
                // The mesh's own box, turned: looser than the triangles, but found without visiting them.
                Bounds local = m.bounds();
                Vec3 h = local.max().sub(local.min()).mul(0.5f);
                Vec3 middle = pose.toWorld(local.min().add(h));
                Mat3 r = pose.basis();
                Vec3 extent = new Vec3(
                        Math.abs(r.xx()) * h.x() + Math.abs(r.xy()) * h.y() + Math.abs(r.xz()) * h.z(),
                        Math.abs(r.yx()) * h.x() + Math.abs(r.yy()) * h.y() + Math.abs(r.yz()) * h.z(),
                        Math.abs(r.zx()) * h.x() + Math.abs(r.zy()) * h.y() + Math.abs(r.zz()) * h.z()).add(grow);
                yield new Bounds(middle.sub(extent), middle.add(extent));
            }
            case PlaneShape p -> p.bounds();
        };
    }

    Bounds collisionBounds() {
        return bounds;
    }

    Pose shapePose(int index) {
        return shapePoses[index];
    }

    Bounds shapeBounds(int index) {
        return shapeBounds[index];
    }

    Shape collisionShape(int index) {
        return scaledShapes.get(index).shape();
    }

    int shapeCount() {
        return shapePoses.length;
    }

    /**
     * Gravity and the gathered forces change the velocity; damping then slows it.
     */
    void integrateVelocity(float dt, Vec3 gravity) {
        if (type != BodyType.DYNAMIC || sleeping) return;
        vx += (fx * invMass + gravity.x() * gravityScale) * dt * lx;
        vy += (fy * invMass + gravity.y() * gravityScale) * dt * ly;
        vz += (fz * invMass + gravity.z() * gravityScale) * dt * lz;
        Vec3 dw = invInertiaWorld.mul(new Vec3(tx, ty, tz));
        wx += dw.x() * dt;
        wy += dw.y() * dt;
        wz += dw.z() * dt;
        if (linearDamping > 0f) {
            float keep = (float) Math.exp(-linearDamping * dt);
            vx *= keep;
            vy *= keep;
            vz *= keep;
        }
        if (angularDamping > 0f) {
            float keep = (float) Math.exp(-angularDamping * dt);
            wx *= keep;
            wy *= keep;
            wz *= keep;
        }
    }

    /**
     * The velocity moves the centre of mass and the spin turns the body.
     */
    void integratePosition(float dt) {
        if (type == BodyType.STATIC || sleeping) return;
        limitSpin(dt);
        cx += (vx + pvx) * dt;
        cy += (vy + pvy) * dt;
        cz += (vz + pvz) * dt;
        pvx = pvy = pvz = 0f;
        setOrientation(orientationAfter(dt));
        pwx = pwy = pwz = 0f;
    }

    /**
     * Slows a spin that would turn the body past a quarter turn in {@code dt}, where the rotation update falls apart.
     */
    void limitSpin(float dt) {
        float speed = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (speed * dt > MAX_TURN_PER_STEP) {
            float s = MAX_TURN_PER_STEP / (speed * dt);
            wx *= s;
            wy *= s;
            wz *= s;
        }
    }

    /**
     * The orientation {@code integratePosition(dt)} would leave, from the spin as it is now; changes nothing.
     */
    Quat orientationAfter(float dt) {
        // dq/dt = 0.5 * (w, 0) * q
        float h = 0.5f * dt, tx = wx + pwx, ty = wy + pwy, tz = wz + pwz;
        float nx = qx + h * (tx * qw + ty * qz - tz * qy);
        float ny = qy + h * (ty * qw + tz * qx - tx * qz);
        float nz = qz + h * (tz * qw + tx * qy - ty * qx);
        float nw = qw - h * (tx * qx + ty * qy + tz * qz);
        return new Quat(nx, ny, nz, nw).normalized();
    }

    void savePrevious() {
        previousPosition = getPosition();
        previousRotation = getRotation();
    }

    /**
     * Whether the node has been moved or turned since the body last wrote to it.
     */
    boolean nodeMoved() {
        return node != null && writtenPosition != null
                && (!node.getPosition().equals(writtenPosition) || !node.getRotation().equals(writtenRotation));
    }

    /**
     * Follows the node to where it was moved, keeping the velocity and the shapes.
     */
    void followNode() {
        Mat4.Trs trs = node.worldMatrix().decompose();
        setOrientation(trs.rotation());
        setCenter(trs.position().add(getRotation().rotate(localCenter)));
        teleported();
    }

    /**
     * Where the body is {@code alpha} of the way from the previous step to the current one; just where it is, for a
     * body that isn't stepped.
     */
    Pose interpolatedPose(float alpha) {
        if (alpha >= 1f || world == null) return new Pose(getPosition(), getRotation());
        return new Pose(previousPosition.lerp(getPosition(), alpha), previousRotation.slerp(getRotation(), alpha));
    }

    /**
     * Puts the node at the place {@code alpha} of the way from the previous step to the current one.
     */
    void writeNode(float alpha) {
        if (node == null) return;
        Pose pose = interpolatedPose(alpha);
        Vec3 position = pose.position();
        Quat rotation = pose.rotation();
        Node3D parent = node.getParent();
        if (parent != null) {
            Mat4 parentWorld = parent.worldMatrix();
            position = parentWorld.inverse().transformPoint(position);
            rotation = parentWorld.decompose().rotation().conjugate().mul(rotation);
        }
        node.setPosition(position);
        node.setRotation(rotation);
        writtenPosition = node.getPosition();
        writtenRotation = node.getRotation();
    }

    /**
     * Applies impulse (px, py, pz) at offset (rx, ry, rz) from the centre of mass, to the velocities or the pseudo
     * velocities.
     */
    void addVelocity(float px, float py, float pz, float rx, float ry, float rz, boolean pseudo) {
        if (invMass == 0f) return;
        float dvx = px * invMass * lx, dvy = py * invMass * ly, dvz = pz * invMass * lz;
        float tx = ry * pz - rz * py, ty = rz * px - rx * pz, tz = rx * py - ry * px;
        Mat3 i = invInertiaWorld;
        float dwx = i.xx() * tx + i.xy() * ty + i.xz() * tz;
        float dwy = i.yx() * tx + i.yy() * ty + i.yz() * tz;
        float dwz = i.zx() * tx + i.zy() * ty + i.zz() * tz;
        if (pseudo) {
            pvx += dvx;
            pvy += dvy;
            pvz += dvz;
            pwx += dwx;
            pwy += dwy;
            pwz += dwz;
        } else {
            vx += dvx;
            vy += dvy;
            vz += dvz;
            wx += dwx;
            wy += dwy;
            wz += dwz;
        }
    }

    /**
     * Applies a linear impulse (px, py, pz) through the centre of mass and an angular impulse (tx, ty, tz), to the
     * velocities: how a joint pushes, since its turning part isn't always a push at a point.
     */
    void addImpulse(float px, float py, float pz, float tx, float ty, float tz) {
        if (invMass == 0f) return;
        vx += px * invMass * lx;
        vy += py * invMass * ly;
        vz += pz * invMass * lz;
        Mat3 i = invInertiaWorld;
        wx += i.xx() * tx + i.xy() * ty + i.xz() * tz;
        wy += i.yx() * tx + i.yy() * ty + i.yz() * tz;
        wz += i.zx() * tx + i.zy() * ty + i.zz() * tz;
    }

    /**
     * As {@link #addImpulse}, but moving and turning the body at once instead of changing its velocity: a joint's
     * position fix after the step.
     */
    void nudge(float px, float py, float pz, float tx, float ty, float tz) {
        if (invMass == 0f) return;
        Mat3 i = invInertiaWorld;
        shift(px * invMass * lx, py * invMass * ly, pz * invMass * lz,
                i.xx() * tx + i.xy() * ty + i.xz() * tz, i.yx() * tx + i.yy() * ty + i.yz() * tz, i.zx() * tx + i.zy() * ty + i.zz() * tz);
    }

    /**
     * Moves the centre of mass by (dx, dy, dz) and turns the body by the angle-times-axis (ax, ay, az), without
     * changing its velocity.
     */
    void shift(float dx, float dy, float dz, float ax, float ay, float az) {
        cx += dx;
        cy += dy;
        cz += dz;
        if (ax == 0f && ay == 0f && az == 0f) return;
        float nx = qx + 0.5f * (ax * qw + ay * qz - az * qy);
        float ny = qy + 0.5f * (ay * qw + az * qx - ax * qz);
        float nz = qz + 0.5f * (az * qw + ax * qy - ay * qx);
        float nw = qw - 0.5f * (ax * qx + ay * qy + az * qz);
        setOrientation(new Quat(nx, ny, nz, nw).normalized());
    }

    /**
     * {@code 1 / mass} times the lock along each world axis: 0 along a locked axis.
     */
    Vec3 inverseMassAxes() {
        return new Vec3(invMass * lx, invMass * ly, invMass * lz);
    }

    Mat3 inverseInertiaWorld() {
        return invInertiaWorld;
    }

    private void setCenter(Vec3 center) {
        cx = center.x();
        cy = center.y();
        cz = center.z();
    }

    private void setOrientation(Quat q) {
        qx = q.x();
        qy = q.y();
        qz = q.z();
        qw = q.w();
        updateWorldInertia();
    }

    /**
     * After a jump, nothing to smooth from: the node goes straight to the new place.
     */
    private void teleported() {
        wakeUp();
        savePrevious();
        if (world != null) {
            writeNode(1f);
            updateCollisionShapes(PhysicsWorld.CONTACT_MARGIN);
        }
    }

    private void updateWorldInertia() {
        if (type != BodyType.DYNAMIC) {
            invInertiaWorld = Mat3.ZERO;
            return;
        }
        Mat3 m = invInertiaLocal.rotated(Mat3.rotation(getRotation()));
        // A locked axis has no inverse inertia, so nothing can turn the body around it.
        invInertiaWorld = new Mat3(
                m.xx() * ax * ax, m.xy() * ax * ay, m.xz() * ax * az,
                m.yx() * ay * ax, m.yy() * ay * ay, m.yz() * ay * az,
                m.zx() * az * ax, m.zy() * az * ay, m.zz() * az * az);
    }

    /**
     * Where a placed shape's centre of mass is in the body's space.
     */
    private static Vec3 shapeCenter(BodyShape s) {
        return s.offset().add(s.rotation().rotate(s.shape().centerOfMass()));
    }

    private void updateMassProperties() {
        Vec3 origin = getPosition();
        List<BodyShape> scaled = new ArrayList<>(shapes.size());
        float volume = 0f;
        Vec3 weighted = Vec3.ZERO;
        for (BodyShape shape : shapes) {
            BodyShape s = shape.scaled(scale);
            scaled.add(s);
            float v = s.shape().volume();
            volume += v;
            weighted = weighted.add(shapeCenter(s).mul(v));
        }
        scaledShapes = List.copyOf(scaled);

        Vec3 center = centerOverride != null ? centerOverride : volume > 0f ? weighted.mul(1f / volume) : Vec3.ZERO;
        Mat3 inertia;
        if (inertiaOverride != null) {
            inertia = Mat3.diagonal(inertiaOverride);
        } else if (volume <= 0f) {
            inertia = Mat3.diagonal(new SphereShape(0.5f).unitInertia().mul(mass));
        } else {
            inertia = Mat3.ZERO;
            for (BodyShape s : scaled) {
                float m = mass * s.shape().volume() / volume;
                Mat3 unit = s.shape() instanceof ConvexHullShape hull ? hull.unitInertiaTensor() : Mat3.diagonal(s.shape().unitInertia());
                Mat3 own = unit.mul(m).rotated(Mat3.rotation(s.rotation()));
                inertia = inertia.add(own).add(Mat3.pointMass(m, shapeCenter(s).sub(center)));
            }
        }
        localCenter = center;
        reach = computeReach();
        thickness = thickness();
        inertiaLocal = inertia;
        invInertiaLocal = inertia.inverse();
        invMass = type == BodyType.DYNAMIC ? 1f / mass : 0f;
        setCenter(origin.add(getRotation().rotate(localCenter)));
        updateWorldInertia();
        if (world != null) updateCollisionShapes(PhysicsWorld.CONTACT_MARGIN);
    }
}
