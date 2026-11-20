package me.piitex.engine.render3d;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.gl.renderer.Snapshot;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.ToneMapping;
import me.piitex.engine.render3d.upscale.Upscalers;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.ColorAlphaType;
import org.jetbrains.skia.ColorType;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.ImageInfo;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link Snapshot}s of 3D, on a real GPU on Vulkan and OpenGL: a snapshot of a {@link Viewport3D}, and of a window with
 * a viewport and a {@link Background3D}, shows the 3D picture the window shows, at the snapshot's size. The scene is
 * drawn at half resolution and upscaled, with 4 samples and a lamp brighter than white under ACES, so the picture that
 * is checked is the finished one. The pictures are also written to {@code target/snapshot-gpu}. Needs a real window (a
 * GPU and a display); skips where there is none.
 */
class SnapshotGpuTest {
    private static final int VIEW = 160, MARGIN = 40, WIN = VIEW + 2 * MARGIN;
    private static final int SNAPSHOT_FRAME = 8;
    /**
     * Where the middle of the lamp lands, as a share of the picture's width and height from the top-left: one unit
     * left of and above the middle, seen from 10 units away with a 40 degree field of view.
     */
    private static final float LAMP_U = 0.5f - 0.5f / (10f * (float) Math.tan(Math.toRadians(20)));

    private void run(RenderAPI api) {
        GpuHarness.Recorder r = new GpuHarness.Recorder();
        boolean[] rendered = new boolean[1];
        boolean[] checked = new boolean[1];
        RenderAPI[] actual = new RenderAPI[1];
        int[] framesAfter = new int[1];
        Throwable[] startError = new Throwable[1];

        Window window = new Window(new WindowOptions("RenGL snapshot test").setDimensions(WIN, WIN)
                .setRenderer(api).setVsync(false).setSplashEnabled(false));
        window.hide();
        window.setBackground(new Background3D(window, solid(0f, 0.8f, 0f)));

        Container root = new Container(WIN, WIN);
        root.getStyling().setBackgroundColor(Color.TRANSPARENT);
        root.getStyling().setBorderThickness(0f);
        Container marker = new Container(4, 4, 16, 16);
        marker.getStyling().setBackgroundColor(new Color(1f, 0f, 0f, 1f));
        marker.getStyling().setBorderThickness(0f);
        root.addElement(marker);

        Scene3D scene = new Scene3D();
        scene.setBackground(new Color(0.05f, 0.05f, 0.4f, 1f));
        scene.setToneMapping(ToneMapping.ACES);
        scene.setRenderScale(0.5f);
        scene.setUpscaler(Upscalers.builtIn());
        // Up and to the left of the middle, so a picture turned upside down or mirrored shows.
        Node3D lamp = new Node3D("lamp", Mesh.cube(1.5f), new Material(Color.BLACK).setEmissive(4f, 4f, 4f));
        lamp.setPosition(new Vec3(-1f, 1f, 0f));
        scene.add(lamp);
        scene.camera().lookAt(new Vec3(0, 0, 10), new Vec3(0, 0, 0)).setFovYDegrees(40);

        Viewport3D viewport = new Viewport3D(VIEW, VIEW);
        viewport.setPosition(MARGIN, MARGIN);
        viewport.setSamples(4);
        RenderTarget[] shown = new RenderTarget[1];
        viewport.setContent(new Viewport3D.Content() {
            int frame;

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
                rendered[0] = true;
                actual[0] = device.api();
                scene.render(device, target, delta);
                shown[0] = target;
                if (checked[0] && ++framesAfter[0] == 3) window.close();
                if (++frame != SNAPSHOT_FRAME) return;
                // Taken at the start of the next frame, before the viewport draws again: what the window shows now.
                float scale = (float) viewport.getPixelWidth() / VIEW;
                viewport.takeSnapshotAsync(scale, shot -> {
                    try (shot) {
                        r.guard("viewport snapshot", () -> checkViewport(api, shot, shown[0], r));
                        r.guard("window snapshot", () -> {
                            try (Snapshot whole = window.takeSnapshot(1f)) {
                                checkWindow(api, whole, r);
                            }
                        });
                        r.guard("a viewport never drawn", () -> {
                            Viewport3D fresh = new Viewport3D(32, 32);
                            fresh.setContent(solid(1f, 1f, 1f));
                            try (Snapshot empty = fresh.takeSnapshot()) {
                                byte[] px = pixels(empty.getImage());
                                boolean clear = true;
                                for (int i = 3; i < px.length; i += 4) clear &= px[i] == 0;
                                r.check("a viewport never drawn snapshots as empty", empty.getWidth() == 32 && clear,
                                        empty.getWidth() + "x" + empty.getHeight() + ", clear " + clear);
                            }
                        });
                    } finally {
                        checked[0] = true;
                    }
                });
            }

            @Override
            public void dispose(GpuDevice device) {
                scene.dispose(device);
            }
        });
        root.addElement(viewport);
        window.addContainer(root);

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(10_000);
                if (!rendered[0]) {
                    window.close();
                    return;
                }
                Thread.sleep(20_000);
                window.close();
            } catch (InterruptedException ignored) {
            }
        }, "snapshot-test-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            new Engine().start(window);
        } catch (Throwable t) {
            startError[0] = t;
        } finally {
            watchdog.interrupt();
        }

        assumeTrue(startError[0] == null, "the engine could not start: " + startError[0]);
        assumeTrue(rendered[0] && actual[0] == api, "the window never drew a 3D frame on " + api);
        r.check("the checks ran", checked[0], "the snapshot callback never ran");
        r.check("the viewport kept drawing after the snapshots", framesAfter[0] >= 3, "frames after " + framesAfter[0]);
        r.results.forEach((name, failure) -> assertNull(failure, api + ": " + name + ": " + failure));
    }

    private static void checkViewport(RenderAPI api, Snapshot shot, RenderTarget target, GpuHarness.Recorder r) {
        int w = target.width(), h = target.height();
        r.check("the viewport snapshot is the viewport's size in pixels", shot.getWidth() == w && shot.getHeight() == h,
                shot.getWidth() + "x" + shot.getHeight() + ", the target is " + w + "x" + h);
        save(shot, api + "-viewport.png");
        if (shot.getWidth() != w || shot.getHeight() != h) return;

        byte[] px = pixels(shot.getImage());
        int[] lamp = at(px, w, Math.round(w * LAMP_U), Math.round(h * LAMP_U));
        int[] mirrored = at(px, w, Math.round(w * (1 - LAMP_U)), Math.round(h * (1 - LAMP_U))), corner = at(px, w, 3, 3);
        r.check("the lamp shows up and to the left, tone mapped", lamp[0] > 200 && lamp[1] > 200 && lamp[2] > 200, rgb(lamp));
        r.check("and not mirrored", isSceneBlue(mirrored), rgb(mirrored));
        r.check("the scene's background shows at the corner", isSceneBlue(corner), rgb(corner));
        boolean opaque = true;
        for (int i = 3; i < px.length; i += 4) opaque &= (px[i] & 255) == 255;
        r.check("the snapshot is opaque", opaque, "some pixels are see-through");

        ByteBuffer gpu = target.readPixels();
        int worst = 0;
        long total = 0;
        for (int i = 0; i < w * h * 4; i++) {
            if (i % 4 == 3) continue;
            int d = Math.abs((px[i] & 255) - (gpu.get(i) & 255));
            worst = Math.max(worst, d);
            total += d;
        }
        double mean = total / (w * h * 3.0);
        r.check("the snapshot matches the picture on the GPU", worst <= 4 && mean < 0.5,
                "largest difference " + worst + ", mean " + mean);
    }

    private static void checkWindow(RenderAPI api, Snapshot shot, GpuHarness.Recorder r) {
        save(shot, api + "-window.png");
        r.check("the window snapshot is the window's size", shot.getWidth() == WIN && shot.getHeight() == WIN,
                shot.getWidth() + "x" + shot.getHeight());
        if (shot.getWidth() != WIN || shot.getHeight() != WIN) return;
        byte[] px = pixels(shot.getImage());
        int[] lamp = at(px, WIN, MARGIN + Math.round(VIEW * LAMP_U), MARGIN + Math.round(VIEW * LAMP_U)), sceneCorner = at(px, WIN, MARGIN + 4, MARGIN + 4);
        int[] background = at(px, WIN, WIN - 6, WIN - 6), marker = at(px, WIN, 10, 10);
        r.check("the window snapshot has the lamp", lamp[0] > 200 && lamp[1] > 200 && lamp[2] > 200, rgb(lamp));
        r.check("the window snapshot has the viewport's background", isSceneBlue(sceneCorner), rgb(sceneCorner));
        r.check("the window snapshot has the 3D window background", background[1] > 150 && background[0] < 40 && background[2] < 40,
                rgb(background));
        r.check("the window snapshot has the 2D UI over it", marker[0] > 200 && marker[1] < 40 && marker[2] < 40, rgb(marker));
    }

    /**
     * The scene's dark blue background after tone mapping and sRGB encoding, about (62, 62, 193).
     */
    private static boolean isSceneBlue(int[] c) {
        return c[2] > 150 && c[0] < 100 && c[1] < 100;
    }

    private static Viewport3D.Content solid(float red, float green, float blue) {
        return (device, target, delta) -> device.beginPass(target, red, green, blue, 1f).close();
    }

    /**
     * An image's pixels as RGBA8, top row first, not premultiplied.
     */
    private static byte[] pixels(Image image) {
        ImageInfo info = new ImageInfo(image.getWidth(), image.getHeight(), ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
        try (Bitmap bitmap = new Bitmap()) {
            if (!bitmap.allocPixels(info) || !image.readPixels(bitmap, 0, 0)) throw new IllegalStateException("could not read the snapshot");
            return bitmap.readPixels(info, image.getWidth() * 4, 0, 0);
        }
    }

    private static int[] at(byte[] px, int width, int x, int y) {
        int i = (y * width + x) * 4;
        return new int[]{px[i] & 255, px[i + 1] & 255, px[i + 2] & 255};
    }

    private static String rgb(int[] c) {
        return "rgb " + c[0] + ", " + c[1] + ", " + c[2];
    }

    private static void save(Snapshot shot, String name) {
        try {
            shot.savePng(Path.of("target", "snapshot-gpu", name));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
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
