package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CharacterUpAndKnockTest {
    private static final float DT = 1f / 60f;

    private static void run(PhysicsWorld world, float seconds) {
        for (int i = 0, n = Math.round(seconds / DT); i < n; i++) world.step(DT);
    }

    private static CharacterController character(PhysicsWorld world, Vec3 at) {
        CharacterController c = new CharacterController(null, 0.3f, 1.8f);
        c.getBody().setPosition(at);
        world.addCharacter(c);
        return c;
    }

    private static PhysicsWorld flat() {
        PhysicsWorld world = new PhysicsWorld();
        world.add(new RigidBody(BodyType.STATIC).addShape(new PlaneShape()));
        return world;
    }

    // ---- Up ----

    @Test
    void anUpAlongX_standsOnAWallAndWalksAlongIt() {
        // Gravity pulls towards +X, the floor's face is at x = 5, so "up" is -X.
        PhysicsWorld world = new PhysicsWorld().setGravity(new Vec3(9.81f, 0f, 0f));
        RigidBody wall = new RigidBody(BodyType.STATIC).addShape(new BoxShape(new Vec3(1f, 30f, 30f)));
        wall.setPosition(6f, 0f, 0f);
        world.add(wall);
        CharacterController c = new CharacterController(null, 0.3f, 1.8f);
        c.getBody().setPosition(3f, 0f, 0f);
        c.setUp(new Vec3(-1f, 0f, 0f));
        world.addCharacter(c);
        assertEquals(-1f, c.getUp().x(), 1e-5f);

        run(world, 1.5f);
        assertTrue(c.isGrounded(), "it should have fallen sideways onto the wall");
        assertEquals(5f, c.getPosition().x(), 0.01f);
        assertEquals(-1f, c.getGroundNormal().x(), 1e-3f);

        // Walking across up (along Z), and asking to walk through the wall (along X) does nothing.
        c.setWalkVelocity(new Vec3(4f, 0f, 3f));
        run(world, 1f);
        assertEquals(3f, c.getPosition().z(), 0.1f);
        assertEquals(0f, c.getPosition().y(), 0.05f);
        assertEquals(5f, c.getPosition().x(), 0.01f);
        assertTrue(c.isGrounded());

        // A jump leaves the wall along up, and it falls back.
        c.setWalkVelocity(Vec3.ZERO);
        assertTrue(c.jump(5f));
        run(world, 0.3f);
        assertTrue(c.getPosition().x() < 4.5f, "went off the wall to x = " + c.getPosition().x());
        run(world, 2f);
        assertTrue(c.isGrounded());
        assertEquals(5f, c.getPosition().x(), 0.01f);
    }

    @Test
    void aCharacterCanHangFromACeilingWithGravityUpsideDown() {
        PhysicsWorld world = new PhysicsWorld().setGravity(new Vec3(0f, 9.81f, 0f));
        RigidBody ceiling = new RigidBody(BodyType.STATIC).addShape(new BoxShape(new Vec3(30f, 1f, 30f)));
        ceiling.setPosition(0f, 7f, 0f); // its underside is at y = 6
        world.add(ceiling);
        CharacterController c = new CharacterController(null, 0.3f, 1.8f);
        c.getBody().setPosition(0f, 4f, 0f);
        c.setUp(new Vec3(0f, -1f, 0f));
        world.addCharacter(c);
        run(world, 2f);
        assertTrue(c.isGrounded());
        assertEquals(6f, c.getPosition().y(), 0.01f);
        c.setWalkVelocity(new Vec3(2f, 0f, 0f));
        run(world, 1f);
        assertEquals(2f, c.getPosition().x(), 0.1f);
        assertEquals(6f, c.getPosition().y(), 0.01f);
    }

    @Test
    void theUsualUpIsExactlyTheWorldsAndSettingItFlipsAndReturns() {
        PhysicsWorld world = flat();
        CharacterController c = character(world, Vec3.ZERO);
        run(world, 0.2f);
        assertEquals(Vec3.UP, c.getUp());
        c.setUp(new Vec3(0f, -1f, 0f));
        assertEquals(-1f, c.getUp().y(), 1e-5f, "a half turn, however it is made");
        c.setUp(Vec3.UP);
        assertEquals(1f, c.getUp().y(), 1e-5f);
        assertThrows(IllegalArgumentException.class, () -> c.setUp(Vec3.ZERO));
    }

    // ---- Knocks ----

    /**
     * The fastest the character goes along -X in the half second after a crate of {@code kilograms} thrown at its chest
     * hits it, for a character of {@code mass}. (Even one of mass 0 is shoved a little: whatever overlaps it pushes it
     * out; the knock is on top of that.)
     */
    private static float peakSpeed(float kilograms, float mass) {
        PhysicsWorld world = flat();
        CharacterController c = character(world, Vec3.ZERO);
        c.setMass(mass);
        run(world, 0.5f);
        RigidBody crate = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(0.25f, 0.25f, 0.25f))).setMass(kilograms);
        crate.setPosition(2f, 0.9f, 0f);
        crate.setVelocity(new Vec3(-10f, 0f, 0f));
        crate.setGravityScale(0f);
        world.add(crate);
        float peak = 0f;
        for (int i = 0; i < 30; i++) {
            world.step(DT);
            peak = Math.max(peak, -c.getVelocity().x());
            assertEquals(0f, c.getVelocity().y(), 0.01f, "knocked sideways only");
        }
        assertTrue(c.isGrounded());
        return peak;
    }

    @Test
    void aThrownCrateKnocksTheCharacterAside() {
        float loose = peakSpeed(20f, 80f), braced = peakSpeed(20f, 0f);
        assertTrue(loose > braced + 0.7f, "a character of 80 kg went " + loose + " m/s, one that takes no knock " + braced + " m/s");
    }

    /**
     * The speed the character is driven to along -X by a floating crate held against it by a steady force of 400 N.
     */
    private static float leaned(float characterMass) {
        PhysicsWorld world = flat();
        CharacterController c = character(world, Vec3.ZERO);
        c.setMass(characterMass);
        run(world, 0.5f);
        RigidBody crate = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(0.25f, 0.25f, 0.25f))).setMass(50f);
        crate.setPosition(0.56f, 0.9f, 0f);
        crate.setGravityScale(0f);
        world.add(crate);
        world.setStepHandler((w, dt) -> crate.applyForce(new Vec3(-400f, 0f, 0f)));
        run(world, 1.5f);
        return -c.getVelocity().x();
    }

    @Test
    void aBodyPressedAgainstTheCharacterDrivesItFasterWhenItIsLighter() {
        // Each step the crate presses 400 N * dt, over the mass, while the knock fades at 8 a second: 400 / (m * 8).
        float light = leaned(40f), normal = leaned(80f);
        assertEquals(1.25f, light, 0.35f);
        assertEquals(0.625f, normal, 0.25f);
        assertTrue(light > normal * 1.4f);
    }

    @Test
    void theKnockFadesAndTheCharacterStops() {
        PhysicsWorld world = flat();
        CharacterController c = character(world, Vec3.ZERO);
        run(world, 0.5f);
        RigidBody crate = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(0.25f, 0.25f, 0.25f))).setMass(40f);
        crate.setPosition(2f, 0.9f, 0f);
        crate.setVelocity(new Vec3(-10f, 0f, 0f));
        crate.setGravityScale(0f);
        world.add(crate);
        run(world, 2f);
        assertEquals(0f, c.getVelocity().length(), 0.05f);
    }

    @Test
    void standingOnACrateIsNotAKnock() {
        PhysicsWorld world = flat();
        RigidBody crate = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(new Vec3(1f, 0.5f, 1f))).setMass(50f);
        crate.setPosition(0f, 0.5f, 0f);
        world.add(crate);
        CharacterController c = character(world, new Vec3(0f, 1f, 0f));
        run(world, 3f);
        assertTrue(c.isGrounded());
        assertEquals(0f, c.getPosition().x(), 0.02f);
        assertEquals(0f, c.getPosition().z(), 0.02f);
    }
}
