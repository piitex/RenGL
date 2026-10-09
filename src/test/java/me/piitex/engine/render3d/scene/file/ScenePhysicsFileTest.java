package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.physics.BodyShape;
import me.piitex.engine.render3d.physics.BodyType;
import me.piitex.engine.render3d.physics.BoxShape;
import me.piitex.engine.render3d.physics.CapsuleShape;
import me.piitex.engine.render3d.physics.CharacterController;
import me.piitex.engine.render3d.physics.ConvexHullShape;
import me.piitex.engine.render3d.physics.BallJoint;
import me.piitex.engine.render3d.physics.DistanceJoint;
import me.piitex.engine.render3d.physics.HingeJoint;
import me.piitex.engine.render3d.physics.MeshShape;
import me.piitex.engine.render3d.physics.PhysicsWorld;
import me.piitex.engine.render3d.physics.RigidBody;
import me.piitex.engine.render3d.physics.SliderJoint;
import me.piitex.engine.render3d.physics.SphereShape;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
class ScenePhysicsFileTest {
    @TempDir
    Path temp;

    private static final String SCENE = """
            {
              "format": 1,
              "world": { "gravity": [0, -5, 0], "substeps": 3 },
              "nodes": [
                { "name": "Floor", "shape": "PLANE", "width": 20, "depth": 20, "body": "STATIC", "friction": 0.8 },
                { "name": "Crate", "shape": "CUBE", "position": [0, 3, 0], "body": "DYNAMIC", "mass": 20, "bounce": 0.2, "linearDamping": 0.1 },
                { "name": "Ball", "shape": "SPHERE", "radius": 0.25, "position": [3, 0.25, 0], "body": "DYNAMIC", "canSleep": false },
                { "name": "Zone", "position": [5, 1, 0], "body": "STATIC", "trigger": true, "collider": "BOX", "colliderSize": [2, 2, 2], "layer": 4 },
                { "name": "Lift", "shape": "CUBE", "position": [-4, 0.5, 0], "body": "KINEMATIC", "collider": "CAPSULE",
                  "colliderRadius": 0.4, "colliderHeight": 1.5, "colliderOffset": [0, 0.2, 0] },
                { "name": "Player", "position": [-2, 0, 3], "body": "CHARACTER", "characterRadius": 0.4, "characterHeight": 1.7,
                  "slopeLimit": 40, "stepHeight": 0.25, "characterMass": 70, "mask": 3 },
                { "name": "Door", "shape": "CUBE", "position": [0, 1, -4], "scale": [1, 2, 0.1], "body": "DYNAMIC" },
                { "name": "Lamp", "shape": "SPHERE", "radius": 0.2, "position": [6, 3, 4], "body": "DYNAMIC" }
              ],
              "joints": [
                { "type": "HINGE", "name": "Door hinge", "bodyA": "Door", "anchor": [-0.5, 1, -4], "limited": true, "lower": 0, "upper": 100 },
                { "type": "DISTANCE", "name": "Lamp rope", "bodyA": "Lamp", "anchor": [6, 3.2, 4], "anchorB": [6, 5, 4], "rope": true },
                { "type": "SLIDER", "name": "Rail", "bodyA": "Ball", "anchor": [3, 0.25, 0], "axis": [1, 0, 0], "motor": true,
                  "motorSpeed": 1, "motorStrength": 50 },
                { "type": "BALL", "name": "Shoulder", "bodyA": "Lamp", "anchor": [6, 3, 4], "axis": [1, 0, 0], "swingLimited": true,
                  "swing": 30, "limited": true, "lower": -20, "upper": 50 },
                { "type": "BALL", "name": "Ghost", "bodyA": "Nobody", "anchor": [0, 0, 0] }
              ]
            }
            """;

    private static SceneProperty<Node3D> node(String name) {
        return SceneKinds.node().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    private static <T> boolean has(List<SceneProperty<T>> list, String name, T target) {
        return list.stream().anyMatch(p -> p.getName().equals(name) && p.appliesTo(target));
    }

    private static BodyShape only(RigidBody body) {
        assertEquals(1, body.getWorldScaledShapes().size());
        return body.getWorldScaledShapes().getFirst();
    }

    @Test
    void physicsRoundTrips() {
        String first = Scene3DFile.toJson(Scene3DFile.parse(SCENE, temp, false));
        String second = Scene3DFile.toJson(Scene3DFile.parse(first, temp, false));
        assertEquals(first, second);
        for (String expected : List.of("\"gravity\"", "\"substeps\": 3","\"body\": \"CHARACTER\"", "\"colliderOffset\"", "\"characterMass\"", "\"trigger\": true",
                "\"joints\"", "\"type\": \"SLIDER\"", "\"motorStrength\"", "\"rope\": true", "\"Ghost\"")) {
            assertTrue(first.contains(expected), expected + " in " + first);
        }
    }

    @Test
    void defaultsAreLeftOut() {
        Scene3D scene = new Scene3D();
        Node3D crate = SceneKinds.newNode(Shape.CUBE, "Crate");
        node("body").set(crate, BodyKind.DYNAMIC);
        scene.add(crate);
        scene.add(SceneKinds.newNode(Shape.SPHERE, "Decoration"));
        String json = Scene3DFile.toJson(scene);
        assertTrue(json.contains("\"body\": \"DYNAMIC\""));
        for (String left : List.of("mass", "friction", "bounce", "collider", "layer", "mask", "gravity", "joints", "canSleep")) {
            assertFalse(json.contains("\"" + left + "\""), left + " in " + json);
        }
        assertEquals(1, json.split("\"body\"").length - 1, "a node without a body says nothing about one");
    }

    @Test
    void loadingToRunBuildsTheWorld() {
        Scene3D scene = Scene3DFile.parse(SCENE, temp, true);
        PhysicsWorld world = scene.getPhysics();
        assertNotNull(world);
        assertEquals(new Vec3(0, -5, 0), world.getGravity());
        assertEquals(8, world.bodies().size(), "seven bodies and the character's");
        assertEquals(1, world.characters().size());

        RigidBody floor = Scene3DFile.findBody(scene, "Floor");
        assertEquals(BodyType.STATIC, floor.getType());
        assertEquals(0.8f, floor.getMaterial().friction(), 1e-6f);
        // A sheet collides as a thin box with its top at the surface.
        BoxShape slab = (BoxShape) only(floor).shape();
        assertEquals(new Vec3(10f, 0.01f, 10f), slab.halfExtents());
        assertEquals(-0.01f, only(floor).offset().y(), 1e-6f);

        RigidBody crate = Scene3DFile.findBody(scene, "Crate");
        assertEquals(BodyType.DYNAMIC, crate.getType());
        assertEquals(20f, crate.getMass(), 1e-5f);
        assertEquals(0.2f, crate.getMaterial().restitution(), 1e-6f);
        assertEquals(0.1f, crate.getLinearDamping(), 1e-6f);
        assertEquals(new Vec3(0.5f, 0.5f, 0.5f), ((BoxShape) only(crate).shape()).halfExtents());

        RigidBody ball = Scene3DFile.findBody(scene, "Ball");
        assertEquals(0.25f, ((SphereShape) only(ball).shape()).radius(), 1e-6f);
        assertFalse(ball.isSleepingAllowed());

        RigidBody zone = Scene3DFile.findBody(scene, "Zone");
        assertTrue(zone.isTrigger());
        assertEquals(4, zone.getCollisionLayer());
        assertEquals(new Vec3(1, 1, 1), ((BoxShape) only(zone).shape()).halfExtents());

        RigidBody lift = Scene3DFile.findBody(scene, "Lift");
        assertEquals(BodyType.KINEMATIC, lift.getType());
        CapsuleShape capsule = (CapsuleShape) only(lift).shape();
        assertEquals(0.4f, capsule.radius(), 1e-6f);
        assertEquals(0.35f, capsule.halfHeight(), 1e-6f);
        assertEquals(new Vec3(0f, 0.2f, 0f), only(lift).offset());

        // The node's scale stretches the collider, as for any body.
        assertEquals(new Vec3(0.5f, 1f, 0.05f), ((BoxShape) only(Scene3DFile.findBody(scene, "Door")).shape()).halfExtents());

        CharacterController player = Scene3DFile.findCharacter(scene, "Player");
        assertEquals(40f, player.getSlopeLimit(), 1e-6f);
        assertEquals(0.25f, player.getStepHeight(), 1e-6f);
        assertEquals(70f, player.getMass(), 1e-6f);
        assertEquals(3, player.getBody().getCollisionMask());
        assertEquals(0.4f, ((CapsuleShape) only(player.getBody()).shape()).radius(), 1e-6f);
        assertEquals(new Vec3(-2, 0, 3), player.getPosition());
        assertSame(player.getBody(), Scene3DFile.findBody(scene, "Player"));
        assertNull(Scene3DFile.findCharacter(scene, "Crate"));
        assertNull(Scene3DFile.findBody(scene, "No such node"));
    }

    @Test
    void jointsAreMadeAndFoundByName() {
        Scene3D scene = Scene3DFile.parse(SCENE, temp, true);
        PhysicsWorld world = scene.getPhysics();
        // The joint naming a node that isn't there is left out, and the scene still loads.
        assertEquals(3, world.getSubsteps());
        assertEquals(4, world.joints().size());
        assertNull(Scene3DFile.findJoint(scene, "Ghost"));
        assertEquals(5, Scene3DFile.joints(scene).size(), "but kept in the scene, so saving doesn't lose it");

        HingeJoint hinge = (HingeJoint) Scene3DFile.findJoint(scene, "Door hinge");
        assertSame(Scene3DFile.findBody(scene, "Door"), hinge.getBodyA());
        assertNull(hinge.getBodyB(), "held to the world");
        assertTrue(hinge.hasLimits());
        assertEquals(0f, hinge.getLowerLimit(), 1e-6f);
        assertEquals((float) Math.toRadians(100), hinge.getUpperLimit(), 1e-6f);
        assertEquals(new Vec3(-0.5f, 1f, -4f), hinge.getAnchor());

        DistanceJoint rope = (DistanceJoint) Scene3DFile.findJoint(scene, "Lamp rope");
        assertEquals(0f, rope.getMinLength(), 1e-6f);
        assertEquals(1.8f, rope.getMaxLength(), 1e-5f);

        BallJoint shoulder = (BallJoint) Scene3DFile.findJoint(scene, "Shoulder");
        assertTrue(shoulder.hasSwingLimit());
        assertEquals((float) Math.toRadians(30), shoulder.getSwingLimit(), 1e-6f);
        assertTrue(shoulder.hasTwistLimit());
        assertEquals((float) Math.toRadians(-20), shoulder.getLowerTwistLimit(), 1e-6f);
        assertEquals((float) Math.toRadians(50), shoulder.getUpperTwistLimit(), 1e-6f);
        assertEquals(new Vec3(1, 0, 0), shoulder.getAxis());

        SliderJoint rail = (SliderJoint) Scene3DFile.findJoint(scene, "Rail");
        assertTrue(rail.hasMotor());
        assertEquals(1f, rail.getMotorSpeed(), 1e-6f);
        assertEquals(50f, rail.getMaxMotorForce(), 1e-6f);
        assertEquals(new Vec3(1, 0, 0), rail.getAxis());
    }

    @Test
    void theLoadedSceneRuns() {
        Scene3D scene = Scene3DFile.parse(SCENE, temp, true);
        RigidBody crate = Scene3DFile.findBody(scene, "Crate");
        for (int i = 0; i < 240; i++) scene.getPhysics().step(1f / 60f);
        assertEquals(0.5f, crate.getPosition().y(), 0.02f, "resting on the floor");
        assertEquals(0.5f, Scene3DFile.find(scene, "Crate").getPosition().y(), 0.02f, "and its node with it");
        // The lamp hangs from its rope, which stops it 1.8 m below the hook.
        assertEquals(5f - 1.8f - 0.2f, Scene3DFile.findBody(scene, "Lamp").getPosition().y(), 0.03f);
        // The door swings on its hinge, about its edge.
        assertEquals(1f, Scene3DFile.findBody(scene, "Door").getPosition().y(), 0.02f);
    }

    @Test
    void anEditorGetsNoWorldButCanBuildOne() {
        Scene3D scene = Scene3DFile.parse(SCENE, temp, false);
        assertNull(scene.getPhysics());
        assertNull(Scene3DFile.findBody(scene, "Crate"));
        PhysicsWorld one = Scene3DFile.buildPhysics(scene), two = Scene3DFile.buildPhysics(scene);
        assertNotSame(one, two);
        assertEquals(8, one.bodies().size());
        assertEquals(4, two.joints().size());
        // A scene with no bodies runs without a world.
        Scene3D plain = Scene3DFile.parse("{\"nodes\": [{\"name\": \"Box\", \"shape\": \"CUBE\"}]}", temp, true);
        assertNull(plain.getPhysics());
    }

    @Test
    void hullsAndMeshesComeFromWhatTheNodeDraws() throws IOException {
        Files.createDirectories(temp.resolve("models"));
        Files.writeString(temp.resolve("models/wedge.obj"), """
                v 0 0 0
                v 2 0 0
                v 0 1 0
                v 0 0 1
                f 1 2 3
                f 1 3 4
                f 1 4 2
                f 2 4 3
                """);
        String json = """
                { "nodes": [
                  { "name": "Rock", "shape": "CUBE", "body": "DYNAMIC", "collider": "HULL" },
                  { "name": "Block", "shape": "CUBE", "body": "STATIC", "collider": "MESH" },
                  { "name": "Moving", "shape": "CUBE", "body": "DYNAMIC", "collider": "MESH" },
                  { "name": "Statue", "shape": "MODEL", "model": "models/wedge.obj", "body": "STATIC",
                    "children": [ { "name": "Child", "shape": "CUBE", "position": [5, 0, 0] } ] },
                  { "name": "Thrown", "shape": "MODEL", "model": "models/wedge.obj", "body": "DYNAMIC" },
                  { "name": "Group", "body": "STATIC", "colliderSize": [3, 3, 3] }
                ] }
                """;
        Scene3D scene = Scene3DFile.parse(json, temp, true);
        assertEquals(8, ((ConvexHullShape) only(Scene3DFile.findBody(scene, "Rock")).shape()).vertices().size());
        assertEquals(12, ((MeshShape) only(Scene3DFile.findBody(scene, "Block")).shape()).triangleCount());
        // A mesh can't move, so a dynamic body asking for one gets a hull.
        assertInstanceOf(ConvexHullShape.class, only(Scene3DFile.findBody(scene, "Moving")).shape());
        // A model collides as its own triangles when it stays put and a hull when it moves; the node's children are
        // nodes of their own and not part of it.
        MeshShape statue = (MeshShape) only(Scene3DFile.findBody(scene, "Statue")).shape();
        assertEquals(4, statue.triangleCount());
        assertTrue(statue.bounds().max().x() <= 2.0001f, "the child cube at x = 5 is left out");
        assertEquals(4, ((ConvexHullShape) only(Scene3DFile.findBody(scene, "Thrown")).shape()).vertices().size());
        // A group draws nothing, so Auto is a box of the collider size.
        assertEquals(new Vec3(1.5f, 1.5f, 1.5f), ((BoxShape) only(Scene3DFile.findBody(scene, "Group")).shape()).halfExtents());
    }

    @Test
    void propertiesOnlyAppearWhereTheyMeanSomething() {
        Node3D group = new Node3D("Group"), crate = new Node3D("Crate"), player = new Node3D("Player");
        node("body").set(crate, BodyKind.DYNAMIC);
        node("body").set(player, BodyKind.CHARACTER);
        List<SceneProperty<Node3D>> props = SceneKinds.node();
        assertTrue(has(props, "body", group));
        assertFalse(has(props, "collider", group));
        assertFalse(has(props, "layer", group));
        assertTrue(has(props, "mass", crate));
        assertTrue(has(props, "collider", crate));
        assertFalse(has(props, "colliderSize", crate), "only for a box");
        node("collider").set(crate, ColliderKind.BOX);
        assertTrue(has(props, "colliderSize", crate));
        assertFalse(has(props, "colliderRadius", crate));
        assertFalse(has(props, "characterRadius", crate));
        assertTrue(has(props, "characterRadius", player));
        assertTrue(has(props, "gravityScale", player));
        assertFalse(has(props, "collider", player));
        assertFalse(has(props, "mass", player), "a character has its own");

        SceneJoint hinge = SceneKinds.newJoint(JointKind.HINGE), rod = SceneKinds.newJoint(JointKind.DISTANCE), weld = SceneKinds.newJoint(JointKind.FIXED);
        assertEquals("Hinge", hinge.getName());
        assertTrue(has(SceneKinds.joint(JointKind.HINGE), "axis", hinge));
        assertFalse(has(SceneKinds.joint(JointKind.HINGE), "lower", hinge));
        SceneKinds.joint(JointKind.HINGE).stream().filter(p -> p.getName().equals("limited")).findFirst().orElseThrow().set(hinge, true);
        assertTrue(has(SceneKinds.joint(JointKind.HINGE), "lower", hinge));
        assertFalse(has(SceneKinds.joint(JointKind.DISTANCE), "axis", rod));
        assertTrue(has(SceneKinds.joint(JointKind.DISTANCE), "anchorB", rod));
        assertFalse(has(SceneKinds.joint(JointKind.DISTANCE), "springDamping", rod), "only for a spring");
        assertFalse(has(SceneKinds.joint(JointKind.FIXED), "anchor", weld));
    }

    @Test
    void badJointsAreReportedByWhatIsWrong() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Scene3DFile.parse("{\"nodes\": [], \"joints\": [{\"type\": \"ROPE\"}]}", temp, false));
        assertTrue(e.getMessage().contains("\"joints\"[0]"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> Scene3DFile.parse("{\"nodes\": [], \"joints\": [{\"type\": \"HINGE\", \"axis\": 3}]}", temp, false));
        assertTrue(e.getMessage().contains("joint 0") && e.getMessage().contains("axis"), e.getMessage());
    }
}
