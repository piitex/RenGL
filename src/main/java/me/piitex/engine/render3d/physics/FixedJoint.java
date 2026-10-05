package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;

/**
 * Welds two bodies together as they are, so they move as one: a sign bolted to a post, or parts that should come
 * apart later (give it a {@link #setBreakForce break force}). For parts that never come apart, one body with several
 * shapes is cheaper and stiffer.
 */
public final class FixedJoint extends Joint {
    private Vec3 localA, localB;
    private Quat relative;
    private Vec3 ra, rb;
    private Mat3 linearMass, angularMass;
    private Vec3 linear = Vec3.ZERO, angular = Vec3.ZERO;

    /**
     * Welds {@code a} to {@code b}, or to the world where it is when {@code b} is null.
     */
    public FixedJoint(RigidBody a, RigidBody b) {
        super(a, b);
    }

    @Override
    Vec3 localAnchorA() {
        return localA;
    }

    @Override
    Vec3 localAnchorB() {
        return localB;
    }

    @Override
    void anchor() {
        // Joined halfway between the two centres, which spreads the work evenly between them.
        Vec3 middle = held() ? center(b) : center(a).add(center(b)).mul(0.5f);
        localA = toLocal(a, middle);
        localB = toLocal(b, middle);
        relative = a.getRotation().conjugate().mul(b.getRotation());
    }

    @Override
    void resetImpulses() {
        linear = angular = Vec3.ZERO;
    }

    @Override
    void prepare(float dt) {
        this.dt = dt;
        ra = offset(a, localA);
        rb = offset(b, localB);
        linearMass = pointMass(a, ra, b, rb).inverse();
        angularMass = angularMass(a, b).inverse();
        applyAngular(a, b, angular);
        applyPoint(a, ra, b, rb, linear);
    }

    @Override
    void solveVelocity() {
        Vec3 t = angularMass.mul(angularVelocity(a, b)).negate();
        angular = angular.add(t);
        applyAngular(a, b, t);
        Vec3 p = linearMass.mul(anchorVelocity(a, ra, b, rb)).negate();
        linear = linear.add(p);
        applyPoint(a, ra, b, rb, p);
    }

    @Override
    boolean solvePosition() {
        float angle = solveRotation(a, b, relative);
        float gap = solvePoint(a, localA, b, localB);
        return gap <= LINEAR_SLOP && angle <= ANGULAR_SLOP;
    }

    @Override
    Vec3 linearImpulse() {
        return linear;
    }

    @Override
    Vec3 angularImpulse() {
        return angular;
    }
}
