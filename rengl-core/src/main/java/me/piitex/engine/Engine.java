package me.piitex.engine;

import me.piitex.engine.game.GameTime;
import me.piitex.engine.gl.GLFWindow;
import me.piitex.engine.gl.NativeMenuBar;
import me.piitex.engine.gl.renderer.metal.MetalContext;
import me.piitex.engine.gl.renderer.metal.MetalSkikoRenderer;
import me.piitex.engine.gl.renderer.RenderBackend;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.gl.renderer.SkikoRenderer;
import me.piitex.engine.input.Clipboard;
import me.piitex.engine.input.CursorManager;
import me.piitex.engine.input.FileDialog;
import me.piitex.engine.input.Gamepads;
import me.piitex.engine.input.ImeManager;
import me.piitex.engine.input.InputProcessor;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.input.OpenFiles;
import me.piitex.engine.input.SystemAppearance;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.background.Background;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import me.piitex.engine.utils.flags.GeneratedFragments;

/**
 * Runs the application: opens its windows, and every frame handles their input, runs their scenes and containers,
 * and draws them.
 * <pre>{@code
 * Window window = new Window(new WindowOptions("My Game"));
 * // build the UI...
 * new Engine().start(window); // returns when the window closes
 * }</pre>
 * <b>Several windows.</b> More windows open with {@link #open(Window)} or {@link Window#open()}, from any thread,
 * before or after {@code start}. Each has its own containers, scenes, input and focus, and closing one closes only
 * it. Closing the first window, the one given to {@code start}, closes the others and ends the engine.
 * <p>
 * All windows share one render thread, which draws them one after another each frame, and one {@link GameTime}
 * clock, so pausing game time pauses the scenes of every window. The frame rate follows the pacing window's
 * {@link WindowOptions#setVsync vsync} and {@link WindowOptions#setMaxFPS max FPS} settings: the first window, unless
 * {@link #setPacingWindow} picks another.
 * <p>
 * One engine runs per process.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class Engine {
    private static final Logger log = LogManager.getLogger("Engine");
    private static volatile Engine current;
    /** Cleanup that optional modules (audio, ...) register, run when the engine ends. */
    private static final List<Runnable> shutdownHooks = new CopyOnWriteArrayList<>();

    private final List<Host> hosts = new CopyOnWriteArrayList<>();
    private final Queue<Window> toOpen = new ConcurrentLinkedQueue<>();
    private final Queue<Window> toSwap = new ConcurrentLinkedQueue<>(); // windows whose graphics API was changed while open
    private volatile Host primary;
    private volatile Window pacingWindow; // null: the first window paces
    private volatile boolean running = true;
    private volatile boolean started;

    private long currentContext = NULL; // render thread: the window whose OpenGL context is current

    private long lastFrameTimeNanos;
    private final FrameLimiter frameLimiter = new FrameLimiter();

    /**
     * With vsync on, a frame faster than this can't have waited on the display (e.g. the window is minimised/occluded and no drawable was available), so the cap is applied as a fallback.
     */
    private static final long IMPLAUSIBLY_FAST_FRAME_NANOS = 2_000_000L;

    /**
     * How long the main thread waits for an OS event before it serves its queues anyway.
     */
    private static final double MAIN_LOOP_WAIT_SECONDS = 0.010;

    /**
     * The frame rate while every window is minimized.
     */
    private static final int MINIMIZED_FPS = 10;

    /**
     * One open OS window: the {@link Window} and everything native the engine keeps for it.
     */
    private static final class Host {
        final Window window;
        final GLFWindow glfWindow;
        final long handle;
        final RenderAPI backend;
        final RenderAPI requested;       // what WindowOptions asked for when the window was created
        final InputProcessor inputProcessor = new InputProcessor();
        MetalContext metalContext;   // Metal only; created on the main thread, closed there after the renderer is released
        RenderBackend.Context backendContext; // Vulkan only (a backend from its own module); same lifetime, but closed before the GLFW window is destroyed
        Renderer renderer;         // render thread
        SplashScreen splash;       // render thread; the RenGL splash over the first window, null once it has finished
        volatile int logicalW, logicalH, fbW, fbH;
        volatile boolean sizeDirty;
        volatile boolean focusLost;
        volatile boolean firstFrameRendered;
        boolean shown;                   // main thread
        Boolean appliedVsync;            // render thread; null until first applied
        volatile boolean closeRequested; // main thread -> render thread: stop drawing and release the renderer
        volatile boolean released;       // render thread -> main thread: the renderer is gone, destroy the window
        volatile boolean swapping;       // closeRequested is only to rebuild the window for another graphics API: it stays "open"
        int swapX, swapY;                // main thread: where the window was when it was torn down for a swap

        Host(Window window, GLFWindow glfWindow, long handle) {
            this.window = window;
            this.glfWindow = glfWindow;
            this.handle = handle;
            this.backend = glfWindow.getRenderAPI();
            this.requested = window.getWindowOptions().getRenderer();
        }
    }

    /**
     * Sets what happens when the operating system asks the application to open files: a {@code .zip} double-clicked
     * in Finder / Explorer when the application is registered for that type, "Open With", or a file dropped on the
     * Dock icon. The listener gets the paths on the render thread. Static, so it can be set before the engine exists.
     * Requests that came earlier (the OS can ask while the window is still opening) are delivered as soon as the
     * listener is set.
     * <p>
     * Windows and Linux pass these paths as {@code main}'s arguments, which macOS does not; call
     * {@link #openFilesFromArguments} with them so every platform arrives here:
     * <pre>{@code
     * public static void main(String[] args) {
     *     Engine.onOpenFiles(paths -> archive.open(paths.getFirst()));
     *     Engine.openFilesFromArguments(args);
     *     new Engine().start(window);
     * }
     * }</pre>
     * Pass null to remove the listener. The packaging guide (docs/reference/packaging.md) covers registering file types.
     */
    public static void onOpenFiles(Consumer<List<Path>> listener) {
        OpenFiles.onOpenFiles(listener);
    }

    /**
     * Delivers the {@code main} arguments that name an existing file or folder to {@link #onOpenFiles}, as one
     * request. Options and other arguments are ignored. Safe from any thread, before or after {@link #start}.
     */
    public static void openFilesFromArguments(String[] args) {
        OpenFiles.fromArguments(args);
    }

    /**
     * Whether the operating system is set to a light or a dark appearance right now: macOS "Appearance", Windows
     * "Choose your mode" or the GNOME / GTK dark setting. {@link SystemAppearance.Mode#LIGHT} when it cannot be
     * read. Use it to pick a theme at launch. It runs a short system command unless an
     * {@link #onSystemAppearanceChange listener} is set, so call it once, not every frame. Static, so it works before
     * the engine exists.
     */
    public static SystemAppearance.Mode getSystemAppearance() {
        return SystemAppearance.get();
    }

    /**
     * Calls {@code listener} with the new appearance on the render thread when the user switches the system between
     * light and dark while the application runs. It is not called for the appearance the application starts under; read
     * that with {@link #getSystemAppearance()}. The system is checked every couple of seconds, so a change arrives
     * with that delay. Pass null to remove the listener. For example:
     * <pre>{@code
     * Engine.onSystemAppearanceChange(mode ->
     *         ThemeManager.setCurrent(mode == SystemAppearance.Mode.DARK ? new DarkTheme() : new LightTheme()));
     * }</pre>
     */
    public static void onSystemAppearanceChange(Consumer<SystemAppearance.Mode> listener) {
        SystemAppearance.onChange(listener);
    }

    /**
     * The engine that is running, or null before {@link #start} and after it returns.
     */
    public static Engine getRunning() {
        return current;
    }

    /**
     * Opens {@code window} and runs the engine until it closes. This is the first window: closing it closes every
     * other window and ends the engine. Must be called on the main thread (the first thread, with
     * {@code -XstartOnFirstThread} on macOS), which it keeps until it returns.
     *
     * @throws IllegalStateException if an engine is already running
     */
    public void start(Window window) {
        synchronized (Engine.class) {
            if (current != null) throw new IllegalStateException("An engine is already running; open more windows with Engine.open");
            current = this;
        }
        try {
            run(window);
        } finally {
            current = null;
        }
    }

    private void run(Window window) {
        log.info("Starting engine...");
        started = true;
        OpenFiles.install(); // before GLFW starts, so macOS can hand over the file the app was launched to open
        Host main = createHost(window);
        primary = main;
        main.splash = new SplashScreen(); // plays over the first window before the application appears
        Clipboard.attach(main.handle); // GLFW's clipboard is system-wide; any window's handle will do

        String gpu;
        switch (main.backend) {
            case METAL -> gpu = SystemInfo.gpuFromMetalDevice(main.metalContext.getDevice());
            case VULKAN -> gpu = main.backendContext.gpuName();
            default -> {
                main.glfWindow.initOpenGLContext();
                gpu = SystemInfo.gpuFromGL();
                glfwMakeContextCurrent(NULL); // the render thread takes the context over; it can't be current on two threads
            }
        }
        SystemInfo.collect(gpu).log();
        log.info("VSYNC: {}", window.getWindowOptions().isVsync());
        log.info("Max-FPS: {}", window.getWindowOptions().getMaxFPS());

        Thread renderThread = new Thread(this::renderLoop, "Render-Thread");
        renderThread.start();

        while (running && !glfwWindowShouldClose(primary.handle)) {
            Window swap;
            while ((swap = toSwap.poll()) != null) beginSwap(swap);
            Window next;
            while ((next = toOpen.poll()) != null) {
                try {
                    createHost(next);
                } catch (RuntimeException e) {
                    log.error("Could not open window \"{}\"", next.getWindowOptions().getTitle(), e);
                }
            }
            for (Host host : hosts) {
                if (host.firstFrameRendered && !host.shown) {
                    if (!host.window.isHidden()) host.glfWindow.showWindow(); // hide() before the first frame keeps it hidden
                    host.shown = true;
                }
            }
            // Wakes at once for any OS event; the timeout only bounds how late the queues polled below are served.
            // At 1 ms this thread woke a thousand times a second doing nothing, which keeps the CPU out of its idle states.
            glfwWaitEventsTimeout(MAIN_LOOP_WAIT_SECONDS);

            glfwPollEvents();
            Gamepads.pump(); // GLFW only talks to controllers from this thread; each window's Input takes the reading
            for (Host host : hosts) {
                host.glfWindow.runMainThreadTasks();
                if (host.swapping && glfwWindowShouldClose(host.handle)) host.swapping = false; // closed meanwhile: a plain close
                if (host == primary && !host.swapping) continue;
                if (!host.closeRequested && glfwWindowShouldClose(host.handle)) host.closeRequested = true;
                if (host.released) destroyHost(host);
            }
            Clipboard.pump();
            CursorManager.pump();
            ImeManager.pump();
            NativeMenuBar.pump();
            FileDialog.pump();
        }

        running = false;

        // The windows are going away however the loop ended (close button, Window.close(), Engine stop). Let the
        // render thread finish its last frame first, so the close listeners can touch the UI model without racing it.
        try {
            renderThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Cleanup may take a while; don't leave frozen windows on screen. Other windows close before the first.
        for (Host host : hosts) host.glfWindow.applyHide();
        main = primary; // the first window may have been rebuilt for another graphics API
        for (Host host : hosts) {
            if (host != main) host.window.notifyClosing();
        }
        main.window.notifyClosing();

        FileDialog.shutdown();
        // The windows still open (the first one always is) never reach destroyHost. Destroy them now, or each engine run
        // leaves its GLFW window and its Vulkan instance and device behind: harmless when the process exits next, but a
        // JVM that starts the engine many times (the test suites) runs the GPU driver out of resources after ~50 runs.
        // A window whose renderer the render thread didn't get to release (it was stuck past the join) is left alone.
        for (Host host : hosts) {
            if (!host.released) continue;
            CursorManager.detach(host.handle);
            ImeManager.detach(host.handle);
            if (host.backendContext != null) host.backendContext.close(); // its surface belongs to the GLFW window
            host.glfWindow.destroy();
            if (host.metalContext != null) host.metalContext.close();
            hosts.remove(host);
        }
        Gamepads.shutdown();
        for (Runnable hook : shutdownHooks) {
            try {
                hook.run();
            } catch (Throwable t) {
                log.warn("A shutdown hook failed", t);
            }
        }
        Scheduler.shutdown();
    }

    /**
     * Registers cleanup to run when the engine ends, after the windows have closed. This is how a module that owns a
     * native resource (the audio device, for one) is closed without the core knowing about it. Adding the same
     * hook twice does nothing, and a hook stays registered for later engines in the same process, so it should be safe
     * to run when there is nothing to clean up. A hook that throws is logged and does not stop the others.
     *
     * @param hook what to run
     */
    public static void addShutdownHook(Runnable hook) {
        if (hook != null && !shutdownHooks.contains(hook)) shutdownHooks.add(hook);
    }

    /**
     * Opens another window. Safe from any thread, and before {@link #start}: the window then opens right after the
     * first. It opens on the next pass of the main thread's loop and shows once its first frame is drawn. Opening a
     * window that is already open does nothing. A window that was closed can be opened again.
     */
    public void open(Window window) {
        if (window == null) throw new IllegalArgumentException("window must not be null");
        if (started && !running) throw new IllegalStateException("The engine has stopped");
        for (Host host : hosts) {
            if (host.window == window && (!host.closeRequested || host.swapping)) return;
        }
        if (!toOpen.contains(window)) toOpen.add(window);
    }

    /**
     * Called when an open window's {@link WindowOptions#setRenderer graphics API} was changed. A window is made for
     * one API, so the engine closes its OS window and opens it again with the new one; the {@link Window} itself, its
     * containers and scenes stay as they are and no close listener runs. Safe from any thread. A window that is not
     * open just uses the new API when it opens.
     *
     * @throws IllegalStateException when the API is OpenGL on macOS (see {@link RenderAPI#resolve})
     */
    void changeRenderer(Window window) {
        for (Host host : hosts) {
            if (host.window != window) continue;
            if (host.closeRequested && !host.swapping) return; // closing anyway; it opens with the new API next time
            RenderAPI.resolve(window.getWindowOptions().getRenderer()); // throws here, on the caller's thread
            toSwap.add(window);
            return;
        }
    }

    /**
     * Main thread. Starts rebuilding a window for its new graphics API: the render thread releases the old renderer,
     * then {@link #destroyHost} makes the new window.
     */
    private void beginSwap(Window window) {
        for (Host host : hosts) {
            if (host.window != window || host.closeRequested) continue;
            RenderAPI wanted = window.getWindowOptions().getRenderer();
            if (wanted == host.requested || RenderAPI.resolve(wanted) == host.backend) return;
            log.info("Switching \"{}\" from {} to {}", window.getWindowOptions().getTitle(), host.backend, wanted);
            host.swapping = true;
            host.closeRequested = true;
            return;
        }
    }

    /**
     * Main thread. Opens the window again after its old OS window was destroyed for a graphics API change, where the
     * old one was. If the new API can't start, the old one is restored.
     */
    private void recreate(Host old) {
        Window window = old.window;
        Host next;
        try {
            next = createHost(window);
        } catch (RuntimeException e) {
            log.error("Could not switch \"{}\" to {}; going back to {}", window.getWindowOptions().getTitle(),
                    window.getWindowOptions().getRenderer(), old.requested, e);
            window.getWindowOptions().restoreRenderer(old.requested);
            try {
                next = createHost(window);
            } catch (RuntimeException again) {
                if (old == primary) running = false;
                log.error("Could not reopen \"{}\"", window.getWindowOptions().getTitle(), again);
                if (old == primary) throw again;
                return;
            }
        }
        if (!window.getWindowOptions().isFullscreen()) next.glfWindow.applyPosition(old.swapX, old.swapY);
        if (old == primary) {
            primary = next;
            Clipboard.attach(next.handle);
        }
    }

    /**
     * Makes {@code window} pace the frame loop: it alone waits for the display when its vsync is on, and its max FPS
     * caps the loop, while the other windows are drawn at its rate. An editor sets this to the game window it opens
     * on Play, so the game runs at its own settings. {@code null}, or the window closing, hands pacing back to the
     * first window. Safe from any thread; it takes effect on the next frame.
     */
    public void setPacingWindow(Window window) {
        pacingWindow = window;
    }

    /**
     * The window pacing the frame loop now: the one given to {@link #setPacingWindow} while it is open, otherwise the
     * first window.
     */
    public Window getPacingWindow() {
        Host host = pacingHost();
        return host == null ? null : host.window;
    }

    /**
     * The host whose settings pace the loop: the chosen pacing window while it is open, else the first window.
     */
    private Host pacingHost() {
        Window chosen = pacingWindow;
        if (chosen != null) {
            for (Host host : hosts) {
                if (host.window == chosen && !host.closeRequested) return host;
            }
        }
        return primary;
    }

    /**
     * The windows open now, the first one first. Windows asked for with {@link #open} appear once they have opened,
     * and closed ones are gone.
     */
    public List<Window> getWindows() {
        List<Window> windows = new ArrayList<>();
        for (Host host : hosts) {
            if (!host.closeRequested || host.swapping) windows.add(host.window);
        }
        return windows;
    }

    /**
     * Main thread. Creates the native window for {@code window} and starts tracking it; the render thread sets up its
     * renderer on its next frame.
     */
    private Host createHost(Window window) {
        window.initializeGLFW();
        WindowOptions options = window.getWindowOptions();
        GLFWindow glfWindow = window.getGlfWindow();
        long windowHandle = glfWindow.createWindow(options.getWidth(), options.getHeight(), options.getTitle());
        glfWindow.applyOptions(options);
        Host host = new Host(window, glfWindow, windowHandle);
        CursorManager.attach(windowHandle);
        ImeManager.attach(windowHandle);

        // Main thread only: AppKit, and GLFW's size queries.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            IntBuffer bw = stack.mallocInt(1), bh = stack.mallocInt(1);
            glfwGetWindowSize(windowHandle, w, h);
            glfwGetFramebufferSize(windowHandle, bw, bh);
            host.logicalW = w.get(0);
            host.logicalH = h.get(0);
            host.fbW = bw.get(0);
            host.fbH = bh.get(0);
        }
        // The window may not be the size that was asked for: fullscreen changes it, and the OS can shrink one
        // that doesn't fit the screen. WindowOptions always holds the real size.
        if (!options.isFullscreen() && (host.logicalW != options.getWidth() || host.logicalH != options.getHeight())) {
            log.warn("Window opened at {}x{} instead of the requested {}x{} (adjusted by the OS).",
                    host.logicalW, host.logicalH, options.getWidth(), options.getHeight());
        }
        options.markOpened();
        boolean openedAtOtherSize = host.logicalW != options.getWidth() || host.logicalH != options.getHeight();
        options.syncDimensions(host.logicalW, host.logicalH);

        switch (host.backend) {
            case METAL -> host.metalContext = new MetalContext(windowHandle);
            case VULKAN -> {
                try {
                    host.backendContext = RenderBackend.find(RenderAPI.VULKAN).createContext(windowHandle);
                } catch (RuntimeException e) {
                    // Past the startup probe the device still failed (no surface support, out of memory): the window
                    // is already a context-less one, so there is nothing to fall back to.
                    glfWindow.destroy();
                    throw new IllegalStateException("Could not start Vulkan for the window: " + e.getMessage(), e);
                }
            }
            default -> { }
        }

        // Window size changed in screen coordinates (user drag-resized the border)
        glfwSetWindowSizeCallback(windowHandle, (w, newW, newH) -> {
            host.logicalW = newW;
            host.logicalH = newH;
            options.syncDimensions(newW, newH); // keep WindowOptions in step with the real size (mouse clamping, modal centering, etc.)
            host.sizeDirty = true;
            window.notifyResized(newW, newH);
        });

        window.getInputManager().refreshKeyNames();
        glfwSetWindowFocusCallback(windowHandle, (w, focused) -> {
            window.notifyFocused(focused);
            if (!focused) host.focusLost = true;
            else window.getInputManager().refreshKeyNames(); // the keyboard layout may have changed while away
        });

        // callback: just record the size, do not mutate the layer
        glfwSetFramebufferSizeCallback(windowHandle, (w, newW, newH) -> {
            host.fbW = newW;
            host.fbH = newH;
            host.sizeDirty = true;
        });

        // A window that opened maximized (or shrunk to fit) never gets a size callback for it, so tell the listeners.
        if (openedAtOtherSize) window.notifyResized(host.logicalW, host.logicalH);

        hosts.add(host);
        return host;
    }

    /**
     * Main thread, once the render thread has released the window's renderer: tells the window it closed and destroys
     * the native window.
     */
    private void destroyHost(Host host) {
        hosts.remove(host);
        host.glfWindow.applyHide();
        if (host.swapping) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer x = stack.mallocInt(1), y = stack.mallocInt(1);
                glfwGetWindowPos(host.handle, x, y);
                host.swapX = x.get(0);
                host.swapY = y.get(0);
            }
        } else {
            host.window.notifyClosing();
        }
        CursorManager.detach(host.handle);
        ImeManager.detach(host.handle);
        if (host.backendContext != null) host.backendContext.close(); // its surface belongs to the GLFW window
        host.glfWindow.destroy();
        if (host.metalContext != null) host.metalContext.close();
        if (host.swapping) recreate(host);
    }

    private void renderLoop() {
        // If the render thread dies (a renderer that can't start, an exception in a frame), the main thread would
        // keep polling a window that never draws and the program would hang with nothing on screen. Stop instead.
        try {
            runFrames();
        } catch (Throwable t) {
            log.error("The render thread stopped", t);
            running = false;
            glfwPostEmptyEvent(); // wake the main thread out of glfwWaitEventsTimeout
            throw t;
        }
    }

    private void runFrames() {
        lastFrameTimeNanos = System.nanoTime();
        GameTime.reset();
        List<Host> live = new ArrayList<>();

        while (running) {
            long now = System.nanoTime();
            long frameNanos = now - lastFrameTimeNanos;
            float delta = frameNanos / 1_000_000_000f;
            lastFrameTimeNanos = now;

            // Guard against a huge delta after a debugger pause, GC stall, or the
            // very first frame. Without this, onUpdate consumers doing physics or
            // animation math can see a multi-second jump and misbehave.
            delta = Math.min(delta, 0.25f);

            live.clear();
            for (Host host : hosts) {
                if (host.closeRequested) {
                    if (!host.released) release(host);
                    continue;
                }
                if (host.renderer == null) initRenderer(host);
                live.add(host);
            }

            // Per window: surface size, focus, input, and animated backdrops
            for (Host host : live) {
                Window window = host.window;
                select(host);
                window.recordFrame(frameNanos);
                applyVsync(host);

                if (host.sizeDirty) {
                    switch (host.backend) {
                        case METAL -> {
                            host.metalContext.resize(host.fbW, host.fbH);
                            ((MetalSkikoRenderer) host.renderer).onResize(host.logicalW, host.logicalH, host.fbW, host.fbH);
                        }
                        case VULKAN -> ((RenderBackend.BackendRenderer) host.renderer).onResize(host.logicalW, host.logicalH);
                        default -> {
                            makeContextCurrent(host);
                            ((SkikoRenderer) host.renderer).recreateSurface(host.handle);
                        }
                    }
                    host.sizeDirty = false;
                }

                if (host.focusLost) {
                    host.focusLost = false;
                    if (window.getWindowOptions().isHandleFocus()) {
                        window.requestFocus(null); // OS window lost focus: drop keyboard focus so carets/selections don't linger
                    }
                }

                SplashScreen splash = host.splash;
                if (splash != null) {
                    splash.advance(delta);
                    if (splash.finished()) {
                        splash.dispose();
                        host.splash = null;
                    }
                }
                if (holdsApp(host)) {
                    // The application hasn't appeared yet: what the user types or clicks meant for it is dropped.
                    window.getInputManager().getEventQueue().clear();
                    continue;
                }
                host.inputProcessor.processEvents(window);

                if (window.getBackgroundColor() instanceof AnimatedPaint animated) {
                    animated.advance(delta);
                }
                Background windowBackground = window.getBackground();
                if (windowBackground != null) {
                    windowBackground.update(delta, 0f, 0f, window.getWindowOptions().getWidth(), window.getWindowOptions().getHeight());
                }
            }

            NativeMenuBar.drainActions(); // callbacks of items chosen in the macOS system menu bar
            FileDialog.drainCallbacks();  // results of native open/save dialogs
            OpenFiles.drain();            // files the OS asked the application to open
            SystemAppearance.drain();     // the user switched the system between light and dark
            Scheduler.update(delta);      // runLater callbacks, timers, and async results

            // Game time: every window's scenes take the fixed steps (as many as this frame covers), then their
            // per-frame update
            GameTime.advance(delta, () -> {
                for (Host host : live) {
                    if (holdsApp(host)) continue;
                    select(host);
                    host.window.getSceneManager().fixedUpdate();
                }
            });
            for (Host host : live) {
                if (holdsApp(host)) continue;
                select(host);
                host.window.getSceneManager().update(GameTime.getDelta());
            }

            for (Host host : live) {
                select(host);
                updateAndDraw(host, delta);
            }

            // Vsync off: this loop is the only thing pacing frames, so hold it to maxFPS.
            // Vsync on: the display paces it, except when that isn't happening (see the constant).
            Host pacer = pacingHost();
            WindowOptions pacing = pacer != null ? pacer.window.getWindowOptions() : live.isEmpty() ? null : live.get(0).window.getWindowOptions();
            if (!live.isEmpty() && live.stream().allMatch(h -> h.window.isMinimized())) {
                frameLimiter.limit(MINIMIZED_FPS); // nothing is visible, so don't redraw at full rate
            } else if (pacing != null && pacing.getBackgroundFPS() > 0 && live.stream().noneMatch(h -> h.window.isFocused())) {
                frameLimiter.limit(pacing.getBackgroundFPS()); // in the background: the display's rate isn't needed
            } else if (pacing != null && (!pacing.isVsync() || System.nanoTime() - now < IMPLAUSIBLY_FAST_FRAME_NANOS)) {
                frameLimiter.limit(pacing.getMaxFPS());
            }
        }

        // The engine is stopping. The windows still open (the first one always is) release their renderers here, on the
        // render thread, as a window closed on its own would, so a 3D viewport's content and GPU device are torn down
        // properly instead of being left to process exit.
        for (Host host : hosts) {
            if (!host.released) {
                try {
                    release(host);
                } catch (RuntimeException e) {
                    log.error("Could not release the renderer of the window \"{}\"", host.window.getWindowOptions().getTitle(), e);
                }
            }
        }
    }

    /**
     * Render thread. Runs one window's containers and draws it.
     */
    private void updateAndDraw(Host host, float delta) {
        Window window = host.window;
        Renderer renderer = host.renderer;

        boolean held = holdsApp(host); // the splash is up: the application isn't updated or drawn yet

        // Update pass: fires onUpdate for every enabled container and element
        if (!held) {
            for (Map.Entry<Integer, Container> entry : window.getContainers().entrySet()) {
                Container container = entry.getValue();
                if (container.isEnabled()) {
                    container.update(delta);
                }
            }

            window.refreshTransitionLock(); // after the update pass, so a transition started this frame already locks

            window.followPopupAnchor(); // after the update pass, so layouts have placed the anchor

            TooltipManager.update(window, delta);
        }

        if (host.backend == RenderAPI.OPENGL) makeContextCurrent(host);

        // A plain Color is cleared directly, which is cheap and needs no extra draw
        // call. A gradient cannot be, since Skia's canvas clear takes only one flat
        // color, so it is drawn as a full-window quad first thing after begin().
        // An opaque black clear goes underneath it so no pixel is left undefined at
        // the very edges before that quad covers the whole canvas.
        Paint background = AnimatedPaint.resolve(window.getBackgroundColor());
        if (held) {
            renderer.clear(0.027f, 0.035f, 0.075f, 1f); // the splash's own backdrop colour, so no frame flashes the window's
        } else if (background instanceof Color solid) {
            renderer.clear(solid.getR(), solid.getG(), solid.getB(), solid.getA());
        } else {
            renderer.clear(0f, 0f, 0f, 1f);
        }

        renderer.begin();

        int width = window.getWindowOptions().getWidth();
        int height = window.getWindowOptions().getHeight();
        if (!held && background instanceof LinearGradient gradient) {
            // Passing gradient itself, not its unpacked colors, is what lets a
            // ScrollingGradient window background actually scroll.
            renderer.drawGradient(0f, 0f, width, height, gradient, 0f);
        }

        if (!held) {
            Background windowBackground = window.getBackground();
            if (windowBackground != null) {
                windowBackground.render(renderer, 0f, 0f, width, height);
            }

            for (Map.Entry<Integer, Container> entry : window.getContainers().entrySet()) {
                Container container = entry.getValue();
                if (container.isEnabled()) {
                    container.renderWithTransitions(renderer);
                }
            }
            TooltipManager.render(renderer, window); // above every container
        }
        if (host.splash != null) host.splash.render(renderer, width, height, host.backend.name()); // over the application while it fades out
        renderer.end();

        if (host.backend == RenderAPI.OPENGL) glfwSwapBuffers(host.handle);

        if (!host.firstFrameRendered) host.firstFrameRendered = true;
    }

    /**
     * Whether the RenGL splash screen still holds this window's application back.
     */
    private static boolean holdsApp(Host host) {
        SplashScreen splash = host.splash;
        return splash != null && splash.holdsApp();
    }

    /**
     * Render thread. Points the static helpers that answer for "the window" (mouse position, cursor requests) at
     * this one.
     */
    private void select(Host host) {
        MouseTracker.select(host.window);
        CursorManager.select(host.handle);
        ImeManager.select(host.handle);
    }

    /**
     * Render thread. Sets up a newly opened window's renderer.
     */
    private void initRenderer(Host host) {
        switch (host.backend) {
            case METAL -> {
                MetalSkikoRenderer metalRenderer = new MetalSkikoRenderer(host.metalContext);
                metalRenderer.init();
                metalRenderer.onResize(host.logicalW, host.logicalH, host.fbW, host.fbH);
                host.renderer = metalRenderer;
            }
            case VULKAN -> host.renderer = host.backendContext.createRenderer(host.logicalW, host.logicalH);
            default -> {
                host.glfWindow.initOpenGLContext();
                currentContext = host.handle;
                host.renderer = new SkikoRenderer(host.handle);
            }
        }
        host.sizeDirty = false;
        applyVsync(host);
    }

    /**
     * Render thread. Frees a closing window's renderer, then lets the main thread destroy the window.
     */
    private void release(Host host) {
        if (host.renderer != null) {
            switch (host.backend) {
                case METAL -> ((MetalSkikoRenderer) host.renderer).dispose();
                case VULKAN -> ((RenderBackend.BackendRenderer) host.renderer).dispose();
                default -> {
                    makeContextCurrent(host);
                    ((SkikoRenderer) host.renderer).dispose();
                    glfwMakeContextCurrent(NULL); // the context is destroyed with its window, on the main thread
                    currentContext = NULL;
                }
            }
            host.renderer = null;
        }
        TooltipManager.forget(host.window);
        MouseTracker.forget(host.window);
        host.released = true;
    }

    private void makeContextCurrent(Host host) {
        if (currentContext != host.handle) {
            glfwMakeContextCurrent(host.handle);
            currentContext = host.handle;
        }
    }

    /**
     * Render thread. Applies a window's vsync setting when it changes. Only the pacing window (see
     * {@link #setPacingWindow}) waits for the display, and that wait paces the whole loop. With OpenGL each wait is a whole frame, so a second waiting window would
     * halve the frame rate; with Metal (and likewise Vulkan), several waiting layers pace the loop unevenly (between 60 and 120 frames a
     * second on a 120 Hz display). The other windows present as soon as they are drawn.
     */
    private void applyVsync(Host host) {
        boolean pacer = host == pacingHost();
        boolean vsync = host.window.getWindowOptions().isVsync() && pacer;
        if (host.appliedVsync != null && host.appliedVsync == vsync) return;
        switch (host.backend) {
            case METAL -> host.metalContext.setVsync(vsync);
            case VULKAN -> host.backendContext.setVsync(vsync);
            default -> {
                makeContextCurrent(host);
                host.glfWindow.setVsync(vsync);
            }
        }
        host.appliedVsync = vsync;
        if (pacer) frameLimiter.reset(); // pacing changed, so don't carry a deadline from the old mode
    }
}
