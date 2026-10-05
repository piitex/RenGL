package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;

/**
 * Lets two bodies turn against each other around one axis only: a door on its frame, a wheel on an axle, a lever, a
 * drawbridge. {@link #setLimits Limits} stop the turn at two angles, the way a door stops at the wall, and a
 * {@link #setMotor motor} turns it at a set speed, as far as its torque allows.
 * <p>
 * The angle is body B's turn against body A around the axis, counter-clockwise looking down the axis, and is 0 where
 * the bodies were when the joint was added.
 */
public final class HingeJoint extends Joint {
    private final Vec3 anchor, axis;
    private Vec3 localA, localB, axisA, axisB;
    private Quat reference;

    private float lower = 0f, upper = 0f;
    private boolean limited;
    private boolean motor;
    private float motorSpeed, maxMotorTorque;

    private Vec3 ra, rb, worldAxis, b1, b2, axialPush;
    private Mat3 pointMass;
    private float k11, k12, k22, axialMass, angle;
    private Vec3 pointImpulse = Vec3.ZERO;
    private float impulse1, impulse2, motorImpulse, lowerImpulse, upperImpulse;

    /**
     * Joins {@code a} and {@code b} at {@code anchor}, turning around {@code axis}, both in world space. With {@code b}
     * null, {@code a} turns around a fixed axis in the world.
     */
    public HingeJoint(RigidBody a, RigidBody b, Vec3 anchor, Vec3 axis) {
        super(a, b);
        if (axis.lengthSquared() < 1e-12f) throw new IllegalArgumentException("The hinge axis has no direction");
        this.anchor = anchor;
        this.axis = axis.normalized();
    }

    /**
     * Stops the turn below {@code lower} and above {@code upper}, in radians between -pi and pi; a door that opens a
     * quarter turn one way is {@code setLimits(0, (float) Math.PI / 2)}.
     */
    public HingeJoint setLimits(float lower, float upper) {
        if (!(lower <= upper)) throw new IllegalArgumentException("The lower limit " + lower + " is above the upper " + upper);
        this.lower = Math.max(lower, (float) -Math.PI);
        this.upper = Math.min(upper, (float) Math.PI);
        limited = true;
        wake();
        return this;
    }

    public HingeJoint clearLimits() {
        limited = false;
        lowerImpulse = upperImpulse = 0f;
        wake();
        return this;
    }

    public boolean hasLimits() {
        return limited;
    }

    public float getLowerLimit() {
        return lower;
    }

    public float getUpperLimit() {
        return upper;
    }

    /**
     * Turns the hinge at {@code speed} radians per second, with at most {@code maxTorque} newton metres. A speed of 0
     * holds it still against whatever pushes on it, up to that torque, like a stiff hinge or a brake.
     */
    public HingeJoint setMotor(float speed, float maxTorque) {
        motor = true;
        motorSpeed = speed;
        maxMotorTorque = Math.max(0f, maxTorque);
        wake();
        return this;
    }

    public HingeJoint clearMotor() {
        motor = false;
        motorImpulse = 0f;
        wake();
        return this;
    }

    public boolean hasMotor() {
        return motor;
    }

    public float getMotorSpeed() {
        return motorSpeed;
    }

    public float getMaxMotorTorque() {
        return maxMotorTorque;
    }

    /**
     * The torque the motor used in the last step, in newton metres.
     */
    public float getMotorTorque() {
        return motorImpulse / dt;
    }

    /**
     * Body B's turn against body A, in radians from -pi to pi.
     */
    public float getAngle() {
        if (reference == null) return 0f;
        Quat delta = a.getRotation().conjugate().mul(b.getRotation()).mul(reference.conjugate());
        float s = delta.x() * axisA.x() + delta.y() * axisA.y() + delta.z() * axisA.z();
        return wrapAngle(2f * (float) Math.atan2(s, delta.w()));
    }

    /**
     * How fast body B turns against body A around the axis, in radians per second.
     */
    public float getAngularSpeed() {
        return angularVelocity(a, b).dot(getAxis());
    }

    /**
     * The hinge axis in world space, as of now; it turns with body A when there are two bodies.
     */
    public Vec3 getAxis() {
        return axisA == null ? axis : a.getRotation().rotate(axisA);
    }

    /**
     * The point the hinge turns around, in world space, as of now; it moves with body A when there are two bodies.
     */
    public Vec3 getAnchor() {
        return localA == null ? anchor : a.getPosition().add(a.getRotation().rotate(localA));
    }

    private void wake() {
        if (getWorld() == null) return;
        a.wakeUp();
        b.wakeUp();
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
        localA = toLocal(a, anchor);
        localB = toLocal(b, anchor);
        axisA = a.getRotation().conjugate().rotate(axis);
        axisB = b.getRotation().conjugate().rotate(axis);
        reference = a.getRotation().conjugate().mul(b.getRotation());
    }

    @Override
    void resetImpulses() {
        pointImpulse = Vec3.ZERO;
        impulse1 = impulse2 = motorImpulse = lowerImpulse = upperImpulse = 0f;
    }

    @Override
    void prepare(float dt) {
        this.dt = dt;
        ra = offset(a, localA);
        rb = offset(b, localB);
        pointMass = pointMass(a, ra, b, rb).inverse();
        worldAxis = a.getRotation().rotate(axisA);
        Vec3[] across = perpendiculars(worldAxis);
        b1 = across[0];
        b2 = across[1];
        Mat3 k = angularMass(a, b);
        float a11 = b1.dot(k.mul(b1)), a12 = b1.dot(k.mul(b2)), a22 = b2.dot(k.mul(b2));
        float det = a11 * a22 - a12 * a12;
        float inv = Math.abs(det) > 1e-20f ? 1f / det : 0f;
        k11 = a22 * inv;
        k12 = -a12 * inv;
        k22 = a11 * inv;
        // A turn around the axis comes with the push at the pivot that keeps the pivot still, so the motor and the
        // stops turn the body around the pivot, not around its centre of mass. Solved on their own, the stops would
        // fight the pivot and let a slammed door bounce off them.
        Vec3 ta = a.inverseInertiaWorld().mul(worldAxis), tb = b.inverseInertiaWorld().mul(worldAxis);
        Vec3 drift = tb.cross(rb).add(ta.cross(ra));
        axialPush = pointMass.mul(drift).negate();
        float axial = worldAxis.dot(k.mul(worldAxis))
                + worldAxis.dot(b.inverseInertiaWorld().mul(rb.cross(axialPush)).add(a.inverseInertiaWorld().mul(ra.cross(axialPush))));
        axialMass = axial > 1e-12f ? 1f / axial : 0f;
        angle = getAngle();
        if (!limited) lowerImpulse = upperImpulse = 0f;
        if (!motor) motorImpulse = 0f;

        // The pivot's share of last step's motor and stop impulses is already in its own impulse.
        applyPoint(a, ra, b, rb, pointImpulse);
        applyAngular(a, b, b1.mul(impulse1).add(b2.mul(impulse2)).add(worldAxis.mul(motorImpulse + lowerImpulse - upperImpulse)));
    }

    /**
     * A turn of {@code impulse} around the axis, with the push at the pivot that goes with it.
     */
    private void applyAxial(float impulse) {
        if (impulse == 0f) return;
        applyAngular(a, b, worldAxis.mul(impulse));
        Vec3 p = axialPush.mul(impulse);
        applyPoint(a, ra, b, rb, p);
        pointImpulse = pointImpulse.add(p);
    }

    @Override
    void solveVelocity() {
        if (motor) {
            float speed = angularVelocity(a, b).dot(worldAxis) - motorSpeed;
            float max = maxMotorTorque * dt;
            float old = motorImpulse;
            motorImpulse = Math.clamp(old - axialMass * speed, -max, max);
            applyAxial(motorImpulse - old);
        }
        if (limited) {
            // Each stop may let the hinge close in on it this step, but not pass it.
            float gap = angle - lower;
            float speed = angularVelocity(a, b).dot(worldAxis);
            float old = lowerImpulse;
            lowerImpulse = Math.max(old - axialMass * (speed + Math.max(gap, 0f) / dt), 0f);
            applyAxial(lowerImpulse - old);

            gap = upper - angle;
            speed = -angularVelocity(a, b).dot(worldAxis);
            old = upperImpulse;
            upperImpulse = Math.max(old - axialMass * (speed + Math.max(gap, 0f) / dt), 0f);
            applyAxial(old - upperImpulse);
        }
        Vec3 w = angularVelocity(a, b);
        float c1 = b1.dot(w), c2 = b2.dot(w);
        float l1 = -(k11 * c1 + k12 * c2), l2 = -(k12 * c1 + k22 * c2);
        impulse1 += l1;
        impulse2 += l2;
        applyAngular(a, b, b1.mul(l1).add(b2.mul(l2)));

        Vec3 p = pointMass.mul(anchorVelocity(a, ra, b, rb)).negate();
        pointImpulse = pointImpulse.add(p);
        applyPoint(a, ra, b, rb, p);
    }

    @Override
    boolean solvePosition() {
        // Line the axes up again: B's axis has drifted from A's by about their cross product.
        Vec3 axisOnA = a.getRotation().rotate(axisA), axisOnB = b.getRotation().rotate(axisB);
        Vec3 drift = axisOnA.cross(axisOnB);
        float angularError = drift.length();
        if (angularError > 1e-7f) {
            Vec3[] across = perpendiculars(axisOnA);
            Mat3 k = angularMass(a, b);
            float a11 = across[0].dot(k.mul(across[0])), a12 = across[0].dot(k.mul(across[1])), a22 = across[1].dot(k.mul(across[1]));
            float det = a11 * a22 - a12 * a12;
            if (Math.abs(det) > 1e-20f) {
                float scale = angularError > MAX_ANGULAR_CORRECTION ? MAX_ANGULAR_CORRECTION / angularError : 1f;
                float c1 = across[0].dot(drift) * scale, c2 = across[1].dot(drift) * scale;
                float l1 = -(a22 * c1 - a12 * c2) / det, l2 = -(-a12 * c1 + a11 * c2) / det;
                nudgeAngular(a, b, across[0].mul(l1).add(across[1].mul(l2)));
            }
        }
        if (limited) {
            float now = getAngle();
            float c = now < lower ? now - lower : now > upper ? now - upper : 0f;
            if (c != 0f) {
                Vec3 n = a.getRotation().rotate(axisA);
                float k = n.dot(angularMass(a, b).mul(n));
                if (k > 1e-12f) nudgeAngular(a, b, n.mul(-Math.clamp(c, -MAX_ANGULAR_CORRECTION, MAX_ANGULAR_CORRECTION) / k));
                angularError = Math.max(angularError, Math.abs(c));
            }
        }
        float gap = solvePoint(a, localA, b, localB);
        return gap <= LINEAR_SLOP && angularError <= ANGULAR_SLOP;
    }

    @Override
    Vec3 linearImpulse() {
        return pointImpulse;
    }

    @Override
    Vec3 angularImpulse() {
        if (worldAxis == null) return Vec3.ZERO;
        return b1.mul(impulse1).add(b2.mul(impulse2)).add(worldAxis.mul(motorImpulse + lowerImpulse - upperImpulse));
    }
}
