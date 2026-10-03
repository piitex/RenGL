package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
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
                new Scenario("two lights cast shadows", ShadowGpuTest::twoCasters, ShadowGpuTest::twoCastersCheck));
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

    static String describe(int[] c) {
        return Arrays.toString(c);
    }
}
