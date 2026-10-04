package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Node3D;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DynamicsTest {
    private static final float DT = 1f / 60f;
    private static final float G = 9.81f;

    private static void run(PhysicsWorld world, float seconds) {
        int steps = Math.round(seconds / DT);
        for (int i = 0; i < steps; i++) world.step(DT);
    }

    private static void assertVec(Vec3 expected, Vec3 actual, float tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance, "x of " + actual);
        assertEquals(expected.y(), actual.y(), tolerance, "y of " + actual);
        assertEquals(expected.z(), actual.z(), tolerance, "z of " + actual);
    }

    private static RigidBody ball(float radius, float mass) {
        return new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(radius)).setMass(mass).setAngularDamping(0f);
    }

    @Test
    void freeFallFollowsGravity() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody body = ball(0.5f, 1f);
        world.add(body.setPosition(0, 100, 0));
        run(world, 2f);
        assertEquals(-G * 2f, body.getVelocity().y(), 1e-3f);
        // Semi-implicit Euler lands within g*dt*t/2 of the exact curve, and on the discrete formula exactly.
        float n = 120;
        assertEquals(100f - G * DT * DT * n * (n + 1) / 2f, body.getPosition().y(), 1e-3f);
        assertEquals(100f - 0.5f * G * 4f, body.getPosition().y(), G * DT * 2f / 2f + 1e-3f);
        assertEquals(0f, body.getPosition().x());
    }

    @Test
    void massDoesNotChangeTheFall() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody light = ball(0.5f, 0.1f), heavy = ball(0.5f, 500f);
        world.add(light).add(heavy);
        run(world, 1f);
        assertEquals(light.getPosition().y(), heavy.getPosition().y(), 1e-5f);
    }

    @Test
    void projectileLandsAtTheExpectedRange() {
        PhysicsWorld world = new PhysicsWorld().setFixedStep(1f / 240f);
        RigidBody body = ball(0.1f, 1f).setVelocity(10f, 10f, 0f);
        world.add(body);
        float flight = 2f * 10f / G;
        int steps = Math.round(flight * 240f);
        for (int i = 0; i < steps; i++) world.step(1f / 240f);
        assertEquals(10f * flight, body.getPosition().x(), 0.05f);
        assertEquals(0f, body.getPosition().y(), 0.05f);
        assertEquals(-10f, body.getVelocity().y(), 0.05f);
    }

    @Test
    void gravityScaleAndWorldGravity() {
        PhysicsWorld world = new PhysicsWorld().setGravity(new Vec3(0, 0, -2f));
        RigidBody floaty = ball(0.5f, 1f).setGravityScale(0f);
        RigidBody normal = ball(0.5f, 1f);
        RigidBody rising = ball(0.5f, 1f).setGravityScale(-1f);
        world.add(floaty).add(normal).add(rising);
        run(world, 1f);
        assertEquals(Vec3.ZERO, floaty.getVelocity());
        assertEquals(-2f, normal.getVelocity().z(), 1e-4f);
        assertEquals(2f, rising.getVelocity().z(), 1e-4f);
    }

    @Test
    void forceGivesAccelerationOverMassAndIsClearedAfterAStep() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody body = ball(0.5f, 4f);
        world.add(body);
        body.applyForce(new Vec3(8f, 0, 0));
        world.step(DT);
        assertEquals(2f * DT, body.getVelocity().x(), 1e-6f);
        assertEquals(Vec3.ZERO, body.getForce());
        world.step(DT);
        assertEquals(2f * DT, body.getVelocity().x(), 1e-6f);
    }

    @Test
    void steadyForceFromTheStepHandler() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody body = ball(0.5f, 2f);
        world.add(body).setStepHandler((w, dt) -> body.applyForce(new Vec3(0, 0, 4f)));
        run(world, 3f);
        assertEquals(6f, body.getVelocity().z(), 1e-3f);
    }

    @Test
    void impulseChangesVelocityAtOnce() {
        RigidBody body = ball(0.5f, 5f);
        new PhysicsWorld().add(body);
        body.applyImpulse(new Vec3(10f, 0, 0));
        assertEquals(new Vec3(2f, 0, 0), body.getVelocity());
    }

    @Test
    void torqueOnABallFollowsItsInertia() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody body = ball(0.5f, 4f);
        assertVec(new Vec3(0.4f, 0.4f, 0.4f), body.getInertia(), 1e-6f); // 2/5 m r²
        world.add(body).setStepHandler((w, dt) -> body.applyTorque(new Vec3(0, 0, 2f)));
        run(world, 1f);
        assertVec(new Vec3(0, 0, 5f), body.getAngularVelocity(), 1e-3f);
    }

    @Test
    void boxInertiaPerAxis() {
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(BoxShape.ofSize(2f, 1f, 0.5f)).setMass(12f);
        // m/12 (b² + c²) for each axis
        assertVec(new Vec3(1f + 0.25f, 4f + 0.25f, 4f + 1f), body.getInertia(), 1e-5f);

        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        body.setAngularDamping(0f);
        world.add(body);
        body.applyTorqueImpulse(new Vec3(5f, 0, 0));
        assertVec(new Vec3(4f, 0, 0), body.getAngularVelocity(), 1e-5f);
    }

    @Test
    void inertiaFollowsTheTurnedBody() {
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(BoxShape.ofSize(2f, 1f, 0.5f)).setMass(12f);
        new PhysicsWorld().add(body);
        // Turned a quarter around Z, the long side lies along Y, so spinning around world Y is now the easy way.
        body.setRotation(Quat.fromAxisAngle(Vec3.Z, (float) Math.PI / 2));
        body.applyTorqueImpulse(new Vec3(0, 5f, 0));
        assertEquals(4f, body.getAngularVelocity().y(), 1e-4f);
    }

    @Test
    void capsuleInertia() {
        Vec3 sphere = new SphereShape(0.5f).unitInertia();
        assertVec(sphere, new CapsuleShape(0.5f, 0f).unitInertia(), 1e-6f);
        Vec3 tall = CapsuleShape.ofHeight(0.3f, 1.8f).unitInertia();
        assertEquals(tall.x(), tall.z());
        assertTrue(tall.y() < tall.x(), "a tall capsule spins more easily around its length");
        assertEquals(0.6f, CapsuleShape.ofHeight(0.3f, 1.8f).halfHeight(), 1e-6f);
    }

    @Test
    void compoundShapesShareMassByVolume() {
        RigidBody body = new RigidBody(BodyType.DYNAMIC)
                .addShape(new SphereShape(0.5f), new Vec3(-1, 0, 0))
                .addShape(new SphereShape(0.5f), new Vec3(1, 0, 0))
                .setMass(2f);
        assertVec(Vec3.ZERO, body.getLocalCenterOfMass(), 1e-6f);
        // Each 1 kg ball: 2/5 m r² of its own, plus m d² for being 1 m out.
        float own = 0.4f * 0.25f;
        assertVec(new Vec3(2 * own, 2 * (own + 1f), 2 * (own + 1f)), body.getInertia(), 1e-5f);

        RigidBody lopsided = new RigidBody(BodyType.DYNAMIC)
                .addShape(new SphereShape(1f))
                .addShape(new SphereShape(1f), new Vec3(2, 0, 0));
        assertVec(new Vec3(1, 0, 0), lopsided.getLocalCenterOfMass(), 1e-6f);
    }

    @Test
    void densitySetsMass() {
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(BoxShape.ofSize(1, 2, 0.5f)).setDensity(1000f);
        assertEquals(1000f, body.getMass(), 1e-3f);
    }

    @Test
    void offCentreImpulseSpinsTheBody() {
        RigidBody body = ball(0.5f, 4f);
        new PhysicsWorld().add(body);
        body.applyImpulseAtPoint(new Vec3(0, 0, -1f), new Vec3(0.5f, 0, 0));
        assertVec(new Vec3(0, 0, -0.25f), body.getVelocity(), 1e-6f);
        // r × J = (0.5,0,0) × (0,0,-1) = (0, 0.5, 0); over I = 0.4
        assertVec(new Vec3(0, 1.25f, 0), body.getAngularVelocity(), 1e-5f);
        assertVec(new Vec3(0, 0, -0.25f - 0.625f), body.getPointVelocity(new Vec3(0.5f, 0, 0)), 1e-5f);
    }

    @Test
    void forceAtPointAddsTorque() {
        RigidBody body = ball(0.5f, 1f);
        new PhysicsWorld().add(body);
        body.applyForceAtPoint(new Vec3(0, 10f, 0), new Vec3(1f, 0, 0));
        assertEquals(new Vec3(0, 10f, 0), body.getForce());
        assertVec(new Vec3(0, 0, 10f), body.getTorque(), 1e-6f);
    }

    @Test
    void spinTurnsTheBody() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody body = ball(0.5f, 1f).setAngularVelocity(new Vec3(0, (float) Math.PI / 2, 0));
        world.add(body);
        run(world, 1f);
        Vec3 forward = body.getRotation().rotate(new Vec3(0, 0, -1));
        assertVec(new Vec3(-1, 0, 0), forward, 1e-3f);
    }

    @Test
    void spinAroundAnOffsetCentreOfMassMovesTheOrigin() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.5f), new Vec3(1, 0, 0)).setAngularDamping(0f);
        world.add(body);
        body.setAngularVelocity(new Vec3(0, (float) Math.PI, 0));
        run(world, 1f);
        assertVec(new Vec3(1, 0, 0), body.getCenterOfMass(), 1e-4f);
        assertVec(new Vec3(2, 0, 0), body.getPosition(), 1e-3f);
    }

    @Test
    void dampingDecaysExponentially() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody body = ball(0.5f, 1f).setLinearDamping(0.5f).setAngularDamping(1f)
                .setVelocity(10f, 0, 0).setAngularVelocity(new Vec3(0, 4f, 0));
        world.add(body);
        run(world, 2f);
        assertEquals(10f * (float) Math.exp(-1), body.getVelocity().x(), 1e-3f);
        assertEquals(4f * (float) Math.exp(-2), body.getAngularVelocity().y(), 1e-3f);
    }

    @Test
    void freeSpinKeepsItsEnergy() {
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(BoxShape.ofSize(1, 1, 1)).setMass(3f).setAngularDamping(0f)
                .setVelocity(1, 2, 3).setAngularVelocity(new Vec3(0, 2f, 0));
        world.add(body);
        float before = body.getKineticEnergy();
        run(world, 5f);
        assertEquals(before, body.getKineticEnergy(), before * 1e-4f);
    }

    @Test
    void lockedAxesDoNotMove() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody body = ball(0.5f, 1f).lockPosition(false, true, false).lockRotation(true, false, true);
        world.add(body);
        body.applyImpulse(new Vec3(1, 1, 0));
        body.applyTorqueImpulse(new Vec3(1, 1, 1));
        run(world, 1f);
        assertEquals(0f, body.getPosition().y());
        assertEquals(1f, body.getPosition().x(), 1e-4f);
        assertEquals(0f, body.getAngularVelocity().x());
        assertEquals(0f, body.getAngularVelocity().z());
        assertTrue(body.getAngularVelocity().y() > 0f);
        assertTrue(body.isPositionLocked(1) && !body.isPositionLocked(0));
    }

    @Test
    void kinematicAndStaticBodiesIgnoreForces() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody lift = new RigidBody(BodyType.KINEMATIC).addShape(BoxShape.ofSize(2, 0.2f, 2)).setVelocity(0, 1f, 0);
        RigidBody ground = new RigidBody(BodyType.STATIC).addShape(BoxShape.ofSize(10, 1, 10)).setVelocity(0, 5f, 0);
        world.add(lift).add(ground);
        lift.applyForce(new Vec3(100, 0, 0)).applyImpulse(new Vec3(100, 0, 0));
        run(world, 2f);
        assertVec(new Vec3(0, 2f, 0), lift.getPosition(), 1e-4f);
        assertEquals(0f, lift.getInverseMass());
        assertEquals(Vec3.ZERO, ground.getPosition());
        assertEquals(Vec3.ZERO, ground.getVelocity());
        assertEquals(0f, ground.getKineticEnergy());
    }

    @Test
    void updateRunsWholeFixedSteps() {
        PhysicsWorld world = new PhysicsWorld();
        world.update(1f / 30f);
        assertEquals(2, world.getStepCount());
        world.update(1f / 120f);
        assertEquals(2, world.getStepCount());
        world.update(1f / 120f);
        assertEquals(3, world.getStepCount());
        world.update(10f); // a long pause: capped, and the rest dropped
        assertEquals(3 + world.getMaxSteps(), world.getStepCount());
        assertTrue(world.getInterpolationAlpha() < 1f);
    }

    @Test
    void nodesArePlacedBetweenSteps() {
        Node3D node = new Node3D("ball");
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO);
        RigidBody body = new RigidBody(node).addShape(new SphereShape(0.5f)).setVelocity(6f, 0, 0);
        world.add(body);
        world.update(DT * 1.5f);
        assertEquals(1, world.getStepCount());
        assertEquals(0.1f, body.getPosition().x(), 1e-5f);
        assertEquals(0.05f, node.getPosition().x(), 1e-5f, "half way between the start and the first step");

        world.setInterpolation(false);
        world.update(0f);
        assertEquals(0.1f, node.getPosition().x(), 1e-5f);
    }

    @Test
    void bodyStartsAtItsNodeAndWritesBack() {
        Node3D node = new Node3D("crate").setPosition(1, 5, 2);
        node.setRotation(Quat.fromAxisAngle(Vec3.UP, 0.5f));
        PhysicsWorld world = new PhysicsWorld().setInterpolation(false);
        RigidBody body = new RigidBody(node).addShape(BoxShape.ofSize(1, 1, 1));
        world.add(body);
        assertVec(new Vec3(1, 5, 2), body.getPosition(), 1e-5f);
        assertEquals(0.5f, 2f * (float) Math.acos(body.getRotation().w()), 1e-4f);
        world.update(1f);
        assertEquals(body.getPosition(), node.getPosition());
        assertTrue(node.getPosition().y() < 5f);
    }

    @Test
    void movingTheNodeTeleportsTheBody() {
        Node3D node = new Node3D("ball");
        PhysicsWorld world = new PhysicsWorld().setGravity(Vec3.ZERO).setInterpolation(false);
        RigidBody body = new RigidBody(node).addShape(new SphereShape(0.5f)).setVelocity(1f, 0, 0);
        world.add(body);
        world.step(DT);
        node.setPosition(0, 10, 0);
        world.step(DT);
        assertVec(new Vec3(DT, 10, 0), body.getPosition(), 1e-5f);
        assertEquals(1f, body.getVelocity().x(), 1e-6f);
    }

    @Test
    void childNodeIsWrittenInItsParentsSpace() {
        Node3D parent = new Node3D("platform").setPosition(10, 0, 0);
        parent.setRotation(Quat.fromAxisAngle(Vec3.UP, (float) Math.PI / 2));
        parent.setScale(2f);
        Node3D child = new Node3D("ball").setPosition(1, 3, 0);
        parent.add(child);
        PhysicsWorld world = new PhysicsWorld().setInterpolation(false);
        RigidBody body = new RigidBody(child).addShape(new SphereShape(0.5f));
        world.add(body);
        // In world space: the parent turns local +X to -Z and doubles it.
        assertVec(new Vec3(10, 6, -2), body.getPosition(), 1e-4f);
        assertEquals(1f, ((SphereShape) body.getWorldScaledShapes().getFirst().shape()).radius(), 1e-5f);

        world.update(0.5f);
        Vec3 world1 = body.getPosition();
        assertVec(world1, child.worldMatrix().transformPoint(Vec3.ZERO), 1e-4f);
        assertEquals(1f, child.getPosition().x(), 1e-4f, "falling straight down keeps the local X");

        // Moving the parent moves the child with it, and the body follows the node.
        parent.setPosition(0, 0, 0);
        world.update(0f);
        assertVec(child.worldMatrix().transformPoint(Vec3.ZERO), body.getPosition(), 1e-4f);
    }

    @Test
    void nodeScaleStretchesTheShapes() {
        Node3D node = new Node3D("slab").setScale(new Vec3(4, 0.5f, 2));
        RigidBody body = new RigidBody(node).addShape(BoxShape.ofSize(1, 1, 1), new Vec3(0, 1, 0));
        new PhysicsWorld().add(body);
        BodyShape shape = body.getWorldScaledShapes().getFirst();
        assertEquals(new Vec3(2, 0.25f, 1), ((BoxShape) shape.shape()).halfExtents());
        assertEquals(new Vec3(0, 0.5f, 0), shape.offset());
        assertEquals(new Vec3(0, 0.5f, 0), body.getLocalCenterOfMass());
    }

    @Test
    void bodyBelongsToOneWorld() {
        RigidBody body = ball(0.5f, 1f);
        PhysicsWorld a = new PhysicsWorld(), b = new PhysicsWorld();
        a.add(body).add(body);
        assertEquals(1, a.bodies().size());
        assertThrows(IllegalStateException.class, () -> b.add(body));
        assertTrue(a.remove(body));
        b.add(body);
        assertSame(b, body.getWorld());
    }

    @Test
    void removedInTheStepHandlerStopsAtOnce() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody body = ball(0.5f, 1f);
        world.add(body).setStepHandler((w, dt) -> w.remove(body));
        world.step(DT);
        assertEquals(Vec3.ZERO, body.getVelocity());
    }

    @Test
    void badValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new SphereShape(0f));
        assertThrows(IllegalArgumentException.class, () -> new BoxShape(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> ball(0.5f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new PhysicsWorld().setFixedStep(0f));
        assertThrows(IllegalStateException.class, () -> new RigidBody(BodyType.DYNAMIC).setDensity(1f));
    }
}
