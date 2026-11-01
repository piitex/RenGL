package me.piitex.engine.upscale.dlss;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.RenderTarget;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.upscale.UpscaleComparison;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DLSS Ray Reconstruction on a real Vulkan device: a path traced scene drawn through the DLSS upscaler hands its noisy light and
 * the guide pictures (albedo, normals, roughness) to DLSS, which cleans it up and enlarges it, in place of the scene's own filter.
 * The picture must still show the light that bounces (a red wall tints the floor beside it), be smooth, follow a moving camera,
 * and the scene must say what it did ({@link Scene3D#isUsingRayReconstruction()}); with the setting off the scene's own filter is
 * used with DLSS super resolution.
 * <p>
 * Needs the Streamline libraries for DLSS and for Ray Reconstruction ({@link Sl}), an NVIDIA RTX GPU with ray queries, and skips
 * elsewhere. Run with {@code -Drengl.dlss.dir=<folder>}; {@code -Drengl.dlss.dump=<folder>} writes the pictures.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class DlssRayReconstructionGpuTest {
    private static final int SIZE = 256, SETTLE = 70, PAN = 40;

    /** A white floor and a tall red wall at x = 2.5, lit by a sun and by nothing else: light that tints the floor bounced. */
    private static Scene3D bounce() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setAmbient(Color.BLACK, 0f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.lights().add(Light.directional(new Vec3(1f, -1.2f, 0f), Color.WHITE, 0.8f));
        scene.setPathTracing(true);
        scene.add(new Node3D("floor", Mesh.plane(40f, 40f), new Material(Color.WHITE).setRoughness(0.9f)));
        Node3D wall = new Node3D("wall", Mesh.cube(1f), new Material(Color.RED).setRoughness(0.9f));
        wall.setPosition(2.5f, 2f, 0f);
        wall.setScale(new Vec3(1f, 4f, 12f));
        scene.add(wall);
        Node3D ball = new Node3D("ball", Mesh.sphere(1f, 32, 16), new Material(new Color(0.9f, 0.9f, 0.9f, 1f)).setRoughness(0.15f).setMetallic(0.8f));
        ball.setPosition(-1f, 1f, 1f);
        scene.add(ball);
        scene.camera().lookAt(new Vec3(-3f, 7f, 9f), new Vec3(0f, 0f, 0f)).setFovYDegrees(60);
        return scene;
    }

    /** RGBA bytes of a picture, and what the tests ask of them. */
    private record Shot(byte[] data, int width, int height, Scene3D scene) {
        int[] screen(float x, float y, float z) {
            var camera = scene.camera();
            Vec3 ndc = camera.projection((float) width / height).mul(camera.view()).transformPoint(new Vec3(x, y, z));
            int cx = Math.round((ndc.x() * 0.5f + 0.5f) * width), cy = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * height);
            if (cx < 0 || cy < 0 || cx >= width || cy >= height) throw new IllegalStateException("(" + x + ", " + y + ", " + z + ") is off screen");
            return new int[]{cx, cy};
        }

        int[] mean(float x, float y, float z) {
            int[] at = screen(x, y, z);
            long[] sum = new long[3];
            int n = 0;
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    int sx = at[0] + dx, sy = at[1] + dy;
                    if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue;
                    for (int c = 0; c < 3; c++) sum[c] += data[(sy * width + sx) * 4 + c] & 255;
                    n++;
                }
            }
            return new int[]{(int) (sum[0] / n), (int) (sum[1] / n), (int) (sum[2] / n)};
        }

        /** How far, on average, the red of the pixels around a point is from their mean: the grain left in the picture. */
        double grain(float x, float y, float z) {
            int[] at = screen(x, y, z);
            int mean = mean(x, y, z)[0];
            double sum = 0;
            int n = 0;
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    int sx = at[0] + dx, sy = at[1] + dy;
                    if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue;
                    sum += Math.abs((data[(sy * width + sx) * 4] & 255) - mean);
                    n++;
                }
            }
            return sum / n;
        }

        double brightness() {
            return UpscaleComparison.brightness(data);
        }

        void write(String folder, String name) {
            if (folder == null) return;
            var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (y * width + x) * 4;
                    image.setRGB(x, y, (data[i] & 255) << 16 | (data[i + 1] & 255) << 8 | (data[i + 2] & 255));
                }
            }
            try {
                var file = new java.io.File(folder, name + ".png");
                file.getParentFile().mkdirs();
                javax.imageio.ImageIO.write(image, "png", file);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    private static Shot shot(Scene3D scene, RenderTarget target) {
        return new Shot(UpscaleComparison.pixels(target), target.width(), target.height(), scene);
    }

    /** Render scales from a third to the full size (DLAA), which DLSS super resolution takes and Ray Reconstruction must too. */
    private static final float[] SCALES = {0.33f, 0.4f, 0.5f, 0.58f, 0.67f, 0.8f, 1f};
    private static final int SWEEP = 40;

    @Test
    void rayReconstructionTakesTheRenderSizesOfDlss() {
        assumeTrue(Sl.load() != null, "NVIDIA's Streamline libraries were not found: " + Sl.failure());
        DlssProvider provider = new DlssProvider();
        Scene3D scene = bounce();
        String[] unavailable = new String[1];
        double[] brightness = new double[SCALES.length];
        int[] sizes = new int[SCALES.length];
        boolean[] reconstructed = new boolean[SCALES.length];
        UpscaleComparison.Outcome outcome = UpscaleComparison.runWindow(scene, SIZE, false, (frame, s, device, target, delta, window) -> {
            int phase = (frame - 1) / SWEEP, step = (frame - 1) % SWEEP + 1;
            // The window may ask for one frame more after the last one has said it is done.
            if (phase >= SCALES.length) return true;
            if (frame == 1) {
                unavailable[0] = DlssProvider.rayReconstructionUnavailableReason(device);
                if (unavailable[0] != null) return true;
                s.setUpscaler(provider);
            }
            if (step == 1) {
                s.setRenderScale(SCALES[phase]);
                s.resetTemporalHistory();
            }
            s.render(device, target, delta);
            if (step == SWEEP) {
                Shot shot = shot(s, target);
                brightness[phase] = shot.brightness();
                reconstructed[phase] = s.isUsingRayReconstruction();
                sizes[phase] = s.getRenderScale() >= 1f ? SIZE : Math.round(SIZE * provider.supportedRenderScale(s.getRenderScale(), SIZE, SIZE));
                shot.write(System.getProperty("rengl.dlss.dump"), "rr-scale-" + Math.round(SCALES[phase] * 100));
            }
            return frame == SCALES.length * SWEEP;
        });

        assumeTrue(outcome.rendered() && outcome.api() == RenderAPI.VULKAN, "no Vulkan window: " + outcome.api());
        assumeTrue(unavailable[0] == null, "Ray Reconstruction is not usable here: " + unavailable[0]);
        if (outcome.failure() != null) throw new AssertionError("the run failed: " + outcome.failure(), outcome.failure());
        for (int i = 0; i < SCALES.length; i++) {
            System.out.printf("Ray Reconstruction at %d%% (drawn %d wide): brightness %.3f%n", Math.round(SCALES[i] * 100), sizes[i], brightness[i]);
            assertTrue(reconstructed[i], "Ray Reconstruction ran at " + SCALES[i]);
            assertTrue(brightness[i] > 0.3, "the picture is not black at " + SCALES[i] + ": " + brightness[i]);
        }
    }

    @Test
    void rayReconstructionCleansUpAPathTracedSceneAndFollowsTheCamera() {
        assumeTrue(Sl.load() != null, "NVIDIA's Streamline libraries were not found: " + Sl.failure());
        DlssProvider provider = new DlssProvider();
        Scene3D scene = bounce();
        String[] unavailable = new String[1];
        Shot[] shots = new Shot[3];
        boolean[][] flags = new boolean[3][];
        UpscaleComparison.Outcome outcome = UpscaleComparison.runWindow(scene, SIZE, false, (frame, s, device, target, delta, window) -> {
            if (frame == 1) {
                unavailable[0] = DlssProvider.rayReconstructionUnavailableReason(device);
                if (unavailable[0] != null) return true;
                s.setRenderScale(0.5f);
                s.setUpscaler(provider);
                s.setRayReconstruction(true);
            }
            if (frame == SETTLE + 1) {
                s.setRayReconstruction(false);
                s.resetTemporalHistory();
            }
            if (frame == 2 * SETTLE + 1) {
                s.setRayReconstruction(true);
                s.resetTemporalHistory();
            }
            int phase = (frame - 1) / SETTLE;
            int step = (frame - 1) % SETTLE + 1;
            // The last stretch pans, to see whether the motion vectors carry the history along.
            if (phase == 2) {
                float x = -3f + 0.03f * Math.min(step, PAN);
                s.camera().lookAt(new Vec3(x, 7f, 9f), new Vec3(0f, 0f, 0f));
            }
            s.render(device, target, delta);
            if (step == SETTLE && phase < 3) {
                shots[phase] = shot(s, target);
                flags[phase] = new boolean[]{s.isUsingPathTracing(), s.isUsingRayReconstruction()};
            }
            return frame == 3 * SETTLE;
        });

        assumeTrue(outcome.rendered() && outcome.api() == RenderAPI.VULKAN, "no Vulkan window: " + outcome.api());
        assumeTrue(unavailable[0] == null, "Ray Reconstruction is not usable here: " + unavailable[0]);
        if (outcome.failure() != null) throw new AssertionError("the run failed: " + outcome.failure(), outcome.failure());
        assertTrue(shots[2] != null, "the run reached its last picture");

        String dump = System.getProperty("rengl.dlss.dump");
        shots[0].write(dump, "rr-reconstructed");
        shots[1].write(dump, "rr-own-filter");
        shots[2].write(dump, "rr-panning");

        assertTrue(flags[0][0] && flags[0][1], "the scene was path traced and Ray Reconstruction cleaned it up");
        assertTrue(flags[1][0] && !flags[1][1], "with Ray Reconstruction off the scene's own filter did");
        assertTrue(flags[2][0] && flags[2][1], "Ray Reconstruction came back when turned on again");

        Shot a = shots[0], b = shots[1], c = shots[2];
        int[] near = a.mean(1.5f, 0f, 0f), far = a.mean(-4f, 0f, 0f);
        double grainNear = a.grain(1.2f, 0f, 0f), grainFar = a.grain(-4f, 0f, 0f), ownGrain = b.grain(-4f, 0f, 0f);
        System.out.printf("Ray Reconstruction: floor beside the wall %d,%d,%d far %d,%d,%d; grain %.2f / %.2f (own filter %.2f); brightness %.3f (own filter %.3f), panning %.3f%n",
                near[0], near[1], near[2], far[0], far[1], far[2], grainNear, grainFar, ownGrain, a.brightness(), b.brightness(), c.brightness());
        assertTrue(a.brightness() > 0.05, "the picture is not empty: " + a.brightness());
        assertTrue((near[0] - near[1]) > (far[0] - far[1]) + 10 && far[0] > 40, "the floor beside the red wall is tinted red: near " + near[0] + "," + near[1] + " far " + far[0] + "," + far[1]);
        assertTrue(grainNear < 9.0 && grainFar < 9.0, "the cleaned picture is smooth: " + grainNear + " and " + grainFar);
        assertEquals(b.brightness(), a.brightness(), b.brightness() * 0.35, "it holds about the light the scene's own filter does");
        assertTrue(c.brightness() > 0.05 && Math.abs(c.brightness() - a.brightness()) < a.brightness() * 0.4, "the panning picture is as bright: " + c.brightness() + " against " + a.brightness());
        assertFalse(java.util.Arrays.equals(a.data(), b.data()), "the two ways of cleaning it up make different pictures");
    }
}
