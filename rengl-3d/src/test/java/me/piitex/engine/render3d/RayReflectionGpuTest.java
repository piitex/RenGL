package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.render3d.scene.TextureData;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reflections and ambient occlusion by ray queries ({@link Scene3D#setRayTracedReflections},
 * {@link Scene3D#setRayTracedAmbientOcclusion}) on a real GPU: a polished floor shows the box standing on it, in the colour of its
 * material or its texture, and shows nothing of it where the floor is rough; a corner is darkened by rays and an open floor is
 * not. Where the device can not trace rays (OpenGL) the scene keeps the sky's reflection and the depth picture's occlusion.
 * Skips where there is no GPU or no ray queries, like the other GPU tests.
 */
class RayReflectionGpuTest {
    private static final int VIEW = 256;

    private interface Check {
        void run(Scene3D scene, GpuHarness.Recorder r, Pixels pixels);
    }

    /**
     * @param traced says whether the scene reports it traced what it was asked to, when the device can
     */
    private record Scenario(String name, Supplier<Scene3D> scene, Check check, Predicate<Scene3D> traced, boolean expectTraced) {
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

        int[] rgb(float x, float y, float z) {
            int w = target.width(), h = target.height();
            var camera = scene.camera();
            Vec3 ndc = camera.projection((float) w / h).mul(camera.view()).transformPoint(new Vec3(x, y, z));
            int sx = Math.round((ndc.x() * 0.5f + 0.5f) * w), sy = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * h);
            if (sx < 0 || sy < 0 || sx >= w || sy >= h) throw new IllegalStateException("(" + x + ", " + y + ", " + z + ") is off screen");
            int i = (sy * w + sx) * 4;
            return new int[]{data.get(i) & 255, data.get(i + 1) & 255, data.get(i + 2) & 255};
        }

        /** The mean over a small square around the point, so a grain of scattered rays does not decide a test. */
        int[] mean(float x, float y, float z) {
            int w = target.width(), h = target.height();
            var camera = scene.camera();
            Vec3 ndc = camera.projection((float) w / h).mul(camera.view()).transformPoint(new Vec3(x, y, z));
            int cx = Math.round((ndc.x() * 0.5f + 0.5f) * w), cy = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * h);
            long[] sum = new long[3];
            int n = 0;
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    int sx = cx + dx, sy = cy + dy;
                    if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
                    int i = (sy * w + sx) * 4;
                    sum[0] += data.get(i) & 255;
                    sum[1] += data.get(i + 1) & 255;
                    sum[2] += data.get(i + 2) & 255;
                    n++;
                }
            }
            if (n == 0) throw new IllegalStateException("(" + x + ", " + y + ", " + z + ") is off screen");
            return new int[]{(int) (sum[0] / n), (int) (sum[1] / n), (int) (sum[2] / n)};
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
                new Scenario("a polished floor shows the box", () -> mirror(0.05f, Color.RED, null, true), RayReflectionGpuTest::redCheck, Scene3D::isUsingRayTracedReflections, true),
                new Scenario("without reflections the floor shows no box", () -> mirror(0.05f, Color.RED, null, false), RayReflectionGpuTest::noBoxCheck, Scene3D::isUsingRayTracedReflections, false),
                new Scenario("a rough floor shows no box", () -> mirror(0.9f, Color.RED, null, true), RayReflectionGpuTest::noBoxCheck, Scene3D::isUsingRayTracedReflections, true),
                new Scenario("a slightly rough floor still shows the box", () -> mirror(0.15f, Color.RED, null, true), RayReflectionGpuTest::redCheck, Scene3D::isUsingRayTracedReflections, true),
                new Scenario("a textured box is reflected in its texture", () -> mirror(0.05f, Color.WHITE, TextureData.solid(0f, 1f, 0f, 1f), true), RayReflectionGpuTest::greenCheck, Scene3D::isUsingRayTracedReflections, true),
                new Scenario("a polished floor shows the sky where nothing is in the way", RayReflectionGpuTest::skyMirror, RayReflectionGpuTest::skyCheck, Scene3D::isUsingRayTracedReflections, true),
                new Scenario("reflections and shadows trace together", RayReflectionGpuTest::shadowedMirror, RayReflectionGpuTest::shadowedCheck,
                        scene -> scene.isUsingRayTracedReflections() && scene.isUsingRayTracedShadows(), true),
                new Scenario("a corner is darkened by rays", () -> corner(true), RayReflectionGpuTest::cornerCheck, Scene3D::isUsingRayTracedAmbientOcclusion, true));
    }

    private List<DynamicTest> tests(RenderAPI api) {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            Scene3D scene = scenario.scene().get();
            boolean[] traced = {false};
            GpuHarness.Run run = GpuHarness.run(api, VIEW, (Consumer<Viewport3D>) viewport -> viewport.setContent(new SceneContent(scene)),
                    (device, target, recorder) -> {
                        if (device.api() != api) return;
                        traced[0] = scenario.traced().test(scene);
                        boolean capable = api == RenderAPI.VULKAN && device.supportsRayTracing();
                        if (api == RenderAPI.VULKAN && !capable) return;
                        Pixels pixels = new Pixels(scene, target);
                        try {
                            String dump = System.getProperty("rengl.test.dump");
                            if (dump != null) {
                                try {
                                    pixels.writePng(java.nio.file.Path.of(dump, "rt-" + scenario.name().replace(' ', '-') + "-" + api.name().toLowerCase() + ".png"));
                                } catch (java.io.IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            }
                            if (capable) scenario.check().run(scene, recorder, pixels);
                            boolean expected = capable && scenario.expectTraced();
                            recorder.check(scenario.name() + ": " + (expected ? "the rays are traced" : "the rays are not traced"),
                                    traced[0] == expected, "traced: " + traced[0]);
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

    // ---- a polished floor ------------------------------------------------------------------------------------------

    /**
     * A bare metal floor of the given roughness with a box standing on it behind the point (0, 0, 0): the floor there reflects the
     * box's face that looks at the camera. A sun lights the box.
     */
    private static Scene3D mirror(float roughness, Color boxColor, TextureData boxTexture, boolean reflections) {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setAmbient(Color.WHITE, 0.35f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.lights().add(Light.directional(new Vec3(0.3f, -1f, -0.5f), Color.WHITE, 1.4f));
        scene.setRayTracedReflections(reflections);
        scene.add(new Node3D("floor", Mesh.plane(40f, 40f), new Material(Color.WHITE).setMetallic(1f).setRoughness(roughness)));
        Material box = boxTexture == null ? new Material(boxColor) : new Material(boxTexture);
        box.setRoughness(0.8f);
        Node3D cube = new Node3D("box", Mesh.cube(2f), box);
        cube.setPosition(0f, 1f, -2f);
        scene.add(cube);
        scene.camera().lookAt(new Vec3(0f, 3f, 8f), new Vec3(0f, 0f, 0f)).setFovYDegrees(60);
        return scene;
    }

    private static void redCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("floor: the box is in the reflection", () -> {
            int[] c = px.mean(0f, 0f, 0f);
            r.check("floor: the box is in the reflection", c[0] > 60 && c[0] > c[1] * 2 && c[0] > c[2] * 2, rgb(c));
        });
        r.guard("floor: the reflection is only where the box is", () -> {
            int[] c = px.mean(3.5f, 0f, 0f);
            r.check("floor: away from the box nothing red", Math.abs(c[0] - c[1]) < 25 && Math.abs(c[1] - c[2]) < 25, rgb(c));
        });
    }

    private static void noBoxCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("floor: no box in the reflection", () -> {
            int[] c = px.mean(0f, 0f, 0f);
            r.check("floor: no box in the reflection", !(c[0] > 60 && c[0] > c[1] * 2 && c[0] > c[2] * 2), rgb(c));
        });
    }

    private static void greenCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("floor: the box's texture is in the reflection", () -> {
            int[] c = px.mean(0f, 0f, 0f);
            r.check("floor: the box's texture is in the reflection", c[1] > 60 && c[1] > c[0] * 2 && c[1] > c[2] * 2, rgb(c));
        });
    }

    /** The same floor and box lit by a sky of blue over white, which the floor reflects where the box is not. */
    private static Scene3D skyMirror() {
        Scene3D scene = mirror(0.05f, Color.RED, null, true);
        scene.setSkybox(Skybox.gradient(new Color(0.1f, 0.2f, 1f, 1f), Color.WHITE, Color.GRAY));
        scene.setEnvironmentLighting(1f);
        return scene;
    }

    private static void skyCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("floor: the box is in the reflection of a lit sky", () -> {
            int[] c = px.mean(0f, 0f, 0f);
            r.check("floor: the box is in the reflection of a lit sky", c[0] > c[2] * 1.5 && c[0] > 60, rgb(c));
        });
        r.guard("floor: the sky is in the reflection", () -> {
            int[] c = px.mean(3.5f, 0f, 0f);
            r.check("floor: the sky is in the reflection", c[2] > c[0] + 25 && c[2] > 80, rgb(c));
        });
    }

    /** A floor whose box is lit by a sun that casts a ray traced shadow: the reflection of the box is still there. */
    private static Scene3D shadowedMirror() {
        Scene3D scene = mirror(0.05f, Color.RED, null, true);
        scene.lights().get(0).setCastShadow(true);
        scene.setRayTracedShadows(true);
        return scene;
    }

    private static void shadowedCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("floor: the lit box is in the reflection", () -> {
            int[] c = px.mean(0f, 0f, 0f);
            r.check("floor: the lit box is in the reflection", c[0] > 60 && c[0] > c[1] * 2 && c[0] > c[2] * 2, rgb(c));
        });
    }

    private static String rgb(int[] c) {
        return "rgb " + c[0] + ", " + c[1] + ", " + c[2];
    }

    // ---- a corner --------------------------------------------------------------------------------------------------

    /**
     * A floor with a tall wall rising from it at x = 2, lit by the flat ambient light alone, with ambient occlusion by rays.
     */
    private static Scene3D corner(boolean rays) {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setAmbient(Color.WHITE, 0.8f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.setAmbientOcclusion(1.5f).setAmbientOcclusionRadius(1.2f);
        scene.setRayTracedAmbientOcclusion(rays);
        scene.add(new Node3D("floor", Mesh.plane(40f, 40f), new Material(Color.WHITE).setRoughness(0.9f)));
        Node3D wall = new Node3D("wall", Mesh.cube(1f), new Material(Color.WHITE).setRoughness(0.9f));
        wall.setPosition(2.5f, 2f, 0f);
        wall.setScale(new Vec3(1f, 4f, 12f));
        scene.add(wall);
        scene.camera().lookAt(new Vec3(-3f, 7f, 9f), new Vec3(0f, 0f, 0f)).setFovYDegrees(60);
        return scene;
    }

    private static void cornerCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("corner: the floor at the wall is darker than the open floor", () -> {
            int[] near = px.mean(1.85f, 0f, 0f), far = px.mean(-3f, 0f, 0f);
            r.check("corner: the floor at the wall is darker than the open floor", near[0] < far[0] * 0.88 && far[0] > 60, "near " + near[0] + " far " + far[0]);
        });
        r.guard("corner: the open floor is evenly lit", () -> {
            int a = px.mean(-3f, 0f, 0f)[0], b = px.mean(-5f, 0f, 2f)[0], c = px.mean(-2f, 0f, -3f)[0];
            r.check("corner: the open floor is evenly lit", Math.max(a, Math.max(b, c)) - Math.min(a, Math.min(b, c)) < Math.max(12, a * 0.12), a + ", " + b + ", " + c);
        });
    }
}
