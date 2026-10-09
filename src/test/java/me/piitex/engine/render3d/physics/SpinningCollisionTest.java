package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpinningCollisionTest {
    private static final float DT = 1f / 60f;

    /**
     * A long stick turning fast towards a thin sheet above its middle; returns the furthest the stick's tip got past the wall.
     */
    private static float tunnel(float spin, float wallAt) {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false).setGravity(Vec3.ZERO);
        RigidBody wall = new RigidBody(BodyType.STATIC).addShape(new BoxShape(new Vec3(3f, 0.02f, 3f)));
        wall.setPosition(0f, wallAt, 0f);
        world.add(wall);
        RigidBody stick = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(1f, 0.03f, 0.03f))).setMass(1f)
                .setAngularDamping(0f).setLinearDamping(0f);
        world.add(stick);
        stick.setAngularVelocity(new Vec3(0f, 0f, spin));
        float past = 0f;
        for (int i = 0; i < 120; i++) {
            world.step(DT);
            Vec3 tip = stick.getPosition().add(stick.getRotation().rotate(new Vec3(1f, 0f, 0f)));
            Vec3 other = stick.getPosition().add(stick.getRotation().rotate(new Vec3(-1f, 0f, 0f)));
            past = Math.max(past, Math.max(tip.y(), other.y()) - (wallAt + 0.02f));
        }
        return past;
    }

    @Test
    void aFastSpinningStickDoesNotPassThroughAThinWall() {
        for (float spin : new float[]{30f, 60f, 120f}) {
            float past = tunnel(spin, 0.5f);
            assertTrue(past < 0.05f, "at " + spin + " rad/s the stick's tip got " + past + " m past the wall");
        }
    }

    @Test
    void aSlowSpinHasNoExtraMargin() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false).setGravity(Vec3.ZERO);
        RigidBody stick = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(1f, 0.03f, 0.03f))).setMass(1f);
        world.add(stick);
        stick.setAngularVelocity(new Vec3(0f, 0f, 0.1f));
        world.step(DT);
        assertEquals(PhysicsWorld.CONTACT_MARGIN, stick.speculative, 1e-6f);
        stick.setAngularVelocity(new Vec3(0f, 0f, 60f));
        world.step(DT);
        assertTrue(stick.speculative > 0.9f, "a tip at 60 m/s moves a metre a step, margin " + stick.speculative);
    }

    @Test
    void boxesStillApartGetAFaceOfPointsNotOneEdgePoint() {
        // A flat stick under a wide sheet, 0.45 m apart, with a margin big enough to reach: the face and the crossing
        // edges are equally far apart, and the face (4 points, one at each end of the stick) must win.
        Manifold m = new Manifold();
        Pose stick = new Pose(Vec3.ZERO, me.piitex.engine.render3d.math.Quat.IDENTITY);
        Pose sheet = new Pose(new Vec3(0f, 0.5f, 0f), me.piitex.engine.render3d.math.Quat.IDENTITY);
        Narrowphase.collide(new BoxShape(new Vec3(1f, 0.03f, 0.03f)), stick, new BoxShape(new Vec3(3f, 0.02f, 3f)), sheet, 1f, m);
        assertEquals(4, m.count);
        float far = 0f;
        for (int i = 0; i < m.count; i++) far = Math.max(far, Math.abs(m.points[i].x()));
        assertTrue(far > 0.9f, "the points should reach the stick's ends, the furthest is at " + far);
    }
}
