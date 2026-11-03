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
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Spot lights with a shaped cone and an edge curve, on a real GPU on both APIs. One spot light shines down on a white floor
 * in the dark. The lit patch must have the shape the CPU says ({@link Light#coneFactor}) whether the pixel tests the light
 * directly or finds it in the light grid, the two paths must draw the same picture, and the edge curve must darken or
 * brighten the ramp it bends. Skips where there is no GPU.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class ConeShapeGpuTest {
    private static final int VIEW = 192;
    private static final Vec3 LAMP = new Vec3(0, 6, 0);

    private static Scene3D scene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(Color.BLACK);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.setAmbient(Color.BLACK, 0f);
        scene.camera().lookAt(new Vec3(0, 18, 8), new Vec3(0, 0, 0)).setFovYDegrees(60).setClip(0.1f, 100f);
        scene.add(new Node3D("floor", Mesh.plane(40, 40), new Material(Color.WHITE)));
        // Not so bright that the middle of the ramp is already white, or a curve could not be seen on it.
        scene.lights().add(Light.spot(LAMP, new Vec3(0, -1, 0), Color.WHITE, 1f, 30f, 25f, 40f));
        return scene;
    }

    private interface Checks {
        void run(Scene3D scene, GpuDevice device, RenderTarget target, GpuHarness.Recorder recorder);
    }

    private void run(RenderAPI api, Checks checks) {
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

    /** Where a point of the world lands in the picture, as an index of its first byte, or -1 if it is out of view. */
    private static int pixel(Scene3D scene, RenderTarget target, Vec3 p) {
        int w = target.width(), h = target.height();
        var camera = scene.camera();
        Vec3 ndc = camera.projection((float) w / h).mul(camera.view()).transformPoint(p);
        if (Math.abs(ndc.x()) > 0.95f || Math.abs(ndc.y()) > 0.95f) return -1;
        int sx = Math.round((ndc.x() * 0.5f + 0.5f) * w), sy = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * h);
        return (Math.clamp(sy, 0, h - 1) * w + Math.clamp(sx, 0, w - 1)) * 4;
    }

    private static int luma(ByteBuffer px, int at) {
        return (px.get(at) & 255) + (px.get(at + 1) & 255) + (px.get(at + 2) & 255);
    }

    /** Draws the scene both ways and holds each picture to the shape the light says it should have. */
    private static void shape(String name, Scene3D scene, GpuDevice device, RenderTarget target, GpuHarness.Recorder recorder) {
        Light light = scene.lights().get(0);
        ByteBuffer direct = draw(scene, Scene3D.LightMode.DIRECT, device, target);
        ByteBuffer grid = draw(scene, Scene3D.LightMode.CLUSTERED, device, target);
        try {
            int centre = luma(direct, pixel(scene, target, new Vec3(0, 0, 0)));
            int inside = 0, outside = 0, wrongInside = 0, wrongOutside = 0;
            for (float x = -9f; x <= 9f; x += 0.5f) {
                for (float z = -9f; z <= 9f; z += 0.5f) {
                    Vec3 p = new Vec3(x, 0, z);
                    int at = pixel(scene, target, p);
                    if (at < 0) continue;
                    // Only where the cone is clear-cut for a quarter of a metre round: the edge rises from nothing, so a
                    // point a pixel from it is already lit.
                    float lowest = 1f, highest = 0f;
                    for (float[] step : new float[][]{{0, 0}, {0.25f, 0}, {-0.25f, 0}, {0, 0.25f}, {0, -0.25f}}) {
                        float e = light.coneFactor(p.add(new Vec3(step[0], 0, step[1])).sub(LAMP).normalized());
                        lowest = Math.min(lowest, e);
                        highest = Math.max(highest, e);
                    }
                    if (lowest >= 0.99f) {
                        inside++;
                        if (luma(direct, at) < centre * 0.5f) wrongInside++;
                        if (luma(grid, at) < centre * 0.5f) wrongInside++;
                    } else if (highest == 0f) {
                        outside++;
                        if (luma(direct, at) > 12) wrongOutside++;
                        if (luma(grid, at) > 12) wrongOutside++;
                    }
                }
            }
            recorder.check(name + ": the centre is lit", centre > 60, "centre " + centre);
            recorder.check(name + ": the patch has points to check", inside > 5 && outside > 100, "inside " + inside + ", outside " + outside);
            recorder.check(name + ": the floor is lit where the cone says (both paths)", wrongInside == 0, wrongInside + " dark points inside of " + inside);
            recorder.check(name + ": and dark where it says (both paths)", wrongOutside == 0, wrongOutside + " lit points outside of " + outside);
            recorder.check(name + ": the grid was used", scene.getStats().clusteredLights() == 1, "clustered " + scene.getStats().clusteredLights());

            long sum = 0;
            int worst = 0, n = target.width() * target.height();
            for (int i = 0; i < n * 4; i++) {
                if (i % 4 == 3) continue;
                int d = Math.abs((direct.get(i) & 255) - (grid.get(i) & 255));
                sum += d;
                worst = Math.max(worst, d);
            }
            recorder.check(name + ": the grid draws the same picture (mean difference)", sum / (n * 3.0) < 0.6, "mean " + sum / (n * 3.0));
            recorder.check(name + ": the grid draws the same picture (worst pixel)", worst <= 10, "worst " + worst);
        } finally {
            MemoryUtil.memFree(direct);
            MemoryUtil.memFree(grid);
        }
    }

    private void shapes(RenderAPI api) {
        run(api, (scene, device, target, recorder) -> recorder.guard("shaped cones", () -> {
            Light light = scene.lights().get(0);
            shape("round", scene, device, target, recorder);
            light.setConeShape(0.4f, 1f, 0f);
            shape("a wide rectangle", scene, device, target, recorder);
            light.setConeShape(0.4f, 0f, 35f);
            shape("an oval turned 35 degrees", scene, device, target, recorder);
            light.setConeShape(2.5f, 0.5f, -70f);
            shape("a tall rounded rectangle turned the other way", scene, device, target, recorder);
        }));
    }

    private void curve(RenderAPI api) {
        run(api, (scene, device, target, recorder) -> recorder.guard("the edge curve", () -> {
            Light light = scene.lights().get(0);
            // A point on the floor half way down the ramp of a straight edge.
            Vec3 ramp = null;
            for (float z = 0f; z < 9f && ramp == null; z += 0.05f) {
                Vec3 p = new Vec3(0, 0, z);
                float s = light.coneFactor(p.sub(LAMP).normalized());
                if (s > 0.45f && s < 0.55f) ramp = p;
            }
            recorder.check("a point on the ramp was found", ramp != null, "none");
            if (ramp == null) return;
            int at = pixel(scene, target, ramp);
            for (Scene3D.LightMode mode : new Scene3D.LightMode[]{Scene3D.LightMode.DIRECT, Scene3D.LightMode.CLUSTERED}) {
                light.setEdgeCurve(1f);
                ByteBuffer one = draw(scene, mode, device, target);
                int straight = luma(one, at);
                MemoryUtil.memFree(one);
                light.setEdgeCurve(3f);
                ByteBuffer three = draw(scene, mode, device, target);
                int tight = luma(three, at);
                MemoryUtil.memFree(three);
                light.setEdgeCurve(0.3f);
                ByteBuffer hardEdge = draw(scene, mode, device, target);
                int hard = luma(hardEdge, at);
                MemoryUtil.memFree(hardEdge);
                recorder.check(mode + ": the ramp is lit", straight > 40, "straight " + straight);
                recorder.check(mode + ": a curve above 1 darkens the ramp", tight < straight * 0.7f, "tight " + tight + ", straight " + straight);
                recorder.check(mode + ": a curve below 1 brightens it", hard > straight * 1.05f, "hard " + hard + ", straight " + straight);
            }
        }));
    }

    @Test
    void shapesVulkan() {
        shapes(RenderAPI.VULKAN);
    }

    @Test
    void shapesOpenGl() {
        shapes(RenderAPI.OPENGL);
    }

    @Test
    void curveVulkan() {
        curve(RenderAPI.VULKAN);
    }

    @Test
    void curveOpenGl() {
        curve(RenderAPI.OPENGL);
    }
}
