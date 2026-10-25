package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.physics.CapsuleShape;
import me.piitex.engine.render3d.physics.ConvexHullShape;
import me.piitex.engine.render3d.physics.MeshShape;
import me.piitex.engine.render3d.physics.PhysicsWorld;
import me.piitex.engine.render3d.physics.RigidBody;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class SceneWorldBuildingTest {
    @TempDir
    Path temp;

    private static Scene3D parse(String json, Path base, boolean animate) {
        return Scene3DFile.parse(json, base, animate);
    }

    /** The volume the mesh's triangles enclose, which is positive only when they all face outward. */
    private static double volume(Mesh mesh) {
        float[] v = mesh.vertices();
        int stride = Mesh.FLOATS_PER_VERTEX;
        int[] ix = mesh.indices();
        double total = 0;
        for (int t = 0; t < ix.length; t += 3) {
            double[] a = at(v, ix[t] * stride), b = at(v, ix[t + 1] * stride), c = at(v, ix[t + 2] * stride);
            total += (a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1] - b[1] * c[0])) / 6.0;
        }
        return total;
    }

    private static double[] at(float[] v, int o) {
        return new double[]{v[o], v[o + 1], v[o + 2]};
    }

    @Test
    void theNewPrimitivesEncloseTheVolumeTheirSizeSaysWithEveryTriangleFacingOut() {
        double cylinder = volume(Mesh.cylinder(0.5f, 2f, 64));
        assertEquals(Math.PI * 0.25 * 2, cylinder, 0.02 * cylinder);
        double cone = volume(Mesh.cone(1f, 3f, 64));
        assertEquals(Math.PI * 3 / 3, cone, 0.02 * cone);
        double capsule = volume(Mesh.capsule(0.5f, 2f, 64, 16));
        assertEquals(Math.PI * 0.25 * 1 + 4.0 / 3 * Math.PI * 0.125, capsule, 0.02 * capsule);
        // A capsule shorter than its two ends is a sphere.
        assertEquals(4.0 / 3 * Math.PI * 0.125, volume(Mesh.capsule(0.5f, 0.5f, 64, 16)), 0.03);
    }

    @Test
    void theNewPrimitivesAreSavedAndLoadedWithTheirSizes() {
        String json = """
                { "format": 1, "nodes": [
                  { "name": "Pillar", "shape": "CYLINDER", "radius": 0.3, "height": 4, "segments": 12 },
                  { "name": "Pill", "shape": "CAPSULE", "radius": 0.4, "height": 2 },
                  { "name": "Spire", "shape": "CONE", "radius": 1, "height": 3, "position": [4, 0, 0] }
                ] }""";
        Scene3D scene = parse(json, temp, false);
        Node3D pillar = Scene3DFile.find(scene, "Pillar");
        assertEquals(Shape.CYLINDER, Scene3DFile.getShape(pillar));
        assertNotNull(pillar.getMesh());
        assertNotNull(pillar.getMaterial());
        String saved = Scene3DFile.toJson(scene);
        assertEquals(saved, Scene3DFile.toJson(parse(saved, temp, false)));
        assertTrue(saved.contains("\"height\": 4"), saved);
        assertTrue(saved.contains("\"segments\": 12"), saved);
        // Only the settings a shape has are saved.
        assertFalse(saved.contains("\"rings\""), saved);
    }

    @Test
    void roundAndPointedShapesCollideAsWhatTheyLookLike() {
        String json = """
                { "format": 1, "nodes": [
                  { "name": "Pill", "shape": "CAPSULE", "radius": 0.4, "height": 2, "position": [0, 2, 0], "body": "DYNAMIC" },
                  { "name": "Can", "shape": "CYLINDER", "position": [2, 2, 0], "body": "DYNAMIC" },
                  { "name": "Cone", "shape": "CONE", "position": [4, 2, 0], "body": "DYNAMIC" }
                ] }""";
        Scene3D scene = parse(json, temp, false);
        PhysicsWorld world = Scene3DFile.buildPhysics(scene);
        scene.setPhysics(world);
        assertEquals(3, world.bodies().size());
        RigidBody pill = Scene3DFile.findBody(scene, "Pill");
        assertInstanceOf(CapsuleShape.class, pill.getShapes().getFirst().shape());
        assertInstanceOf(ConvexHullShape.class, Scene3DFile.findBody(scene, "Can").getShapes().getFirst().shape());
        assertInstanceOf(ConvexHullShape.class, Scene3DFile.findBody(scene, "Cone").getShapes().getFirst().shape());
    }

    private Path heightmap() throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int grey = x * 255 / 15;
                image.setRGB(x, y, grey << 16 | grey << 8 | grey);
            }
        }
        Path file = temp.resolve("assets/hill.png");
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private static float[] range(Mesh mesh, int axis) {
        float[] v = mesh.vertices();
        float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            float x = v[i * Mesh.FLOATS_PER_VERTEX + axis];
            low = Math.min(low, x);
            high = Math.max(high, x);
        }
        return new float[]{low, high};
    }

    @Test
    void terrainIsRaisedFromAHeightMapAndCollidesAsItsTriangles() throws IOException {
        heightmap();
        String json = """
                { "format": 1, "nodes": [
                  { "name": "Hills", "shape": "TERRAIN", "heightmap": "assets/hill.png", "width": 40, "depth": 20, "peak": 8,
                    "resolution": 16, "body": "STATIC" }
                ] }""";
        Scene3D scene = parse(json, temp, false);
        Node3D hills = Scene3DFile.find(scene, "Hills");
        assertNull(Scene3DFile.terrainProblem(hills));
        float[] x = range(hills.getMesh(), 0), y = range(hills.getMesh(), 1), z = range(hills.getMesh(), 2);
        assertEquals(-20f, x[0], 1e-4f);
        assertEquals(20f, x[1], 1e-4f);
        assertEquals(-10f, z[0], 1e-4f);
        assertEquals(0f, y[0], 1e-3f, "the black edge is at the bottom");
        assertEquals(8f, y[1], 1e-2f, "the white edge is a peak high");
        // The ground rises from left to right.
        float[] v = hills.getMesh().vertices();
        assertTrue(v[(15) * Mesh.FLOATS_PER_VERTEX + 1] > v[1] + 7f);
        PhysicsWorld world = Scene3DFile.buildPhysics(scene);
        assertInstanceOf(MeshShape.class, world.bodies().getFirst().getShapes().getFirst().shape());
        String saved = Scene3DFile.toJson(scene);
        assertEquals(saved, Scene3DFile.toJson(parse(saved, temp, false)));
    }

    @Test
    void terrainWithNoHeightMapIsFlatAndOneThatCantBeReadSaysWhy() {
        Scene3D scene = parse("""
                { "format": 1, "nodes": [
                  { "name": "Flat", "shape": "TERRAIN" },
                  { "name": "Lost", "shape": "TERRAIN", "heightmap": "assets/none.png" }
                ] }""", temp, false);
        Node3D flat = Scene3DFile.find(scene, "Flat");
        assertEquals(0f, range(flat.getMesh(), 1)[1], 0f);
        assertNull(Scene3DFile.terrainProblem(flat));
        Node3D lost = Scene3DFile.find(scene, "Lost");
        assertNotNull(Scene3DFile.terrainProblem(lost));
        assertTrue(Scene3DFile.toJson(scene).contains("assets/none.png"), "kept so saving doesn't lose it");
    }

    @Test
    void aPrefabIsAnotherSceneFileUsedWhereverItIsWantedAndIsNotCopiedIntoTheFile() throws IOException {
        Files.createDirectories(temp.resolve("prefabs"));
        Files.writeString(temp.resolve("prefabs/crate.json"), """
                { "format": 1, "nodes": [
                  { "name": "Crate", "shape": "CUBE", "body": "DYNAMIC", "children": [
                    { "name": "Lid", "shape": "CUBE", "scale": [1, 0.1, 1], "position": [0, 0.5, 0] } ] }
                ] }""");
        Scene3D scene = parse("""
                { "format": 1, "nodes": [
                  { "name": "Left", "shape": "PREFAB", "prefab": "prefabs/crate.json", "position": [-3, 0, 0] },
                  { "name": "Right", "shape": "PREFAB", "prefab": "prefabs/crate.json", "position": [3, 0, 0] }
                ] }""", temp, false);
        Node3D left = Scene3DFile.find(scene, "Left");
        assertNull(Scene3DFile.prefabProblem(left));
        assertEquals(1, left.getChildren().size());
        Node3D lid = Scene3DFile.find(scene, "Lid");
        assertNotNull(lid, "a game can find what is inside a prefab");
        assertTrue(Scene3DFile.isInternal(lid), "an editor leaves it to the prefab's own file");
        assertSame(left, Scene3DFile.owner(lid));
        String saved = Scene3DFile.toJson(scene);
        assertFalse(saved.contains("Lid"), saved);
        assertEquals(saved, Scene3DFile.toJson(parse(saved, temp, false)));
        // Each use has its own body, where the prefab was put.
        PhysicsWorld world = Scene3DFile.buildPhysics(scene);
        assertEquals(2, world.bodies().size());
        float x = world.bodies().get(0).getPosition().x();
        float other = world.bodies().get(1).getPosition().x();
        assertEquals(6f, Math.abs(x - other), 1e-3f);
    }

    @Test
    void aPrefabThatHoldsItselfOrIsMissingIsLeftEmptyWithAReason() throws IOException {
        Files.writeString(temp.resolve("loop.json"), """
                { "format": 1, "nodes": [ { "name": "Again", "shape": "PREFAB", "prefab": "loop.json" } ] }""");
        Scene3D scene = parse("""
                { "format": 1, "nodes": [
                  { "name": "Loop", "shape": "PREFAB", "prefab": "loop.json" },
                  { "name": "Gone", "shape": "PREFAB", "prefab": "nowhere.json" },
                  { "name": "Blank", "shape": "PREFAB" }
                ] }""", temp, false);
        Node3D again = Scene3DFile.find(scene, "Again");
        assertNotNull(again, "the file is used once, and what it holds of itself is left out");
        assertNotNull(Scene3DFile.prefabProblem(again));
        assertNotNull(Scene3DFile.prefabProblem(Scene3DFile.find(scene, "Gone")));
        assertNotNull(Scene3DFile.prefabProblem(Scene3DFile.find(scene, "Blank")));
        assertTrue(Scene3DFile.toJson(scene).contains("nowhere.json"));
    }

    @Test
    void copiesAreDrawnInOneGoAlongTheSpacingAndAreOnlyDrawn() {
        Scene3D scene = parse("""
                { "format": 1, "nodes": [
                  { "name": "Fence", "shape": "CYLINDER", "radius": 0.1, "height": 1.5, "copies": 5, "spacing": [0, 0, 1.5], "body": "STATIC" },
                  { "name": "Single", "shape": "CUBE" }
                ] }""", temp, false);
        Node3D fence = Scene3DFile.find(scene, "Fence");
        assertEquals(5, fence.getInstances().size());
        assertEquals(6f, fence.getInstances().get(4).transformPoint(new me.piitex.engine.render3d.math.Vec3(0, 0, 0)).z(), 1e-4f);
        assertNull(Scene3DFile.find(scene, "Single").getInstances());
        assertEquals(1, Scene3DFile.buildPhysics(scene).bodies().size(), "the first one collides");
        String saved = Scene3DFile.toJson(scene);
        assertTrue(saved.contains("\"copies\": 5"), saved);
        assertEquals(saved, Scene3DFile.toJson(parse(saved, temp, false)));
        // Setting the count back to one draws it as usual.
        SceneKinds.node().stream().filter(p -> p.getName().equals("copies")).findFirst().orElseThrow().set(fence, 1);
        assertNull(fence.getInstances());
    }

    @Test
    void lightsBlinkWhenTheGameRunsAndStayPutInTheEditor() {
        String json = """
                { "format": 1,
                  "lights": [
                    { "type": "POINT", "name": "Lamp", "intensity": 4, "pattern": "BLINK", "period": 2, "minimum": 0.25, "start": 0.5, "glow": "Bulb" },
                    { "type": "POINT", "name": "Steady", "intensity": 2 }
                  ],
                  "nodes": [ { "name": "Bulb", "shape": "SPHERE", "material": { "emissiveColor": [1, 0, 0, 1], "emissiveStrength": 3 } } ] }""";
        Scene3D editing = parse(json, temp, false);
        assertFalse(editing.needsFrames(), "the editor shows the lights as they are");
        Scene3D running = parse(json, temp, true);
        assertTrue(running.needsFrames(), "a blinking light asks for frames");
        Scene3D steady = parse("""
                { "format": 1, "lights": [ { "type": "POINT", "name": "Steady", "intensity": 2 } ] }""", temp, true);
        assertFalse(steady.needsFrames());
        Light lamp = Scene3DFile.findLight(editing, "Lamp");
        assertNotNull(lamp);
        String saved = Scene3DFile.toJson(editing);
        assertTrue(saved.contains("\"pattern\": \"BLINK\""), saved);
        assertTrue(saved.contains("\"glow\": \"Bulb\""), saved);
        assertEquals(saved, Scene3DFile.toJson(parse(saved, temp, false)));
        // The settings of a light that doesn't blink don't clutter its entry.
        int steadyAt = saved.indexOf("\"Steady\"");
        assertFalse(saved.substring(steadyAt).contains("period"), saved);
    }
}
