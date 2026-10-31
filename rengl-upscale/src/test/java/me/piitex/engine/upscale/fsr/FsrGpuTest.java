package me.piitex.engine.upscale.fsr;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.RenderTarget;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.render3d.scene.TextureData;
import me.piitex.engine.render3d.upscale.Upscalers;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FSR on a real Vulkan device, judged the way the built-in upscaler is: by how close its picture gets to the scene drawn at
 * full size. The scene (a finely checked wall, a sphere and a cube) is drawn at half the width and height, once enlarged
 * smoothly and once by FSR after it has had frames to build up; FSR must be clearly closer to the full-size picture.
 * <p>
 * Needs AMD's libraries (see {@link Ffx}), which are not part of the build: the test skips where they are not found,
 * where there is no Vulkan device, or where the SDK will not make an upscaler on it. Run with
 * {@code -Drengl.fsr.dir=<folder>}; add {@code -Drengl.vulkan.validation=true} to have the validation layer check what the
 * SDK records, and {@code -Drengl.fsr.version=3.1} to pin FSR 3.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class FsrGpuTest {
    private static final int VIEW = 128;
    private static final int REFERENCE = 3, SMOOTH = 6, FSR = 48, BUILTIN = 100;

    private static Scene3D scene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(Color.BLACK);
        scene.setSkybox(Skybox.gradient(new Color(0.15f, 0.3f, 0.7f, 1f), new Color(0.8f, 0.85f, 0.9f, 1f), new Color(0.1f, 0.1f, 0.1f, 1f)));
        scene.setAmbient(Color.WHITE, 1f);
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

    private static double error(byte[] a, byte[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            if ((i & 3) == 3) continue;
            sum += Math.abs((a[i] & 255) - (b[i] & 255));
        }
        return sum / (a.length / 4 * 3) / 255.0;
    }

    /** Writes the picture enlarged four times (nearest), for looking at. */
    private static void writePng(java.io.File file, byte[] rgba) {
        int scale = 4;
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(VIEW * scale, VIEW * scale, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < VIEW * scale; y++) {
            for (int x = 0; x < VIEW * scale; x++) {
                int i = ((y / scale) * VIEW + x / scale) * 4;
                image.setRGB(x, y, (rgba[i] & 255) << 16 | (rgba[i + 1] & 255) << 8 | (rgba[i + 2] & 255));
            }
        }
        try {
            file.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(image, "png", file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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

    @Test
    void fsrIsCloserToTheFullSizePictureThanASmoothEnlargement() {
        assumeTrue(Ffx.load() != null, "the FidelityFX libraries were not found: " + Ffx.failure());
        Scene3D scene = scene();
        FsrProvider provider = new FsrProvider();
        byte[][] shots = new byte[4][];
        String[] outcome = new String[3]; // the upscaler's name, or why there is none
        Throwable[] failure = new Throwable[1];
        boolean[] rendered = new boolean[1];
        RenderAPI[] api = new RenderAPI[1];

        Window window = new Window(new WindowOptions("RenGL FSR test").setDimensions(VIEW + 40, VIEW + 40)
                .setRenderer(RenderAPI.VULKAN).setVsync(false));
        window.hide();
        Container root = new Container(VIEW + 40, VIEW + 40);
        window.addContainer(root);
        Viewport3D viewport = new Viewport3D(VIEW, VIEW);
        viewport.setPosition(20, 20);
        viewport.setContent(new Viewport3D.Content() {
            int frame;

            @Override
            public void init(GpuDevice device) {
                scene.init(device);
            }

            @Override
            public void render(GpuDevice device, RenderTarget target, float delta) {
                rendered[0] = true;
                api[0] = device.api();
                if (device.api() != RenderAPI.VULKAN) {
                    window.close();
                    return;
                }
                frame++;
                try {
                    if (frame == REFERENCE - 1) {
                        scene.setRenderScale(1f);
                        scene.setUpscaler(null);
                    } else if (frame == REFERENCE + 1) {
                        scene.setRenderScale(0.5f);
                    } else if (frame == SMOOTH + 1) {
                        if (!provider.isAvailable(device)) {
                            outcome[2] = FsrProvider.unavailableReason() != null ? FsrProvider.unavailableReason() : "the SDK would not make an upscaler on " + device.name();
                        } else {
                            scene.setUpscaler(provider);
                        }
                    } else if (frame == FSR + 1) {
                        scene.setUpscaler(Upscalers.builtIn());
                        scene.resetTemporalHistory();
                    }
                    scene.render(device, target, delta);
                    if (frame == REFERENCE) shots[0] = pixels(target);
                    if (frame == SMOOTH) shots[1] = pixels(target);
                    if (frame == FSR) shots[2] = pixels(target);
                    if (frame == BUILTIN) {
                        shots[3] = pixels(target);
                        outcome[0] = "done";
                    }
                    if (frame == BUILTIN || outcome[2] != null) window.close();
                } catch (Throwable t) {
                    failure[0] = t;
                    window.close();
                }
            }

            @Override
            public void dispose(GpuDevice device) {
                scene.dispose(device);
            }
        });
        root.addElement(viewport);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(40_000);
                window.close();
            } catch (InterruptedException ignored) {
            }
        }, "fsr-test-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            new Engine().start(window);
        } finally {
            watchdog.interrupt();
        }

        assumeTrue(rendered[0] && api[0] == RenderAPI.VULKAN, "no Vulkan window: " + api[0]);
        assumeTrue(outcome[2] == null, "FSR is not usable here: " + outcome[2]);
        if (failure[0] != null) throw new AssertionError("FSR failed: " + failure[0], failure[0]);
        assertTrue("done".equals(outcome[0]), "the run did not reach the FSR frame");
        double smooth = error(shots[1], shots[0]), fsr = error(shots[2], shots[0]), builtIn = error(shots[3], shots[0]);
        System.out.printf("FSR: smooth enlargement %.5f, FSR %.5f, built-in %.5f%n", smooth, fsr, builtIn);
        String dump = System.getProperty("rengl.fsr.dump");
        if (dump != null) {
            String[] names = {"reference", "smooth", "fsr", "builtin"};
            for (int i = 0; i < shots.length; i++) writePng(new java.io.File(dump, names[i] + ".png"), shots[i]);
        }
        double brightness = 0;
        for (int i = 0; i < shots[2].length; i += 4) brightness += (shots[2][i] & 255) + (shots[2][i + 1] & 255) + (shots[2][i + 2] & 255);
        brightness /= shots[2].length / 4 * 3 * 255.0;
        assertTrue(brightness > 0.1, "the FSR picture is not empty, mean brightness " + brightness);
        // The wall's checks are only two pixels wide at half the size, so every picture drawn that small, whatever enlarges it,
        // is a little off the full-size one at each edge; that puts a floor under both errors. FSR measures about 0.75 of the
        // smooth enlargement here.
        assertTrue(fsr < smooth * 0.9, "FSR is closer to the full-size picture than the smooth enlargement: FSR " + fsr + ", smooth " + smooth);
    }

    @Test
    void withoutTheLibrariesTheProviderSaysSo() {
        assumeTrue(Ffx.load() == null, "the libraries are present");
        assertTrue(FsrProvider.unavailableReason() != null && !FsrProvider.unavailableReason().isBlank());
    }
}
