package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BallJointLimitsTest {
    private static final float DT = 1f / 60f;

    private static RigidBody stick(PhysicsWorld world, Vec3 at, float length) {
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(length / 2, 0.05f, 0.05f))).setMass(1f);
        body.setPosition(at);
        body.setAngularDamping(0f);
        world.add(body);
        return body;
    }

    @Test
    void swingLimitHoldsALimbAtTheEdgeOfItsCone() {
        float limit = (float) Math.toRadians(40);
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody arm = stick(world, new Vec3(1f, 5f, 0f), 2f);
        BallJoint joint = new BallJoint(arm, null, new Vec3(0f, 5f, 0f), Vec3.X).setSwingLimit(limit);
        world.addJoint(joint);
        float most = 0f;
        for (int i = 0; i < 240; i++) {
            world.step(DT);
            most = Math.max(most, joint.getSwing());
        }
        assertTrue(most < limit + Math.toRadians(2), "swung " + Math.toDegrees(most) + " degrees past a 40 degree cone");
        assertEquals(limit, joint.getSwing(), (float) Math.toRadians(1.5), "it should rest on the edge of the cone");
        // The arm's centre hangs 1 m from the pivot, 40 degrees below level.
        Vec3 centre = arm.getCenterOfMass();
        assertEquals(5f - (float) Math.sin(limit), centre.y(), 0.04f);
        assertEquals((float) Math.cos(limit), centre.x(), 0.04f);
        assertEquals(1f, centre.distance(new Vec3(0f, 5f, 0f)), 0.01f, "the pivot held");
    }

    @Test
    void withoutALimitTheSameArmHangsStraightDown() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody arm = stick(world, new Vec3(1f, 5f, 0f), 2f).setAngularDamping(0.5f);
        BallJoint joint = new BallJoint(arm, null, new Vec3(0f, 5f, 0f), Vec3.X);
        world.addJoint(joint);
        for (int i = 0; i < 1200; i++) world.step(DT);
        assertEquals(Math.PI / 2, joint.getSwing(), 0.05f);
    }

    @Test
    void twistLimitStopsTurningAroundTheLimb() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false).setGravity(Vec3.ZERO);
        RigidBody arm = stick(world, new Vec3(1f, 5f, 0f), 2f);
        BallJoint joint = new BallJoint(arm, null, new Vec3(0f, 5f, 0f), Vec3.X).setTwistLimit(-0.4f, 0.6f);
        world.addJoint(joint);
        arm.setAngularVelocity(new Vec3(3f, 0f, 0f));
        float most = 0f;
        for (int i = 0; i < 120; i++) {
            world.step(DT);
            most = Math.max(most, joint.getTwist());
        }
        assertTrue(most < 0.6f + 0.03f, "twisted to " + most);
        assertEquals(0.6f, joint.getTwist(), 0.03f);
        assertTrue(Math.abs(arm.getAngularVelocity().x()) < 0.2f, "still turning at " + arm.getAngularVelocity().x());
        assertEquals(1f, arm.getCenterOfMass().distance(new Vec3(0f, 5f, 0f)), 0.01f);

        arm.setAngularVelocity(new Vec3(-5f, 0f, 0f));
        float least = 0f;
        for (int i = 0; i < 120; i++) {
            world.step(DT);
            least = Math.min(least, joint.getTwist());
        }
        assertTrue(least > -0.4f - 0.03f, "twisted to " + least);
        assertEquals(-0.4f, joint.getTwist(), 0.03f);
    }

    @Test
    void limitsWorkBetweenTwoMovingBodies() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody upper = stick(world, new Vec3(0.5f, 5f, 0f), 1f);
        world.addJoint(new BallJoint(upper, null, new Vec3(0f, 5f, 0f), Vec3.X).setSwingLimit((float) Math.toRadians(80)));
        RigidBody lower = stick(world, new Vec3(1.5f, 5f, 0f), 1f);
        BallJoint elbow = new BallJoint(lower, upper, new Vec3(1f, 5f, 0f), Vec3.X).setSwingLimit((float) Math.toRadians(30));
        world.addJoint(elbow);
        float most = 0f;
        for (int i = 0; i < 360; i++) {
            world.step(DT);
            most = Math.max(most, elbow.getSwing());
        }
        assertTrue(most < Math.toRadians(30 + 3), "the elbow bent " + Math.toDegrees(most));
        assertTrue(Math.toDegrees(most) > 27, "and it did reach the limit, at " + Math.toDegrees(most));
        assertEquals(0.5f, upper.getCenterOfMass().distance(new Vec3(0f, 5f, 0f)), 0.01f);
        assertEquals(0.5f, lower.getCenterOfMass().distance(upper.getPosition().add(upper.getRotation().rotate(new Vec3(0.5f, 0f, 0f)))), 0.02f);
    }

    @Test
    void aRagdollArmOfThreeLimbsStaysInsideEveryCone() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody previous = null;
        BallJoint[] joints = new BallJoint[3];
        for (int i = 0; i < 3; i++) {
            RigidBody limb = stick(world, new Vec3(0.5f + i, 5f, 0f), 1f);
            joints[i] = new BallJoint(limb, previous, new Vec3(i, 5f, 0f), Vec3.X)
                    .setSwingLimit((float) Math.toRadians(35)).setTwistLimit(-0.5f, 0.5f);
            world.addJoint(joints[i]);
            previous = limb;
        }
        float[] most = new float[3];
        for (int i = 0; i < 600; i++) {
            world.step(DT);
            for (int j = 0; j < 3; j++) {
                assertTrue(Float.isFinite(joints[j].getSwing()));
                most[j] = Math.max(most[j], joints[j].getSwing());
            }
        }
        for (int j = 0; j < 3; j++) assertTrue(most[j] < Math.toRadians(35 + 4), "joint " + j + " bent " + Math.toDegrees(most[j]));
    }

    @Test
    void limitsCanBeClearedAndAreChecked() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody arm = stick(world, new Vec3(1f, 5f, 0f), 2f);
        BallJoint joint = new BallJoint(arm, null, new Vec3(0f, 5f, 0f), Vec3.X).setSwingLimit(1f).setTwistLimit(-1f, 1f);
        assertTrue(joint.hasSwingLimit());
        assertTrue(joint.hasTwistLimit());
        joint.clearLimits();
        assertFalse(joint.hasSwingLimit());
        assertFalse(joint.hasTwistLimit());
        assertThrows(IllegalArgumentException.class, () -> joint.setSwingLimit(-0.1f));
        assertThrows(IllegalArgumentException.class, () -> joint.setTwistLimit(1f, -1f));
        assertThrows(IllegalArgumentException.class, () -> new BallJoint(arm, null, Vec3.ZERO, Vec3.ZERO));
    }
}
