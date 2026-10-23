package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SolverTest {
    private static final float DT = 1f / 60f;
    private static final float G = 9.81f;

    private static void run(PhysicsWorld world, float seconds) {
        int steps = Math.round(seconds / DT);
        for (int i = 0; i < steps; i++) world.step(DT);
    }

    private static PhysicsWorld withFloor() {
        PhysicsWorld world = new PhysicsWorld();
        world.add(new RigidBody(BodyType.STATIC).addShape(new PlaneShape()));
        return world;
    }

    private static RigidBody box(float size, float x, float y, float z) {
        return new RigidBody(BodyType.DYNAMIC).addShape(BoxShape.ofSize(size, size, size)).setPosition(x, y, z);
    }

    private static RigidBody ball(float radius, float x, float y, float z) {
        return new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(radius)).setPosition(x, y, z);
    }

    @Test
    void boxComesToRestOnTheFloor() {
        PhysicsWorld world = withFloor();
        RigidBody crate = box(1f, 0, 3f, 0);
        world.add(crate);
        run(world, 3f);
        assertEquals(0.5f, crate.getPosition().y(), 0.01f);
        assertTrue(crate.getPosition().y() < 0.5f + 1e-3f, "not floating");
        assertEquals(0f, crate.getVelocity().length(), 1e-3f);
        assertEquals(1f, Math.abs(crate.getRotation().w()), 1e-3f, "landed flat and stayed flat");
    }

    @Test
    void restingDoesNotJitter() {
        PhysicsWorld world = withFloor().setSleeping(false);
        RigidBody crate = box(1f, 0, 0.5f, 0);
        RigidBody ball = ball(0.5f, 3, 0.5f, 0);
        world.add(crate).add(ball);
        run(world, 1f);
        float worst = 0f;
        for (int i = 0; i < 300; i++) {
            world.step(DT);
            worst = Math.max(worst, Math.max(crate.getVelocity().length(), ball.getVelocity().length()));
            assertEquals(0.5f, crate.getPosition().y(), 0.006f);
            assertEquals(0.5f, ball.getPosition().y(), 0.006f);
        }
        assertTrue(worst < 0.01f, "fastest wobble " + worst + " m/s");
        assertEquals(0f, crate.getPosition().x(), 1e-3f);
    }

    @Test
    void bounceHeightFollowsRestitution() {
        for (float e : new float[]{0.5f, 0.8f}) {
            PhysicsWorld world = withFloor().setFixedStep(1f / 240f);
            RigidBody ball = ball(0.25f, 0, 2.25f, 0).setRestitution(e);
            world.add(ball);
            boolean hit = false;
            float peak = 0f;
            for (int i = 0; i < 240 * 2; i++) {
                world.step(1f / 240f);
                if (ball.getVelocity().y() > 0f) hit = true;
                if (hit) peak = Math.max(peak, ball.getPosition().y() - 0.25f);
                if (hit && ball.getVelocity().y() < -0.5f) break;
            }
            assertEquals(e * e * 2f, peak, 0.06f * e * e * 2f, "restitution " + e);
        }
    }

    @Test
    void noRestitutionMeansNoBounce() {
        PhysicsWorld world = withFloor();
        RigidBody ball = ball(0.25f, 0, 3f, 0);
        world.add(ball);
        float peakAfter = 0f;
        boolean landed = false;
        for (int i = 0; i < 120; i++) {
            world.step(DT);
            if (ball.getPosition().y() < 0.26f) landed = true;
            if (landed) peakAfter = Math.max(peakAfter, ball.getPosition().y() - 0.25f);
        }
        assertTrue(landed);
        assertTrue(peakAfter < 0.01f, "rose " + peakAfter);
    }

    @Test
    void frictionStopsASlidingBox() {
        PhysicsWorld world = withFloor();
        RigidBody crate = box(1f, 0, 0.5f, 0).setVelocity(5f, 0, 0);
        world.add(crate);
        run(world, 3f);
        float expected = 25f / (2f * 0.5f * G); // v² / (2 µ g)
        assertEquals(expected, crate.getPosition().x(), expected * 0.05f);
        assertEquals(0f, crate.getVelocity().x(), 1e-3f);
        assertEquals(1f, Math.abs(crate.getRotation().w()), 1e-3f, "slid without tipping over");
    }

    @Test
    void iceSlidesFarther() {
        PhysicsWorld world = withFloor();
        RigidBody puck = box(1f, 0, 0.5f, 0).setMaterial(PhysicsMaterial.ICE).setVelocity(5f, 0, 0);
        world.add(puck);
        run(world, 3f);
        assertTrue(puck.getPosition().x() > 10f, "slid " + puck.getPosition().x());
    }

    private static RigidBody ramp(float degrees) {
        return new RigidBody(BodyType.STATIC).addShape(new BoxShape(10f, 0.5f, 3f))
                .setRotation(Quat.fromAxisAngle(Vec3.Z, (float) Math.toRadians(degrees)));
    }

    private static Vec3 onRamp(float degrees, float height) {
        float a = (float) Math.toRadians(degrees);
        Vec3 normal = new Vec3(-(float) Math.sin(a), (float) Math.cos(a), 0);
        return normal.mul(0.5f + height);
    }

    @Test
    void frictionHoldsABoxOnAGentleSlope() {
        PhysicsWorld world = new PhysicsWorld();
        world.add(ramp(20f));
        RigidBody crate = box(1f, 0, 0, 0).setRotation(Quat.fromAxisAngle(Vec3.Z, (float) Math.toRadians(20)));
        crate.setPosition(onRamp(20f, 0.5f));
        world.add(crate);
        run(world, 0.5f);
        Vec3 settled = crate.getPosition();
        run(world, 3f);
        assertTrue(crate.getPosition().distance(settled) < 0.01f, "crept " + crate.getPosition().distance(settled));

        // On a slicker surface the same slope is too steep (tan 20° = 0.36).
        PhysicsWorld slick = new PhysicsWorld();
        slick.add(ramp(20f).setFriction(0.2f));
        RigidBody sliding = box(1f, 0, 0, 0).setRotation(Quat.fromAxisAngle(Vec3.Z, (float) Math.toRadians(20))).setFriction(0.2f);
        sliding.setPosition(onRamp(20f, 0.5f));
        slick.add(sliding);
        run(slick, 1f);
        assertTrue(sliding.getPosition().x() < -0.5f, "slid down to " + sliding.getPosition());
    }

    @Test
    void ballRollsDownASlopeAtFiveSevenths() {
        float degrees = 15f;
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        world.add(ramp(degrees).setFriction(1f));
        RigidBody ball = ball(0.25f, 0, 0, 0).setFriction(1f).setAngularDamping(0f);
        ball.setPosition(onRamp(degrees, 0.25f - 0.5f + 0.25f));
        world.add(ball);
        run(world, 0.25f);
        Vec3 start = ball.getVelocity();
        run(world, 1f);
        float acceleration = ball.getVelocity().sub(start).length();
        // A solid ball rolling without slipping: (5/7) g sin θ, not the g sin θ of a sliding block.
        float expected = 5f / 7f * G * (float) Math.sin(Math.toRadians(degrees));
        assertEquals(expected, acceleration, expected * 0.05f);
    }

    private static List<RigidBody> stack(PhysicsWorld world, int height) {
        List<RigidBody> stack = new ArrayList<>();
        for (int i = 0; i < height; i++) {
            RigidBody crate = box(1f, 0, 0.5f + i * 1.0f, 0);
            stack.add(crate);
            world.add(crate);
        }
        return stack;
    }

    @Test
    void stackOfTenStaysUp() {
        PhysicsWorld world = withFloor();
        List<RigidBody> stack = stack(world, 10);
        run(world, 10f);
        for (int i = 0; i < 10; i++) {
            Vec3 p = stack.get(i).getPosition();
            assertEquals(0f, p.x(), 0.1f, "box " + i);
            assertEquals(0f, p.z(), 0.1f, "box " + i);
            assertEquals(0.5f + i, p.y(), 0.05f, "box " + i);
        }
        assertTrue(stack.getLast().isSleeping(), "the settled stack fell asleep");
    }

    @Test
    void tallerStackHoldsWithMoreIterations() {
        PhysicsWorld world = withFloor().setVelocityIterations(20);
        List<RigidBody> stack = stack(world, 15);
        run(world, 10f);
        Vec3 top = stack.getLast().getPosition();
        assertEquals(14.5f, top.y(), 0.05f);
        assertTrue(Math.hypot(top.x(), top.z()) < 0.2f, "leaned to " + top);
    }

    @Test
    void pyramidOfBoxesSettles() {
        PhysicsWorld world = withFloor();
        List<RigidBody> boxes = new ArrayList<>();
        int rows = 5;
        for (int row = 0; row < rows; row++) {
            for (int i = 0; i < rows - row; i++) {
                RigidBody crate = box(1f, i * 1.02f - (rows - row - 1) * 0.51f, 0.5f + row, 0);
                boxes.add(crate);
                world.add(crate);
            }
        }
        run(world, 8f);
        assertEquals(rows - 0.5f, boxes.getLast().getPosition().y(), 0.08f);
        for (RigidBody crate : boxes) assertTrue(crate.isSleeping(), crate + " still awake");
    }

    @Test
    void sleepingAndWaking() {
        PhysicsWorld world = withFloor();
        RigidBody bottom = box(1f, 0, 0.5f, 0), top = box(1f, 0, 1.5f, 0);
        world.add(bottom).add(top);
        run(world, 2f);
        assertTrue(bottom.isSleeping() && top.isSleeping());
        assertFalse(world.getContacts().isEmpty(), "sleeping bodies keep their contacts");

        // A ball landing on the top wakes the whole stack, not just the box it hits.
        RigidBody ball = ball(0.3f, 0.2f, 3f, 0);
        world.add(ball);
        boolean bothAwake = false;
        for (int i = 0; i < 60 && !bothAwake; i++) {
            world.step(DT);
            bothAwake = !top.isSleeping() && !bottom.isSleeping();
        }
        assertTrue(bothAwake);

        // A push wakes a body; removing what it rests on wakes it too.
        run(world, 3f);
        assertTrue(top.isSleeping());
        top.applyImpulse(new Vec3(0.1f, 0, 0));
        assertFalse(top.isSleeping());
        run(world, 3f);
        world.remove(bottom);
        assertFalse(top.isSleeping());
        run(world, 0.5f);
        assertTrue(top.getPosition().y() < 1f, "fell once its support went");
    }

    @Test
    void sleepCanBeTurnedOff() {
        PhysicsWorld world = withFloor();
        RigidBody player = box(1f, 0, 0.5f, 0).setSleepingAllowed(false);
        world.add(player);
        run(world, 3f);
        assertFalse(player.isSleeping());
        world.setSleeping(false);
        RigidBody crate = box(1f, 3, 0.5f, 0);
        world.add(crate);
        run(world, 3f);
        assertFalse(crate.isSleeping());
    }

    @Test
    void pilesNeverGainEnergy() {
        PhysicsWorld world = withFloor().setSleeping(false);
        Random random = new Random(11);
        List<RigidBody> bodies = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Shape shape = switch (i % 3) {
                case 0 -> new SphereShape(0.3f);
                case 1 -> BoxShape.ofSize(0.6f, 0.4f, 0.5f);
                default -> new CapsuleShape(0.2f, 0.3f);
            };
            RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(shape).setLinearDamping(0f).setAngularDamping(0f)
                    .setPosition(random.nextFloat() * 2 - 1, 0.5f + i * 0.5f, random.nextFloat() * 2 - 1)
                    .setRotation(Quat.fromEuler(random.nextFloat() * 3, random.nextFloat() * 3, random.nextFloat() * 3));
            bodies.add(body);
            world.add(body);
        }
        float start = energy(bodies);
        float previous = start;
        for (int i = 0; i < 600; i++) {
            world.step(DT);
            float now = energy(bodies);
            // Rounding aside (0.1% of the start a step), contacts only ever take energy away.
            assertTrue(now <= previous + 1e-3f * start, "step " + i + ": " + previous + " -> " + now);
            previous = now;
        }
        assertTrue(previous < start);
        for (RigidBody body : bodies) assertTrue(body.getPosition().y() > 0f, body + " fell through the floor");
    }

    private static float energy(List<RigidBody> bodies) {
        float total = 0f;
        for (RigidBody body : bodies) total += body.getKineticEnergy() + body.getMass() * G * body.getCenterOfMass().y();
        return total;
    }

    @Test
    void collisionsKeepMomentum() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO).setSleeping(false);
        Random random = new Random(5);
        List<RigidBody> bodies = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            RigidBody body = (i % 2 == 0 ? ball(0.4f, 0, 0, 0) : box(0.7f, 0, 0, 0)).setMass(1f + i)
                    .setPosition(random.nextFloat() * 4, random.nextFloat() * 4, random.nextFloat() * 4)
                    .setVelocity(random.nextFloat() * 4 - 2, random.nextFloat() * 4 - 2, random.nextFloat() * 4 - 2)
                    .setRestitution(0.5f);
            bodies.add(body);
            world.add(body);
        }
        Vec3 before = momentum(bodies);
        run(world, 3f);
        Vec3 after = momentum(bodies);
        assertTrue(world.getStepCount() > 0);
        assertEquals(before.x(), after.x(), 1e-2f);
        assertEquals(before.y(), after.y(), 1e-2f);
        assertEquals(before.z(), after.z(), 1e-2f);
    }

    private static Vec3 momentum(List<RigidBody> bodies) {
        Vec3 total = Vec3.ZERO;
        for (RigidBody body : bodies) total = total.add(body.getVelocity().mul(body.getMass()));
        return total;
    }

    @Test
    void elasticHeadOnSwapsVelocities() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody moving = ball(0.5f, -2f, 0, 0).setRestitution(1f).setVelocity(3f, 0, 0);
        RigidBody still = ball(0.5f, 0, 0, 0).setRestitution(1f);
        world.add(moving).add(still);
        run(world, 1.5f);
        assertEquals(0f, moving.getVelocity().x(), 0.05f);
        assertEquals(3f, still.getVelocity().x(), 0.05f);
    }

    @Test
    void movingPlatformCarriesABox() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody platform = new RigidBody(BodyType.KINEMATIC).addShape(new BoxShape(5f, 0.25f, 5f)).setVelocity(1f, 0, 0);
        RigidBody crate = box(1f, 0, 0.75f, 0);
        world.add(platform).add(crate);
        run(world, 2f);
        assertEquals(1f, crate.getVelocity().x(), 0.02f);
        assertEquals(crate.getPosition().x(), platform.getPosition().x(), 0.1f);
        assertEquals(0.75f, crate.getPosition().y(), 0.01f);
    }

    @Test
    void lockedRotationKeepsACharacterUpright() {
        PhysicsWorld world = withFloor();
        RigidBody player = new RigidBody(BodyType.DYNAMIC).addShape(CapsuleShape.ofHeight(0.3f, 1.8f)).setPosition(0, 0.9f, 0)
                .lockRotation(true, true, true);
        RigidBody wall = box(1f, 0.9f, 0.5f, 0).setMass(100f);
        world.add(player).add(wall);
        player.applyImpulseAtPoint(new Vec3(5f, 0, 0), new Vec3(0, 1.6f, 0));
        run(world, 2f);
        assertEquals(1f, Math.abs(player.getRotation().w()), 1e-4f);
        assertEquals(0.9f, player.getPosition().y(), 0.01f);
    }

    @Test
    void capsuleLyingOnTheFloorStaysPut() {
        PhysicsWorld world = withFloor();
        RigidBody log = new RigidBody(BodyType.DYNAMIC).addShape(new CapsuleShape(0.25f, 1f))
                .setRotation(Quat.fromAxisAngle(Vec3.Z, (float) Math.PI / 2)).setPosition(0, 0.3f, 0);
        world.add(log);
        run(world, 3f);
        assertEquals(0.25f, log.getPosition().y(), 0.01f);
        assertEquals(0f, log.getPosition().x(), 0.01f);
        assertTrue(log.isSleeping());
    }

    @Test
    void fastBallDoesNotTunnelThroughAThinFloor() {
        PhysicsWorld world = new PhysicsWorld();
        world.add(new RigidBody(BodyType.STATIC).addShape(new BoxShape(5f, 0.025f, 5f)));
        RigidBody bullet = ball(0.1f, 0, 3f, 0).setVelocity(0, -60f, 0);
        world.add(bullet);
        run(world, 1f);
        assertTrue(bullet.getPosition().y() > 0f, "went through to " + bullet.getPosition().y());
    }

    @Test
    void deepOverlapComesApartGently() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody a = box(1f, 0, 0, 0), b = box(1f, 0.6f, 0, 0);
        world.add(a).add(b);
        float fastest = 0f;
        for (int i = 0; i < 60; i++) {
            world.step(DT);
            fastest = Math.max(fastest, Math.max(a.getVelocity().length(), b.getVelocity().length()));
        }
        assertTrue(b.getPosition().x() - a.getPosition().x() > 0.98f, "still overlapping: " + (b.getPosition().x() - a.getPosition().x()));
        assertTrue(fastest < 0.5f, "split overlaps shouldn't leave speed behind: " + fastest);
    }
}
