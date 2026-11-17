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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Changing level of detail on a real GPU on both APIs: a dithered change draws both levels in the pixels the other leaves,
 * with no hole, and ends on the new level; a simpler shadow still lands where the full one does.
 */
class LodFadeGpuTest {
    private static final int VIEW = 160;
    private static final Color BACKGROUND = new Color(0.05f, 0.05f, 0.1f, 1f);

    private static Mesh ball() {
        Mesh ball = Mesh.sphere(1f, 64, 32);
        ball.addLod(Mesh.sphere(1f, 16, 8)).addLod(Mesh.sphere(1f, 6, 4));
        return ball;
    }

    /** A white ball, unlit, on a dark sky; as one node, or as one of two instances (the other far off to the side). */
    private static Scene3D fadeScene(boolean instanced) {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(BACKGROUND);
        scene.setSkybox(Skybox.gradient(BACKGROUND, BACKGROUND, BACKGROUND));
        Node3D ball = new Node3D("ball", ball(), new Material(Color.WHITE).setUnlit(true));
        if (instanced) {
            Instances two = new Instances();
            two.add(Vec3.ZERO);
            two.add(new Vec3(400, 0, 0));
            ball.setInstances(two);
        }
        scene.add(ball);
        scene.setLodFadeTime(0.4f);
        scene.camera().lookAt(new Vec3(0, 0, 80), Vec3.ZERO).setFovYDegrees(40).setClip(0.1f, 1000f);
        return scene;
    }

    private interface Body {
        void run(GpuDevice device, RenderTarget target, GpuHarness.Recorder recorder) throws Exception;
    }

    private static void run(RenderAPI api, Scene3D scene, String name, Body body) {
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
            recorder.guard(name, () -> {
                try {
                    body.run(device, target, recorder);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        });
        assumeTrue(run.available() && run.api() == api, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        run.results().forEach((check, failure) -> assertNull(failure, check + ": " + failure));
    }

    /** Pixels of the disc in the middle of the picture: white (level 0), yellow (level 2, in the debug colours), and neither. */
    private static int[] disc(RenderTarget target, float radiusShare) {
        ByteBuffer px = target.readPixels();
        try {
            int w = target.width(), h = target.height();
            float r = radiusShare * Math.min(w, h) / 2f;
            int white = 0, yellow = 0, other = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float dx = x + 0.5f - w / 2f, dy = y + 0.5f - h / 2f;
                    if (dx * dx + dy * dy > r * r) continue;
                    int o = (y * w + x) * 4;
                    int red = px.get(o) & 255, green = px.get(o + 1) & 255, blue = px.get(o + 2) & 255;
                    if (red > 200 && green > 200 && blue > 200) white++;
                    else if (red > 200 && green > 180 && blue < 90) yellow++;
                    else other++;
                }
            }
            return new int[]{white, yellow, other};
        } finally {
            MemoryUtil.memFree(px);
        }
    }

    private void fade(RenderAPI api, boolean instanced) {
        Scene3D scene = fadeScene(instanced);
        run(api, scene, "dithered change of level", (device, target, recorder) -> {
            scene.setLodDebugColors(true);
            Supplier<Scene3D.Stats> frame = () -> {
                scene.render(device, target, 1f / 60f);
                return scene.getStats();
            };
            frame.get();
            Scene3D.Stats far = frame.get();
            recorder.check("far off it draws the simplest level", far.recordsAtLevel(2) == 1 && far.recordsAtLevel(0) == 0,
                    java.util.Arrays.toString(far.levelRecords()));

            // Close up: the full level comes in over the fade time, the simple one going out.
            scene.camera().lookAt(new Vec3(0, 0, 3.2f), Vec3.ZERO);
            Scene3D.Stats starting = frame.get();
            recorder.check("as the change starts both levels are drawn", starting.recordsAtLevel(0) == 1 && starting.recordsAtLevel(2) == 1,
                    java.util.Arrays.toString(starting.levelRecords()));
            Thread.sleep(200);
            frame.get();
            int[] middle = disc(target, 0.7f);
            int total = middle[0] + middle[1] + middle[2];
            recorder.check("half way, the ball is a mix of both levels' pixels", middle[0] > total / 10 && middle[1] > total / 10,
                    "white " + middle[0] + ", yellow " + middle[1] + " of " + total);
            recorder.check("and none of the ball is see-through", middle[2] == 0, middle[2] + " of " + total + " pixels are neither level");
            Thread.sleep(450);
            Scene3D.Stats done = frame.get();
            int[] after = disc(target, 0.7f);
            recorder.check("after the fade only the full level is drawn", done.recordsAtLevel(0) == 1 && done.recordsAtLevel(2) == 0,
                    java.util.Arrays.toString(done.levelRecords()));
            recorder.check("and all of the ball is it", after[1] == 0 && after[2] == 0, "yellow " + after[1] + ", other " + after[2]);

            // Without the fade the level changes at once.
            scene.setLodTransition(Scene3D.LodTransition.NONE);
            scene.camera().lookAt(new Vec3(0, 0, 80), Vec3.ZERO);
            Scene3D.Stats sudden = frame.get();
            recorder.check("with no transition the change is at once", sudden.recordsAtLevel(2) == 1 && sudden.recordsAtLevel(0) == 0,
                    java.util.Arrays.toString(sudden.levelRecords()));
        });
    }

    /** A ball over a floor, its shadow thrown to the side by a slanting light. */
    private static Scene3D shadowScene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(BACKGROUND);
        scene.setSkybox(Skybox.gradient(BACKGROUND, BACKGROUND, BACKGROUND));
        scene.setAmbient(Color.WHITE, 0.25f);
        scene.setLodTransition(Scene3D.LodTransition.NONE);
        scene.lights().add(Light.directional(new Vec3(-1f, -1.4f, -0.3f), Color.WHITE, 1.2f).setCastShadow(true));
        scene.add(new Node3D("floor", Mesh.plane(40, 40), new Material(new Color(0.8f, 0.8f, 0.8f, 1f))));
        scene.add(new Node3D("ball", ball(), new Material(new Color(0.9f, 0.4f, 0.2f, 1f))).setPosition(0, 1.5f, 0));
        scene.camera().lookAt(new Vec3(0, 5.5f, 7), new Vec3(-1, 0.5f, 0)).setFovYDegrees(50).setClip(0.1f, 100f);
        return scene;
    }

    private void shadow(RenderAPI api) {
        Scene3D scene = shadowScene();
        run(api, scene, "simpler shadows", (device, target, recorder) -> {
            Node3D ball = scene.root().find("ball");
            ball.setForcedLod(1);
            scene.setShadowLodBias(0);
            ByteBuffer same = render(scene, device, target);
            Scene3D.Stats sameStats = scene.getStats();
            scene.setShadowLodBias(1);
            ByteBuffer simpler = render(scene, device, target);
            Scene3D.Stats simplerStats = scene.getStats();
            ball.setCastShadow(false);
            ByteBuffer none = render(scene, device, target);
            ball.setCastShadow(true);
            try {
                int n = target.width() * target.height();
                int changed = differing(same, simpler, n), shadowArea = differing(same, none, n);
                recorder.check("the ball throws a shadow to compare", shadowArea > n / 100, shadowArea + " of " + n);
                recorder.check("a simpler shadow lands where the full one does", changed < shadowArea / 4,
                        changed + " pixels changed, against a shadow of " + shadowArea);
                recorder.check("the picture's own level is the same", java.util.Arrays.equals(sameStats.levelRecords(), simplerStats.levelRecords()),
                        java.util.Arrays.toString(sameStats.levelRecords()) + " then " + java.util.Arrays.toString(simplerStats.levelRecords()));
            } finally {
                MemoryUtil.memFree(same);
                MemoryUtil.memFree(simpler);
                MemoryUtil.memFree(none);
            }
        });
    }

    private static ByteBuffer render(Scene3D scene, GpuDevice device, RenderTarget target) {
        scene.render(device, target, 1f / 60f);
        return target.readPixels();
    }

    private static int differing(ByteBuffer a, ByteBuffer b, int pixels) {
        int count = 0;
        for (int p = 0; p < pixels; p++) {
            int worst = 0;
            for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs((a.get(p * 4 + c) & 255) - (b.get(p * 4 + c) & 255)));
            if (worst > 24) count++;
        }
        return count;
    }

    @Test
    void fadeVulkan() {
        fade(RenderAPI.VULKAN, false);
    }

    @Test
    void fadeOpenGl() {
        fade(RenderAPI.OPENGL, false);
    }

    @Test
    void fadeInstancedVulkan() {
        fade(RenderAPI.VULKAN, true);
    }

    @Test
    void fadeInstancedOpenGl() {
        fade(RenderAPI.OPENGL, true);
    }

    @Test
    void shadowVulkan() {
        shadow(RenderAPI.VULKAN);
    }

    @Test
    void shadowOpenGl() {
        shadow(RenderAPI.OPENGL);
    }
}
