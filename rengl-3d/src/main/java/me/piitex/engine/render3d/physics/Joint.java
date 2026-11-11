package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;

import java.util.function.Consumer;

/**
 * Holds two bodies together, or one body to a point in the world: a {@link HingeJoint} for a door or a wheel, a
 * {@link BallJoint} for a chain link or a pendulum, a {@link SliderJoint} for a drawer or a piston, a
 * {@link DistanceJoint} for a rope, a rod or a spring, and a {@link FixedJoint} to weld two bodies into one.
 * <pre>
 * world.addJoint(new HingeJoint(door, frame, hingePoint, Vec3.UP));
 * world.addJoint(new BallJoint(lamp, null, ceilingPoint)); // null: hung from the world
 * </pre>
 * Anchors and axes are given in world space and read against where the bodies are when the joint is added, so add the
 * bodies first and place them as they should be joined. From then on the joint keeps them that way, whatever moves
 * them.
 * <p>
 * The two bodies of a joint don't collide with each other unless {@link #setCollideConnected} says so, since joined
 * parts usually overlap where they meet. A joint can break: give it a {@link #setBreakForce break force} and it lets go
 * when pulled harder than that, and the world drops it.
 */
public abstract sealed class Joint permits BallJoint, DistanceJoint, FixedJoint, HingeJoint, SliderJoint {
    /**
     * The most the position fix moves a joint per pass, in metres and radians, so a joint pulled far apart (or a body
     * teleported away) comes back over a few steps rather than with a jolt.
     */
    static final float MAX_LINEAR_CORRECTION = 0.2f, MAX_ANGULAR_CORRECTION = (float) Math.toRadians(8);
    /**
     * Errors below these, in metres and radians, are left for the next step: the position fix stops early.
     */
    static final float LINEAR_SLOP = 0.001f, ANGULAR_SLOP = (float) Math.toRadians(0.5);

    private final RigidBody bodyA, bodyB;
    /**
     * The bodies the solver works on, measuring B against A. A body held to the world is {@code b}, and {@code a} stands
     * in for the world, fixed at its origin, so angles and speeds are the body's own.
     */
    final RigidBody a, b;
    private PhysicsWorld world;
    private boolean collideConnected;
    private float breakForce = Float.POSITIVE_INFINITY, breakTorque = Float.POSITIVE_INFINITY;
    private boolean broken;
    private Consumer<Joint> breakHandler;
    private Object userData;
    private Vec3 reportedForce, reportedTorque;
    float dt = 1f / 60f;

    Joint(RigidBody a, RigidBody b) {
        if (a == null) throw new IllegalArgumentException("A joint needs a first body");
        if (a == b) throw new IllegalArgumentException("A joint needs two different bodies");
        bodyA = a;
        bodyB = b;
        this.a = b != null ? a : new RigidBody(BodyType.STATIC);
        this.b = b != null ? b : a;
    }

    public RigidBody getBodyA() {
        return bodyA;
    }

    /**
     * The second body, or null when the joint holds body A to the world.
     */
    public RigidBody getBodyB() {
        return bodyB;
    }

    /**
     * The body on the other end from {@code body}, or null if {@code body} isn't one of the joint's (or the other end
     * is the world).
     */
    public RigidBody getOther(RigidBody body) {
        return body == bodyA ? bodyB : body == bodyB ? bodyA : null;
    }

    /**
     * The world the joint is in, or null.
     */
    public PhysicsWorld getWorld() {
        return world;
    }

    public boolean isCollideConnected() {
        return collideConnected;
    }

    /**
     * Whether the two joined bodies collide with each other (off by default).
     */
    public Joint setCollideConnected(boolean collide) {
        collideConnected = collide;
        return this;
    }

    public float getBreakForce() {
        return breakForce;
    }

    /**
     * The pull, in newtons, past which the joint breaks; infinite (unbreakable) by default. A 10 kg lamp hanging from
     * it pulls with about 100 N.
     */
    public Joint setBreakForce(float newtons) {
        breakForce = newtons > 0f ? newtons : Float.POSITIVE_INFINITY;
        return this;
    }

    public float getBreakTorque() {
        return breakTorque;
    }

    /**
     * The twist, in newton metres, past which the joint breaks; infinite by default.
     */
    public Joint setBreakTorque(float newtonMetres) {
        breakTorque = newtonMetres > 0f ? newtonMetres : Float.POSITIVE_INFINITY;
        return this;
    }

    /**
     * Whether the joint has broken. A broken joint is out of its world and holds nothing.
     */
    public boolean isBroken() {
        return broken;
    }

    /**
     * Called with the joint when it breaks, after the world has dropped it: to play a snap, or swap in broken parts.
     */
    public Joint setBreakHandler(Consumer<Joint> handler) {
        breakHandler = handler;
        return this;
    }

    public Object getUserData() {
        return userData;
    }

    public Joint setUserData(Object userData) {
        this.userData = userData;
        return this;
    }

    /**
     * The force the joint put on body B in the last step, in newtons (body A got the opposite): what holding the
     * bodies together took. For a body held to the world, it is the force on that body.
     */
    public Vec3 getReactionForce() {
        if (reportedForce != null) return reportedForce;
        return linearImpulse().mul(1f / dt);
    }

    /**
     * The torque the joint put on body B in the last step, in newton metres, or on body A when it's held to the world.
     */
    public Vec3 getReactionTorque() {
        if (reportedTorque != null) return reportedTorque;
        return angularImpulse().mul(1f / dt);
    }

    // ---- World use ------------------------------------------------------------------------------------------------

    void attach(PhysicsWorld world) {
        this.world = world;
        broken = false;
        resetImpulses();
        anchor();
        a.wakeUp();
        b.wakeUp();
    }

    void detach() {
        world = null;
        reportedForce = reportedTorque = null;
    }

    /**
     * What a {@link PhysicsBackend} measured holding the joint in the last step.
     */
    void reported(Vec3 force, Vec3 torque) {
        reportedForce = force;
        reportedTorque = torque;
    }

    /**
     * Marks the joint broken, as a {@link PhysicsBackend} found it.
     */
    void breakNow() {
        broken = true;
    }

    /**
     * Whether either body can move this step, so the joint has work to do.
     */
    boolean active() {
        return a.isAwakeDynamic() || b.isAwakeDynamic();
    }

    /**
     * Breaks the joint if the last step pulled it harder than it can take.
     *
     * @return true if it broke
     */
    boolean checkBreak() {
        if (breakForce == Float.POSITIVE_INFINITY && breakTorque == Float.POSITIVE_INFINITY) return false;
        if (linearImpulse().length() <= breakForce * dt && angularImpulse().length() <= breakTorque * dt) return false;
        broken = true;
        return true;
    }

    /**
     * Whether body A is held to the world, so the solver's {@code b} is body A.
     */
    boolean held() {
        return bodyB == null;
    }

    void fireBreak() {
        if (breakHandler != null) breakHandler.accept(this);
    }

    /**
     * Reads the anchors and axes against where the bodies are now.
     */
    abstract void anchor();

    /**
     * Where the joint holds the solver's bodies {@link #a} and {@link #b}, each in its own space from its origin, or
     * null before the joint is added: for drawing it.
     */
    abstract Vec3 localAnchorA();

    abstract Vec3 localAnchorB();

    abstract void resetImpulses();

    /**
     * Works out the joint's directions and masses for this step and applies last step's impulses as a head start.
     */
    abstract void prepare(float dt);

    /**
     * One pass at stopping the bodies moving apart.
     */
    abstract void solveVelocity();

    /**
     * One pass at moving the bodies back together after the step.
     *
     * @return true if they were already as close as they need to be
     */
    abstract boolean solvePosition();

    /**
     * The linear impulse on body B in the last step.
     */
    abstract Vec3 linearImpulse();

    /**
     * The angular impulse on body B in the last step.
     */
    abstract Vec3 angularImpulse();

    /**
     * The longest {@link #directRows} any joint has.
     */
    static final int MAX_DIRECT_ROWS = 6;
    private Row[] directBuffer;

    /**
     * True while the world's {@link JointTree} holds this joint's rows (the pivot, the axis, the weld, and the stops and
     * motor), so the joint's own passes leave all of them to it.
     */
    boolean direct;

    /**
     * How many rows the joint gives the {@link JointTree}, which solves them exactly together with the rest of its tree
     * of joints: 0 for a joint that can't (it is then solved a pass at a time with the rest). The always-on rows come
     * first, then {@link #directBoundedRows} stops and motors.
     */
    int directRows() {
        return 0;
    }

    /**
     * How many of the last {@link #directRows} are stops and motors: rows whose impulse is limited, set up with
     * {@link Row#limits}, so the tree has to find where on their limits they are.
     */
    int directBoundedRows() {
        return 0;
    }

    /**
     * The impulse a stop or motor row has used so far this step, which the tree keeps within the row's limits.
     *
     * @param row the row's index among all of the joint's rows
     */
    float directAccumulated(int row) {
        throw new UnsupportedOperationException();
    }

    /**
     * The scratch rows the tree fills in with {@link #directVelocityRows} and {@link #directPositionRows}.
     */
    final Row[] directBuffer() {
        if (directBuffer == null) {
            directBuffer = new Row[MAX_DIRECT_ROWS];
            for (int i = 0; i < MAX_DIRECT_ROWS; i++) directBuffer[i] = new Row();
        }
        return directBuffer;
    }

    /**
     * Sets the rows to the joint's always-on constraints for this step, after {@link #prepare}.
     */
    void directVelocityRows(Row[] rows) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes what the tree solver pushed on the rows, to keep the joint's impulses (and so its reaction) right.
     */
    void directImpulses(double[] lambda, int at) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets the rows to the joint's always-on constraints where the bodies are now, and {@code error} to how far each is
     * off (at most one correction's worth).
     *
     * @return true if they were already as close as they need to be
     */
    boolean directPositionRows(Row[] rows, float[] error) {
        throw new UnsupportedOperationException();
    }

    /**
     * Rows that hold B's anchor at A's: one for each world axis.
     */
    static void pointRows(Row[] rows, int at, RigidBody a, Vec3 ra, RigidBody b, Vec3 rb) {
        for (int i = 0; i < 3; i++) {
            Vec3 n = i == 0 ? Vec3.X : i == 1 ? Vec3.UP : Vec3.Z;
            rows[at + i].set(a, b, n, ra.cross(n), rb.cross(n));
        }
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------

    /**
     * {@code world} in the body's own space, from its origin.
     */
    static Vec3 toLocal(RigidBody body, Vec3 world) {
        return body.getRotation().conjugate().rotate(world.sub(body.getPosition()));
    }

    /**
     * A point given in the body's own space, as an offset from its centre of mass in world space.
     */
    static Vec3 offset(RigidBody body, Vec3 local) {
        return body.getRotation().rotate(local.sub(body.getLocalCenterOfMass()));
    }

    static Vec3 center(RigidBody body) {
        return new Vec3(body.cx, body.cy, body.cz);
    }

    /**
     * Two directions at right angles to {@code n} and to each other.
     */
    static Vec3[] perpendiculars(Vec3 n) {
        Vec3 t = Math.abs(n.x()) >= 0.57735f ? new Vec3(n.y(), -n.x(), 0f).normalized() : new Vec3(0f, n.z(), -n.y()).normalized();
        return new Vec3[]{t, n.cross(t)};
    }

    static Mat3 skew(Vec3 r) {
        return new Mat3(0f, -r.z(), r.y(), r.z(), 0f, -r.x(), -r.y(), r.x(), 0f);
    }

    /**
     * How much an impulse at the anchors changes how fast they move apart: the 3x3 mass matrix of a point joint.
     */
    static Mat3 pointMass(RigidBody a, Vec3 ra, RigidBody b, Vec3 rb) {
        Vec3 ma = a.inverseMassAxes(), mb = b.inverseMassAxes();
        Mat3 k = Mat3.diagonal(ma.add(mb));
        Mat3 sa = skew(ra), sb = skew(rb);
        return k.add(sa.transpose().mul(a.inverseInertiaWorld()).mul(sa)).add(sb.transpose().mul(b.inverseInertiaWorld()).mul(sb));
    }

    /**
     * How much an angular impulse changes how fast the bodies turn apart.
     */
    static Mat3 angularMass(RigidBody a, RigidBody b) {
        return a.inverseInertiaWorld().add(b.inverseInertiaWorld());
    }

    /**
     * How fast B's anchor moves away from A's.
     */
    static Vec3 anchorVelocity(RigidBody a, Vec3 ra, RigidBody b, Vec3 rb) {
        Vec3 va = a.getVelocity().add(a.getAngularVelocity().cross(ra));
        Vec3 vb = b.getVelocity().add(b.getAngularVelocity().cross(rb));
        return vb.sub(va);
    }

    static Vec3 angularVelocity(RigidBody a, RigidBody b) {
        return b.getAngularVelocity().sub(a.getAngularVelocity());
    }

    /**
     * Impulse {@code p} on B at its anchor, and the opposite on A.
     */
    static void applyPoint(RigidBody a, Vec3 ra, RigidBody b, Vec3 rb, Vec3 p) {
        Vec3 ta = ra.cross(p), tb = rb.cross(p);
        b.addImpulse(p.x(), p.y(), p.z(), tb.x(), tb.y(), tb.z());
        a.addImpulse(-p.x(), -p.y(), -p.z(), -ta.x(), -ta.y(), -ta.z());
    }

    static void nudgePoint(RigidBody a, Vec3 ra, RigidBody b, Vec3 rb, Vec3 p) {
        Vec3 ta = ra.cross(p), tb = rb.cross(p);
        b.nudge(p.x(), p.y(), p.z(), tb.x(), tb.y(), tb.z());
        a.nudge(-p.x(), -p.y(), -p.z(), -ta.x(), -ta.y(), -ta.z());
    }

    static void applyAngular(RigidBody a, RigidBody b, Vec3 t) {
        b.addImpulse(0f, 0f, 0f, t.x(), t.y(), t.z());
        a.addImpulse(0f, 0f, 0f, -t.x(), -t.y(), -t.z());
    }

    static void nudgeAngular(RigidBody a, RigidBody b, Vec3 t) {
        b.nudge(0f, 0f, 0f, t.x(), t.y(), t.z());
        a.nudge(0f, 0f, 0f, -t.x(), -t.y(), -t.z());
    }

    /**
     * Pulls the anchors together after the step.
     *
     * @return the gap, in metres
     */
    static float solvePoint(RigidBody a, Vec3 localA, RigidBody b, Vec3 localB) {
        Vec3 ra = offset(a, localA), rb = offset(b, localB);
        Vec3 gap = center(b).add(rb).sub(center(a).add(ra));
        float error = gap.length();
        if (error > MAX_LINEAR_CORRECTION) gap = gap.mul(MAX_LINEAR_CORRECTION / error);
        Vec3 p = pointMass(a, ra, b, rb).inverse().mul(gap).negate();
        nudgePoint(a, ra, b, rb, p);
        return error;
    }

    /**
     * The turn that would take B from where {@code relative} says it should be (relative to A) to where it is, as an
     * axis times an angle in world space.
     */
    static Vec3 rotationError(RigidBody a, RigidBody b, Quat relative) {
        Quat e = b.getRotation().mul(a.getRotation().mul(relative).conjugate());
        if (e.w() < 0f) e = new Quat(-e.x(), -e.y(), -e.z(), -e.w());
        Vec3 v = new Vec3(e.x(), e.y(), e.z());
        float s = v.length();
        if (s < 1e-6f) return v.mul(2f);
        return v.mul(2f * (float) Math.atan2(s, e.w()) / s);
    }

    /**
     * Turns B back to where {@code relative} says it should be, relative to A.
     *
     * @return the angle it was off by
     */
    static float solveRotation(RigidBody a, RigidBody b, Quat relative) {
        Vec3 error = rotationError(a, b, relative);
        float angle = error.length();
        if (angle > MAX_ANGULAR_CORRECTION) error = error.mul(MAX_ANGULAR_CORRECTION / angle);
        nudgeAngular(a, b, angularMass(a, b).inverse().mul(error).negate());
        return angle;
    }

    static float wrapAngle(float angle) {
        float twoPi = (float) (2 * Math.PI);
        angle %= twoPi;
        if (angle > Math.PI) angle -= twoPi;
        else if (angle <= -Math.PI) angle += twoPi;
        return angle;
    }

    /**
     * One direction the bodies must or may not move apart in, with a linear part {@code n} and turning parts for each
     * body: a slider's sideways lock, a hinge's limit, a rope's length.
     */
    static final class Row {
        float nx, ny, nz, sax, say, saz, sbx, sby, sbz;
        /**
         * 1 over how much an impulse of 1 changes the row's speed.
         */
        float mass;
        /**
         * For a stop or motor row, how its impulse depends on its speed, as steps of a staircase: the row holds still at
         * {@code speed[k]} with any impulse from {@code low[k]} to {@code high[k]}, and in between the steps it
         * pushes with the {@code low} of the lower step, which is the {@code high} of the next. A stop is a step with
         * no top (or no bottom), and a motor a step at its speed with the most it can push either way.
         */
        int steps;
        /** Which way the motor is pushing flat out against a stop that outranks it (-1 or 1), or 0. */
        int pinned;
        final float[] speed = new float[3], low = new float[3], high = new float[3];

        /**
         * Sets the staircase of a row that may have a stop at each end of its travel and a motor: a stop lets the speed
         * close in on it by what is left of the gap (given as the speeds it holds at, below and above 0), and a motor
         * pushes up to {@code maxMotor} to get to its speed. A stop wins over a motor, which then pushes as hard as it
         * can against it.
         */
        void limits(boolean lowerStop, float lowerSpeed, boolean upperStop, float upperSpeed, boolean motor, float motorSpeed, float maxMotor) {
            steps = 0;
            pinned = 0;
            float inf = Float.POSITIVE_INFINITY;
            if (!motor || maxMotor <= 0f) {
                if (lowerStop) step(lowerSpeed, 0f, inf);
                if (upperStop) step(upperSpeed, -inf, 0f);
            } else if (lowerStop && motorSpeed < lowerSpeed) {
                pinned = -1;
                step(lowerSpeed, -maxMotor, inf);
                if (upperStop) step(upperSpeed, -inf, -maxMotor);
            } else if (upperStop && motorSpeed > upperSpeed) {
                pinned = 1;
                if (lowerStop) step(lowerSpeed, maxMotor, inf);
                step(upperSpeed, -inf, maxMotor);
            } else {
                if (lowerStop) step(lowerSpeed, maxMotor, inf);
                step(motorSpeed, -maxMotor, maxMotor);
                if (upperStop) step(upperSpeed, -inf, -maxMotor);
            }
        }

        private void step(float at, float low, float high) {
            speed[steps] = at;
            this.low[steps] = low;
            this.high[steps] = high;
            steps++;
        }

        /**
         * How much of the row's total impulse the motor gives, the stops giving the rest: what it can, or all it can
         * where it is pushing against a stop.
         */
        float motorShare(float total, float maxMotor) {
            return pinned != 0 ? pinned * maxMotor : Math.clamp(total, -maxMotor, maxMotor);
        }

        /**
         * Makes the row push nowhere, for a stop that isn't touched.
         */
        void clear() {
            nx = ny = nz = sax = say = saz = sbx = sby = sbz = 0f;
            mass = 0f;
        }

        /**
         * B moves along {@code n} and turns around {@code sb}; A moves along {@code -n} and turns around {@code -sa}.
         */
        void set(RigidBody a, RigidBody b, Vec3 n, Vec3 sa, Vec3 sb) {
            nx = n.x();
            ny = n.y();
            nz = n.z();
            sax = sa.x();
            say = sa.y();
            saz = sa.z();
            sbx = sb.x();
            sby = sb.y();
            sbz = sb.z();
            Vec3 ma = a.inverseMassAxes(), mb = b.inverseMassAxes();
            float k = nx * nx * (ma.x() + mb.x()) + ny * ny * (ma.y() + mb.y()) + nz * nz * (ma.z() + mb.z())
                    + sa.dot(a.inverseInertiaWorld().mul(sa)) + sb.dot(b.inverseInertiaWorld().mul(sb));
            mass = k > 1e-12f ? 1f / k : 0f;
        }

        void copy(Row other) {
            nx = other.nx;
            ny = other.ny;
            nz = other.nz;
            sax = other.sax;
            say = other.say;
            saz = other.saz;
            sbx = other.sbx;
            sby = other.sby;
            sbz = other.sbz;
            mass = other.mass;
        }

        void angular(RigidBody a, RigidBody b, Vec3 axis) {
            set(a, b, Vec3.ZERO, axis, axis);
        }

        float velocity(RigidBody a, RigidBody b) {
            return nx * (b.vx - a.vx) + ny * (b.vy - a.vy) + nz * (b.vz - a.vz)
                    + sbx * b.wx + sby * b.wy + sbz * b.wz - sax * a.wx - say * a.wy - saz * a.wz;
        }

        void apply(RigidBody a, RigidBody b, float impulse) {
            b.addImpulse(nx * impulse, ny * impulse, nz * impulse, sbx * impulse, sby * impulse, sbz * impulse);
            a.addImpulse(-nx * impulse, -ny * impulse, -nz * impulse, -sax * impulse, -say * impulse, -saz * impulse);
        }

        void nudge(RigidBody a, RigidBody b, float impulse) {
            b.nudge(nx * impulse, ny * impulse, nz * impulse, sbx * impulse, sby * impulse, sbz * impulse);
            a.nudge(-nx * impulse, -ny * impulse, -nz * impulse, -sax * impulse, -say * impulse, -saz * impulse);
        }

        Vec3 linear() {
            return new Vec3(nx, ny, nz);
        }

        Vec3 turnB() {
            return new Vec3(sbx, sby, sbz);
        }
    }
}
