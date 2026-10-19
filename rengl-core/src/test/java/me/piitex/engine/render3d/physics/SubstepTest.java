package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubstepTest {
    private static final float DT = 1f / 60f;

    /** How far the top of a stack of heavy-on-light boxes ends up from where it began, after 3 seconds. */
    private static float stackSink(int substeps, int passes, boolean heavyTop) {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false).setSubsteps(substeps).setVelocityIterations(passes);
        RigidBody floor = new RigidBody(BodyType.STATIC).addShape(new PlaneShape());
        world.add(floor);
        RigidBody top = null;
        int n = 20;
        for (int i = 0; i < n; i++) {
            boolean last = i == n - 1;
            float mass = last && heavyTop ? 200f : 1f;
            RigidBody box = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(0.5f, 0.5f, 0.5f))).setMass(mass);
            box.setPosition(0f, 0.5f + i, 0f);
            world.add(box);
            top = box;
        }
        for (int i = 0; i < 180; i++) world.step(DT);
        return Math.abs(top.getPosition().y() - (0.5f + n - 1)) + Math.abs(top.getPosition().x()) + Math.abs(top.getPosition().z());
    }

    private static float chainReach(float load, int substeps) {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false).setSubsteps(substeps);
        RigidBody previous = null;
        for (int i = 0; i < 6; i++) {
            RigidBody link = new RigidBody(BodyType.DYNAMIC).addShape(new CapsuleShape(0.05f, 0.25f)).setMass(0.5f);
            link.setPosition(0f, 10f - 0.3f - i * 0.6f, 0f);
            world.add(link);
            world.addJoint(new BallJoint(link, previous, new Vec3(0f, 10f - i * 0.6f, 0f)));
            previous = link;
        }
        RigidBody weight = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(0.25f, 0.25f, 0.25f))).setMass(load);
        weight.setPosition(0f, 10f - 3.6f - 0.25f, 0f);
        world.add(weight);
        world.addJoint(new BallJoint(weight, previous, new Vec3(0f, 10f - 3.6f, 0f)));
        weight.applyImpulse(new Vec3(2f * load, 0f, 0f));
        float reach = 0f;
        for (int i = 0; i < 300; i++) {
            world.step(DT);
            reach = Math.max(reach, weight.getPosition().distance(new Vec3(0f, 10f, 0f)));
        }
        return reach;
    }

    @Test
    void aHeavyTopOnATallStackNeedsSubsteps() {
        assertTrue(stackSink(1, 10, true) > 5f, "without substeps the 200 kg box brings the stack down");
        assertTrue(stackSink(4, 10, true) < 1f, "with 4 the stack holds, off by " + stackSink(4, 10, true));
        assertTrue(stackSink(8, 10, true) < stackSink(4, 10, true), "and more is better");
    }

    @Test
    void anOrdinaryStackSettlesTighter() {
        assertTrue(stackSink(1, 10, false) > 0.3f);
        assertTrue(stackSink(4, 10, false) < 0.05f);
    }

    @Test
    void aHeavyLoadOnALightChainHoldsInAnyStepSize() {
        assertEquals(3.85f, chainReach(100f, 1), 0.03f);
        assertEquals(3.85f, chainReach(100f, 8), 0.05f);
        assertEquals(3.85f, chainReach(10f, 4), 0.05f);
    }

    @Test
    void forcesTimeAndEventsStayPerWholeStep() {
        for (int substeps : new int[]{1, 4}) {
            PhysicsWorld world = new PhysicsWorld().setSleeping(false).setGravity(Vec3.ZERO).setSubsteps(substeps);
            RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.5f)).setMass(2f).setLinearDamping(0f);
            world.add(ball);
            RigidBody zone = new RigidBody(BodyType.STATIC).addShape(new SphereShape(1f)).setTrigger(true);
            zone.setPosition(1.8f, 0f, 0f);
            world.add(zone);
            int[] entered = {0};
            world.addContactListener(new ContactListener() {
                @Override
                public void triggerEnter(RigidBody trigger, RigidBody other) {
                    entered[0]++;
                }
            });
            world.setStepHandler((w, dt) -> ball.applyForce(new Vec3(8f, 0f, 0f)));
            for (int i = 0; i < 60; i++) world.step(DT);
            assertEquals(4f, ball.getVelocity().x(), 0.01f, "8 N on 2 kg for a second, in " + substeps + " substeps");
            assertEquals(1.0, world.getTime(), 1e-5);
            assertEquals(60, world.getStepCount());
            assertEquals(1, entered[0]);
        }
    }

    @Test
    void substepsAreClampedAndReadBack() {
        PhysicsWorld world = new PhysicsWorld();
        assertEquals(1, world.getSubsteps());
        assertEquals(1, world.setSubsteps(0).getSubsteps());
        assertEquals(32, world.setSubsteps(1000).getSubsteps());
    }
}
