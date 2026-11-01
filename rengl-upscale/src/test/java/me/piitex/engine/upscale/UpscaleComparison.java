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
    /** The share of the size the scene is drawn at; {@code -Drengl.upscale.test.scale} tries another. */
    private static final float SCALE = Float.parseFloat(System.getProperty("rengl.upscale.test.scale", "0.5"));
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

    public static byte[] pixels(RenderTarget target) {
        ByteBuffer data = target.readPixels();
        try {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            return bytes;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /** What the frames of a script do; {@code frame} counts from 1. Return true to end the run. */
    private interface Script {
        boolean frame(int frame, GpuDevice device, RenderTarget target, float delta);
    }

    /** How a run in a window went. */
    private record Run(Throwable failure, boolean rendered, RenderAPI api) {
    }

    /** Draws the scene in a hidden Vulkan window, one script step for each frame, until the script ends the run. */
    private static Run drive(Scene3D scene, Script script) {
        return drive(scene, VIEW, 20, true, w -> {
        }, script);
    }

    /** How a frame step of {@link #runWindow} ends the run, and what it was given. */
    public interface Step {
        boolean frame(int frame, Scene3D scene, GpuDevice device, RenderTarget target, float delta, Window window);
    }

    /** How a run in a window went. */
    public record Outcome(Throwable failure, boolean rendered, RenderAPI api) {
    }

    /**
     * Draws the test scene in a Vulkan window whose 3D view fills it (as frame generation wants it), {@code size} pixels square,
     * one step for each frame, until a step returns true. The window is not hidden when {@code visible}: a hidden window is
     * not presented to the display, which frame generation needs.
     */
    public static Outcome runWindow(int size, boolean visible, Step step) {
        return runWindow(scene(), size, visible, step);
    }

    /** The same for a scene of the caller's. */
    public static Outcome runWindow(Scene3D scene, int size, boolean visible, Step step) {
        Window[] window = new Window[1];
        Run run = drive(scene, size, 0, !visible, w -> window[0] = w,
                (frame, device, target, delta) -> step.frame(frame, scene, device, target, delta, window[0]));
        return new Outcome(run.failure(), run.rendered(), run.api());
    }

    private static Run drive(Scene3D scene, int view, int margin, boolean hidden, java.util.function.Consumer<Window> windowSink, Script script) {
        Throwable[] failure = new Throwable[1];
        boolean[] rendered = new boolean[1];
        RenderAPI[] api = new RenderAPI[1];
        Window window = new Window(new WindowOptions("RenGL upscaler test").setDimensions(view + 2 * margin, view + 2 * margin)
                .setRenderer(RenderAPI.VULKAN).setVsync(false));
        windowSink.accept(window);
        if (hidden) window.hide();
        Container root = new Container(view + 2 * margin, view + 2 * margin);
        window.addContainer(root);
        Viewport3D viewport = new Viewport3D(view, view);
        viewport.setPosition(margin, margin);
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
                try {
                    if (script.frame(++frame, device, target, delta)) window.close();
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
        // The view fills the window as it is resized (frame generation works on the whole window).
        window.onResize((w, h) -> {
            root.setWidth(w);
            root.setHeight(h);
            viewport.setWidth(w - 2 * margin);
            viewport.setHeight(h - 2 * margin);
        });
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(60_000);
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
        return new Run(failure[0], rendered[0], api[0]);
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
        boolean[] done = new boolean[1];
        Run run = drive(scene, (frame, device, target, delta) -> {
            if (frame == REFERENCE - 1) {
                scene.setRenderScale(1f);
                scene.setUpscaler(null);
            } else if (frame == REFERENCE + 1) {
                scene.setRenderScale(SCALE);
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
            return frame == BUILTIN || unavailable[0] != null;
        });
        return new Result(shots, unavailable[0], run.failure(), run.rendered(), run.api(), done[0]);
    }

    /**
     * What a camera that pans steadily does to an upscaler: the picture of the last of {@link #MOVING} frames of panning, the
     * same pose drawn at full size and drawn smaller and enlarged smoothly, for the vendor's upscaler and then for the built-in
     * one. The motion vectors are what carries the history along, so a wrong sign or scale shows up as a blur or ghost here and
     * not in the still scene.
     *
     * @param shots vendor, its full-size reference, its smooth enlargement, then the same three for the built-in upscaler
     */
    public record MovingResult(byte[][] shots, String unavailable, Throwable failure, boolean rendered, RenderAPI api, boolean done) {
    }

    public static final int MOVING = 40;
    private static final float PAN_PER_FRAME = 0.06f;

    public static MovingResult runMoving(UpscalerProvider provider, Function<GpuDevice, String> reason) {
        Scene3D scene = scene();
        byte[][] shots = new byte[6][];
        String[] unavailable = new String[1];
        boolean[] done = new boolean[1];
        Run run = drive(scene, (frame, device, target, delta) -> {
            int phase = (frame - 1) / (MOVING + 2); // 0: the vendor's, 1: the built-in one
            int step = (frame - 1) % (MOVING + 2) + 1;
            if (step == 1) {
                scene.setRenderScale(SCALE);
                if (phase == 0) {
                    unavailable[0] = reason.apply(device);
                    if (unavailable[0] != null) return true;
                    scene.setUpscaler(provider);
                } else {
                    scene.setUpscaler(Upscalers.builtIn());
                }
                scene.resetTemporalHistory();
            }
            if (step <= MOVING) {
                float x = PAN_PER_FRAME * (step + phase * MOVING);
                scene.camera().lookAt(new Vec3(x, 0, 3.5f), new Vec3(x, 0, 0));
            } else if (step == MOVING + 1) {
                scene.setRenderScale(1f);
                scene.setUpscaler(null);
            } else {
                scene.setRenderScale(SCALE);
            }
            scene.render(device, target, delta);
            if (step >= MOVING) shots[phase * 3 + (step - MOVING)] = pixels(target);
            if (frame == 2 * (MOVING + 2)) done[0] = true;
            return frame == 2 * (MOVING + 2);
        });
        return new MovingResult(shots, unavailable[0], run.failure(), run.rendered(), run.api(), done[0]);
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
