package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
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
 * Line meshes on a real GPU: a segment draws as a thin line in its colour, and nothing round it.
 */
class LineGpuTest {
    private static Scene3D scene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(Color.BLACK);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        // A horizontal red line at height 0 from x = -2 to 2, and a vertical green one at x = 0 from y = -2 to 2.
        scene.add(new Node3D("red", Mesh.lines(new float[]{-2, 0, 0, 2, 0, 0}, new int[]{0, 1}), new Material(new Color(1f, 0f, 0f, 1f)).setUnlit(true)));
        scene.add(new Node3D("green", Mesh.lines(new float[]{0, -2, 0, 0, 2, 0}, new int[]{0, 1}), new Material(new Color(0f, 1f, 0f, 1f)).setUnlit(true)));
        scene.camera().lookAt(new Vec3(0, 0, 6), new Vec3(0, 0, 0)).setFovYDegrees(60);
        return scene;
    }

    private void run(RenderAPI api) {
        Scene3D scene = scene();
        String[] problem = {null};
        GpuHarness.Run run = GpuHarness.run(api, 128, (Consumer<Viewport3D>) viewport -> viewport.setContent(new Viewport3D.Content() {
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
            ByteBuffer px = target.readPixels();
            try {
                int w = target.width(), h = target.height();
                var camera = scene.camera();
                var viewProj = camera.projection((float) w / h).mul(camera.view());
                java.util.function.BiFunction<Float, Float, int[]> at = (x, y) -> {
                    Vec3 ndc = viewProj.transformPoint(new Vec3(x, y, 0));
                    int sx = Math.round((ndc.x() * 0.5f + 0.5f) * w), sy = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * h);
                    // The line is a pixel wide: take the brightest pixel in a 3 by 3 block.
                    int[] best = {0, 0, 0};
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int i = (Math.clamp(sy + dy, 0, h - 1) * w + Math.clamp(sx + dx, 0, w - 1)) * 4;
                            int[] c = {px.get(i) & 255, px.get(i + 1) & 255, px.get(i + 2) & 255};
                            if (c[0] + c[1] + c[2] > best[0] + best[1] + best[2]) best = c;
                        }
                    }
                    return best;
                };
                int[] red = at.apply(1f, 0f), green = at.apply(0f, 1.2f), empty = at.apply(1f, 1f);
                recorder.check("a line draws in its colour (red)", red[0] > 90 && red[1] < 60 && red[2] < 60, java.util.Arrays.toString(red));
                recorder.check("a line draws in its colour (green)", green[1] > 90 && green[0] < 60 && green[2] < 60, java.util.Arrays.toString(green));
                recorder.check("and nothing is drawn where there is no line", empty[0] + empty[1] + empty[2] < 40, java.util.Arrays.toString(empty));
            } finally {
                MemoryUtil.memFree(px);
            }
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
