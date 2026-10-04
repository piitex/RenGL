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
        body.updateCollisionShapes(CONTACT_MARGIN);
        return this;
    }

    public boolean remove(RigidBody body) {
        if (!bodies.remove(body)) return false;
        broadphase.remove(body);
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
        collide();
        for (RigidBody body : active) {
            if (body.getWorld() == this) body.integrateVelocity(dt, gravity);
        }
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
    private void collide() {
        for (RigidBody body : bodies) body.updateCollisionShapes(CONTACT_MARGIN);
        Map<PairKey, Contact> previous = contactMap;
        Map<PairKey, Contact> found = new HashMap<>();
        List<Contact> list = new ArrayList<>();
        broadphase.findPairs((a, b) -> {
            for (int i = 0; i < a.shapeCount(); i++) {
                for (int j = 0; j < b.shapeCount(); j++) {
                    if (!Broadphase.overlap(a.shapeBounds(i), b.shapeBounds(j))) continue;
                    if (!Narrowphase.collide(a.collisionShape(i), a.shapePose(i), b.collisionShape(j), b.shapePose(j), CONTACT_MARGIN, manifold)) continue;
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

    private void followMovedNodes() {
        for (RigidBody body : bodies) {
            if (body.nodeMoved()) body.followNode();
        }
    }

    private void writeNodes(float alpha) {
        for (RigidBody body : bodies) body.writeNode(alpha);
    }
}
