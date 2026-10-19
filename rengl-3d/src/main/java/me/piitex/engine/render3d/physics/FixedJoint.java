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
        if (direct) return;
        Vec3 t = angularMass.mul(angularVelocity(a, b)).negate();
        angular = angular.add(t);
        applyAngular(a, b, t);
        Vec3 p = linearMass.mul(anchorVelocity(a, ra, b, rb)).negate();
        linear = linear.add(p);
        applyPoint(a, ra, b, rb, p);
    }

    @Override
    int directRows() {
        return 6;
    }

    @Override
    void directVelocityRows(Row[] rows) {
        pointRows(rows, 0, a, ra, b, rb);
        rows[3].angular(a, b, Vec3.X);
        rows[4].angular(a, b, Vec3.UP);
        rows[5].angular(a, b, Vec3.Z);
    }

    @Override
    void directImpulses(double[] lambda, int at) {
        linear = linear.add(new Vec3((float) lambda[at], (float) lambda[at + 1], (float) lambda[at + 2]));
        angular = angular.add(new Vec3((float) lambda[at + 3], (float) lambda[at + 4], (float) lambda[at + 5]));
    }

    @Override
    boolean directPositionRows(Row[] rows, float[] error) {
        Vec3 pa = offset(a, localA), pb = offset(b, localB);
        Vec3 gap = center(b).add(pb).sub(center(a).add(pa));
        float length = gap.length();
        if (length > MAX_LINEAR_CORRECTION) gap = gap.mul(MAX_LINEAR_CORRECTION / length);
        pointRows(rows, 0, a, pa, b, pb);
        error[0] = gap.x();
        error[1] = gap.y();
        error[2] = gap.z();
        Vec3 turn = rotationError(a, b, relative);
        float angle = turn.length();
        if (angle > MAX_ANGULAR_CORRECTION) turn = turn.mul(MAX_ANGULAR_CORRECTION / angle);
        rows[3].angular(a, b, Vec3.X);
        rows[4].angular(a, b, Vec3.UP);
        rows[5].angular(a, b, Vec3.Z);
        error[3] = turn.x();
        error[4] = turn.y();
        error[5] = turn.z();
        return length <= LINEAR_SLOP && angle <= ANGULAR_SLOP;
    }

    @Override
    boolean solvePosition() {
        if (direct) return true;
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
