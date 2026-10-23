package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.LightFalloff;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.ShadowFilter;
import me.piitex.engine.render3d.scene.ShadowQuality;
import me.piitex.engine.render3d.scene.Shadows;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The lighting and shadow system on a real GPU, on Vulkan and OpenGL: several lights casting shadows at once, point lights,
 * cascades, per-light settings. Each scenario renders a small scene and checks pixels at places where a shadow should be or
 * shouldn't. Skips where there is no GPU, like the other GPU tests.
 */
class ShadowGpuTest {
    private static final int VIEW = 256;

    private record Scenario(String name, Supplier<Scene3D> scene, Check check) {
    }

    private interface Check {
        void run(Scene3D scene, RenderTarget target, GpuHarness.Recorder r, Pixels pixels);
    }

    /**
     * The picture, sampled where a world point lands on screen.
     */
    private static final class Pixels {
        private final Scene3D scene;
        private final RenderTarget target;
        private final ByteBuffer data;

        Pixels(Scene3D scene, RenderTarget target) {
            this.scene = scene;
            this.target = target;
            this.data = target.readPixels();
        }

        int luma(float x, float y, float z) {
            int w = target.width(), h = target.height();
            var camera = scene.camera();
            Vec3 ndc = camera.projection((float) w / h).mul(camera.view()).transformPoint(new Vec3(x, y, z));
            int sx = Math.round((ndc.x() * 0.5f + 0.5f) * w), sy = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * h);
            if (sx < 0 || sy < 0 || sx >= w || sy >= h) throw new IllegalStateException("(" + x + ", " + y + ", " + z + ") is off screen");
            int i = (sy * w + sx) * 4;
            return (data.get(i) & 255) + (data.get(i + 1) & 255) + (data.get(i + 2) & 255);
        }

        /**
         * Writes the picture as a PNG, to look at it.
         */
        void writePng(java.nio.file.Path path) throws java.io.IOException {
            int w = target.width(), h = target.height();
            var image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 4;
                    image.setRGB(x, y, ((data.get(i) & 255) << 16) | ((data.get(i + 1) & 255) << 8) | (data.get(i + 2) & 255));
                }
            }
            java.nio.file.Files.createDirectories(path.getParent());
            javax.imageio.ImageIO.write(image, "png", path.toFile());
        }

        void free() {
            MemoryUtil.memFree(data);
        }
    }

    private static final class SceneContent implements Viewport3D.Content {
        private final Scene3D scene;

        SceneContent(Scene3D scene) {
            this.scene = scene;
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
            scene.render(device, target, delta);
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

    private static List<Scenario> scenarios() {
        return List.of(
                new Scenario("two lights cast shadows", ShadowGpuTest::twoCasters, ShadowGpuTest::twoCastersCheck),
                new Scenario("a point light casts shadows all round", ShadowGpuTest::pointRoom, ShadowGpuTest::pointRoomCheck),
                new Scenario("eight lights cast shadows", ShadowGpuTest::eightSpots, ShadowGpuTest::eightSpotsCheck),
                new Scenario("three cascades", () -> cascadeScene(3), ShadowGpuTest::cascadeCheck),
                new Scenario("one cascade", () -> cascadeScene(1), ShadowGpuTest::cascadeCheck),
                new Scenario("custom falloffs", ShadowGpuTest::falloffScene, ShadowGpuTest::falloffCheck),
                new Scenario("custom shadow filters", ShadowGpuTest::filterScene, ShadowGpuTest::filterCheck),
                new Scenario("a custom filter that does not compile", ShadowGpuTest::brokenFilterScene, ShadowGpuTest::brokenFilterCheck));
    }

    private List<DynamicTest> tests(RenderAPI api) {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            Scene3D scene = scenario.scene().get();
            GpuHarness.Run run = GpuHarness.run(api, VIEW, (Consumer<Viewport3D>) viewport -> viewport.setContent(new SceneContent(scene)),
                    (device, target, recorder) -> {
                        if (device.api() != api) return;
                        Pixels pixels = new Pixels(scene, target);
                        try {
                            String dump = System.getProperty("rengl.test.dump"); // a folder to write each scenario's picture into
                            if (dump != null) {
                                try {
                                    pixels.writePng(java.nio.file.Path.of(dump, scenario.name().replace(' ', '-') + "-" + api.name().toLowerCase() + ".png"));
                                } catch (java.io.IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            }
                            scenario.check().run(scene, target, recorder, pixels);
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

    private static Node3D pillar(String name, float x, float z, float height) {
        Node3D n = new Node3D(name, Mesh.cube(1f), new Material(Color.WHITE).setRoughness(0.9f));
        n.setPosition(x, height / 2f, z);
        n.setScale(new Vec3(1f, height, 1f));
        return n;
    }

    private static Node3D floor(float size) {
        return new Node3D("floor", Mesh.plane(size, size), new Material(Color.WHITE).setRoughness(0.9f));
    }

    // ---- two lights, two shadows ---------------------------------------------------------------------------------

    /**
     * A weak sun from the left with a pillar at x = -6, and a strong spot light over a taller pillar at x = 6, each with its
     * own shadow settings. The spot light's shadow is a ring around its pillar's foot; the sun's falls to the right of its pillar.
     */
    private static Scene3D twoCasters() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.lights().add(Light.directional(new Vec3(1, -1, 0), Color.WHITE, 0.4f).setShadows(Shadows.of(ShadowQuality.HIGH)));
        scene.lights().add(Light.spot(new Vec3(6, 6, 0), new Vec3(0, -1, 0), Color.WHITE, 3f, 20f, 35f, 45f)
                .setShadows(Shadows.of(ShadowQuality.LOW).filter(ShadowFilter.HARD)));
        scene.setAmbient(Color.WHITE, 0.03f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.setShadowDistance(60f);
        scene.add(floor(40f)).add(pillar("sunPillar", -6f, 0f, 2f)).add(pillar("spotPillar", 6f, 0f, 4f));
        scene.camera().lookAt(new Vec3(0, 12f, 14f), new Vec3(0, 0, 0)).setFovYDegrees(75);
        return scene;
    }

    private static void twoCastersCheck(Scene3D scene, RenderTarget target, GpuHarness.Recorder r, Pixels px) {
        r.guard("two lights: the sun's shadow falls beside its pillar", () -> {
            int shadowed = px.luma(-4f, 0f, 0f), lit = px.luma(-1.5f, 0f, 0f);
            r.check("two lights: the sun's shadow falls beside its pillar", shadowed < lit * 0.75 && lit > 40,
                    "shadowed " + shadowed + " lit " + lit);
        });
        r.guard("two lights: the spot light's shadow falls around its pillar", () -> {
            // The pillar is 4 tall under a light 6 up, so its shadow reaches 1.5 from its axis; z = 1.2 is in front of it, in view.
            int shadowed = px.luma(6f, 0f, 1.2f), lit = px.luma(8.5f, 0f, 1.2f);
            r.check("two lights: the spot light's shadow falls around its pillar", shadowed < lit * 0.8 && lit > 600,
                    "shadowed " + shadowed + " lit " + lit);
        });
        r.guard("two lights: neither shadow lands in the other's place", () -> {
            // under the sun's shadow the spot light still lights nothing (out of its cone), and far from both the floor is plain
            int betweenA = px.luma(0f, 0f, 0f), betweenB = px.luma(0f, 0f, 1f);
            r.check("two lights: the floor between is evenly lit", Math.abs(betweenA - betweenB) < Math.max(12, betweenA * 0.1),
                    betweenA + " vs " + betweenB);
        });
    }

    // ---- eight lights ---------------------------------------------------------------------------------------------

    /**
     * Eight spot lights in a row, each over its own pillar and leaning towards it from the outer side, so each throws a
     * shadow towards the middle of the picture that no other light fills in (the pillars' tops, seen from the middle,
     * lean outwards and would hide a shadow on that side).
     */
    private static Scene3D eightSpots() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setAmbient(Color.WHITE, 0.02f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.add(floor(80f));
        for (int i = 0; i < 8; i++) {
            float x = -24.5f + 7f * i, outward = x < 0 ? -1f : 1f;
            scene.lights().add(Light.spot(new Vec3(x + 3f * outward, 6f, 0f), new Vec3(-3f * outward, -6f, 0f), Color.WHITE, 3f, 20f, 15f, 25f)
                    .setShadows(Shadows.of(ShadowQuality.LOW)));
            scene.add(pillar("pillar" + i, x, 0f, 2f));
        }
        scene.camera().lookAt(new Vec3(0, 45f, 0.01f), new Vec3(0, 0, 0)).setFovYDegrees(70);
        return scene;
    }

    private static void eightSpotsCheck(Scene3D scene, RenderTarget target, GpuHarness.Recorder r, Pixels px) {
        r.check("eight lights: all eight are given shadow views", scene.getStats().shadowViews() == 8, "views " + scene.getStats().shadowViews());
        for (int i = 0; i < 8; i++) {
            int light = i;
            String name = "eight lights: light " + i + " throws a shadow behind its pillar";
            r.guard(name, () -> {
                float x = -24.5f + 7f * light, outward = x < 0 ? -1f : 1f;
                // The pillar's far edge is 0.5 towards the middle and the shadow runs 1.75 further.
                int shadowed = px.luma(x - 1.4f * outward, 0f, 0f), lit = px.luma(x + 2.3f * outward, 0f, 0f);
                r.check(name, shadowed < lit * 0.5 && lit > 100, "shadowed " + shadowed + " lit " + lit);
            });
        }
    }

    // ---- a point light ---------------------------------------------------------------------------------------------

    /**
     * A bulb 4 up over a floor, with a low pillar east, west, south and north of it, one on the diagonal and a block right
     * under it, so that shadows land on four of the cube's sides, across a seam between two, and on its underside.
     */
    private static Scene3D pointRoom() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.lights().add(Light.point(new Vec3(0, 4, 0), Color.WHITE, 4f, 25f).setShadows(ShadowQuality.MEDIUM));
        scene.setAmbient(Color.WHITE, 0.03f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.add(floor(40f));
        scene.add(pillar("east", 4f, 0f, 1.5f)).add(pillar("west", -4f, 0f, 1.5f)).add(pillar("south", 0f, 4f, 1.5f)).add(pillar("north", 0f, -4f, 1.5f))
                .add(pillar("diagonal", 3f, 3f, 1.5f)); // its shadow runs along the seam between two sides of the cube
        Node3D block = new Node3D("block", Mesh.cube(1.4f), new Material(Color.WHITE).setRoughness(0.9f));
        block.setPosition(0f, 1f, 0f);
        scene.add(block);
        scene.camera().lookAt(new Vec3(0, 20f, 3f), new Vec3(0, 0, 0)).setFovYDegrees(70);
        return scene;
    }

    private static void pointRoomCheck(Scene3D scene, RenderTarget target, GpuHarness.Recorder r, Pixels px) {
        Object[][] spots = {
                {"east", 5.5f, 0f, 0f, 5.5f, 0f, 2.5f},
                {"west", -5.5f, 0f, 0f, -5.5f, 0f, 2.5f},
                {"south", 0f, 0f, 5.5f, 2.5f, 0f, 5.5f},
                {"north", 0f, 0f, -5.5f, 2.5f, 0f, -5.5f},
                {"underneath", 0f, 0f, 1.0f, 3f, 0f, 1.0f},
                {"diagonally (across the seam between two faces)", 4.1f, 0f, 4.1f, 5.8f, 0f, 2.0f}};
        for (Object[] spot : spots) {
            String name = "point light: the floor " + spot[0] + " of the bulb is shadowed";
            r.guard(name, () -> {
                int shadowed = px.luma((float) spot[1], (float) spot[2], (float) spot[3]);
                int lit = px.luma((float) spot[4], (float) spot[5], (float) spot[6]);
                r.check(name, shadowed < lit * 0.5 && lit > 600, "shadowed " + shadowed + " lit " + lit);
            });
        }
    }

    // ---- cascades --------------------------------------------------------------------------------------------------

    /**
     * Widths, in scene units, of the soft edge of the nearest pillar's shadow, by backend and cascade count, so that the one
     * cascade scenario can be compared with the three cascade one that ran before it.
     */
    private static final java.util.Map<String, Float> EDGE_WIDTH = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A sun over a long floor with a 2 tall pillar near the camera, one in the middle distance and one far away, each
     * throwing its shadow to the right. Shadows reach 80 units, as one cascade or three of 1024 pixels.
     */
    private static Scene3D cascadeScene(int cascades) {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.lights().add(Light.directional(new Vec3(1, -1, 0), Color.WHITE, 1.2f)
                .setShadows(Shadows.of(ShadowQuality.MEDIUM).cascades(cascades).distance(80f)));
        scene.setAmbient(Color.WHITE, 0.1f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.add(new Node3D("floor", Mesh.plane(300f, 300f), new Material(Color.WHITE).setRoughness(0.9f)));
        // The far pillar is wide along z: the camera sees the floor there at a grazing angle, so a narrow shadow is a pixel or two.
        Node3D far = pillar("far", -3f, -30f, 2f);
        far.setScale(new Vec3(1f, 2f, 8f));
        scene.add(pillar("near", -3f, 2f, 2f)).add(pillar("middle", -3f, -8f, 2f)).add(far);
        scene.camera().lookAt(new Vec3(0, 4f, 8f), new Vec3(0, 0, 0)).setFovYDegrees(60);
        return scene;
    }

    private static void cascadeCheck(Scene3D scene, RenderTarget target, GpuHarness.Recorder r, Pixels px) {
        int cascades = scene.lights().get(0).getShadows().cascades();
        String label = "cascades (" + cascades + ")";
        for (float z : new float[]{2f, -8f, -30f}) {
            String name = label + ": the pillar at z = " + (int) z + " shadows the floor";
            r.guard(name, () -> {
                int shadowed = px.luma(-1.5f, 0f, z), lit = px.luma(1.5f, 0f, z);
                r.check(name, shadowed < lit * 0.6 && lit > 100, "shadowed " + shadowed + " lit " + lit);
            });
        }
        String name = label + ": the nearest shadow's edge";
        r.guard(name, () -> {
            // Walk across the shadow's near edge (z = 2.5) in small steps and measure how wide the grey is.
            int dark = px.luma(-1.5f, 0f, 2.0f), light = px.luma(-1.5f, 0f, 3.2f);
            int low = dark + (light - dark) / 6, high = light - (light - dark) / 6;
            int grey = 0;
            float step = 0.01f;
            for (float dz = -0.5f; dz <= 0.5f; dz += step) {
                int l = px.luma(-1.5f, 0f, 2.5f + dz);
                if (l > low && l < high) grey++;
            }
            float width = grey * step;
            String key = (target.isBottomLeftOrigin() ? "gl" : "vk");
            EDGE_WIDTH.put(key + cascades, width);
            r.check(name + " is soft but not smeared", light - dark > 100 && width < 0.6f, "edge " + width + " units wide, dark " + dark + " light " + light);
            Float three = EDGE_WIDTH.get(key + 3);
            if (cascades == 1 && three != null) {
                r.check(label + ": the nearest edge is sharper with three cascades than with one", three < width * 0.75f,
                        "three cascades " + three + ", one " + width);
            }
        });
    }

    // ---- custom falloffs and filters -------------------------------------------------------------------------------

    /**
     * Three bulbs along x. The left one's falloff gives nothing, the middle one's range is 3 but its falloff reaches twice as
     * far, and the right one has the built-in falloff.
     */
    private static Scene3D falloffScene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.lights().add(Light.point(new Vec3(-12, 3, 0), Color.WHITE, 3f, 10f).setFalloff(LightFalloff.custom("dark", "return 0.0;")));
        scene.lights().add(Light.point(new Vec3(0, 3, 0), Color.WHITE, 3f, 3f)
                .setFalloff(LightFalloff.custom("wide", "return dist < range * 2.0 ? 1.0 : 0.0;")));
        scene.lights().add(Light.point(new Vec3(12, 3, 0), Color.WHITE, 3f, 10f));
        scene.setAmbient(Color.WHITE, 0.03f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.add(floor(60f));
        scene.camera().lookAt(new Vec3(0, 22f, 6f), new Vec3(0, 0, 0)).setFovYDegrees(70);
        return scene;
    }

    private static void falloffCheck(Scene3D scene, RenderTarget target, GpuHarness.Recorder r, Pixels px) {
        r.guard("custom falloff: a falloff of nothing leaves the floor dark", () -> {
            // -8 is out of reach of every light, so it shows the ambient light alone.
            int dark = px.luma(-12f, 0f, 0f), ambient = px.luma(-8f, 0f, 0f), lit = px.luma(12f, 0f, 0f);
            r.check("custom falloff: a falloff of nothing leaves the floor dark", Math.abs(dark - ambient) < 12 && lit > ambient * 3,
                    "under the dark bulb " + dark + ", ambient only " + ambient + ", under the plain one " + lit);
        });
        r.guard("custom falloff: a custom falloff reaches past the light's range", () -> {
            int reached = px.luma(-4.5f, 0f, 0f), beyond = px.luma(-8f, 0f, 0f);
            r.check("custom falloff: a custom falloff reaches past the light's range", reached > beyond * 3 && reached > 100, "at 5.4 from it " + reached + ", at 8.5 " + beyond);
        });
    }

    /**
     * A sun beside a pillar whose filter is a custom wrapper of one lookup, and a spot light over another whose custom
     * filter says everything is lit.
     */
    private static Scene3D filterScene() {
        Scene3D scene = twoCasters();
        scene.lights().clear();
        scene.lights().add(Light.directional(new Vec3(1, -1, 0), Color.WHITE, 0.4f)
                .setShadows(Shadows.of(ShadowQuality.HIGH).filter(ShadowFilter.custom("oneLookup", "return shadowLit(uv, depth);"))));
        scene.lights().add(Light.spot(new Vec3(6, 6, 0), new Vec3(0, -1, 0), Color.WHITE, 3f, 20f, 35f, 45f)
                .setShadows(Shadows.of(ShadowQuality.LOW).filter(ShadowFilter.custom("neverShadowed", "return 1.0;"))));
        return scene;
    }

    private static void filterCheck(Scene3D scene, RenderTarget target, GpuHarness.Recorder r, Pixels px) {
        r.guard("custom filter: a lookup through shadowLit casts the sun's shadow", () -> {
            int shadowed = px.luma(-4f, 0f, 0f), lit = px.luma(-1.5f, 0f, 0f);
            r.check("custom filter: a lookup through shadowLit casts the sun's shadow", shadowed < lit * 0.75 && lit > 40, "shadowed " + shadowed + " lit " + lit);
        });
        r.guard("custom filter: a filter that says lit removes the spot light's shadow", () -> {
            // Nearer the axis than the point beside it, so without a shadow it is the brighter one.
            int inside = px.luma(6f, 0f, 1.2f), outside = px.luma(4.1f, 0f, 0f);
            r.check("custom filter: a filter that says lit removes the spot light's shadow", inside > outside * 0.9 && outside > 600, "inside the ring " + inside + ", outside " + outside);
        });
    }

    /**
     * The sun's filter is not GLSL: it is reported, and the light uses the built-in filter instead.
     */
    private static Scene3D brokenFilterScene() {
        Scene3D scene = twoCasters();
        scene.lights().clear();
        scene.lights().add(Light.directional(new Vec3(1, -1, 0), Color.WHITE, 0.4f)
                .setShadows(Shadows.of(ShadowQuality.HIGH).filter(ShadowFilter.custom("broken", "this is not glsl"))));
        return scene;
    }

    private static void brokenFilterCheck(Scene3D scene, RenderTarget target, GpuHarness.Recorder r, Pixels px) {
        r.guard("broken filter: the scene still draws with a shadow", () -> {
            int shadowed = px.luma(-4f, 0f, 0f), lit = px.luma(-1.5f, 0f, 0f);
            r.check("broken filter: the scene still draws with a shadow", shadowed < lit * 0.75 && lit > 40, "shadowed " + shadowed + " lit " + lit);
        });
    }

    static String describe(int[] c) {
        return Arrays.toString(c);
    }
}
