package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The joints of a tree are solved exactly, all at once: a heavy load on light parts doesn't stretch them.
 */
class JointTreeTest {
    private static final float DT = 1f / 60f;

    private static RigidBody capsule(PhysicsWorld world, float x, float y, float z, float mass) {
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(new CapsuleShape(0.05f, 0.25f)).setMass(mass);
        body.setPosition(x, y, z);
        world.add(body);
        return body;
    }

    private static RigidBody box(PhysicsWorld world, Vec3 at, float half, float mass) {
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(half, half, half))).setMass(mass);
        body.setPosition(at);
        world.add(body);
        return body;
    }

    /** How far apart the joint's two anchors are. */
    private static float gap(Joint joint) {
        Vec3 a = Joint.center(joint.a).add(Joint.offset(joint.a, joint.localAnchorA()));
        Vec3 b = Joint.center(joint.b).add(Joint.offset(joint.b, joint.localAnchorB()));
        return a.distance(b);
    }

    /** The widest any joint has opened; a slider is meant to slide, so it is measured by {@link #offRail}. */
    private static float worstGap(PhysicsWorld world) {
        float worst = 0f;
        for (Joint joint : world.joints()) worst = Math.max(worst, joint instanceof SliderJoint slider ? offRail(slider) : gap(joint));
        return worst;
    }

    /** A chain of {@code links} light links hung from (0, 10, 0), with a weight of {@code load} kilograms at its end. */
    private static RigidBody hang(PhysicsWorld world, int links, float load) {
        RigidBody previous = null;
        for (int i = 0; i < links; i++) {
            RigidBody link = capsule(world, 0f, 10f - 0.3f - i * 0.6f, 0f, 0.5f);
            world.addJoint(new BallJoint(link, previous, new Vec3(0f, 10f - i * 0.6f, 0f)));
            previous = link;
        }
        RigidBody weight = box(world, new Vec3(0f, 10f - links * 0.6f - 0.25f, 0f), 0.25f, load);
        world.addJoint(new BallJoint(weight, previous, new Vec3(0f, 10f - links * 0.6f, 0f)));
        return weight;
    }

    @Test
    void aLongChainWithAHeavyEndHangsStill() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody weight = hang(world, 20, 200f); // 400:1
        for (int i = 0; i < 180; i++) world.step(DT);
        assertEquals(10f - 12f - 0.25f, weight.getPosition().y(), 0.02f);
        assertTrue(worstGap(world) < 0.002f, "the chain came apart by " + worstGap(world));
    }

    @Test
    void theTopJointCarriesTheWholeLoad() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        hang(world, 6, 100f);
        for (int i = 0; i < 120; i++) world.step(DT);
        Joint top = world.joints().getFirst();
        float expected = (100f + 6 * 0.5f) * 9.81f;
        assertEquals(expected, top.getReactionForce().length(), expected * 0.02f);
    }

    @Test
    void aSwingingHeavyEndKeepsEveryJointClosed() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody weight = hang(world, 6, 100f);
        weight.applyImpulse(new Vec3(300f, 0f, 150f));
        float worst = 0f;
        for (int i = 0; i < 300; i++) {
            world.step(DT);
            worst = Math.max(worst, worstGap(world));
        }
        assertTrue(worst < 0.01f, "a joint opened by " + worst);
    }

    @Test
    void aBranchingTreeHoldsItsShape() {
        // A body hung by a hinge, with an arm out of each side ending in a heavy hand, and a head on a ball joint.
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody torso = box(world, new Vec3(0f, 8f, 0f), 0.3f, 20f);
        world.addJoint(new HingeJoint(torso, null, new Vec3(0f, 8.3f, 0f), Vec3.X));
        for (float side : new float[]{-1f, 1f}) {
            RigidBody upper = capsule(world, side * 0.8f, 8.2f, 0f, 1f);
            upper.setRotation(Quat.fromAxisAngle(Vec3.Z, (float) Math.PI / 2f));
            world.addJoint(new BallJoint(upper, torso, new Vec3(side * 0.3f, 8.2f, 0f)));
            RigidBody lower = capsule(world, side * 1.4f, 8.2f, 0f, 1f);
            lower.setRotation(Quat.fromAxisAngle(Vec3.Z, (float) Math.PI / 2f));
            world.addJoint(new BallJoint(lower, upper, new Vec3(side * 1.1f, 8.2f, 0f)));
            RigidBody hand = box(world, new Vec3(side * 1.75f, 8.2f, 0f), 0.15f, 60f);
            world.addJoint(new FixedJoint(hand, lower));
        }
        RigidBody head = box(world, new Vec3(0f, 8.65f, 0f), 0.15f, 4f);
        world.addJoint(new BallJoint(head, torso, new Vec3(0f, 8.4f, 0f)));
        torso.applyImpulse(new Vec3(80f, 0f, 40f));
        float worst = 0f;
        for (int i = 0; i < 240; i++) {
            world.step(DT);
            worst = Math.max(worst, worstGap(world));
        }
        assertTrue(worst < 0.01f, "a joint opened by " + worst);
    }

    @Test
    void aWeldedPairKeepsItsAngle() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody bar = capsule(world, 0f, 9.7f, 0f, 1f);
        world.addJoint(new HingeJoint(bar, null, new Vec3(0f, 10f, 0f), Vec3.Z));
        RigidBody block = box(world, new Vec3(0f, 9.15f, 0f), 0.25f, 100f);
        FixedJoint weld = new FixedJoint(block, bar);
        world.addJoint(weld);
        block.applyImpulse(new Vec3(150f, 0f, 0f));
        float turn = 0f;
        for (int i = 0; i < 300; i++) {
            world.step(DT);
            Quat relative = bar.getRotation().conjugate().mul(block.getRotation());
            turn = Math.max(turn, 2f * (float) Math.acos(Math.min(1f, Math.abs(relative.w()))));
            assertTrue(gap(weld) < 0.01f, "the weld opened by " + gap(weld));
        }
        assertTrue(turn < 0.03f, "the weld twisted by " + turn);
    }

    @Test
    void stopsAndMotorsStillWorkInsideATree() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody arm = capsule(world, 0.3f, 10f, 0f, 1f);
        arm.setRotation(Quat.fromAxisAngle(Vec3.Z, (float) Math.PI / 2f));
        HingeJoint shoulder = new HingeJoint(arm, null, new Vec3(0f, 10f, 0f), Vec3.Z).setLimits(-0.5f, 0.5f);
        world.addJoint(shoulder);
        RigidBody forearm = capsule(world, 0.9f, 10f, 0f, 1f);
        forearm.setRotation(Quat.fromAxisAngle(Vec3.Z, (float) Math.PI / 2f));
        world.addJoint(new BallJoint(forearm, arm, new Vec3(0.6f, 10f, 0f)));
        RigidBody hand = box(world, new Vec3(1.35f, 10f, 0f), 0.2f, 80f);
        world.addJoint(new FixedJoint(hand, forearm));
        for (int i = 0; i < 300; i++) {
            world.step(DT);
            assertTrue(shoulder.getAngle() > -0.6f, "the lower stop gave way: " + shoulder.getAngle());
        }
        assertEquals(-0.5f, shoulder.getAngle(), 0.1f, "the weight drops the arm against its stop");
        assertTrue(worstGap(world) < 0.01f);
    }

    @Test
    void aLoopOfJointsStillHoldsAndSoDoRopesBesideATree() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        // A square of four bars joined at the corners, hung by one corner: joints that close the loop are left to the
        // pass-at-a-time solver.
        Vec3[] corner = {new Vec3(0f, 10f, 0f), new Vec3(1f, 10f, 0f), new Vec3(1f, 9f, 0f), new Vec3(0f, 9f, 0f)};
        List<RigidBody> bars = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Vec3 a = corner[i], b = corner[(i + 1) % 4];
            RigidBody bar = box(world, a.add(b).mul(0.5f), 0.1f, 2f);
            bars.add(bar);
        }
        for (int i = 0; i < 4; i++) world.addJoint(new BallJoint(bars.get(i), bars.get((i + 3) % 4), corner[i]));
        world.addJoint(new BallJoint(bars.get(0), null, corner[0]));
        RigidBody pendant = box(world, new Vec3(0.5f, 8f, 0f), 0.2f, 30f);
        world.addJoint(new DistanceJoint(pendant, new Vec3(0.5f, 8.2f, 0f), bars.get(2), new Vec3(1f, 9f, 0f)));
        for (int i = 0; i < 240; i++) world.step(DT);
        for (Joint joint : world.joints()) {
            if (joint instanceof BallJoint) assertTrue(gap(joint) < 0.02f, "a corner opened by " + gap(joint));
        }
    }

    /** How far B's anchor has slid sideways off A's rail: the part of the gap that isn't along the axis. */
    private static float offRail(SliderJoint slider) {
        Vec3 gap = Joint.center(slider.b).add(Joint.offset(slider.b, slider.localAnchorB()))
                .sub(Joint.center(slider.a).add(Joint.offset(slider.a, slider.localAnchorA())));
        return gap.sub(slider.getAxis().mul(gap.dot(slider.getAxis()))).length();
    }

    @Test
    void aHeavyLoadHangingFromALightCarriageDoesNotPullItOffItsRail() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody carriage = box(world, new Vec3(0f, 10f, 0f), 0.1f, 0.2f);
        SliderJoint rail = new SliderJoint(carriage, null, new Vec3(0f, 10f, 0f), Vec3.X);
        world.addJoint(rail);
        RigidBody weight = box(world, new Vec3(0f, 9.4f, 0f), 0.25f, 400f); // 2000:1
        world.addJoint(new BallJoint(weight, carriage, new Vec3(0f, 9.9f, 0f)));
        for (int i = 0; i < 180; i++) world.step(DT);
        assertEquals(10f, carriage.getPosition().y(), 0.002f);
        assertTrue(offRail(rail) < 0.002f, "the carriage left its rail by " + offRail(rail));
        assertTrue(worstGap(world) < 0.002f, "a joint opened by " + worstGap(world));
        float expected = 400.2f * 9.81f;
        assertEquals(expected, rail.getReactionForce().length(), expected * 0.02f);
    }

    @Test
    void aSwingingLoadOnASliderKeepsItTurnedTheSameWay() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody carriage = box(world, new Vec3(0f, 10f, 0f), 0.1f, 0.2f);
        world.addJoint(new SliderJoint(carriage, null, new Vec3(0f, 10f, 0f), Vec3.X));
        RigidBody weight = box(world, new Vec3(0f, 9f, 0f), 0.25f, 400f);
        world.addJoint(new BallJoint(weight, carriage, new Vec3(0f, 10f, 0f)));
        weight.applyImpulse(new Vec3(800f, 0f, 400f));
        float turn = 0f;
        for (int i = 0; i < 240; i++) {
            world.step(DT);
            turn = Math.max(turn, 2f * (float) Math.acos(Math.min(1f, Math.abs(carriage.getRotation().w()))));
        }
        assertTrue(turn < 0.05f, "the carriage turned by " + turn);
        assertTrue(worstGap(world) < 0.02f, "a joint opened by " + worstGap(world));
    }

    @Test
    void aTelescopingRailBetweenTwoBodiesHoldsAHeavyLoad() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody outer = box(world, new Vec3(0f, 10f, 0f), 0.1f, 1f);
        SliderJoint first = new SliderJoint(outer, null, new Vec3(0f, 10f, 0f), Vec3.X);
        world.addJoint(first);
        RigidBody inner = box(world, new Vec3(0f, 10f, 0f), 0.08f, 0.2f);
        SliderJoint second = new SliderJoint(inner, outer, new Vec3(0f, 10f, 0f), Vec3.X);
        world.addJoint(second);
        RigidBody weight = box(world, new Vec3(0f, 9.4f, 0f), 0.25f, 400f);
        world.addJoint(new BallJoint(weight, inner, new Vec3(0f, 9.9f, 0f)));
        for (int i = 0; i < 180; i++) world.step(DT);
        assertEquals(10f, inner.getPosition().y(), 0.003f);
        assertTrue(offRail(first) < 0.002f && offRail(second) < 0.002f,
                "off the rails by " + offRail(first) + " and " + offRail(second));
    }

    @Test
    void aChainHangingFromASliderHoldsItsLength() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody carriage = box(world, new Vec3(0f, 10f, 0f), 0.1f, 0.2f);
        SliderJoint rail = new SliderJoint(carriage, null, new Vec3(0f, 10f, 0f), Vec3.X);
        world.addJoint(rail);
        RigidBody previous = carriage;
        for (int i = 0; i < 10; i++) {
            RigidBody link = capsule(world, 0f, 10f - 0.3f - i * 0.6f, 0f, 0.5f);
            world.addJoint(new BallJoint(link, previous, new Vec3(0f, 10f - i * 0.6f, 0f)));
            previous = link;
        }
        RigidBody weight = box(world, new Vec3(0f, 10f - 6f - 0.25f, 0f), 0.25f, 200f);
        world.addJoint(new BallJoint(weight, previous, new Vec3(0f, 10f - 6f, 0f)));
        weight.applyImpulse(new Vec3(100f, 0f, 50f));
        float worst = 0f;
        for (int i = 0; i < 240; i++) {
            world.step(DT);
            worst = Math.max(worst, worstGap(world));
        }
        assertTrue(worst < 0.01f, "a joint opened by " + worst);
        assertEquals(10f, carriage.getPosition().y(), 0.01f);
    }

    /** A light arm out along +x from a hinge at (0, 10, 0) on the world, with a weight welded on at its tip. */
    private static HingeJoint heavyArm(PhysicsWorld world, float load, float lower, float upper) {
        RigidBody arm = capsule(world, 0.5f, 10f, 0f, 0.2f);
        arm.setRotation(Quat.fromAxisAngle(Vec3.Z, (float) Math.PI / 2f));
        HingeJoint hinge = new HingeJoint(arm, null, new Vec3(0f, 10f, 0f), Vec3.Z);
        if (lower < upper) hinge.setLimits(lower, upper);
        world.addJoint(hinge);
        RigidBody weight = box(world, new Vec3(1.25f, 10f, 0f), 0.25f, load);
        world.addJoint(new FixedJoint(weight, arm));
        return hinge;
    }

    private static final float ARM_TORQUE = (400f * 1.25f + 0.2f * 0.5f) * 9.81f;

    @Test
    void aStopHoldsAHeavyLoadOnALightArm() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        HingeJoint hinge = heavyArm(world, 400f, 0f, 1f); // 2000:1, resting on its lower stop
        float lowest = 0f;
        for (int i = 0; i < 180; i++) {
            world.step(DT);
            lowest = Math.min(lowest, hinge.getAngle());
        }
        assertTrue(lowest > -0.01f, "the stop gave way by " + lowest + " rad");
        assertEquals(ARM_TORQUE, hinge.getReactionTorque().length(), ARM_TORQUE * 0.03f);
    }

    @Test
    void aMotorHoldsAHeavyLoadOnALightArm() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        HingeJoint hinge = heavyArm(world, 400f, 0f, 0f).setMotor(0f, 10000f);
        float lowest = 0f;
        for (int i = 0; i < 180; i++) {
            world.step(DT);
            lowest = Math.min(lowest, hinge.getAngle());
        }
        assertTrue(lowest > -0.01f, "the motor gave way by " + lowest + " rad");
        assertEquals(ARM_TORQUE, hinge.getMotorTorque(), ARM_TORQUE * 0.03f);
    }

    @Test
    void aMotorThatIsTooWeakPushesAsHardAsItCanAndTheLoadSinks() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        HingeJoint hinge = heavyArm(world, 400f, 0f, 0f).setMotor(0f, 1000f);
        for (int i = 0; i < 60; i++) world.step(DT);
        assertTrue(hinge.getAngle() < -0.3f, "the arm only sank to " + hinge.getAngle());
        assertEquals(1000f, hinge.getMotorTorque(), 20f);
    }

    @Test
    void aMotorPushingIntoAStopLeavesTheArmAtTheStopAndLetsGoWhenReversed() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        HingeJoint hinge = heavyArm(world, 40f, -1f, 0.5f).setMotor(1f, 5000f);
        float highest = 0f;
        for (int i = 0; i < 240; i++) {
            world.step(DT);
            highest = Math.max(highest, hinge.getAngle());
        }
        assertTrue(highest < 0.52f, "the arm went through its stop to " + highest);
        assertEquals(0.5f, hinge.getAngle(), 0.02f);
        assertEquals(5000f, hinge.getMotorTorque(), 100f, "the motor pushes as hard as it can against the stop");
        hinge.setMotor(-1f, 5000f);
        for (int i = 0; i < 60; i++) world.step(DT);
        assertTrue(hinge.getAngle() < 0.1f, "the arm stayed at the stop: " + hinge.getAngle());
        assertEquals(-1f, hinge.getAngularSpeed(), 0.05f);
    }

    @Test
    void aSliderStopAndMotorHoldAHeavyLoad() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody carriage = box(world, new Vec3(0f, 10f, 0f), 0.1f, 0.2f);
        SliderJoint lift = new SliderJoint(carriage, null, new Vec3(0f, 10f, 0f), Vec3.UP).setLimits(-1f, 1f).setMotor(0f, 10000f);
        world.addJoint(lift);
        RigidBody weight = box(world, new Vec3(0f, 9.5f, 0f), 0.25f, 400f);
        world.addJoint(new FixedJoint(weight, carriage));
        for (int i = 0; i < 180; i++) world.step(DT);
        assertEquals(10f, carriage.getPosition().y(), 0.01f);
        assertEquals(400.2f * 9.81f, lift.getMotorForce(), 400.2f * 9.81f * 0.03f);
        // With the motor off the weight hangs from the lower stop alone.
        lift.clearMotor();
        for (int i = 0; i < 120; i++) world.step(DT);
        assertEquals(9f, carriage.getPosition().y(), 0.02f);
        assertEquals(400.2f * 9.81f, lift.getReactionForce().length(), 400.2f * 9.81f * 0.03f);
    }

    @Test
    void aBallJointsTwistStopHoldsAHeavyLoad() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody arm = capsule(world, 0.5f, 10f, 0f, 0.2f);
        arm.setRotation(Quat.fromAxisAngle(Vec3.Z, (float) Math.PI / 2f));
        BallJoint shoulder = new BallJoint(arm, null, new Vec3(0f, 10f, 0f), Vec3.X).setTwistLimit(-0.2f, 0.2f);
        world.addJoint(shoulder);
        // The weight hangs off to the side of the arm's axis, so it twists the arm until the stop catches it.
        RigidBody weight = box(world, new Vec3(0.5f, 9.7f, 0f), 0.2f, 100f);
        world.addJoint(new FixedJoint(weight, arm));
        for (int i = 0; i < 240; i++) world.step(DT);
        assertTrue(Math.abs(shoulder.getTwist()) < 0.25f, "the twist stop gave way: " + shoulder.getTwist());
    }
}
