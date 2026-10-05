package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JointTest {
    private static final float DT = 1f / 60f;
    private static final float G = 9.81f;

    private static void run(PhysicsWorld world, float seconds) {
        int steps = Math.round(seconds / DT);
        for (int i = 0; i < steps; i++) world.step(DT);
    }

    private static RigidBody ball(PhysicsWorld world, float x, float y, float z, float radius, float mass) {
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(radius)).setMass(mass);
        body.setPosition(x, y, z);
        world.add(body);
        return body;
    }

    private static RigidBody box(PhysicsWorld world, Vec3 at, Vec3 half, float mass) {
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(half)).setMass(mass);
        body.setPosition(at);
        world.add(body);
        return body;
    }

    private static RigidBody floor(PhysicsWorld world) {
        RigidBody floor = new RigidBody(BodyType.STATIC).addShape(new PlaneShape());
        world.add(floor);
        return floor;
    }

    @Test
    void pendulumKeepsItsLengthAndSwingsAtTheRightRate() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        float length = 2f;
        RigidBody bob = ball(world, length, 5f, 0f, 0.05f, 1f).setAngularDamping(0f);
        Vec3 pivot = new Vec3(0f, 5f, 0f);
        world.addJoint(new BallJoint(bob, null, pivot));
        float worst = 0f, fastest = 0f;
        for (int i = 0; i < 600; i++) {
            world.step(DT);
            worst = Math.max(worst, Math.abs(bob.getCenterOfMass().distance(pivot) - length));
            fastest = Math.max(fastest, bob.getVelocity().length());
        }
        assertTrue(worst < 0.002f, "the rope stretched by " + worst);
        // Dropped from level, it swings through the bottom at sqrt(2gL), less the little lost to the solver.
        float ideal = (float) Math.sqrt(2 * G * length);
        assertEquals(ideal, fastest, ideal * 0.03f);

        // A small swing takes 2 pi sqrt(L / g).
        PhysicsWorld small = new PhysicsWorld().setSleeping(false);
        RigidBody b2 = ball(small, (float) Math.sin(0.1) * length, 5f - (float) Math.cos(0.1) * length, 0f, 0.01f, 1f).setAngularDamping(0f);
        small.addJoint(new BallJoint(b2, null, pivot));
        float lastX = b2.getPosition().x();
        List<Float> crossings = new ArrayList<>();
        for (int i = 0; i < 900; i++) {
            small.step(DT);
            float x = b2.getPosition().x();
            if (lastX > 0f && x <= 0f) crossings.add((i + lastX / (lastX - x)) * DT);
            lastX = x;
        }
        assertTrue(crossings.size() >= 3);
        float period = (crossings.get(crossings.size() - 1) - crossings.get(0)) / (crossings.size() - 1);
        assertEquals(2 * Math.PI * Math.sqrt(length / G), period, 0.02);
    }

    @Test
    void chainHangsWithoutStretchingAndSleepsAsOne() {
        PhysicsWorld world = new PhysicsWorld();
        List<RigidBody> links = new ArrayList<>();
        RigidBody previous = null;
        for (int i = 0; i < 12; i++) {
            // Laid out sideways, so the chain swings down and has to stay together on the way.
            RigidBody link = box(world, new Vec3(0.2f + i * 0.4f, 10f, 0f), new Vec3(0.2f, 0.05f, 0.05f), 1f);
            link.setLinearDamping(0.3f); // some air, so it settles
            Vec3 joint = new Vec3(i * 0.4f, 10f, 0f);
            world.addJoint(new BallJoint(link, previous, joint));
            links.add(link);
            previous = link;
        }
        float worst = 0f;
        for (int s = 0; s < 600; s++) {
            world.step(DT);
            for (Joint joint : world.joints()) {
                BallJoint j = (BallJoint) joint;
                Vec3 onB = j.getBodyB() == null ? new Vec3(0f, 10f, 0f) : j.getBodyB().getPosition().add(j.getBodyB().getRotation().rotate(new Vec3(0.2f, 0f, 0f)));
                worst = Math.max(worst, j.getAnchor().distance(onB));
            }
        }
        assertTrue(worst < 0.01f, "a link came " + worst + " m loose");
        run(world, 40f);
        RigidBody last = links.get(links.size() - 1);
        assertTrue(last.getPosition().y() < 10f - 4.5f, "the chain should hang down, the end is at " + last.getPosition());
        for (RigidBody link : links) assertTrue(link.isSleeping(), "the hanging chain should sleep");
        links.get(0).applyImpulse(new Vec3(1f, 0f, 0f));
        world.step(DT);
        for (RigidBody link : links) assertFalse(link.isSleeping(), "pushing one link wakes the whole chain");
    }

    @Test
    void hingeTurnsOnlyAroundItsAxisAndStopsAtItsLimits() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        // A door 1 m wide, hinged along its left edge.
        RigidBody door = box(world, new Vec3(0.5f, 1f, 0f), new Vec3(0.5f, 1f, 0.05f), 20f);
        Vec3 hinge = new Vec3(0f, 1f, 0f);
        HingeJoint joint = new HingeJoint(door, null, hinge, Vec3.UP);
        world.addJoint(joint);
        door.applyImpulseAtPoint(new Vec3(0f, 0f, -5f), new Vec3(1f, 1.5f, 0f)); // pushed at the top corner
        door.applyImpulse(new Vec3(3f, 4f, 1f)); // and shoved every way, which the hinge must take
        run(world, 0.5f);
        assertTrue(joint.getAngle() > 0.2f, "the door should swing open, angle " + joint.getAngle());
        Vec3 up = door.getRotation().rotate(Vec3.UP);
        assertEquals(1f, up.y(), 1e-3f, "the door tilted: " + up);
        assertTrue(door.getPosition().add(door.getRotation().rotate(new Vec3(-0.5f, 0f, 0f))).distance(hinge) < 0.005f);

        joint.setLimits(-0.5f, 0.5f);
        door.setAngularVelocity(new Vec3(0f, 6f, 0f));
        float most = 0f;
        for (int i = 0; i < 120; i++) {
            world.step(DT);
            most = Math.max(most, joint.getAngle());
        }
        assertTrue(most < 0.5f + 0.02f, "the door went past its stop to " + most);
        assertTrue(most > 0.45f);
        door.setAngularVelocity(new Vec3(0f, -6f, 0f));
        run(world, 1f);
        assertEquals(-0.5f, joint.getAngle(), 0.02f);
    }

    @Test
    void hingeLimitHoldsAgainstGravity() {
        PhysicsWorld world = new PhysicsWorld();
        // A lever sticking out sideways, hinged around Z, its stop holding it level.
        RigidBody lever = box(world, new Vec3(1f, 3f, 0f), new Vec3(1f, 0.05f, 0.05f), 5f);
        HingeJoint joint = new HingeJoint(lever, null, new Vec3(0f, 3f, 0f), Vec3.Z).setLimits(0f, 1f);
        world.addJoint(joint);
        run(world, 2f);
        assertEquals(0f, joint.getAngle(), 0.01f);
        assertEquals(3f, lever.getPosition().y(), 0.01f);
        // The stop takes the lever's weight times half its length.
        assertEquals(5f * G * 1f, joint.getReactionTorque().length(), 5f * G * 0.05f);
    }

    @Test
    void hingeMotorSpinsAWheelAtItsSpeed() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody wheel = new RigidBody(BodyType.DYNAMIC).addShape(new CapsuleShape(0.5f, 0f)).setMass(10f);
        wheel.setPosition(0f, 2f, 0f);
        world.add(wheel);
        HingeJoint axle = new HingeJoint(wheel, null, new Vec3(0f, 2f, 0f), Vec3.X).setMotor(3f, 50f);
        world.addJoint(axle);
        run(world, 1f);
        assertEquals(3f, axle.getAngularSpeed(), 0.01f);
        assertEquals(3f, wheel.getAngularVelocity().x(), 0.01f);
        assertEquals(0f, wheel.getVelocity().length(), 0.01f);
        // A weak motor can't hold a lopsided load: it slips.
        axle.setMotor(0f, 0.1f);
        wheel.applyTorqueImpulse(new Vec3(10f, 0f, 0f));
        world.step(DT);
        assertTrue(Math.abs(axle.getMotorTorque()) <= 0.1f + 1e-4f);
    }

    @Test
    void sliderMovesOnlyAlongItsRailAndItsMotorLifts() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        // A horizontal rail: gravity can't pull the box off it.
        RigidBody carriage = box(world, new Vec3(0f, 2f, 0f), new Vec3(0.2f, 0.2f, 0.2f), 2f);
        SliderJoint rail = new SliderJoint(carriage, null, new Vec3(0f, 2f, 0f), Vec3.X).setLimits(-1f, 1f);
        world.addJoint(rail);
        carriage.applyImpulseAtPoint(new Vec3(4f, 3f, 2f), new Vec3(0.2f, 2.2f, 0.2f));
        float most = 0f;
        for (int i = 0; i < 120; i++) {
            world.step(DT);
            most = Math.max(most, rail.getTranslation());
            assertEquals(2f, carriage.getPosition().y(), 0.005f);
            assertEquals(0f, carriage.getPosition().z(), 0.005f);
            Quat q = carriage.getRotation();
            assertTrue(Math.abs(q.w()) > 0.9999f, "the carriage turned: " + q);
        }
        assertTrue(most <= 1.01f && most > 0.98f, "the stop should catch the carriage at 1 m, got " + most);

        // An upright rail with a motor: a 50 kg lift goes up at 0.5 m/s, with the force to carry it.
        RigidBody lift = box(world, new Vec3(5f, 0.5f, 0f), new Vec3(1f, 0.1f, 1f), 50f);
        SliderJoint shaft = new SliderJoint(lift, null, new Vec3(5f, 0.5f, 0f), Vec3.UP).setLimits(0f, 3f).setMotor(0.5f, 2000f);
        world.addJoint(shaft);
        run(world, 2f);
        assertEquals(0.5f, shaft.getSpeed(), 0.01f);
        assertEquals(1.5f, lift.getPosition().y(), 0.03f);
        assertEquals(50f * G, shaft.getMotorForce(), 5f);
        run(world, 6f);
        assertEquals(3.5f, lift.getPosition().y(), 0.01f, "the lift stops at its top limit");
        // Too weak a motor lets the lift sink.
        shaft.setMotor(0.5f, 100f);
        run(world, 1f);
        assertTrue(lift.getPosition().y() < 3.4f);
    }

    @Test
    void ropeGoesSlackButNeverStretches() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        Vec3 hook = new Vec3(0f, 10f, 0f);
        // Hung on a 3 m rope but held 1 m below the hook, so it drops 2 m before the rope catches it.
        RigidBody weight = ball(world, 0f, 9f, 0f, 0.1f, 1f);
        DistanceJoint rope = new DistanceJoint(weight, weight.getPosition(), null, hook).setLength(3f).setMinLength(0f);
        world.addJoint(rope);
        run(world, 0.3f);
        assertEquals(9f - 0.5f * G * 0.3f * 0.3f, weight.getPosition().y(), 0.03f, "slack, it falls freely");
        float longest = 0f;
        for (int i = 0; i < 300; i++) {
            world.step(DT);
            longest = Math.max(longest, rope.getCurrentLength());
        }
        assertTrue(longest < 3.005f, "the rope stretched to " + longest);
        assertEquals(7f, weight.getPosition().y(), 0.01f);

        // A rod also pushes.
        RigidBody strut = ball(world, 3f, 5f, 0f, 0.1f, 1f);
        RigidBody base = new RigidBody(BodyType.STATIC);
        base.setPosition(3f, 4f, 0f);
        world.add(base);
        DistanceJoint rod = new DistanceJoint(strut, strut.getPosition(), base, base.getPosition());
        world.addJoint(rod);
        strut.applyImpulse(new Vec3(0f, -5f, 0f));
        run(world, 0.5f);
        assertEquals(1f, rod.getCurrentLength(), 0.005f);
    }

    @Test
    void springBouncesAtItsFrequency() {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false).setGravity(Vec3.ZERO);
        RigidBody mass = ball(world, 0f, 0f, 1.5f, 0.1f, 3f);
        DistanceJoint spring = new DistanceJoint(mass, mass.getPosition(), null, Vec3.ZERO).setLength(1f).setSpring(2f, 0f);
        world.addJoint(spring);
        float last = mass.getPosition().z() - 1f;
        List<Float> crossings = new ArrayList<>();
        for (int i = 0; i < 240; i++) {
            world.step(DT);
            float now = mass.getPosition().z() - 1f;
            if (last > 0f && now <= 0f) crossings.add((i + last / (last - now)) * DT);
            last = now;
        }
        assertTrue(crossings.size() >= 5);
        float period = (crossings.get(crossings.size() - 1) - crossings.get(0)) / (crossings.size() - 1);
        assertEquals(0.5f, period, 0.03f);

        // Fully damped, it settles at its length without swinging through.
        PhysicsWorld calm = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody m2 = ball(calm, 0f, 0f, 1.5f, 0.1f, 3f);
        calm.addJoint(new DistanceJoint(m2, m2.getPosition(), null, Vec3.ZERO).setLength(1f).setSpring(2f, 1f));
        float lowest = Float.MAX_VALUE;
        for (int i = 0; i < 180; i++) {
            calm.step(DT);
            lowest = Math.min(lowest, m2.getPosition().z());
        }
        assertTrue(lowest > 0.99f, "it overshot to " + lowest);
        assertEquals(1f, m2.getPosition().z(), 0.01f);
    }

    @Test
    void fixedJointWeldsBodiesThatFallTogether() {
        PhysicsWorld world = new PhysicsWorld();
        floor(world);
        RigidBody a = box(world, new Vec3(0f, 2f, 0f), new Vec3(0.5f, 0.25f, 0.5f), 10f);
        RigidBody b = box(world, new Vec3(0.5f, 2.5f, 0f), new Vec3(0.25f, 0.25f, 0.25f), 2f);
        b.setRotation(Quat.fromAxisAngle(Vec3.UP, 0.3f));
        Vec3 offset = a.getRotation().conjugate().rotate(b.getPosition().sub(a.getPosition()));
        Quat relative = a.getRotation().conjugate().mul(b.getRotation());
        world.addJoint(new FixedJoint(a, b));
        a.setAngularVelocity(new Vec3(1f, 2f, 0.5f));
        run(world, 3f);
        Vec3 nowOffset = a.getRotation().conjugate().rotate(b.getPosition().sub(a.getPosition()));
        assertTrue(nowOffset.distance(offset) < 0.01f, "the weld slipped from " + offset + " to " + nowOffset);
        Quat nowRelative = a.getRotation().conjugate().mul(b.getRotation());
        assertTrue(Math.abs(nowRelative.x() * relative.x() + nowRelative.y() * relative.y() + nowRelative.z() * relative.z() + nowRelative.w() * relative.w()) > 0.9999f);
        assertTrue(a.getPosition().y() < 1f, "the welded pair should have landed: " + a.getPosition());
    }

    @Test
    void jointBreaksWhenPulledTooHard() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody lamp = ball(world, 0f, 4f, 0f, 0.2f, 10f);
        BallJoint strong = new BallJoint(lamp, null, new Vec3(0f, 4.5f, 0f));
        strong.setBreakForce(200f);
        world.addJoint(strong);
        run(world, 1f);
        assertFalse(strong.isBroken());
        assertEquals(10f * G, strong.getReactionForce().length(), 1f);
        assertEquals(4f, lamp.getPosition().y(), 0.01f);

        List<Joint> snapped = new ArrayList<>();
        strong.setBreakHandler(snapped::add);
        lamp.applyImpulse(new Vec3(0f, -20f, 0f)); // a yank of 1200 N for a step
        world.step(DT);
        assertTrue(strong.isBroken());
        assertEquals(List.of(strong), snapped);
        assertTrue(world.joints().isEmpty());
        assertNull(strong.getWorld());
        run(world, 0.5f);
        assertTrue(lamp.getPosition().y() < 3f, "a broken joint holds nothing");
    }

    @Test
    void joinedBodiesDontCollideUnlessAsked() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        // Two boxes overlapping where they're hinged together, as joined parts do.
        RigidBody a = box(world, new Vec3(0f, 0f, 0f), new Vec3(0.5f, 0.5f, 0.5f), 1f);
        RigidBody b = box(world, new Vec3(0.8f, 0f, 0f), new Vec3(0.5f, 0.5f, 0.5f), 1f);
        world.addJoint(new HingeJoint(a, b, new Vec3(0.4f, 0f, 0f), Vec3.UP));
        run(world, 0.5f);
        assertEquals(0f, a.getVelocity().length(), 1e-4f);
        assertEquals(0.8f, b.getPosition().x(), 1e-3f);
        assertTrue(world.getContacts().isEmpty());

        PhysicsWorld other = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody c = box(other, new Vec3(0f, 0f, 0f), new Vec3(0.5f, 0.5f, 0.5f), 1f);
        RigidBody d = box(other, new Vec3(0.8f, 0f, 0f), new Vec3(0.5f, 0.5f, 0.5f), 1f);
        other.addJoint(new DistanceJoint(c, c.getPosition(), d, d.getPosition()).setMinLength(0f).setMaxLength(5f).setCollideConnected(true));
        run(other, 0.5f);
        assertTrue(d.getPosition().x() - c.getPosition().x() > 0.99f, "colliding, they push apart");
    }

    @Test
    void removingABodyTakesItsJointsAndJointsNeedTheirBodiesAdded() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody a = ball(world, 0f, 2f, 0f, 0.2f, 1f);
        RigidBody b = ball(world, 1f, 2f, 0f, 0.2f, 1f);
        Joint joint = new DistanceJoint(a, a.getPosition(), b, b.getPosition());
        world.addJoint(joint);
        world.addJoint(joint);
        assertEquals(1, world.joints().size());
        world.remove(b);
        assertTrue(world.joints().isEmpty());
        assertNull(joint.getWorld());
        run(world, 0.2f);

        RigidBody loose = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.1f));
        assertThrows(IllegalStateException.class, () -> world.addJoint(new BallJoint(loose, null, Vec3.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> new BallJoint(a, a, Vec3.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new HingeJoint(a, null, Vec3.ZERO, Vec3.ZERO));
    }

    /**
     * The longest the chain reaches from its hook over five seconds of swinging, with an end weight of {@code load} kg
     * on six 0.5 kg links 0.6 m long (3.85 m in all).
     */
    private static float chainReach(float load) {
        PhysicsWorld world = new PhysicsWorld().setSleeping(false);
        RigidBody previous = null;
        for (int i = 0; i < 6; i++) {
            RigidBody link = new RigidBody(BodyType.DYNAMIC).addShape(new CapsuleShape(0.05f, 0.25f)).setMass(0.5f);
            link.setPosition(0f, 10f - 0.3f - i * 0.6f, 0f);
            world.add(link);
            world.addJoint(new BallJoint(link, previous, new Vec3(0f, 10f - i * 0.6f, 0f)));
            previous = link;
        }
        RigidBody weight = box(world, new Vec3(0f, 10f - 3.6f - 0.25f, 0f), new Vec3(0.25f, 0.25f, 0.25f), load);
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
    void heavyLoadOnALightChainHolds() {
        assertEquals(3.85f, chainReach(5f), 0.02f);
        assertEquals(3.85f, chainReach(20f), 0.15f, "40:1 stretches a little");
        float extreme = chainReach(100f);
        assertTrue(Float.isFinite(extreme) && extreme < 5f, "200:1 stretches but must hold, reached " + extreme);
    }
}
