package me.piitex.engine.physx;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.physics.BallJoint;
import me.piitex.engine.render3d.physics.BodyType;
import me.piitex.engine.render3d.physics.BoxShape;
import me.piitex.engine.render3d.physics.CapsuleShape;
import me.piitex.engine.render3d.physics.CharacterController;
import me.piitex.engine.render3d.physics.Contact;
import me.piitex.engine.render3d.physics.ContactListener;
import me.piitex.engine.render3d.physics.ConvexHullShape;
import me.piitex.engine.render3d.physics.DistanceJoint;
import me.piitex.engine.render3d.physics.FixedJoint;
import me.piitex.engine.render3d.physics.HingeJoint;
import me.piitex.engine.render3d.physics.Joint;
import me.piitex.engine.render3d.physics.MeshShape;
import me.piitex.engine.render3d.physics.PhysicsMaterial;
import me.piitex.engine.render3d.physics.PhysicsWorld;
import me.piitex.engine.render3d.physics.PlaneShape;
import me.piitex.engine.render3d.physics.RaycastHit;
import me.piitex.engine.render3d.physics.RigidBody;
import me.piitex.engine.render3d.physics.SliderJoint;
import me.piitex.engine.render3d.physics.SphereShape;
import me.piitex.engine.render3d.scene.Node3D;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same {@link PhysicsWorld} calls a game makes, with PhysX doing the steps.
 */
class PhysXBackendTest {
    private static final float STEP = 1f / 60f;
    private PhysicsWorld world;

    private PhysicsWorld world() {
        world = new PhysicsWorld().setBackend(new PhysXBackend());
        return world;
    }

    @AfterEach
    void close() {
        if (world != null) world.close();
        world = null;
        PhysX.uninstall();
    }

    private static RigidBody floor(PhysicsWorld world) {
        RigidBody floor = new RigidBody(BodyType.STATIC).addShape(new PlaneShape());
        world.add(floor);
        return floor;
    }

    private static RigidBody box(PhysicsWorld world, float x, float y, float z, float half) {
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(half, half, half));
        body.setPosition(x, y, z);
        world.add(body);
        return body;
    }

    private static void run(PhysicsWorld world, float seconds) {
        for (int i = 0, n = Math.round(seconds / STEP); i < n; i++) world.step(STEP);
    }

    @Test
    void physXLoadsAndNamesItself() {
        assertTrue(PhysX.isAvailable(), () -> String.valueOf(PhysX.getFailure()));
        assertTrue(world().getBackend().name().startsWith("PhysX 5"), world.getBackend().name());
    }

    @Test
    void aBoxFallsOntoThePlaneRestsAndSleeps() {
        PhysicsWorld world = world();
        floor(world);
        RigidBody box = box(world, 0f, 3f, 0f, 0.5f);
        world.step(STEP);
        assertTrue(box.getPosition().y() < 3f && box.getVelocity().y() < 0f, "it started falling: " + box.getPosition());
        run(world, 4f);
        assertEquals(0.5f, box.getPosition().y(), 0.02f);
        assertEquals(0f, box.getPosition().x(), 0.01f);
        assertTrue(box.isSleeping(), "a resting box falls asleep");
        assertEquals(Vec3.ZERO, box.getVelocity());
        // Pushing it wakes it and moves it.
        box.applyImpulse(new Vec3(3f, 0f, 0f));
        assertFalse(box.isSleeping());
        run(world, 0.25f);
        assertTrue(box.getPosition().x() > 0.1f, "pushed along: " + box.getPosition());
    }

    @Test
    void aStackOfTenBoxesStands() {
        PhysicsWorld world = world();
        floor(world);
        List<RigidBody> stack = new ArrayList<>();
        for (int i = 0; i < 10; i++) stack.add(box(world, 0f, 0.5f + i, 0f, 0.5f));
        run(world, 5f);
        for (int i = 0; i < 10; i++) {
            Vec3 p = stack.get(i).getPosition();
            assertEquals(0.5f + i, p.y(), 0.05f, "box " + i);
            assertEquals(0f, p.x(), 0.05f, "box " + i);
            assertEquals(0f, p.z(), 0.05f, "box " + i);
        }
    }

    @Test
    void teleportingAndSettingVelocityReachPhysX() {
        PhysicsWorld world = world().setGravity(Vec3.ZERO);
        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.5f));
        world.add(ball);
        ball.setLinearDamping(0f);
        ball.setVelocity(2f, 0f, 0f);
        run(world, 1f);
        assertEquals(2f, ball.getPosition().x(), 0.02f);
        ball.setPosition(10f, 5f, 0f);
        ball.setVelocity(0f, -1f, 0f);
        run(world, 1f);
        assertEquals(10f, ball.getPosition().x(), 0.02f);
        assertEquals(4f, ball.getPosition().y(), 0.02f);
        ball.setVelocity(Vec3.ZERO);
        ball.applyForce(new Vec3(0f, 0f, 60f)); // one step of 60 N on 1 kg is 1 m/s
        world.step(STEP);
        assertEquals(1f, ball.getVelocity().z(), 0.01f);
        world.step(STEP);
        assertEquals(1f, ball.getVelocity().z(), 0.01f, "the force lasted one step");
    }

    @Test
    void gravityScaleLocksAndMassCarryOver() {
        PhysicsWorld world = world();
        RigidBody floating = box(world, 0f, 5f, 0f, 0.5f).setGravityScale(0f);
        RigidBody heavy = box(world, 3f, 5f, 0f, 0.5f).setGravityScale(2f);
        RigidBody rail = box(world, 6f, 5f, 0f, 0.5f).lockPosition(false, true, false);
        RigidBody normal = box(world, 9f, 5f, 0f, 0.5f);
        for (RigidBody b : List.of(floating, heavy, rail, normal)) b.setLinearDamping(0f);
        run(world, 0.5f);
        assertEquals(5f, floating.getPosition().y(), 1e-3f);
        assertEquals(5f, rail.getPosition().y(), 1e-3f);
        float fell = 5f - normal.getPosition().y();
        assertEquals(1.25f, fell, 0.1f);
        assertEquals(2f * fell, 5f - heavy.getPosition().y(), 0.1f);

        // A heavier ball loses less speed to the same impulse.
        PhysicsWorld still = world;
        still.setGravity(Vec3.ZERO);
        RigidBody light = box(still, 0f, 20f, 0f, 0.5f), massive = box(still, 5f, 20f, 0f, 0.5f).setMass(10f);
        light.applyImpulse(new Vec3(0f, 0f, 10f));
        massive.applyImpulse(new Vec3(0f, 0f, 10f));
        still.step(STEP);
        assertEquals(10f, light.getVelocity().z(), 0.2f);
        assertEquals(1f, massive.getVelocity().z(), 0.05f);
    }

    @Test
    void contactsBeginStayAndEnd() {
        PhysicsWorld world = world();
        RigidBody floor = floor(world);
        RigidBody box = box(world, 0f, 1f, 0f, 0.5f);
        List<String> heard = new ArrayList<>();
        Contact[] first = new Contact[1];
        world.addContactListener(new ContactListener() {
            @Override
            public void contactBegin(Contact contact) {
                heard.add("begin");
                first[0] = contact;
            }

            @Override
            public void contactStay(Contact contact) {
                if (!heard.contains("stay")) heard.add("stay");
            }

            @Override
            public void contactEnd(RigidBody a, RigidBody b) {
                heard.add("end");
            }
        });
        box.setSleepingAllowed(false);
        run(world, 1.5f);
        assertEquals(List.of("begin", "stay"), heard);
        assertEquals(floor, first[0].getBodyA());
        assertEquals(box, first[0].getBodyB());
        assertTrue(first[0].getNormal().y() > 0.99f, "from the floor up to the box: " + first[0].getNormal());
        assertFalse(first[0].getPoints().isEmpty());
        assertEquals(1, world.getContacts().size());
        assertTrue(world.getContacts().getFirst().getImpulse() > 0f, "the floor pushes back");
        box.setPosition(0f, 10f, 0f);
        box.setVelocity(0f, 5f, 0f);
        run(world, 0.2f);
        assertEquals(List.of("begin", "stay", "end"), heard);
    }

    @Test
    void aSleepingPairKeepsItsContact() {
        PhysicsWorld world = world();
        floor(world);
        RigidBody box = box(world, 0f, 0.5f, 0f, 0.5f);
        int[] ended = new int[1];
        world.addContactListener(new ContactListener() {
            @Override
            public void contactEnd(RigidBody a, RigidBody b) {
                ended[0]++;
            }
        });
        run(world, 4f);
        assertTrue(box.isSleeping());
        assertEquals(0, ended[0]);
        assertEquals(1, world.getContacts().size());
    }

    @Test
    void triggersReportEnterAndExitAndDoNotBlock() {
        PhysicsWorld world = world().setGravity(Vec3.ZERO);
        RigidBody zone = new RigidBody(BodyType.STATIC).addShape(new BoxShape(1f, 1f, 1f)).setTrigger(true);
        world.add(zone);
        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.25f));
        ball.setPosition(-3f, 0f, 0f);
        world.add(ball);
        ball.setLinearDamping(0f).setVelocity(4f, 0f, 0f);
        List<String> heard = new ArrayList<>();
        world.addContactListener(new ContactListener() {
            @Override
            public void triggerEnter(RigidBody trigger, RigidBody other) {
                heard.add("enter " + (trigger == zone) + " " + (other == ball));
            }

            @Override
            public void triggerExit(RigidBody trigger, RigidBody other) {
                heard.add("exit");
            }

            @Override
            public void contactBegin(Contact contact) {
                heard.add("contact");
            }
        });
        run(world, 0.6f);
        assertEquals(List.of("enter true true"), heard);
        run(world, 1f);
        assertEquals(List.of("enter true true", "exit"), heard);
        assertEquals(4f, ball.getVelocity().x(), 0.01f, "a trigger doesn't slow it");
    }

    @Test
    void aKinematicBodySetsOffAStaticTrigger() {
        PhysicsWorld world = world();
        RigidBody zone = new RigidBody(BodyType.STATIC).addShape(new BoxShape(1f, 1f, 1f)).setTrigger(true);
        world.add(zone);
        RigidBody lift = new RigidBody(BodyType.KINEMATIC).addShape(new BoxShape(0.25f, 0.25f, 0.25f));
        lift.setPosition(-3f, 0f, 0f);
        world.add(lift);
        lift.setVelocity(4f, 0f, 0f);
        int[] entered = new int[1];
        world.addContactListener(new ContactListener() {
            @Override
            public void triggerEnter(RigidBody trigger, RigidBody other) {
                entered[0]++;
            }
        });
        run(world, 0.6f);
        assertEquals(1, entered[0]);
        assertEquals(-3f + 4f * 0.6f, lift.getPosition().x(), 0.01f);
    }

    @Test
    void bothLayersHaveToAgree() {
        PhysicsWorld world = world();
        RigidBody floor = floor(world);
        floor.setCollisionLayer(1);
        // The ghost's mask leaves the floor out; the floor's mask has everything. One refusing is enough.
        RigidBody ghost = box(world, 0f, 2f, 0f, 0.5f).setCollisionLayer(2).setCollisionMask(~1);
        RigidBody solid = box(world, 3f, 2f, 0f, 0.5f).setCollisionLayer(4);
        run(world, 2f);
        assertTrue(ghost.getPosition().y() < -2f, "fell through: " + ghost.getPosition());
        assertEquals(0.5f, solid.getPosition().y(), 0.02f);
        // Changing its mind later takes effect.
        ghost.setCollisionMask(-1);
        ghost.setPosition(0f, 2f, 0f);
        ghost.setVelocity(Vec3.ZERO);
        run(world, 2f);
        assertEquals(0.5f, ghost.getPosition().y(), 0.02f);
    }

    @Test
    void aFastThinBodyDoesNotCrossAThinWall() {
        PhysicsWorld world = world().setGravity(Vec3.ZERO);
        RigidBody wall = new RigidBody(BodyType.STATIC).addShape(new BoxShape(5f, 5f, 0.01f));
        world.add(wall);
        RigidBody bullet = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.02f));
        bullet.setPosition(0f, 0f, -5f);
        world.add(bullet);
        bullet.setLinearDamping(0f).setVelocity(0f, 0f, 300f); // 5 m a step
        run(world, 0.5f);
        assertTrue(bullet.getPosition().z() < 0f, "stopped by the wall: " + bullet.getPosition());

        RigidBody careless = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.02f)).setContinuous(false);
        careless.setPosition(1f, 0f, -5.2f);
        world.add(careless);
        careless.setLinearDamping(0f).setVelocity(0f, 0f, 300f);
        run(world, 0.5f);
        assertTrue(careless.getPosition().z() > 1f, "without the sweep it goes through: " + careless.getPosition());
    }

    @Test
    void frictionAndBounceCombineAsBuiltIn() {
        PhysicsWorld world = world();
        floor(world).setMaterial(new PhysicsMaterial(1f, 0f));
        RigidBody ice = box(world, 0f, 0.5f, 0f, 0.5f).setMaterial(new PhysicsMaterial(0f, 0f));
        RigidBody rubber = box(world, 0f, 0.5f, 5f, 0.5f).setMaterial(new PhysicsMaterial(1f, 0f));
        ice.setLinearDamping(0f).setVelocity(5f, 0f, 0f);
        rubber.setLinearDamping(0f).setVelocity(5f, 0f, 0f);
        run(world, 1f);
        assertEquals(5f, ice.getPosition().x(), 0.2f, "no friction when one side has none");
        assertTrue(rubber.getPosition().x() < 2f, "friction 1 stops it within 1.3 m: " + rubber.getPosition());

        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.25f)).setMaterial(new PhysicsMaterial(0.5f, 0.8f));
        ball.setPosition(0f, 2.25f, 20f); // out of the sliding boxes' way
        world.add(ball);
        ball.setLinearDamping(0f);
        float highest = 0f;
        boolean landed = false;
        for (int i = 0; i < 120; i++) {
            world.step(STEP);
            if (ball.getVelocity().y() > 0f) landed = true;
            if (landed) highest = Math.max(highest, ball.getPosition().y() - 0.25f);
        }
        assertEquals(2f * 0.64f, highest, 0.2f, "the larger restitution counts");
    }

    @Test
    void hullsAndMeshesAreCooked() {
        PhysicsWorld world = world();
        // Two triangles making a 20 m square floor at y = 0, facing up.
        float[] corners = {-10, 0, -10, 10, 0, -10, 10, 0, 10, -10, 0, 10};
        RigidBody ground = new RigidBody(BodyType.STATIC).addShape(new MeshShape(corners, new int[]{0, 2, 1, 0, 3, 2}));
        world.add(ground);
        ConvexHullShape pyramid = ConvexHullShape.of(new Vec3(-0.5f, 0f, -0.5f), new Vec3(0.5f, 0f, -0.5f), new Vec3(0.5f, 0f, 0.5f),
                new Vec3(-0.5f, 0f, 0.5f), new Vec3(0f, 1f, 0f));
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(pyramid);
        body.setPosition(0f, 2f, 0f);
        world.add(body);
        RigidBody twin = new RigidBody(BodyType.DYNAMIC).addShape(pyramid);
        twin.setPosition(3f, 2f, 0f);
        world.add(twin);
        run(world, 3f);
        assertEquals(0f, body.getPosition().y(), 0.03f);
        assertEquals(0f, twin.getPosition().y(), 0.03f);
        assertTrue(body.getRotation().rotate(Vec3.UP).y() > 0.99f, "it lands on its base and stays on it");
        // Queries are the world's own and see where PhysX left things.
        RaycastHit hit = world.raycast(new Ray(new Vec3(0f, 5f, 0f), new Vec3(0f, -1f, 0f)), 10f);
        assertNotNull(hit);
        assertEquals(body, hit.body());
        assertEquals(4f, hit.distance(), 0.05f);
        assertEquals(List.of(twin), world.overlapSphere(new Vec3(3f, 0.3f, 0f), 0.2f));
        world.remove(body);
        world.remove(twin);
        run(world, 0.1f);
    }

    @Test
    void aCapsuleStandsAlongY() {
        PhysicsWorld world = world();
        floor(world);
        RigidBody capsule = new RigidBody(BodyType.DYNAMIC).addShape(new CapsuleShape(0.3f, 0.6f)).lockRotation(true, true, true);
        capsule.setPosition(0f, 2f, 0f);
        world.add(capsule);
        run(world, 2f);
        assertEquals(0.9f, capsule.getPosition().y(), 0.02f, "radius plus half height above the floor");
    }

    @Test
    void aTiltedPlaneIsSolidBehindItsNormal() {
        PhysicsWorld world = world();
        Vec3 normal = new Vec3(1f, 1f, 0f).normalized();
        world.add(new RigidBody(BodyType.STATIC).addShape(new PlaneShape(normal)));
        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.5f)).setMaterial(new PhysicsMaterial(0f, 0f));
        ball.setPosition(0f, 3f, 0f);
        world.add(ball);
        run(world, 2f);
        // It slides down the slope: always half a metre off the plane, moving toward +x and down.
        assertEquals(0.5f, ball.getPosition().dot(normal), 0.03f);
        assertTrue(ball.getPosition().x() > 2f, "slid down the slope: " + ball.getPosition());
    }

    @Test
    void aHingeTurnsOnlyAroundItsAxisWithinItsLimits() {
        PhysicsWorld world = world();
        RigidBody door = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(0.5f, 1f, 0.05f));
        door.setPosition(0.5f, 0f, 0f);
        world.add(door);
        HingeJoint hinge = new HingeJoint(door, null, Vec3.ZERO, Vec3.UP).setLimits(-0.5f, 1f);
        world.addJoint(hinge);
        door.setAngularDamping(0f);
        door.applyTorqueImpulse(new Vec3(0f, 20f, 0f));
        float most = 0f;
        for (int i = 0; i < 120; i++) {
            world.step(STEP);
            most = Math.max(most, hinge.getAngle());
            assertEquals(0f, hinge.getAnchor().distance(Vec3.ZERO), 0.02f, "the door stays on its hinge");
        }
        assertEquals(1f, most, 0.08f, "it turns the way it was pushed, up to the limit");
        assertEquals(0f, door.getPosition().y(), 0.02f, "gravity doesn't pull it off the hinge");
        hinge.clearLimits().setMotor(-2f, 1000f);
        run(world, 0.5f);
        assertEquals(-2f, hinge.getAngularSpeed(), 0.1f, "the motor turns it the way the built-in solver's does");
        assertEquals(-2f, door.getAngularVelocity().y(), 0.1f);
    }

    @Test
    void aHingeBetweenTwoBodiesMeasuresTheSecondAgainstTheFirst() {
        PhysicsWorld world = world().setGravity(Vec3.ZERO);
        RigidBody base = new RigidBody(BodyType.STATIC).addShape(new BoxShape(0.2f, 0.2f, 0.2f));
        world.add(base);
        RigidBody arm = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(0.5f, 0.1f, 0.1f));
        arm.setPosition(1f, 0f, 0f);
        world.add(arm);
        HingeJoint hinge = new HingeJoint(base, arm, new Vec3(0.3f, 0f, 0f), Vec3.Z).setMotor(1f, 1000f);
        world.addJoint(hinge);
        run(world, 1f);
        assertEquals(1f, hinge.getAngle(), 0.05f);
        assertEquals(1f, arm.getAngularVelocity().z(), 0.05f);
        hinge.setLimits(0f, 1.2f);
        run(world, 1f);
        assertEquals(1.2f, hinge.getAngle(), 0.05f);
    }

    @Test
    void aSliderSlidesAlongItsAxisWithLimitsAndAMotor() {
        PhysicsWorld world = world().setGravity(Vec3.ZERO);
        RigidBody block = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(0.25f, 0.25f, 0.25f));
        world.add(block);
        SliderJoint slider = new SliderJoint(block, null, Vec3.ZERO, Vec3.X).setLimits(-1f, 2f);
        world.addJoint(slider);
        block.setLinearDamping(0f).setVelocity(3f, 2f, 1f);
        run(world, 2f);
        assertEquals(2f, slider.getTranslation(), 0.05f);
        assertEquals(2f, block.getPosition().x(), 0.05f);
        assertEquals(0f, block.getPosition().y(), 0.02f);
        assertEquals(0f, block.getPosition().z(), 0.02f);
        slider.setMotor(-1f, 500f);
        run(world, 1f);
        assertEquals(1f, slider.getTranslation(), 0.1f);
        run(world, 4f);
        assertEquals(-1f, slider.getTranslation(), 0.05f, "the motor runs it to the lower stop");
    }

    @Test
    void aBallJointHoldsThePointAndTheSwingLimit() {
        PhysicsWorld world = world();
        RigidBody bob = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.2f));
        bob.setPosition(0f, -1f, 0f);
        world.add(bob);
        BallJoint joint = new BallJoint(bob, null, Vec3.ZERO, new Vec3(0f, -1f, 0f)).setSwingLimit(0.5f);
        world.addJoint(joint);
        bob.setLinearDamping(0f).setVelocity(3f, 0f, 0f);
        float most = 0f;
        for (int i = 0; i < 180; i++) {
            world.step(STEP);
            assertEquals(1f, bob.getPosition().length(), 0.03f, "the bob stays 1 m from the pin");
            most = Math.max(most, joint.getSwing());
        }
        assertEquals(0.5f, most, 0.08f);
    }

    @Test
    void aDistanceJointIsARodARopeOrASpring() {
        PhysicsWorld world = world();
        RigidBody rod = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.2f));
        rod.setPosition(2f, 0f, 0f);
        world.add(rod);
        world.addJoint(new DistanceJoint(rod, rod.getPosition(), null, Vec3.ZERO));
        RigidBody rope = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.2f));
        rope.setPosition(10f, -1f, 0f);
        world.add(rope);
        world.addJoint(new DistanceJoint(rope, rope.getPosition(), null, new Vec3(10f, 0f, 0f)).setLength(3f).setMinLength(0f));
        for (int i = 0; i < 180; i++) {
            world.step(STEP);
            assertEquals(2f, rod.getPosition().length(), 0.05f, "a rod keeps its length");
        }
        assertEquals(-3f, rope.getPosition().y(), 0.05f, "a rope lets it fall to its full length");

        RigidBody weight = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.2f));
        weight.setPosition(20f, -1f, 0f);
        world.add(weight);
        DistanceJoint spring = new DistanceJoint(weight, weight.getPosition(), null, new Vec3(20f, 0f, 0f)).setSpring(2f, 1f);
        world.addJoint(spring);
        run(world, 3f);
        // It hangs where the spring's pull equals its weight: g / (2 pi f)^2 below the rest length.
        float sag = 9.81f / (float) Math.pow(2.0 * Math.PI * 2.0, 2.0);
        assertEquals(-1f - sag, weight.getPosition().y(), 0.02f);
    }

    @Test
    void aWeldHoldsReportsItsLoadAndBreaks() {
        PhysicsWorld world = world();
        RigidBody hung = box(world, 0f, 0f, 0f, 0.5f).setMass(2f);
        FixedJoint weld = new FixedJoint(hung, null);
        world.addJoint(weld);
        run(world, 1f);
        assertEquals(0f, hung.getPosition().length(), 0.01f);
        assertEquals(2f * 9.81f, weld.getReactionForce().y(), 0.5f, "it holds the weight up");
        List<Joint> snapped = new ArrayList<>();
        weld.setBreakForce(100f).setBreakHandler(snapped::add);
        run(world, 0.5f);
        assertTrue(snapped.isEmpty());
        hung.applyImpulse(new Vec3(0f, -40f, 0f)); // far more than 100 N for a step
        run(world, 1f);
        assertEquals(List.of(weld), snapped);
        assertTrue(weld.isBroken());
        assertNull(weld.getWorld());
        assertTrue(hung.getPosition().y() < -1f, "and the box falls: " + hung.getPosition());
    }

    @Test
    void jointedBodiesDoNotCollideUnlessAsked() {
        PhysicsWorld world = world().setGravity(Vec3.ZERO);
        RigidBody a = box(world, 0f, 0f, 0f, 0.5f), b = box(world, 0.6f, 0f, 0f, 0.5f); // overlapping
        DistanceJoint joint = new DistanceJoint(a, a.getPosition(), b, b.getPosition()).setMinLength(0f).setMaxLength(5f);
        world.addJoint(joint);
        run(world, 0.5f);
        assertEquals(0.6f, b.getPosition().sub(a.getPosition()).length(), 0.01f, "left overlapping");
        joint.setCollideConnected(true);
        run(world, 1f);
        assertTrue(b.getPosition().sub(a.getPosition()).length() > 0.95f, "pushed apart: " + b.getPosition().sub(a.getPosition()));
    }

    @Test
    void aKinematicPlatformCarriesWhatStandsOnIt() {
        PhysicsWorld world = world();
        RigidBody platform = new RigidBody(BodyType.KINEMATIC).addShape(new BoxShape(2f, 0.1f, 2f));
        world.add(platform);
        RigidBody rider = box(world, 0f, 0.6f, 0f, 0.5f).setMaterial(new PhysicsMaterial(1f, 0f));
        platform.setMaterial(new PhysicsMaterial(1f, 0f));
        run(world, 0.5f);
        platform.setVelocity(1f, 0f, 0f);
        run(world, 2f);
        assertEquals(2f, platform.getPosition().x(), 0.01f);
        assertEquals(2f, rider.getPosition().x(), 0.25f);
        assertEquals(0.6f, rider.getPosition().y(), 0.02f);
        // Becoming dynamic, the platform falls with the rest.
        platform.setType(BodyType.DYNAMIC);
        run(world, 1f);
        assertTrue(platform.getPosition().y() < -2f);
    }

    @Test
    void aBodyCanChangeShapeAndTypeWhileInTheWorld() {
        PhysicsWorld world = world();
        floor(world);
        RigidBody body = box(world, 0f, 0.5f, 0f, 0.5f);
        run(world, 1f);
        body.removeShape(body.getShapes().getFirst());
        body.addShape(new SphereShape(1f));
        run(world, 2f);
        assertEquals(1f, body.getPosition().y(), 0.03f, "now a ball of radius 1");
        body.setType(BodyType.STATIC);
        body.setPosition(0f, 4f, 0f);
        run(world, 1f);
        assertEquals(4f, body.getPosition().y(), 1e-4f, "a static body stays where it is put");
        RigidBody onTop = box(world, 0f, 6f, 0f, 0.5f);
        run(world, 2f);
        assertEquals(5.5f, onTop.getPosition().y(), 0.03f, "and things land on it there");
    }

    @Test
    void aCharacterWalksAndStandsOnPhysXBodies() {
        PhysicsWorld world = world();
        floor(world);
        RigidBody crate = box(world, 3f, 0.5f, 0f, 0.5f).setMass(5f);
        CharacterController hero = new CharacterController(new Node3D("hero"), 0.3f, 1.8f);
        hero.setPosition(new Vec3(0f, 0.01f, 0f));
        world.addCharacter(hero);
        run(world, 0.5f);
        assertTrue(hero.isGrounded());
        float standing = hero.getPosition().y();
        hero.setWalkVelocity(new Vec3(2f, 0f, 0f));
        run(world, 1f);
        assertEquals(2f, hero.getPosition().x(), 0.2f);
        assertEquals(standing, hero.getPosition().y(), 0.05f);
        run(world, 1.5f);
        assertTrue(crate.getPosition().x() > 3.3f, "walking into the crate shoves it: " + crate.getPosition());
        assertTrue(hero.getPosition().x() < crate.getPosition().x(), "and doesn't walk through it");
    }

    @Test
    void substepsAndWorldSettingsCarryOver() {
        PhysicsWorld world = world().setSubsteps(4).setSleeping(false);
        floor(world);
        RigidBody box = box(world, 0f, 0.5f, 0f, 0.5f);
        run(world, 3f);
        assertFalse(box.isSleeping(), "sleeping is off for the world");
        assertEquals(0.5f, box.getPosition().y(), 0.02f);
        world.setGravity(new Vec3(0f, 9.81f, 0f));
        run(world, 1f);
        assertTrue(box.getPosition().y() > 3f, "gravity now pulls up: " + box.getPosition());
        assertEquals(4f, world.getTime(), 0.01f);
    }

    @Test
    void installMakesEveryNewWorldAPhysXWorldAndSwitchingBackWorks() {
        PhysX.install();
        world = new PhysicsWorld();
        assertTrue(world.getBackend() instanceof PhysXBackend);
        floor(world);
        RigidBody box = box(world, 0f, 2f, 0f, 0.5f);
        run(world, 0.2f);
        float falling = box.getVelocity().y();
        assertTrue(falling < -1f);
        world.setBackend(null); // the built-in solver takes over where PhysX left off
        run(world, 3f);
        assertEquals(0.5f, box.getPosition().y(), 0.03f);
        world.setBackend(new PhysXBackend());
        box.setPosition(0f, 2f, 0f);
        run(world, 3f);
        assertEquals(0.5f, box.getPosition().y(), 0.03f);
        PhysX.uninstall();
        assertNull(new PhysicsWorld().getBackend());
    }

    @Test
    void manyWorldsComeAndGo() {
        for (int round = 0; round < 20; round++) {
            PhysicsWorld w = new PhysicsWorld().setBackend(new PhysXBackend());
            floor(w);
            List<RigidBody> boxes = new ArrayList<>();
            for (int i = 0; i < 50; i++) boxes.add(box(w, (i % 5) * 1.1f, 0.5f + (i / 5) * 1.05f, 0f, 0.5f));
            w.addJoint(new HingeJoint(boxes.get(0), boxes.get(5), new Vec3(0f, 1f, 0f), Vec3.X));
            run(w, 0.25f);
            for (int i = 0; i < 25; i++) w.remove(boxes.get(i * 2));
            run(w, 0.25f);
            w.close();
            assertNull(w.getBackend());
        }
    }

    @Test
    void aRotatedBodyKeepsItsShapesInPlace() {
        PhysicsWorld world = world();
        floor(world);
        // A plank offset from its body and the body turned a quarter turn: the plank ends up standing on end.
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(1f, 0.1f, 0.1f), new Vec3(1f, 0f, 0f));
        body.setRotation(Quat.fromAxisAngle(Vec3.Z, (float) (Math.PI / 2)));
        body.setPosition(0f, 0.001f, 0f);
        body.lockRotation(true, true, true);
        world.add(body);
        run(world, 1f);
        assertEquals(0f, body.getPosition().y(), 0.02f, "the plank's lower end is at the body's origin, on the floor");
        assertEquals(1f, body.getCenterOfMass().y(), 0.02f);
    }
}
