package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Instances;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skin;
import me.piitex.engine.render3d.scene.TextureData;
import me.piitex.engine.render3d.scene.ToneMapping;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the scene renderer draws, on a real GPU on Vulkan and OpenGL: what it leaves out (single instances and skinned
 * meshes out of view), the order it draws see-through things in, and the picture's tone mapping.
 */
class SceneGpuTest {
    private static final int VIEW = 128;
    private static final int PHASE_CHANGE = 4;

    /**
     * A scene, what to change between the third frame and the fourth, and the checks (which see the stats of the third
     * frame and of the sixth, the last, and the picture of the sixth).
     */
    private record Scenario(String name, Supplier<Scene3D> scene, Consumer<Scene3D> change, Check check) {
    }

    private interface Check {
        void run(Scene3D scene, Scene3D.Stats before, Scene3D.Stats after, Pixels pixels, GpuHarness.Recorder r);
    }

    private static final class Pixels {
        private final Scene3D scene;
        private final RenderTarget target;
        private final ByteBuffer data;

        Pixels(Scene3D scene, RenderTarget target) {
            this.scene = scene;
            this.target = target;
            this.data = target.readPixels();
        }

        /** Red, green and blue where a world point lands on screen. */
        int[] rgb(float x, float y, float z) {
            int w = target.width(), h = target.height();
            var camera = scene.camera();
            Vec3 ndc = camera.projection((float) w / h).mul(camera.view()).transformPoint(new Vec3(x, y, z));
            int sx = Math.round((ndc.x() * 0.5f + 0.5f) * w), sy = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * h);
            int i = (Math.clamp(sy, 0, h - 1) * w + Math.clamp(sx, 0, w - 1)) * 4;
            return new int[]{data.get(i) & 255, data.get(i + 1) & 255, data.get(i + 2) & 255};
        }

        int luma(float x, float y, float z) {
            int[] c = rgb(x, y, z);
            return c[0] + c[1] + c[2];
        }

        void free() {
            MemoryUtil.memFree(data);
        }
    }

    private static final class SceneContent implements Viewport3D.Content {
        private final Scene3D scene;
        private final Consumer<Scene3D> change;
        final List<Scene3D.Stats> stats = new ArrayList<>();
        private int frame;

        SceneContent(Scene3D scene, Consumer<Scene3D> change) {
            this.scene = scene;
            this.change = change;
        }

        @Override
        public void init(GpuDevice device) {
            scene.init(device);
        }

        @Override
        public void resize(GpuDevice device, int width, int height) {
            scene.resize(device, width, height);
        }

        @Override
        public void render(GpuDevice device, RenderTarget target, float delta) {
            if (++frame == PHASE_CHANGE && change != null) change.accept(scene);
            scene.render(device, target, delta);
            stats.add(scene.getStats());
        }

        @Override
        public void dispose(GpuDevice device) {
            scene.dispose(device);
        }
    }

    @TestFactory
    List<DynamicTest> vulkan() {
        return tests(RenderAPI.VULKAN);
    }

    @TestFactory
    List<DynamicTest> openGl() {
        return tests(RenderAPI.OPENGL);
    }

    private List<DynamicTest> tests(RenderAPI api) {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            Scene3D scene = scenario.scene().get();
            SceneContent content = new SceneContent(scene, scenario.change());
            GpuHarness.Run run = GpuHarness.run(api, VIEW, (Consumer<Viewport3D>) viewport -> viewport.setContent(content),
                    (device, target, recorder) -> {
                        if (device.api() != api) return;
                        Pixels pixels = new Pixels(scene, target);
                        try {
                            scenario.check().run(scene, content.stats.get(PHASE_CHANGE - 2), content.stats.get(content.stats.size() - 1), pixels, recorder);
                        } finally {
                            pixels.free();
                        }
                    });
            boolean usable = run.available() && run.api() == api;
            tests.add(DynamicTest.dynamicTest(api + ": " + scenario.name() + ": runs", () -> {
                assumeTrue(usable, run.reason() == null ? "the window ran on " + run.api() : run.reason());
            }));
            if (usable) {
                run.results().forEach((name, failure) -> tests.add(DynamicTest.dynamicTest(api + ": " + name,
                        () -> assertNull(failure, name + ": " + failure))));
            }
        }
        return tests;
    }

    private static Scenario[] scenarioArray() {
        return new Scenario[]{
                new Scenario("instances", SceneGpuTest::row, null, SceneGpuTest::rowCheck),
                new Scenario("a caster out of view", SceneGpuTest::pillars, null, SceneGpuTest::pillarsCheck),
                new Scenario("a skinned mesh", SceneGpuTest::skinned, scene -> {
                    scene.root().find("joint").setPosition(500f, 0f, 0f);
                }, SceneGpuTest::skinnedCheck),
                new Scenario("see-through instances far first", () -> glass(true), null, SceneGpuTest::glassCheck),
                new Scenario("see-through instances near first", () -> glass(false), null, SceneGpuTest::glassCheck),
                new Scenario("no tone mapping", () -> toned(ToneMapping.NONE, 1f), null, (scene, b, a, px, r) -> toneCheck("none", px, r, 252, 256)),
                new Scenario("reinhard tone mapping", () -> toned(ToneMapping.REINHARD, 1f), null, (scene, b, a, px, r) -> toneCheck("reinhard", px, r, 195, 235)),
                new Scenario("aces tone mapping", () -> toned(ToneMapping.ACES, 1f), null, (scene, b, a, px, r) -> toneCheck("aces", px, r, 236, 254)),
                new Scenario("exposure", () -> toned(ToneMapping.NONE, 0.25f), null, (scene, b, a, px, r) -> toneCheck("exposure", px, r, 195, 225)),
                new Scenario("plain texture", () -> uvPlane(m -> { }, null), null, (scene, b, a, px, r) -> halves("plain", px, r, true, true)),
                new Scenario("texture offset", () -> uvPlane(m -> m.setUvTransform(0.5f, 0f, 1f, 1f, 0f), null), null,
                        (scene, b, a, px, r) -> halves("offset by half", px, r, false, false)),
                new Scenario("texture scale", () -> uvPlane(m -> m.setUvScale(2f), null), null, (scene, b, a, px, r) -> scaled(px, r)),
                new Scenario("texture rotation", () -> uvPlane(m -> m.setUvTransform(0f, 0f, 1f, 1f, (float) Math.PI / 2), null), null,
                        (scene, b, a, px, r) -> turned(px, r)),
                new Scenario("second texture coordinates", () -> uvPlane(m -> m.setTexCoordSet(Material.Slot.BASE_COLOR, 1), mesh -> {
                    float[] uv2 = new float[8];
                    for (int v = 0; v < 4; v++) uv2[v * 2] = 0.25f; // every vertex on the red texel
                    mesh.setUvs2(uv2);
                }), null, (scene, b, a, px, r) -> allRed(px, r)),
                new Scenario("second coordinates a mesh does not have", () -> uvPlane(m -> m.setTexCoordSet(Material.Slot.BASE_COLOR, 1), null), null,
                        (scene, b, a, px, r) -> halves("second set missing", px, r, true, true)),
                new Scenario("sorted triangles", () -> folded(true), null, (scene, before, after, px, r) -> foldedCheck(true, px, r)),
                new Scenario("unsorted triangles", () -> folded(false), null, (scene, before, after, px, r) -> foldedCheck(false, px, r))};
    }

    private static List<Scenario> scenarios() {
        return List.of(scenarioArray());
    }

    private static Scene3D base() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(Color.BLACK);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        return scene;
    }

    // ---- a row of instances ----------------------------------------------------------------------------------------

    private static Scene3D row() {
        Scene3D scene = base();
        scene.setAmbient(Color.WHITE, 1f);
        Instances instances = new Instances();
        for (int i = 0; i < 41; i++) instances.add(new Vec3(-100f + 5f * i, 0f, 0f));
        Node3D cubes = new Node3D("cubes", Mesh.cube(1f), new Material(Color.WHITE).setUnlit(true));
        cubes.setInstances(instances);
        scene.add(cubes);
        scene.camera().lookAt(new Vec3(0, 0, 10), new Vec3(0, 0, 0)).setFovYDegrees(60);
        return scene;
    }

    private static void rowCheck(Scene3D scene, Scene3D.Stats before, Scene3D.Stats after, Pixels px, GpuHarness.Recorder r) {
        r.check("instances: only those in view are drawn", after.instances() >= 3 && after.instances() <= 7,
                "drew " + after.instances() + " of 41");
        r.guard("instances: the ones in view show", () -> {
            r.check("instances: the middle one shows", px.luma(0f, 0f, 0f) > 300, "luma " + px.luma(0f, 0f, 0f));
            r.check("instances: one near the edge shows", px.luma(5f, 0f, 0f) > 300, "luma " + px.luma(5f, 0f, 0f));
        });
    }

    // ---- shadows from out of view ----------------------------------------------------------------------------------

    private static Scene3D pillars() {
        Scene3D scene = base();
        scene.lights().add(Light.directional(new Vec3(1, -1, 0), Color.WHITE, 1f).setShadows(me.piitex.engine.render3d.scene.ShadowQuality.HIGH));
        scene.setAmbient(Color.WHITE, 0.05f);
        scene.setShadowDistance(60f);
        Node3D floor = new Node3D("floor", Mesh.plane(80f, 80f), new Material(Color.WHITE).setRoughness(0.9f));
        scene.add(floor);
        Instances instances = new Instances();
        instances.add(Mat4.compose(new Vec3(0f, 3f, 5f), Quat.IDENTITY, new Vec3(1f, 6f, 1f)));   // in view
        instances.add(Mat4.compose(new Vec3(-16f, 3f, 0f), Quat.IDENTITY, new Vec3(1f, 6f, 1f))); // out of view, its shadow falls in
        Node3D pillars = new Node3D("pillars", Mesh.cube(1f), new Material(Color.WHITE).setRoughness(0.9f));
        pillars.setInstances(instances);
        scene.add(pillars);
        scene.camera().lookAt(new Vec3(0, 20f, 0.01f), new Vec3(0, 0, 0)).setFovYDegrees(60);
        return scene;
    }

    private static void pillarsCheck(Scene3D scene, Scene3D.Stats before, Scene3D.Stats after, Pixels px, GpuHarness.Recorder r) {
        r.check("a caster out of view: only the one in view is drawn", after.instances() == 2, "drew " + after.instances() + " (the floor and one pillar)");
        r.guard("a caster out of view: its shadow still falls into view", () -> {
            // The sun travels +X and down at 45 degrees; a pillar 6 tall at x = -16 throws its shadow from -16 to -10.
            int shadowed = px.luma(-10.7f, 0f, 0f), lit = px.luma(-6f, 0f, 0f);
            r.check("a caster out of view: its shadow still falls into view", shadowed < lit * 0.6 && lit > 300, "shadowed " + shadowed + " lit " + lit);
        });
    }

    // ---- a skinned mesh --------------------------------------------------------------------------------------------

    private static Scene3D skinned() {
        Scene3D scene = base();
        scene.setAmbient(Color.WHITE, 1f);
        Node3D joint = new Node3D("joint");
        scene.add(joint);
        Mesh mesh = Mesh.cube(1f);
        int vertices = mesh.vertexCount();
        int[] joints = new int[vertices * 4];
        float[] weights = new float[vertices * 4];
        for (int v = 0; v < vertices; v++) weights[v * 4] = 1f;
        mesh.setSkinning(joints, weights);
        Node3D body = new Node3D("body", mesh, new Material(Color.WHITE).setUnlit(true));
        body.setSkin(new Skin(List.of(joint), List.of(Mat4.identity())));
        scene.add(body);
        scene.camera().lookAt(new Vec3(0, 0, 10), new Vec3(0, 0, 0)).setFovYDegrees(60);
        return scene;
    }

    private static void skinnedCheck(Scene3D scene, Scene3D.Stats before, Scene3D.Stats after, Pixels px, GpuHarness.Recorder r) {
        r.check("a skinned mesh: drawn while its joints are in view", before.culled() == 0 && before.instances() == 1,
                "culled " + before.culled() + ", drew " + before.instances());
        r.check("a skinned mesh: left out once its joints are far away", after.culled() == 1 && after.instances() == 0,
                "culled " + after.culled() + ", drew " + after.instances());
        r.guard("a skinned mesh: and the picture is empty", () -> r.check("a skinned mesh: and the picture is empty", px.luma(0f, 0f, 0f) < 20,
                "luma " + px.luma(0f, 0f, 0f)));
    }

    // ---- see-through instances ---------------------------------------------------------------------------------------

    /**
     * A red pane in front of a blue one, both half see-through, as instances of one node: the red one must be drawn after
     * the blue one whichever is first in the node's list.
     */
    private static Scene3D glass(boolean farFirst) {
        Scene3D scene = base();
        Instances instances = new Instances();
        Mat4 far = Mat4.translation(new Vec3(0f, 0f, -2f)), near = Mat4.translation(new Vec3(0f, 0f, 2f));
        instances.add(farFirst ? far : near);
        instances.add(farFirst ? near : far);
        instances.setTint(farFirst ? 0 : 1, 0f, 0f, 1f, 0.5f);
        instances.setTint(farFirst ? 1 : 0, 1f, 0f, 0f, 0.5f);
        Node3D panes = new Node3D("panes", Mesh.cube(3f), new Material(Color.WHITE).setUnlit(true).setBlend(true).setDoubleSided(false));
        panes.setInstances(instances);
        scene.add(panes);
        scene.camera().lookAt(new Vec3(0, 0, 12), new Vec3(0, 0, 0)).setFovYDegrees(30);
        return scene;
    }

    private static void glassCheck(Scene3D scene, Scene3D.Stats before, Scene3D.Stats after, Pixels px, GpuHarness.Recorder r) {
        r.guard("see-through instances: the near one is over the far one", () -> {
            int[] c = px.rgb(0f, 0f, 0f);
            // Red over blue over black is (.5, 0, .25) in light; blue over red over black would be (.25, 0, .5).
            r.check("see-through instances: the near one is over the far one", c[0] > c[2] + 20, "r " + c[0] + " b " + c[2]);
        });
    }

    // ---- the triangles of one see-through mesh -------------------------------------------------------------------------

    /**
     * One mesh of two panes, a red one in front of a blue one, the red one stored first, so drawn in stored order the blue
     * one lands on top of it.
     */
    private static Scene3D folded(boolean sort) {
        Scene3D scene = base();
        float[] positions = new float[24], normals = new float[24], uvs = new float[16];
        float[] z = {2f, -2f};
        float[] u = {0.25f, 0.75f}; // the middle of the left texel (red) and of the right one (blue)
        for (int q = 0; q < 2; q++) {
            float[][] corner = {{-1.5f, -1.5f}, {1.5f, -1.5f}, {1.5f, 1.5f}, {-1.5f, 1.5f}};
            for (int c = 0; c < 4; c++) {
                int v = q * 4 + c;
                positions[v * 3] = corner[c][0];
                positions[v * 3 + 1] = corner[c][1];
                positions[v * 3 + 2] = z[q];
                normals[v * 3 + 2] = 1f;
                uvs[v * 2] = u[q];
                uvs[v * 2 + 1] = 0.5f;
            }
        }
        int[] indices = {0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7};
        Mesh mesh = new Mesh(positions, normals, uvs, indices);
        TextureData texture = new TextureData(2, 1, new byte[]{(byte) 255, 0, 0, (byte) 128, 0, 0, (byte) 255, (byte) 128});
        Node3D panes = new Node3D("panes", mesh, new Material(texture).setUnlit(true).setBlend(true));
        panes.setSortTriangles(sort);
        scene.add(panes);
        scene.camera().lookAt(new Vec3(0, 0, 12), new Vec3(0, 0, 0)).setFovYDegrees(30);
        return scene;
    }

    private static void foldedCheck(boolean sorted, Pixels px, GpuHarness.Recorder r) {
        String name = sorted ? "sorted triangles: the near pane is over the far one" : "unsorted triangles: drawn in the stored order";
        r.guard(name, () -> {
            int[] c = px.rgb(0f, 0f, 0f);
            r.check(name, sorted ? c[0] > c[2] + 20 : c[2] > c[0] + 20, "r " + c[0] + " b " + c[2]);
        });
    }

    // ---- tone mapping and exposure -----------------------------------------------------------------------------------

    /**
     * A white floor lit from straight above at twice white (and a little ambient), so the lit colour is about 2.
     */
    private static Scene3D toned(ToneMapping mapping, float exposure) {
        Scene3D scene = base();
        scene.lights().add(Light.directional(new Vec3(0, -1, 0), Color.WHITE, 2f));
        scene.setAmbient(Color.BLACK, 0f);
        scene.setToneMapping(mapping);
        scene.setExposure(exposure);
        scene.add(new Node3D("floor", Mesh.plane(20f, 20f), new Material(Color.WHITE)));
        scene.camera().lookAt(new Vec3(0, 10f, 0.01f), new Vec3(0, 0, 0)).setFovYDegrees(40);
        return scene;
    }

    private static void toneCheck(String name, Pixels px, GpuHarness.Recorder r, int low, int high) {
        r.guard("tone mapping: " + name, () -> {
            int[] c = px.rgb(0f, 0f, 0f);
            r.check("tone mapping: " + name, c[1] >= low && c[1] < high, "green is " + c[1] + ", expected " + low + " to " + (high - 1));
        });
    }

    // ---- texture transforms and a second set of coordinates ----------------------------------------------------------

    /**
     * A plane with a two-pixel texture, red then blue along u, which runs along +X.
     */
    private static Scene3D uvPlane(Consumer<Material> material, Consumer<Mesh> mesh) {
        Scene3D scene = base();
        TextureData texture = new TextureData(2, 1, new byte[]{(byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, (byte) 255});
        Material m = new Material(texture).setUnlit(true);
        material.accept(m);
        Mesh plane = Mesh.plane(10f, 10f);
        if (mesh != null) mesh.accept(plane);
        scene.add(new Node3D("plane", plane, m));
        scene.camera().lookAt(new Vec3(0, 12f, 6f), new Vec3(0, 0, 0)).setFovYDegrees(50);
        return scene;
    }

    private static boolean red(int[] c) {
        return c[0] > 200 && c[2] < 60;
    }

    private static boolean blue(int[] c) {
        return c[2] > 200 && c[0] < 60;
    }

    private static void halves(String name, Pixels px, GpuHarness.Recorder r, boolean leftRed, boolean rightBlue) {
        r.guard("textures: " + name, () -> {
            int[] left = px.rgb(-2.5f, 0f, 0f), right = px.rgb(2.5f, 0f, 0f);
            r.check("textures: " + name, (leftRed ? red(left) : blue(left)) && (rightBlue ? blue(right) : red(right)),
                    "left " + java.util.Arrays.toString(left) + " right " + java.util.Arrays.toString(right));
        });
    }

    private static void scaled(Pixels px, GpuHarness.Recorder r) {
        r.guard("textures: scaled by two", () -> {
            int[] a = px.rgb(-3.75f, 0f, 0f), b = px.rgb(-1.25f, 0f, 0f), c = px.rgb(1.25f, 0f, 0f), d = px.rgb(3.75f, 0f, 0f);
            r.check("textures: scaled by two", red(a) && blue(b) && red(c) && blue(d),
                    java.util.Arrays.toString(a) + java.util.Arrays.toString(b) + java.util.Arrays.toString(c) + java.util.Arrays.toString(d));
        });
    }

    private static void turned(Pixels px, GpuHarness.Recorder r) {
        r.guard("textures: turned a quarter", () -> {
            // Turned, the texture's colours change along the plane's other axis: along X they agree, along Z they differ.
            int[] a = px.rgb(-2.5f, 0f, 2.5f), b = px.rgb(2.5f, 0f, 2.5f), c = px.rgb(-2.5f, 0f, -2.5f), d = px.rgb(2.5f, 0f, -2.5f);
            boolean sameAcross = (red(a) && red(b) && blue(c) && blue(d)) || (blue(a) && blue(b) && red(c) && red(d));
            r.check("textures: turned a quarter", sameAcross, java.util.Arrays.toString(a) + java.util.Arrays.toString(b)
                    + java.util.Arrays.toString(c) + java.util.Arrays.toString(d));
        });
    }

    private static void allRed(Pixels px, GpuHarness.Recorder r) {
        r.guard("textures: second coordinates", () -> {
            int[] left = px.rgb(-2.5f, 0f, 0f), right = px.rgb(2.5f, 0f, 0f);
            r.check("textures: second coordinates", red(left) && red(right), java.util.Arrays.toString(left) + java.util.Arrays.toString(right));
        });
    }
}
