package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.ClusterProbe;
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
 * Levels of detail for rays on a real GPU: {@link Scene3D#setRayTracingLodBias} makes traced shadows come from a simpler
 * level than the picture draws, and a mesh drawn from clusters with no levels of its own is traced as an even cut of its
 * clusters, coarser for a bias, and as the whole mesh where the scene's virtual geometry is off.
 */
class RayLodGpuTest {
    private static final int VIEW = 256;

    private static Scene3D sun() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setAmbient(Color.WHITE, 0.03f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.setRayTracedShadows(true);
        // From the left, so a shadow falls to the right of what casts it.
        scene.lights().add(Light.directional(new Vec3(1, -1, 0), Color.WHITE, 1.0f).setCastShadow(true));
        scene.add(new Node3D("floor", Mesh.plane(40f, 40f), new Material(Color.WHITE).setRoughness(0.9f)));
        scene.camera().lookAt(new Vec3(0, 12f, 14f), new Vec3(0, 0, 0)).setFovYDegrees(75);
        return scene;
    }

    /** The summed colour of the pixel a point of the scene lands on. */
    private static int luma(Scene3D scene, RenderTarget target, ByteBuffer data, float x, float y, float z) {
        int w = target.width(), h = target.height();
        var camera = scene.camera();
        Vec3 ndc = camera.projection((float) w / h).mul(camera.view()).transformPoint(new Vec3(x, y, z));
        int sx = Math.round((ndc.x() * 0.5f + 0.5f) * w), sy = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * h);
        int i = (sy * w + sx) * 4;
        return (data.get(i) & 255) + (data.get(i + 1) & 255) + (data.get(i + 2) & 255);
    }

    private interface Check {
        void run(GpuDevice device, RenderTarget target, ByteBuffer pixels, GpuHarness.Recorder r);
    }

    /** Draws the scene for some frames with shadows traced and runs the check on the last; skips without ray queries. */
    private static void run(Scene3D scene, boolean needsClusters, Check check) {
        boolean[] ran = {false};
        GpuHarness.Run run = GpuHarness.run(RenderAPI.VULKAN, VIEW, (Consumer<Viewport3D>) viewport -> viewport.setContent(new Viewport3D.Content() {
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
            if (device.api() != RenderAPI.VULKAN || !device.supportsRayTracing()) return;
            if (needsClusters && !device.supportsVirtualGeometry()) return;
            ran[0] = true;
            recorder.check("the shadows are traced", scene.isUsingRayTracedShadows(), "they are not");
            ByteBuffer pixels = target.readPixels();
            try {
                recorder.guard("checks", () -> check.run(device, target, pixels, recorder));
            } finally {
                MemoryUtil.memFree(pixels);
            }
        });
        assumeTrue(run.available() && run.api() == RenderAPI.VULKAN, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        assumeTrue(ran[0], "no ray queries" + (needsClusters ? " or no virtual geometry" : "") + " on this GPU");
        StringBuilder failures = new StringBuilder();
        run.results().forEach((name, failure) -> {
            if (failure != null) failures.append(System.lineSeparator()).append(name).append(": ").append(failure);
        });
        assertNull(failures.length() == 0 ? null : failures.toString(), "failed checks");
    }

    /**
     * A pillar whose only simpler level is a speck, with an error so big the camera never picks it: rays hit the pillar
     * itself, and with a bias of 1 the speck, so its traced shadow goes while the picture still shows the pillar.
     */
    private static void pillar(int rayBias, boolean shadowExpected) {
        Scene3D scene = sun().setRayTracingLodBias(rayBias);
        Mesh mesh = Mesh.cube(1f).addLod(Mesh.cube(0.02f), 1000f);
        Node3D pillar = new Node3D("pillar", mesh, new Material(Color.WHITE).setRoughness(0.9f)).setPosition(-6f, 1f, 0f);
        pillar.setScale(new Vec3(1f, 2f, 1f));
        scene.add(pillar);
        run(scene, false, (device, target, px, r) -> {
            int shadowed = luma(scene, target, px, -4f, 0f, 0f), lit = luma(scene, target, px, -1.5f, 0f, 0f);
            r.check("the floor beside the pillar is lit", lit > 60, "lit " + lit);
            if (shadowExpected) {
                r.check("bias " + rayBias + ": the pillar throws its shadow", shadowed < lit * 0.5, "shadowed " + shadowed + " lit " + lit);
            } else {
                r.check("bias " + rayBias + ": rays hit the speck, so no shadow", Math.abs(shadowed - lit) < Math.max(20, lit * 0.15), "shadowed " + shadowed + " lit " + lit);
            }
            r.check("the camera still draws the pillar itself", scene.getStats().recordsAtLevel(1) == 0, "levels " + java.util.Arrays.toString(scene.getStats().levelRecords()));
        });
    }

    @Test
    void raysHitTheLevelThePictureDraws() {
        pillar(0, true);
    }

    @Test
    void aRayBiasHitsASimplerLevel() {
        pillar(1, false);
    }

    /**
     * A ball drawn from clusters, with no levels: its shadow lands, rays hit an even cut of its clusters (coarser with a bias),
     * and with the scene's virtual geometry off they hit the whole ball.
     */
    private static int clusterBall(boolean virtualGeometry, int rayBias) {
        Scene3D scene = sun().setRayTracingLodBias(rayBias);
        scene.setVirtualGeometry(virtualGeometry);
        Mesh ball = ClusterProbe.ball(1f).buildClusters();
        scene.add(new Node3D("ball", ball, new Material(Color.WHITE).setRoughness(0.9f)).setPosition(-6f, 1.2f, 0f));
        run(scene, true, (device, target, px, r) -> {
            int shadowed = luma(scene, target, px, -4.8f, 0f, 0f), lit = luma(scene, target, px, -1.5f, 0f, 0f);
            r.check("the ball throws its shadow", shadowed < lit * 0.5 && lit > 60, "shadowed " + shadowed + " lit " + lit);
            r.check("drawn from clusters as asked", scene.isUsingVirtualGeometry() == virtualGeometry, "using virtual geometry: " + scene.isUsingVirtualGeometry());
        });
        return ClusterProbe.coarsestRayCut(ball);
    }

    @Test
    void aClusteredMeshIsTracedAsAnEvenCut() {
        int plain = clusterBall(true, 0);
        int biased = clusterBall(true, 6);
        int whole = clusterBall(false, 6);
        int cuts = ClusterProbe.rayCuts(ClusterProbe.ball(1f).buildClusters());
        assertNull(whole == 0 ? null : "cut " + whole, "with virtual geometry off rays hit the whole mesh");
        assertNull(biased > plain ? null : "cut " + biased + " with a bias of 6, " + plain + " without, of " + cuts, "a bias makes the cut rays hit coarser");
    }
}
