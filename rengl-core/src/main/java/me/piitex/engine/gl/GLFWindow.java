package me.piitex.engine.gl;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.WindowStyle;
import me.piitex.engine.gl.renderer.SkikoRenderer;
import me.piitex.engine.gl.renderer.RenderBackend;
import me.piitex.engine.input.FileDrag;
import me.piitex.engine.input.InputManager;
import me.piitex.engine.utils.flags.GeneratedFragments;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.jetbrains.skia.EncodedImageFormat;
import org.jetbrains.skia.Image;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Thin wrapper around a single GLFW window and its input callback wiring.
 * <p>
 * Which graphics API the window is made for is the {@link RenderAPI}, resolved once in the constructor
 * ({@link #getRenderAPI()}).
 * <p>
 * With {@link RenderAPI#OPENGL} (the default off macOS) this is a normal OpenGL-capable window. GLFW is
 * asked for a GL 3.3 core-profile context, and the render thread later calls
 * {@link #initOpenGLContext()} to bind it before drawing with {@link SkikoRenderer}.
 * <p>
 * With {@link RenderAPI#VULKAN} the window has no client API at all ({@code GLFW_NO_API}); the engine creates a
 * Vulkan surface for it instead, through the {@link RenderBackend} that the rengl-vulkan module provides.
 * <p>
 * On macOS the window is intentionally created with {@code GLFW_CLIENT_API =
 * GLFW_NO_API}, so GLFW backs it with a plain {@code NSView} rather than an
 * {@code NSOpenGLView}. This matters: {@code MetalContext} re-hosts that view's layer
 * with its own {@code CAMetalLayer} via {@code setLayer:}/{@code setWantsLayer:}, and
 * an {@code NSOpenGLView} does not tolerate being re-hosted that way. AppKit can detach
 * the custom layer during ordinary window lifecycle events, such as moving the window
 * between displays, leaving the surface rendering to nothing visible. Never bind an
 * OpenGL context on this window on macOS; there is none, and {@code MetalContext} is
 * used instead.
 * <p>
 * <b>Threading:</b> the constructor and {@link #createWindow} must run on the main
 * thread (GLFW/AppKit requirement). {@link #showWindow()} is also main-thread-only.
 * {@link #initOpenGLContext()} must run on the render thread, and only on non-Mac
 * platforms. {@link #pollEventsHighFrequency()} must run on the main thread, since it
 * is what delivers queued input and window events to their callbacks.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class GLFWindow {
    private static final Logger log = LogManager.getLogger("Engine");
    private static final boolean MAC = System.getProperty("os.name").toLowerCase().contains("mac");
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private long window = NULL;
    private final InputManager inputManager;
    private volatile Consumer<Boolean> iconifyHandler, maximizeHandler;
    private volatile java.util.function.BooleanSupplier closeRequestHandler;
    private volatile FileDropListener dropListener;
    private long nativeHandle;                      // main thread only: the Win32 window handle, 0 off Windows
    private WindowsDragDrop.Target dropTarget;     // main thread only: the OLE drop target, when the window has one
    private boolean fullscreen; // main thread only
    private boolean frameHiddenForFullscreen; // main thread only: fullscreen took the title bar off a STANDARD window
    private int windowedX, windowedY, windowedW, windowedH; // bounds to return to when leaving fullscreen
    private final ConcurrentLinkedQueue<Runnable> mainThreadTasks = new ConcurrentLinkedQueue<>();
    private final RenderAPI backend;

    /**
     * Initializes GLFW and sets up window hints for the platform this is running on.
     * Does not create a window yet. See {@link #createWindow}.
     *
     * @param inputManager receives all cursor/button/key callbacks registered in
     *                     {@link #createWindow}; the actual event queue draining
     *                     happens elsewhere (InputProcessor), not here.
     * @throws IllegalStateException if GLFW itself fails to initialize.
     */
    public GLFWindow(InputManager inputManager) {
        this(inputManager, RenderAPI.AUTO);
    }

    /**
     * Same as {@link #GLFWindow(InputManager)}, for a window that wants {@code requested}. What it really gets is
     * {@link #getRenderAPI()}: a backend the machine can't run (no Vulkan GPU, Metal off macOS) falls back to the
     * platform default.
     */
    public GLFWindow(InputManager inputManager, RenderAPI requested) {
        this.inputManager = inputManager;

        // GLFW_FORMAT_UNAVAILABLE is a normal, recoverable outcome of
        // glfwGetClipboardString (see me.piitex.engine.input.Clipboard) whenever the OS
        // clipboard does not currently hold plain text. It is not an error, so it is
        // filtered out here rather than printing a full stack trace to stderr.
        // Everything else goes through the same formatted print.
        GLFWErrorCallback printCallback = GLFWErrorCallback.createPrint(System.err);
        GLFWErrorCallback.create((error, description) -> {
            if (error != GLFW_FORMAT_UNAVAILABLE) {
                printCallback.invoke(error, description);
            }
        }).set();

        RenderBackend vulkan = RenderBackend.find(RenderAPI.VULKAN);
        if (vulkan != null && (requested == RenderAPI.VULKAN || RenderAPI.resolve(requested) == RenderAPI.VULKAN)) {
            vulkan.beforeGlfwInit();
        }

        // Native Wayland breaks both backends: GLFW makes an EGL context there, and Skia's makeGL finds its functions
        // through GLX, so OpenGL fails with "Can't create OpenGL DirectContext"; and a Vulkan window presents its first
        // frame while still hidden, before the compositor has configured the surface, which is a protocol error
        // ("attached a buffer before configure event") that ends the connection. Use X11 (XWayland on a Wayland
        // session). The hint only counts on the first glfwInit of the process, so it applies to every backend.
        if (System.getProperty("os.name", "").toLowerCase().contains("linux") && glfwPlatformSupported(GLFW_PLATFORM_X11)) {
            glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11);
        }

        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        RenderAPI resolved = RenderAPI.resolve(requested);
        if (resolved == RenderAPI.VULKAN) {
            String problem = vulkan == null
                    ? "the rengl-vulkan module is not on the classpath (add the me.piitex.engine:rengl-vulkan dependency)"
                    : vulkan.unsupportedReason();
            if (problem != null) {
                log.warn("Vulkan was requested, but {}. Using {} instead.", problem, RenderAPI.platformDefault());
                resolved = RenderAPI.platformDefault();
            }
        }
        this.backend = resolved;
        log.info("Render API: {}", backend);

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // stay hidden until the first frame has rendered, to avoid a flash of an uninitialized/blank surface
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        if (backend != RenderAPI.OPENGL) {
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API); // Metal: a plain NSView, no NSOpenGLView. Vulkan: no context at all.
        } else {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        }
    }

    /**
     * The graphics API this window was made for, after fallbacks.
     */
    public RenderAPI getRenderAPI() {
        return backend;
    }

    /**
     * Called on the main thread with true when the window is minimized and false when it is un-minimized. Set before {@link #createWindow}.
     */
    public void setIconifyHandler(Consumer<Boolean> handler) {
        this.iconifyHandler = handler;
    }

    /**
     * Called on the main thread with true when the window is maximized and false when it is un-maximized. Set before {@link #createWindow}.
     */
    public void setMaximizeHandler(Consumer<Boolean> handler) {
        this.maximizeHandler = handler;
    }

    /**
     * Asked on the main thread when the user asks to close the window (its close button, or Quit on macOS): true lets
     * it close, false keeps it open. {@link #applyClose()} does not ask. Set before {@link #createWindow}.
     */
    public void setCloseRequestHandler(java.util.function.BooleanSupplier handler) {
        this.closeRequestHandler = handler;
    }

    /**
     * Told on the main thread about files dragged onto the window from Finder / Explorer. Set before {@link #createWindow}.
     */
    public void setDropListener(FileDropListener listener) {
        this.dropListener = listener;
    }

    /**
     * Creates the actual GLFW window and wires up input callbacks. The window is
     * created hidden, since {@code GLFW_VISIBLE} was set to false above. The engine
     * shows it later, once the first frame has rendered, so the user never sees a blank
     * or partially drawn frame.
     * <p>
     * Also enables raw mouse motion input when the platform supports it, for
     * unaccelerated, OS-cursor-independent mouse deltas (mainly useful for
     * camera-look style controls rather than ordinary UI cursor tracking).
     *
     * @return the native GLFW window handle, also retrievable later via {@link #getWindow()}.
     * @throws RuntimeException if GLFW fails to create the window.
     */
    public long createWindow(int width, int height, String title) {
        window = glfwCreateWindow(width, height, title, NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }

        if (glfwRawMouseMotionSupported()) {
            glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
        }

        glfwSetCursorPosCallback(window, inputManager::onCursorPos);
        glfwSetCursorEnterCallback(window, inputManager::onCursorEnter);
        glfwSetMouseButtonCallback(window, inputManager::onMouseButton);
        glfwSetKeyCallback(window, inputManager::onKey);
        glfwSetCharCallback(window, inputManager::onChar);
        glfwSetScrollCallback(window, inputManager::onScroll);
        glfwSetWindowIconifyCallback(window, (w, iconified) -> {
            Consumer<Boolean> handler = iconifyHandler;
            if (handler != null) handler.accept(iconified);
        });
        glfwSetWindowMaximizeCallback(window, (w, maximized) -> {
            Consumer<Boolean> handler = maximizeHandler;
            if (handler != null) handler.accept(maximized);
        });
        // On Windows a drop normally arrives through the OLE target below, which also reports the drag before the
        // drop. This callback is how the other platforms hear drops, and Windows' fallback if OLE can't start.
        glfwSetDropCallback(window, (w, count, names) -> {
            FileDropListener listener = dropListener;
            if (listener == null || count <= 0) return;
            java.util.List<java.nio.file.Path> paths = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                try {
                    paths.add(java.nio.file.Path.of(GLFWDropCallback.getName(names, i)));
                } catch (java.nio.file.InvalidPathException ignored) {
                    // not a path this file system can name; nothing to hand over
                }
            }
            if (paths.isEmpty()) return;
            double[] x = new double[1], y = new double[1];
            glfwGetCursorPos(w, x, y);
            listener.drop(java.util.List.copyOf(paths), x[0], y[0]);
        });
        glfwSetWindowCloseCallback(window, w -> {
            java.util.function.BooleanSupplier handler = closeRequestHandler;
            if (handler != null && !handler.getAsBoolean()) glfwSetWindowShouldClose(w, false);
        });

        if (WINDOWS) installDropTarget();

        return window;
    }

    /**
     * Main thread. Has the window take file drops through OLE, so it hears a drag come and go before the drop. If that
     * can't be done the window keeps GLFW's plain drop callback.
     */
    private void installDropTarget() {
        try {
            nativeHandle = GLFWNativeWin32.glfwGetWin32Window(window);
            dropTarget = WindowsDragDrop.register(nativeHandle, new FileDropListener() {
                @Override
                public boolean accepts() {
                    FileDropListener listener = dropListener;
                    return listener != null && listener.accepts();
                }

                @Override
                public void over(java.util.List<Path> files, double x, double y) {
                    FileDropListener listener = dropListener;
                    if (listener != null) listener.over(files, x, y);
                }

                @Override
                public void leave() {
                    FileDropListener listener = dropListener;
                    if (listener != null) listener.leave();
                }

                @Override
                public void drop(java.util.List<Path> files, double x, double y) {
                    FileDropListener listener = dropListener;
                    if (listener != null) listener.drop(files, x, y);
                }
            });
        } catch (Throwable t) {
            log.warn("Could not set up drag and drop through OLE; dropped files will arrive without hover events", t);
        }
    }

    /**
     * MAIN THREAD ONLY. Drags the files {@code source} makes out of the window, for the left button the user is holding,
     * and returns when the button is released. Blocks the main thread until then: the operating system pumps the
     * window's messages meanwhile, and the render thread carries on drawing. Does nothing, answering
     * {@link FileDrag.Outcome#CANCELLED}, when the button is not down any more.
     */
    public FileDrag.Outcome applyFileDrag(FileDrag.Source source) {
        if (!WINDOWS || window == NULL) return FileDrag.Outcome.UNSUPPORTED;
        if (glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) != GLFW_PRESS) return FileDrag.Outcome.CANCELLED;
        FileDrag.Outcome outcome = WindowsDragDrop.dragOut(source);
        // The drag's own message loop took the release of the button, so GLFW still counts it as held.
        if (window != NULL && glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS) {
            WindowsDragDrop.postLeftButtonUp(nativeHandle);
        }
        return outcome;
    }

    /**
     * Applies the creation-time {@link WindowOptions} that GLFW owns: resizability, fullscreen, borderless,
     * maximized state, cursor visibility and the window icon. Vsync and max FPS are deliberately not handled here.
     * <p>
     * Interpretation of the two mode flags:
     * <ul>
     *   <li>{@code fullscreen}: the window covers the primary monitor. On its own it takes the monitor
     *       exclusively, as native fullscreen at the monitor's current video mode.</li>
     *   <li>{@code !windowed}: the window has no title bar or border. Combined with {@code fullscreen} it
     *       becomes borderless fullscreen, an undecorated window sized to the monitor with no mode switch.</li>
     * </ul>
     * Call this after {@link #createWindow} and before {@link #showWindow()}, so the user never sees the
     * transition. Called on the main thread.
     */
    public void applyOptions(WindowOptions options) {
        glfwSetWindowAttrib(window, GLFW_RESIZABLE, options.isResizable() ? GLFW_TRUE : GLFW_FALSE);

        boolean borderless = options.getStyle() == WindowStyle.BORDERLESS;
        if (borderless) {
            glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_FALSE);
        }
        if (options.isAlwaysOnTop()) {
            glfwSetWindowAttrib(window, GLFW_FLOATING, GLFW_TRUE);
        }
        if (options.isFullscreen()) {
            applyFullscreen(true, borderless);
        } else if (options.isMaximized() && options.isResizable()) {
            glfwMaximizeWindow(window);
        }
        if (!options.isShowCursor()) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_HIDDEN); // hidden, but still free-moving (not captured)
        }
        if (options.getIcon() != null) {
            applyIcon(options.getIcon());
        } else if (options.getIconPath() != null) {
            applyIcon(options.getIconPath());
        }
    }

    /**
     * Enters or leaves fullscreen. Entering remembers the windowed position and size so leaving restores them.
     * Covers the primary monitor: an undecorated window sized to it if {@code borderless}, else the monitor's own
     * video mode. MAIN THREAD ONLY.
     */
    public void applyFullscreen(boolean enable, boolean borderless) {
        if (!enable) {
            if (!fullscreen) return;
            fullscreen = false;
            if (frameHiddenForFullscreen) {
                frameHiddenForFullscreen = false;
                if (!borderless) glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_TRUE);
            }
            if (glfwGetWindowMonitor(window) != NULL) {
                glfwSetWindowMonitor(window, NULL, windowedX, windowedY, windowedW, windowedH, GLFW_DONT_CARE);
            } else {
                glfwSetWindowPos(window, windowedX, windowedY);
                glfwSetWindowSize(window, windowedW, windowedH);
            }
            return;
        }
        if (fullscreen) return;

        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode mode = monitor == NULL ? null : glfwGetVideoMode(monitor);
        if (mode == null) {
            log.warn("Fullscreen requested but no primary monitor/video mode is available; staying windowed.");
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1), y = stack.mallocInt(1), w = stack.mallocInt(1), h = stack.mallocInt(1);
            glfwGetWindowPos(window, x, y);
            glfwGetWindowSize(window, w, h);
            windowedX = x.get(0);
            windowedY = y.get(0);
            windowedW = w.get(0);
            windowedH = h.get(0);
        }
        fullscreen = true;
        // macOS has no usable exclusive mode here: the monitor's video mode is in points, GLFW matches it against
        // pixel modes and switches the display to another resolution. Cover the screen with a frameless window instead.
        if (!borderless && MAC) {
            borderless = true;
            frameHiddenForFullscreen = true;
            glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_FALSE);
        }
        if (borderless) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer x = stack.mallocInt(1), y = stack.mallocInt(1);
                glfwGetMonitorPos(monitor, x, y);
                glfwSetWindowPos(window, x.get(0), y.get(0));
            }
            glfwSetWindowSize(window, mode.width(), mode.height());
        } else {
            glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
        }
    }

    /**
     * Sets the window icon from an image file on disk or the classpath. A missing or undecodable file only logs. MAIN THREAD ONLY.
     */
    public void applyIcon(String iconPath) {
        byte[] bytes = readIconBytes(iconPath);
        if (bytes != null) applyIconBytes(bytes, iconPath);
    }

    /**
     * Sets the window icon from an already decoded image, such as one from {@code ImageCache.load}. MAIN THREAD ONLY.
     */
    public void applyIcon(Image image) {
        if (image == null) return;
        org.jetbrains.skia.Data data = image.encodeToData(EncodedImageFormat.PNG, 100, 100);
        if (data == null) {
            log.warn("Could not encode the window icon image.");
            return;
        }
        applyIconBytes(data.getBytes(), "<image>");
    }

    /**
     * {@code bytes} is an encoded image file. On macOS this sets the Dock icon (GLFW has no window icon there);
     * elsewhere it sets the window icon through GLFW.
     */
    private void applyIconBytes(byte[] bytes, String label) {
        if (MAC) {
            MacIcon.set(bytes);
            return;
        }

        ByteBuffer encoded = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), channels = stack.mallocInt(1);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, w, h, channels, 4);
            if (pixels == null) {
                log.warn("Could not decode window icon '{}': {}", label, STBImage.stbi_failure_reason());
                return;
            }
            try {
                GLFWImage.Buffer icons = GLFWImage.malloc(1, stack);
                icons.get(0).set(w.get(0), h.get(0), pixels);
                glfwSetWindowIcon(window, icons);
            } finally {
                STBImage.stbi_image_free(pixels);
            }
        } finally {
            MemoryUtil.memFree(encoded);
        }
    }

    /**
     * The icon file's bytes from disk or, failing that, the classpath; null (after a warning) if it can't be read.
     */
    private static byte[] readIconBytes(String iconPath) {
        try {
            Path path = Path.of(iconPath);
            if (Files.exists(path)) {
                return Files.readAllBytes(path);
            }
            try (InputStream in = GLFWindow.class.getResourceAsStream(iconPath.startsWith("/") ? iconPath : "/" + iconPath)) {
                if (in == null) {
                    log.warn("Window icon not found: {}", iconPath);
                    return null;
                }
                return in.readAllBytes();
            }
        } catch (IOException e) {
            log.warn("Could not read window icon '{}'", iconPath, e);
            return null;
        }
    }

    /**
     * Makes the window visible. Called once, after the render thread has produced its
     * first real frame, so the window never appears blank or with undefined contents.
     * <p>
     * Called on the main thread.
     */
    public void showWindow() {
        glfwShowWindow(window);
    }

    /**
     * Binds this window's OpenGL context to the calling thread and initializes LWJGL's
     * GL bindings for it. This is valid only on non-macOS platforms. See the class-level
     * note above on why macOS has no GL context to bind.
     * <p>
     * Starts with vsync off ({@code glfwSwapInterval(0)}); the render loop then applies the
     * configured setting through {@link #setVsync(boolean)}.
     * <p>
     * Called on the render thread.
     */
    public void initOpenGLContext() {
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        GL.createCapabilities();
    }

    /**
     * Turns vsync on or off for the current OpenGL context ({@code glfwSwapInterval}). Non-macOS only; on
     * macOS vsync is a property of the Metal layer instead (see {@code MetalContext#setVsync}).
     * <p>
     * Called on the render thread.
     */
    public void setVsync(boolean vsync) {
        glfwSwapInterval(vsync ? 1 : 0);
    }

    /**
     * Drains and dispatches all pending GLFW events (input callbacks, resize/move
     * callbacks, etc.) for every GLFW window in this process. Despite the name this
     * is an ordinary, unthrottled {@code glfwPollEvents()} call. "High frequency"
     * describes how often the engine's main loop calls it, once per spin of a tight
     * polling loop, rather than any special GLFW behavior.
     * <p>
     * Must be called from the main thread; GLFW callbacks (cursor, mouse button, key,
     * resize) are only ever invoked as a side effect of this call.
     */
    public void pollEventsHighFrequency() {
        glfwPollEvents();
        runMainThreadTasks();
    }

    /**
     * Main thread only. Runs the tasks queued with {@link #runOnMainThread} for this window. With several windows
     * open, the engine polls GLFW once and then calls this for each window.
     */
    public void runMainThreadTasks() {
        Runnable task;
        while ((task = mainThreadTasks.poll()) != null) {
            task.run();
        }
    }

    /**
     * MAIN THREAD ONLY. Destroys the native window, and its OpenGL context with it; the render thread must be done
     * with that context first. Tasks still queued for the window are dropped.
     */
    public void destroy() {
        if (window == NULL) return;
        if (dropTarget != null) {
            WindowsDragDrop.unregister(nativeHandle, dropTarget);
            dropTarget = null;
        }
        glfwDestroyWindow(window);
        window = NULL;
        mainThreadTasks.clear();
    }

    /**
     * Safe from any thread: runs {@code task} on the main thread, on the next {@link #pollEventsHighFrequency()}.
     */
    public void runOnMainThread(Runnable task) {
        mainThreadTasks.add(task);
    }

    /**
     * MAIN THREAD ONLY. Adds or removes the OS frame (title bar, title and window controls) of the live window.
     */
    public void applyStyle(WindowStyle style) {
        if (frameHiddenForFullscreen) return; // the frame comes back, or not, when fullscreen ends
        glfwSetWindowAttrib(window, GLFW_DECORATED, style == WindowStyle.STANDARD ? GLFW_TRUE : GLFW_FALSE);
    }

    /**
     * MAIN THREAD ONLY.
     */
    public void applyAlwaysOnTop(boolean alwaysOnTop) {
        glfwSetWindowAttrib(window, GLFW_FLOATING, alwaysOnTop ? GLFW_TRUE : GLFW_FALSE);
    }

    /**
     * MAIN THREAD ONLY. The size/resize callbacks the engine registered pick up the new size.
     */
    public void applySize(int width, int height) {
        glfwSetWindowSize(window, width, height);
    }

    /**
     * MAIN THREAD ONLY.
     */
    public void applyTitle(String title) {
        glfwSetWindowTitle(window, title);
    }

    /**
     * MAIN THREAD ONLY. Centers the window in the primary monitor's usable area.
     */
    public void applyCenter() {
        long monitor = glfwGetPrimaryMonitor();
        if (monitor == NULL) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer mx = stack.mallocInt(1), my = stack.mallocInt(1), mw = stack.mallocInt(1), mh = stack.mallocInt(1);
            glfwGetMonitorWorkarea(monitor, mx, my, mw, mh);
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            glfwGetWindowSize(window, w, h);
            glfwSetWindowPos(window, mx.get(0) + (mw.get(0) - w.get(0)) / 2, my.get(0) + (mh.get(0) - h.get(0)) / 2);
        }
    }

    /**
     * MAIN THREAD ONLY.
     */
    public void applyResizable(boolean resizable) {
        glfwSetWindowAttrib(window, GLFW_RESIZABLE, resizable ? GLFW_TRUE : GLFW_FALSE);
    }

    /**
     * MAIN THREAD ONLY.
     */
    public void applyPosition(int x, int y) {
        glfwSetWindowPos(window, x, y);
    }

    /**
     * MAIN THREAD ONLY. A negative value means no limit on that side.
     */
    public void applySizeLimits(int minW, int minH, int maxW, int maxH) {
        glfwSetWindowSizeLimits(window, minW < 0 ? GLFW_DONT_CARE : minW, minH < 0 ? GLFW_DONT_CARE : minH,
                maxW < 0 ? GLFW_DONT_CARE : maxW, maxH < 0 ? GLFW_DONT_CARE : maxH);
    }

    /**
     * MAIN THREAD ONLY.
     */
    public void applyOpacity(float opacity) {
        glfwSetWindowOpacity(window, opacity);
    }

    /**
     * MAIN THREAD ONLY. Hidden keeps the cursor free-moving; locked hides it and keeps it in the window, reporting
     * only how far the mouse moves (GLFW's disabled cursor).
     */
    public void applyCursorMode(boolean visible, boolean locked) {
        glfwSetInputMode(window, GLFW_CURSOR, locked ? GLFW_CURSOR_DISABLED : visible ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_HIDDEN);
    }

    /**
     * MAIN THREAD ONLY. Moves the cursor, in window points, and reports it to the input, since not every platform
     * sends an event for a cursor moved by the program. GLFW only moves it while the window has focus.
     */
    public void applyCursorPosition(double x, double y) {
        if (glfwGetWindowAttrib(window, GLFW_FOCUSED) == GLFW_FALSE) return;
        glfwSetCursorPos(window, x, y);
        inputManager.onCursorPos(window, x, y);
    }

    /**
     * MAIN THREAD ONLY.
     */
    public void applyMinimize() {
        glfwIconifyWindow(window);
    }

    /**
     * MAIN THREAD ONLY.
     */
    public void applyMaximize() {
        glfwMaximizeWindow(window);
    }

    /**
     * MAIN THREAD ONLY. Minimizes or un-minimizes; a no-op if the window is already in the requested state.
     */
    public void applyMinimized(boolean minimized) {
        boolean iconified = glfwGetWindowAttrib(window, GLFW_ICONIFIED) == GLFW_TRUE;
        if (minimized && !iconified) {
            glfwIconifyWindow(window);
        } else if (!minimized && iconified) {
            glfwRestoreWindow(window);
        }
    }

    /**
     * MAIN THREAD ONLY. Maximizes or un-maximizes; a no-op if already so. Never un-minimizes when asked to un-maximize.
     */
    public void applyMaximized(boolean maximized) {
        if (fullscreen) return; // the request is kept in WindowOptions and applied when fullscreen ends
        boolean iconified = glfwGetWindowAttrib(window, GLFW_ICONIFIED) == GLFW_TRUE;
        boolean isMaximized = glfwGetWindowAttrib(window, GLFW_MAXIMIZED) == GLFW_TRUE;
        if (maximized && !isMaximized) {
            glfwMaximizeWindow(window);
        } else if (!maximized && isMaximized && !iconified) {
            glfwRestoreWindow(window);
        }
    }

    /**
     * MAIN THREAD ONLY. Undoes minimize or maximize.
     */
    public void applyRestore() {
        glfwRestoreWindow(window);
    }

    /**
     * MAIN THREAD ONLY.
     */
    public void applyFocus() {
        glfwFocusWindow(window);
    }

    /**
     * MAIN THREAD ONLY. Hides the window (no taskbar or Dock entry on most platforms) without ending the app.
     */
    public void applyHide() {
        glfwHideWindow(window);
    }

    /**
     * MAIN THREAD ONLY. Shows a hidden window again.
     */
    public void applyShow() {
        glfwShowWindow(window);
    }

    /**
     * MAIN THREAD ONLY. Closes the window, as if the user had clicked its close button. Closing the engine's first
     * window ends the engine loop.
     */
    public void applyClose() {
        glfwSetWindowShouldClose(window, true);
    }

    /**
     * The native GLFW window handle, as returned by {@link #createWindow}.
     */
    public long getWindow() {
        return window;
    }
}