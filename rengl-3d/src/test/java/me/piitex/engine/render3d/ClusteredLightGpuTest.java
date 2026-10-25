package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.LightFalloff;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lights sorted into the cluster grid, on a real GPU on both APIs. The same scene is drawn with every pixel testing every
 * light and with the grid, and the two pictures must agree; then a crowd too big for the direct path is drawn, and a shadow
 * is checked to survive the split. Skips where there is no GPU.
 */
class ClusteredLightGpuTest {
    private static final int VIEW = 192;

    private static Scene3D base() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(Color.BLACK);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.setAmbient(Color.BLACK, 0f);
        scene.camera().lookAt(new Vec3(0, 14, 14), new Vec3(0, 0, -2)).setFovYDegrees(60).setClip(0.1f, 100f);
        return scene;
    }

    /** A floor, a classic sphere and a metal sphere, under a spread of lights of every kind. */
    private static Scene3D spread(int pointLights) {
        Scene3D scene = base();
        scene.add(new Node3D("floor", Mesh.plane(40, 40), new Material(new Color(0.8f, 0.8f, 0.8f, 1f)).setPbr(true).setRoughness(0.7f)));
        scene.add(new Node3D("classic", Mesh.sphere(1.5f, 24, 16), new Material(new Color(0.9f, 0.4f, 0.3f, 1f))).setPosition(-4, 1.5f, 0));
        scene.add(new Node3D("metal", Mesh.sphere(1.5f, 24, 16), new Material(new Color(0.9f, 0.9f, 1f, 1f)).setPbr(true).setMetallic(1f).setRoughness(0.3f)).setPosition(4, 1.5f, -2));
        Random random = new Random(5);
        for (int i = 0; i < pointLights; i++) {
            Color color = new Color(0.4f + random.nextFloat() * 0.6f, 0.4f + random.nextFloat() * 0.6f, 0.4f + random.nextFloat() * 0.6f, 1f);
            Vec3 at = new Vec3((random.nextFloat() - 0.5f) * 24f, 1f + random.nextFloat() * 3f, (random.nextFloat() - 0.5f) * 24f);
            Light light = Light.point(at, color, 1f + random.nextFloat() * 2f, 3f + random.nextFloat() * 5f);
            if (i % 5 == 1) light.setFalloff(LightFalloff.LINEAR);
            scene.lights().add(light);
        }
        for (int i = 0; i < 6; i++) {
            scene.lights().add(Light.spot(new Vec3(-10 + i * 4, 6, -4), new Vec3(0.2f * i - 0.5f, -1, 0.3f), Color.WHITE, 4f, 12f, 12f, 28f));
        }
        return scene;
    }

    /** A grid of small lights, far more than a direct frame holds, each lighting only the floor under it. */
    private static Scene3D crowd(int columns, int rows) {
        Scene3D scene = base();
        scene.add(new Node3D("floor", Mesh.plane(60, 60), new Material(Color.WHITE)));
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                scene.lights().add(Light.point(new Vec3((c - (columns - 1) / 2f) * 2.5f, 1f, -r * 2.5f + 6f), Color.WHITE, 2f, 1.8f));
            }
        }
        return scene;
    }

    /** A white floor in the dark with a glowing cube on it and nothing else to light it. */
    private static Scene3D glowing(float range) {
        Scene3D scene = base();
        scene.add(new Node3D("floor", Mesh.plane(40, 40), new Material(Color.WHITE)));
        scene.add(new Node3D("lamp", Mesh.cube(1f), new Material(new Color(1f, 0.6f, 0.2f, 1f)).setEmissive(new Color(1f, 0.6f, 0.2f, 1f), 3f).setGlow(range)).setPosition(0, 1.5f, 0));
        return scene;
    }

    /** A spot light casting a shadow, with a crowd of faint local lights far from where the shadow falls. */
    private static Scene3D shadowed() {
        Scene3D scene = base();
        scene.add(new Node3D("floor", Mesh.plane(60, 60), new Material(Color.WHITE)));
        scene.add(new Node3D("box", Mesh.cube(2f), new Material(Color.WHITE)).setPosition(0, 1f, 0));
        scene.lights().add(Light.spot(new Vec3(-6, 6, 0), new Vec3(1, -1, 0), Color.WHITE, 3f, 24f, 40f, 60f).setCastShadow(true));
        for (int i = 0; i < 80; i++) {
            scene.lights().add(Light.point(new Vec3(-26 + (i % 40) * 1.3f, 1f, -12 - (i / 40) * 3f), new Color(1f, 0.2f, 0.2f, 1f), 0.5f, 1f));
        }
        return scene;
    }

    private interface Checks {
        void run(Scene3D scene, GpuDevice device, RenderTarget target, GpuHarness.Recorder recorder);
    }

    private void run(RenderAPI api, Scene3D scene, Checks checks) {
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
            checks.run(scene, device, target, recorder);
        });
        assumeTrue(run.available() && run.api() == api, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        run.results().forEach((name, failure) -> assertNull(failure, name + ": " + failure));
    }

    private static ByteBuffer draw(Scene3D scene, Scene3D.LightMode mode, GpuDevice device, RenderTarget target) {
        scene.setLightMode(mode);
        scene.render(device, target, 1f / 60f);
        return target.readPixels();
    }

    private static boolean visible(Scene3D scene, RenderTarget target, float x, float y, float z) {
        int w = target.width(), h = target.height();
        var camera = scene.camera();
        Vec3 ndc = camera.projection((float) w / h).mul(camera.view()).transformPoint(new Vec3(x, y, z));
        return Math.abs(ndc.x()) < 0.95f && Math.abs(ndc.y()) < 0.95f;
    }

    private static int luma(ByteBuffer px, RenderTarget target, Scene3D scene, float x, float y, float z) {
        int w = target.width(), h = target.height();
        var camera = scene.camera();
        Vec3 ndc = camera.projection((float) w / h).mul(camera.view()).transformPoint(new Vec3(x, y, z));
        int sx = Math.round((ndc.x() * 0.5f + 0.5f) * w), sy = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * h);
        int i = (Math.clamp(sy, 0, h - 1) * w + Math.clamp(sx, 0, w - 1)) * 4;
        return (px.get(i) & 255) + (px.get(i + 1) & 255) + (px.get(i + 2) & 255);
    }

    private void agreement(RenderAPI api) {
        Scene3D scene = spread(48);
        run(api, scene, (s, device, target, recorder) -> {
            recorder.guard("the grid and the direct path agree", () -> {
                    ByteBuffer direct = draw(s, Scene3D.LightMode.DIRECT, device, target);
                long sum = 0;
                int worst = 0, n = target.width() * target.height();
                ByteBuffer grid = draw(s, Scene3D.LightMode.CLUSTERED, device, target);
                try {
                    long lit = 0;
                    for (int i = 0; i < n * 4; i++) {
                        if (i % 4 == 3) continue;
                        int d = Math.abs((direct.get(i) & 255) - (grid.get(i) & 255));
                        sum += d;
                        worst = Math.max(worst, d);
                        lit += grid.get(i) & 255;
                    }
                    double mean = sum / (n * 3.0);
                    recorder.check("the picture is lit", lit / (n * 3.0) > 15, "mean " + lit / (n * 3.0));
                    recorder.check("the grid draws the same picture (mean difference)", mean < 0.6, "mean " + mean);
                    recorder.check("the grid draws the same picture (worst pixel)", worst <= 10, "worst " + worst);
                    recorder.check("the grid was used", s.getStats().clusteredLights() == 48 + 6, "clustered " + s.getStats().clusteredLights());
                } finally {
                    MemoryUtil.memFree(direct);
                    MemoryUtil.memFree(grid);
                }
            });
        });
    }

    private void bigCrowd(RenderAPI api) {
        Scene3D scene = crowd(30, 16); // 480 lights
        run(api, scene, (s, device, target, recorder) -> recorder.guard("a crowd of lights", () -> {
            ByteBuffer direct = draw(s, Scene3D.LightMode.DIRECT, device, target);
            ByteBuffer auto = draw(s, Scene3D.LightMode.AUTO, device, target);
            try {
                recorder.check("a scene over the direct limit goes through the grid by itself", s.getStats().clusteredLights() > 64, "clustered " + s.getStats().clusteredLights());
                // Under each light's own patch of floor: lights the direct path dropped leave it black, the grid lights it.
                int dropped = 0, bothLit = 0, gridLit = 0, onScreen = 0;
                for (Light light : s.lights()) {
                    Vec3 p = light.getVector();
                    if (!visible(s, target, p.x(), 0f, p.z())) continue;
                    onScreen++;
                    int a = luma(auto, target, s, p.x(), 0f, p.z()), d = luma(direct, target, s, p.x(), 0f, p.z());
                    if (a > 30) gridLit++;
                    if (a > 30 && d > 30) bothLit++;
                    if (a > 30 && d < 10) dropped++;
                }
                recorder.check("most lights in view light their patch of floor with the grid", gridLit > onScreen * 0.8, "lit " + gridLit + " of " + onScreen);
                recorder.check("the direct path left many of them dark", dropped > 20 && bothLit < gridLit, "dark in direct " + dropped + ", lit in both " + bothLit);
            } finally {
                MemoryUtil.memFree(direct);
                MemoryUtil.memFree(auto);
            }
        }));
    }

    private void shadowSurvives(RenderAPI api) {
        Scene3D scene = shadowed();
        run(api, scene, (s, device, target, recorder) -> recorder.guard("shadows with the grid", () -> {
            ByteBuffer grid = draw(s, Scene3D.LightMode.CLUSTERED, device, target);
            try {
                int shaded = luma(grid, target, s, 3f, 0f, 0f), lit = luma(grid, target, s, 3f, 0f, 4.5f);
                recorder.check("the box still shadows the floor beside it", shaded < lit / 2 && lit > 60, "shaded " + shaded + ", lit " + lit);
                recorder.check("the faint lights in view were sorted into the grid", s.getStats().clusteredLights() > 20 && s.getStats().clusteredLights() <= 80, "clustered " + s.getStats().clusteredLights());
            } finally {
                MemoryUtil.memFree(grid);
            }
        }));
    }

    private void glow(RenderAPI api) {
        Scene3D lit = glowing(8f);
        run(api, lit, (s, device, target, recorder) -> recorder.guard("a glowing surface lights its surroundings", () -> {
            ByteBuffer on = draw(s, Scene3D.LightMode.AUTO, device, target);
            int near = luma(on, target, s, 2.5f, 0f, 0f), far = luma(on, target, s, 11f, 0f, 0f);
            int glowing = s.getStats().glowLights();
            MemoryUtil.memFree(on);
            s.root().getChildren().get(1).getMaterial().setGlow(0f);
            ByteBuffer off = draw(s, Scene3D.LightMode.AUTO, device, target);
            int offNear = luma(off, target, s, 2.5f, 0f, 0f);
            MemoryUtil.memFree(off);
            recorder.check("one glow light was made", glowing == 1, "glow lights " + glowing);
            recorder.check("the floor beside the lamp is lit by it", near > 60, "near " + near);
            recorder.check("and beyond its range it is dark", far < 10, "far " + far);
            recorder.check("with the glow off the floor is dark again", offNear < 10, "off " + offNear);
        }));
    }

    @Test
    void glowVulkan() {
        glow(RenderAPI.VULKAN);
    }

    @Test
    void glowOpenGl() {
        glow(RenderAPI.OPENGL);
    }

    @Test
    void agreementVulkan() {
        agreement(RenderAPI.VULKAN);
    }

    @Test
    void agreementOpenGl() {
        agreement(RenderAPI.OPENGL);
    }

    @Test
    void crowdVulkan() {
        bigCrowd(RenderAPI.VULKAN);
    }

    @Test
    void crowdOpenGl() {
        bigCrowd(RenderAPI.OPENGL);
    }

    @Test
    void shadowVulkan() {
        shadowSurvives(RenderAPI.VULKAN);
    }

    @Test
    void shadowOpenGl() {
        shadowSurvives(RenderAPI.OPENGL);
    }
}
