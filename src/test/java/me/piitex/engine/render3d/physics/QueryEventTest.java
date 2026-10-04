package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryEventTest {
    private static final float DT = 1f / 60f;

    private static void run(PhysicsWorld world, float seconds) {
        for (int i = 0; i < Math.round(seconds / DT); i++) world.step(DT);
    }

    private static void assertVec(Vec3 expected, Vec3 actual, float tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance, "x of " + actual);
        assertEquals(expected.y(), actual.y(), tolerance, "y of " + actual);
        assertEquals(expected.z(), actual.z(), tolerance, "z of " + actual);
    }

    private static PhysicsWorld still() {
        return new PhysicsWorld().setGravity(Vec3.ZERO);
    }

    // ---- Raycasts ----

    @Test
    void rayHitsEachShape() {
        PhysicsWorld world = still();
        RigidBody ball = new RigidBody(BodyType.STATIC).addShape(new SphereShape(1f)).setPosition(0, 0, -10);
        RigidBody crate = new RigidBody(BodyType.STATIC).addShape(new BoxShape(1, 2, 1)).setPosition(5, 0, -10);
        RigidBody pill = new RigidBody(BodyType.STATIC).addShape(new CapsuleShape(0.5f, 1f)).setPosition(10, 0, -10);
        RigidBody ground = new RigidBody(BodyType.STATIC).addShape(new PlaneShape()).setPosition(0, -3, 0);
        world.add(ball).add(crate).add(pill).add(ground);

        RaycastHit hit = world.raycast(new Ray(Vec3.ZERO, new Vec3(0, 0, -1)), 100f);
        assertSame(ball, hit.body());
        assertEquals(9f, hit.distance(), 1e-4f);
        assertVec(new Vec3(0, 0, -9), hit.point(), 1e-4f);
        assertVec(Vec3.Z, hit.normal(), 1e-5f);

        RaycastHit side = world.raycast(new Ray(new Vec3(0, 1.5f, -10), Vec3.X), 100f);
        assertSame(crate, side.body());
        assertEquals(4f, side.distance(), 1e-4f);
        assertVec(new Vec3(-1, 0, 0), side.normal(), 1e-5f);

        RaycastHit capsuleSide = world.raycast(new Ray(new Vec3(7, 0.5f, -10), Vec3.X), 100f);
        assertSame(pill, capsuleSide.body());
        assertEquals(2.5f, capsuleSide.distance(), 1e-4f);
        RaycastHit capsuleTop = world.raycast(new Ray(new Vec3(10, 5, -10), new Vec3(0, -1, 0)), 100f);
        assertSame(pill, capsuleTop.body());
        assertEquals(3.5f, capsuleTop.distance(), 1e-4f);
        assertVec(Vec3.UP, capsuleTop.normal(), 1e-5f);

        RaycastHit down = world.raycast(new Ray(new Vec3(20, 0, 0), new Vec3(0, -1, 0)), 100f);
        assertSame(ground, down.body());
        assertEquals(3f, down.distance(), 1e-5f);
        assertNull(world.raycast(new Ray(new Vec3(20, 0, 0), new Vec3(0, -1, 0)), 2f), "too short to reach");
        assertNull(world.raycast(new Ray(new Vec3(20, -5, 0), new Vec3(0, -1, 0)), 100f), "from below the ground");
        assertNull(world.raycast(new Ray(Vec3.ZERO, Vec3.UP), 100f));
    }

    @Test
    void rayAgainstATurnedBox() {
        PhysicsWorld world = still();
        RigidBody diamond = new RigidBody(BodyType.STATIC).addShape(new BoxShape(1, 1, 1))
                .setRotation(Quat.fromAxisAngle(Vec3.Z, (float) Math.PI / 4));
        world.add(diamond);
        RaycastHit hit = world.raycast(new Ray(new Vec3(0, 5, 0), new Vec3(0, -1, 0)), 10f);
        assertEquals(5f - (float) Math.sqrt(2), hit.distance(), 1e-4f);
    }

    @Test
    void rayFromInsideIgnoresThatBody() {
        PhysicsWorld world = still();
        RigidBody player = new RigidBody(BodyType.DYNAMIC).addShape(new CapsuleShape(0.3f, 0.6f));
        RigidBody wall = new RigidBody(BodyType.STATIC).addShape(new BoxShape(0.1f, 2, 2)).setPosition(3, 0, 0);
        world.add(player).add(wall);
        assertSame(wall, world.raycast(new Ray(Vec3.ZERO, Vec3.X), 10f).body());
    }

    @Test
    void raycastAllAndMasks() {
        PhysicsWorld world = still();
        List<RigidBody> row = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RigidBody b = new RigidBody(BodyType.STATIC).addShape(new SphereShape(0.4f)).setPosition(i * 2 + 2, 0, 0).setCollisionLayer(i % 2 == 0 ? 1 : 2);
            row.add(b);
            world.add(b);
        }
        List<RaycastHit> all = world.raycastAll(new Ray(Vec3.ZERO, Vec3.X), 100f, -1);
        assertEquals(row, all.stream().map(RaycastHit::body).toList());
        assertSame(row.get(1), world.raycast(new Ray(Vec3.ZERO, Vec3.X), 100f, 2).body());
        assertEquals(2, world.raycastAll(new Ray(Vec3.ZERO, Vec3.X), 100f, 1).size());
    }

    @Test
    void raySeesBodiesMovedSinceTheLastStep() {
        PhysicsWorld world = still();
        RigidBody target = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.5f));
        world.add(target);
        world.step(DT);
        target.setPosition(0, 0, -5);
        RaycastHit hit = world.raycast(new Ray(Vec3.ZERO, new Vec3(0, 0, -1)), 10f);
        assertEquals(4.5f, hit.distance(), 1e-4f);
    }

    @Test
    void compoundBodyReportsTheShapeHit() {
        PhysicsWorld world = still();
        BodyShape top = new BodyShape(new BoxShape(1, 0.1f, 1), new Vec3(0, 1, 0), Quat.IDENTITY);
        RigidBody table = new RigidBody(BodyType.STATIC).addShape(top).addShape(new BoxShape(0.1f, 0.5f, 0.1f), new Vec3(0, 0.4f, 0));
        world.add(table);
        assertEquals(top, world.raycast(new Ray(new Vec3(0.5f, 5, 0), new Vec3(0, -1, 0)), 10f).shape());
    }

    // ---- Overlaps ----

    @Test
    void overlapQueries() {
        PhysicsWorld world = still();
        RigidBody near = new RigidBody(BodyType.STATIC).addShape(new SphereShape(0.5f)).setPosition(2, 0, 0);
        RigidBody far = new RigidBody(BodyType.STATIC).addShape(new SphereShape(0.5f)).setPosition(10, 0, 0);
        RigidBody crate = new RigidBody(BodyType.STATIC).addShape(new BoxShape(0.5f, 0.5f, 0.5f)).setPosition(0, 2.4f, 0);
        world.add(near).add(far).add(crate);
        assertEquals(List.of(near, crate), world.overlapSphere(Vec3.ZERO, 2f));
        assertEquals(List.of(near), world.overlapSphere(Vec3.ZERO, 1.6f));
        assertEquals(List.of(far), world.overlapBox(new Vec3(10, 0, 0), new Vec3(1, 1, 1), Quat.IDENTITY));
        assertEquals(List.of(crate), world.overlap(new CapsuleShape(0.2f, 1f), new Vec3(0, 1, 0), Quat.IDENTITY, -1));
        assertTrue(world.overlap(new SphereShape(1f), Vec3.ZERO, null, 2).isEmpty(), "masked out");
    }

    // ---- Events ----

    private static final class Recorder implements ContactListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void contactBegin(Contact contact) {
            events.add("begin " + name(contact.getBodyA()) + "-" + name(contact.getBodyB()) + (contact.getImpulse() > 0 ? " hit" : ""));
        }

        @Override
        public void contactStay(Contact contact) {
            if (!events.getLast().startsWith("stay")) events.add("stay");
        }

        @Override
        public void contactEnd(RigidBody a, RigidBody b) {
            events.add("end " + name(a) + "-" + name(b));
        }

        @Override
        public void triggerEnter(RigidBody trigger, RigidBody other) {
            events.add("enter " + name(trigger) + " " + name(other));
        }

        @Override
        public void triggerExit(RigidBody trigger, RigidBody other) {
            events.add("exit " + name(trigger) + " " + name(other));
        }

        private static String name(RigidBody body) {
            return (String) body.getUserData();
        }
    }

    @Test
    void contactEventsComeOncePerPairInOrder() {
        PhysicsWorld world = new PhysicsWorld();
        Recorder recorder = new Recorder();
        world.addContactListener(recorder);
        RigidBody ground = new RigidBody(BodyType.STATIC).addShape(new PlaneShape()).setUserData("ground");
        RigidBody table = new RigidBody(BodyType.DYNAMIC).setUserData("table").setPosition(0, 0.5f, 0)
                .addShape(new BoxShape(0.1f, 0.5f, 0.1f), new Vec3(-0.8f, 0, 0))
                .addShape(new BoxShape(0.1f, 0.5f, 0.1f), new Vec3(0.8f, 0, 0));
        world.add(ground).add(table);
        run(world, 1f);
        assertEquals(List.of("begin ground-table hit", "stay"), recorder.events, "two legs, one pair");

        table.setPosition(0, 3, 0);
        world.step(DT);
        assertEquals("end ground-table", recorder.events.getLast());
        run(world, 1.5f);
        assertEquals("begin ground-table hit", recorder.events.get(recorder.events.size() - 2));

        world.remove(table);
        assertEquals("end ground-table", recorder.events.getLast(), "leaving the world ends its contacts");
    }

    @Test
    void sleepingBodiesKeepTouching() {
        PhysicsWorld world = new PhysicsWorld();
        Recorder recorder = new Recorder();
        RigidBody ground = new RigidBody(BodyType.STATIC).addShape(new PlaneShape()).setUserData("ground");
        RigidBody crate = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(0.5f, 0.5f, 0.5f)).setPosition(0, 0.5f, 0)
                .setUserData("crate").setContactListener(recorder);
        world.add(ground).add(crate);
        run(world, 3f);
        assertTrue(crate.isSleeping());
        assertEquals(List.of("begin ground-crate", "stay"), recorder.events.stream().map(e -> e.replace(" hit", "")).toList());
    }

    @Test
    void triggersReportButDoNotPush() {
        PhysicsWorld world = new PhysicsWorld();
        Recorder recorder = new Recorder();
        world.addContactListener(recorder);
        RigidBody zone = new RigidBody(BodyType.STATIC).addShape(new BoxShape(2, 0.5f, 2)).setPosition(0, 5, 0)
                .setTrigger(true).setUserData("zone");
        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.25f)).setPosition(0, 8, 0).setUserData("ball");
        world.add(zone).add(ball);
        run(world, 1.2f);
        assertTrue(ball.getPosition().y() < 4f, "fell straight through");
        assertEquals(List.of("enter zone ball", "exit zone ball"), recorder.events);
        assertNull(world.raycast(new Ray(new Vec3(1.5f, 10, 0), new Vec3(0, -1, 0)), 20f), "rays skip triggers");
        world.setQueriesHitTriggers(true);
        assertSame(zone, world.raycast(new Ray(new Vec3(1.5f, 10, 0), new Vec3(0, -1, 0)), 20f).body());
    }

    @Test
    void listenerMayRemoveBodies() {
        PhysicsWorld world = new PhysicsWorld();
        world.add(new RigidBody(BodyType.STATIC).addShape(new PlaneShape()).setUserData("ground"));
        RigidBody bomb = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.2f)).setPosition(0, 1, 0).setUserData("bomb");
        world.add(bomb);
        List<String> seen = new ArrayList<>();
        world.addContactListener(new ContactListener() {
            @Override
            public void contactBegin(Contact contact) {
                seen.add("boom");
                world.remove(contact.getOther(contact.getBodyA()));
            }

            @Override
            public void contactEnd(RigidBody a, RigidBody b) {
                seen.add("end");
            }
        });
        run(world, 1f);
        assertEquals(List.of("boom", "end"), seen);
        assertNull(bomb.getWorld());
    }
}
