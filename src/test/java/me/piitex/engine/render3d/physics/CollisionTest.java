package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CollisionTest {
    private static final float M = PhysicsWorld.CONTACT_MARGIN;
    private static final float PI = (float) Math.PI;

    private static Pose at(float x, float y, float z) {
        return new Pose(new Vec3(x, y, z), Quat.IDENTITY);
    }

    private static Pose at(float x, float y, float z, Quat rotation) {
        return new Pose(new Vec3(x, y, z), rotation);
    }

    private static Manifold collide(Shape a, Pose pa, Shape b, Pose pb) {
        Manifold m = new Manifold();
        Narrowphase.collide(a, pa, b, pb, M, m);
        return m;
    }

    private static void assertVec(Vec3 expected, Vec3 actual, float tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance, "x of " + actual);
        assertEquals(expected.y(), actual.y(), tolerance, "y of " + actual);
        assertEquals(expected.z(), actual.z(), tolerance, "z of " + actual);
    }

    private static float maxDepth(Manifold m) {
        float d = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < m.count; i++) d = Math.max(d, m.depths[i]);
        return d;
    }

    private static final SphereShape BALL = new SphereShape(0.5f);
    private static final BoxShape CUBE = new BoxShape(0.5f, 0.5f, 0.5f);
    private static final CapsuleShape CAPSULE = new CapsuleShape(0.25f, 0.5f);
    private static final PlaneShape FLOOR = new PlaneShape();

    // ---- Spheres ----

    @Test
    void sphereSphere() {
        Manifold m = collide(BALL, at(0, 0, 0), BALL, at(0.9f, 0, 0));
        assertEquals(1, m.count);
        assertVec(Vec3.X, m.normal, 1e-6f);
        assertEquals(0.1f, m.depths[0], 1e-5f);
        assertVec(new Vec3(0.45f, 0, 0), m.points[0], 1e-5f);

        assertEquals(0, collide(BALL, at(0, 0, 0), BALL, at(1.1f, 0, 0)).count);
        Manifold near = collide(BALL, at(0, 0, 0), BALL, at(1.01f, 0, 0));
        assertEquals(1, near.count, "inside the margin");
        assertEquals(-0.01f, near.depths[0], 1e-5f);
        assertEquals(1, collide(BALL, at(0, 0, 0), BALL, at(0, 0, 0)).count, "exactly on top of each other still pushes apart");
    }

    @Test
    void sphereBoxFaceEdgeCornerAndInside() {
        Manifold face = collide(BALL, at(0, 0.9f, 0), CUBE, at(0, 0, 0));
        assertVec(new Vec3(0, -1, 0), face.normal, 1e-5f);
        assertEquals(0.1f, face.depths[0], 1e-5f);

        float s = 0.5f + 0.4f / (float) Math.sqrt(2);
        Manifold edge = collide(BALL, at(s, s, 0), CUBE, at(0, 0, 0));
        assertVec(new Vec3(-1, -1, 0).normalized(), edge.normal, 1e-5f);
        assertEquals(0.1f, edge.depths[0], 1e-5f);

        float c = 0.5f + 0.45f / (float) Math.sqrt(3);
        Manifold corner = collide(BALL, at(c, c, c), CUBE, at(0, 0, 0));
        assertVec(new Vec3(-1, -1, -1).normalized(), corner.normal, 1e-5f);
        assertEquals(0.05f, corner.depths[0], 1e-5f);

        Manifold inside = collide(BALL, at(0, 0.3f, 0.1f), CUBE, at(0, 0, 0));
        assertVec(new Vec3(0, -1, 0), inside.normal, 1e-5f);
        assertEquals(0.7f, inside.depths[0], 1e-5f);

        Manifold turned = collide(BALL, at(0, 0.9f, 0), CUBE, at(0, 0, 0, Quat.fromAxisAngle(Vec3.UP, 0.7f)));
        assertVec(new Vec3(0, -1, 0), turned.normal, 1e-5f);
        assertEquals(0, collide(BALL, at(0, 1.1f, 0), CUBE, at(0, 0, 0)).count);
    }

    @Test
    void spherePlane() {
        Manifold m = collide(BALL, at(3, 0.4f, -2), FLOOR, at(0, 0, 0));
        assertVec(new Vec3(0, -1, 0), m.normal, 1e-6f);
        assertEquals(0.1f, m.depths[0], 1e-5f);
        assertVec(new Vec3(3, -0.05f, -2), m.points[0], 1e-5f);

        Pose tilted = at(0, 0, 0, Quat.fromAxisAngle(Vec3.Z, PI / 4));
        Vec3 n = new Vec3(-1, 1, 0).normalized();
        Manifold t = collide(BALL, new Pose(n.mul(0.45f), Quat.IDENTITY), FLOOR, tilted);
        assertVec(n.negate(), t.normal, 1e-5f);
        assertEquals(0.05f, t.depths[0], 1e-5f);
        assertEquals(0, collide(BALL, at(0, 0.6f, 0), FLOOR, at(0, 0, 0)).count);
    }

    @Test
    void sphereCapsuleSideAndEnd() {
        Manifold side = collide(BALL, at(0.7f, 0.2f, 0), CAPSULE, at(0, 0, 0));
        assertVec(new Vec3(-1, 0, 0), side.normal, 1e-5f);
        assertEquals(0.05f, side.depths[0], 1e-5f);
        Manifold end = collide(BALL, at(0, 1.2f, 0), CAPSULE, at(0, 0, 0));
        assertVec(new Vec3(0, -1, 0), end.normal, 1e-5f);
        assertEquals(0.05f, end.depths[0], 1e-5f);
    }

    // ---- Capsules ----

    @Test
    void capsuleCapsuleCrossingAndParallel() {
        Quat lying = Quat.fromAxisAngle(Vec3.Z, PI / 2);
        Manifold cross = collide(CAPSULE, at(0, 0, 0, lying), CAPSULE, at(0, 0.45f, 0, Quat.fromAxisAngle(Vec3.X, PI / 2)));
        assertEquals(1, cross.count);
        assertVec(Vec3.UP, cross.normal, 1e-5f);
        assertEquals(0.05f, cross.depths[0], 1e-5f);

        Manifold parallel = collide(CAPSULE, at(0, 0, 0, lying), CAPSULE, at(0.3f, 0.45f, 0, lying));
        assertEquals(2, parallel.count, "side by side touches at both ends of the overlap");
        for (int i = 0; i < 2; i++) assertEquals(0.05f, parallel.depths[i], 1e-5f);
        assertVec(Vec3.UP, parallel.normal, 1e-5f);
    }

    @Test
    void capsulePlaneStandingAndLying() {
        Manifold standing = collide(CAPSULE, at(0, 0.7f, 0), FLOOR, at(0, 0, 0));
        assertEquals(1, standing.count);
        assertEquals(0.05f, standing.depths[0], 1e-5f);
        Manifold lying = collide(CAPSULE, at(0, 0.2f, 0, Quat.fromAxisAngle(Vec3.X, PI / 2)), FLOOR, at(0, 0, 0));
        assertEquals(2, lying.count);
        assertVec(new Vec3(0, -1, 0), lying.normal, 1e-6f);
    }

    @Test
    void capsuleBox() {
        Manifold standing = collide(CAPSULE, at(0.1f, 1.2f, 0), CUBE, at(0, 0, 0));
        assertEquals(1, standing.count);
        assertVec(new Vec3(0, -1, 0), standing.normal, 1e-4f);
        assertEquals(0.05f, standing.depths[0], 1e-4f);

        Manifold lying = collide(CAPSULE, at(0, 0.7f, 0, Quat.fromAxisAngle(Vec3.Z, PI / 2)), CUBE, at(0, 0, 0));
        assertEquals(2, lying.count, "lying flat on top touches at both ends");
        assertVec(new Vec3(0, -1, 0), lying.normal, 1e-4f);
        for (int i = 0; i < 2; i++) assertEquals(0.05f, lying.depths[i], 1e-4f);
        Set<Float> xs = new HashSet<>();
        for (int i = 0; i < 2; i++) xs.add(Math.round(lying.points[i].x() * 1000) / 1000f);
        assertEquals(Set.of(-0.5f, 0.5f), xs, "the segment is cut where the face ends");

        // Across a top edge of a box turned 45 degrees: one point, pushing straight up.
        Pose diamond = at(0, 0, 0, Quat.fromAxisAngle(Vec3.X, PI / 4));
        float top = 0.5f * (float) Math.sqrt(2);
        Manifold edge = collide(CAPSULE, at(0, top + 0.2f, 0, Quat.fromAxisAngle(Vec3.Z, PI / 2)), CUBE, diamond);
        assertTrue(edge.count >= 1);
        assertVec(new Vec3(0, -1, 0), edge.normal, 1e-3f);
        assertEquals(0.05f, maxDepth(edge), 1e-3f);

        Manifold inside = collide(CAPSULE, at(0, 0, 0), CUBE, at(0, 0, 0));
        assertTrue(inside.count >= 1 && maxDepth(inside) > 0.5f);
        assertEquals(0, collide(CAPSULE, at(0, 1.4f, 0), CUBE, at(0, 0, 0)).count);
    }

    // ---- Boxes ----

    @Test
    void boxPlane() {
        Manifold flat = collide(CUBE, at(0, 0.49f, 0), FLOOR, at(0, 0, 0));
        assertEquals(4, flat.count);
        for (int i = 0; i < 4; i++) assertEquals(0.01f, flat.depths[i], 1e-5f);
        assertVec(new Vec3(0, -1, 0), flat.normal, 1e-6f);

        Manifold onEdge = collide(CUBE, at(0, 0.7f, 0, Quat.fromAxisAngle(Vec3.Z, PI / 4)), FLOOR, at(0, 0, 0));
        assertEquals(2, onEdge.count);
        assertEquals(0.5f * (float) Math.sqrt(2) - 0.7f, onEdge.depths[0], 1e-5f);
    }

    @Test
    void boxRestingOnBox() {
        Manifold m = collide(CUBE, at(0, 0, 0), CUBE, at(0.2f, 0.98f, 0.1f));
        assertEquals(4, m.count);
        assertVec(Vec3.UP, m.normal, 1e-5f);
        for (int i = 0; i < 4; i++) {
            assertEquals(0.02f, m.depths[i], 1e-5f);
            assertEquals(0.49f, m.points[i].y(), 1e-5f);
        }

        BoxShape small = new BoxShape(0.2f, 0.2f, 0.2f);
        Manifold smallOnTop = collide(CUBE, at(0, 0, 0), small, at(0.1f, 0.69f, 0));
        assertEquals(4, smallOnTop.count);
        for (int i = 0; i < 4; i++) {
            assertEquals(0.01f, smallOnTop.depths[i], 1e-5f);
            assertTrue(Math.abs(smallOnTop.points[i].x() - 0.1f) <= 0.2f + 1e-5f, "corners of the small box");
        }
        // The big box on top of the small one: the face of the lower box is the one clipped against.
        Manifold bigOnTop = collide(small, at(0, 0, 0), CUBE, at(0, 0.69f, 0));
        assertEquals(4, bigOnTop.count);
        assertVec(Vec3.UP, bigOnTop.normal, 1e-5f);
    }

    @Test
    void boxTurnedOnBoxKeepsFourPoints() {
        Manifold m = collide(CUBE, at(0, 0, 0), CUBE, at(0, 0.99f, 0, Quat.fromAxisAngle(Vec3.UP, PI / 4)));
        assertEquals(4, m.count, "eight crossing points cut down to four");
        assertVec(Vec3.UP, m.normal, 1e-5f);
        for (int i = 0; i < 4; i++) assertEquals(0.01f, m.depths[i], 1e-5f);
    }

    @Test
    void boxEdgeOnEdge() {
        float r2 = (float) Math.sqrt(2);
        Pose lower = at(0, 0, 0, Quat.fromAxisAngle(Vec3.Z, PI / 4));
        Pose upper = at(0, r2 - 0.02f, 0, Quat.fromAxisAngle(Vec3.X, PI / 4));
        Manifold m = collide(CUBE, lower, CUBE, upper);
        assertEquals(1, m.count);
        assertVec(Vec3.UP, m.normal, 1e-4f);
        assertEquals(0.02f, m.depths[0], 1e-4f);
        assertVec(new Vec3(0, r2 / 2 - 0.01f, 0), m.points[0], 1e-4f);
    }

    @Test
    void separatedBoxesHaveNoContact() {
        assertEquals(0, collide(CUBE, at(0, 0, 0), CUBE, at(1.05f, 0, 0)).count);
        // Apart only along an edge axis: no face axis separates them.
        float r2 = (float) Math.sqrt(2);
        assertEquals(0, collide(CUBE, at(0, 0, 0, Quat.fromAxisAngle(Vec3.Z, PI / 4)), CUBE, at(0, r2 + 0.05f, 0, Quat.fromAxisAngle(Vec3.X, PI / 4))).count);
    }

    @Test
    void swappingTheShapesFlipsTheNormal() {
        Shape[] shapes = {BALL, CAPSULE, CUBE, FLOOR};
        Random random = new Random(7);
        for (int trial = 0; trial < 200; trial++) {
            Shape a = shapes[random.nextInt(3)], b = shapes[random.nextInt(4)];
            Pose pa = at(random.nextFloat() * 0.6f - 0.3f, random.nextFloat() * 0.6f + 0.3f, random.nextFloat() * 0.6f - 0.3f,
                    Quat.fromEuler(random.nextFloat() * 6, random.nextFloat() * 6, random.nextFloat() * 6));
            Pose pb = at(0, 0, 0, b instanceof PlaneShape ? Quat.IDENTITY : Quat.fromEuler(random.nextFloat() * 6, random.nextFloat() * 6, 0));
            Manifold ab = collide(a, pa, b, pb), ba = collide(b, pb, a, pa);
            assertEquals(ab.count, ba.count);
            if (ab.count == 0) continue;
            if (a.getClass() == b.getClass()) {
                // Deep in, two of the same shape can tie between axes and break the tie by order; the overlap agrees.
                if (maxDepth(ab) < 0.2f) assertEquals(maxDepth(ab), maxDepth(ba), 2e-3f);
            } else {
                assertVec(ab.normal.negate(), ba.normal, 1e-5f);
            }
            assertEquals(1f, ab.normal.length(), 1e-4f);
            // The normal points from the first shape toward the second.
            Vec3 between = pb.position().sub(pa.position());
            if (!(b instanceof PlaneShape) && between.length() > 0.3f) assertTrue(ab.normal.dot(between) > -1e-3f, a + " " + b + " " + ab.normal);
            assertTrue(ab.count <= Manifold.MAX_POINTS);
        }
    }

    // ---- Broadphase and world ----

    @Test
    void broadphaseFindsExactlyTheOverlappingPairs() {
        PhysicsWorld world = new PhysicsWorld();
        Random random = new Random(3);
        List<RigidBody> bodies = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            BodyType type = i % 5 == 0 ? BodyType.STATIC : BodyType.DYNAMIC;
            Shape shape = i % 2 == 0 ? new SphereShape(0.2f + random.nextFloat()) : new BoxShape(0.2f + random.nextFloat(), 0.3f, 0.2f + random.nextFloat() * 2);
            RigidBody body = new RigidBody(type).addShape(shape).setPosition(random.nextFloat() * 20, random.nextFloat() * 6, random.nextFloat() * 10);
            world.add(body);
            bodies.add(body);
        }
        world.add(new RigidBody(BodyType.STATIC).addShape(FLOOR));
        bodies.add(world.bodies().getLast());
        Broadphase broadphase = new Broadphase();
        for (RigidBody body : bodies) {
            broadphase.add(body);
            body.updateCollisionShapes(M);
        }
        for (int round = 0; round < 3; round++) {
            Set<String> found = new HashSet<>();
            broadphase.findPairs((a, b) -> assertTrue(found.add(a.id + "-" + b.id), "each pair once"));
            Set<String> expected = new HashSet<>();
            for (RigidBody a : bodies) {
                for (RigidBody b : bodies) {
                    if (a.id < b.id && Broadphase.overlap(a.getBounds(), b.getBounds()) && Broadphase.mayCollide(a, b)) expected.add(a.id + "-" + b.id);
                }
            }
            assertEquals(expected, found);
            assertFalse(found.isEmpty());
            for (RigidBody body : bodies) { // shuffle things about and go again
                if (body.isDynamic()) body.setPosition(body.getPosition().add(new Vec3(random.nextFloat() - 0.5f, 0, random.nextFloat() * 3 - 1.5f)));
                body.updateCollisionShapes(M);
            }
        }
    }

    @Test
    void layersAndMasksFilterPairs() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody player1 = new RigidBody(BodyType.DYNAMIC).addShape(BALL).setCollisionLayer(2).setCollisionMask(~2);
        RigidBody player2 = new RigidBody(BodyType.DYNAMIC).addShape(BALL).setPosition(0.5f, 0, 0).setCollisionLayer(2).setCollisionMask(~2);
        RigidBody crate = new RigidBody(BodyType.DYNAMIC).addShape(CUBE).setPosition(0, 0.6f, 0);
        world.add(player1).add(player2).add(crate);
        world.step(1f / 60f);
        for (Contact c : world.getContacts()) {
            assertFalse(c.getBodyA().getCollisionLayer() == 2 && c.getBodyB().getCollisionLayer() == 2, "the players pass through each other");
        }
        assertEquals(2, world.getContacts().size(), "each player still hits the crate");
    }

    @Test
    void worldKeepsContactsBetweenSteps() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody ground = new RigidBody(BodyType.STATIC).addShape(FLOOR);
        RigidBody crate = new RigidBody(BodyType.DYNAMIC).addShape(CUBE).setPosition(0, 0.49f, 0);
        RigidBody away = new RigidBody(BodyType.DYNAMIC).addShape(BALL).setPosition(5, 3, 0);
        world.add(ground).add(crate).add(away);
        world.step(1f / 60f);
        assertEquals(1, world.getContacts().size());
        Contact contact = world.getContacts().getFirst();
        assertSame(ground, contact.getBodyA());
        assertSame(crate, contact.getOther(ground));
        assertVec(Vec3.UP, contact.getNormal(), 1e-6f);
        assertEquals(4, contact.getPoints().size());
        assertTrue(contact.isTouching());
        assertEquals(0.01f, contact.getDepth(), 1e-5f);

        world.step(1f / 60f);
        assertSame(contact, world.getContacts().getFirst(), "the same contact carries on");
        crate.setPosition(0, 2, 0);
        world.step(1f / 60f);
        assertTrue(world.getContacts().isEmpty());
        world.remove(crate);
        assertTrue(world.getContacts().isEmpty());
    }

    @Test
    void compoundBodyGetsAContactPerShape() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        world.add(new RigidBody(BodyType.STATIC).addShape(FLOOR));
        RigidBody table = new RigidBody(BodyType.DYNAMIC)
                .addShape(new BoxShape(1f, 0.05f, 0.5f), new Vec3(0, 1f, 0))
                .addShape(new BoxShape(0.05f, 0.5f, 0.05f), new Vec3(-0.9f, 0.49f, 0))
                .addShape(new BoxShape(0.05f, 0.5f, 0.05f), new Vec3(0.9f, 0.49f, 0));
        world.add(table);
        world.step(1f / 60f);
        assertEquals(2, world.getContacts().size(), "both legs, not the top");
    }

    @Test
    void planesStayOffDynamicBodies() {
        assertThrows(IllegalStateException.class, () -> new RigidBody(BodyType.DYNAMIC).addShape(FLOOR));
        RigidBody ground = new RigidBody(BodyType.STATIC).addShape(FLOOR);
        assertThrows(IllegalStateException.class, () -> ground.setType(BodyType.DYNAMIC));
        assertEquals(BodyType.STATIC, ground.getType());
        assertTrue(Float.isInfinite(FLOOR.bounds().max().x()));
    }
}
