package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BodyTreeTest {
    private static RigidBody randomBody(Random random, float spread) {
        Shape shape = random.nextBoolean() ? new SphereShape(0.1f + random.nextFloat()) : new BoxShape(0.1f + random.nextFloat(), 0.1f + random.nextFloat() * 2, 0.1f + random.nextFloat());
        BodyType type = random.nextInt(4) == 0 ? BodyType.STATIC : BodyType.DYNAMIC;
        return new RigidBody(type).addShape(shape).setPosition(random.nextFloat() * spread, random.nextFloat() * spread * 0.2f, random.nextFloat() * spread);
    }

    private static Set<RigidBody> query(PhysicsWorld world, Bounds box) {
        Set<RigidBody> found = new HashSet<>();
        world.tree.query(box, found::add);
        return found;
    }

    @Test
    void staysBalancedThroughAddsMovesAndRemovals() {
        PhysicsWorld world = new PhysicsWorld();
        Random random = new Random(11);
        List<RigidBody> bodies = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            RigidBody body = randomBody(random, 100f);
            world.add(body);
            bodies.add(body);
        }
        world.tree.validate();
        assertEquals(2000, world.tree.size());
        // A balanced tree of 2000 leaves is 11 deep at best; AVL rotations keep it within half again of that.
        assertTrue(world.tree.height() <= 17, "height " + world.tree.height());
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 300; i++) {
                RigidBody body = bodies.get(random.nextInt(bodies.size()));
                body.setPosition(random.nextFloat() * 100, random.nextFloat() * 20, random.nextFloat() * 100);
            }
            for (int i = 0; i < 100; i++) world.remove(bodies.remove(random.nextInt(bodies.size())));
            for (int i = 0; i < 100; i++) {
                RigidBody body = randomBody(random, 100f);
                world.add(body);
                bodies.add(body);
            }
            world.tree.validate();
            assertTrue(world.tree.height() <= 17, "height " + world.tree.height());
        }
        assertEquals(bodies.size(), world.tree.size());
        for (RigidBody body : List.copyOf(bodies)) world.remove(body);
        assertEquals(0, world.tree.size());
        assertEquals(0, world.tree.height());
    }

    @Test
    void boxQueriesFindEveryOverlapAndLittleElse() {
        PhysicsWorld world = new PhysicsWorld();
        Random random = new Random(5);
        List<RigidBody> bodies = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            RigidBody body = randomBody(random, 60f);
            world.add(body);
            bodies.add(body);
        }
        int extra = 0, total = 0;
        for (int q = 0; q < 200; q++) {
            Vec3 min = new Vec3(random.nextFloat() * 60, random.nextFloat() * 12, random.nextFloat() * 60);
            Bounds box = new Bounds(min, min.add(new Vec3(random.nextFloat() * 5, random.nextFloat() * 5, random.nextFloat() * 5)));
            Set<RigidBody> found = query(world, box);
            for (RigidBody body : bodies) {
                if (Broadphase.overlap(box, body.getBounds())) {
                    assertTrue(found.contains(body), "missed a body");
                    total++;
                } else if (found.contains(body)) {
                    extra++;
                }
            }
        }
        assertTrue(total > 100);
        assertTrue(extra < total, "the leaves are only a little bigger than the bodies: " + extra + " extra for " + total);
    }

    @Test
    void raysFindEveryBodyTheyCross() {
        PhysicsWorld world = new PhysicsWorld();
        Random random = new Random(8);
        List<RigidBody> bodies = new ArrayList<>();
        for (int i = 0; i < 800; i++) {
            RigidBody body = randomBody(random, 50f);
            world.add(body);
            bodies.add(body);
        }
        for (int q = 0; q < 300; q++) {
            Vec3 origin = new Vec3(random.nextFloat() * 50, random.nextFloat() * 10, random.nextFloat() * 50);
            Vec3 direction = switch (q % 4) { // along the axes too, where the slab test divides by zero
                case 0 -> Vec3.X;
                case 1 -> new Vec3(0f, -1f, 0f);
                default -> new Vec3(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f).normalized();
            };
            Ray ray = new Ray(origin, direction);
            float max = 5f + random.nextFloat() * 30f;
            Set<RigidBody> found = new HashSet<>();
            world.tree.raycast(ray, max, found::add);
            for (RigidBody body : bodies) {
                float d = ray.distanceTo(body.getBounds());
                if (d >= 0f && d <= max) assertTrue(found.contains(body), "missed a body " + d + " along");
            }
            // And the nearest hit is the first of all the hits.
            RaycastHit hit = world.raycast(ray, max);
            List<RaycastHit> all = world.raycastAll(ray, max, -1);
            if (hit == null) assertTrue(all.isEmpty());
            else assertEquals(all.getFirst().distance(), hit.distance(), 1e-5f);
        }
    }

    @Test
    void planesSitBesideTheTreeAndTurnUpEverywhere() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody floor = new RigidBody(BodyType.STATIC).addShape(new PlaneShape());
        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.5f)).setPosition(0, 3, 0);
        world.add(floor).add(ball);
        assertEquals(BodyTree.UNBOUNDED, floor.proxy);
        assertTrue(ball.proxy >= 0);
        Set<RigidBody> far = query(world, new Bounds(new Vec3(500, 500, 500), new Vec3(501, 501, 501)));
        assertEquals(Set.of(floor), far);
        assertNotNull(world.raycast(new Ray(new Vec3(300, 10, 300), new Vec3(0, -1, 0)), 20f));
        world.remove(floor);
        assertEquals(BodyTree.NONE, floor.proxy);
        assertEquals(1, world.tree.size());
    }

    @Test
    void aBodyThatStopsGetsAFittingLeafAgain() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.5f));
        world.add(ball);
        ball.setVelocity(new Vec3(60f, 0f, 0f));
        world.step(1f / 60f);
        // Moving at 60 m/s, the leaf reaches four steps ahead.
        float x = ball.getPosition().x();
        assertTrue(query(world, new Bounds(new Vec3(x + 3f, -0.1f, -0.1f), new Vec3(x + 3.1f, 0.1f, 0.1f))).contains(ball));
        ball.setVelocity(Vec3.ZERO);
        world.step(1f / 60f);
        assertFalse(query(world, new Bounds(new Vec3(x + 3f, -0.1f, -0.1f), new Vec3(x + 3.1f, 0.1f, 0.1f))).contains(ball));
        assertTrue(query(world, new Bounds(new Vec3(x, 0f, 0f), new Vec3(x, 0f, 0f))).contains(ball));
    }

    @Test
    void settledBodiesKeepTheirPlace() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody wall = new RigidBody(BodyType.STATIC).addShape(new BoxShape(1, 1, 1)).setPosition(5, 0, 0);
        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.5f)).setPosition(0, 0, 0);
        world.setGravity(Vec3.ZERO).add(wall).add(ball);
        world.step(1f / 60f);
        assertTrue(wall.settled());
        // Moving a static body by hand places its shapes at once, so it is found where it went.
        wall.setPosition(0, 5, 0);
        assertTrue(wall.settled());
        assertEquals(5f, wall.getBounds().center().y(), 1e-4f);
        assertSame(wall, world.raycast(new Ray(new Vec3(0, 0, 0), Vec3.UP), 10f).body());
        // A sleeping body is settled too, and waking it isn't.
        ball.sleep();
        world.step(1f / 60f);
        assertTrue(ball.settled());
        ball.setVelocity(new Vec3(1, 0, 0));
        assertFalse(ball.settled());
    }
}
