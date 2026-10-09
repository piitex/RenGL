package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Node3D;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a character around a physics world: a standing capsule that goes where it's told rather than where it's
 * pushed. It slides along walls, walks up slopes and steps, stays on the ground going down them, falls, jumps, rides
 * moving platforms and shoves loose objects out of its way.
 * <pre>
 * CharacterController player = new CharacterController(playerNode, 0.35f, 1.8f);
 * world.addCharacter(player);
 * // each frame:
 * player.setWalkVelocity(forward.mul(input.y()).add(right.mul(input.x())).mul(5f));
 * if (jumpPressed) player.jump(5f);
 * </pre>
 * The node's origin is at the character's feet, and the capsule stands on it along the node's own +Y axis, so "up" is
 * wherever the node's +Y points: usually the world's, but {@link #setUp} tilts it, for a character walking on a wall, a
 * ceiling or a small planet. Turn the node around that axis to face it any way. The character moves in the world's
 * steps, as bodies do, and its node is placed the same way.
 * <p>
 * Inside, the character is a kinematic {@link #getBody() body}: rays and triggers find it, and objects falling on it
 * land on it. Walking into a loose object pushes the object as hard as the character's {@link #setMass mass} allows,
 * and a loose object that hits the character pushes it back the same way (a thrown crate knocks it aside; a heavy
 * one knocks it further). Beside that, only its own walking and the platforms it stands on move it.
 */
public final class CharacterController {
    /**
     * How far below the feet the ground still counts as under them, in metres.
     */
    private static final float GROUND_PROBE = 0.05f;
    /**
     * Overlap left alone, in metres, so the character doesn't jitter against what it touches.
     */
    private static final float SKIN = 1e-4f;
    private static final int MAX_PUSHES = 8;

    private final RigidBody body;
    private PhysicsWorld world;
    private float minGroundUp = (float) Math.cos(Math.toRadians(45));
    private float slopeLimit = 45f;
    private float stepHeight = 0.3f;
    private float gravityScale = 1f;
    private float mass = 80f;
    private float airAcceleration = Float.POSITIVE_INFINITY;

    private static final float KNOCK_DECAY_GROUND = 8f, KNOCK_DECAY_AIR = 0.6f;

    private Vec3 up = Vec3.UP;
    private Vec3 walk = Vec3.ZERO;
    private Vec3 knock = Vec3.ZERO;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 inherited = Vec3.ZERO;
    private Vec3 actualVelocity = Vec3.ZERO;
    private boolean grounded;
    private Vec3 groundNormal = Vec3.UP;
    private Vec3 groundPoint = Vec3.ZERO;
    private RigidBody groundBody;
    private boolean wall, ceiling;
    private final Manifold manifold = new Manifold();

    /**
     * A character as tall as {@code height} and as wide as twice {@code radius}, in the node's units like any shape,
     * moving {@code node} (which may be null).
     */
    public CharacterController(Node3D node, float radius, float height) {
        if (!(height >= 2f * radius)) throw new IllegalArgumentException("A character " + height + " tall can't be " + 2 * radius + " wide");
        body = new RigidBody(node, BodyType.KINEMATIC)
                .addShape(CapsuleShape.ofHeight(radius, height), new Vec3(0f, height * 0.5f, 0f));
        body.character = this;
    }

    /**
     * The kinematic body the character moves: for its collision layer and mask, a contact listener, or user data.
     */
    public RigidBody getBody() {
        return body;
    }

    public Node3D getNode() {
        return body.getNode();
    }

    /**
     * The world the character is in, or null.
     */
    public PhysicsWorld getWorld() {
        return world;
    }

    // ---- Control --------------------------------------------------------------------------------------------------

    public Vec3 getWalkVelocity() {
        return walk;
    }

    /**
     * How fast and which way the character should walk, in metres per second; only the part across {@link #getUp up}
     * counts (X and Z, for the usual up). It keeps walking that way until told otherwise, so set it every frame from
     * the input, and to zero to stop.
     */
    public CharacterController setWalkVelocity(Vec3 velocity) {
        walk = velocity == null ? Vec3.ZERO : across(velocity);
        return this;
    }

    /**
     * Which way is up for the character: the node's +Y axis in the world, as of now.
     */
    public Vec3 getUp() {
        return up;
    }

    /**
     * Turns the character to stand along {@code direction}, by the shortest turn from where it stands, so the way it
     * faces about its own axis stays. Gravity pulls it along the opposite of up only as far as the world's gravity points
     * that way, so for a wall or a planet set the world's gravity, or the character's {@link #setGravityScale scale}, to
     * match (and call this again as the ground curves away).
     */
    public CharacterController setUp(Vec3 direction) {
        if (direction.lengthSquared() < 1e-12f) throw new IllegalArgumentException("The up direction has no direction");
        Vec3 to = direction.normalized();
        Vec3 from = body.getRotation().rotate(Vec3.UP);
        Vec3 axis = from.cross(to);
        float sin = axis.length(), cos = from.dot(to);
        if (sin < 1e-6f && cos > 0f) return this;
        if (sin < 1e-6f) axis = perpendicular(from); // upside down: any turn of a half turn will do
        else axis = axis.mul(1f / sin);
        body.setRotation(Quat.fromAxisAngle(axis, (float) Math.atan2(sin, cos)).mul(body.getRotation()));
        up = body.getRotation().rotate(Vec3.UP);
        leaveGround();
        return this;
    }

    private static Vec3 perpendicular(Vec3 v) {
        return (Math.abs(v.x()) < 0.6f ? Vec3.X : Vec3.UP).cross(v).normalized();
    }

    /**
     * {@code v} without its part along up.
     */
    private Vec3 across(Vec3 v) {
        return v.sub(up.mul(v.dot(up)));
    }

    /**
     * Reads which way up is from the body's turn, exactly the world's up when the body is only turned around it.
     */
    private void readUp() {
        Vec3 u = body.getRotation().rotate(Vec3.UP);
        up = Math.abs(u.x()) < 1e-6f && Math.abs(u.z()) < 1e-6f && u.y() > 0f ? Vec3.UP : u.normalized();
    }

    /**
     * Jumps at {@code speed} metres per second upwards, if the character is on the ground. 5 m/s rises about 1.3 m.
     *
     * @return whether it jumped
     */
    public boolean jump(float speed) {
        if (!grounded) return false;
        leaveGround();
        velocity = across(velocity).add(up.mul(speed + Math.max(0f, inherited.dot(up))));
        inherited = across(inherited);
        return true;
    }

    /**
     * The character's velocity in the last step, in metres per second, from where it went: walking, falling and being
     * carried.
     */
    public Vec3 getVelocity() {
        return actualVelocity;
    }

    /**
     * Sets the velocity the character carries on with, such as a knock back. On the ground, walking takes over again
     * at once; in the air it carries on until it lands, unless the walk velocity steers it (see
     * {@link #setAirAcceleration}).
     */
    public CharacterController setVelocity(Vec3 velocity) {
        this.velocity = velocity;
        inherited = Vec3.ZERO;
        if (velocity.dot(up) > 0f) leaveGround();
        return this;
    }

    /**
     * Where the character's feet are.
     */
    public Vec3 getPosition() {
        return body.getPosition();
    }

    /**
     * Moves the character's feet to {@code position} at once, through anything in between.
     */
    public CharacterController setPosition(Vec3 position) {
        body.setPosition(position);
        leaveGround();
        return this;
    }

    // ---- State ----------------------------------------------------------------------------------------------------

    /**
     * Whether the character stood on walkable ground after the last step.
     */
    public boolean isGrounded() {
        return grounded;
    }

    /**
     * Which way the ground under the character faces, or straight up when it isn't on the ground.
     */
    public Vec3 getGroundNormal() {
        return grounded ? groundNormal : up;
    }

    /**
     * The body the character stands on, or null when it isn't on the ground.
     */
    public RigidBody getGroundBody() {
        return grounded ? groundBody : null;
    }

    /**
     * Whether the character walked into something too steep to climb in the last step.
     */
    public boolean isTouchingWall() {
        return wall;
    }

    /**
     * Whether the character's head hit something in the last step.
     */
    public boolean isTouchingCeiling() {
        return ceiling;
    }

    // ---- Settings -------------------------------------------------------------------------------------------------

    public float getSlopeLimit() {
        return slopeLimit;
    }

    /**
     * The steepest slope the character walks up, in degrees (45 by default). Steeper ground is a wall it slides down.
     */
    public CharacterController setSlopeLimit(float degrees) {
        slopeLimit = Math.clamp(degrees, 0f, 89f);
        minGroundUp = (float) Math.cos(Math.toRadians(slopeLimit));
        return this;
    }

    public float getStepHeight() {
        return stepHeight;
    }

    /**
     * The tallest step the character walks up, in metres (0.3 by default), and the furthest it drops to stay on the
     * ground going down steps and slopes.
     */
    public CharacterController setStepHeight(float metres) {
        stepHeight = Math.max(0f, metres);
        return this;
    }

    public float getGravityScale() {
        return gravityScale;
    }

    /**
     * How much of the world's gravity pulls the character (1 by default).
     */
    public CharacterController setGravityScale(float scale) {
        gravityScale = scale;
        return this;
    }

    public float getMass() {
        return mass;
    }

    /**
     * How heavy the character is when it pushes things and stands on them, in kilograms (80 by default). A 20 kg crate
     * moves away almost as fast as an 80 kg character walks into it; a 1000 kg one barely moves. 0 pushes nothing.
     */
    public CharacterController setMass(float kilograms) {
        mass = Math.max(0f, kilograms);
        return this;
    }

    public float getAirAcceleration() {
        return airAcceleration;
    }

    /**
     * How quickly the walk velocity steers the character in the air, in metres per second squared. Infinite by default:
     * it steers in the air as on the ground. 0 keeps the velocity it jumped or fell with. With no walk velocity it keeps
     * going in the air as it was, whatever this is.
     */
    public CharacterController setAirAcceleration(float metresPerSecondSquared) {
        airAcceleration = Math.max(0f, metresPerSecondSquared);
        return this;
    }

    // ---- The move -------------------------------------------------------------------------------------------------

    void attach(PhysicsWorld world) {
        this.world = world;
    }

    void detach() {
        world = null;
        body.setVelocity(Vec3.ZERO);
    }

    private void leaveGround() {
        if (grounded && groundBody != null && groundBody.getType() != BodyType.STATIC) {
            inherited = groundBody.getPointVelocity(groundPoint); // keep the platform's speed on leaving it
        }
        grounded = false;
        groundBody = null;
    }

    /**
     * Works out where the character goes this step and sets its body's velocity to take it there.
     */
    void step(float dt) {
        Vec3 start = body.getPosition();
        readUp();
        // Gravity's pull along the way down: negative, as for the usual up.
        float gravity = world.getGravity().dot(up) * gravityScale;
        takeKnocks(dt);

        // Velocity: walking sets it on the ground. In the air the walk steers it, and without one it keeps going as it
        // was (a jump carries on, a slide down a steep slope speeds up).
        Vec3 flatWalk = across(walk);
        Vec3 horizontal = across(velocity);
        if (grounded || (airAcceleration == Float.POSITIVE_INFINITY && !flatWalk.equals(Vec3.ZERO))) {
            horizontal = flatWalk;
        } else if (!flatWalk.equals(Vec3.ZERO)) {
            Vec3 change = flatWalk.sub(horizontal);
            float most = airAcceleration * dt, length = change.length();
            horizontal = horizontal.add(length > most ? change.mul(most / length) : change);
        }
        float vy = grounded ? 0f : velocity.dot(up) + gravity * dt;
        velocity = horizontal.add(up.mul(vy));
        boolean rising = vy > 0f;
        Vec3 carry = grounded && groundBody != null && groundBody.getType() != BodyType.STATIC ? groundBody.getPointVelocity(groundPoint).mul(dt) : Vec3.ZERO;
        Vec3 drift = (grounded ? Vec3.ZERO : across(inherited).mul(dt)).add(knock.mul(dt));

        Vec3 move = velocity.mul(dt).add(drift);
        if (grounded) {
            // Along the slope, at the walking speed across it.
            Vec3 n = groundNormal;
            Vec3 level = across(move);
            move = level.add(up.mul(-level.dot(n) / n.dot(up)));
        }

        // Moved in the frame of what it stands on: the ground isn't an obstacle to being carried along with it.
        Move result = slide(start, move, velocity, null, grounded);
        if (grounded && result.wall && stepHeight > 0f) {
            Move stepped = stepUp(start, move, velocity);
            if (stepped != null && horizontalDistance(stepped.position, start) > horizontalDistance(result.position, start) + 1e-4f) result = stepped;
        }
        if (!carry.equals(Vec3.ZERO)) {
            Move carried = slide(result.position, carry, result.velocity, groundBody, false);
            carried.wall |= result.wall;
            carried.ceiling |= result.ceiling;
            carried.pushes.addAll(0, result.pushes);
            result = carried;
        }
        Vec3 end = result.position;
        velocity = result.velocity;
        wall = result.wall;
        ceiling = result.ceiling;

        // Ground: under the feet now, or close enough below to step down to when it was on the ground.
        boolean wasGrounded = grounded;
        Ground ground = rising ? null : findGround(end);
        if (ground == null && wasGrounded && !rising && stepHeight > 0f) {
            Vec3 dropped = drop(end, stepHeight);
            if (dropped != null) {
                end = dropped;
                ground = findGround(end);
            }
        }
        if (ground != null && ground.gap > SKIN) {
            // Settle onto it, so standing doesn't float a little above the ground.
            end = end.sub(up.mul(Math.min(ground.gap / Math.max(ground.contactUp, 0.3f), 2f * GROUND_PROBE)));
        }
        if (ground != null) {
            if (!wasGrounded) inherited = Vec3.ZERO;
            grounded = true;
            groundNormal = ground.normal;
            groundPoint = ground.point;
            groundBody = ground.body;
            velocity = across(velocity);
        } else if (wasGrounded) {
            leaveGround();
        }

        push(result.pushes);
        if (grounded && groundBody != null && groundBody.isDynamic() && gravity < 0f) {
            // Its weight on what it stands on: a plank sags, a seesaw tips.
            groundBody.applyImpulseAtPoint(up.mul(mass * gravity * dt), groundPoint);
        }

        actualVelocity = end.sub(start).mul(1f / dt);
        body.setVelocity(actualVelocity);
        body.setAngularVelocity(Vec3.ZERO);
    }

    /**
     * Shoves the loose bodies the character walked into, as an inelastic knock between the character's mass and
     * theirs, sideways only so walking into a crate doesn't press it into the floor.
     */
    private void push(List<Push> pushes) {
        if (mass <= 0f) return;
        List<RigidBody> done = new ArrayList<>(pushes.size());
        for (Push p : pushes) {
            if (done.contains(p.body())) continue;
            Vec3 into = across(p.normal().negate());
            float across = into.length();
            if (across < 0.1f) continue; // landed on it or hit it from below: its weight or the solver deals with that
            into = into.mul(1f / across);
            float speed = p.velocity().dot(into) - p.body().getPointVelocity(p.point()).dot(into);
            if (speed <= 0f) continue;
            done.add(p.body());
            float m = p.body().getMass();
            p.body().applyImpulseAtPoint(into.mul(speed * mass * m / (mass + m)), p.point());
        }
    }

    private float horizontalDistance(Vec3 a, Vec3 b) {
        return across(a.sub(b)).length();
    }

    /**
     * Takes the knocks from loose bodies that hit the character: the push the solver gave the kinematic body (which
     * took it without moving) is the push the character would have taken if it had been loose, so the character's speed
     * changes by that over its {@link #setMass mass}. Sideways only; being landed on or stood under is not a knock. The
     * speed it gains fades, quickly on the ground and slowly in the air.
     */
    private void takeKnocks(float dt) {
        Vec3 gained = Vec3.ZERO;
        if (mass > 0f) {
            for (Contact c : world.getContacts()) {
                boolean first = c.getBodyA() == body;
                if (!first && c.getBodyB() != body) continue;
                RigidBody other = first ? c.getBodyB() : c.getBodyA();
                if (!other.isDynamic() || other.isTrigger() || !other.isAwakeDynamic()) continue;
                float total = c.getImpulse();
                if (total <= 0f) continue;
                // The impulse is along the normal onto B; the character, if it is A, is pushed back along it.
                Vec3 push = across(c.normal.mul(first ? -total : total));
                gained = gained.add(push.mul(1f / mass));
            }
        }
        float decay = Math.max(0f, 1f - (grounded ? KNOCK_DECAY_GROUND : KNOCK_DECAY_AIR) * dt);
        knock = across(knock).mul(decay).add(gained);
        if (knock.lengthSquared() < 1e-6f) knock = Vec3.ZERO;
    }

    /**
     * Where a move ended, the velocity left once what it ran into was taken off, and what it hit.
     */
    private static final class Move {
        Vec3 position;
        Vec3 velocity;
        boolean wall, ceiling;
        final List<Push> pushes = new ArrayList<>(0);
    }

    /**
     * A loose body the character walked into: where, which way it was pushed out of it, and how fast it was going.
     */
    private record Push(RigidBody body, Vec3 point, Vec3 normal, Vec3 velocity) {
    }

    /**
     * Ground under the feet: which way its surface faces, where it touches, how far below it is along the contact
     * normal, and how upright that normal is (less than the surface's on an edge).
     */
    private record Ground(Vec3 normal, Vec3 point, RigidBody body, float gap, float contactUp) {
    }

    /**
     * The deepest overlap found by {@link #deepest}.
     */
    private static final class Overlap {
        float depth;
        Vec3 normal; // out of the obstacle, toward the character
        Vec3 point;
        RigidBody body;
    }

    /**
     * Moves from {@code from} by {@code move} in pieces no longer than half the radius, so it can't pass through
     * anything, pushing out of whatever it runs into and sliding along it.
     */
    private Move slide(Vec3 from, Vec3 move, Vec3 velocity, RigidBody ignore, boolean walking) {
        Move result = new Move();
        result.position = from;
        result.velocity = velocity;
        CapsuleShape capsule = capsule();
        float length = move.length();
        int pieces = Math.clamp((int) Math.ceil(length / (capsule.radius() * 0.5f)), 1, 64);
        Vec3 piece = move.mul(1f / pieces);
        for (int i = 0; i < pieces; i++) {
            result.position = result.position.add(piece);
            piece = resolve(result, piece, ignore, walking);
        }
        return result;
    }

    /**
     * Pushes the character out of everything it overlaps at {@code move.position}, deepest first, and takes what it
     * pushed against off the velocity and the rest of the move. {@code walking} lifts it straight up onto walkable
     * ground and pushes it straight sideways out of ground too steep to climb, so walking into a steep slope doesn't
     * carry it up; falling, it slides down along it.
     *
     * @return what is left of {@code piece}
     */
    private Vec3 resolve(Move move, Vec3 piece, RigidBody ignore, boolean walking) {
        Overlap overlap = new Overlap();
        for (int i = 0; i < MAX_PUSHES; i++) {
            if (!deepest(move.position, 0f, ignore, overlap) || overlap.depth <= SKIN) break;
            Vec3 n = overlap.normal;
            Vec3 out = n;
            float distance = overlap.depth;
            float upward = n.dot(up);
            if (upward >= minGroundUp) {
                if (walking) { // lifted straight up onto walkable ground, keeping the walking speed across it
                    out = up;
                    distance = overlap.depth / upward;
                }
            } else if (upward < -0.3f) {
                move.ceiling = true;
            } else {
                move.wall = true;
                Vec3 side = across(n);
                float sideways = side.length();
                if (walking && upward > 0f && sideways > 1e-3f) {
                    out = side.mul(1f / sideways);
                    distance = overlap.depth / sideways;
                }
            }
            move.position = move.position.add(out.mul(distance - SKIN * 0.5f));
            if (overlap.body.isDynamic() && !overlap.body.isTrigger()) move.pushes.add(new Push(overlap.body, overlap.point, out, move.velocity));
            float into = move.velocity.dot(out);
            if (into < 0f) move.velocity = move.velocity.sub(out.mul(into));
            float left = piece.dot(out);
            if (left < 0f) piece = piece.sub(out.mul(left));
        }
        return piece;
    }

    /**
     * Finds the deepest overlap of the character standing at {@code feet} with anything solid, counting shapes up to
     * {@code margin} apart.
     *
     * @return whether anything was found
     */
    private boolean deepest(Vec3 feet, float margin, RigidBody ignore, Overlap out) {
        CapsuleShape capsule = capsule();
        Pose pose = new Pose(feet.add(up.mul(centreHeight())), body.getRotation());
        var box = RigidBody.worldBounds(capsule, pose, margin + 0.01f);
        boolean found = false;
        out.depth = -Float.MAX_VALUE;
        for (RigidBody other : world.near(box)) {
            if (other == body || other == ignore || other.isTrigger() || !blocks(other)) continue;
            if (!Broadphase.overlap(box, other.collisionBounds())) continue;
            for (int i = 0; i < other.shapeCount(); i++) {
                if (!Broadphase.overlap(box, other.shapeBounds(i))) continue;
                Shape shape = other.collisionShape(i);
                if (shape instanceof MeshShape mesh) {
                    boolean[] hit = {false};
                    MeshCollision.collide(mesh, other.shapePose(i), capsule, pose, margin, false, (_, m) -> {
                        hit[0] |= keepDeepest(m, other, out);
                        return true;
                    });
                    found |= hit[0];
                } else if (Narrowphase.collide(capsule, pose, shape, other.shapePose(i), margin, manifold)) {
                    found |= keepDeepest(manifold, other, out);
                }
            }
        }
        return found;
    }

    private static boolean keepDeepest(Manifold m, RigidBody other, Overlap out) {
        boolean kept = false;
        for (int k = 0; k < m.count; k++) {
            if (m.depths[k] <= out.depth) continue;
            out.depth = m.depths[k];
            out.normal = m.normal.negate();
            out.point = m.points[k];
            out.body = other;
            kept = true;
        }
        return kept;
    }

    private boolean blocks(RigidBody other) {
        return (body.getCollisionLayer() & other.getCollisionMask()) != 0 && (other.getCollisionLayer() & body.getCollisionMask()) != 0;
    }

    /**
     * The walkable ground just under the character at {@code feet}, or null.
     */
    private Ground findGround(Vec3 feet) {
        CapsuleShape capsule = capsule();
        Pose pose = new Pose(feet.add(up.mul(centreHeight())), body.getRotation());
        var box = RigidBody.worldBounds(capsule, pose, GROUND_PROBE + 0.01f);
        Ground best = null;
        for (RigidBody other : world.near(box)) {
            if (other == body || other.isTrigger() || !blocks(other)) continue;
            if (!Broadphase.overlap(box, other.collisionBounds())) continue;
            for (int i = 0; i < other.shapeCount(); i++) {
                if (!Broadphase.overlap(box, other.shapeBounds(i))) continue;
                Shape shape = other.collisionShape(i);
                List<Ground> found = new ArrayList<>(1);
                if (shape instanceof MeshShape mesh) {
                    MeshCollision.collide(mesh, other.shapePose(i), capsule, pose, GROUND_PROBE, false, (_, m) -> {
                        Ground g = groundIn(m, other, feet);
                        if (g != null) found.add(g);
                        return true;
                    });
                } else if (Narrowphase.collide(capsule, pose, shape, other.shapePose(i), GROUND_PROBE, manifold)) {
                    Ground g = groundIn(manifold, other, feet);
                    if (g != null) found.add(g);
                }
                for (Ground g : found) if (best == null || g.normal.dot(up) > best.normal.dot(up)) best = g;
            }
        }
        return best;
    }

    /**
     * The ground in a contact: under the rounded bottom of the capsule, and walkable. The bottom resting on the edge
     * of a step or a ledge touches it with a slanted normal, though the surface it stands on is flat, so a slanted
     * contact counts when the surface just past it, away from the capsule, is walkable.
     */
    private Ground groundIn(Manifold m, RigidBody other, Vec3 feet) {
        Vec3 n = m.normal.negate();
        float upward = n.dot(up);
        if (m.count == 0 || upward < 0.05f) return null;
        int deepest = 0;
        for (int k = 1; k < m.count; k++) if (m.depths[k] > m.depths[deepest]) deepest = k;
        Vec3 point = m.points[deepest];
        if (point.sub(feet).dot(up) > capsule().radius()) return null;
        Vec3 surface = n;
        if (upward < minGroundUp) {
            Vec3 away = across(point.sub(feet));
            float length = away.length();
            if (length < 1e-4f) return null;
            Vec3 from = point.add(away.mul(0.02f / length)).add(up.mul(0.05f));
            RaycastHit below = surfaceBelow(from, 0.1f);
            if (below == null || below.normal().dot(up) < minGroundUp) return null;
            surface = below.normal();
        }
        return new Ground(surface, point, other, Math.max(0f, -m.depths[deepest]), upward);
    }

    /**
     * The first solid surface straight below {@code from} within {@code distance}, not counting the character.
     */
    private RaycastHit surfaceBelow(Vec3 from, float distance) {
        for (RaycastHit hit : world.raycastAll(new Ray(from, up.negate()), distance, -1)) {
            if (hit.body() != body && !hit.body().isTrigger() && blocks(hit.body())) return hit;
        }
        return null;
    }

    /**
     * Lifts the character by the step height, moves it across, and lowers it onto what it stepped up on.
     *
     * @return the move, or null when there is nothing walkable to step onto
     */
    private Move stepUp(Vec3 start, Vec3 move, Vec3 velocity) {
        Move lift = slide(start, up.mul(stepHeight), Vec3.ZERO, null, false);
        float lifted = lift.position.sub(start).dot(up);
        if (lifted < 1e-3f) return null;
        Move over = slide(lift.position, across(move), velocity, null, true);
        Vec3 down = drop(over.position, lifted);
        if (down == null) return null;
        Ground ground = findGround(down);
        if (ground == null || ground.point.sub(start).dot(up) > stepHeight + 0.01f) return null; // too tall a step
        over.position = down;
        over.wall = false;
        over.velocity = across(over.velocity);
        return over;
    }

    /**
     * Lowers the character from {@code from} by up to {@code distance} until it stands on walkable ground.
     *
     * @return where it landed, or null if there's no ground within reach
     */
    private Vec3 drop(Vec3 from, float distance) {
        Move move = new Move();
        move.position = from;
        move.velocity = up.negate();
        float left = distance;
        while (true) {
            if (findGround(move.position) != null) return move.position;
            if (left <= 0f) return null;
            float d = Math.min(GROUND_PROBE, left);
            left -= d;
            move.position = move.position.sub(up.mul(d));
            resolve(move, up.mul(-d), null, false);
        }
    }

    private CapsuleShape capsule() {
        return (CapsuleShape) body.collisionShape(0);
    }

    /**
     * How far above the feet the middle of the capsule is, in metres.
     */
    private float centreHeight() {
        return body.getWorldScaledShapes().getFirst().offset().y();
    }
}
