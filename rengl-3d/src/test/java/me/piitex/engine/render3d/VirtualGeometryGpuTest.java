package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.ClusterProbe;
import me.piitex.engine.render3d.scene.Instances;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.LodSettings;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.render3d.upscale.Upscalers;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Virtual geometry on a real GPU: the clusters the compute shader picks are the CPU's choice, the picture hardly changes from
 * the full mesh while far fewer triangles are drawn, a near mesh that mixes levels has no hole, shadows and the upscaler's
 * motion pass draw from the lists too, and OpenGL (which can not) draws the plain meshes.
 */
class VirtualGeometryGpuTest {
    private static final int VIEW = 192;
    private static final int ROW = 10;
    private static final Color SKY = new Color(0.1f, 0.1f, 0.15f, 1f);
    private static final Vec3 EYE = new Vec3(0, 1.5f, 6f);
    private static final Mesh BALL = ClusterProbe.ball(0.5f).buildClusters();

    private static Scene3D scene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(SKY);
        scene.setSkybox(Skybox.gradient(SKY, SKY, SKY));
        scene.setAmbient(Color.WHITE, 0.3f);
        scene.lights().add(Light.directional(new Vec3(-1, -1.2f, -0.4f), Color.WHITE, 1.2f).setCastShadow(true));
        scene.add(new Node3D("floor", Mesh.plane(80, 200), new Material(new Color(0.8f, 0.8f, 0.8f, 1f))).setPosition(0, 0, -60));
        Instances row = new Instances();
        for (int i = 0; i < ROW; i++) row.add(position(i));
        scene.add(new Node3D("balls", BALL, new Material(new Color(0.9f, 0.5f, 0.3f, 1f))).setInstances(row));
        Node3D big = new Node3D("big", BALL, new Material(new Color(0.3f, 0.6f, 0.9f, 1f))).setPosition(0, 5, -4);
        big.setScale(8f);
        big.setVisible(false);
        scene.add(big);
        scene.camera().lookAt(EYE, new Vec3(0, 0.6f, -30)).setFovYDegrees(60).setClip(0.1f, 150f);
        return scene;
    }

    private static Vec3 position(int i) {
        return new Vec3((i % 2) * 1.6f - 0.8f, 0.6f, 2f - i * 4f);
    }

    private static float pixelsPerUnit() {
        return VIEW * 0.5f / (float) Math.tan(Math.toRadians(30));
    }

    /**
     * Draws a few times and returns the last picture. The GPU's count of the triangles it drew comes back late: the stats of a
     * render hold what the GPU counted for the one before, once it finished (which reading the picture waits for).
     */
    private static ByteBuffer settle(Scene3D scene, GpuDevice device, RenderTarget target) {
        for (int i = 0; i < 3; i++) scene.render(device, target, 1f / 60f);
        MemoryUtil.memFree(target.readPixels());
        scene.render(device, target, 1f / 60f);
        return target.readPixels();
    }

    private static boolean isSky(ByteBuffer b, int p) {
        return Math.abs((b.get(p * 4) & 255) - 26) <= 4 && Math.abs((b.get(p * 4 + 1) & 255) - 26) <= 4 && Math.abs((b.get(p * 4 + 2) & 255) - 38) <= 4;
    }

    /**
     * Draws the scene as virtual geometry for some frames, then runs the checks: the stats of those frames are the GPU's own
     * count (which comes back a few frames late), the rest is drawn and read within the frame the checks run in.
     *
     * @return the triangles the frames before the checks drew, or -1 where nothing was drawn from clusters
     */
    private long run(RenderAPI api, float bias, boolean pictures) {
        Scene3D scene = scene();
        scene.setVirtualGeometry(true).setLodBias(bias);
        long[] framed = {-1};
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
            recorder.guard("virtual geometry", () -> {
                long ballTriangles = BALL.indexCount() / 3;
                if (device.supportsVirtualGeometry()) {
                    recorder.check("on, the frames say they drew clusters", scene.isUsingVirtualGeometry(), "they say they did not");
                    long expected = 2;
                    for (int i = 0; i < ROW; i++) {
                        expected += ClusterProbe.cutTriangles(BALL, Mat4.translation(position(i)).toArray(),
                                new float[]{EYE.x(), EYE.y(), EYE.z()}, pixelsPerUnit(), (float) Math.pow(2, bias));
                    }
                    long drawn = scene.getStats().triangles();
                    framed[0] = drawn;
                    recorder.check("the GPU picks the clusters the CPU does", Math.abs(drawn - expected) <= expected / 50,
                            drawn + " triangles drawn, the CPU's choice has " + expected);
                    recorder.check("with far fewer triangles than the full meshes", drawn * 3 < 2 + ROW * ballTriangles,
                            drawn + " against " + (2 + ROW * ballTriangles));
                }
                if (!pictures) return;
                scene.setVirtualGeometry(false);
                ByteBuffer off = settle(scene, device, target);
                Scene3D.Stats statsOff = scene.getStats();
                scene.setVirtualGeometry(true);
                ByteBuffer on = settle(scene, device, target);
                Scene3D.Stats statsOn = scene.getStats();
                try {
                    if (!device.supportsVirtualGeometry()) {
                        recorder.check("where the GPU can not, nothing is drawn from clusters", !scene.isUsingVirtualGeometry(), "it says it was");
                        recorder.check("and the plain meshes are drawn", statsOn.triangles() == statsOff.triangles(),
                                statsOn.triangles() + " against " + statsOff.triangles());
                        return;
                    }
                    recorder.check("off, the full meshes are drawn", statsOff.triangles() == 2 + ROW * ballTriangles, statsOff.triangles() + " triangles");
                    recorder.check("on, the frame says it drew clusters", scene.isUsingVirtualGeometry(), "it says it did not");
                    int n = target.width() * target.height(), changed = 0;
                    for (int p = 0; p < n; p++) {
                        int worst = 0;
                        for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs((off.get(p * 4 + c) & 255) - (on.get(p * 4 + c) & 255)));
                        if (worst > 40) changed++;
                    }
                    recorder.check("and hardly a pixel changes, shadows included", changed < n * 3 / 200, changed + " of " + n + " pixels changed");
                } finally {
                    MemoryUtil.memFree(off);
                    MemoryUtil.memFree(on);
                }

                // The upscaler's motion pass draws the same lists.
                scene.setRenderScale(0.75f).setUpscaler(Upscalers.builtIn());
                MemoryUtil.memFree(settle(scene, device, target));
                recorder.check("with a temporal upscaler, still drawn from clusters", scene.isUsingVirtualGeometry(), "it says it was not");
                scene.setUpscaler(null).setRenderScale(1f);

                // A node told not to draws its plain mesh, with the scene's virtual geometry still on.
                Node3D balls = scene.root().find("balls");
                balls.setVirtualGeometry(false);
                MemoryUtil.memFree(settle(scene, device, target));
                recorder.check("a node turned off is not drawn from clusters", !scene.isUsingVirtualGeometry(), "it says it was");
                recorder.check("and draws its full mesh", scene.getStats().triangles() == 2 + ROW * ballTriangles, scene.getStats().triangles() + " triangles");
                balls.setVirtualGeometry(true);

                // Close to one big ball the near side draws fine clusters and the far side coarse ones: no hole between.
                Node3D big = scene.root().find("big");
                scene.root().find("balls").setVisible(false);
                big.setVisible(true);
                Vec3 eye = new Vec3(0, 5, 9);
                scene.camera().lookAt(eye, new Vec3(0, 5, -4));
                float pixels = 3f;
                Set<Integer> levels = ClusterProbe.cutLevels(BALL, big.localMatrix().toArray(), new float[]{eye.x(), eye.y(), eye.z()}, pixelsPerUnit(), pixels);
                recorder.check("the near ball mixes levels", levels.size() >= 2, "levels " + levels);
                scene.setVirtualGeometry(false);
                ByteBuffer full = settle(scene, device, target);
                scene.setVirtualGeometry(true);
                scene.setLodPixelError(pixels);
                ByteBuffer mixed = settle(scene, device, target);
                scene.setLodPixelError(1f);
                try {
                    int w = target.width(), h = target.height(), holes = 0, inside = 0;
                    int band = 5;
                    for (int y = band; y < h - band; y++) {
                        for (int x = band; x < w - band; x++) {
                            boolean interior = true;
                            for (int dy = -band; dy <= band && interior; dy++) {
                                for (int dx = -band; dx <= band; dx++) {
                                    if (isSky(full, (y + dy) * w + x + dx)) {
                                        interior = false;
                                        break;
                                    }
                                }
                            }
                            if (!interior) continue;
                            inside++;
                            if (isSky(mixed, y * w + x)) holes++;
                        }
                    }
                    recorder.check("the sky is told apart", isSky(full, 0) && isSky(full, w - 1), "the corners are not the sky's colour");
                    recorder.check("the ball covers much of the picture", inside > w * h / 4 && inside < w * h * 3 / 4, inside + " pixels well inside it");
                    recorder.check("and has no hole where its levels meet", holes == 0, holes + " pixels show the sky through it");
                } finally {
                    MemoryUtil.memFree(full);
                    MemoryUtil.memFree(mixed);
                }
            });
        });
        assumeTrue(run.available() && run.api() == api, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        StringBuilder failures = new StringBuilder();
        run.results().forEach((name, failure) -> {
            if (failure != null) failures.append(System.lineSeparator()).append(name).append(": ").append(failure);
        });
        assertNull(failures.length() == 0 ? null : failures.toString(), "failed checks");
        return framed[0];
    }

    @Test
    void vulkan() {
        run(RenderAPI.VULKAN, 0f, true);
    }

    /** The same with every job walking the hierarchy (ten balls are too few to walk it otherwise). */
    @Test
    void vulkanWalkingTheHierarchy() {
        ClusterProbe.walkHierarchy(true);
        try {
            run(RenderAPI.VULKAN, 0f, true);
        } finally {
            ClusterProbe.walkHierarchyAsUsual();
        }
    }

    @Test
    void aBiasDrawsFewerTriangles() {
        long plain = run(RenderAPI.VULKAN, 0f, false);
        assumeTrue(plain >= 0, "nothing was drawn from clusters");
        long biased = run(RenderAPI.VULKAN, 2f, false);
        assertTrue(biased < plain * 0.7, biased + " triangles with a bias of 2, against " + plain);
    }

    /**
     * With a wall in front of the left half of the picture, which hides the nearest ball and every other one behind it: frames with the test for what is hidden on, then the last of them
     * against the full meshes drawn without clusters.
     *
     * @return the triangles the frames drew, or -1 where nothing was drawn from clusters
     */
    private long hidden(boolean occlusion) {
        Scene3D scene = scene();
        scene.setVirtualGeometry(true).setVirtualGeometryOcclusion(occlusion);
        Node3D wall = new Node3D("wall", Mesh.cube(1f), new Material(new Color(0.5f, 0.7f, 0.5f, 1f))).setPosition(-1.5f, 1.2f, 3.5f);
        wall.setScale(new Vec3(3f, 3f, 0.2f));
        scene.add(wall);
        long[] framed = {-1};
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
            if (device.api() != RenderAPI.VULKAN || !device.supportsVirtualGeometry()) return;
            recorder.guard("what is hidden", () -> {
                framed[0] = scene.getStats().triangles();
                recorder.check("drawn from clusters", scene.isUsingVirtualGeometry(), "it says it was not");
                ByteBuffer framedPicture = target.readPixels();
                scene.setVirtualGeometry(false);
                ByteBuffer full = target.readPixels();
                MemoryUtil.memFree(full);
                full = settle(scene, device, target);
                try {
                    int n = target.width() * target.height(), changed = 0;
                    for (int p = 0; p < n; p++) {
                        int worst = 0;
                        for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs((full.get(p * 4 + c) & 255) - (framedPicture.get(p * 4 + c) & 255)));
                        if (worst > 40) changed++;
                    }
                    recorder.check("nothing in sight is left out", changed < n / 100, changed + " of " + n + " pixels changed");
                } finally {
                    MemoryUtil.memFree(framedPicture);
                    MemoryUtil.memFree(full);
                }
            });
        }, 10);
        assumeTrue(run.available() && run.api() == RenderAPI.VULKAN, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        StringBuilder failures = new StringBuilder();
        run.results().forEach((name, failure) -> {
            if (failure != null) failures.append(System.lineSeparator()).append(name).append(": ").append(failure);
        });
        assertNull(failures.length() == 0 ? null : failures.toString(), "failed checks");
        return framed[0];
    }

    @Test
    void whatAWallHidesIsLeftOut() {
        long plain = hidden(false);
        assumeTrue(plain >= 0, "nothing was drawn from clusters");
        long occluded = hidden(true);
        long ballTriangles = BALL.indexCount() / 3;
        // The balls on the right, the wall and the floor are left, and a few clusters at the wall's edge.
        assertTrue(occluded < plain * 0.6, occluded + " triangles with the test for what is hidden, against " + plain + " without (a full ball has " + ballTriangles + ")");
    }

    @Test
    void aMeshWithLevelsDrawsClustersOnlyUpClose() {
        Scene3D scene = scene();
        Mesh both = ClusterProbe.ball(0.5f).generateLods(new LodSettings()).buildClusters();
        scene.root().find("balls").setMesh(both);
        // One big ball close by, which needs the full mesh, and a strict error so it does in this small view.
        Node3D near = scene.root().find("big").setMesh(both).setPosition(0, 1.5f, 1f);
        near.setScale(2f);
        near.setVisible(true);
        scene.setLodTransition(Scene3D.LodTransition.NONE).setLodPixelError(0.05f);
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
            if (device.api() != RenderAPI.VULKAN || !device.supportsVirtualGeometry()) return;
            recorder.guard("hand-over", () -> {
                ByteBuffer classic = settle(scene, device, target);
                int[] before = scene.getStats().levelRecords();
                scene.setVirtualGeometry(true);
                ByteBuffer mixed = settle(scene, device, target);
                Scene3D.Stats stats = scene.getStats();
                try {
                    recorder.check("the near ball is drawn from clusters", scene.isUsingVirtualGeometry() && stats.recordsAtLevel(0) >= 2,
                            "levels " + java.util.Arrays.toString(stats.levelRecords()));
                    recorder.check("the far ones keep their simpler levels", stats.recordsAtLevel(0) == before[0]
                                    && java.util.Arrays.equals(java.util.Arrays.copyOfRange(stats.levelRecords(), 1, Scene3D.Stats.LEVELS),
                                    java.util.Arrays.copyOfRange(before, 1, Scene3D.Stats.LEVELS)),
                            "levels " + java.util.Arrays.toString(stats.levelRecords()) + " against " + java.util.Arrays.toString(before));
                    int n = target.width() * target.height(), changed = 0;
                    for (int p = 0; p < n; p++) {
                        int worst = 0;
                        for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs((classic.get(p * 4 + c) & 255) - (mixed.get(p * 4 + c) & 255)));
                        if (worst > 40) changed++;
                    }
                    recorder.check("and the picture hardly changes", changed < n / 100, changed + " of " + n + " pixels changed");
                } finally {
                    MemoryUtil.memFree(classic);
                    MemoryUtil.memFree(mixed);
                }
            });
        });
        assumeTrue(run.available() && run.api() == RenderAPI.VULKAN, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        StringBuilder failures = new StringBuilder();
        run.results().forEach((name, failure) -> {
            if (failure != null) failures.append(System.lineSeparator()).append(name).append(": ").append(failure);
        });
        assertNull(failures.length() == 0 ? null : failures.toString(), "failed checks");
    }

    @Test
    void openGl() {
        run(RenderAPI.OPENGL, 0f, true);
    }

    private static final int EDGE_VIEW = 256;
    private static final int EDGE_STEPS = 16;
    /** A finer ball, whose finest clusters are a few pixels across where it is drawn, so the test reads the finest texels. */
    private static final Mesh FINE_BALL = ClusterProbe.ball(0.5f, 256, 128).buildClusters();

    /**
     * A big ball half behind a wall, the wall's edge moved a sixteenth of a pixel each step: each step is drawn with the test
     * for what is hidden off and then on, and the two pictures compared. Whatever differs is a cluster that should have shown
     * at the wall's edge and was taken for hidden.
     *
     * @return the pixels that differed over all steps, the steps where any did, the pixels compared, the triangles drawn on and
     * off (summed, as the GPU counted them), the samples drawn with and the GPU's most; null where nothing was drawn from clusters
     */
    private static long[] edgeLoss(int samples, float yawDegrees) {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(SKY);
        scene.setSkybox(Skybox.gradient(SKY, SKY, SKY));
        scene.setAmbient(Color.WHITE, 0.4f);
        scene.lights().add(Light.directional(new Vec3(-0.3f, -0.5f, -1f), Color.WHITE, 1.2f));
        scene.setVirtualGeometry(true).setVirtualGeometryOcclusion(true).setLodPixelError(0.02f);
        Node3D ball = new Node3D("ball", FINE_BALL, new Material(new Color(0.9f, 0.5f, 0.3f, 1f))).setPosition(0, 1.5f, 1.5f);
        ball.setScale(3f);
        scene.add(ball);
        Node3D wall = new Node3D("wall", Mesh.cube(1f), new Material(new Color(0.3f, 0.8f, 0.4f, 1f)));
        wall.setScale(new Vec3(8f, 6f, 0.1f));
        // Turned about its right edge, so its left end goes away from the camera.
        float yaw = (float) Math.toRadians(-yawDegrees);
        wall.setRotation(Quat.fromAxisAngle(new Vec3(0, 1, 0), yaw));
        float halfX = 4f * (float) Math.cos(yaw), halfZ = -4f * (float) Math.sin(yaw);
        scene.add(wall);
        Vec3 eye = new Vec3(0, 1.5f, 9f);
        scene.camera().lookAt(eye, new Vec3(0, 1.5f, 0)).setFovYDegrees(60).setClip(0.1f, 100f);
        // One pixel at the wall's front face.
        float pixel = 2f * (eye.z() - 4.35f) * (float) Math.tan(Math.toRadians(30)) / EDGE_VIEW;
        long[] result = new long[7];
        boolean[] clusters = {false};
        GpuHarness.Run run = GpuHarness.run(RenderAPI.VULKAN, EDGE_VIEW, (Consumer<Viewport3D>) viewport -> {
            viewport.setSamples(samples);
            viewport.setContent(new Viewport3D.Content() {
                int frame;
                ByteBuffer off;

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
                    int j = Math.max(0, frame++ - 2), step = Math.min(j / 2, EDGE_STEPS - 1);
                    boolean occlusion = frame <= 2 || j % 2 == 1;
                    // The wall's right edge, at x = 0 and a step more each time.
                    wall.setPosition(step * pixel / EDGE_STEPS - halfX, 1.5f, 4.3f - halfZ);
                    scene.setVirtualGeometryOcclusion(occlusion);
                    scene.render(device, target, delta);
                    if (frame <= 2 || j >= EDGE_STEPS * 2 || device.api() != RenderAPI.VULKAN || !device.supportsVirtualGeometry()) return;
                    clusters[0] |= scene.isUsingVirtualGeometry();
                    result[5] = target.samples();
                    result[6] = device.maxSamples();
                    // What the GPU counted comes back two frames late, from a frame drawn the same way.
                    result[occlusion ? 3 : 4] += scene.getStats().triangles();
                    ByteBuffer picture = target.readPixels();
                    if (!occlusion) {
                        if (off != null) MemoryUtil.memFree(off);
                        off = picture;
                        return;
                    }
                    try {
                        int n = target.width() * target.height(), changed = 0;
                        for (int p = 0; p < n; p++) {
                            int worst = 0;
                            for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs((off.get(p * 4 + c) & 255) - (picture.get(p * 4 + c) & 255)));
                            if (worst > 8) changed++;
                        }
                        result[0] += changed;
                        if (changed > 0) result[1]++;
                        result[2] += n;
                    } finally {
                        MemoryUtil.memFree(picture);
                    }
                }

                @Override
                public void dispose(GpuDevice device) {
                    if (off != null) MemoryUtil.memFree(off);
                    off = null;
                    scene.dispose(device);
                }
            });
        }, (device, target, recorder) -> {
        }, 2 + EDGE_STEPS * 2 + 1);
        assumeTrue(run.available() && run.api() == RenderAPI.VULKAN, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        return clusters[0] ? result : null;
    }

    private static final int FIELD = 40, PHASE = 8;

    /**
     * A field of {@code FIELD * FIELD} fine balls with shadows, drawn {@link #PHASE} frames testing every cluster of every
     * ball, then as many walking the hierarchy.
     *
     * @return per phase (every cluster, then the walk): the triangles drawn and the walks run; then the pixels that differ
     * and the pixels compared; null where nothing was drawn from clusters
     */
    private static long[] field() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(SKY);
        scene.setSkybox(Skybox.gradient(SKY, SKY, SKY));
        scene.setAmbient(Color.WHITE, 0.3f);
        scene.lights().add(Light.directional(new Vec3(-1, -1.2f, -0.4f), Color.WHITE, 1.2f).setCastShadow(true));
        scene.setVirtualGeometry(true);
        scene.add(new Node3D("floor", Mesh.plane(300, 300), new Material(new Color(0.8f, 0.8f, 0.8f, 1f))).setPosition(0, 0, -60));
        Instances field = new Instances();
        for (int x = 0; x < FIELD; x++) {
            for (int z = 0; z < FIELD; z++) field.add(new Vec3(x * 3f - 60f, 0.6f, -z * 3f));
        }
        scene.add(new Node3D("field", FINE_BALL, new Material(new Color(0.9f, 0.5f, 0.3f, 1f))).setInstances(field));
        scene.camera().lookAt(new Vec3(0, 4f, 6f), new Vec3(0, 0, -40)).setFovYDegrees(60).setClip(0.1f, 300f);
        long[] result = new long[6];
        boolean[] clusters = {false};
        GpuHarness.Run run = GpuHarness.run(RenderAPI.VULKAN, EDGE_VIEW, (Consumer<Viewport3D>) viewport -> viewport.setContent(new Viewport3D.Content() {
            int frame;
            long walksBefore;
            ByteBuffer flat;

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
                int phase = Math.min(frame / PHASE, 1), at = frame - phase * PHASE;
                frame++;
                if (at == 0) walksBefore = ClusterProbe.walks();
                ClusterProbe.walkHierarchy(phase == 1);
                scene.render(device, target, delta);
                if (at != PHASE - 1 || device.api() != RenderAPI.VULKAN || !device.supportsVirtualGeometry()) return;
                clusters[0] |= scene.isUsingVirtualGeometry();
                // What the GPU counted comes back two frames late, from a frame of the same phase.
                result[phase * 2] = scene.getStats().triangles();
                result[phase * 2 + 1] = ClusterProbe.walks() - walksBefore;
                ByteBuffer picture = target.readPixels();
                if (phase == 0) {
                    flat = picture;
                    return;
                }
                try {
                    int n = target.width() * target.height(), changed = 0;
                    for (int p = 0; p < n; p++) {
                        int worst = 0;
                        for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs((flat.get(p * 4 + c) & 255) - (picture.get(p * 4 + c) & 255)));
                        if (worst > 8) changed++;
                    }
                    result[4] = changed;
                    result[5] = n;
                } finally {
                    MemoryUtil.memFree(picture);
                }
            }

            @Override
            public void dispose(GpuDevice device) {
                if (flat != null) MemoryUtil.memFree(flat);
                flat = null;
                scene.dispose(device);
            }
        }), (device, target, recorder) -> {
        }, PHASE * 2 + 1);
        ClusterProbe.walkHierarchyAsUsual();
        assumeTrue(run.available() && run.api() == RenderAPI.VULKAN, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        return clusters[0] ? result : null;
    }

    /**
     * Walking the hierarchy over the clusters, which skips the parts of each ball's graph with nothing to draw, draws exactly
     * what testing every cluster of every ball draws, in a field of fine balls from near to far with shadows.
     */
    @Test
    void walkingTheHierarchyDrawsWhatTestingEveryClusterDraws() {
        long[] r = field();
        assumeTrue(r != null, "nothing was drawn from clusters");
        String report = String.format("every cluster: %d triangles; walked: %d triangles, %d walks; %d of %d pixels differ",
                r[0], r[2], r[3], r[4], r[5]);
        System.out.println("[hierarchy] " + report);
        assertTrue(r[1] == 0 && r[3] > 0, "the walk ran only when asked: " + report);
        assertTrue(r[0] > 0 && r[0] == r[2], "the same triangles: " + report);
        assertTrue(r[5] > 0 && r[4] == 0, "the same picture: " + report);
    }

    private static float[][] grid(int across, int deep, float spacing, float scale) {
        float[][] models = new float[across * deep][];
        for (int i = 0; i < models.length; i++) {
            models[i] = Mat4.translation(new Vec3((i % across) * spacing - across * spacing / 2, 0.6f, -(i / across) * spacing)).toArray();
            for (int k : new int[]{0, 5, 10}) models[i][k] = scale;
        }
        return models;
    }

    /**
     * What the walk saves, the pick timed alone (every placement in view, the camera at 1 pixel and the shadows at 2): one big
     * ball up close, which has little to skip, and fields of fine and of small balls.
     */
    @Test
    void walkingTheHierarchyTakesLessGpuTime() {
        float ppu = 540f / (float) Math.tan(Math.toRadians(30));
        float[] eye = {0, 4f, 6f};
        Object[][] cases = {
                {"one ball of 65,000 triangles up close", FINE_BALL, new float[][]{grid(1, 1, 0f, 8f)[0]}},
                {"1,600 balls of 65,000 triangles", FINE_BALL, grid(FIELD, FIELD, 3f, 1f)},
                {"7,200 balls of 9,000 triangles", BALL, grid(90, 80, 2f, 1f)},
                {"100,000 balls of 9,000 triangles", BALL, grid(400, 250, 1.5f, 1f)},
        };
        long[][] ns = new long[cases.length][2];
        GpuHarness.Run run = GpuHarness.run(RenderAPI.VULKAN, 64, (Consumer<Viewport3D>) viewport -> {
        }, (device, target, recorder) -> {
            if (device.api() != RenderAPI.VULKAN || !device.supportsVirtualGeometry()) return;
            recorder.guard("timing", () -> {
                Runnable finish = () -> MemoryUtil.memFree(target.readPixels());
                for (int c = 0; c < cases.length; c++) {
                    for (int walk = 0; walk < 2; walk++) {
                        ns[c][walk] = ClusterProbe.timeCut(device, finish, (Mesh) cases[c][1], (float[][]) cases[c][2], eye, ppu, 1f, 2f, walk == 1, 50);
                    }
                }
            });
        });
        assumeTrue(run.available() && run.api() == RenderAPI.VULKAN, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        assumeTrue(ns[0][0] > 0, "this GPU has no virtual geometry");
        StringBuilder report = new StringBuilder();
        for (int c = 0; c < cases.length; c++) {
            report.append(String.format("%n%s: every cluster %.3f ms, walked %.3f ms", cases[c][0], ns[c][0] / 1e6, ns[c][1] / 1e6));
        }
        System.out.println("[hierarchy]" + report);
        run.results().forEach((name, failure) -> assertNull(failure, name));
        // Below about a tenth of a millisecond the time is the CPU's, recording the work; only the biggest case is past it.
        assertTrue(ns[3][1] < ns[3][0] * 0.75, "the walk is faster where much is skipped:" + report);
    }

    /**
     * The test for what is hidden reads depth drawn at one sample per pixel. With multisampling, a sample beside the centre of
     * a pixel the wall covers can still see the ball: the finest texels the test reads hold two by two pixels, and a cluster's
     * box reaches past its triangles, so none is lost, straight on or with the wall turned, at 4 or 8 samples.
     */
    @Test
    void aWallsEdgeHidesNoSample() {
        StringBuilder report = new StringBuilder();
        boolean lost = false;
        for (int[] c : new int[][]{{1, 0}, {4, 0}, {4, 30}, {8, 30}}) {
            long[] r = edgeLoss(c[0], c[1]);
            assumeTrue(r != null, "nothing was drawn from clusters");
            report.append(System.lineSeparator()).append(r[5]).append(" samples, wall turned ").append(c[1]).append(": ").append(r[0])
                    .append(" pixels differ in ").append(r[1]).append(" of ").append(EDGE_STEPS).append(" steps; triangles ")
                    .append(r[4]).append(" off, ").append(r[3]).append(" on");
            assertTrue(r[2] > 0 && r[5] == Math.min(c[0], r[6]), "the pictures were drawn as asked:" + report);
            assertTrue(r[3] < r[4] * 0.6, "the clusters behind the wall are left out:" + report);
            lost |= r[0] != 0;
        }
        assertTrue(!lost, "a cluster at the wall's edge was taken for hidden:" + report);
    }
}
