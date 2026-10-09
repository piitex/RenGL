package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ColliderViewTest {
    private static final float DT = 1f / 60f;

    private static List<Node3D> bodies(ColliderView view) {
        return view.getNode().getChildren().get(0).getChildren();
    }

    private static List<Node3D> shown(Node3D group) {
        return group.getChildren().stream().filter(Node3D::isVisible).toList();
    }

    private static Vec3 worldPosition(Node3D node) {
        return node.worldMatrix().decompose().position();
    }

    private static void assertNear(Vec3 expected, Vec3 actual, float tolerance) {
        assertTrue(expected.distance(actual) <= tolerance, "expected " + expected + " but was " + actual);
    }

    @Test
    void drawsEachShapeWhereItIs() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody table = new RigidBody(BodyType.DYNAMIC)
                .addShape(new BoxShape(1f, 0.05f, 0.5f), new Vec3(0f, 0.75f, 0f), Quat.IDENTITY)
                .addShape(new BoxShape(0.05f, 0.35f, 0.05f), new Vec3(0.9f, 0.35f, 0.4f), Quat.IDENTITY)
                .setPosition(2f, 0f, -1f).setRotation(Quat.fromAxisAngle(Vec3.UP, 0.7f));
        world.add(table);
        ColliderView view = new ColliderView(world);
        assertEquals(2, bodies(view).size());
        for (int i = 0; i < 2; i++) {
            Node3D node = bodies(view).get(i);
            assertNear(table.shapePose(i).position(), node.getPosition(), 1e-5f);
            assertEquals(table.getRotation().w(), node.getRotation().w(), 1e-5f);
            assertFalse(node.isPickable());
            assertFalse(node.isCastShadow());
        }
        // It follows the body as the world runs.
        for (int i = 0; i < 30; i++) world.step(DT);
        assertNear(table.shapePose(0).position(), bodies(view).getFirst().getPosition(), 1e-5f);
    }

    @Test
    void followsBodiesComingGoingAndChanging() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.5f)).setPosition(0, 2, 0);
        world.add(ball);
        ColliderView view = new ColliderView(world);
        assertEquals(1, bodies(view).size());
        RigidBody crate = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(0.5f, 0.5f, 0.5f)).setPosition(3, 2, 0);
        world.add(crate);
        world.step(DT);
        assertEquals(2, bodies(view).size());
        crate.addShape(new SphereShape(0.3f), new Vec3(0, 0.8f, 0), Quat.IDENTITY);
        world.step(DT);
        assertEquals(3, bodies(view).size());
        world.remove(ball);
        world.step(DT);
        assertEquals(2, bodies(view).size());
    }

    @Test
    void colourSaysWhatABodyIsDoing() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody awake = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.5f)).setPosition(0, 3, 0);
        RigidBody asleep = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.5f)).setPosition(3, 3, 0);
        RigidBody wall = new RigidBody(BodyType.STATIC).addShape(new BoxShape(1, 1, 1)).setPosition(-5, 0, 0);
        RigidBody zone = new RigidBody(BodyType.STATIC).addShape(new BoxShape(1, 1, 1)).setPosition(-10, 0, 0).setTrigger(true);
        RigidBody platform = new RigidBody(BodyType.KINEMATIC).addShape(new BoxShape(1, 0.1f, 1)).setPosition(10, 0, 0);
        world.add(awake).add(asleep).add(wall).add(zone).add(platform);
        CharacterController who = new CharacterController(new Node3D("who"), 0.3f, 1.8f);
        world.addCharacter(who);
        asleep.sleep();
        ColliderView view = new ColliderView(world);
        List<Node3D> nodes = bodies(view);
        // Green, grey-blue, light grey, yellow, blue and pink, in the order the bodies were added.
        float[][] expected = {{0.3f, 1f, 0.4f}, {0.5f, 0.58f, 0.75f}, {0.85f, 0.85f, 0.85f}, {1f, 0.85f, 0.2f}, {0.3f, 0.65f, 1f}, {1f, 0.45f, 0.85f}};
        for (int i = 0; i < expected.length; i++) {
            var m = nodes.get(i).getMaterial();
            assertArrayEquals(expected[i], new float[]{m.getRed(), m.getGreen(), m.getBlue()}, 1e-6f, "body " + i);
            assertTrue(m.isBlend() && m.isUnlit() && m.isDoubleSided());
        }
        // Waking the sleeping ball turns it green.
        asleep.wakeUp();
        world.step(DT);
        assertEquals(1f, nodes.get(1).getMaterial().getGreen(), 1e-6f);
        // Static bodies can be hidden.
        view.setShowStatic(false);
        assertFalse(nodes.get(2).isVisible());
        assertFalse(nodes.get(3).isVisible());
        assertTrue(nodes.get(0).isVisible());
    }

    @Test
    void marksContactsAndJoints() {
        PhysicsWorld world = new PhysicsWorld();
        world.add(new RigidBody(BodyType.STATIC).addShape(new PlaneShape()));
        RigidBody crate = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(0.5f, 0.5f, 0.5f)).setPosition(0, 0.5f, 0);
        RigidBody lamp = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.2f)).setPosition(5, 3, 0);
        RigidBody door = new RigidBody(BodyType.DYNAMIC).addShape(new BoxShape(0.5f, 1f, 0.05f)).setPosition(-5, 1.2f, 0);
        world.add(crate).add(lamp).add(door);
        world.addJoint(new DistanceJoint(lamp, new Vec3(5, 3.2f, 0), null, new Vec3(5, 5, 0)).setMinLength(0f));
        world.addJoint(new HingeJoint(door, null, new Vec3(-5.5f, 1.2f, 0), Vec3.UP));
        ColliderView view = new ColliderView(world);
        for (int i = 0; i < 10; i++) world.step(DT);
        Node3D contacts = view.getNode().getChildren().get(1), joints = view.getNode().getChildren().get(2);
        // The crate rests on four corners: a dot and a line each.
        assertEquals(8, shown(contacts).size());
        for (Node3D mark : shown(contacts)) {
            if (mark.getScale().z() < 0.1f) assertEquals(0f, mark.getPosition().y(), 0.02f); // a dot on the floor
        }
        // The rope: a dot at each end and a line between them. The hinge: one dot and its axis.
        assertEquals(5, shown(joints).size());
        boolean ropeLine = shown(joints).stream().anyMatch(n -> Math.abs(n.getScale().z() - 1.8f) < 0.05f);
        assertTrue(ropeLine, "a line as long as the rope");
        view.setShowContacts(false).setShowJoints(false);
        assertTrue(shown(contacts).isEmpty());
        assertTrue(shown(joints).isEmpty());
    }

    @Test
    void movesSmoothlyWithTheNodes() {
        PhysicsWorld world = new PhysicsWorld();
        Node3D node = new Node3D("ball");
        RigidBody ball = new RigidBody(node, BodyType.DYNAMIC).addShape(new SphereShape(0.5f));
        node.setPosition(0, 5, 0);
        world.add(ball);
        ColliderView view = new ColliderView(world);
        world.update(DT * 2.5f); // two steps and half of the next
        assertEquals(0.5f, world.getInterpolationAlpha(), 1e-4f);
        assertNear(node.getPosition(), bodies(view).getFirst().getPosition(), 1e-5f);
        assertTrue(node.getPosition().y() > ball.getPosition().y(), "half a step behind the body");
    }

    @Test
    void underAMovedParentItStillDrawsInTheWorld() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody box = new RigidBody(BodyType.STATIC).addShape(new BoxShape(1, 1, 1)).setPosition(4, 0, 2);
        world.add(box);
        Node3D holder = new Node3D("holder").setPosition(10, 0, 0).setRotation(Quat.fromAxisAngle(Vec3.UP, 1f));
        ColliderView view = new ColliderView(world);
        holder.add(view.getNode());
        view.refresh();
        assertNear(new Vec3(4, 0, 2), worldPosition(bodies(view).getFirst()), 1e-4f);
    }

    @Test
    void hiddenAndDisposedViewsStopFollowing() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.5f)).setPosition(0, 5, 0);
        world.add(ball);
        Node3D root = new Node3D("root");
        ColliderView view = new ColliderView(world);
        root.add(view.getNode());
        view.setVisible(false);
        Vec3 before = bodies(view).getFirst().getPosition();
        for (int i = 0; i < 10; i++) world.step(DT);
        assertEquals(before, bodies(view).getFirst().getPosition());
        view.setVisible(true);
        assertNear(ball.getPosition(), bodies(view).getFirst().getPosition(), 1e-5f);
        view.dispose();
        assertNull(view.getNode().getParent());
        Vec3 last = bodies(view).getFirst().getPosition();
        world.step(DT);
        assertEquals(last, bodies(view).getFirst().getPosition());
    }

    @Test
    void meshesWrapTheShapes() {
        List<Shape> shapes = List.of(new BoxShape(0.5f, 1f, 2f), new SphereShape(0.7f), CapsuleShape.ofHeight(0.3f, 2f),
                ConvexHullShape.of(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1), new Vec3(0.6f, 0.6f, 0.6f)),
                new MeshShape(new float[]{0, 0, 0, 4, 0, 0, 0, 0, -4}, new int[]{0, 1, 2}), new PlaneShape());
        for (Shape shape : shapes) {
            ColliderMeshes.Built built = ColliderMeshes.of(shape);
            Mesh mesh = built.mesh();
            assertTrue(mesh.vertexCount() >= 3 && mesh.indexCount() % 3 == 0, shape.toString());
            float[] v = mesh.vertices();
            for (int i = 0; i < mesh.vertexCount(); i++) {
                Vec3 p = new Vec3(v[i * Mesh.FLOATS_PER_VERTEX], v[i * Mesh.FLOATS_PER_VERTEX + 1], v[i * Mesh.FLOATS_PER_VERTEX + 2]);
                float u = v[i * Mesh.FLOATS_PER_VERTEX + 6], t = v[i * Mesh.FLOATS_PER_VERTEX + 7];
                // Every corner sits on a line of the texture, so the lines run along the edges.
                assertEquals(Math.round(u), u, 1e-6f, shape + " u");
                assertEquals(Math.round(t), t, 1e-6f, shape + " v");
                switch (shape) {
                    case BoxShape b -> assertTrue(Math.abs(p.x()) > b.halfExtents().x() || Math.abs(p.y()) > b.halfExtents().y() || Math.abs(p.z()) > b.halfExtents().z());
                    case SphereShape s -> assertTrue(p.length() > s.radius());
                    default -> {
                    }
                }
            }
            assertEquals(shape instanceof ConvexHullShape || shape instanceof MeshShape ? ColliderMeshes.Pattern.TRIANGLES : ColliderMeshes.Pattern.SQUARES, built.pattern());
        }
        // The pattern's texture: clear inside a cell, solid on its edges.
        var texture = ColliderMeshes.Pattern.SQUARES.texture();
        assertNotNull(texture);
        assertSame(texture, ColliderMeshes.Pattern.SQUARES.texture());
    }

    @Test
    void outlinesAreRealLinesOfEachShape() {
        Mesh box = ColliderLines.of(new BoxShape(0.5f, 1f, 2f));
        assertTrue(box.isLines());
        assertEquals(24, box.indexCount(), "twelve edges");
        assertEquals(96 * 2, ColliderLines.of(new SphereShape(0.7f)).indexCount(), "three circles of 32");
        Mesh capsule = ColliderLines.of(CapsuleShape.ofHeight(0.3f, 2f));
        assertEquals((2 * 32 + 4 + 4 * 16) * 2, capsule.indexCount(), "two rings, four rods and four half circles");
        ConvexHullShape pyramid = ConvexHullShape.of(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1));
        assertEquals(12, ColliderLines.of(pyramid).indexCount(), "a tetrahedron's six edges");
        MeshShape sheet = new MeshShape(new float[]{0, 0, 0, 4, 0, 0, 0, 0, -4, 4, 0, -4}, new int[]{0, 1, 2, 2, 1, 3});
        assertEquals(8, ColliderLines.of(sheet).indexCount(), "a flat sheet of two triangles: its four borders, and the shared edge is a seam");
        assertEquals(102 * 2, ColliderLines.of(new PlaneShape()).indexCount(), "a grid of 51 lines each way");
        // A box's lines sit just outside it.
        float[] v = box.vertices();
        for (int i = 0; i < box.vertexCount(); i++) {
            float x = v[i * Mesh.FLOATS_PER_VERTEX], y = v[i * Mesh.FLOATS_PER_VERTEX + 1], z = v[i * Mesh.FLOATS_PER_VERTEX + 2];
            assertTrue(Math.abs(x) > 0.5f && Math.abs(y) > 1f && Math.abs(z) > 2f);
        }
    }

    @Test
    void eachSurfaceCarriesItsOutlineInTheSameColour() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.5f)).setPosition(0, 2, 0);
        world.add(ball);
        ColliderView view = new ColliderView(world);
        Node3D surface = bodies(view).getFirst();
        assertEquals(1, surface.getChildren().size());
        Node3D outline = surface.getChildren().getFirst();
        assertTrue(outline.getMesh().isLines());
        assertTrue(outline.getMaterial().isUnlit());
        assertFalse(outline.isPickable());
        assertEquals(surface.getMaterial().getGreen(), outline.getMaterial().getGreen(), 1e-6f);
        ball.sleep();
        world.step(DT);
        assertEquals(0.58f, outline.getMaterial().getGreen(), 1e-6f, "it follows the state too");
        view.setVisible(false);
        assertFalse(view.getNode().isVisible(), "hiding the view hides the lines with the surfaces");
    }
}
