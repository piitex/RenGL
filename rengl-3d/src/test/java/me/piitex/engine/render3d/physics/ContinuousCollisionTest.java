package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ContinuousCollisionTest {
    private static final float DT = 1f / 60f;

    private static PhysicsWorld space() {
        return new PhysicsWorld().setSleeping(false).setGravity(Vec3.ZERO);
    }

    /**
     * A thin wall in the plane x = 0 (2 cm thick), 20 m wide and high.
     */
    private static RigidBody wall() {
        return new RigidBody(BodyType.STATIC).addShape(new BoxShape(0.01f, 10f, 10f));
    }

    private static float[] quad() { // the same wall as two triangles, 0.2 m thick? no: a sheet, with nothing inside
        return new float[]{0f, -10f, -10f, 0f, 10f, -10f, 0f, 10f, 10f, 0f, -10f, 10f};
    }

    /**
     * Fires a bullet at {@code speed} from x = -30 at the wall for a second; returns the highest x it reached.
     */
    private static float reach(RigidBody wall, RigidBody bullet) {
        PhysicsWorld world = space();
        world.add(wall);
        world.add(bullet);
        float far = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 60; i++) {
            world.step(DT);
            far = Math.max(far, bullet.getPosition().x());
        }
        return far;
    }

    private static RigidBody bullet(float speed) {
        return new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.05f)).setMass(0.01f).setPosition(-30f, 1f, 0.5f).setVelocity(speed, 0f, 0f);
    }

    @Test
    void aBulletFasterThanTheContactMarginReachesStopsAtAThinWall() {
        // 600 m/s is ten metres a step: past the largest margin a contact is looked for at.
        float far = reach(wall(), bullet(600f));
        assertTrue(far < -0.01f, "the bullet got to x = " + far + " and the wall is at 0");
    }

    @Test
    void withoutItTheSameBulletPassesThrough() {
        // The premise of the test above: it is the sweep that stops it.
        float far = reach(wall(), bullet(600f).setContinuous(false));
        assertTrue(far > 1f, "the bullet only got to x = " + far);
    }

    @Test
    void itStopsAtAPlaneAndAtAMeshAsWell() {
        RigidBody plane = new RigidBody(BodyType.STATIC).addShape(new PlaneShape(new Vec3(-1f, 0f, 0f)));
        assertTrue(reach(plane, bullet(600f)) < 0f, "through the plane");
        RigidBody sheet = new RigidBody(BodyType.STATIC).addShape(new MeshShape(quad(), new int[]{0, 1, 2, 0, 2, 3}));
        assertTrue(reach(sheet, bullet(600f)) < 0f, "through the mesh");
        RigidBody capsule = new RigidBody(BodyType.STATIC).addShape(new CapsuleShape(0.01f, 5f));
        assertTrue(reach(capsule, bullet(600f).setPosition(-30f, 0f, 0f)) < 0f, "through the capsule");
        RigidBody ball = new RigidBody(BodyType.STATIC).addShape(new SphereShape(0.5f));
        assertTrue(reach(ball, bullet(600f).setPosition(-30f, 0f, 0f)) < 0f, "through the ball");
    }

    @Test
    void theBulletComesToRestAtTheSurfaceAndStaysThere() {
        PhysicsWorld world = space();
        world.add(wall());
        RigidBody bullet = bullet(600f);
        world.add(bullet);
        for (int i = 0; i < 90; i++) world.step(DT);
        // 0.05 radius, 0.01 half thickness.
        assertEquals(-0.06f, bullet.getPosition().x(), 0.02f);
        assertTrue(Math.abs(bullet.getVelocity().x()) < 1f, "still moving at " + bullet.getVelocity());
    }

    @Test
    void aBouncyBulletBouncesOffTheWallItWasStoppedAt() {
        PhysicsWorld world = space();
        world.add(wall());
        RigidBody bullet = bullet(600f).setMaterial(new PhysicsMaterial(0.5f, 1f));
        world.add(bullet);
        float low = 0f;
        for (int i = 0; i < 20; i++) {
            world.step(DT);
            low = Math.min(low, bullet.getVelocity().x());
        }
        assertTrue(bullet.getPosition().x() < 0f, "went through to " + bullet.getPosition());
        assertTrue(bullet.getVelocity().x() < -100f, "it should come back, but its speed is " + bullet.getVelocity().x());
    }

    /**
     * A 2 cm sheet 80 cm across, turned about the vertical axis, sent at the wall; returns the farthest x its centre reached.
     */
    private static float sheetAt(float speed, float turn) {
        PhysicsWorld world = space();
        world.add(wall());
        RigidBody sheet = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(0.01f, 0.4f, 0.4f)).setMass(1f)
                .setPosition(-20f, 0f, 0f).setVelocity(speed, 0f, 0f);
        sheet.setRotation(Quat.fromAxisAngle(new Vec3(0f, 1f, 0f), turn));
        world.add(sheet);
        float far = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 90; i++) {
            world.step(DT);
            far = Math.max(far, sheet.getPosition().x());
        }
        return far;
    }

    @Test
    void aThinFastSheetStopsAtAWallAtAnAngle() {
        for (float turn = 0f; turn <= 1.5f; turn += 0.15f) {
            assertTrue(sheetAt(30f, turn) < 0.1f, "a sheet turned " + turn + " at 30 m/s got to x = " + sheetAt(30f, turn));
        }
        // Flat on, or edge on, it is stopped at any speed.
        for (float turn : new float[]{0f, 1.5707964f}) {
            assertTrue(sheetAt(600f, turn) < 0.1f, "a sheet turned " + turn + " at 600 m/s got to x = " + sheetAt(600f, turn));
        }
    }

    @Test
    void aStickSpinningAndMovingFastDoesNotPassAWall() {
        for (float spin : new float[]{20f, 60f, 90f}) {
            PhysicsWorld world = space();
            world.add(wall());
            RigidBody stick = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(0.03f, 0.03f, 1f)).setMass(1f)
                    .setAngularDamping(0f).setPosition(-8f, 0f, 0f).setVelocity(200f, 0f, 0f);
            stick.setAngularVelocity(new Vec3(0f, spin, 0f));
            world.add(stick);
            float far = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < 40; i++) {
                world.step(DT);
                for (float end : new float[]{-1f, 1f}) {
                    far = Math.max(far, stick.getPosition().add(stick.getRotation().rotate(new Vec3(0f, 0f, end))).x());
                }
            }
            assertTrue(far < 0.1f, "at " + spin + " rad/s the stick's end got to x = " + far);
        }
    }

    @Test
    void whatTheSolverAlreadyStopsIsNotHeldBackOrChanged() {
        // A ball dropped on a floor and a slow one rolling along it never get swept (they move less than they are thick), and a thin
        // fast one landing is stopped by its contacts at the surface first: the heights match with the sweep off.
        float[] with = new float[2], without = new float[2];
        for (int run = 0; run < 2; run++) {
            for (int kind = 0; kind < 2; kind++) {
                PhysicsWorld world = new PhysicsWorld();
                world.add(new RigidBody(BodyType.STATIC).addShape(new BoxShape(20f, 0.5f, 20f)).setPosition(0f, -0.5f, 0f));
                RigidBody body = new RigidBody(BodyType.DYNAMIC).setMass(1f).setPosition(0f, 2f, 0f);
                body.addShape(kind == 0 ? new SphereShape(0.25f) : new BoxShape(0.4f, 0.01f, 0.4f));
                body.setVelocity(kind == 0 ? 0f : 3f, kind == 0 ? 0f : -20f, 0f).setContinuous(run == 0);
                world.add(body);
                for (int i = 0; i < 90; i++) world.step(DT);
                (run == 0 ? with : without)[kind] = body.getPosition().y();
            }
        }
        for (int kind = 0; kind < 2; kind++) assertEquals(without[kind], with[kind], 0.02f, "kind " + kind);
        assertEquals(0.25f, with[0], 0.02f);
        assertEquals(0.01f, with[1], 0.02f);
    }

    @Test
    void aBodyLyingOnTheGroundKeepsSlidingAtSpeed() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        world.add(new RigidBody(BodyType.STATIC).addShape(new BoxShape(200f, 0.5f, 200f)).setPosition(0f, -0.5f, 0f).setMaterial(new PhysicsMaterial(0f, 0f)));
        RigidBody plank = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(0.5f, 0.01f, 0.5f)).setMass(1f).setMaterial(new PhysicsMaterial(0f, 0f))
                .setPosition(0f, 0.01f, 0f).setVelocity(20f, 0f, 0f);
        world.add(plank);
        for (int i = 0; i < 60; i++) world.step(DT);
        assertEquals(20f, plank.getVelocity().x(), 0.5f);
        assertEquals(20f, plank.getPosition().x(), 1.5f);
    }

    @Test
    void triggersFilteredLayersAndMovingPlatformsDontStopIt() {
        RigidBody zone = wall().setTrigger(true);
        assertTrue(reach(zone, bullet(600f)) > 1f, "a trigger stopped it");

        RigidBody other = wall();
        other.setCollisionLayer(2);
        RigidBody b = bullet(600f);
        b.setCollisionMask(1);
        assertTrue(reach(other, b) > 1f, "a layer it ignores stopped it");

        RigidBody platform = new RigidBody(BodyType.KINEMATIC).addShape(new BoxShape(0.01f, 10f, 10f)).setVelocity(0f, 1f, 0f);
        assertTrue(reach(platform, bullet(600f)) > 1f, "a moving platform stopped it");
    }

    @Test
    void thicknessAndTheTestForAStepThatLongIsPerBody() {
        PhysicsWorld world = space();
        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.5f)).setMass(1f);
        RigidBody pellet = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.01f)).setMass(1f);
        world.add(ball).add(pellet);
        ball.setVelocity(20f, 0f, 0f); // a third of a metre a step, and the ball is half a metre thick
        pellet.setVelocity(20f, 0f, 0f);
        assertFalse(ball.sweeps(DT));
        assertTrue(pellet.sweeps(DT));
        assertFalse(pellet.setContinuous(false).sweeps(DT));
        assertTrue(ball.isContinuous());
        // a long stick turning: its far end covers more than its thickness
        RigidBody stick = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(1f, 0.05f, 0.05f)).setMass(1f);
        world.add(stick);
        stick.setAngularVelocity(new Vec3(0f, 0f, 1f));
        assertFalse(stick.sweeps(DT));
        stick.setAngularVelocity(new Vec3(0f, 0f, 20f));
        assertTrue(stick.sweeps(DT));
    }

    @Test
    void aHullIsAsThinAsItsNarrowestWidth() {
        ConvexHullShape slab = ConvexHullShape.of(java.util.List.of(
                new Vec3(-1f, -0.02f, -1f), new Vec3(1f, -0.02f, -1f), new Vec3(1f, 0.02f, -1f), new Vec3(-1f, 0.02f, -1f),
                new Vec3(-1f, -0.02f, 1f), new Vec3(1f, -0.02f, 1f), new Vec3(1f, 0.02f, 1f), new Vec3(-1f, 0.02f, 1f)));
        PhysicsWorld world = space();
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(slab).setMass(1f).setVelocity(3f, 0f, 0f);
        world.add(body);
        assertTrue(body.sweeps(DT), "three metres a second is 5 cm a step, and the slab is 2 cm thick");
        body.setVelocity(0.5f, 0f, 0f);
        assertFalse(body.sweeps(DT));
    }

    @Test
    void gjkBetweenTwoSetsIsTheGapBetweenTheirCorners() {
        Random random = new Random(5);
        for (int trial = 0; trial < 200; trial++) {
            Vec3 half = new Vec3(0.2f + random.nextFloat(), 0.2f + random.nextFloat(), 0.2f + random.nextFloat());
            Vec3[] a = Polytope.box(half).vertices;
            Quat turn = Quat.fromAxisAngle(new Vec3(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f).normalized(), random.nextFloat() * 6f);
            // Another box, turned, a known distance away along x with the faces apart on the way.
            Vec3[] b = Polytope.box(new Vec3(0.3f, 0.3f, 0.3f)).vertices.clone();
            float gap = 0.1f + random.nextFloat() * 2f;
            float lowestB = Float.POSITIVE_INFINITY;
            for (int i = 0; i < b.length; i++) {
                b[i] = turn.rotate(b[i]);
                lowestB = Math.min(lowestB, b[i].x());
            }
            float shift = half.x() + gap - lowestB;
            for (int i = 0; i < b.length; i++) b[i] = b[i].add(new Vec3(shift, 0f, 0f));
            // Their gap along x is exact only when the closest features face each other, so compare against the separating
            // axis bound: the true distance is at least the x gap, and at most the distance between any two corners.
            Gjk.Result r = Gjk.closest(a, b);
            assertTrue(r.distance() >= gap - 1e-3f, "trial " + trial + ": " + r.distance() + " < " + gap);
            float most = Float.POSITIVE_INFINITY;
            for (Vec3 p : a) for (Vec3 q : b) most = Math.min(most, p.distance(q));
            assertTrue(r.distance() <= most + 1e-4f, "trial " + trial + ": " + r.distance() + " > corner pair " + most);
            assertEquals(r.distance(), r.onSegment().distance(r.onSet()), 1e-4f);
        }
    }
}
