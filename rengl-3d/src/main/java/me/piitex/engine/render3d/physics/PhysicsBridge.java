package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;

/**
 * What a {@link PhysicsBackend} needs from the physics classes beyond their public methods: writing a step's result
 * into a body without the side effects of {@link RigidBody#setPosition} (which is a teleport), making the contacts it
 * reports, and reading where a joint holds its bodies. Games have no use for it.
 */
public final class PhysicsBridge {
    private PhysicsBridge() {
    }

    /**
     * Where a joint holds its two bodies, in world space as of now. {@code first} is null when the joint holds
     * {@code second} to the world; angles and distances are {@code second}'s against {@code first}.
     */
    public record JointFrame(RigidBody first, RigidBody second, Vec3 anchorFirst, Vec3 anchorSecond) {
    }

    /**
     * The number the world gave the body when it was added: lower for a body added earlier.
     */
    public static int id(RigidBody body) {
        return body.id;
    }

    /**
     * Puts a body where a step left it. {@code position} is the body's origin, {@code velocity} that of its centre of
     * mass. Nothing is woken and the node isn't touched: the world places the nodes after the step.
     */
    public static void place(RigidBody body, Vec3 position, Quat rotation, Vec3 velocity, Vec3 angularVelocity, boolean sleeping) {
        body.simulated(position, rotation, velocity, angularVelocity, sleeping);
    }

    /**
     * The inertia given with {@link RigidBody#setInertia}, or null when it comes from the shapes.
     */
    public static Vec3 inertiaOverride(RigidBody body) {
        return body.inertiaOverride();
    }

    /**
     * The centre of mass given with {@link RigidBody#setCenterOfMass}, or null when it comes from the shapes.
     */
    public static Vec3 centerOverride(RigidBody body) {
        return body.centerOverride();
    }

    /**
     * A contact between shape {@code shapeA} of {@code a} and shape {@code shapeB} of {@code b} (indices into
     * {@link RigidBody#getShapes()}), with the normal from A to B and up to four points; more are dropped, the deepest
     * kept. {@code depths} are overlaps in metres (negative for a gap) and {@code impulses} the push along the normal
     * at each point in newton seconds. The contact counts as touching whatever its depths, since the backend decides
     * what touches.
     */
    public static Contact contact(RigidBody a, int shapeA, RigidBody b, int shapeB, Vec3 normal, Vec3[] points, float[] depths, float[] impulses, int count) {
        Contact contact = new Contact(a, shapeA, b, shapeB, -1);
        contact.report(normal, points, depths, impulses, count);
        return contact;
    }

    /**
     * Where {@code joint} holds its bodies now, or null when it isn't in a world.
     */
    public static JointFrame frame(Joint joint) {
        if (joint.getWorld() == null || joint.localAnchorA() == null) return null;
        boolean held = joint.held();
        Vec3 onFirst = joint.a.getPosition().add(joint.a.getRotation().rotate(joint.localAnchorA()));
        Vec3 onSecond = joint.b.getPosition().add(joint.b.getRotation().rotate(joint.localAnchorB()));
        return new JointFrame(held ? null : joint.a, joint.b, onFirst, onSecond);
    }

    /**
     * Tells a joint what holding its bodies together took in the last step, for {@link Joint#getReactionForce()} and
     * {@link Joint#getReactionTorque()}: the force and torque on its second body.
     */
    public static void reaction(Joint joint, Vec3 force, Vec3 torque) {
        joint.reported(force, torque);
    }
}
