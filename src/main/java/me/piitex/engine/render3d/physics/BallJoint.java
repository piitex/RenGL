package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

/**
 * Pins two bodies together at one point, letting them turn freely around it: a pendulum, a chain link, a trailer
 * hitch, a lamp on a cord.
 */
public final class BallJoint extends Joint {
    private final Vec3 anchor;
    private Vec3 localA, localB;
    private Vec3 ra, rb;
    private Mat3 mass;
    private Vec3 impulse = Vec3.ZERO;

    /**
     * Joins {@code a} and {@code b} at {@code anchor}, in world space. With {@code b} null, {@code a} hangs from that
     * point in the world.
     */
    public BallJoint(RigidBody a, RigidBody b, Vec3 anchor) {
        super(a, b);
        this.anchor = anchor;
    }

    /**
     * The point the bodies are pinned at, in world space, as of now; it moves with body A when there are two bodies.
     */
    public Vec3 getAnchor() {
        return localA == null ? anchor : a.getPosition().add(a.getRotation().rotate(localA));
    }

    @Override
    void anchor() {
        localA = toLocal(a, anchor);
        localB = toLocal(b, anchor);
    }

    @Override
    void resetImpulses() {
        impulse = Vec3.ZERO;
    }

    @Override
    void prepare(float dt) {
        this.dt = dt;
        ra = offset(a, localA);
        rb = offset(b, localB);
        mass = pointMass(a, ra, b, rb).inverse();
        applyPoint(a, ra, b, rb, impulse);
    }

    @Override
    void solveVelocity() {
        Vec3 p = mass.mul(anchorVelocity(a, ra, b, rb)).negate();
        impulse = impulse.add(p);
        applyPoint(a, ra, b, rb, p);
    }

    @Override
    boolean solvePosition() {
        return solvePoint(a, localA, b, localB) <= LINEAR_SLOP;
    }

    @Override
    Vec3 linearImpulse() {
        return impulse;
    }

    @Override
    Vec3 angularImpulse() {
        return Vec3.ZERO;
    }
}
