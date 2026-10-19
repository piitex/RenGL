package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BigHullTest {
    private static final float M = PhysicsWorld.CONTACT_MARGIN;
    private static final float DT = 1f / 60f;

    @AfterEach
    void restore() {
        PolytopeCollision.prunePairs = 600;
    }

    private static ConvexHullShape cloud(int n, Random random, float radius) {
        List<Vec3> points = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Vec3 v = new Vec3((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian()).normalized();
            points.add(new Vec3(v.x() * radius, v.y() * radius * 0.7f, v.z() * radius * 1.2f));
        }
        return ConvexHullShape.of(points);
    }

    private static Quat randomTurn(Random random) {
        return Quat.fromAxisAngle(new Vec3((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian()).normalized(),
                random.nextFloat() * 6.28f);
    }

    @Test
    void pruningTheEdgePairsGivesTheSameContacts() {
        Random random = new Random(11);
        int touching = 0, apart = 0;
        for (int trial = 0; trial < 250; trial++) {
            ConvexHullShape a = cloud(40 + random.nextInt(60), random, 1f), b = cloud(40 + random.nextInt(60), random, 0.8f);
            Pose pa = new Pose(Vec3.ZERO, randomTurn(random));
            float reach = 1.2f + random.nextFloat() * 1.2f;
            Pose pb = new Pose(new Vec3((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian()).normalized().mul(reach), randomTurn(random));

            Manifold fast = new Manifold(), full = new Manifold();
            PolytopeCollision.prunePairs = 600;
            Narrowphase.collide(a, pa, b, pb, M, fast);
            PolytopeCollision.prunePairs = Integer.MAX_VALUE;
            Narrowphase.collide(a, pa, b, pb, M, full);

            assertEquals(full.count > 0, fast.count > 0, "trial " + trial + ": touching or not");
            if (full.count == 0) {
                apart++;
                continue;
            }
            touching++;
            float deepFull = Float.NEGATIVE_INFINITY, deepFast = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < full.count; i++) deepFull = Math.max(deepFull, full.depths[i]);
            for (int i = 0; i < fast.count; i++) deepFast = Math.max(deepFast, fast.depths[i]);
            assertEquals(deepFull, deepFast, 2e-3f, "trial " + trial + ": the deepest point");
            assertTrue(full.normal.dot(fast.normal) > 0.99f, "trial " + trial + ": normals " + full.normal + " and " + fast.normal);
        }
        assertTrue(touching > 60 && apart > 20, touching + " touching, " + apart + " apart: the trials should cover both");
    }

    @Test
    void twoHullsOfHundredsOfCornersCollideInAFewMilliseconds() {
        Random random = new Random(5);
        ConvexHullShape a = cloud(400, random, 1f), b = cloud(400, random, 1f);
        assertTrue(a.polytope().vertices.length > 200, "built from " + a.polytope().vertices.length + " corners");
        Pose pa = new Pose(Vec3.ZERO, Quat.IDENTITY), pb = new Pose(new Vec3(1.5f, 0.1f, 0f), Quat.fromAxisAngle(Vec3.UP, 0.7f));
        Manifold m = new Manifold();
        for (int i = 0; i < 3; i++) Narrowphase.collide(a, pa, b, pb, M, m);
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) Narrowphase.collide(a, pa, b, pb, M, m);
        double ms = (System.nanoTime() - start) / 1e6 / 10;
        assertTrue(ms < 40, "took " + ms + " ms a pair"); // 0.9 s without the pruning
        assertTrue(m.count > 0);
    }

    @Test
    void aBigHullFallsOnAnotherAndSettles() {
        Random random = new Random(3);
        PhysicsWorld world = new PhysicsWorld();
        RigidBody floor = new RigidBody(BodyType.STATIC).addShape(cloud(300, random, 1.5f));
        floor.setPosition(0f, -1.5f, 0f);
        world.add(floor);
        RigidBody rock = new RigidBody(BodyType.DYNAMIC).addShape(cloud(300, random, 0.5f)).setMass(5f);
        rock.setPosition(0.1f, 1.2f, 0f);
        world.add(rock);
        for (int i = 0; i < 240; i++) world.step(DT);
        assertTrue(rock.isSleeping() || rock.getVelocity().length() < 0.1f, "still moving at " + rock.getVelocity());
        assertTrue(rock.getPosition().y() < 0.8f && rock.getPosition().y() > -0.5f, "rests on top, at " + rock.getPosition());
    }
}
