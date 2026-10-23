package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SolverThreadsTest {
    private static final float DT = 1f / 60f;

    /** Many separate piles of boxes on a plane, and a chain hanging from the ceiling, so some islands have joints. */
    private static List<RigidBody> scene(PhysicsWorld world) {
        world.add(new RigidBody(BodyType.STATIC).addShape(new PlaneShape()));
        List<RigidBody> bodies = new ArrayList<>();
        for (int pile = 0; pile < 40; pile++) {
            for (int i = 0; i < 8; i++) {
                RigidBody box = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(0.5f, 0.5f, 0.5f))).setMass(1f + i);
                box.setPosition(pile * 4f + 0.07f * i, 0.5f + i * 1.01f, (pile % 5) * 0.05f);
                world.add(box);
                bodies.add(box);
            }
        }
        RigidBody previous = null;
        for (int i = 0; i < 5; i++) {
            RigidBody link = new RigidBody(BodyType.DYNAMIC).addShape(new CapsuleShape(0.05f, 0.25f)).setMass(0.5f);
            link.setPosition(-5f, 10f - 0.3f - i * 0.6f, 0f);
            world.add(link);
            world.addJoint(new BallJoint(link, previous, new Vec3(-5f, 10f - i * 0.6f, 0f)));
            bodies.add(link);
            previous = link;
        }
        return bodies;
    }

    private static List<Vec3> run(int threads) {
        PhysicsWorld world = new PhysicsWorld().setSolverThreads(threads);
        List<RigidBody> bodies = scene(world);
        for (int i = 0; i < 240; i++) world.step(DT);
        List<Vec3> result = new ArrayList<>();
        for (RigidBody body : bodies) result.add(body.getPosition());
        return result;
    }

    @Test
    void threadsGiveTheSameResultAsOne() {
        List<Vec3> alone = run(0);
        for (int threads : new int[]{1, 3, 7}) {
            List<Vec3> together = run(threads);
            for (int i = 0; i < alone.size(); i++) {
                assertEquals(alone.get(i), together.get(i), "body " + i + " with " + threads + " threads");
            }
        }
    }

    /** Hulls tumbling down a sloped mesh: the narrow phase against triangles, and one big heap at the bottom. */
    private static List<Vec3> runMesh(int threads) {
        PhysicsWorld world = new PhysicsWorld().setSolverThreads(threads);
        float[] positions = {-20, 0, -20, 20, 0, -20, 20, -6, 20, -20, -6, 20};
        world.add(new RigidBody(BodyType.STATIC).addShape(new MeshShape(positions, new int[]{0, 2, 1, 0, 3, 2})));
        java.util.Random random = new java.util.Random(3);
        List<RigidBody> bodies = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            List<Vec3> points = new ArrayList<>();
            for (int k = 0; k < 12; k++) {
                points.add(new Vec3((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian()).normalized().mul(0.3f + random.nextFloat() * 0.2f));
            }
            RigidBody rock = new RigidBody(BodyType.DYNAMIC).addShape(ConvexHullShape.of(points)).setDensity(2500f);
            rock.setPosition(random.nextFloat() * 30f - 15f, 1f + random.nextFloat() * 3f, random.nextFloat() * 30f - 15f);
            world.add(rock);
            bodies.add(rock);
        }
        for (int i = 0; i < 180; i++) world.step(DT);
        List<Vec3> result = new ArrayList<>();
        for (RigidBody body : bodies) result.add(body.getPosition());
        return result;
    }

    @Test
    void threadsGiveTheSameResultOnAMesh() {
        List<Vec3> alone = runMesh(0);
        List<Vec3> together = runMesh(5);
        for (int i = 0; i < alone.size(); i++) assertEquals(alone.get(i), together.get(i), "rock " + i);
    }

    @Test
    void aWorldWithoutThreadsStillWorks() {
        PhysicsWorld world = new PhysicsWorld().setSolverThreads(0);
        assertEquals(0, world.getSolverThreads());
        List<RigidBody> bodies = scene(world);
        for (int i = 0; i < 60; i++) world.step(DT);
        assertTrue(bodies.getFirst().getPosition().y() < 1f);
    }
}
