package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves {@link RigidBody rigid bodies}: gravity, forces, spin and inertia. Give it to a scene and it runs once per
 * frame, before the scene's update handler, so the handler sees where things ended up:
 * <pre>
 * PhysicsWorld world = new PhysicsWorld();
 * scene.setPhysics(world);
 * world.add(new RigidBody(ball).addShape(new SphereShape(0.5f)));
 * </pre>
 * Or run it yourself with {@link #update(float)} each frame, or {@link #step(float)} for one exact step.
 * <p>
 * Units are metres, kilograms and seconds, with +Y up. The world moves in fixed steps (60 a second by default) whatever
 * the frame rate, so the result doesn't depend on how fast the machine draws. A frame usually runs zero, one or two
 * steps; the nodes are then placed between the last two steps by how far the frame got into the next one, which keeps
 * motion smooth at any refresh rate (turn this off with {@link #setInterpolation}). The bodies themselves always report
 * where the last step left them.
 * <p>
 * Use the world from one thread, the render thread when it belongs to a scene.
 */
public final class PhysicsWorld {

    /**
     * Runs before every step, the place for forces that act over time.
     */
    public interface StepHandler {
        void step(PhysicsWorld world, float dt);
    }

    /**
     * How close shapes have to come before they get a contact, in metres. Tracking them a little before they touch lets
     * the solver stop a falling body at the surface instead of after it sinks in.
     */
    static final float CONTACT_MARGIN = 0.02f;

    private record PairKey(RigidBody a, int shapeA, RigidBody b, int shapeB) {
    }

    private final List<RigidBody> bodies = new ArrayList<>();
    private final Broadphase broadphase = new Broadphase();
    private final Manifold manifold = new Manifold();
    private Map<PairKey, Contact> contactMap = new HashMap<>();
    private List<Contact> contacts = new ArrayList<>();
    private int nextId;
    private int velocityIterations = 10;
    private int positionIterations = 4;
    private boolean sleeping = true;
    private int[] islands = new int[0];

    /**
     * How slow a body has to be, in metres (and radians) per second, and for how long, before it may sleep.
     */
    static final float SLEEP_SPEED = 0.05f, SLEEP_ANGULAR_SPEED = 0.05f, TIME_TO_SLEEP = 0.5f;
    private Vec3 gravity = new Vec3(0f, -9.81f, 0f);
    private float fixedStep = 1f / 60f;
    private int maxSteps = 8;
    private boolean interpolation = true;
    private double accumulator;
    private double time;
    private long stepCount;
    private StepHandler stepHandler;

    /**
     * Adds a body; it starts where its node is. Adding a body twice does nothing.
     */
    public PhysicsWorld add(RigidBody body) {
        if (body.getWorld() == this) return this;
        if (body.getWorld() != null) throw new IllegalStateException(body + " is already in another world");
        if (body.isDynamic() && body.hasPlane()) throw new IllegalStateException("A body with a plane can't be dynamic");
        body.id = nextId++;
        bodies.add(body);
        broadphase.add(body);
        body.attach(this);
        body.updateCollisionShapes(CONTACT_MARGIN, fixedStep, gravity);
        return this;
    }

    public boolean remove(RigidBody body) {
        if (!bodies.remove(body)) return false;
        broadphase.remove(body);
        for (Contact c : contacts) { // whatever rested on it has to fall
            RigidBody other = c.getOther(body);
            if (other != null) other.wakeUp();
        }
        contacts.removeIf(c -> c.getBodyA() == body || c.getBodyB() == body);
        contactMap.values().removeIf(c -> c.getBodyA() == body || c.getBodyB() == body);
        body.detach();
        return true;
    }

    public List<RigidBody> bodies() {
        return Collections.unmodifiableList(bodies);
    }

    /**
     * The shapes touching or about to touch, as found at the start of the last step. Each pair of shapes has one
     * contact; a body made of several shapes can have several with the same neighbour.
     */
    public List<Contact> getContacts() {
        return Collections.unmodifiableList(contacts);
    }

    public Vec3 getGravity() {
        return gravity;
    }

    /**
     * The pull on every body, in metres per second squared; (0, -9.81, 0) by default.
     */
    public PhysicsWorld setGravity(Vec3 gravity) {
        this.gravity = gravity == null ? Vec3.ZERO : gravity;
        for (RigidBody body : bodies) body.wakeUp();
        return this;
    }

    public float getFixedStep() {
        return fixedStep;
    }

    /**
     * How long each step is, in seconds: 1/60 by default. Shorter steps are more accurate and cost more.
     */
    public PhysicsWorld setFixedStep(float seconds) {
        if (!(seconds > 0f)) throw new IllegalArgumentException("The step must be above 0, not " + seconds);
        fixedStep = seconds;
        return this;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    /**
     * The most steps one {@link #update} runs (8 by default). After a long pause the world skips the rest instead of
     * running hundreds of steps and falling further behind, so the game slows down rather than stalls.
     */
    public PhysicsWorld setMaxSteps(int maxSteps) {
        this.maxSteps = Math.max(1, maxSteps);
        return this;
    }

    public boolean isInterpolation() {
        return interpolation;
    }

    /**
     * Whether {@link #update} places nodes between the last two steps (on by default). Off, nodes jump from step to step,
     * which shows as judder when the frame rate isn't the step rate.
     */
    public PhysicsWorld setInterpolation(boolean interpolation) {
        this.interpolation = interpolation;
        return this;
    }

    public int getVelocityIterations() {
        return velocityIterations;
    }

    /**
     * How many passes the solver makes over the contacts each step (10 by default, enough for a stack of ten boxes).
     * More make taller stacks hold and cost more: 20 holds fifteen.
     */
    public PhysicsWorld setVelocityIterations(int iterations) {
        velocityIterations = Math.max(1, iterations);
        return this;
    }

    public int getPositionIterations() {
        return positionIterations;
    }

    /**
     * How many passes fix overlaps each step (4 by default).
     */
    public PhysicsWorld setPositionIterations(int iterations) {
        positionIterations = Math.max(0, iterations);
        return this;
    }

    public boolean isSleeping() {
        return sleeping;
    }

    /**
     * Whether resting bodies may fall asleep (on by default), which makes a settled scene almost free.
     */
    public PhysicsWorld setSleeping(boolean sleeping) {
        this.sleeping = sleeping;
        if (!sleeping) for (RigidBody body : bodies) body.wakeUp();
        return this;
    }

    public StepHandler getStepHandler() {
        return stepHandler;
    }

    public PhysicsWorld setStepHandler(StepHandler stepHandler) {
        this.stepHandler = stepHandler;
        return this;
    }

    /**
     * Seconds simulated so far.
     */
    public double getTime() {
        return time;
    }

    public long getStepCount() {
        return stepCount;
    }

    /**
     * How far the last {@link #update} got into the next step, from 0 to 1: where the nodes were placed between the
     * last two steps.
     */
    public float getInterpolationAlpha() {
        return (float) (accumulator / fixedStep);
    }

    /**
     * Moves the world on by {@code delta} seconds of frame time, in whole fixed steps, and places the nodes.
     */
    public void update(float delta) {
        followMovedNodes();
        if (delta > 0f) accumulator += delta;
        int steps = 0;
        while (accumulator >= fixedStep && steps < maxSteps) {
            simulate(fixedStep);
            accumulator -= fixedStep;
            steps++;
        }
        if (accumulator >= fixedStep) accumulator %= fixedStep; // too far behind: let the rest go
        writeNodes(interpolation ? getInterpolationAlpha() : 1f);
    }

    /**
     * Runs one step of exactly {@code dt} seconds and puts the nodes where it left the bodies.
     */
    public void step(float dt) {
        if (!(dt > 0f)) throw new IllegalArgumentException("The step must be above 0, not " + dt);
        followMovedNodes();
        simulate(dt);
        writeNodes(1f);
    }

    private void simulate(float dt) {
        RigidBody[] active = bodies.toArray(RigidBody[]::new);
        for (RigidBody body : active) body.savePrevious();
        if (stepHandler != null) stepHandler.step(this, dt);
        collide(dt);
        updateSleep(dt);
        for (RigidBody body : active) {
            if (body.getWorld() == this) body.integrateVelocity(dt, gravity);
        }
        List<Contact> solving = new ArrayList<>(contacts.size());
        for (Contact contact : contacts) if (ContactSolver.active(contact)) solving.add(contact);
        ContactSolver.prepare(solving, dt);
        for (int i = 0; i < velocityIterations; i++) ContactSolver.solveVelocities(solving, dt, i % 2 == 1);
        ContactSolver.applyRestitution(solving, dt);
        for (int i = 0; i < positionIterations; i++) ContactSolver.solvePositions(solving, dt, i % 2 == 1);
        for (RigidBody body : active) {
            if (body.getWorld() == this) body.integratePosition(dt);
            body.clearForces();
        }
        time += dt;
        stepCount++;
    }

    /**
     * Finds every contact: the broadphase pairs up bodies whose boxes overlap, then each pair of their shapes whose
     * boxes overlap gets an exact test. A contact found last step is kept and updated, so it can remember what the
     * solver learned about it.
     */
    private void collide(float dt) {
        for (RigidBody body : bodies) body.updateCollisionShapes(CONTACT_MARGIN, dt, gravity);
        Map<PairKey, Contact> previous = contactMap;
        Map<PairKey, Contact> found = new HashMap<>();
        List<Contact> list = new ArrayList<>();
        // Sleeping bodies aren't tested, but keep the contacts they fell asleep with, so waking one wakes its neighbours.
        for (Map.Entry<PairKey, Contact> entry : previous.entrySet()) {
            Contact c = entry.getValue();
            RigidBody a = c.getBodyA(), b = c.getBodyB();
            if (!a.isActive() && !b.isActive() && (a.isSleeping() || b.isSleeping())) found.put(entry.getKey(), c);
        }
        for (Contact c : contacts) if (found.containsKey(keyOf(c))) list.add(c);
        broadphase.findPairs((a, b) -> {
            float margin = a.speculative + b.speculative - CONTACT_MARGIN;
            for (int i = 0; i < a.shapeCount(); i++) {
                for (int j = 0; j < b.shapeCount(); j++) {
                    if (!Broadphase.overlap(a.shapeBounds(i), b.shapeBounds(j))) continue;
                    if (!Narrowphase.collide(a.collisionShape(i), a.shapePose(i), b.collisionShape(j), b.shapePose(j), margin, manifold)) continue;
                    PairKey key = new PairKey(a, i, b, j);
                    Contact contact = previous.get(key);
                    if (contact == null) contact = new Contact(a, i, b, j);
                    contact.update(manifold);
                    found.put(key, contact);
                    list.add(contact);
                }
            }
        });
        contactMap = found;
        contacts = list;
    }

    private static PairKey keyOf(Contact c) {
        return new PairKey(c.getBodyA(), c.shapeIndexA(), c.getBodyB(), c.shapeIndexB());
    }

    /**
     * Groups bodies that touch into islands; an island whose bodies have all been still long enough falls asleep
     * together, and one with any moving body (or touched by a moving kinematic body) is woken whole. A stack sleeps as
     * one, and a box landing on it wakes all of it.
     */
    private void updateSleep(float dt) {
        int n = bodies.size();
        if (islands.length < n) islands = new int[Math.max(n, islands.length * 2)];
        for (int i = 0; i < n; i++) {
            RigidBody body = bodies.get(i);
            body.islandIndex = i;
            islands[i] = i;
            if (!body.isAwakeDynamic()) continue;
            boolean still = sleeping && body.isSleepingAllowed()
                    && body.vx * body.vx + body.vy * body.vy + body.vz * body.vz < SLEEP_SPEED * SLEEP_SPEED
                    && body.wx * body.wx + body.wy * body.wy + body.wz * body.wz < SLEEP_ANGULAR_SPEED * SLEEP_ANGULAR_SPEED;
            body.sleepTime = still ? body.sleepTime + dt : 0f;
        }
        for (Contact c : contacts) {
            RigidBody a = c.getBodyA(), b = c.getBodyB();
            if (a.isDynamic() && b.isDynamic()) union(a.islandIndex, b.islandIndex);
        }
        // An island stays awake if any body in it is: still moving, not allowed to sleep, or pushed by a moving kinematic body.
        boolean[] awake = new boolean[n];
        for (int i = 0; i < n; i++) {
            RigidBody body = bodies.get(i);
            if (body.isAwakeDynamic() && body.sleepTime < TIME_TO_SLEEP) awake[find(i)] = true;
        }
        for (Contact c : contacts) {
            RigidBody a = c.getBodyA(), b = c.getBodyB();
            if (a.isDynamic() && b.getType() == BodyType.KINEMATIC && b.isMoving()) awake[find(a.islandIndex)] = true;
            if (b.isDynamic() && a.getType() == BodyType.KINEMATIC && a.isMoving()) awake[find(b.islandIndex)] = true;
        }
        for (int i = 0; i < n; i++) {
            RigidBody body = bodies.get(i);
            if (!body.isDynamic()) continue;
            if (awake[find(i)]) {
                if (body.isSleeping()) body.wakeUp();
            } else if (!body.isSleeping()) {
                body.sleep();
            }
        }
    }

    private int find(int i) {
        while (islands[i] != i) {
            islands[i] = islands[islands[i]];
            i = islands[i];
        }
        return i;
    }

    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra != rb) islands[Math.max(ra, rb)] = Math.min(ra, rb);
    }

    private void followMovedNodes() {
        for (RigidBody body : bodies) {
            if (body.nodeMoved()) body.followNode();
        }
    }

    private void writeNodes(float alpha) {
        for (RigidBody body : bodies) body.writeNode(alpha);
    }
}
