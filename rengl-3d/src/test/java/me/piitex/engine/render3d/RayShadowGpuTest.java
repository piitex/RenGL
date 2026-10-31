package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.ShadowQuality;
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
 * Shadows by ray queries ({@link Scene3D#setRayTracedShadows}) on a real GPU: a sun, a spot light and a point light each throw
 * their shadow where the shadow maps do, a shadow follows a node that moves, and where the device can not trace rays (OpenGL)
 * the scene keeps drawing maps. Skips where there is no GPU or no ray queries, like the other GPU tests.
 */
class RayShadowGpuTest {
    private static final int VIEW = 256;

    private interface Check {
        void run(Scene3D scene, GpuHarness.Recorder r, Pixels pixels);
    }

    private record Scenario(String name, Supplier<Scene3D> scene, java.util.function.BiConsumer<Scene3D, Integer> perFrame, Check check) {
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

        int luma(float x, float y, float z) {
            int w = target.width(), h = target.height();
            var camera = scene.camera();
            Vec3 ndc = camera.projection((float) w / h).mul(camera.view()).transformPoint(new Vec3(x, y, z));
            int sx = Math.round((ndc.x() * 0.5f + 0.5f) * w), sy = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * h);
            if (sx < 0 || sy < 0 || sx >= w || sy >= h) throw new IllegalStateException("(" + x + ", " + y + ", " + z + ") is off screen");
            int i = (sy * w + sx) * 4;
            return (data.get(i) & 255) + (data.get(i + 1) & 255) + (data.get(i + 2) & 255);
        }

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
        private final java.util.function.BiConsumer<Scene3D, Integer> perFrame;
        private int frame;

        SceneContent(Scene3D scene, java.util.function.BiConsumer<Scene3D, Integer> perFrame) {
            this.scene = scene;
            this.perFrame = perFrame;
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
            if (perFrame != null) perFrame.accept(scene, ++frame);
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
                new Scenario("a sun casts a shadow", RayShadowGpuTest::sun, null, RayShadowGpuTest::sunCheck),
                new Scenario("a spot light casts a shadow", RayShadowGpuTest::spot, null, RayShadowGpuTest::spotCheck),
                new Scenario("a point light casts shadows all round", RayShadowGpuTest::pointRoom, null, RayShadowGpuTest::pointRoomCheck),
                new Scenario("a shadow follows a node that moves", RayShadowGpuTest::sun, RayShadowGpuTest::movePillar, RayShadowGpuTest::movedCheck),
                new Scenario("many instances cast shadows", RayShadowGpuTest::instanced, null, RayShadowGpuTest::instancedCheck));
    }

    private List<DynamicTest> tests(RenderAPI api) {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            Scene3D scene = scenario.scene().get();
            boolean[] usedRays = {false};
            GpuHarness.Run run = GpuHarness.run(api, VIEW, (Consumer<Viewport3D>) viewport -> viewport.setContent(new SceneContent(scene, scenario.perFrame())),
                    (device, target, recorder) -> {
                        if (device.api() != api) return;
                        usedRays[0] = scene.isUsingRayTracedShadows();
                        if (api == RenderAPI.VULKAN && !device.supportsRayTracing()) return;
                        Pixels pixels = new Pixels(scene, target);
                        try {
                            String dump = System.getProperty("rengl.test.dump");
                            if (dump != null) {
                                try {
                                    pixels.writePng(java.nio.file.Path.of(dump, "ray-" + scenario.name().replace(' ', '-') + "-" + api.name().toLowerCase() + ".png"));
                                } catch (java.io.IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            }
                            scenario.check().run(scene, recorder, pixels);
                            // Vulkan on a GPU with ray queries traces, OpenGL keeps its maps.
                            recorder.check(scenario.name() + ": " + (api == RenderAPI.VULKAN ? "the shadows are traced" : "the maps are kept"),
                                    usedRays[0] == (api == RenderAPI.VULKAN), "using ray traced shadows: " + usedRays[0]);
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

    private static Scene3D base() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setAmbient(Color.WHITE, 0.03f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.setRayTracedShadows(true);
        return scene;
    }

    // ---- a sun -----------------------------------------------------------------------------------------------------

    /**
     * A sun from the left with a pillar at x = -6: its shadow falls to the right of the pillar.
     */
    private static Scene3D sun() {
        Scene3D scene = base();
        scene.lights().add(Light.directional(new Vec3(1, -1, 0), Color.WHITE, 1.0f).setCastShadow(true));
        scene.add(floor(40f)).add(pillar("pillar", -6f, 0f, 2f));
        scene.camera().lookAt(new Vec3(0, 12f, 14f), new Vec3(0, 0, 0)).setFovYDegrees(75);
        return scene;
    }

    private static void sunCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("sun: the shadow falls beside the pillar", () -> {
            int shadowed = px.luma(-4f, 0f, 0f), lit = px.luma(-1.5f, 0f, 0f);
            r.check("sun: the shadow falls beside the pillar", shadowed < lit * 0.5 && lit > 60, "shadowed " + shadowed + " lit " + lit);
        });
        r.guard("sun: the pillar's own lit side is not shadowed", () -> {
            // The floor to the left of the pillar is lit by the sun like the open floor is.
            int left = px.luma(-8.5f, 0f, 0f), open = px.luma(0f, 0f, 0f);
            r.check("sun: the floor on the lit side of the pillar is lit", Math.abs(left - open) < Math.max(20, open * 0.15), "left " + left + " open " + open);
        });
        r.guard("sun: the open floor is not speckled with self shadows", () -> {
            int a = px.luma(2f, 0f, 3f), b = px.luma(5f, 0f, -2f), c = px.luma(0f, 0f, 1f);
            r.check("sun: the open floor is evenly lit", Math.max(a, Math.max(b, c)) - Math.min(a, Math.min(b, c)) < Math.max(20, a * 0.1), a + ", " + b + ", " + c);
        });
    }

    // ---- a spot light ----------------------------------------------------------------------------------------------

    private static Scene3D spot() {
        Scene3D scene = base();
        scene.lights().add(Light.spot(new Vec3(6, 6, 0), new Vec3(0, -1, 0), Color.WHITE, 3f, 20f, 35f, 45f).setCastShadow(true));
        scene.add(floor(40f)).add(pillar("pillar", 6f, 0f, 4f));
        scene.camera().lookAt(new Vec3(0, 12f, 14f), new Vec3(0, 0, 0)).setFovYDegrees(75);
        return scene;
    }

    private static void spotCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("spot: the shadow falls around the pillar", () -> {
            int shadowed = px.luma(6f, 0f, 1.2f), lit = px.luma(8.5f, 0f, 1.2f);
            r.check("spot: the shadow falls around the pillar", shadowed < lit * 0.6 && lit > 400, "shadowed " + shadowed + " lit " + lit);
        });
    }

    // ---- a point light ---------------------------------------------------------------------------------------------

    private static Scene3D pointRoom() {
        Scene3D scene = base();
        scene.lights().add(Light.point(new Vec3(0, 4, 0), Color.WHITE, 4f, 25f).setShadows(ShadowQuality.MEDIUM));
        scene.add(floor(40f));
        scene.add(pillar("east", 4f, 0f, 1.5f)).add(pillar("west", -4f, 0f, 1.5f)).add(pillar("south", 0f, 4f, 1.5f)).add(pillar("north", 0f, -4f, 1.5f))
                .add(pillar("diagonal", 3f, 3f, 1.5f));
        Node3D block = new Node3D("block", Mesh.cube(1.4f), new Material(Color.WHITE).setRoughness(0.9f));
        block.setPosition(0f, 1f, 0f);
        scene.add(block);
        scene.camera().lookAt(new Vec3(0, 20f, 3f), new Vec3(0, 0, 0)).setFovYDegrees(70);
        return scene;
    }

    private static void pointRoomCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        Object[][] spots = {
                {"east", 5.5f, 0f, 0f, 5.5f, 0f, 2.5f},
                {"west", -5.5f, 0f, 0f, -5.5f, 0f, 2.5f},
                {"south", 0f, 0f, 5.5f, 2.5f, 0f, 5.5f},
                {"north", 0f, 0f, -5.5f, 2.5f, 0f, -5.5f},
                {"underneath", 0f, 0f, 1.0f, 3f, 0f, 1.0f},
                {"diagonally", 4.1f, 0f, 4.1f, 5.8f, 0f, 2.0f}};
        for (Object[] spot : spots) {
            String name = "point light: the floor " + spot[0] + " of the bulb is shadowed";
            r.guard(name, () -> {
                int shadowed = px.luma((float) spot[1], (float) spot[2], (float) spot[3]);
                int lit = px.luma((float) spot[4], (float) spot[5], (float) spot[6]);
                r.check(name, shadowed < lit * 0.5 && lit > 400, "shadowed " + shadowed + " lit " + lit);
            });
        }
    }

    // ---- a node that moves -----------------------------------------------------------------------------------------

    /** From the fourth frame on the pillar stands at x = 6 instead of x = -6. */
    private static void movePillar(Scene3D scene, int frame) {
        if (frame < 4) return;
        for (Node3D node : scene.root().getChildren()) {
            if (node.getName().equals("pillar")) node.setPosition(6f, 1f, 0f);
        }
    }

    private static void movedCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("moving: the shadow is where the pillar went", () -> {
            int shadowed = px.luma(8f, 0f, 0f), lit = px.luma(3.5f, 0f, 0f);
            r.check("moving: the shadow is where the pillar went", shadowed < lit * 0.5 && lit > 60, "shadowed " + shadowed + " lit " + lit);
        });
        r.guard("moving: the old shadow is gone", () -> {
            int old = px.luma(-4f, 0f, 0f), open = px.luma(-1.5f, 0f, 0f);
            r.check("moving: the old shadow is gone", Math.abs(old - open) < Math.max(20, open * 0.15), "old place " + old + " open " + open);
        });
    }

    // ---- instances -------------------------------------------------------------------------------------------------

    private static Scene3D instanced() {
        Scene3D scene = base();
        scene.lights().add(Light.directional(new Vec3(1, -1, 0), Color.WHITE, 1.0f).setCastShadow(true));
        scene.add(floor(40f));
        Node3D posts = new Node3D("posts", Mesh.cube(1f), new Material(Color.WHITE).setRoughness(0.9f));
        var instances = new me.piitex.engine.render3d.scene.Instances();
        for (int i = 0; i < 5; i++) {
            instances.add(new Vec3(-8f + 4f * i, 1f, 0f), me.piitex.engine.render3d.math.Quat.IDENTITY, new Vec3(1f, 2f, 1f));
        }
        posts.setInstances(instances);
        scene.add(posts);
        scene.camera().lookAt(new Vec3(0, 12f, 14f), new Vec3(0, 0, 0)).setFovYDegrees(75);
        return scene;
    }

    private static void instancedCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        for (int i = 0; i < 5; i++) {
            int post = i;
            String name = "instances: post " + i + " throws its shadow";
            r.guard(name, () -> {
                float x = -8f + 4f * post;
                // The shadow runs 2 to the right of the post's far edge; a metre beyond it is lit again.
                int shadowed = px.luma(x + 1.5f, 0f, 0f), lit = px.luma(x + 3.2f, 0f, 1.2f);
                r.check(name, shadowed < lit * 0.5 && lit > 60, "shadowed " + shadowed + " lit " + lit);
            });
        }
    }
}
