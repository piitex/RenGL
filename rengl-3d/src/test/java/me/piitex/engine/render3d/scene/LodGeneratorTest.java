package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Bounds;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Levels of detail made with meshoptimizer, and the hand-made ones model files carry.
 */
class LodGeneratorTest {

    @Test
    void levelsHaveFewerTrianglesAndMoreErrorEachStep() {
        Mesh sphere = Mesh.sphere(1f, 64, 32).generateLods();
        LodChain chain = sphere.getLods();
        assertNotNull(chain);
        assertTrue(chain.size() >= 4, "levels: " + chain.size());
        for (int i = 1; i < chain.size(); i++) {
            LodChain.Level before = chain.level(i - 1), level = chain.level(i);
            assertFalse(level.handMade());
            assertTrue(level.mesh().indexCount() < before.mesh().indexCount() * 0.9, "level " + i + " has " + level.mesh().indexCount() / 3 + " triangles");
            assertTrue(level.mesh().indexCount() / 3 >= 64, "no level under the smallest count");
            assertTrue(level.error() >= before.error() && level.error() > 0f, "level " + i + " error " + level.error());
            assertInside(level.mesh().bounds(), sphere.bounds(), 1e-4f);
            // The error meshoptimizer gives is near what is measured.
            float measured = LodGenerator.measureError(sphere, level.mesh());
            assertTrue(measured <= level.error() * 4f + 1e-4f && measured >= level.error() / 8f,
                    "level " + i + ": error " + level.error() + ", measured " + measured);
        }
        // Each level keeps only the vertices it uses.
        Mesh last = chain.coarsest();
        assertTrue(last.vertexCount() < sphere.vertexCount() / 4, last.vertexCount() + " vertices");
    }

    @Test
    void aSmallMeshGetsNone() {
        assertNull(Mesh.cube(1f).generateLods().getLods());
        assertThrows(IllegalStateException.class, () -> Mesh.lines(new float[]{0, 0, 0, 1, 0, 0}, new int[]{0, 1}).generateLods());
    }

    @Test
    void settingsShapeTheChain() {
        Mesh two = Mesh.sphere(1f, 64, 32).generateLods(new LodSettings().ratios(0.4f, 0.1f));
        assertEquals(3, two.getLods().size());
        Mesh few = Mesh.sphere(1f, 64, 32).generateLods(new LodSettings().minTriangles(1000));
        assertTrue(few.getLods().size() <= 3, "levels: " + few.getLods().size());
        assertThrows(IllegalArgumentException.class, () -> new LodSettings().ratios(0.5f, 0.6f));
        assertThrows(IllegalArgumentException.class, () -> new LodSettings().ratios(1f));
        assertThrows(IllegalArgumentException.class, () -> new LodSettings().maxError(0f));
    }

    @Test
    void handMadeLevelsKeepTheirPlace() {
        Mesh hand = Mesh.sphere(1f, 12, 6);
        Mesh sphere = Mesh.sphere(1f, 64, 32).addLod(hand, 0.05f).generateLods();
        LodChain chain = sphere.getLods();
        int found = -1;
        for (int i = 1; i < chain.size(); i++) if (chain.level(i).mesh() == hand) found = i;
        assertTrue(found >= 1, "the hand-made level is still there");
        assertTrue(chain.level(found).handMade());
        for (int i = 1; i < chain.size(); i++) assertTrue(chain.level(i).error() >= chain.level(i - 1).error());
    }

    @Test
    void everyPartOfTheMeshComesAlong() {
        int columns = 40, rows = 40;
        float[] heights = new float[columns * rows];
        for (int i = 0; i < heights.length; i++) heights[i] = (float) (Math.sin(i * 0.37) * 0.5 + 0.5);
        Mesh ground = Mesh.terrain(10, 10, 1, heights, columns, rows);
        int n = ground.vertexCount();
        int[] joints = new int[n * 4];
        float[] weights = new float[n * 4], uv2 = new float[n * 2], lift = new float[n * 3], bend = new float[n * 3];
        for (int i = 0; i < n; i++) {
            joints[i * 4] = i % 3;
            joints[i * 4 + 1] = (i + 1) % 3;
            weights[i * 4] = 0.75f;
            weights[i * 4 + 1] = 0.25f;
            uv2[i * 2] = ground.vertices()[i * Mesh.FLOATS_PER_VERTEX];
            uv2[i * 2 + 1] = ground.vertices()[i * Mesh.FLOATS_PER_VERTEX + 2];
            lift[i * 3 + 1] = ground.vertices()[i * Mesh.FLOATS_PER_VERTEX];
            bend[i * 3] = 0.1f;
        }
        ground.setSkinning(joints, weights).setUvs2(uv2);
        ground.setMorphTargets(List.of(new Mesh.MorphTarget("lift", lift, null), new Mesh.MorphTarget("bend", bend, bend)), new float[]{0.5f, 0f});
        ground.generateLods();
        LodChain chain = ground.getLods();
        assertNotNull(chain);
        for (int i = 1; i < chain.size(); i++) {
            Mesh level = chain.level(i).mesh();
            assertTrue(level.isSkinned() && level.hasUvs2() && level.morphTargets().size() == 2, "level " + i);
            assertArrayEquals(new float[]{0.5f, 0f}, level.morphDefaults());
            assertNotNull(level.morphTargets().get(1).normals());
            assertNull(level.morphTargets().get(0).normals());
            // Each vertex still carries what it had: its second coordinates are its x and z, its lift its x.
            float[] v = level.vertices();
            for (int k = 0; k < level.vertexCount(); k++) {
                float x = v[k * Mesh.FLOATS_PER_VERTEX], z = v[k * Mesh.FLOATS_PER_VERTEX + 2];
                assertEquals(x, level.uvs2()[k * 2], 1e-6f);
                assertEquals(z, level.uvs2()[k * 2 + 1], 1e-6f);
                assertEquals(x, level.morphTargets().get(0).positions()[k * 3 + 1], 1e-6f);
                assertEquals(1f, level.skinWeights()[k * 4] + level.skinWeights()[k * 4 + 1], 1e-6f);
            }
        }
        assertEquals(chain.size() - 1, SceneRenderer.lastUsable(chain, true, true), "a skinned, morphed node may use every level");
    }

    @Test
    void manySeparatePiecesNeedTheRoughWay() {
        // Two hundred separate boxes: nothing can be joined along a surface, so only the rough way gets anywhere.
        List<float[]> parts = new ArrayList<>();
        float[] p = new float[0];
        int[] idx = new int[0];
        Mesh box = Mesh.cube(0.2f);
        for (int b = 0; b < 200; b++) {
            int base = p.length / 3;
            float ox = (b % 20) * 0.5f, oz = (b / 20) * 0.5f;
            float[] grow = java.util.Arrays.copyOf(p, p.length + box.vertexCount() * 3);
            for (int i = 0; i < box.vertexCount(); i++) {
                grow[p.length + i * 3] = box.vertices()[i * 8] + ox;
                grow[p.length + i * 3 + 1] = box.vertices()[i * 8 + 1];
                grow[p.length + i * 3 + 2] = box.vertices()[i * 8 + 2] + oz;
            }
            int[] more = java.util.Arrays.copyOf(idx, idx.length + box.indexCount());
            for (int i = 0; i < box.indexCount(); i++) more[idx.length + i] = box.indices()[i] + base;
            p = grow;
            idx = more;
        }
        Mesh boxes = new Mesh(p, null, null, idx);
        assertNull(new Mesh(p, null, null, idx).generateLods(new LodSettings().sloppy(false).maxError(0.5f)).getLods(),
                "without the rough way no level is worth making");
        boxes.generateLods(new LodSettings().maxError(0.5f));
        assertNotNull(boxes.getLods(), "with it there are levels");
        assertTrue(boxes.getLods().level(1).mesh().indexCount() < boxes.indexCount() * 0.85);
    }

    @Test
    void optimizingKeepsTheMesh() {
        Mesh sphere = Mesh.sphere(1f, 48, 24);
        Mesh fast = LodGenerator.optimize(sphere);
        assertEquals(sphere.indexCount(), fast.indexCount());
        assertEquals(0f, LodGenerator.measureError(sphere, fast), 1e-5f);
        assertInside(fast.bounds(), sphere.bounds(), 1e-6f);
    }

    @Test
    void measuringTheErrorOfAHandMadeLevel() {
        Mesh sphere = Mesh.sphere(1f, 64, 32);
        assertEquals(0f, LodGenerator.measureError(sphere, sphere), 1e-6f);
        assertEquals(0.1f, LodGenerator.measureError(sphere, Mesh.sphere(1.1f, 64, 32)), 0.002f);
        Mesh m = Mesh.sphere(1f, 64, 32).addLod(Mesh.sphere(1f, 8, 4));
        // 8 sides round and 4 bands from pole to pole: the middle of a face is cos(22.5 degrees) squared from the centre.
        float faceMiddle = (float) (1 - Math.cos(Math.PI / 8) * Math.cos(Math.PI / 8));
        assertEquals(faceMiddle, m.getLods().level(1).error(), faceMiddle * 0.15f);
    }

    // ---- model files --------------------------------------------------------------------------------------------

    @Test
    void nodesNamedLikeUnitysLevelsBecomeAChain() throws IOException {
        Gltf file = new Gltf();
        int full = file.mesh(Mesh.sphere(1f, 32, 16)), low = file.mesh(Mesh.sphere(1f, 8, 4));
        file.node("rock_LOD0", full, null, true);
        file.node("rock_LOD1", low, null, true);
        Model model = Model.load("rock.gltf", file.bytes());
        Node3D rock = model.root().find("rock");
        assertNotNull(rock, "the first level is renamed after the group");
        assertNull(model.root().find("rock_LOD1"), "the other levels leave the tree");
        LodChain chain = rock.getMesh().getLods();
        assertNotNull(chain);
        assertEquals(2, chain.size());
        assertTrue(chain.level(1).handMade());
        assertEquals((float) (1 - Math.cos(Math.PI / 8) * Math.cos(Math.PI / 8)), chain.level(1).error(), 0.025f);
        assertEquals(1, model.meshes().size(), "the level is not a mesh of its own in the model");
    }

    @Test
    void gltfMsftLodWithScreenCoverage() throws IOException {
        Gltf file = new Gltf();
        int full = file.mesh(Mesh.sphere(1f, 32, 16)), low = file.mesh(Mesh.sphere(1f, 8, 4));
        file.extensionsUsed = "\"MSFT_lod\"";
        file.node("tree", full, "\"extensions\":{\"MSFT_lod\":{\"ids\":[1]}},\"extras\":{\"MSFT_screencoverage\":[0.25,0.01]}", true);
        file.node("tree_low", low, null, false);
        Model model = Model.load("tree.gltf", file.bytes());
        Node3D tree = model.root().find("tree");
        LodChain chain = tree.getMesh().getLods();
        assertNotNull(chain, "the MSFT_lod level is read");
        assertEquals(2, chain.size());
        // A quarter of the screen's area is half its height: the error that is a pixel there on a 1080 high screen.
        assertEquals(2f * tree.getMesh().bounds().radius() / (0.5f * 1080f), chain.level(1).error(), 1e-5f);
        assertEquals(0.1f, tree.getCullScreenSize(), 1e-5f, "below the last coverage nothing is drawn");
        assertNull(model.root().find("tree_low"));
    }

    @Test
    void msftLodFromAFileBesideItsBuffer(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws IOException {
        Gltf file = new Gltf();
        int full = file.mesh(Mesh.sphere(1f, 32, 16)), low = file.mesh(Mesh.sphere(1f, 8, 4));
        file.extensionsUsed = "\"MSFT_lod\"";
        file.node("tree", full, "\"extensions\":{\"MSFT_lod\":{\"ids\":[1]}}", true);
        file.nodeWithoutName(low);
        java.nio.file.Files.write(dir.resolve("tree.bin"), file.data.toByteArray());
        java.nio.file.Files.write(dir.resolve("tree.gltf"), file.bytes("tree.bin"));
        Model model = Model.load(dir.resolve("tree.gltf"));
        LodChain chain = model.root().find("tree").getMesh().getLods();
        assertNotNull(chain, "the level is read through the buffer file beside it");
        assertEquals(2, chain.size());
        assertTrue(chain.level(1).error() > 0.1f, "with no coverage the error is measured: " + chain.level(1).error());
        assertEquals(1, model.meshes().size());
        int[] drawing = {0};
        model.root().traverse(n -> {
            if (n.getMesh() != null) drawing[0]++;
        });
        assertEquals(1, drawing[0], "only the full mesh is drawn");
    }

    @Test
    void aModelLoadedWithSettingsGetsLevels() throws IOException {
        Gltf file = new Gltf();
        file.node("ball", file.mesh(Mesh.sphere(1f, 64, 32)), null, true);
        Model plain = Model.load("ball.gltf", file.bytes());
        assertNull(plain.root().find("ball").getMesh().getLods());
        Model made = Model.load("ball.gltf", file.bytes(), new LodSettings());
        assertTrue(made.root().find("ball").getMesh().getLods().size() >= 3);
        assertTrue(plain.generateLods(new LodSettings()).root().find("ball").getMesh().getLods().size() >= 3);
    }

    private static void assertInside(Bounds inner, Bounds outer, float slack) {
        assertTrue(inner.min().x() >= outer.min().x() - slack && inner.min().y() >= outer.min().y() - slack && inner.min().z() >= outer.min().z() - slack
                && inner.max().x() <= outer.max().x() + slack && inner.max().y() <= outer.max().y() + slack && inner.max().z() <= outer.max().z() + slack,
                inner + " inside " + outer);
    }

    /**
     * A glTF file written in memory: meshes of positions, normals and indices, and nodes at the top of the scene or outside it.
     */
    private static final class Gltf {
        final StringBuilder meshes = new StringBuilder(), accessors = new StringBuilder(), views = new StringBuilder(), nodes = new StringBuilder();
        final List<Integer> sceneNodes = new ArrayList<>();
        final java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        int meshCount, accessorCount, nodeCount;
        String extensionsUsed;

        int mesh(Mesh mesh) {
            int n = mesh.vertexCount();
            ByteBuffer pos = ByteBuffer.allocate(n * 12).order(ByteOrder.LITTLE_ENDIAN), nrm = ByteBuffer.allocate(n * 12).order(ByteOrder.LITTLE_ENDIAN);
            float[] v = mesh.vertices();
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < 3; k++) pos.putFloat(v[i * 8 + k]);
                for (int k = 0; k < 3; k++) nrm.putFloat(v[i * 8 + 3 + k]);
            }
            ByteBuffer ind = ByteBuffer.allocate(mesh.indexCount() * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i : mesh.indices()) ind.putInt(i);
            Bounds b = mesh.bounds();
            int p = accessor(pos.array(), n, "VEC3", 5126, ",\"min\":[" + b.min().x() + "," + b.min().y() + "," + b.min().z() + "],\"max\":[" + b.max().x() + "," + b.max().y() + "," + b.max().z() + "]", 34962);
            int q = accessor(nrm.array(), n, "VEC3", 5126, "", 34962);
            int r = accessor(ind.array(), mesh.indexCount(), "SCALAR", 5125, "", 34963);
            comma(meshes).append("{\"primitives\":[{\"attributes\":{\"POSITION\":").append(p).append(",\"NORMAL\":").append(q).append("},\"indices\":").append(r).append("}]}");
            return meshCount++;
        }

        private int accessor(byte[] bytes, int count, String type, int component, String extra, int target) {
            int offset = data.size();
            data.writeBytes(bytes);
            while (data.size() % 4 != 0) data.write(0);
            comma(views).append("{\"buffer\":0,\"byteOffset\":").append(offset).append(",\"byteLength\":").append(bytes.length).append(",\"target\":").append(target).append("}");
            comma(accessors).append("{\"bufferView\":").append(accessorCount).append(",\"componentType\":").append(component).append(",\"count\":").append(count)
                    .append(",\"type\":\"").append(type).append("\"").append(extra).append("}");
            return accessorCount++;
        }

        void nodeWithoutName(int mesh) {
            comma(nodes).append("{\"mesh\":").append(mesh).append("}");
            nodeCount++;
        }

        void node(String name, int mesh, String extra, boolean inScene) {
            comma(nodes).append("{\"name\":\"").append(name).append("\",\"mesh\":").append(mesh).append(extra == null ? "" : "," + extra).append("}");
            if (inScene) sceneNodes.add(nodeCount);
            nodeCount++;
        }

        private static StringBuilder comma(StringBuilder b) {
            if (!b.isEmpty()) b.append(',');
            return b;
        }

        byte[] bytes() {
            return bytes("data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(data.toByteArray()));
        }

        byte[] bytes(String uri) {
            String json = "{\"asset\":{\"version\":\"2.0\"}," + (extensionsUsed == null ? "" : "\"extensionsUsed\":[" + extensionsUsed + "],")
                    + "\"scene\":0,\"scenes\":[{\"nodes\":" + sceneNodes + "}],\"nodes\":[" + nodes + "],\"meshes\":[" + meshes + "],"
                    + "\"accessors\":[" + accessors + "],\"bufferViews\":[" + views + "],\"buffers\":[{\"byteLength\":" + data.size() + ",\"uri\":\"" + uri + "\"}]}";
            return json.getBytes(StandardCharsets.UTF_8);
        }
    }
}
