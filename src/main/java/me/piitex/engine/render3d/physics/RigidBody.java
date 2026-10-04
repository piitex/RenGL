package me.piitex.engine.render3d.physics;

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
 * velocity. The node's scale stretches the shapes (read when the body is added, or by {@link #syncFromNode()}).
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
    private float lx = 1f, ly = 1f, lz = 1f;
    private float ax = 1f, ay = 1f, az = 1f;

    private float linearDamping;
    private float angularDamping = 0.05f;
    private float gravityScale = 1f;
    private Object userData;

    private Vec3 previousPosition = Vec3.ZERO;
    private Quat previousRotation = Quat.IDENTITY;
    private Vec3 writtenPosition;
    private Quat writtenRotation;

    /**
     * A body that moves {@code node}, which may be null for an invisible body.
     */
    public RigidBody(Node3D node, BodyType type) {
        this.node = node;
        this.type = type == null ? BodyType.DYNAMIC : type;
        updateMassProperties();
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
        this.type = type == null ? BodyType.DYNAMIC : type;
        if (this.type == BodyType.STATIC) {
            setVelocity(Vec3.ZERO);
            setAngularVelocity(Vec3.ZERO);
        }
        clearForces();
        updateMassProperties();
        return this;
    }

    public boolean isDynamic() {
        return type == BodyType.DYNAMIC;
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
        shapes.add(shape);
        updateMassProperties();
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
        scale = new Vec3(Math.abs(trs.scale().x()), Math.abs(trs.scale().y()), Math.abs(trs.scale().z()));
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
     * Gravity and the gathered forces change the velocity; damping then slows it.
     */
    void integrateVelocity(float dt, Vec3 gravity) {
        if (type != BodyType.DYNAMIC) return;
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
        if (type == BodyType.STATIC) return;
        float speed = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (speed * dt > MAX_TURN_PER_STEP) { // past a quarter turn per step the rotation update falls apart
            float s = MAX_TURN_PER_STEP / (speed * dt);
            wx *= s;
            wy *= s;
            wz *= s;
        }
        cx += vx * dt;
        cy += vy * dt;
        cz += vz * dt;
        // dq/dt = 0.5 * (w, 0) * q
        float h = 0.5f * dt;
        float nx = qx + h * (wx * qw + wy * qz - wz * qy);
        float ny = qy + h * (wy * qw + wz * qx - wx * qz);
        float nz = qz + h * (wz * qw + wx * qy - wy * qx);
        float nw = qw - h * (wx * qx + wy * qy + wz * qz);
        setOrientation(new Quat(nx, ny, nz, nw).normalized());
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
     * Puts the node at the place {@code alpha} of the way from the previous step to the current one.
     */
    void writeNode(float alpha) {
        if (node == null) return;
        Vec3 position = getPosition();
        Quat rotation = getRotation();
        if (alpha < 1f) {
            position = previousPosition.lerp(position, alpha);
            rotation = previousRotation.slerp(rotation, alpha);
        }
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
        savePrevious();
        if (world != null) writeNode(1f);
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
            weighted = weighted.add(s.offset().mul(v));
        }
        scaledShapes = List.copyOf(scaled);

        Vec3 center = centerOverride != null ? centerOverride : volume > 0f ? weighted.mul(1f / volume) : Vec3.ZERO;
        Mat3 inertia;
        if (inertiaOverride != null) {
            inertia = Mat3.diagonal(inertiaOverride);
        } else if (scaled.isEmpty()) {
            inertia = Mat3.diagonal(new SphereShape(0.5f).unitInertia().mul(mass));
        } else {
            inertia = Mat3.ZERO;
            for (BodyShape s : scaled) {
                float m = mass * s.shape().volume() / volume;
                Mat3 own = Mat3.diagonal(s.shape().unitInertia().mul(m)).rotated(Mat3.rotation(s.rotation()));
                inertia = inertia.add(own).add(Mat3.pointMass(m, s.offset().sub(center)));
            }
        }
        localCenter = center;
        inertiaLocal = inertia;
        invInertiaLocal = inertia.inverse();
        invMass = type == BodyType.DYNAMIC ? 1f / mass : 0f;
        setCenter(origin.add(getRotation().rotate(localCenter)));
        updateWorldInertia();
    }
}
