package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /**
     * Which two shapes a contact is between, and the triangle when one is a mesh (-1 otherwise).
     */
    private record PairKey(RigidBody a, int shapeA, RigidBody b, int shapeB, int triangle) {
    }

    private record BodyPair(RigidBody a, RigidBody b) {
    }

    private final List<ContactListener> listeners = new ArrayList<>();
    private Map<BodyPair, Contact> touching = new LinkedHashMap<>();
    private boolean queriesHitTriggers;

    private final List<RigidBody> bodies = new ArrayList<>();
    private final List<Joint> joints = new ArrayList<>();
    private final List<CharacterController> characters = new ArrayList<>();
    private final List<ColliderView> views = new ArrayList<>();
    float nodesAlpha = 1f; // how far between steps the nodes were last put
    private final Broadphase broadphase = new Broadphase();
    final BodyTree tree = new BodyTree();
    private final Manifold manifold = new Manifold();
    private Map<PairKey, Contact> contactMap = new HashMap<>();
    private List<Contact> contacts = new ArrayList<>();
    private int nextId;
    private int velocityIterations = 10;
    private int positionIterations = 4;
    private int substeps = 1;
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
        if (body.isDynamic() && body.hasSurface()) throw new IllegalStateException("A body with a plane or mesh can't be dynamic");
        body.id = nextId++;
        bodies.add(body);
        body.attach(this);
        body.updateCollisionShapes(CONTACT_MARGIN, fixedStep, gravity);
        return this;
    }

    public boolean remove(RigidBody body) {
        if (!bodies.contains(body)) return false;
        if (body.character != null && characters.remove(body.character)) body.character.detach();
        // Its contacts end now, so a listener counting what touches what stays balanced.
        List<BodyPair> ended = new ArrayList<>();
        for (BodyPair pair : touching.keySet()) if (pair.a() == body || pair.b() == body) ended.add(pair);
        for (Joint joint : List.copyOf(body.joints)) removeJoint(joint);
        bodies.remove(body);
        tree.remove(body);
        for (Contact c : contacts) { // whatever rested on it has to fall
            RigidBody other = c.getOther(body);
            if (other != null) other.wakeUp();
        }
        contacts.removeIf(c -> c.getBodyA() == body || c.getBodyB() == body);
        contactMap.values().removeIf(c -> c.getBodyA() == body || c.getBodyB() == body);
        body.detach();
        for (BodyPair pair : ended) {
            boolean trigger = touching.remove(pair).isTrigger();
            fireEnd(pair, trigger);
        }
        return true;
    }

    public List<RigidBody> bodies() {
        return Collections.unmodifiableList(bodies);
    }

    /**
     * Adds a joint between bodies already in this world, reading its anchors against where they are now. Adding a
     * joint twice does nothing.
     */
    public PhysicsWorld addJoint(Joint joint) {
        if (joint.getWorld() == this) return this;
        if (joint.getWorld() != null) throw new IllegalStateException(joint + " is already in another world");
        if (joint.getBodyA().getWorld() != this || (joint.getBodyB() != null && joint.getBodyB().getWorld() != this)) {
            throw new IllegalStateException("Add both bodies to the world before the joint between them");
        }
        joints.add(joint);
        joint.getBodyA().joints.add(joint);
        if (joint.getBodyB() != null) joint.getBodyB().joints.add(joint);
        joint.attach(this);
        return this;
    }

    /**
     * Takes a joint out, letting its bodies go.
     */
    public boolean removeJoint(Joint joint) {
        if (!joints.remove(joint)) return false;
        joint.getBodyA().joints.remove(joint);
        if (joint.getBodyB() != null) joint.getBodyB().joints.remove(joint);
        joint.getBodyA().wakeUp();
        if (joint.getBodyB() != null) joint.getBodyB().wakeUp();
        joint.detach();
        return true;
    }

    public List<Joint> joints() {
        return Collections.unmodifiableList(joints);
    }

    /**
     * Adds a character, starting where its node is. It moves in each step before the bodies, so they see where it
     * went.
     */
    public PhysicsWorld addCharacter(CharacterController character) {
        if (character.getWorld() == this) return this;
        if (character.getWorld() != null) throw new IllegalStateException(character + " is already in another world");
        add(character.getBody());
        characters.add(character);
        character.attach(this);
        return this;
    }

    public boolean removeCharacter(CharacterController character) {
        if (!characters.contains(character)) return false;
        return remove(character.getBody());
    }

    public List<CharacterController> characters() {
        return Collections.unmodifiableList(characters);
    }

    /**
     * Whether a joint between {@code a} and {@code b} keeps them from colliding.
     */
    private static boolean jointedApart(RigidBody a, RigidBody b) {
        List<Joint> list = a.joints.size() <= b.joints.size() ? a.joints : b.joints;
        for (Joint joint : list) {
            if (!joint.isCollideConnected() && (joint.getBodyA() == a && joint.getBodyB() == b || joint.getBodyA() == b && joint.getBodyB() == a)) return true;
        }
        return false;
    }

    /**
     * The shapes touching or about to touch, as found at the start of the last step. Each pair of shapes has one
     * contact; a body made of several shapes can have several with the same neighbour, and so can a body on a mesh,
     * one for each way the triangles under it face.
     */
    public List<Contact> getContacts() {
        return Collections.unmodifiableList(contacts);
    }

    public PhysicsWorld addContactListener(ContactListener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        return this;
    }

    public PhysicsWorld removeContactListener(ContactListener listener) {
        listeners.remove(listener);
        return this;
    }

    // ---- Queries --------------------------------------------------------------------------------------------------

    public boolean isQueriesHitTriggers() {
        return queriesHitTriggers;
    }

    /**
     * Whether raycasts and overlap queries find triggers (off by default, so a ray passes through trigger zones).
     */
    public PhysicsWorld setQueriesHitTriggers(boolean hit) {
        queriesHitTriggers = hit;
        return this;
    }

    /**
     * The nearest body the ray hits within {@code maxDistance}, or null. A ray from the camera through the mouse
     * ({@code scene.camera().screenRay(...)}) finds what was clicked; a ray down from a character finds the ground.
     */
    public RaycastHit raycast(Ray ray, float maxDistance) {
        return raycast(ray, maxDistance, -1);
    }

    /**
     * As {@link #raycast(Ray, float)}, counting only bodies whose collision layer shares a bit with {@code mask}.
     */
    public RaycastHit raycast(Ray ray, float maxDistance, int mask) {
        RaycastHit best = null;
        for (RigidBody body : alongRay(ray, maxDistance)) {
            RaycastHit hit = raycastBody(body, ray, best == null ? maxDistance : best.distance(), mask);
            if (hit != null) best = hit;
        }
        return best;
    }

    /**
     * Every body the ray hits within {@code maxDistance}, one hit per body (its nearest), nearest first.
     */
    public List<RaycastHit> raycastAll(Ray ray, float maxDistance, int mask) {
        List<RaycastHit> hits = new ArrayList<>();
        for (RigidBody body : alongRay(ray, maxDistance)) {
            RaycastHit hit = raycastBody(body, ray, maxDistance, mask);
            if (hit != null) hits.add(hit);
        }
        hits.sort((a, b) -> Float.compare(a.distance(), b.distance()));
        return hits;
    }

    private RaycastHit raycastBody(RigidBody body, Ray ray, float maxDistance, int mask) {
        if (!queryable(body, mask)) return null;
        float toBox = ray.distanceTo(body.collisionBounds());
        if (toBox < 0f || toBox > maxDistance) return null;
        RaycastHit best = null;
        for (int i = 0; i < body.shapeCount(); i++) {
            Raycast.Result hit = Raycast.cast(body.collisionShape(i), body.shapePose(i), ray, best == null ? maxDistance : best.distance());
            if (hit != null) best = new RaycastHit(body, body.getShapes().get(i), ray.at(hit.distance()), hit.normal(), hit.distance(), hit.triangle());
        }
        return best;
    }

    /**
     * The bodies overlapping {@code shape} placed at {@code position} and turned by {@code rotation}, in the order they
     * were added. Only bodies whose collision layer shares a bit with {@code mask} count.
     */
    public List<RigidBody> overlap(Shape shape, Vec3 position, Quat rotation, int mask) {
        if (shape instanceof MeshShape || shape instanceof PlaneShape) throw new IllegalArgumentException("Overlap queries take a solid shape, not " + shape);
        Pose pose = new Pose(position, rotation == null ? Quat.IDENTITY : rotation.normalized());
        RigidBody probe = new RigidBody(BodyType.STATIC).addShape(shape);
        probe.setPosition(position).setRotation(pose.rotation());
        probe.updateCollisionShapes(0f);
        List<RigidBody> found = new ArrayList<>();
        for (RigidBody body : near(probe.collisionBounds())) {
            if (!queryable(body, mask) || !Broadphase.overlap(probe.collisionBounds(), body.collisionBounds())) continue;
            for (int i = 0; i < body.shapeCount(); i++) {
                if (!Broadphase.overlap(probe.collisionBounds(), body.shapeBounds(i))) continue;
                if (body.collisionShape(i) instanceof MeshShape mesh) {
                    boolean[] touching = new boolean[1];
                    MeshCollision.collide(mesh, body.shapePose(i), shape, pose, 0f, true, (_, m) -> {
                        for (int k = 0; k < m.count; k++) touching[0] |= m.depths[k] >= 0f;
                        return !touching[0];
                    });
                    if (touching[0]) {
                        found.add(body);
                        break;
                    }
                } else if (Narrowphase.collide(shape, pose, body.collisionShape(i), body.shapePose(i), 0f, manifold) && manifold.depths[0] >= 0f) {
                    found.add(body);
                    break;
                }
            }
        }
        return found;
    }

    /**
     * The bodies within {@code radius} of {@code center}: who an explosion reaches, what is near the player.
     */
    public List<RigidBody> overlapSphere(Vec3 center, float radius) {
        return overlap(new SphereShape(radius), center, Quat.IDENTITY, -1);
    }

    /**
     * The bodies overlapping a box, given by its centre, half extents and rotation.
     */
    public List<RigidBody> overlapBox(Vec3 center, Vec3 halfExtents, Quat rotation) {
        return overlap(new BoxShape(halfExtents), center, rotation, -1);
    }

    /**
     * The bodies whose leaves in the tree the box reaches, in the order they were added: a few more than overlap it.
     */
    List<RigidBody> near(Bounds box) {
        List<RigidBody> found = new ArrayList<>();
        tree.query(box, found::add);
        found.sort(Broadphase.BY_ID);
        return found;
    }

    private List<RigidBody> alongRay(Ray ray, float maxDistance) {
        List<RigidBody> found = new ArrayList<>();
        tree.raycast(ray, maxDistance, found::add);
        found.sort(Broadphase.BY_ID);
        return found;
    }

    private boolean queryable(RigidBody body, int mask) {
        return (body.getCollisionLayer() & mask) != 0 && (queriesHitTriggers || !body.isTrigger());
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

    public int getSubsteps() {
        return substeps;
    }

    /**
     * Cuts every step into this many smaller ones (1 by default), each with its own collision detection and solving. The
     * small steps are what tall stacks and heavy loads on light chains need: with a step a quarter as long, bodies move
     * a quarter as far between two looks at them, so the solver's passes have much less to correct. It costs about as
     * much more as there are substeps, so try 2 to 4 before raising the solver passes. Forces and the
     * {@link StepHandler} still work on the whole step; events come once per whole step.
     */
    public PhysicsWorld setSubsteps(int substeps) {
        this.substeps = Math.clamp(substeps, 1, 32);
        return this;
    }

    public int getPositionIterations() {
        return positionIterations;
    }

    /**
     * How many passes fix overlaps each step (4 by default). Joints get twice as many to pull their bodies back
     * together, stopping early once every joint is closed.
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

    void addView(ColliderView view) {
        views.add(view);
    }

    void removeView(ColliderView view) {
        views.remove(view);
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
        for (CharacterController character : List.copyOf(characters)) character.step(dt);
        List<Joint> broken = null;
        float h = dt / substeps;
        for (int sub = 0; sub < substeps; sub++) {
            List<Joint> snapped = substep(active, h, sub == substeps - 1);
            if (snapped != null) {
                if (broken == null) broken = new ArrayList<>();
                broken.addAll(snapped);
            }
        }
        time += dt;
        stepCount++;
        fireEvents();
        if (broken != null) for (Joint joint : broken) joint.fireBreak();
    }

    /**
     * One pass of collision detection, solving and integration over {@code dt} seconds, which is the whole step unless
     * it was cut into substeps.
     *
     * @return the joints that broke in it, or null
     */
    private List<Joint> substep(RigidBody[] active, float dt, boolean last) {
        collide(dt);
        updateSleep(dt);
        for (RigidBody body : active) {
            if (body.getWorld() == this) body.integrateVelocity(dt, gravity);
        }
        List<Contact> solving = new ArrayList<>(contacts.size());
        for (Contact contact : contacts) if (ContactSolver.active(contact)) solving.add(contact);
        List<Joint> holding = new ArrayList<>(joints.size());
        for (Joint joint : joints) if (joint.active()) holding.add(joint);
        ContactSolver.prepare(solving, dt);
        for (Joint joint : holding) joint.prepare(dt);
        // Joints first, so the contacts have the last word and joined bodies don't get pulled into the ground.
        for (int i = 0; i < velocityIterations; i++) {
            for (Joint joint : holding) joint.solveVelocity();
            ContactSolver.solveVelocities(solving, dt, i % 2 == 1);
        }
        ContactSolver.applyRestitution(solving, dt);
        for (int i = 0; i < positionIterations; i++) ContactSolver.solvePositions(solving, dt, i % 2 == 1);
        List<Joint> broken = null;
        for (Joint joint : holding) {
            if (!joint.checkBreak()) continue;
            if (broken == null) broken = new ArrayList<>();
            broken.add(joint);
            removeJoint(joint);
        }
        if (broken != null) holding.removeAll(broken);
        for (RigidBody body : active) {
            if (body.getWorld() == this) body.integratePosition(dt);
            if (last) body.clearForces();
        }
        // The velocities kept the joints from coming apart, as far as a straight-line step can; turning moved the
        // anchors a little anyway, so pull them back together now, before anything sees the drift. Joints are few and
        // a chain passes each fix along one link per pass, so they get twice the passes.
        for (int i = 0; i < Math.max(2 * positionIterations, 1); i++) {
            boolean done = true;
            for (Joint joint : holding) done &= joint.solvePosition();
            if (done) break;
        }
        if (last) for (RigidBody body : bodies) if (!body.settled()) body.updateCollisionShapes(CONTACT_MARGIN); // fresh for queries
        return broken;
    }

    /**
     * Compares which pairs of bodies touch now with the last step and tells the listeners what changed.
     */
    private void fireEvents() {
        Map<BodyPair, Contact> now = new LinkedHashMap<>();
        for (Contact c : contacts) {
            if (c.isTouching()) now.putIfAbsent(new BodyPair(c.getBodyA(), c.getBodyB()), c);
        }
        Map<BodyPair, Contact> before = touching;
        touching = now;
        if (listeners.isEmpty() && !anyBodyListens(now, before)) return;
        for (Map.Entry<BodyPair, Contact> entry : before.entrySet()) {
            if (!now.containsKey(entry.getKey())) fireEnd(entry.getKey(), entry.getValue().isTrigger());
        }
        for (Map.Entry<BodyPair, Contact> entry : now.entrySet()) {
            Contact c = entry.getValue();
            boolean began = !before.containsKey(entry.getKey());
            if (c.isTrigger()) {
                if (!began) continue;
                RigidBody trigger = c.getBodyA().isTrigger() ? c.getBodyA() : c.getBodyB();
                RigidBody other = c.getOther(trigger);
                for (ContactListener listener : listenersFor(c.getBodyA(), c.getBodyB())) listener.triggerEnter(trigger, other);
            } else {
                for (ContactListener listener : listenersFor(c.getBodyA(), c.getBodyB())) {
                    if (began) listener.contactBegin(c);
                    else listener.contactStay(c);
                }
            }
        }
    }

    private void fireEnd(BodyPair pair, boolean trigger) {
        for (ContactListener listener : listenersFor(pair.a(), pair.b())) {
            if (trigger) {
                RigidBody t = pair.a().isTrigger() ? pair.a() : pair.b();
                listener.triggerExit(t, t == pair.a() ? pair.b() : pair.a());
            } else {
                listener.contactEnd(pair.a(), pair.b());
            }
        }
    }

    private List<ContactListener> listenersFor(RigidBody a, RigidBody b) {
        List<ContactListener> list = new ArrayList<>(listeners);
        if (a.getContactListener() != null) list.add(a.getContactListener());
        if (b.getContactListener() != null && b.getContactListener() != a.getContactListener()) list.add(b.getContactListener());
        return list;
    }

    private static boolean anyBodyListens(Map<BodyPair, Contact> now, Map<BodyPair, Contact> before) {
        for (BodyPair pair : now.keySet()) if (pair.a().getContactListener() != null || pair.b().getContactListener() != null) return true;
        for (BodyPair pair : before.keySet()) if (pair.a().getContactListener() != null || pair.b().getContactListener() != null) return true;
        return false;
    }

    /**
     * Finds every contact: the broadphase pairs up bodies whose boxes overlap, then each pair of their shapes whose
     * boxes overlap gets an exact test. A contact found last step is kept and updated, so it can remember what the
     * solver learned about it.
     */
    private void collide(float dt) {
        // A static or sleeping body's shapes stay where they were; with a big level, placing them all each step would
        // cost more than everything else.
        for (RigidBody body : bodies) if (!body.settled()) body.updateCollisionShapes(CONTACT_MARGIN, dt, gravity);
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
        broadphase.findPairs(bodies, tree, (a, b) -> {
            if (!a.joints.isEmpty() && !b.joints.isEmpty() && jointedApart(a, b)) return;
            float margin = a.speculative + b.speculative - CONTACT_MARGIN;
            for (int i = 0; i < a.shapeCount(); i++) {
                for (int j = 0; j < b.shapeCount(); j++) {
                    if (!Broadphase.overlap(a.shapeBounds(i), b.shapeBounds(j))) continue;
                    Shape sa = a.collisionShape(i), sb = b.collisionShape(j);
                    if (sa instanceof MeshShape || sb instanceof MeshShape) {
                        // One contact per triangle touched, each remembered on its own.
                        int si = i, sj = j;
                        collideMesh(a, i, b, j, margin, (triangle, m) -> {
                            PairKey key = new PairKey(a, si, b, sj, triangle);
                            Contact contact = previous.get(key);
                            if (contact == null) contact = new Contact(a, si, b, sj, triangle);
                            contact.update(m);
                            found.put(key, contact);
                            list.add(contact);
                            return true;
                        });
                        continue;
                    }
                    if (!Narrowphase.collide(sa, a.shapePose(i), sb, b.shapePose(j), margin, manifold)) continue;
                    PairKey key = new PairKey(a, i, b, j, -1);
                    Contact contact = previous.get(key);
                    if (contact == null) contact = new Contact(a, i, b, j, -1);
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
        return new PairKey(c.getBodyA(), c.shapeIndexA(), c.getBodyB(), c.shapeIndexB(), c.getTriangle());
    }

    /**
     * Shape {@code i} of {@code a} against shape {@code j} of {@code b}, one of them a mesh.
     */
    private static void collideMesh(RigidBody a, int i, RigidBody b, int j, float margin, MeshCollision.TriangleContact visit) {
        if (a.collisionShape(i) instanceof MeshShape mesh) {
            MeshCollision.collide(mesh, a.shapePose(i), b.collisionShape(j), b.shapePose(j), margin, true, visit);
        } else {
            MeshCollision.collide((MeshShape) b.collisionShape(j), b.shapePose(j), a.collisionShape(i), a.shapePose(i), margin, false, visit);
        }
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
            if (!c.isTrigger() && a.isDynamic() && b.isDynamic()) union(a.islandIndex, b.islandIndex);
        }
        for (Joint joint : joints) { // joined bodies sleep and wake together
            RigidBody a = joint.getBodyA(), b = joint.getBodyB();
            if (b != null && a.isDynamic() && b.isDynamic()) union(a.islandIndex, b.islandIndex);
        }
        // An island stays awake if any body in it is: still moving, not allowed to sleep, or pushed by a moving kinematic body.
        boolean[] awake = new boolean[n];
        for (int i = 0; i < n; i++) {
            RigidBody body = bodies.get(i);
            if (body.isAwakeDynamic() && body.sleepTime < TIME_TO_SLEEP) awake[find(i)] = true;
        }
        for (Contact c : contacts) {
            if (c.isTrigger()) continue;
            RigidBody a = c.getBodyA(), b = c.getBodyB();
            if (a.isDynamic() && b.getType() == BodyType.KINEMATIC && b.isMoving()) awake[find(a.islandIndex)] = true;
            if (b.isDynamic() && a.getType() == BodyType.KINEMATIC && a.isMoving()) awake[find(b.islandIndex)] = true;
        }
        for (Joint joint : joints) {
            RigidBody a = joint.getBodyA(), b = joint.getBodyB();
            if (b == null) continue;
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
        nodesAlpha = alpha;
        for (RigidBody body : bodies) body.writeNode(alpha);
        for (ColliderView view : views) view.sync(alpha);
    }
}
