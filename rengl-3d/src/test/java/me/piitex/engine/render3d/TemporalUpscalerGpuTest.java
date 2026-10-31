package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.render3d.scene.TextureData;
import me.piitex.engine.render3d.upscale.Upscalers;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The built-in temporal upscaler, on a real GPU on both APIs, judged by how close its picture gets to the scene drawn at
 * full size with anti-aliasing. The scene (a finely checked wall, a sphere and a cube, all lit by their own colour) is
 * drawn at half the width and height; with the camera still, the upscaler's picture must be clearly closer to the full-size one
 * than the same scene enlarged smoothly is, and with the camera panning it must stay closer. Skips where there
 * is no GPU.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class TemporalUpscalerGpuTest {
    private static final int VIEW = 128;
    /** Frames: the reference, the smooth enlargement, the upscaler on a still camera, the upscaler on a panning one, then both again at the panned place. */
    private static final int REF1 = 3, BILINEAR1 = 6, STILL = 40, PAN = 52, REF2 = 55, BILINEAR2 = 58;

    private static Scene3D scene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(Color.BLACK);
        scene.setSkybox(Skybox.gradient(new Color(0.15f, 0.3f, 0.7f, 1f), new Color(0.8f, 0.85f, 0.9f, 1f), new Color(0.1f, 0.1f, 0.1f, 1f)));
        scene.setAmbient(Color.WHITE, 1f);
        // Anything that takes the scene through the effects pass, so every phase is drawn and finished the same way.
        scene.setSaturation(1.0001f);
        TextureData checks = new TextureData(64, 64, new byte[64 * 64 * 4]);
        checks.edit(px -> {
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    boolean on = ((x / 4) + (y / 4)) % 2 == 0;
                    int i = (y * 64 + x) * 4;
                    px[i] = (byte) (on ? 230 : 40);
                    px[i + 1] = (byte) (on ? 210 : 60);
                    px[i + 2] = (byte) (on ? 160 : 90);
                    px[i + 3] = (byte) 255;
                }
            }
        });
        Mesh quad = new Mesh(new float[]{-3, -3, 0, 3, -3, 0, 3, 3, 0, -3, 3, 0}, new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[]{0, 3, 3, 3, 3, 0, 0, 0}, new int[]{0, 1, 2, 0, 2, 3});
        scene.add(new Node3D("wall", quad, new Material(Color.WHITE).setUnlit(true).setTexture(checks)));
        Node3D sphere = new Node3D("sphere", Mesh.sphere(0.7f, 32, 16), new Material(new Color(0.2f, 0.8f, 0.3f, 1f)).setUnlit(true));
        sphere.setPosition(-0.8f, 0.2f, 1f);
        scene.add(sphere);
        Node3D cube = new Node3D("cube", Mesh.cube(0.9f), new Material(new Color(0.9f, 0.2f, 0.2f, 1f)).setUnlit(true));
        cube.setPosition(0.9f, -0.3f, 1.2f);
        scene.add(cube);
        scene.camera().lookAt(new Vec3(0, 0, 3.5f), new Vec3(0, 0, 0)).setFovYDegrees(60).setClip(0.5f, 20f);
        return scene;
    }

    /**
     * The mean difference per colour channel between two pictures, from 0 to 1.
     */
    private static double error(byte[] a, byte[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            if ((i & 3) == 3) continue;
            sum += Math.abs((a[i] & 255) - (b[i] & 255));
        }
        return sum / (a.length / 4 * 3) / 255.0;
    }

    private static byte[] pixels(RenderTarget target) {
        ByteBuffer data = target.readPixels();
        try {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            return bytes;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static void run(RenderAPI api) {
        Scene3D scene = scene();
        byte[][] shots = new byte[6][];
        GpuHarness.Run run = GpuHarness.run(api, VIEW, (Consumer<Viewport3D>) viewport -> viewport.setContent(new Viewport3D.Content() {
            int frame;

            @Override
            public void init(GpuDevice device) {
                scene.init(device);
            }

            @Override
            public void render(GpuDevice device, RenderTarget target, float delta) {
                frame++;
                if (frame == REF1 - 2) {
                    scene.setRenderScale(1f);
                    scene.setUpscaler(null);
                } else if (frame == REF1 + 1) {
                    scene.setRenderScale(0.5f);
                } else if (frame == BILINEAR1 + 1) {
                    scene.setUpscaler(Upscalers.builtIn());
                } else if (frame > STILL && frame <= PAN) {
                    // A slow pan: a third of a pixel of the picture a frame.
                    Vec3 p = scene.camera().getPosition();
                    scene.camera().lookAt(new Vec3(p.x() + 0.01f, p.y(), p.z()), new Vec3(p.x() + 0.01f, 0, 0));
                } else if (frame == PAN + 1) {
                    scene.setRenderScale(1f);
                    scene.setUpscaler(null);
                } else if (frame == REF2 + 1) {
                    scene.setRenderScale(0.5f);
                }
                scene.render(device, target, delta);
                if (frame == REF1) shots[0] = pixels(target);
                if (frame == BILINEAR1) shots[1] = pixels(target);
                if (frame == STILL) shots[2] = pixels(target);
                if (frame == PAN) shots[3] = pixels(target);
                if (frame == REF2) shots[4] = pixels(target);
                if (frame == BILINEAR2) shots[5] = pixels(target);
            }

            @Override
            public void dispose(GpuDevice device) {
                scene.dispose(device);
            }
        }), (device, target, recorder) -> {
            if (device.api() != api) return;
            double smooth = error(shots[1], shots[0]), temporal = error(shots[2], shots[0]);
            double smoothMoved = error(shots[5], shots[4]), temporalMoved = error(shots[3], shots[4]);
            String dump = System.getenv("RENGL_TEST_DUMP");
            if (dump != null) {
                String[] names = {"ref1", "bilinear1", "still", "pan", "ref2", "bilinear2"};
                for (int k = 0; k < shots.length; k++) {
                    java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(target.width(), target.height(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < target.height(); y++) {
                        for (int x = 0; x < target.width(); x++) {
                            int i = (y * target.width() + x) * 4;
                            image.setRGB(x, y, ((shots[k][i] & 255) << 16) | ((shots[k][i + 1] & 255) << 8) | (shots[k][i + 2] & 255));
                        }
                    }
                    try {
                        javax.imageio.ImageIO.write(image, "png", new java.io.File(dump, api + "-" + names[k] + ".png"));
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            }
            System.out.printf("%s: still: smooth %.5f, temporal %.5f; panning: smooth %.5f, temporal %.5f%n", api, smooth, temporal, smoothMoved, temporalMoved);
            double brightness = 0;
            for (int i = 0; i < shots[2].length; i += 4) brightness += (shots[2][i] & 255) + (shots[2][i + 1] & 255) + (shots[2][i + 2] & 255);
            brightness /= shots[2].length / 4 * 3 * 255.0;
            recorder.check("the upscaled picture is not empty", brightness > 0.1, "mean brightness " + brightness);
            recorder.check("still: the smooth enlargement is visibly different from the full-size picture", smooth > 0.01, "error " + smooth);
            recorder.check("still: the upscaler is much closer to the full-size picture than the smooth enlargement", temporal < smooth * 0.75,
                    "upscaler " + temporal + ", smooth " + smooth);
            recorder.check("panning: the upscaler is still closer than the smooth enlargement", temporalMoved < smoothMoved * 0.9,
                    "upscaler " + temporalMoved + ", smooth " + smoothMoved);
        }, BILINEAR2 + 1);
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
