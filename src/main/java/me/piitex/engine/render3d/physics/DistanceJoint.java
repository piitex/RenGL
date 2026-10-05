package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

/**
 * Keeps a point on one body at a distance from a point on another, leaving them free to swing and turn: a rod by
 * default, a rope with {@link #setMinLength setMinLength(0)}, and a spring with {@link #setSpring}.
 * <pre>
 * world.addJoint(new DistanceJoint(bucket, handle, null, hook).setMinLength(0));  // a rope: goes slack, never stretches
 * world.addJoint(new DistanceJoint(car, wheelTop, wheel, wheelMiddle).setSpring(4f, 0.5f));  // a springy link
 * </pre>
 * The length is the distance between the anchors when the joint is added, unless set.
 */
public final class DistanceJoint extends Joint {
    private final Vec3 anchorA, anchorB;
    private Vec3 localA, localB;
    private float length = Float.NaN, minLength = Float.NaN, maxLength = Float.NaN;
    private float frequency, dampingRatio;

    private Vec3 ra, rb;
    private final Row row = new Row();
    private float distance, minNow, maxNow, gamma, bias, softMass;
    private float impulse, lowerImpulse, upperImpulse;

    /**
     * Links {@code anchorA} on {@code a} to {@code anchorB} on {@code b}, in world space. With {@code b} null,
     * {@code anchorB} is a fixed point in the world.
     */
    public DistanceJoint(RigidBody a, Vec3 anchorA, RigidBody b, Vec3 anchorB) {
        super(a, b);
        this.anchorA = anchorA;
        this.anchorB = anchorB;
    }

    /**
     * The length the joint keeps, or that its spring pulls toward, in metres.
     */
    public float getLength() {
        return Float.isNaN(length) ? anchorA.distance(anchorB) : length;
    }

    public DistanceJoint setLength(float length) {
        if (!(length >= 0f)) throw new IllegalArgumentException("The length can't be below 0, not " + length);
        this.length = length;
        wake();
        return this;
    }

    public float getMinLength() {
        return Float.isNaN(minLength) ? isSpring() ? 0f : getLength() : minLength;
    }

    /**
     * The shortest the joint may get. Below the length, the bodies can come closer: 0 makes a rope. A spring has no
     * shortest length unless set.
     */
    public DistanceJoint setMinLength(float min) {
        if (!(min >= 0f)) throw new IllegalArgumentException("The shortest length can't be below 0, not " + min);
        minLength = min;
        wake();
        return this;
    }

    public float getMaxLength() {
        return Float.isNaN(maxLength) ? isSpring() ? Float.POSITIVE_INFINITY : getLength() : maxLength;
    }

    /**
     * The longest the joint may get: above the length, the bodies can move further apart. A spring has no longest
     * length unless set; giving it one makes a bungee that stretches only so far.
     */
    public DistanceJoint setMaxLength(float max) {
        if (!(max >= 0f)) throw new IllegalArgumentException("The longest length can't be below 0, not " + max);
        maxLength = max;
        wake();
        return this;
    }

    /**
     * Makes the length springy: the joint pulls and pushes toward its length, bouncing {@code frequency} times a second
     * whatever the mass, and losing its bounce by {@code dampingRatio}: 0 bounces forever, 1 settles without
     * overshooting. 0 frequency makes it rigid again.
     */
    public DistanceJoint setSpring(float frequency, float dampingRatio) {
        this.frequency = Math.max(0f, frequency);
        this.dampingRatio = Math.max(0f, dampingRatio);
        wake();
        return this;
    }

    public boolean isSpring() {
        return frequency > 0f;
    }

    public float getSpringFrequency() {
        return frequency;
    }

    public float getSpringDamping() {
        return dampingRatio;
    }

    /**
     * How far apart the anchors are now, in metres.
     */
    public float getCurrentLength() {
        if (localA == null) return anchorA.distance(anchorB);
        return center(b).add(offset(b, localB)).distance(center(a).add(offset(a, localA)));
    }

    /**
     * Anchor A in world space, as of now.
     */
    public Vec3 getAnchorA() {
        if (localA == null) return anchorA;
        return held() ? b.getPosition().add(b.getRotation().rotate(localB)) : a.getPosition().add(a.getRotation().rotate(localA));
    }

    /**
     * Anchor B in world space, as of now.
     */
    public Vec3 getAnchorB() {
        if (localB == null || held()) return anchorB;
        return b.getPosition().add(b.getRotation().rotate(localB));
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
        // Held to the world, the solver's A is the world and B is body A.
        localA = toLocal(a, held() ? anchorB : anchorA);
        localB = toLocal(b, held() ? anchorA : anchorB);
    }

    @Override
    void resetImpulses() {
        impulse = lowerImpulse = upperImpulse = 0f;
    }

    /**
     * Points the row from anchor A to anchor B, as the bodies are now.
     */
    private Vec3 setRow() {
        ra = offset(a, localA);
        rb = offset(b, localB);
        Vec3 d = center(b).add(rb).sub(center(a).add(ra));
        distance = d.length();
        Vec3 u = distance > 1e-6f ? d.mul(1f / distance) : Vec3.UP;
        row.set(a, b, u, ra.cross(u), rb.cross(u));
        return u;
    }

    @Override
    void prepare(float dt) {
        this.dt = dt;
        setRow();
        minNow = getMinLength();
        maxNow = Math.max(minNow, getMaxLength());
        if (minNow < maxNow) {
            if (isSpring()) {
                // A spring as a soft constraint, from the mass the row moves (Catto's soft step).
                float rest = Math.clamp(getLength(), minNow, maxNow);
                float omega = (float) (2 * Math.PI * frequency);
                float k = row.mass * omega * omega, c = 2f * row.mass * dampingRatio * omega;
                float g = dt * (c + dt * k);
                gamma = g > 0f ? 1f / g : 0f;
                bias = (distance - rest) * dt * k * gamma;
                float kk = row.mass > 0f ? 1f / row.mass + gamma : 0f;
                softMass = kk > 0f ? 1f / kk : 0f;
            } else {
                impulse = 0f;
            }
            if (minNow <= 0f) lowerImpulse = 0f;
            if (maxNow == Float.POSITIVE_INFINITY) upperImpulse = 0f;
        } else {
            lowerImpulse = upperImpulse = 0f;
        }
        row.apply(a, b, impulse + lowerImpulse - upperImpulse);
    }

    @Override
    void solveVelocity() {
        if (minNow < maxNow) {
            if (isSpring()) {
                float l = -softMass * (row.velocity(a, b) + bias + gamma * impulse);
                impulse += l;
                row.apply(a, b, l);
            }
            if (minNow > 0f) {
                float gap = distance - minNow;
                float old = lowerImpulse;
                lowerImpulse = Math.max(old - row.mass * (row.velocity(a, b) + Math.max(gap, 0f) / dt), 0f);
                row.apply(a, b, lowerImpulse - old);
            }
            if (maxNow != Float.POSITIVE_INFINITY) {
                float gap = maxNow - distance;
                float old = upperImpulse;
                upperImpulse = Math.max(old - row.mass * (-row.velocity(a, b) + Math.max(gap, 0f) / dt), 0f);
                row.apply(a, b, old - upperImpulse);
            }
        } else {
            float l = -row.mass * row.velocity(a, b);
            impulse += l;
            row.apply(a, b, l);
        }
    }

    @Override
    boolean solvePosition() {
        setRow();
        float c;
        if (minNow >= maxNow) c = distance - minNow;
        else if (distance < minNow) c = distance - minNow;
        else if (distance > maxNow) c = distance - maxNow;
        else return true;
        row.nudge(a, b, -row.mass * Math.clamp(c, -MAX_LINEAR_CORRECTION, MAX_LINEAR_CORRECTION));
        return Math.abs(c) <= LINEAR_SLOP;
    }

    @Override
    Vec3 linearImpulse() {
        return row.linear().mul(impulse + lowerImpulse - upperImpulse);
    }

    @Override
    Vec3 angularImpulse() {
        return Vec3.ZERO;
    }
}
