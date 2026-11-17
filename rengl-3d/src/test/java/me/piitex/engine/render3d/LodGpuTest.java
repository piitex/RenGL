package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Instances;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Levels of detail on a real GPU on both APIs: a row of spheres running away from the camera draws simpler spheres further
 * off, with far fewer triangles and a picture that hardly changes; a forced level, levels turned off, the debug colours and
 * the smallest size do what they say.
 */
class LodGpuTest {
    private static final int VIEW = 192;
    /** Triangles of the three spheres: 32 rings of 64, 8 of 16, 4 of 6, two triangles each. */
    private static final int FULL = 32 * 64 * 2, MEDIUM = 8 * 16 * 2, LOW = 4 * 6 * 2;
    private static final int ROW = 60;

    private static Scene3D scene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        Color sky = new Color(0.1f, 0.1f, 0.15f, 1f);
        scene.setBackground(sky);
        scene.setSkybox(Skybox.gradient(sky, sky, sky));
        scene.setAmbient(Color.WHITE, 0.3f);
        // Levels change at once here, so every frame shows exactly the levels picked; the fade has a test of its own.
        scene.setLodTransition(Scene3D.LodTransition.NONE);
        scene.lights().add(Light.directional(new Vec3(-1, -1.2f, -0.4f), Color.WHITE, 1.2f).setCastShadow(true));
        scene.add(new Node3D("floor", Mesh.plane(80, 200), new Material(new Color(0.8f, 0.8f, 0.8f, 1f))).setPosition(0, 0, -60));

        // The chord of a circle cut into n sides is r (1 - cos(pi / n)) inside it: that is each level's error.
        Mesh ball = Mesh.sphere(0.5f, 64, 32);
        ball.addLod(Mesh.sphere(0.5f, 16, 8), (float) (0.5 * (1 - Math.cos(Math.PI / 8))))
                .addLod(Mesh.sphere(0.5f, 6, 4), (float) (0.5 * (1 - Math.cos(Math.PI / 4))));
        Instances row = new Instances();
        for (int i = 0; i < ROW; i++) row.add(new Vec3((i % 2) * 2f - 1f, 0.5f, 4f - i * 1.5f));
        scene.add(new Node3D("balls", ball, new Material(new Color(0.9f, 0.5f, 0.3f, 1f))).setInstances(row));
        scene.add(new Node3D("near", ball, new Material(new Color(0.3f, 0.6f, 0.9f, 1f))).setPosition(0, 1.6f, 6.5f));
        scene.camera().lookAt(new Vec3(0, 2, 9), new Vec3(0, 0.5f, -30)).setFovYDegrees(60).setClip(0.1f, 150f);
        return scene;
    }

    private void run(RenderAPI api) {
        Scene3D scene = scene();
        GpuHarness.Run run = GpuHarness.run(api, VIEW, (Consumer<Viewport3D>) viewport -> viewport.setContent(new Viewport3D.Content() {
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
        }), (device, target, recorder) -> {
            if (device.api() != api) return;
            recorder.guard("levels of detail", () -> {
                scene.setLodEnabled(false);
                scene.render(device, target, 1f / 60f);
                ByteBuffer off = target.readPixels();
                Scene3D.Stats statsOff = scene.getStats();
                scene.setLodEnabled(true);
                scene.render(device, target, 1f / 60f);
                ByteBuffer on = target.readPixels();
                Scene3D.Stats statsOn = scene.getStats();
                try {
                    int n = target.width() * target.height(), changed = 0;
                    for (int p = 0; p < n; p++) {
                        int worst = 0;
                        for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs((off.get(p * 4 + c) & 255) - (on.get(p * 4 + c) & 255)));
                        if (worst > 40) changed++;
                    }
                    recorder.check("off, everything draws the full sphere", statsOff.recordsAtLevel(0) == statsOff.instances()
                            && statsOff.recordsAtLevel(1) == 0, "levels " + java.util.Arrays.toString(statsOff.levelRecords()));
                    recorder.check("on, the further spheres draw both simpler levels", statsOn.recordsAtLevel(1) > 0 && statsOn.recordsAtLevel(2) > 0,
                            "levels " + java.util.Arrays.toString(statsOn.levelRecords()));
                    recorder.check("the sphere beside the camera stays full", statsOn.recordsAtLevel(0) >= 1, "levels " + java.util.Arrays.toString(statsOn.levelRecords()));
                    recorder.check("as many things are drawn either way", statsOn.instances() == statsOff.instances(),
                            statsOn.instances() + " against " + statsOff.instances());
                    recorder.check("with far fewer triangles", statsOn.triangles() * 4 < statsOff.triangles(),
                            statsOn.triangles() + " against " + statsOff.triangles());
                    recorder.check("and hardly a pixel changes", changed < n / 100, changed + " of " + n + " pixels changed");
                } finally {
                    MemoryUtil.memFree(off);
                    MemoryUtil.memFree(on);
                }

                Node3D balls = scene.root().find("balls"), near = scene.root().find("near");
                // A forced level is drawn whatever the distance, even with levels turned off.
                scene.setLodEnabled(false);
                balls.setForcedLod(2);
                near.setForcedLod(9);
                scene.render(device, target, 1f / 60f);
                Scene3D.Stats forced = scene.getStats();
                long floorTriangles = 2;
                recorder.check("a forced level is drawn by every instance", forced.recordsAtLevel(2) == forced.instances() - 1,
                        "levels " + java.util.Arrays.toString(forced.levelRecords()));
                recorder.check("and its triangles are the simplest sphere's", forced.triangles() == floorTriangles + (long) LOW * (forced.instances() - 1),
                        forced.triangles() + " triangles for " + forced.instances() + " records");
                balls.setForcedLod(-1);
                near.setForcedLod(-1);
                scene.setLodEnabled(true);

                // A higher bias takes simpler levels sooner.
                scene.render(device, target, 1f / 60f);
                long before = scene.getStats().triangles();
                scene.setLodBias(2f);
                scene.render(device, target, 1f / 60f);
                long biased = scene.getStats().triangles();
                scene.setLodBias(0f);
                recorder.check("a bias draws fewer triangles", biased < before, biased + " against " + before);

                // The debug colours repaint the spheres by level.
                scene.render(device, target, 1f / 60f);
                ByteBuffer plain = target.readPixels();
                scene.setLodDebugColors(true);
                scene.render(device, target, 1f / 60f);
                ByteBuffer tinted = target.readPixels();
                scene.setLodDebugColors(false);
                try {
                    int n = target.width() * target.height(), greener = 0;
                    for (int p = 0; p < n; p++) {
                        int g0 = plain.get(p * 4 + 1) & 255, r0 = plain.get(p * 4) & 255;
                        int g1 = tinted.get(p * 4 + 1) & 255, r1 = tinted.get(p * 4) & 255;
                        // The orange spheres of level 1 turn green, and those of level 2 yellow: green rises against red.
                        if (g1 - r1 > g0 - r0 + 30) greener++;
                    }
                    recorder.check("the debug colours show on the simpler spheres", greener > 30, greener + " pixels turned greener");
                } finally {
                    MemoryUtil.memFree(plain);
                    MemoryUtil.memFree(tinted);
                }

                // The smallest size leaves out the far spheres, shadows and all.
                scene.render(device, target, 1f / 60f);
                Scene3D.Stats all = scene.getStats();
                balls.setCullScreenSize(0.05f);
                scene.render(device, target, 1f / 60f);
                Scene3D.Stats small = scene.getStats();
                balls.setCullScreenSize(0f);
                recorder.check("the smallest size leaves the far spheres out", small.instances() < all.instances() - 10 && small.instances() > 2,
                        small.instances() + " against " + all.instances());

                // A level picked once stays the same frame after frame with nothing moving.
                scene.render(device, target, 1f / 60f);
                int[] first = scene.getStats().levelRecords();
                scene.render(device, target, 1f / 60f);
                int[] second = scene.getStats().levelRecords();
                recorder.check("the levels are steady", java.util.Arrays.equals(first, second),
                        java.util.Arrays.toString(first) + " then " + java.util.Arrays.toString(second));
            });
        });
        assumeTrue(run.available() && run.api() == api, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        run.results().forEach((name, failure) -> assertNull(failure, name + ": " + failure));
    }

    @Test
    void vulkan() {
        run(RenderAPI.VULKAN);
    }

    @Test
    void openGl() {
        run(RenderAPI.OPENGL);
    }
}
