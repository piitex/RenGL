package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;

/**
 * Pins two bodies together at one point, letting them turn freely around it: a pendulum, a chain link, a trailer
 * hitch, a lamp on a cord.
 * <p>
 * A ball joint can be given limits, which is what a shoulder, a hip or a neck needs. The joint has an <b>axis</b>, the
 * direction of the limb (given when the joint is made, or up if it isn't). {@link #setSwingLimit Swing} is how far body
 * B's axis may lean away from body A's, in a cone around it; {@link #setTwistLimit twist} is how far B may turn around
 * its own axis. A ragdoll's elbow is a hinge, but its shoulder is a ball joint with a cone of about 90 degrees and a
 * twist of about 45.
 */
public final class BallJoint extends Joint {
    private final Vec3 anchor, axis;
    private Vec3 localA, localB, axisA, axisB;
    private Quat reference;
    private Vec3 ra, rb;
    private Mat3 mass, pointStiffness;
    private Vec3 impulse = Vec3.ZERO;

    private boolean swingLimited, twistLimited;
    private float swingMax = (float) Math.PI, twistLower, twistUpper;
    private final Stop swingStop = new Stop(), twistStop = new Stop();
    private float swing, twist, swingImpulse, lowerImpulse, upperImpulse;

    /**
     * Joins {@code a} and {@code b} at {@code anchor}, in world space. With {@code b} null, {@code a} hangs from that
     * point in the world. The axis for the limits is up.
     */
    public BallJoint(RigidBody a, RigidBody b, Vec3 anchor) {
        this(a, b, anchor, Vec3.UP);
    }

    /**
     * Joins {@code a} and {@code b} at {@code anchor} with limits measured around {@code axis}, both in world space:
     * the direction of the limb that body B is, pointing away from the joint.
     */
    public BallJoint(RigidBody a, RigidBody b, Vec3 anchor, Vec3 axis) {
        super(a, b);
        if (axis.lengthSquared() < 1e-12f) throw new IllegalArgumentException("The ball joint axis has no direction");
        this.anchor = anchor;
        this.axis = axis.normalized();
    }

    /**
     * Keeps B's axis within {@code maxAngle} radians of A's, a cone of twice that angle across (0 to pi).
     */
    public BallJoint setSwingLimit(float maxAngle) {
        if (!(maxAngle >= 0f)) throw new IllegalArgumentException("The swing limit " + maxAngle + " is below 0");
        swingMax = Math.min(maxAngle, (float) Math.PI);
        swingLimited = true;
        wake();
        return this;
    }

    /**
     * Keeps B's turn around its axis, against where it started, between {@code lower} and {@code upper} radians (each
     * between -pi and pi).
     */
    public BallJoint setTwistLimit(float lower, float upper) {
        if (!(lower <= upper)) throw new IllegalArgumentException("The lower twist limit " + lower + " is above the upper " + upper);
        twistLower = Math.max(lower, (float) -Math.PI);
        twistUpper = Math.min(upper, (float) Math.PI);
        twistLimited = true;
        wake();
        return this;
    }

    /**
     * Frees both the swing and the twist.
     */
    public BallJoint clearLimits() {
        swingLimited = twistLimited = false;
        swingImpulse = lowerImpulse = upperImpulse = 0f;
        wake();
        return this;
    }

    public boolean hasSwingLimit() {
        return swingLimited;
    }

    public boolean hasTwistLimit() {
        return twistLimited;
    }

    public float getSwingLimit() {
        return swingMax;
    }

    public float getLowerTwistLimit() {
        return twistLower;
    }

    public float getUpperTwistLimit() {
        return twistUpper;
    }

    /**
     * How far B's axis leans from A's, in radians (0 to pi).
     */
    public float getSwing() {
        if (axisA == null) return 0f;
        Vec3 u = a.getRotation().rotate(axisA), v = b.getRotation().rotate(axisB);
        return (float) Math.atan2(u.cross(v).length(), u.dot(v));
    }

    /**
     * B's turn around its axis against A, in radians from -pi to pi, 0 where the bodies were when the joint was added.
     */
    public float getTwist() {
        if (reference == null) return 0f;
        Quat delta = a.getRotation().conjugate().mul(b.getRotation()).mul(reference.conjugate());
        float s = delta.x() * axisA.x() + delta.y() * axisA.y() + delta.z() * axisA.z();
        return wrapAngle(2f * (float) Math.atan2(s, delta.w()));
    }

    /**
     * The point the bodies are pinned at, in world space, as of now; it moves with body A when there are two bodies.
     */
    public Vec3 getAnchor() {
        return localA == null ? anchor : a.getPosition().add(a.getRotation().rotate(localA));
    }

    /**
     * The limits' axis in world space, as of now; it turns with body A when there are two bodies.
     */
    public Vec3 getAxis() {
        return axisA == null ? axis : a.getRotation().rotate(axisA);
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
        impulse = Vec3.ZERO;
        swingImpulse = lowerImpulse = upperImpulse = 0f;
    }

    /**
     * A turn around one direction together with the push at the pivot that keeps the pivot still, so a stop turns the
     * body around the pivot, not around its centre of mass; solved on their own the stops would fight the pivot and a
     * limb would bounce off them.
     */
    private final class Stop {
        Vec3 n = Vec3.ZERO, push = Vec3.ZERO;
        float effective;

        void set(Vec3 direction) {
            n = direction;
            Vec3 ta = a.inverseInertiaWorld().mul(n), tb = b.inverseInertiaWorld().mul(n);
            push = mass.mul(tb.cross(rb).add(ta.cross(ra))).negate();
            float k = n.dot(angularMass(a, b).mul(n))
                    + n.dot(b.inverseInertiaWorld().mul(rb.cross(push)).add(a.inverseInertiaWorld().mul(ra.cross(push))));
            effective = k > 1e-12f ? 1f / k : 0f;
        }

        void apply(float amount) {
            if (amount == 0f) return;
            applyAngular(a, b, n.mul(amount));
            Vec3 p = push.mul(amount);
            applyPoint(a, ra, b, rb, p);
            impulse = impulse.add(p);
        }
    }

    /**
     * Swing below this is too small to have a direction; the cone isn't touched there (a cone of 0 is a hinge's
     * business, not this one's).
     */
    private static final float MIN_SWING = 1e-4f;

    @Override
    void prepare(float dt) {
        this.dt = dt;
        ra = offset(a, localA);
        rb = offset(b, localB);
        pointStiffness = pointMass(a, ra, b, rb);
        mass = pointStiffness.inverse();
        if (!swingLimited) swingImpulse = 0f;
        if (!twistLimited) lowerImpulse = upperImpulse = 0f;
        swing = getSwing();
        twist = getTwist();
        if (swing <= MIN_SWING) swingImpulse = 0f;
        if (swingLimited) swingStop.set(swingDirection());
        if (twistLimited) twistStop.set(a.getRotation().rotate(axisA));

        // The pivot's share of last step's stop impulses is already in the point impulse.
        applyPoint(a, ra, b, rb, impulse);
        applyAngular(a, b, stopTorque());
    }

    /**
     * The direction that turns A's axis towards B's, about which B's leaning grows; zero when they line up.
     */
    private Vec3 swingDirection() {
        Vec3 u = a.getRotation().rotate(axisA), v = b.getRotation().rotate(axisB);
        Vec3 c = u.cross(v);
        float length = c.length();
        return length > 1e-7f ? c.mul(1f / length) : Vec3.ZERO;
    }

    private Vec3 stopTorque() {
        Vec3 t = Vec3.ZERO;
        if (swingLimited) t = t.sub(swingStop.n.mul(swingImpulse));
        if (twistLimited) t = t.add(twistStop.n.mul(lowerImpulse - upperImpulse));
        return t;
    }

    @Override
    void solveVelocity() {
        if (direct) return;
        if (swingLimited) {
            if (swing > MIN_SWING) {
                // The stop may let the leaning close in on the cone this step, but not pass it.
                float gap = swingMax - swing;
                float speed = -angularVelocity(a, b).dot(swingStop.n);
                float old = swingImpulse;
                swingImpulse = Math.max(old - swingStop.effective * (speed + Math.max(gap, 0f) / dt), 0f);
                swingStop.apply(old - swingImpulse);
            } else {
                swingImpulse = 0f;
            }
        }
        if (twistLimited) {
            float gap = twist - twistLower;
            float speed = angularVelocity(a, b).dot(twistStop.n);
            float old = lowerImpulse;
            lowerImpulse = Math.max(old - twistStop.effective * (speed + Math.max(gap, 0f) / dt), 0f);
            twistStop.apply(lowerImpulse - old);

            gap = twistUpper - twist;
            speed = -angularVelocity(a, b).dot(twistStop.n);
            old = upperImpulse;
            upperImpulse = Math.max(old - twistStop.effective * (speed + Math.max(gap, 0f) / dt), 0f);
            twistStop.apply(old - upperImpulse);
        }
        Vec3 p = mass.mul(anchorVelocity(a, ra, b, rb)).negate();
        impulse = impulse.add(p);
        applyPoint(a, ra, b, rb, p);
    }

    /** The pivot's three rows, then one for the swing stop (if any), then one for the twist stops (if any). */
    @Override
    int directRows() {
        return 3 + directBoundedRows();
    }

    @Override
    int directBoundedRows() {
        return (swingLimited ? 1 : 0) + (twistLimited ? 1 : 0);
    }

    /** The twist row: after the pivot's and the swing's. */
    private int twistRow() {
        return swingLimited ? 4 : 3;
    }

    @Override
    float directAccumulated(int row) {
        return swingLimited && row == 3 ? swingImpulse : lowerImpulse - upperImpulse;
    }

    @Override
    void directVelocityRows(Row[] rows) {
        pointRows(rows, 0, a, ra, b, rb);
        // Each stop may let the joint close in on it this step, but not pass it.
        if (swingLimited) {
            rows[3].angular(a, b, swing > MIN_SWING ? swingStop.n.negate() : Vec3.ZERO);
            rows[3].limits(true, -Math.max(swingMax - swing, 0f) / dt, false, 0f, false, 0f, 0f);
        }
        if (twistLimited) {
            int at = twistRow();
            rows[at].angular(a, b, twistStop.n);
            rows[at].limits(true, -Math.max(twist - twistLower, 0f) / dt, true, Math.max(twistUpper - twist, 0f) / dt, false, 0f, 0f);
        }
    }

    @Override
    void directImpulses(double[] lambda, int at) {
        impulse = impulse.add(new Vec3((float) lambda[at], (float) lambda[at + 1], (float) lambda[at + 2]));
        if (swingLimited) swingImpulse = Math.max(swingImpulse + (float) lambda[at + 3], 0f);
        if (twistLimited) {
            float total = lowerImpulse - upperImpulse + (float) lambda[at + twistRow()];
            lowerImpulse = Math.max(total, 0f);
            upperImpulse = Math.max(-total, 0f);
        }
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
        boolean done = length <= LINEAR_SLOP;
        if (swingLimited) {
            float now = getSwing();
            rows[3].clear();
            error[3] = 0f;
            if (now > swingMax && now > MIN_SWING) {
                rows[3].angular(a, b, swingDirection().negate());
                error[3] = -Math.min(now - swingMax, MAX_ANGULAR_CORRECTION);
                done &= now - swingMax <= ANGULAR_SLOP;
            }
        }
        if (twistLimited) {
            float now = getTwist();
            int at = twistRow();
            rows[at].clear();
            error[at] = 0f;
            Vec3 n = a.getRotation().rotate(axisA);
            if (now < twistLower) {
                rows[at].angular(a, b, n);
                error[at] = Math.max(now - twistLower, -MAX_ANGULAR_CORRECTION);
            } else if (now > twistUpper) {
                rows[at].angular(a, b, n);
                error[at] = Math.min(now - twistUpper, MAX_ANGULAR_CORRECTION);
            }
            done &= now >= twistLower - ANGULAR_SLOP && now <= twistUpper + ANGULAR_SLOP;
        }
        return done;
    }

    @Override
    boolean solvePosition() {
        if (direct) return true; // the tree closes the pivot and the stops
        float angularError = 0f;
        if (swingLimited) {
            float now = getSwing();
            if (now > swingMax && now > MIN_SWING) {
                Vec3 n = swingDirection();
                float c = now - swingMax;
                float k = n.dot(angularMass(a, b).mul(n));
                if (k > 1e-12f) nudgeAngular(a, b, n.mul(Math.clamp(c, 0f, MAX_ANGULAR_CORRECTION) / -k));
                angularError = c;
            }
        }
        if (twistLimited) {
            float now = getTwist();
            float c = now < twistLower ? now - twistLower : now > twistUpper ? now - twistUpper : 0f;
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
        return impulse;
    }

    @Override
    Vec3 angularImpulse() {
        return mass == null ? Vec3.ZERO : stopTorque();
    }
}
