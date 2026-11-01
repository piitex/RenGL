package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
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
 * Global illumination by ray queries ({@link Scene3D#setRayTracedGlobalIllumination}) on a real GPU: a red wall tints the floor
 * beside it, a glowing box lights the floor around it, a roof shuts the sky out from the floor under it, an open floor under a
 * flat ambient light is lit as it was, the picture the filter leaves is smooth, and it follows a moving camera. Where the device
 * can not trace rays (OpenGL) the scene keeps its flat or sky ambient light. Skips where there is no GPU or no ray queries, like
 * the other GPU tests.
 */
class RayGlobalIlluminationGpuTest {
    private static final int VIEW = 256;
    /** Enough frames for the filter's average over time to settle. */
    private static final int FRAMES = 48;

    private interface Check {
        void run(Scene3D scene, GpuHarness.Recorder r, Pixels pixels);
    }

    /**
     * @param animate runs before each frame is drawn, with the number of the frame
     */
    private record Scenario(String name, Supplier<Scene3D> scene, Check check, Predicate<Scene3D> traced, boolean expectTraced, Consumer<Integer> animate) {
        Scenario(String name, Supplier<Scene3D> scene, Check check, Predicate<Scene3D> traced, boolean expectTraced) {
            this(name, scene, check, traced, expectTraced, null);
        }
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

        private int[] screen(float x, float y, float z) {
            int w = target.width(), h = target.height();
            var camera = scene.camera();
            Vec3 ndc = camera.projection((float) w / h).mul(camera.view()).transformPoint(new Vec3(x, y, z));
            int cx = Math.round((ndc.x() * 0.5f + 0.5f) * w), cy = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * h);
            if (cx < 0 || cy < 0 || cx >= w || cy >= h) throw new IllegalStateException("(" + x + ", " + y + ", " + z + ") is off screen");
            return new int[]{cx, cy};
        }

        /** The mean over a small square around the point, so a little grain does not decide a test. */
        int[] mean(float x, float y, float z) {
            int[] at = screen(x, y, z);
            long[] sum = new long[3];
            int n = 0;
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    int sx = at[0] + dx, sy = at[1] + dy;
                    if (sx < 0 || sy < 0 || sx >= target.width() || sy >= target.height()) continue;
                    int i = (sy * target.width() + sx) * 4;
                    sum[0] += data.get(i) & 255;
                    sum[1] += data.get(i + 1) & 255;
                    sum[2] += data.get(i + 2) & 255;
                    n++;
                }
            }
            return new int[]{(int) (sum[0] / n), (int) (sum[1] / n), (int) (sum[2] / n)};
        }

        /** How far, on average, the red of the pixels of a small square is from their mean: the grain left in the picture. */
        double grain(float x, float y, float z) {
            int[] at = screen(x, y, z);
            int mean = mean(x, y, z)[0];
            double sum = 0;
            int n = 0;
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    int sx = at[0] + dx, sy = at[1] + dy;
                    if (sx < 0 || sy < 0 || sx >= target.width() || sy >= target.height()) continue;
                    sum += Math.abs((data.get((sy * target.width() + sx) * 4) & 255) - mean);
                    n++;
                }
            }
            return sum / n;
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
        private final Consumer<Integer> animate;
        private int frame;

        SceneContent(Scene3D scene, Consumer<Integer> animate) {
            this.scene = scene;
            this.animate = animate;
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
            if (animate != null) animate.accept(frame);
            frame++;
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
        Scene3D[] moving = {null};
        return List.of(
                new Scenario("a red wall tints the floor beside it", () -> bounce(true), RayGlobalIlluminationGpuTest::tintedCheck, Scene3D::isUsingRayTracedGlobalIllumination, true),
                new Scenario("without global illumination the floor is not tinted", () -> bounce(false), RayGlobalIlluminationGpuTest::untintedCheck, Scene3D::isUsingRayTracedGlobalIllumination, false),
                new Scenario("the filtered picture is smooth", () -> bounce(true), RayGlobalIlluminationGpuTest::smoothCheck, Scene3D::isUsingRayTracedGlobalIllumination, true),
                new Scenario("the light follows a moving camera", () -> moving[0] = bounce(true), RayGlobalIlluminationGpuTest::movingCheck, Scene3D::isUsingRayTracedGlobalIllumination, true,
                        frame -> moving[0].camera().lookAt(new Vec3(-3f + 1.5f * (float) Math.sin(frame * 0.12), 7f, 9f), new Vec3(0f, 0f, 0f))),
                new Scenario("a glowing box lights the floor around it", () -> glow(true), RayGlobalIlluminationGpuTest::glowCheck, Scene3D::isUsingRayTracedGlobalIllumination, true),
                new Scenario("more rays make the light of a small box smoother", () -> glow(true).setRayTracedGlobalIlluminationRays(4), RayGlobalIlluminationGpuTest::smoothGlowCheck, Scene3D::isUsingRayTracedGlobalIllumination, true),
                new Scenario("without global illumination a glowing box lights nothing", () -> glow(false), RayGlobalIlluminationGpuTest::noGlowCheck, Scene3D::isUsingRayTracedGlobalIllumination, false),
                new Scenario("a roof shuts the sky out", () -> roof(true), RayGlobalIlluminationGpuTest::roofCheck, Scene3D::isUsingRayTracedGlobalIllumination, true),
                new Scenario("without global illumination a roof changes nothing", () -> roof(false), RayGlobalIlluminationGpuTest::noRoofCheck, Scene3D::isUsingRayTracedGlobalIllumination, false),
                new Scenario("an open floor keeps the light of a flat ambient", () -> open(true), RayGlobalIlluminationGpuTest::openCheck, Scene3D::isUsingRayTracedGlobalIllumination, true),
                new Scenario("an open floor is lit the same without it", () -> open(false), RayGlobalIlluminationGpuTest::openCheck, Scene3D::isUsingRayTracedGlobalIllumination, false),
                new Scenario("it works with every other ray traced effect", RayGlobalIlluminationGpuTest::everything, RayGlobalIlluminationGpuTest::tintedCheck,
                        scene -> scene.isUsingRayTracedGlobalIllumination() && scene.isUsingRayTracedShadows() && scene.isUsingRayTracedReflections()
                                && scene.isUsingRayTracedAmbientOcclusion(), true));
    }

    private List<DynamicTest> tests(RenderAPI api) {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            Scene3D scene = scenario.scene().get();
            boolean[] traced = {false};
            GpuHarness.Run run = GpuHarness.run(api, VIEW, (Consumer<Viewport3D>) viewport -> viewport.setContent(new SceneContent(scene, scenario.animate())),
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
                                    pixels.writePng(java.nio.file.Path.of(dump, "gi-" + scenario.name().replace(' ', '-') + "-" + api.name().toLowerCase() + ".png"));
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
                    }, FRAMES);
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

    private static String rgb(int[] c) {
        return "rgb " + c[0] + ", " + c[1] + ", " + c[2];
    }

    // ---- a red wall beside a floor ---------------------------------------------------------------------------------

    /**
     * A white floor with a tall red wall rising from it at x = 2.5, lit by a sun that shines on the floor and on the wall's face
     * toward it, and by nothing else: what tints the floor beside the wall can only be light bouncing off it.
     */
    private static Scene3D bounce(boolean gi) {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setAmbient(Color.BLACK, 0f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.lights().add(Light.directional(new Vec3(1f, -1.2f, 0f), Color.WHITE, 0.8f));
        scene.setRayTracedGlobalIllumination(gi);
        scene.add(new Node3D("floor", Mesh.plane(40f, 40f), new Material(Color.WHITE).setRoughness(0.9f)));
        Node3D wall = new Node3D("wall", Mesh.cube(1f), new Material(Color.RED).setRoughness(0.9f));
        wall.setPosition(2.5f, 2f, 0f);
        wall.setScale(new Vec3(1f, 4f, 12f));
        scene.add(wall);
        scene.camera().lookAt(new Vec3(-3f, 7f, 9f), new Vec3(0f, 0f, 0f)).setFovYDegrees(60);
        return scene;
    }

    private static void tintedCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("wall: the floor beside it is tinted red", () -> {
            int[] near = px.mean(1.5f, 0f, 0f), far = px.mean(-4f, 0f, 0f);
            r.check("wall: the floor beside it is tinted red", (near[0] - near[1]) > (far[0] - far[1]) + 12 && far[0] > 60,
                    "near " + rgb(near) + " far " + rgb(far));
        });
        r.guard("wall: the floor far from it is not", () -> {
            int[] far = px.mean(-4f, 0f, 0f);
            r.check("wall: the floor far from it is not", Math.abs(far[0] - far[1]) < 14 && Math.abs(far[1] - far[2]) < 14, rgb(far));
        });
    }

    private static void untintedCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("wall: the floor beside it is not tinted", () -> {
            int[] near = px.mean(1.5f, 0f, 0f), far = px.mean(-4f, 0f, 0f);
            r.check("wall: the floor beside it is not tinted", Math.abs((near[0] - near[1]) - (far[0] - far[1])) < 10 && far[0] > 60,
                    "near " + rgb(near) + " far " + rgb(far));
        });
    }

    private static void smoothCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("grain: the tinted floor is smooth", () -> {
            double near = px.grain(1.2f, 0f, 0f), mid = px.grain(-1f, 0f, 1f);
            r.check("grain: the tinted floor is smooth", near < 5.0 && mid < 5.0, "near " + near + " mid " + mid);
        });
    }

    private static void movingCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("moving: the floor beside the wall is still tinted", () -> {
            int[] near = px.mean(1.5f, 0f, 0f), far = px.mean(-4f, 0f, 0f);
            r.check("moving: the floor beside the wall is still tinted", (near[0] - near[1]) > (far[0] - far[1]) + 12 && far[0] > 60,
                    "near " + rgb(near) + " far " + rgb(far));
        });
        r.guard("moving: the picture is not smeared", () -> {
            double near = px.grain(1.2f, 0f, 0f);
            r.check("moving: the picture is not smeared", near < 8.0, "grain " + near);
        });
    }

    // ---- a glowing box ---------------------------------------------------------------------------------------------

    /** A white floor in the dark with a box that glows standing on it: the only light there is. */
    private static Scene3D glow(boolean gi) {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setAmbient(Color.BLACK, 0f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.setRayTracedGlobalIllumination(gi);
        scene.add(new Node3D("floor", Mesh.plane(40f, 40f), new Material(Color.WHITE).setRoughness(0.9f)));
        Node3D lamp = new Node3D("lamp", Mesh.cube(1f), new Material(Color.BLACK).setRoughness(0.9f).setEmissive(Color.WHITE, 4f));
        lamp.setPosition(0f, 0.5f, -2f);
        scene.add(lamp);
        scene.camera().lookAt(new Vec3(0f, 4f, 8f), new Vec3(0f, 0f, 0f)).setFovYDegrees(60);
        return scene;
    }

    private static void glowCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("glow: the floor beside the box is lit", () -> {
            int[] near = px.mean(0f, 0f, -1f), far = px.mean(3f, 0f, 0f);
            r.check("glow: the floor beside the box is lit", near[0] > 120 && near[0] > far[0] + 60, "near " + rgb(near) + " far " + rgb(far));
        });
    }

    private static void smoothGlowCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("glow: four rays light the floor smoothly", () -> {
            int[] near = px.mean(0f, 0f, -1f), mid = px.mean(3f, 0f, 0f);
            double grain = Math.max(px.grain(2f, 0f, 0f), px.grain(-3f, 0f, 0f));
            r.check("glow: four rays light the floor smoothly", near[0] > 120 && mid[0] > 25 && mid[0] < near[0] - 60 && grain < 4.0,
                    "near " + rgb(near) + " mid " + rgb(mid) + " grain " + grain);
        });
    }

    private static void noGlowCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("glow: the floor stays dark", () -> {
            int[] near = px.mean(0f, 0f, -1f);
            r.check("glow: the floor stays dark", near[0] < 12, rgb(near));
        });
    }

    // ---- a roof ----------------------------------------------------------------------------------------------------

    /**
     * A white floor under a white sky, half of it covered by a roof: where the roof is, the sky is shut out, and the light that
     * comes in is what the sky gives the roof's underside, which the roof itself shuts out from.
     */
    private static Scene3D roof(boolean gi) {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setAmbient(Color.BLACK, 0f);
        scene.setSkybox(Skybox.gradient(Color.WHITE, Color.WHITE, Color.WHITE));
        scene.setEnvironmentLighting(0.8f);
        scene.setRayTracedGlobalIllumination(gi);
        scene.add(new Node3D("floor", Mesh.plane(40f, 40f), new Material(Color.WHITE).setRoughness(0.9f)));
        Node3D slab = new Node3D("roof", Mesh.cube(1f), new Material(Color.WHITE).setRoughness(0.9f));
        slab.setPosition(-4f, 2f, 0f);
        slab.setScale(new Vec3(4f, 0.2f, 6f));
        scene.add(slab);
        scene.camera().lookAt(new Vec3(3f, 6f, 9f), new Vec3(0f, 0f, 0f)).setFovYDegrees(60);
        return scene;
    }

    private static void roofCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("roof: the floor under it is darker than the open floor", () -> {
            int[] under = px.mean(-4f, 0f, 0f), open = px.mean(2f, 0f, 0f);
            r.check("roof: the floor under it is darker than the open floor", under[0] < open[0] * 0.8 && open[0] > 80, "under " + rgb(under) + " open " + rgb(open));
        });
    }

    private static void noRoofCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("roof: the floor under it is as bright as the open floor", () -> {
            int[] under = px.mean(-4f, 0f, 0f), open = px.mean(2f, 0f, 0f);
            r.check("roof: the floor under it is as bright as the open floor", Math.abs(under[0] - open[0]) < open[0] * 0.1 && open[0] > 80, "under " + rgb(under) + " open " + rgb(open));
        });
    }

    // ---- an open floor ---------------------------------------------------------------------------------------------

    /** A white floor under a flat ambient light and nothing else: all of it is indirect light, and nothing is in its way. */
    private static Scene3D open(boolean gi) {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setAmbient(Color.WHITE, 0.5f);
        scene.setRayTracedGlobalIllumination(gi);
        scene.add(new Node3D("floor", Mesh.plane(40f, 40f), new Material(Color.WHITE).setRoughness(0.9f)));
        scene.camera().lookAt(new Vec3(0f, 5f, 8f), new Vec3(0f, 0f, 0f)).setFovYDegrees(60);
        return scene;
    }

    private static void openCheck(Scene3D scene, GpuHarness.Recorder r, Pixels px) {
        r.guard("open: the floor is lit by the ambient light", () -> {
            int[] c = px.mean(0f, 0f, 0f);
            // 0.5 of a white surface, with the little the surface gives back as a shine, and the colour encoded for the screen.
            r.check("open: the floor is lit by the ambient light", Math.abs(c[0] - 187) < 14 && Math.abs(c[0] - c[2]) < 8, rgb(c));
        });
    }

    // ---- all together ----------------------------------------------------------------------------------------------

    /** The red wall again, with every other ray traced effect on too. */
    private static Scene3D everything() {
        Scene3D scene = bounce(true);
        scene.lights().get(0).setCastShadow(true);
        scene.setRayTracedShadows(true);
        scene.setRayTracedReflections(true);
        scene.setAmbientOcclusion(1f).setRayTracedAmbientOcclusion(true);
        return scene;
    }
}
