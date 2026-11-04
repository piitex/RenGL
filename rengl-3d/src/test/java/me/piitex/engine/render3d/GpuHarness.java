package me.piitex.engine.render3d;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.containers.Container;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs checks against a real {@link GpuDevice}: opens a hidden window on the chosen API, puts a {@link Viewport3D}
 * in it, runs the checks on the render thread a few frames in, then closes the window and returns what they found.
 * <p>
 * It reports "no GPU" instead of failing when the machine can't give the 3D layer a device (no display, no Vulkan
 * driver, or a window that fell back to Metal), so the tests that use it skip on a build server.
 */
final class GpuHarness {
    /**
     * What a run found. {@code available} is false when no device existed, and then there are no results. In
     * {@code results} a check's name maps to its failure message, or to {@code null} if it passed.
     */
    record Run(boolean available, String reason, String deviceName, RenderAPI api, Map<String, String> results) {
    }

    /**
     * What the checks see.
     */
    interface Checks {
        void run(GpuDevice device, RenderTarget target, Recorder recorder);
    }

    /**
     * Collects named results. A check that throws is recorded as a failure instead of stopping the others.
     */
    static final class Recorder {
        /**
         * Name to failure message, {@code null} for a pass.
         */
        final Map<String, String> results = new LinkedHashMap<>();

        void check(String name, boolean ok, String detail) {
            results.put(name, ok ? null : detail);
        }

        void guard(String name, Runnable body) {
            try {
                body.run();
            } catch (Throwable t) {
                results.put(name, t.toString());
            }
        }
    }

    private GpuHarness() {
    }

    static Run run(RenderAPI api, int viewportSize, Consumer<Viewport3D> setup, Checks checks) {
        return run(api, viewportSize, setup, checks, 6);
    }

    /**
     * The same, drawing {@code frames} frames before the checks run, for what builds up over time.
     */
    static Run run(RenderAPI api, int viewportSize, Consumer<Viewport3D> setup, Checks checks, int frames) {
        if (api == RenderAPI.OPENGL && System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            return runHeadlessGl(viewportSize, setup, checks, frames); // the engine would log a fatal error for an OpenGL window here
        }
        try {
            if (RenderAPI.resolve(api) != api) {
                return api == RenderAPI.OPENGL ? runHeadlessGl(viewportSize, setup, checks, frames)
                        : new Run(false, api + " does not exist on this platform", null, null, Map.of());
            }
        } catch (IllegalStateException e) {
            // macOS: the engine refuses OpenGL windows, but a bare GLFW context still gives the 3D layer a GL device to test.
            return api == RenderAPI.OPENGL ? runHeadlessGl(viewportSize, setup, checks, frames)
                    : new Run(false, api + " does not exist on this platform", null, null, Map.of());
        }
        Recorder recorder = new Recorder();
        String[] info = new String[2];
        boolean[] rendered = new boolean[1];
        RenderAPI[] actual = new RenderAPI[1];
        Throwable[] startError = new Throwable[1];

        Window window = new Window(new WindowOptions("RenGL GPU test").setDimensions(viewportSize + 40, viewportSize + 40)
                .setRenderer(api).setVsync(false).setSplashEnabled(false));
        window.hide();
        Container root = new Container(viewportSize + 40, viewportSize + 40);
        window.addContainer(root);

        Viewport3D viewport = new Viewport3D(viewportSize, viewportSize);
        viewport.setPosition(20, 20);
        setup.accept(viewport);
        Viewport3D.Content inner = viewport.getContent();
        viewport.setContent(new Viewport3D.Content() {
            int frame;

            @Override
            public void init(GpuDevice device) {
                if (inner != null) inner.init(device);
            }

            @Override
            public void resize(GpuDevice device, int width, int height) {
                if (inner != null) inner.resize(device, width, height);
            }

            @Override
            public void render(GpuDevice device, RenderTarget target, float delta) {
                rendered[0] = true;
                actual[0] = device.api();
                info[0] = device.name();
                if (inner != null) inner.render(device, target, delta);
                if (++frame == frames) {
                    try {
                        checks.run(device, target, recorder);
                    } catch (Throwable t) {
                        recorder.results.put("checks ran to the end", t.toString());
                    }
                    window.close();
                }
            }

            @Override
            public void dispose(GpuDevice device) {
                if (inner != null) inner.dispose(device);
            }
        });
        root.addElement(viewport);

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(10_000);
                if (!rendered[0]) {
                    window.close(); // nothing drew in 10 s: no display, or the window fell back to an API with no 3D layer
                    return;
                }
                Thread.sleep(20_000);
                window.close();
            } catch (InterruptedException ignored) {
            }
        }, "gpu-test-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            new Engine().start(window);
        } catch (Throwable t) {
            startError[0] = t;
        } finally {
            watchdog.interrupt();
        }

        if (startError[0] != null) {
            return new Run(false, "the engine could not start: " + startError[0], null, null, Map.of());
        }
        if (!rendered[0]) {
            return new Run(false, "the window never drew a 3D frame (no display, or no " + api + " device)", null, null, Map.of());
        }
        return new Run(true, null, info[0], actual[0], recorder.results);
    }

    /**
     * The same checks on an OpenGL 3.3 device made from a hidden GLFW window, without the engine. The engine's own OpenGL
     * path doesn't exist on macOS, but the 3D layer's GL backend does, and this is how it gets tested there. The content is
     * drawn six times into a target of the viewport's size, then the checks run, as in a real window.
     */
    private static Run runHeadlessGl(int viewportSize, Consumer<Viewport3D> setup, Checks checks, int frames) {
        Viewport3D viewport = new Viewport3D(viewportSize, viewportSize);
        setup.accept(viewport);
        Viewport3D.Content content = viewport.getContent();
        if (content == null) return new Run(false, "no content", null, null, Map.of());
        if (!org.lwjgl.glfw.GLFW.glfwInit()) return new Run(false, "GLFW could not start", null, null, Map.of());
        long window = 0;
        try {
            org.lwjgl.glfw.GLFW.glfwDefaultWindowHints();
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE, org.lwjgl.glfw.GLFW.GLFW_FALSE);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE, org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT, org.lwjgl.glfw.GLFW.GLFW_TRUE);
            window = org.lwjgl.glfw.GLFW.glfwCreateWindow(viewportSize, viewportSize, "RenGL GL test", 0, 0);
            if (window == 0) return new Run(false, "no OpenGL 3.3 context", null, null, Map.of());
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
            org.lwjgl.opengl.GL.createCapabilities();

            long[] frame = {0};
            org.lwjgl.opengl.GL.createCapabilities();
            var device = new me.piitex.engine.render3d.gl.GlDevice(() -> frame[0]);
            Recorder recorder = new Recorder();
            int samples = Math.min(Integer.highestOneBit(viewport.getSamples()), device.maxSamples());
            RenderTarget target = device.createRenderTarget(viewportSize, viewportSize, true, samples);
            try {
                content.init(device);
                content.resize(device, viewportSize, viewportSize);
                for (int i = 0; i < frames; i++) {
                    frame[0]++;
                    device.beginFrame();
                    try {
                        content.render(device, target, 1f / 60f);
                    } finally {
                        device.endFrame();
                    }
                }
                try {
                    checks.run(device, target, recorder);
                } catch (Throwable t) {
                    recorder.results.put("checks ran to the end", t.toString());
                }
            } finally {
                content.dispose(device);
                target.close();
                device.close();
            }
            return new Run(true, null, device.name(), RenderAPI.OPENGL, recorder.results);
        } catch (Throwable t) {
            return new Run(false, "OpenGL could not run: " + t, null, null, Map.of());
        } finally {
            if (window != 0) org.lwjgl.glfw.GLFW.glfwDestroyWindow(window);
            org.lwjgl.glfw.GLFW.glfwTerminate();
        }
    }
}
