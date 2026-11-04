package me.piitex.engine.render3d;

import me.piitex.engine.Engine;
import me.piitex.engine.Loading;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Splash;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.upscale.Upscaler;
import me.piitex.engine.render3d.upscale.UpscalerProvider;
import me.piitex.engine.render3d.upscale.Upscalers;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link Viewport3D#warmUp()} in a real hidden window: the content is made and drawn under the splash, and the
 * application starts only afterwards. Skips where the API's window cannot open.
 */
class ViewportWarmUpGpuTest {
    /**
     * Holds the application until the window's warm-ups are done, then finishes.
     */
    private static final class WaitingSplash implements Splash {
        private Loading loading;

        @Override
        public void onStart(Window window) {
            loading = window.getLoading();
        }

        @Override
        public void update(float delta) {
        }

        @Override
        public void render(Renderer renderer, int width, int height) {
        }

        @Override
        public boolean isHoldingApp() {
            return !loading.isDone();
        }

        @Override
        public boolean isFinished() {
            return loading.isDone();
        }
    }

    /**
     * Content that asks for frames until it has drawn three, as a scene with a preload in progress does, and logs what happens.
     */
    private static final class CountingContent implements Viewport3D.Content {
        private final List<String> events;
        final AtomicInteger renders = new AtomicInteger();
        final AtomicInteger inits = new AtomicInteger();

        CountingContent(List<String> events) {
            this.events = events;
        }

        @Override
        public void init(GpuDevice device) {
            inits.incrementAndGet();
            events.add("init");
        }

        @Override
        public void render(GpuDevice device, RenderTarget target, float delta) {
            renders.incrementAndGet();
            events.add("render");
        }

        @Override
        public boolean needsFrames() {
            return renders.get() < 3;
        }
    }

    private static void check(RenderAPI api) throws Exception {
        try {
            assumeTrue(RenderAPI.resolve(api) == api, api + " does not exist here");
        } catch (IllegalStateException e) {
            assumeTrue(false, api + " does not exist here");
        }
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger appUpdates = new AtomicInteger();
        CountingContent content = new CountingContent(events);

        Viewport3D viewport = new Viewport3D(200, 200);
        viewport.setContent(content);
        Window window = new Window(new WindowOptions("RenGL viewport warm-up test").setDimensions(240, 240).setRenderer(api)
                .setVsync(false).setSplash(new WaitingSplash()).addWarmUp(viewport.warmUp()));
        window.hide();
        Container root = new Container(240, 240);
        root.onUpdate(_ -> {
            if (appUpdates.getAndIncrement() == 0) events.add("application first updated");
        });
        viewport.setPosition(20, 20);
        root.addElement(viewport);
        window.addContainer(root);

        Thread closer = new Thread(() -> {
            try {
                for (int i = 0; i < 150 && appUpdates.get() < 5; i++) Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                window.close();
            }
        }, "viewport-warm-up-closer");
        closer.setDaemon(true);
        closer.start();
        try {
            new Engine().start(window);
        } catch (Throwable t) {
            assumeTrue(false, "the engine could not start: " + t);
        } finally {
            closer.join(5_000);
        }
        assumeTrue(appUpdates.get() >= 5, "the window never ran");

        int appStarted = events.indexOf("application first updated");
        assertTrue(appStarted > 0, events.toString());
        List<String> before = events.subList(0, appStarted);
        assertEquals(1, before.stream().filter("init"::equals).count(), "the content was initialised under the splash: " + events);
        assertEquals(3L, before.stream().filter("render"::equals).count(),
                "the content was drawn until it stopped asking for frames, before the application started: " + events);
        assertEquals(1, content.inits.get(), "the application's own frames did not initialise it again");
        assertTrue(content.renders.get() > 3, "and the application's frames then drew it: " + events);
    }

    /**
     * A real scene that renders at half size through an upscaler: the upscaler is made when the scene is first drawn, which
     * is the load a vendor's library adds, so it has to happen under the splash and not in the application's first frame.
     */
    private static void checkSceneUpscaler(RenderAPI api) throws Exception {
        try {
            assumeTrue(RenderAPI.resolve(api) == api, api + " does not exist here");
        } catch (IllegalStateException e) {
            assumeTrue(false, api + " does not exist here");
        }
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger appUpdates = new AtomicInteger();
        UpscalerProvider provider = new UpscalerProvider() {
            @Override
            public String name() {
                return "Counting";
            }

            @Override
            public boolean isAvailable(GpuDevice device) {
                return true;
            }

            @Override
            public Upscaler create(GpuDevice device) {
                events.add("upscaler made");
                return Upscalers.builtIn().create(device);
            }
        };
        Scene3D scene = new Scene3D();
        scene.setBackground(Color.BLACK);
        scene.add(new Node3D("cube", Mesh.cube(1f), new Material(new Color(0.9f, 0.2f, 0.2f, 1f))));
        scene.camera().lookAt(new Vec3(0, 0, 3.5f), new Vec3(0, 0, 0)).setFovYDegrees(60).setClip(0.5f, 20f);
        scene.setRenderScale(0.5f);
        scene.setUpscaler(provider);

        Viewport3D viewport = new Viewport3D(200, 200);
        viewport.setContent(new Viewport3D.Content() {
            @Override
            public void init(GpuDevice device) {
                scene.init(device);
            }

            @Override
            public void render(GpuDevice device, RenderTarget target, float delta) {
                scene.render(device, target, delta);
            }

            @Override
            public boolean needsFrames() {
                return scene.needsFrames();
            }

            @Override
            public void dispose(GpuDevice device) {
                scene.dispose(device);
            }
        });
        Window window = new Window(new WindowOptions("RenGL scene warm-up test").setDimensions(240, 240).setRenderer(api)
                .setVsync(false).setSplash(new WaitingSplash()).addWarmUp(viewport.warmUp()));
        window.hide();
        Container root = new Container(240, 240);
        root.onUpdate(_ -> {
            if (appUpdates.getAndIncrement() == 0) events.add("application first updated");
        });
        viewport.setPosition(20, 20);
        root.addElement(viewport);
        window.addContainer(root);

        Thread closer = new Thread(() -> {
            try {
                for (int i = 0; i < 150 && appUpdates.get() < 5; i++) Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                window.close();
            }
        }, "scene-warm-up-closer");
        closer.setDaemon(true);
        closer.start();
        try {
            new Engine().start(window);
        } catch (Throwable t) {
            assumeTrue(false, "the engine could not start: " + t);
        } finally {
            closer.join(5_000);
        }
        assumeTrue(appUpdates.get() >= 5, "the window never ran");

        int made = events.indexOf("upscaler made");
        int appStarted = events.indexOf("application first updated");
        assertTrue(made >= 0, "the scene's upscaler was made: " + events);
        assertTrue(made < appStarted, "the upscaler was made under the splash, before the application started: " + events);
        assertEquals(1, events.stream().filter("upscaler made"::equals).count(), "and the application's frames reused it: " + events);
    }

    @Test
    void openGlContentIsPreparedUnderTheSplash() throws Exception {
        check(RenderAPI.OPENGL);
    }

    @Test
    void vulkanContentIsPreparedUnderTheSplash() throws Exception {
        check(RenderAPI.VULKAN);
    }

    @Test
    void openGlSceneUpscalerIsMadeUnderTheSplash() throws Exception {
        checkSceneUpscaler(RenderAPI.OPENGL);
    }

    @Test
    void vulkanSceneUpscalerIsMadeUnderTheSplash() throws Exception {
        checkSceneUpscaler(RenderAPI.VULKAN);
    }
}
