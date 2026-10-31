package me.piitex.engine.upscale;

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
import me.piitex.engine.render3d.upscale.UpscalerProvider;
import me.piitex.engine.render3d.upscale.Upscalers;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Function;

/**
 * The comparison the GPU tests of the vendor upscalers make: a scene (a finely checked wall, a sphere and a cube) drawn at
 * full size, then at half the width and height once enlarged smoothly, once by the vendor's upscaler after it has had frames
 * to build up, and once by the built-in one. A vendor upscaler is judged by how close its picture is to the full-size one.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class UpscaleComparison {
    public static final int VIEW = 128;
    private static final int REFERENCE = 3, SMOOTH = 6, VENDOR = 48, BUILTIN = 100;

    /** The four pictures (full size, smooth, vendor, built-in) as RGBA bytes, and how the run went. */
    public record Result(byte[][] shots, String unavailable, Throwable failure, boolean rendered, RenderAPI api, boolean done) {
    }

    static Scene3D scene() {
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

    public static double error(byte[] a, byte[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            if ((i & 3) == 3) continue;
            sum += Math.abs((a[i] & 255) - (b[i] & 255));
        }
        return sum / (a.length / 4 * 3) / 255.0;
    }

    /** Writes the picture enlarged four times (nearest), for looking at. */
    static void writePng(java.io.File file, byte[] rgba) {
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

    static byte[] pixels(RenderTarget target) {
        ByteBuffer data = target.readPixels();
        try {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            return bytes;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /**
     * Runs the comparison in a hidden Vulkan window.
     *
     * @param provider the vendor upscaler
     * @param reason   why the provider can not run on a device, or null where it can
     */
    public static Result run(UpscalerProvider provider, Function<GpuDevice, String> reason) {
        Scene3D scene = scene();
        byte[][] shots = new byte[4][];
        String[] unavailable = new String[1];
        Throwable[] failure = new Throwable[1];
        boolean[] rendered = new boolean[1], done = new boolean[1];
        RenderAPI[] api = new RenderAPI[1];

        Window window = new Window(new WindowOptions("RenGL upscaler test").setDimensions(VIEW + 40, VIEW + 40)
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
                        unavailable[0] = reason.apply(device);
                        if (unavailable[0] == null) scene.setUpscaler(provider);
                    } else if (frame == VENDOR + 1) {
                        scene.setUpscaler(Upscalers.builtIn());
                        scene.resetTemporalHistory();
                    }
                    scene.render(device, target, delta);
                    if (frame == REFERENCE) shots[0] = pixels(target);
                    if (frame == SMOOTH) shots[1] = pixels(target);
                    if (frame == VENDOR) shots[2] = pixels(target);
                    if (frame == BUILTIN) {
                        shots[3] = pixels(target);
                        done[0] = true;
                    }
                    if (frame == BUILTIN || unavailable[0] != null) window.close();
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
        }, "upscale-test-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            new Engine().start(window);
        } finally {
            watchdog.interrupt();
        }
        return new Result(shots, unavailable[0], failure[0], rendered[0], api[0], done[0]);
    }

    /** Writes the four pictures, enlarged four times, to a folder, for looking at. */
    public static void dump(String folder, byte[][] shots) {
        String[] names = {"reference", "smooth", "vendor", "builtin"};
        for (int i = 0; i < shots.length; i++) writePng(new java.io.File(folder, names[i] + ".png"), shots[i]);
    }

    public static double brightness(byte[] rgba) {
        double sum = 0;
        for (int i = 0; i < rgba.length; i += 4) sum += (rgba[i] & 255) + (rgba[i + 1] & 255) + (rgba[i + 2] & 255);
        return sum / (rgba.length / 4 * 3 * 255.0);
    }
}
