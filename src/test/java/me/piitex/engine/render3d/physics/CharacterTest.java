package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Node3D;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CharacterTest {
    private static final float DT = 1f / 60f;
    private static final float G = 9.81f;

    private static void run(PhysicsWorld world, float seconds) {
        int steps = Math.round(seconds / DT);
        for (int i = 0; i < steps; i++) world.step(DT);
    }

    private static PhysicsWorld flat() {
        PhysicsWorld world = new PhysicsWorld();
        world.add(new RigidBody(BodyType.STATIC).addShape(new PlaneShape()));
        return world;
    }

    private static RigidBody block(PhysicsWorld world, Vec3 at, Vec3 half) {
        return block(world, at, half, Quat.IDENTITY);
    }

    private static RigidBody block(PhysicsWorld world, Vec3 at, Vec3 half, Quat rotation) {
        RigidBody body = new RigidBody(BodyType.STATIC).addShape(new BoxShape(half));
        body.setPosition(at).setRotation(rotation);
        world.add(body);
        return body;
    }

    private static CharacterController character(PhysicsWorld world, float x, float y, float z) {
        CharacterController c = new CharacterController(null, 0.3f, 1.8f);
        c.getBody().setPosition(x, y, z);
        world.addCharacter(c);
        return c;
    }

    @Test
    void standsFallsAndLands() {
        PhysicsWorld world = flat();
        CharacterController c = character(world, 0f, 0f, 0f);
        run(world, 1f);
        assertTrue(c.isGrounded());
        assertEquals(0f, c.getPosition().y(), 1e-3f);
        assertEquals(Vec3.UP, c.getGroundNormal());

        c.setPosition(new Vec3(0f, 5f, 0f));
        assertFalse(c.isGrounded());
        run(world, 0.5f);
        // Free fall, a step behind the exact curve at most.
        assertEquals(5f - 0.5f * G * 0.25f, c.getPosition().y(), 0.1f);
        assertFalse(c.isGrounded());
        run(world, 1f);
        assertTrue(c.isGrounded());
        assertEquals(0f, c.getPosition().y(), 1e-3f);
        assertEquals(0f, c.getVelocity().length(), 1e-3f);
    }

    @Test
    void walksAtItsSpeedAndJumpsAsHighAsItShould() {
        PhysicsWorld world = flat();
        CharacterController c = character(world, 0f, 0f, 0f);
        world.step(DT);
        c.setWalkVelocity(new Vec3(5f, 3f, 0f)); // the upward part doesn't count
        run(world, 1f);
        assertEquals(5f, c.getPosition().x(), 0.02f);
        assertEquals(0f, c.getPosition().y(), 1e-3f);
        assertEquals(5f, c.getVelocity().x(), 1e-3f);

        c.setWalkVelocity(Vec3.ZERO);
        world.step(DT);
        assertTrue(c.jump(5f));
        assertFalse(c.jump(5f), "no jumping in the air");
        float highest = 0f;
        for (int i = 0; i < 90; i++) {
            world.step(DT);
            highest = Math.max(highest, c.getPosition().y());
        }
        assertEquals(25f / (2f * G), highest, 0.05f);
        assertTrue(c.isGrounded());
    }

    @Test
    void slidesAlongWallsAndStopsUnderCeilings() {
        PhysicsWorld world = flat();
        block(world, new Vec3(0f, 1f, -1f), new Vec3(5f, 1f, 0.2f)); // a wall along X, its face at z = -0.8
        CharacterController c = character(world, 0f, 0f, 0f);
        world.step(DT);
        c.setWalkVelocity(new Vec3(3f, 0f, -3f));
        run(world, 1f);
        assertEquals(-0.8f + 0.3f, c.getPosition().z(), 0.01f, "pressed against the wall");
        assertTrue(c.getPosition().x() > 2.7f, "it slides along at the speed along the wall, got " + c.getPosition());
        assertTrue(c.isTouchingWall());
        assertTrue(c.isGrounded());

        // A ceiling 2.2 m up: a jump stops at the head.
        block(world, new Vec3(10f, 2.5f, 0f), new Vec3(2f, 0.3f, 2f));
        c.setWalkVelocity(Vec3.ZERO);
        c.setPosition(new Vec3(10f, 0f, 0f));
        run(world, 0.2f);
        c.jump(6f);
        float highest = 0f;
        boolean bumped = false;
        for (int i = 0; i < 60; i++) {
            world.step(DT);
            highest = Math.max(highest, c.getPosition().y());
            bumped |= c.isTouchingCeiling();
        }
        assertTrue(bumped);
        assertEquals(2.2f - 1.8f, highest, 0.02f);
        assertTrue(c.isGrounded());
    }

    @Test
    void walksUpGentleSlopesButNotSteepOnes() {
        PhysicsWorld world = flat();
        // A 30 degree ramp rising toward +X, and a 60 degree one toward -X.
        float gentle = (float) Math.toRadians(30), steep = (float) Math.toRadians(60);
        block(world, new Vec3(4f, 0f, 0f), new Vec3(3f, 0.5f, 2f), Quat.fromAxisAngle(Vec3.Z, gentle));
        block(world, new Vec3(-4f, 0f, 0f), new Vec3(3f, 0.5f, 2f), Quat.fromAxisAngle(Vec3.Z, -steep));
        CharacterController c = character(world, 0.5f, 0f, 0f);
        world.step(DT);
        c.setWalkVelocity(new Vec3(2f, 0f, 0f));
        run(world, 2.5f);
        // The ramp comes out of the floor at x = 3; walking across at 2 m/s, it should be 2.5 m in and 1.44 m up.
        assertEquals(5.5f, c.getPosition().x(), 0.1f);
        assertEquals(2.5f * (float) Math.tan(gentle), c.getPosition().y(), 0.1f);
        assertTrue(c.isGrounded());
        assertEquals((float) Math.cos(gentle), c.getGroundNormal().y(), 0.01f);

        c.setPosition(new Vec3(-0.5f, 0f, 0f));
        c.setWalkVelocity(new Vec3(-2f, 0f, 0f));
        run(world, 2f);
        assertTrue(c.getPosition().y() < 0.15f, "it shouldn't climb the steep ramp, at " + c.getPosition());
        assertTrue(c.isTouchingWall());

        // Dropped onto the steep ramp, it slides down it.
        c.setWalkVelocity(Vec3.ZERO);
        c.setPosition(new Vec3(-4.3f, 4f, 0f)); // above the slope, which meets the floor at x = -3.42
        run(world, 3f);
        assertTrue(c.getPosition().x() > -3.6f && c.getPosition().y() < 0.2f, "it should slide off the steep ramp, at " + c.getPosition());
    }

    @Test
    void climbsStepsAndStaysOnTheGroundGoingDown() {
        PhysicsWorld world = flat();
        // Stairs of 0.2 m steps, 0.4 m deep, up toward +X, then down again.
        for (int i = 0; i < 5; i++) {
            float h = 0.2f * (i + 1);
            block(world, new Vec3(1f + i * 0.4f + 0.2f, h * 0.5f, 0f), new Vec3(0.2f, h * 0.5f, 1f));
            block(world, new Vec3(1f + (9 - i) * 0.4f + 0.2f, h * 0.5f, 0f), new Vec3(0.2f, h * 0.5f, 1f));
        }
        CharacterController c = character(world, 0f, 0f, 0f);
        world.step(DT);
        c.setWalkVelocity(new Vec3(1.5f, 0f, 0f));
        boolean alwaysGrounded = true;
        float top = 0f;
        for (int i = 0; i < 240; i++) {
            world.step(DT);
            alwaysGrounded &= c.isGrounded();
            top = Math.max(top, c.getPosition().y());
        }
        assertEquals(1f, top, 0.01f, "up to the top step");
        assertTrue(c.getPosition().x() > 5.5f, "over the stairs and down, at " + c.getPosition());
        assertEquals(0f, c.getPosition().y(), 0.01f);
        assertTrue(alwaysGrounded, "it should stay on the stairs both ways, not fly off them");

        // A step taller than the step height is a wall.
        block(world, new Vec3(8f, 0.25f, 0f), new Vec3(0.5f, 0.25f, 1f));
        c.setPosition(new Vec3(6.5f, 0f, 0f));
        run(world, 2f);
        assertEquals(7.5f - 0.3f, c.getPosition().x(), 0.01f);
        assertEquals(0f, c.getPosition().y(), 0.01f);
    }

    @Test
    void ridesMovingPlatforms() {
        PhysicsWorld world = flat();
        RigidBody platform = new RigidBody(BodyType.KINEMATIC).addShape(new BoxShape(new Vec3(1.5f, 0.1f, 1.5f)));
        platform.setPosition(0f, 1f, 0f);
        world.add(platform);
        CharacterController c = character(world, 0f, 1.1f, 0f);
        run(world, 0.2f);
        assertTrue(c.isGrounded());
        assertSame(platform, c.getGroundBody());

        platform.setVelocity(new Vec3(1f, 0f, 0f));
        run(world, 1f);
        assertEquals(1f, c.getPosition().x(), 0.03f, "carried along");
        assertEquals(1.1f, c.getPosition().y(), 0.005f);

        // Up and down: it stays on the platform the whole way, not bobbing off it.
        boolean alwaysGrounded = true;
        float worst = 0f;
        for (int i = 0; i < 240; i++) {
            float vy = (float) Math.sin(i * DT * 3) * 2f;
            platform.setVelocity(new Vec3(0f, vy, 0f));
            world.step(DT);
            alwaysGrounded &= c.isGrounded();
            worst = Math.max(worst, Math.abs(c.getPosition().y() - (platform.getPosition().y() + 0.1f)));
        }
        assertTrue(alwaysGrounded);
        assertTrue(worst < 0.05f, "it came " + worst + " m off the platform");

        // Turning: carried round with it.
        platform.setVelocity(Vec3.ZERO);
        c.setPosition(platform.getPosition().add(new Vec3(1f, 0.1f, 0f)));
        run(world, 0.1f);
        platform.setAngularVelocity(new Vec3(0f, (float) Math.PI / 2, 0f));
        run(world, 1f);
        Vec3 from = c.getPosition().sub(platform.getPosition());
        assertEquals(1f, Math.hypot(from.x(), from.z()), 0.05f);
        assertTrue(from.z() < -0.9f, "a quarter turn should carry it round, it's at " + from);
    }

    @Test
    void pushesLooseObjectsAsHardAsItsMassAllows() {
        PhysicsWorld world = flat();
        RigidBody crate = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(0.4f, 0.4f, 0.4f))).setMass(20f);
        crate.setPosition(1.5f, 0.4f, 0f);
        world.add(crate);
        RigidBody safe = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(0.4f, 0.4f, 0.4f))).setMass(5000f);
        safe.setPosition(1.5f, 0.4f, 5f);
        world.add(safe);
        CharacterController c = character(world, 0f, 0f, 0f);
        CharacterController d = character(world, 0f, 0f, 5f);
        run(world, 0.2f);
        c.setWalkVelocity(new Vec3(2f, 0f, 0f));
        d.setWalkVelocity(new Vec3(2f, 0f, 0f));
        run(world, 2f);
        assertTrue(crate.getPosition().x() > 3.5f, "the crate should be pushed along, at " + crate.getPosition());
        assertTrue(safe.getPosition().x() < 1.6f, "a safe hardly moves, at " + safe.getPosition());
        assertTrue(d.getPosition().x() < 1.5f - 0.4f - 0.3f + 0.02f, "and blocks the character");
        assertEquals(0.4f, crate.getPosition().y(), 0.02f, "not pressed into the floor or lifted");

        c.setMass(0f);
        c.setWalkVelocity(Vec3.ZERO);
        run(world, 2f);
        float x = crate.getPosition().x();
        c.setWalkVelocity(new Vec3(2f, 0f, 0f));
        run(world, 1f);
        assertEquals(x, crate.getPosition().x(), 0.01f, "with no mass it pushes nothing");
    }

    @Test
    void objectsLandOnItAndRaysAndTriggersFindIt() {
        PhysicsWorld world = flat();
        CharacterController c = character(world, 0f, 0f, 0f);
        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.2f)).setMass(1f);
        ball.setPosition(0f, 4f, 0f);
        world.add(ball);
        run(world, 2f);
        assertEquals(1.8f + 0.2f, ball.getPosition().y(), 0.02f, "it lands on the character's head");
        assertEquals(0f, c.getPosition().y(), 1e-3f, "and doesn't push it down");

        RaycastHit hit = world.raycast(new Ray(new Vec3(-5f, 1f, 0f), new Vec3(1f, 0f, 0f)), 10f);
        assertSame(c.getBody(), hit.body());

        RigidBody zone = new RigidBody(BodyType.STATIC).addShape(new BoxShape(new Vec3(1f, 1f, 1f))).setTrigger(true);
        zone.setPosition(5f, 1f, 0f);
        world.add(zone);
        List<String> events = new ArrayList<>();
        world.addContactListener(new ContactListener() {
            @Override
            public void triggerEnter(RigidBody trigger, RigidBody other) {
                events.add("enter " + (other == c.getBody()));
            }

            @Override
            public void triggerExit(RigidBody trigger, RigidBody other) {
                events.add("exit " + (other == c.getBody()));
            }
        });
        ball.setPosition(-10f, 0.2f, 0f);
        c.setWalkVelocity(new Vec3(3f, 0f, 0f));
        run(world, 1.6f);
        assertEquals(List.of("enter true"), events);
        c.setWalkVelocity(Vec3.ZERO);
        run(world, 1f);
        assertEquals(List.of("enter true"), events, "standing still in the zone, it stays in");
        c.setWalkVelocity(new Vec3(3f, 0f, 0f));
        run(world, 1f);
        assertEquals(List.of("enter true", "exit true"), events);
    }

    @Test
    void walksOverATerrainMesh() {
        PhysicsWorld world = new PhysicsWorld();
        int cells = 40, n = cells + 1;
        float size = 0.5f;
        float[] positions = new float[n * n * 3];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                float x = -10f + i * size, z = -10f + j * size;
                int o = (i * n + j) * 3;
                positions[o] = x;
                positions[o + 1] = 0.6f * (float) Math.sin(x * 0.5f) * (float) Math.cos(z * 0.4f);
                positions[o + 2] = z;
            }
        }
        int[] indices = new int[cells * cells * 6];
        int k = 0;
        for (int i = 0; i < cells; i++) {
            for (int j = 0; j < cells; j++) {
                int a = i * n + j, b = a + n;
                indices[k++] = a;
                indices[k++] = a + 1;
                indices[k++] = b;
                indices[k++] = b;
                indices[k++] = a + 1;
                indices[k++] = b + 1;
            }
        }
        world.add(new RigidBody(BodyType.STATIC).addShape(new MeshShape(positions, indices)));
        CharacterController c = character(world, -8f, 2f, 1f);
        run(world, 1f);
        assertTrue(c.isGrounded());
        c.setWalkVelocity(new Vec3(3f, 0f, 0.5f));
        int airborne = 0;
        for (int i = 0; i < 300; i++) {
            world.step(DT);
            if (!c.isGrounded()) airborne++;
            Vec3 p = c.getPosition();
            float ground = 0.6f * (float) Math.sin(p.x() * 0.5f) * (float) Math.cos(p.z() * 0.4f);
            assertTrue(p.y() > ground - 0.05f, "it sank into the terrain at " + p);
        }
        assertEquals(0, airborne, "walking over gentle hills it should stay on the ground");
        assertTrue(c.getPosition().x() > 6f);
    }

    @Test
    void movesItsNodeAndLeavesTheWorld() {
        PhysicsWorld world = flat();
        Node3D node = new Node3D("player");
        node.setPosition(2f, 0f, 3f);
        CharacterController c = new CharacterController(node, 0.3f, 1.8f);
        world.addCharacter(c);
        world.addCharacter(c);
        assertEquals(1, world.characters().size());
        assertEquals(new Vec3(2f, 0f, 3f), c.getPosition());
        c.setWalkVelocity(new Vec3(0f, 0f, -2f));
        run(world, 1f);
        assertEquals(1f, node.getPosition().z(), 0.02f);
        assertTrue(world.removeCharacter(c));
        assertNull(c.getWorld());
        assertTrue(world.characters().isEmpty());
        assertFalse(world.bodies().contains(c.getBody()));
        assertThrows(IllegalArgumentException.class, () -> new CharacterController(null, 1f, 1f));
    }

    @Test
    void fallsOffLedgesKeepsItsMomentumAndStandsOnTheBrink() {
        PhysicsWorld world = flat();
        block(world, new Vec3(0f, 1f, 0f), new Vec3(2f, 1f, 2f)); // a 2 m high table top, edge at x = 2
        CharacterController c = character(world, 0f, 2f, 0f);
        run(world, 0.2f);
        assertTrue(c.isGrounded());
        c.setWalkVelocity(new Vec3(3f, 0f, 0f));
        while (c.getPosition().x() < 2.4f) world.step(DT); // past the edge and the radius
        c.setWalkVelocity(Vec3.ZERO);
        assertFalse(c.isGrounded(), "a 2 m drop is too far to step down");
        float x = c.getPosition().x();
        run(world, 0.2f);
        assertEquals(x + 0.6f, c.getPosition().x(), 0.05f, "with nothing steering it, it keeps going in the air");
        run(world, 1f);
        assertTrue(c.isGrounded());
        assertEquals(0f, c.getPosition().y(), 1e-3f);

        // Half over the edge, it stands on the brink rather than sliding off.
        c.setPosition(new Vec3(2.15f, 2f, 0f));
        run(world, 1f);
        assertTrue(c.isGrounded());
        // Its round bottom rests on the edge, so it sits a little lower than on the flat.
        assertEquals(2f - 0.3f + (float) Math.sqrt(0.3 * 0.3 - 0.15 * 0.15), c.getPosition().y(), 0.005f);
        assertEquals(2.15f, c.getPosition().x(), 0.01f);
    }

    @Test
    void wedgedInACornerItStaysPut() {
        PhysicsWorld world = flat();
        block(world, new Vec3(0f, 1f, -1f), new Vec3(3f, 1f, 0.2f));
        block(world, new Vec3(-1f, 1f, 0f), new Vec3(0.2f, 1f, 3f), Quat.fromAxisAngle(Vec3.UP, 0.4f));
        CharacterController c = character(world, 0f, 0f, 0f);
        world.step(DT);
        c.setWalkVelocity(new Vec3(-3f, 0f, -3f));
        run(world, 1f);
        Vec3 settled = c.getPosition();
        run(world, 1f);
        assertTrue(c.getPosition().distance(settled) < 1e-3f, "it jittered from " + settled + " to " + c.getPosition());
        assertEquals(0f, c.getPosition().y(), 1e-3f);
        assertTrue(c.isTouchingWall());
    }

    @Test
    void itsWeightTipsWhatItStandsOn() {
        PhysicsWorld world = flat();
        // A plank balanced on a hinge, the character standing on one end.
        RigidBody plank = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(2f, 0.05f, 0.5f))).setMass(10f);
        plank.setPosition(0f, 1f, 0f);
        world.add(plank);
        world.addJoint(new HingeJoint(plank, null, new Vec3(0f, 1f, 0f), Vec3.Z));
        CharacterController c = character(world, 1.5f, 1.05f, 0f);
        run(world, 0.5f);
        assertTrue(plank.getRotation().rotate(Vec3.X).y() < -0.2f, "the plank should tip down under the character, it's at " + plank.getRotation());
    }

    /**
     * A sagging bridge of {@code count} planks hinged end to end across the gap between x = -3 and x = 3, at a height
     * of 2 m; returns the planks.
     */
    static List<RigidBody> bridge(PhysicsWorld world, int count, float plankLength, float plankMass) {
        RigidBody left = block(world, new Vec3(-4.5f, 1f, 0f), new Vec3(1.5f, 1f, 1f));
        RigidBody right = block(world, new Vec3(4.5f, 1f, 0f), new Vec3(1.5f, 1f, 1f));
        // Planks along an even curve, angles from -a to a, with a chosen so they span the gap exactly.
        float lo = 0f, hi = 1.5f;
        for (int k = 0; k < 60; k++) {
            float a = (lo + hi) * 0.5f, span = 0f;
            for (int i = 0; i < count; i++) span += plankLength * (float) Math.cos(-a + 2 * a * (i + 0.5f) / count);
            if (span > 6f) lo = a;
            else hi = a;
        }
        float a = lo, thickness = 0.08f;
        Vec3 hinge = new Vec3(-3f, 2f - thickness * 0.5f, 0f);
        List<RigidBody> planks = new ArrayList<>();
        RigidBody previous = left;
        for (int i = 0; i < count; i++) {
            float angle = -a + 2 * a * (i + 0.5f) / count;
            Vec3 next = hinge.add(new Vec3((float) Math.cos(angle), (float) Math.sin(angle), 0f).mul(plankLength));
            RigidBody plank = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(plankLength * 0.5f, thickness * 0.5f, 0.6f))).setMass(plankMass);
            plank.setPosition(hinge.add(next).mul(0.5f)).setRotation(Quat.fromAxisAngle(Vec3.Z, angle));
            world.add(plank);
            world.addJoint(new HingeJoint(plank, previous, hinge, Vec3.Z));
            planks.add(plank);
            previous = plank;
            hinge = next;
        }
        world.addJoint(new HingeJoint(right, previous, hinge, Vec3.Z));
        return planks;
    }

    @Test
    void crossesARopeBridge() {
        PhysicsWorld world = new PhysicsWorld();
        List<RigidBody> planks = bridge(world, 12, 0.51f, 10f);
        run(world, 1f);
        CharacterController c = character(world, -5f, 2f, 0f);
        run(world, 0.2f);
        c.setWalkVelocity(new Vec3(1.5f, 0f, 0f));
        float lowest = Float.MAX_VALUE, loosest = 0f;
        int airborne = 0;
        for (int i = 0; i < 400; i++) {
            world.step(DT);
            lowest = Math.min(lowest, c.getPosition().y());
            if (!c.isGrounded()) airborne++;
            for (Joint joint : world.joints()) {
                HingeJoint h = (HingeJoint) joint;
                RigidBody b = h.getBodyB();
                Vec3 onA = h.getAnchor();
                // Each hinge is at the right end of body B (a plank or the left platform's edge) and the left of A.
                Vec3 onB = b.getType() == BodyType.STATIC ? onA : b.getPosition().add(b.getRotation().rotate(new Vec3(0.255f, 0f, 0f)));
                if (b.getType() != BodyType.STATIC) loosest = Math.max(loosest, onA.distance(onB));
            }
        }
        assertTrue(c.getPosition().x() > 4f, "it should be across, at " + c.getPosition());
        assertEquals(2f, c.getPosition().y(), 0.01f);
        assertTrue(lowest > 1.2f && lowest < 1.9f, "the bridge should sag under it but hold, lowest " + lowest);
        assertTrue(airborne < 20, "it left the bridge for " + airborne + " steps");
        assertTrue(loosest < 0.05f, "a hinge came " + loosest + " m apart");
        for (RigidBody plank : planks) assertTrue(plank.getPosition().y() > 0.5f);
    }
}
