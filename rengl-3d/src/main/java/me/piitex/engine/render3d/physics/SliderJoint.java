package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;

/**
 * Lets two bodies slide against each other along one axis, without turning: a drawer, a piston, a lift on its rails,
 * a sliding door. {@link #setLimits Limits} stop it at two distances and a {@link #setMotor motor} drives it at a set
 * speed, as far as its force allows.
 * <p>
 * The {@link #getTranslation() translation} is how far body B has slid along the axis from where it was when the joint
 * was added.
 */
public final class SliderJoint extends Joint {
    private final Vec3 anchor, axis;
    private Vec3 localA, localB, axisA;
    private Quat relative;

    private float lower, upper;
    private boolean limited;
    private boolean motor;
    private float motorSpeed, maxMotorForce;

    private Vec3 ra, rb;
    private float translation;
    private Mat3 angularMass;
    private final Row across1 = new Row(), across2 = new Row(), along = new Row();
    private Vec3 angularImpulse = Vec3.ZERO;
    /** B's anchor from A's, as of the last {@link #setRows}. */
    private Vec3 spread = Vec3.ZERO;
    private float impulse1, impulse2, motorImpulse, lowerImpulse, upperImpulse;

    /**
     * Joins {@code a} and {@code b}, sliding along {@code axis} in world space; {@code anchor} is the point on both that
     * the translation is measured from. With {@code b} null, {@code a} slides along a fixed rail in the world.
     */
    public SliderJoint(RigidBody a, RigidBody b, Vec3 anchor, Vec3 axis) {
        super(a, b);
        if (axis.lengthSquared() < 1e-12f) throw new IllegalArgumentException("The slider axis has no direction");
        this.anchor = anchor;
        this.axis = axis.normalized();
    }

    /**
     * Keeps the translation between {@code lower} and {@code upper}, in metres.
     */
    public SliderJoint setLimits(float lower, float upper) {
        if (!(lower <= upper)) throw new IllegalArgumentException("The lower limit " + lower + " is above the upper " + upper);
        this.lower = lower;
        this.upper = upper;
        limited = true;
        wake();
        return this;
    }

    public SliderJoint clearLimits() {
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
     * Slides body B along the axis at {@code speed} metres per second, pushing with at most {@code maxForce} newtons.
     * A lift carrying 100 kg upwards needs more than 981 N.
     */
    public SliderJoint setMotor(float speed, float maxForce) {
        motor = true;
        motorSpeed = speed;
        maxMotorForce = Math.max(0f, maxForce);
        wake();
        return this;
    }

    public SliderJoint clearMotor() {
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

    public float getMaxMotorForce() {
        return maxMotorForce;
    }

    /**
     * The force the motor used in the last step, in newtons.
     */
    public float getMotorForce() {
        return motorImpulse / dt;
    }

    /**
     * How far body B has slid along the axis, in metres.
     */
    public float getTranslation() {
        if (localA == null) return 0f;
        return gap().dot(getAxis());
    }

    /**
     * How fast body B slides along the axis, in metres per second.
     */
    public float getSpeed() {
        if (localA == null) return 0f;
        Vec3 n = getAxis();
        Vec3 ra = offset(a, localA), d = gap();
        return n.dot(anchorVelocity(a, ra.add(d), b, offset(b, localB)));
    }

    /**
     * The axis in world space, as of now; it turns with body A when there are two bodies.
     */
    public Vec3 getAxis() {
        return axisA == null ? axis : a.getRotation().rotate(axisA);
    }

    private Vec3 gap() {
        return center(b).add(offset(b, localB)).sub(center(a).add(offset(a, localA)));
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
        relative = a.getRotation().conjugate().mul(b.getRotation());
    }

    @Override
    void resetImpulses() {
        angularImpulse = Vec3.ZERO;
        impulse1 = impulse2 = motorImpulse = lowerImpulse = upperImpulse = 0f;
    }

    /**
     * Sets the rows from where the bodies are now. The axis turns with A, so A's side of each row turns around the far
     * anchor (its own anchor plus the gap).
     */
    private void setRows() {
        ra = offset(a, localA);
        rb = offset(b, localB);
        Vec3 d = center(b).add(rb).sub(center(a).add(ra));
        Vec3 n = a.getRotation().rotate(axisA);
        Vec3[] side = perpendiculars(n);
        Vec3 far = ra.add(d);
        across1.set(a, b, side[0], far.cross(side[0]), rb.cross(side[0]));
        across2.set(a, b, side[1], far.cross(side[1]), rb.cross(side[1]));
        along.set(a, b, n, far.cross(n), rb.cross(n));
        translation = d.dot(n);
        spread = d;
    }

    @Override
    void prepare(float dt) {
        this.dt = dt;
        setRows();
        angularMass = angularMass(a, b).inverse();
        if (!limited) lowerImpulse = upperImpulse = 0f;
        if (!motor || maxMotorForce <= 0f) motorImpulse = 0f;
        applyAngular(a, b, angularImpulse);
        across1.apply(a, b, impulse1);
        across2.apply(a, b, impulse2);
        along.apply(a, b, motorImpulse + lowerImpulse - upperImpulse);
    }

    @Override
    void solveVelocity() {
        if (direct) return;
        if (motor) {
            float max = maxMotorForce * dt;
            float old = motorImpulse;
            motorImpulse = Math.clamp(old - along.mass * (along.velocity(a, b) - motorSpeed), -max, max);
            along.apply(a, b, motorImpulse - old);
        }
        if (limited) {
            float gap = translation - lower;
            float old = lowerImpulse;
            lowerImpulse = Math.max(old - along.mass * (along.velocity(a, b) + Math.max(gap, 0f) / dt), 0f);
            along.apply(a, b, lowerImpulse - old);

            gap = upper - translation;
            old = upperImpulse;
            upperImpulse = Math.max(old - along.mass * (-along.velocity(a, b) + Math.max(gap, 0f) / dt), 0f);
            along.apply(a, b, old - upperImpulse);
        }
        Vec3 t = angularMass.mul(angularVelocity(a, b)).negate();
        angularImpulse = angularImpulse.add(t);
        applyAngular(a, b, t);

        float l1 = -across1.mass * across1.velocity(a, b);
        impulse1 += l1;
        across1.apply(a, b, l1);
        float l2 = -across2.mass * across2.velocity(a, b);
        impulse2 += l2;
        across2.apply(a, b, l2);
    }

    /** The two sideways rows and the three that lock turning, then one for the stops and the motor (if it has either). */
    @Override
    int directRows() {
        return 5 + directBoundedRows();
    }

    @Override
    int directBoundedRows() {
        return limited || (motor && maxMotorForce > 0f) ? 1 : 0;
    }

    @Override
    float directAccumulated(int row) {
        return lowerImpulse - upperImpulse + motorImpulse;
    }

    @Override
    void directVelocityRows(Row[] rows) {
        rows[0].copy(across1);
        rows[1].copy(across2);
        angularRows(rows);
        if (directBoundedRows() > 0) {
            // The stops may let the slider close in on them this step, but not pass.
            rows[5].copy(along);
            rows[5].limits(limited, -Math.max(translation - lower, 0f) / dt, limited, Math.max(upper - translation, 0f) / dt,
                    motor, motorSpeed, maxMotorForce * dt);
        }
    }

    @Override
    void directImpulses(double[] lambda, int at) {
        impulse1 += (float) lambda[at];
        impulse2 += (float) lambda[at + 1];
        angularImpulse = angularImpulse.add(new Vec3((float) lambda[at + 2], (float) lambda[at + 3], (float) lambda[at + 4]));
        if (directBoundedRows() > 0) {
            float total = lowerImpulse - upperImpulse + motorImpulse + (float) lambda[at + 5];
            float max = motor ? maxMotorForce * dt : 0f;
            motorImpulse = directBuffer()[5].motorShare(total, max);
            lowerImpulse = Math.max(total - motorImpulse, 0f);
            upperImpulse = Math.max(motorImpulse - total, 0f);
        }
    }

    @Override
    boolean directPositionRows(Row[] rows, float[] error) {
        setRows();
        rows[0].copy(across1);
        rows[1].copy(across2);
        angularRows(rows);
        float c1 = spread.dot(across1.linear()), c2 = spread.dot(across2.linear());
        error[0] = Math.clamp(c1, -MAX_LINEAR_CORRECTION, MAX_LINEAR_CORRECTION);
        error[1] = Math.clamp(c2, -MAX_LINEAR_CORRECTION, MAX_LINEAR_CORRECTION);
        Vec3 turn = rotationError(a, b, relative);
        float angle = turn.length();
        if (angle > MAX_ANGULAR_CORRECTION) turn = turn.mul(MAX_ANGULAR_CORRECTION / angle);
        error[2] = turn.x();
        error[3] = turn.y();
        error[4] = turn.z();
        boolean done = Math.max(Math.abs(c1), Math.abs(c2)) <= LINEAR_SLOP && angle <= ANGULAR_SLOP;
        if (directBoundedRows() > 0) {
            rows[5].clear();
            error[5] = 0f;
            if (limited && translation < lower) {
                rows[5].copy(along);
                error[5] = Math.max(translation - lower, -MAX_LINEAR_CORRECTION);
            } else if (limited && translation > upper) {
                rows[5].copy(along);
                error[5] = Math.min(translation - upper, MAX_LINEAR_CORRECTION);
            }
            done &= !limited || translation >= lower - LINEAR_SLOP && translation <= upper + LINEAR_SLOP;
        }
        return done;
    }

    /** The slider's lock on turning: one row for each world axis, after the two sideways rows. */
    private void angularRows(Row[] rows) {
        rows[2].angular(a, b, Vec3.X);
        rows[3].angular(a, b, Vec3.UP);
        rows[4].angular(a, b, Vec3.Z);
    }

    @Override
    boolean solvePosition() {
        if (direct) return true; // the tree closes the rails, the lock on turning and the stops
        float angle = solveRotation(a, b, relative);
        setRows();
        Vec3 d = spread;
        float c1 = d.dot(across1.linear()), c2 = d.dot(across2.linear());
        float error = Math.max(Math.abs(c1), Math.abs(c2));
        across1.nudge(a, b, -across1.mass * Math.clamp(c1, -MAX_LINEAR_CORRECTION, MAX_LINEAR_CORRECTION));
        across2.nudge(a, b, -across2.mass * Math.clamp(c2, -MAX_LINEAR_CORRECTION, MAX_LINEAR_CORRECTION));
        if (limited) {
            float c = translation < lower ? translation - lower : translation > upper ? translation - upper : 0f;
            if (c != 0f) {
                along.nudge(a, b, -along.mass * Math.clamp(c, -MAX_LINEAR_CORRECTION, MAX_LINEAR_CORRECTION));
                error = Math.max(error, Math.abs(c));
            }
        }
        return error <= LINEAR_SLOP && angle <= ANGULAR_SLOP;
    }

    @Override
    Vec3 linearImpulse() {
        return across1.linear().mul(impulse1).add(across2.linear().mul(impulse2)).add(along.linear().mul(motorImpulse + lowerImpulse - upperImpulse));
    }

    @Override
    Vec3 angularImpulse() {
        return angularImpulse;
    }
}
