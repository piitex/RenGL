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
 * Occlusion culling on a real GPU on both APIs: what hides behind a marked wall is not drawn, the picture is the same with it
 * on and off (shadows from hidden boxes included), and moving the camera brings the hidden things back.
 */
class OcclusionGpuTest {
    private static final int VIEW = 192;

    private static Scene3D scene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(new Color(0.1f, 0.1f, 0.15f, 1f));
        scene.setSkybox(Skybox.gradient(new Color(0.1f, 0.1f, 0.15f, 1f), new Color(0.1f, 0.1f, 0.15f, 1f), new Color(0.1f, 0.1f, 0.15f, 1f)));
        scene.setAmbient(Color.WHITE, 0.2f);
        scene.lights().add(Light.directional(new Vec3(-1, -1.2f, -0.4f), Color.WHITE, 1.2f).setCastShadow(true));
        scene.add(new Node3D("floor", Mesh.plane(60, 60), new Material(new Color(0.8f, 0.8f, 0.8f, 1f))));
        scene.add(new Node3D("wall", Mesh.cube(1f), new Material(new Color(0.6f, 0.4f, 0.4f, 1f))).setScale(new Vec3(14, 6, 0.6f)).setPosition(0, 3, 0).setOccluder(true));
        Instances crates = new Instances();
        for (int i = 0; i < 24; i++) crates.add(new Vec3(-5 + (i % 6) * 2f, 0.75f, -4 - (i / 6) * 3f));
        scene.add(new Node3D("crates", Mesh.cube(1.5f), new Material(new Color(0.3f, 0.5f, 0.8f, 1f))).setInstances(crates));
        scene.add(new Node3D("lone", Mesh.cube(1.5f), new Material(new Color(0.9f, 0.8f, 0.2f, 1f))).setPosition(0, 0.75f, -9));
        scene.add(new Node3D("outside", Mesh.cube(1.5f), new Material(new Color(0.2f, 0.9f, 0.3f, 1f))).setPosition(10, 0.75f, -6));
        scene.camera().lookAt(new Vec3(0, 3, 12), new Vec3(0, 2, 0)).setFovYDegrees(60).setClip(0.1f, 100f);
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
            recorder.guard("occlusion culling", () -> {
                scene.setOcclusionCulling(false);
                scene.render(device, target, 1f / 60f);
                ByteBuffer off = target.readPixels();
                int drawnOff = scene.getStats().instances(), occludedOff = scene.getStats().occluded();
                scene.setOcclusionCulling(true);
                scene.render(device, target, 1f / 60f);
                ByteBuffer on = target.readPixels();
                int drawnOn = scene.getStats().instances(), occludedOn = scene.getStats().occluded();
                try {
                    int worst = 0, n = target.width() * target.height() * 4;
                    for (int i = 0; i < n; i++) worst = Math.max(worst, Math.abs((off.get(i) & 255) - (on.get(i) & 255)));
                    recorder.check("nothing is counted as hidden while it is off", occludedOff == 0, "occluded " + occludedOff);
                    recorder.check("the crates and the box behind the wall are left out", occludedOn >= 20, "occluded " + occludedOn);
                    recorder.check("so fewer things are drawn", drawnOn < drawnOff - 15, "drawn " + drawnOn + " against " + drawnOff);
                    recorder.check("and the picture is exactly the same, shadows included", worst == 0, "worst difference " + worst);
                } finally {
                    MemoryUtil.memFree(off);
                    MemoryUtil.memFree(on);
                }
                // From the side the wall hides much less.
                scene.camera().lookAt(new Vec3(16, 3, -2), new Vec3(0, 1, -6));
                scene.render(device, target, 1f / 60f);
                int occludedSide = scene.getStats().occluded();
                recorder.check("from the side the crates show again", occludedSide < occludedOn / 2, "occluded " + occludedSide + " against " + occludedOn);
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
