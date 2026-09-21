package me.piitex.engine;

import me.piitex.engine.gl.GLFWindow;
import me.piitex.engine.gl.NativeMenuBar;
import me.piitex.engine.gl.renderer.metal.MetalContext;
import me.piitex.engine.gl.renderer.metal.MetalSkikoRenderer;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.gl.renderer.SkikoRenderer;
import me.piitex.engine.input.Clipboard;
import me.piitex.engine.input.CursorManager;
import me.piitex.engine.input.FileDialog;
import me.piitex.engine.input.InputProcessor;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.text.TooltipManager;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.utils.SystemInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

public class Engine {
    private static final Logger log = LogManager.getLogger("Engine");
    private Window window;
    private GLFWindow glfWindow;
    private Renderer renderer;
    private volatile boolean running = true;
    private final InputProcessor inputProcessor = new InputProcessor();

    // Thread synchronization flags
    private volatile boolean firstFrameRendered = false;
    private boolean windowVisible = false;

    private final boolean mac = System.getProperty("os.name").toLowerCase().contains("mac");
    private MetalContext metalContext;
    private int logicalW, logicalH, fbW, fbH;
    private volatile boolean sizeDirty = false;
    private volatile boolean focusLost = false;

    private long lastFrameTimeNanos;
    private final FrameLimiter frameLimiter = new FrameLimiter();
    private boolean appliedVsync;

    /** With vsync on, a frame faster than this can't have waited on the display (e.g. the window is minimised/occluded and no drawable was available), so the cap is applied as a fallback. */
    private static final long IMPLAUSIBLY_FAST_FRAME_NANOS = 2_000_000L;

    public void start(Window window) {
        log.info("Starting engine...");
        this.window = window;
        window.initializeGLFW();
        WindowOptions options = window.getWindowOptions();
        glfWindow = window.getGlfWindow();
        long windowHandle = glfWindow.createWindow(options.getWidth(), options.getHeight(), options.getTitle());
        glfWindow.applyOptions(options);
        Clipboard.attach(windowHandle);
        CursorManager.attach(windowHandle);

        // Main thread only: AppKit, and GLFW's size queries.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            IntBuffer bw = stack.mallocInt(1), bh = stack.mallocInt(1);
            glfwGetWindowSize(windowHandle, w, h);
            glfwGetFramebufferSize(windowHandle, bw, bh);
            logicalW = w.get(0); logicalH = h.get(0);
            fbW = bw.get(0);     fbH = bh.get(0);
        }
        // The window may not be the size that was asked for: fullscreen changes it, and the OS can shrink one
        // that doesn't fit the screen. WindowOptions always holds the real size.
        if (!options.isFullscreen() && (logicalW != options.getWidth() || logicalH != options.getHeight())) {
            log.warn("Window opened at {}x{} instead of the requested {}x{} (adjusted by the OS).",
                    logicalW, logicalH, options.getWidth(), options.getHeight());
        }
        options.setDimensions(logicalW, logicalH);

        String gpu;
        if (mac) {
            metalContext = new MetalContext(glfWindow.getWindow());
            gpu = SystemInfo.gpuFromMetalDevice(metalContext.getDevice());
        } else {
            glfWindow.initOpenGLContext();
            gpu = SystemInfo.gpuFromGL();
        }
        SystemInfo.collect(gpu).log();
        log.info("VSYNC: {}", options.isVsync());
        log.info("Max-FPS: {}", options.getMaxFPS());

        // Window size changed in screen coordinates (user drag-resized the border)
        glfwSetWindowSizeCallback(windowHandle, (w, newW, newH) -> {
            logicalW = newW;
            logicalH = newH;
            options.setDimensions(newW, newH); // keep WindowOptions in step with the real size (mouse clamping, modal centering, etc.)
            sizeDirty = true;
        });

        glfwSetWindowFocusCallback(windowHandle, (w, focused) -> {
            if (!focused) focusLost = true;
        });

        // callback: just record the size, do not mutate the layer
        glfwSetFramebufferSizeCallback(windowHandle, (w, newW, newH) -> {
            fbW = newW;
            fbH = newH;
            sizeDirty = true;
        });

        Thread renderThread = new Thread(() -> renderLoop(options), "Render-Thread");
        renderThread.start();

        while (running && !glfwWindowShouldClose(windowHandle)) {
            if (firstFrameRendered && !windowVisible) {
                if (!window.isHidden()) glfWindow.showWindow(); // hide() before the first frame keeps it hidden
                windowVisible = true;
            }
            glfwWaitEventsTimeout(0.001);

            glfWindow.pollEventsHighFrequency();
            Clipboard.pump();
            CursorManager.pump();
            NativeMenuBar.pump();
            FileDialog.pump();
            Thread.onSpinWait();
        }

        running = false;

        // The window is going away however the loop ended (close button, Window.close(), Engine stop). Let the
        // render thread finish its last frame first, so the close listener can touch the UI model without racing it.
        try {
            renderThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        glfWindow.applyHide(); // cleanup may take a while; don't leave a frozen window on screen
        window.notifyClosing();

        FileDialog.shutdown();
        Scheduler.shutdown();
    }

    private void renderLoop(WindowOptions options) {
        if (mac) {
            MetalSkikoRenderer metalRenderer = new MetalSkikoRenderer(metalContext);
            metalRenderer.init();
            metalRenderer.onResize(logicalW, logicalH, fbW, fbH);
            renderer = metalRenderer;
        } else {
            glfWindow.initOpenGLContext();
            renderer = new SkikoRenderer(glfWindow.getWindow());
        }

        applyVsync(options.isVsync());

        lastFrameTimeNanos = System.nanoTime();

        while (running) {
            long now = System.nanoTime();
            float delta = (now - lastFrameTimeNanos) / 1_000_000_000f;
            lastFrameTimeNanos = now;

            // Guard against a huge delta after a debugger pause, GC stall, or the
            // very first frame. Without this, onUpdate consumers doing physics or
            // animation math can see a multi-second jump and misbehave.
            delta = Math.min(delta, 0.25f);

            boolean vsync = options.isVsync();
            if (vsync != appliedVsync) {
                applyVsync(vsync);
            }

            if (sizeDirty) {
                if (mac) {
                    metalContext.resize(fbW, fbH);
                    ((MetalSkikoRenderer) renderer).onResize(logicalW, logicalH, fbW, fbH);
                } else {
                    ((SkikoRenderer) renderer).recreateSurface(glfWindow.getWindow());
                }
                sizeDirty = false;
            }

            if (focusLost) {
                focusLost = false;
                if (options.isHandleFocus()) {
                    window.requestFocus(null); // OS window lost focus: drop keyboard focus so carets/selections don't linger
                }
            }

            inputProcessor.processEvents(window);

            Paint background = window.getBackgroundColor();
            if (background instanceof AnimatedPaint animated) {
                animated.advance(delta);
            }

            NativeMenuBar.drainActions(); // callbacks of items chosen in the macOS system menu bar
            FileDialog.drainCallbacks();  // results of native open/save dialogs
            Scheduler.update(delta);      // runLater callbacks, timers, and async results

            // Update pass: fires onUpdate for every enabled container and element
            for (Map.Entry<Integer, Container> entry : window.getContainers().entrySet()) {
                Container container = entry.getValue();
                if (container.isEnabled()) {
                    container.update(delta);
                }
            }

            TooltipManager.update(window, delta);

            // A plain Color is cleared directly, which is cheap and needs no extra draw
            // call. A gradient cannot be, since Skia's canvas clear takes only one flat
            // color, so it is drawn as a full-window quad first thing after begin().
            // An opaque black clear goes underneath it so no pixel is left undefined at
            // the very edges before that quad covers the whole canvas.
            if (background instanceof Color solid) {
                renderer.clear(solid.getR(), solid.getG(), solid.getB(), solid.getA());
            } else {
                renderer.clear(0f, 0f, 0f, 1f);
            }

            renderer.begin();

            if (background instanceof LinearGradient gradient) {
                // Passing gradient itself, not its unpacked colors, is what lets a
                // ScrollingGradient window background actually scroll.
                renderer.drawGradient(0f, 0f, window.getWindowOptions().getWidth(), window.getWindowOptions().getHeight(),
                        gradient, 0f);
            }

            for (Map.Entry<Integer, Container> entry : window.getContainers().entrySet()) {
                Container container = entry.getValue();
                if (container.isEnabled()) {
                    container.renderWithTransitions(renderer);
                }
            }
            TooltipManager.render(renderer, window); // above every container
            renderer.end();

            if (!mac) glfwSwapBuffers(glfWindow.getWindow());

            if (!firstFrameRendered) firstFrameRendered = true;

            // Vsync off: this loop is the only thing pacing frames, so hold it to maxFPS.
            // Vsync on: the display paces it, except when that isn't happening (see the constant).
            if (!vsync || System.nanoTime() - now < IMPLAUSIBLY_FAST_FRAME_NANOS) {
                frameLimiter.limit(options.getMaxFPS());
            }
        }
    }

    /** Render thread. Applies vsync to whichever backend is in use. */
    private void applyVsync(boolean vsync) {
        if (mac) {
            metalContext.setVsync(vsync);
        } else {
            glfWindow.setVsync(vsync);
        }
        appliedVsync = vsync;
        frameLimiter.reset(); // pacing changed, so don't carry a deadline from the old mode
    }
}