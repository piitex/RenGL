package me.piitex.engine;

import me.piitex.engine.game.SceneManager;
import me.piitex.engine.gl.FileDropListener;
import me.piitex.engine.gl.GLFWindow;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.gl.renderer.Snapshot;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.input.FileDrag;
import me.piitex.engine.input.Input;
import me.piitex.engine.input.InputManager;
import me.piitex.engine.ui.background.Background;
import me.piitex.engine.ui.background.BackgroundKinds;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.PopupHost;
import me.piitex.engine.ui.containers.ModalContainer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.Styling;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.Image;

import org.apache.logging.log4j.LogManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import me.piitex.engine.utils.flags.GeneratedFragments;

/**
 * Implements {@link Themeable} and registers itself automatically in its constructor,
 * unlike {@link Container}, which themes only the specific instances opted in through
 * {@link Container#useTheme()}. There is only ever one Window, and it exists to be the
 * application's visible backdrop, so there is no plain-grouping use case to protect the
 * way there is for Container. The window background therefore follows
 * {@link Theme#getWindowBackground()} out of the box and re-applies on every
 * {@link ThemeManager#setCurrent}. {@link #setBackgroundColor} behaves like every other
 * themed property in this engine: the theme is only a baseline, and an explicit call
 * pins the background so later theme switches leave it alone. See {@link Overridable}.
 */
public class Window implements Themeable, PopupHost {
    private final WindowOptions windowOptions;
    private final ConcurrentSkipListMap<Integer, Container> containers = new ConcurrentSkipListMap<>();
    private final InputManager inputManager = new InputManager();
    private final SceneManager sceneManager = new SceneManager(this);
    private final Input input = new Input(this);
    private final Loading loading = new Loading();
    private volatile GLFWindow glfWindow;
    private Element focusedElement;
    private final java.util.concurrent.CopyOnWriteArrayList<me.piitex.engine.input.ShortcutHandler> shortcutHandlers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Overridable<Paint> backgroundColor = new Overridable<>(Color.WHITE);
    private final Overridable<Background> background = new Overridable<>(null);
    private Background themeEffect; // the one the theme made, kept so a later theme change can update it instead of starting over
    private Container popup;
    private Runnable popupDismissListener;
    private Element popupAnchor;
    private double anchorStartX, anchorStartY; // the anchor's window position when the popup was shown
    private double popupShiftX, popupShiftY;   // how far the popup has been moved since then
    private int popupSide;                     // 1: shown below the anchor, -1: above it, 0: neither
    private double popupGap;                   // space between the anchor and the popup on that side
    private final List<Element> lockingElements = new ArrayList<>(); // enabled Elements with a locking transition, as of the last frame
    private final List<ModalContainer> modals = new ArrayList<>(); // bottom to top; the last one is the active modal
    private volatile boolean hidden;
    private volatile boolean minimized;
    private volatile boolean focused, cursorLocked;
    private volatile Runnable closeListener;
    private volatile Runnable minimizeListener, maximizeListener, restoreListener;
    private volatile java.util.function.BiConsumer<Integer, Integer> resizeListener;
    private volatile Runnable closeRequestListener;
    private volatile java.util.function.Consumer<List<java.nio.file.Path>> fileDropListener;
    private volatile java.util.function.Consumer<FileDrag.Drop> fileDropAtListener;
    private volatile java.util.function.Consumer<FileDrag.Hover> fileDragOverListener;
    private volatile Runnable fileDragLeaveListener;
    private final java.util.concurrent.atomic.AtomicReference<FileDrag.Hover> hoverPending = new java.util.concurrent.atomic.AtomicReference<>();
    private static final long FPS_SAMPLE_NANOS = 500_000_000L;
    private volatile double fps;
    private volatile double frameTime;
    private volatile long frameCount;
    private long fpsSampleNanos;               // render thread only: time and frames in the current FPS sample
    private int fpsSampleFrames;


    public Window(WindowOptions windowOptions) {
        this.windowOptions = windowOptions;
        windowOptions.bind(this);
        ThemeManager.register(this);
    }

    protected void initializeGLFW() {
        glfWindow = new GLFWindow(inputManager, windowOptions.getRenderer());
        glfWindow.setIconifyHandler(this::notifyMinimized);
        glfWindow.setMaximizeHandler(this::notifyMaximized);
        glfWindow.setCloseRequestHandler(this::notifyCloseRequested);
        glfWindow.setDropListener(new FileDropListener() {
            @Override
            public boolean accepts() {
                return fileDropListener != null || fileDropAtListener != null;
            }

            @Override
            public void over(List<java.nio.file.Path> files, double x, double y) {
                notifyFilesOver(files, x, y);
            }

            @Override
            public void leave() {
                notifyFilesLeft();
            }

            @Override
            public void drop(List<java.nio.file.Path> files, double x, double y) {
                notifyFilesDropped(files, x, y);
            }
        });
    }

    public WindowOptions getWindowOptions() {
        return windowOptions;
    }

    /**
     * How far the {@linkplain WindowOptions#addWarmUp warm-ups} that run under the splash screen have got. A
     * {@link Splash} reads it to wait for them; it is done from the start for a window with none.
     */
    public Loading getLoading() {
        return loading;
    }

    /**
     * The graphics API this window really draws with, which is what {@link WindowOptions#setRenderer} asked for
     * unless the machine couldn't do that. {@link RenderAPI#AUTO} once resolved is never returned. Before the window
     * opens this is just the resolved request, without the check that Vulkan has a usable GPU.
     */
    public RenderAPI getRenderer() {
        return glfWindow != null ? glfWindow.getRenderAPI() : RenderAPI.resolve(windowOptions.getRenderer());
    }

    protected GLFWindow getGlfWindow() {
        return glfWindow;
    }

    /**
     * Changes the window's frame while the app is running, for example {@link WindowStyle#BORDERLESS} for a splash
     * screen and {@link WindowStyle#STANDARD} once the main UI is ready. Safe from any thread. Before
     * {@link Engine#start} it just records the style for the window to be created with.
     */
    public void setStyle(WindowStyle style) {
        windowOptions.setStyle(style);
    }

    void pushRenderer() {
        Engine engine = Engine.getRunning();
        if (engine != null) engine.changeRenderer(this);
    }

    void pushStyle() {
        GLFWindow g = glfWindow;
        if (g != null) {
            WindowStyle applied = windowOptions.getStyle();
            boolean maximized = windowOptions.isMaximized();
            g.runOnMainThread(() -> {
                g.applyStyle(applied);
                if (maximized) g.applyMaximized(true); // changing the frame can drop the OS's maximized state
            });
        }
    }

    public WindowStyle getStyle() {
        return windowOptions.getStyle();
    }

    /**
     * Keeps the window above other windows, or stops doing so. Safe from any thread.
     */
    public void setAlwaysOnTop(boolean alwaysOnTop) {
        windowOptions.setAlwaysOnTop(alwaysOnTop);
    }

    void pushAlwaysOnTop() {
        GLFWindow g = glfWindow;
        if (g != null) {
            boolean onTop = windowOptions.isAlwaysOnTop();
            g.runOnMainThread(() -> g.applyAlwaysOnTop(onTop));
        }
    }

    /**
     * Resizes the window (in screen coordinates). Before {@link Engine#start} this just
     * records the size the window will open at, the same as {@link #setTitle} and the
     * other pre-start setters; once running, it also resizes the live window, which
     * fires {@link #onResize} on the next frame the same as a user drag-resize would.
     * Safe from any thread.
     */
    public void setSize(int width, int height) {
        windowOptions.setDimensions(width, height);
    }

    void pushSize(int width, int height) {
        GLFWindow g = glfWindow;
        if (g != null) g.runOnMainThread(() -> g.applySize(width, height));
    }

    /**
     * Centers the window on the primary monitor. Only works once the engine has started. Safe from any thread.
     */
    public void center() {
        if (glfWindow != null) {
            glfWindow.runOnMainThread(glfWindow::applyCenter);
        }
    }

    /**
     * Changes the OS window title. Only works once the engine has started. Safe from any thread.
     */
    public void setTitle(String title) {
        windowOptions.setTitle(title);
    }

    void pushTitle() {
        GLFWindow g = glfWindow;
        if (g != null) {
            String title = windowOptions.getTitle();
            g.runOnMainThread(() -> g.applyTitle(title));
        }
    }

    /**
     * Changes the window icon while running, from an image loaded with {@code ImageCache.load(...)}. On macOS this
     * sets the Dock icon, since macOS windows have no icon of their own. Safe from any thread.
     */
    public void setIcon(Image icon) {
        windowOptions.setIcon(icon);
    }

    void pushIcon() {
        GLFWindow g = glfWindow;
        if (g != null) {
            Image icon = windowOptions.getIcon();
            if (icon != null) g.runOnMainThread(() -> g.applyIcon(icon));
        }
    }

    void pushIconPath(String path) {
        GLFWindow g = glfWindow;
        if (g != null && windowOptions.getIcon() == null) g.runOnMainThread(() -> g.applyIcon(path));
    }

    /**
     * Allows or forbids the user resizing the window. {@link #setSize} still works. Safe from any thread.
     */
    public void setResizable(boolean resizable) {
        windowOptions.setResizable(resizable);
    }

    void pushResizable() {
        GLFWindow g = glfWindow;
        if (g != null) {
            boolean resizable = windowOptions.isResizable();
            g.runOnMainThread(() -> g.applyResizable(resizable));
        }
    }

    /**
     * Enters or leaves fullscreen. Follows the current {@link WindowStyle}: {@code BORDERLESS} gives borderless
     * fullscreen (no display mode switch), {@code STANDARD} takes the monitor exclusively (on macOS both give a frameless
     * window the size of the screen). Leaving restores the
     * position and size from before. Safe from any thread.
     */
    public void setFullscreen(boolean fullscreen) {
        windowOptions.setFullscreen(fullscreen);
    }

    void pushFullscreen() {
        GLFWindow g = glfWindow;
        if (g != null) {
            boolean fullscreen = windowOptions.isFullscreen();
            boolean borderless = windowOptions.getStyle() == WindowStyle.BORDERLESS;
            boolean maximized = windowOptions.isMaximized();
            g.runOnMainThread(() -> {
                g.applyFullscreen(fullscreen, borderless);
                if (!fullscreen && maximized) g.applyMaximized(true); // a maximize asked for during fullscreen, or lost to it
            });
        }
    }

    public boolean isFullscreen() {
        return windowOptions.isFullscreen();
    }

    /**
     * Moves the window's top-left corner to {@code x, y} in screen coordinates. Safe from any thread.
     */
    public void setPosition(int x, int y) {
        if (glfWindow != null) {
            glfWindow.runOnMainThread(() -> glfWindow.applyPosition(x, y));
        }
    }

    /**
     * Limits how small or large the user can resize the window. A negative value means no limit on that side. Safe from any thread.
     */
    public void setSizeLimits(int minWidth, int minHeight, int maxWidth, int maxHeight) {
        if (glfWindow != null) {
            glfWindow.runOnMainThread(() -> glfWindow.applySizeLimits(minWidth, minHeight, maxWidth, maxHeight));
        }
    }

    /**
     * Whole-window opacity from 0 (invisible) to 1. Not every platform supports it. Safe from any thread.
     */
    public void setOpacity(float opacity) {
        float clamped = Math.max(0f, Math.min(1f, opacity));
        if (glfWindow != null) {
            glfWindow.runOnMainThread(() -> glfWindow.applyOpacity(clamped));
        }
    }

    /**
     * Shows or hides the OS cursor over the window. It still moves freely. While the cursor is
     * {@link #setCursorLocked locked} it stays hidden, and this takes effect once it is freed. Safe from any thread.
     */
    public void setCursorVisible(boolean visible) {
        windowOptions.setShowCursor(visible);
    }

    void pushCursorVisible() {
        GLFWindow g = glfWindow;
        if (g != null) {
            boolean visible = windowOptions.isShowCursor();
            g.runOnMainThread(() -> g.applyCursorMode(visible, cursorLocked));
        }
    }

    /**
     * Locks the mouse to the window, or frees it. While it is locked the OS cursor is hidden and can't leave the
     * window, however far the mouse moves, so no click lands on another application; the keyboard can still switch
     * away (Cmd+Tab, Alt+Tab), which frees it until the window has focus again. A locked mouse has no place on
     * screen: {@link Input#getMouseX()} keeps counting past the edges. Draw a pointer of your own and move it by
     * {@link Input#getMouseDeltaX()} and {@link Input#getMouseDeltaY()}. Where the platform supports raw mouse
     * motion, those deltas come without the OS's pointer acceleration. The interface still reacts to the mouse
     * where Input says it is, so free the mouse for menus. Safe from any thread.
     */
    public void setCursorLocked(boolean locked) {
        cursorLocked = locked;
        if (glfWindow != null) {
            glfWindow.runOnMainThread(() -> glfWindow.applyCursorMode(windowOptions.isShowCursor(), locked));
        }
    }

    public boolean isCursorLocked() {
        return cursorLocked;
    }

    /**
     * Moves the OS cursor to (x, y) in window points, the space of {@link Input#getMouseX()}. After freeing a
     * {@link #setCursorLocked locked} mouse, this puts the cursor where the game's own pointer was. Does nothing while
     * the window doesn't have {@link #isFocused() focus}. Safe from any thread; calls made in order take effect in
     * order.
     */
    public void setCursorPosition(float x, float y) {
        if (glfWindow != null) glfWindow.runOnMainThread(() -> glfWindow.applyCursorPosition(x, y));
    }

    /**
     * Whether the window has the OS focus: it gets the keyboard, and is usually in front. False once the user switches
     * to another application, until they come back. Safe from any thread.
     */
    public boolean isFocused() {
        return focused;
    }

    /**
     * Main thread, from the GLFW callback.
     */
    void notifyFocused(boolean focused) {
        this.focused = focused;
    }

    /**
     * See {@link WindowOptions#setVsync}. Picked up on the next frame. Safe from any thread.
     */
    public void setVsync(boolean vsync) {
        windowOptions.setVsync(vsync);
    }

    /**
     * See {@link WindowOptions#setMaxFPS}. Only used while vsync is off. Safe from any thread.
     */
    public void setMaxFPS(int maxFPS) {
        windowOptions.setMaxFPS(maxFPS);
    }

    /**
     * The measured frame rate, in frames per second. It is averaged over half a second and updated that often, so it
     * is steady enough to show in a label. 0 until the engine has run for half a second. Safe from any thread.
     */
    public double getFps() {
        return fps;
    }

    /**
     * How long the last frame took, in milliseconds, from the start of the frame before it to the start of this one.
     * That includes any wait for vsync or {@link #setMaxFPS}. Unlike the {@code delta} given to update callbacks, it
     * is not capped, so a stall shows up in full. 0 before the first frame. Safe from any thread.
     */
    public double getFrameTime() {
        return frameTime;
    }

    /**
     * How many frames the engine has started since it began running. Safe from any thread.
     */
    public long getFrameCount() {
        return frameCount;
    }

    /**
     * Render thread, called by the engine at the start of every frame with the time since the previous frame started.
     */
    void recordFrame(long frameNanos) {
        frameTime = frameNanos / 1_000_000.0;
        frameCount++;
        fpsSampleNanos += frameNanos;
        fpsSampleFrames++;
        if (fpsSampleNanos >= FPS_SAMPLE_NANOS) {
            fps = fpsSampleFrames * 1_000_000_000.0 / fpsSampleNanos;
            fpsSampleNanos = 0;
            fpsSampleFrames = 0;
        }
    }

    /**
     * Minimizes to the Dock or taskbar. Safe from any thread.
     */
    public void minimize() {
        if (glfWindow != null) glfWindow.runOnMainThread(glfWindow::applyMinimize);
    }

    /**
     * Maximizes the window. Does nothing if it isn't resizable. Safe from any thread.
     */
    public void maximize() {
        setMaximized(true);
    }

    /**
     * Undoes {@link #minimize()} or {@link #maximize()}. Safe from any thread.
     */
    public void restore() {
        if (glfWindow != null) glfWindow.runOnMainThread(glfWindow::applyRestore);
    }

    /**
     * {@code true} minimizes, {@code false} un-minimizes. Does nothing if the window is already in that state. Safe from any thread.
     */
    public void setMinimized(boolean minimized) {
        if (glfWindow != null) glfWindow.runOnMainThread(() -> glfWindow.applyMinimized(minimized));
    }

    /**
     * {@code true} maximizes, {@code false} returns to normal size. Does nothing if the window is already in that
     * state, if it isn't resizable, or (for {@code false}) while it is minimized. Safe from any thread.
     */
    public void setMaximized(boolean maximized) {
        windowOptions.setMaximized(maximized);
    }

    void pushMaximized() {
        GLFWindow g = glfWindow;
        if (g != null) {
            boolean maximized = windowOptions.isMaximized();
            g.runOnMainThread(() -> g.applyMaximized(maximized));
        }
    }

    /**
     * Brings the window to the front and gives it OS focus. Safe from any thread.
     */
    public void focus() {
        if (glfWindow != null) glfWindow.runOnMainThread(glfWindow::applyFocus);
    }

    /**
     * Hides the window without closing the application: the engine keeps running, timers and background tasks
     * keep firing, and the UI keeps updating. Bring it back with {@link #show()}, for example from a
     * {@link me.piitex.engine.scheduler.Scheduler} timer or a native menu action. Nothing else can bring it back,
     * so make sure something will. Safe from any thread, and works before the engine starts (the window then
     * opens hidden).
     */
    public void hide() {
        hidden = true;
        if (glfWindow != null) glfWindow.runOnMainThread(glfWindow::applyHide);
    }

    /**
     * Shows the window again after {@link #hide()}. Safe from any thread.
     */
    public void show() {
        hidden = false;
        if (glfWindow != null) glfWindow.runOnMainThread(glfWindow::applyShow);
    }

    /**
     * True between a {@link #hide()} and the next {@link #show()}.
     */
    public boolean isHidden() {
        return hidden;
    }

    /**
     * Runs when the window is minimized, by the user or by {@link #minimize()}. Runs on the render thread. Pass null to remove.
     */
    public void onMinimize(Runnable listener) {
        this.minimizeListener = listener;
    }

    /**
     * Runs when the window is maximized, by the user or by {@link #maximize()}. Runs on the render thread. Pass null to remove.
     */
    public void onMaximize(Runnable listener) {
        this.maximizeListener = listener;
    }

    /**
     * Runs when the window comes back to its normal size, either from being minimized (un-minimized, which
     * returns to maximized if it was maximized before) or from being maximized. Runs on the render thread.
     * Pass null to remove.
     */
    public void onRestore(Runnable listener) {
        this.restoreListener = listener;
    }

    /**
     * Runs whenever the window's logical size (screen coordinates, the same units as
     * {@link WindowOptions#getWidth()}/{@link WindowOptions#getHeight()}) changes,
     * whether from the user dragging the border or from {@link #setSize}. Fires with the
     * new width and height, after {@link WindowOptions} has already been updated, so a
     * layout can be resized or repositioned to match. Not fired for a pure DPI/monitor
     * change that leaves the logical size the same. Runs on the render thread. Pass null
     * to remove.
     */
    public void onResize(java.util.function.BiConsumer<Integer, Integer> listener) {
        this.resizeListener = listener;
    }

    /**
     * Runs once when the window is closing, by the user's close button, {@link #close()}, or the engine
     * stopping, after rendering has stopped. For the first window this is before {@link Engine#start} returns (so
     * before the JVM exits if that is the last thing {@code main} does). Each time a window is opened again, it
     * runs again when it next closes. Use it for cleanup: saving state, closing files and
     * connections, stopping your own threads.
     * <p>
     * It runs on the main thread, the window is already hidden, and it blocks the exit until it returns.
     * {@link me.piitex.engine.scheduler.Scheduler} tasks do not run any more at this point, so do the work
     * directly rather than through {@code runLater} or {@code async}. An exception is logged and does not
     * prevent shutdown. It is not called if the process is killed or {@code System.exit} is called elsewhere.
     * Pass null to remove.
     */
    public void onClose(Runnable listener) {
        this.closeListener = listener;
    }

    /**
     * Takes over the close button (and Quit on macOS): the window stays open and {@code listener} runs on the render
     * thread instead. It closes the window with {@link #close()} when it is ready, after asking whether to save
     * changes, say. {@link #close()} and the engine stopping still close the window at once. Pass null to let the
     * close button close the window again.
     */
    public void onCloseRequest(Runnable listener) {
        this.closeRequestListener = listener;
    }

    /**
     * Runs when the user drops files or folders on the window from Finder / Explorer, with their paths in the order
     * the OS gave them. Runs on the render thread. Pass null to remove; with no listener a drop does nothing.
     * <p>
     * Use {@link #onFileDropAt} to also learn where the pointer was, to tell which element the files were dropped on,
     * and {@link #onFileDragOver} (Windows) to light that element up as the drag approaches.
     * <p>
     * The paths are only names. Nothing is read, and a folder is not expanded into its files.
     */
    public void onFileDrop(java.util.function.Consumer<List<java.nio.file.Path>> listener) {
        this.fileDropListener = listener;
    }

    /**
     * Like {@link #onFileDrop}, and also says where the pointer was when the files were dropped, in window coordinates,
     * so a drop can land on the folder, row or panel under it. Both listeners run for a drop if both are set.
     */
    public void onFileDropAt(java.util.function.Consumer<FileDrag.Drop> listener) {
        this.fileDropAtListener = listener;
    }

    /**
     * Runs while files are being dragged over the window, from Explorer or the desktop, with where they are, as they
     * move. Use it to light up the folder or panel a drop would land on. It runs on the render thread, and a burst of
     * movement reaches it as the latest position rather than every step. A drop ends the drag without calling
     * {@link #onFileDragLeave}, so clear the highlight in the drop listener too. Pass null to remove.
     * <p>
     * <b>Windows only.</b> Other platforms say nothing about a drag until the files are dropped, so this never runs there.
     * The pointer shows the "no" sign over a window that has no drop listener ({@link #onFileDrop} or {@link #onFileDropAt}).
     */
    public void onFileDragOver(java.util.function.Consumer<FileDrag.Hover> listener) {
        this.fileDragOverListener = listener;
    }

    /**
     * Runs when files that were being dragged over the window leave it, or the user cancels the drag. Not called for a
     * drop. Windows only; see {@link #onFileDragOver}. Pass null to remove.
     */
    public void onFileDragLeave(Runnable listener) {
        this.fileDragLeaveListener = listener;
    }

    /**
     * Starts dragging files out of the window, to Explorer, the desktop or any program that takes files, for the left
     * button the user is holding down right now. Call it from the press or move that begins the drag: when the button
     * is already up it does nothing and {@code done} gets {@link FileDrag.Outcome#CANCELLED}.
     * <p>
     * {@code source} is asked for the files only when the user lets go over something that wants them, so a folder
     * inside an archive is not extracted for a drag that goes nowhere. See {@link FileDrag.Source} for what it may
     * and may not do. The files are copied: the receiver never moves or deletes them. Dropping them back on this
     * window is ignored.
     * <p>
     * While the drag lasts the engine's main thread is held by the operating system, and this window keeps drawing and
     * updating as usual. {@code done}, which may be null, runs afterwards on the render thread with how it ended; it
     * says {@link FileDrag.Outcome#UNSUPPORTED} at once on a platform that can't do this. Safe from any thread.
     * {@link me.piitex.engine.ui.containers.TreeView#setFileDragSource} calls this for a row dragged out of the window.
     */
    public void startFileDrag(FileDrag.Source source, java.util.function.Consumer<FileDrag.Outcome> done) {
        java.util.Objects.requireNonNull(source, "source");
        GLFWindow g = glfWindow;
        if (g == null || !FileDrag.isSupported()) {
            if (done != null) fire(() -> done.accept(FileDrag.Outcome.UNSUPPORTED));
            return;
        }
        g.runOnMainThread(() -> {
            FileDrag.Outcome outcome = FileDrag.Outcome.FAILED;
            try {
                outcome = g.applyFileDrag(source);
            } catch (Throwable t) {
                LogManager.getLogger("Engine").error("Dragging files out of the window failed", t);
            }
            FileDrag.Outcome result = outcome;
            if (done != null) fire(() -> done.accept(result));
        });
        org.lwjgl.glfw.GLFW.glfwPostEmptyEvent(); // wake the main thread out of glfwWaitEventsTimeout
    }

    /**
     * Drags the files out of the window, which must already exist. See {@link #startFileDrag(FileDrag.Source, java.util.function.Consumer)}.
     */
    public void startFileDrag(List<java.nio.file.Path> files, java.util.function.Consumer<FileDrag.Outcome> done) {
        List<java.nio.file.Path> copy = List.copyOf(files);
        startFileDrag(() -> copy, done);
    }

    /**
     * Main thread, from the GLFW drop callback or the OLE drop target. See {@link #onFileDrop}.
     */
    private void notifyFilesDropped(List<java.nio.file.Path> paths, double x, double y) {
        hoverPending.set(null); // a hover still queued must not run after the drop
        java.util.function.Consumer<List<java.nio.file.Path>> listener = fileDropListener;
        if (listener != null) fire(() -> listener.accept(paths));
        java.util.function.Consumer<FileDrag.Drop> at = fileDropAtListener;
        if (at != null) fire(() -> at.accept(new FileDrag.Drop(paths, x, y)));
    }

    /**
     * Main thread, from the OLE drop target. See {@link #onFileDragOver}. Movement is coalesced: only the newest
     * position waits for the render thread.
     */
    private void notifyFilesOver(List<java.nio.file.Path> paths, double x, double y) {
        if (fileDragOverListener == null) return;
        if (hoverPending.getAndSet(new FileDrag.Hover(paths, x, y)) != null) return; // one is already queued; it will read this
        Scheduler.runLater(() -> {
            FileDrag.Hover hover = hoverPending.getAndSet(null);
            java.util.function.Consumer<FileDrag.Hover> listener = fileDragOverListener;
            if (hover != null && listener != null) listener.accept(hover);
        });
    }

    /**
     * Main thread, from the OLE drop target. See {@link #onFileDragLeave}.
     */
    private void notifyFilesLeft() {
        hoverPending.set(null);
        fire(fileDragLeaveListener);
    }

    /**
     * Main thread, from the GLFW close callback: whether the close goes ahead. See {@link #onCloseRequest}.
     */
    private boolean notifyCloseRequested() {
        Runnable listener = closeRequestListener;
        if (listener == null) return true;
        fire(listener);
        return false;
    }

    /**
     * Called by the engine once, after the loop has ended.
     */
    void notifyClosing() {
        Runnable listener = closeListener;
        if (listener == null) return;
        try {
            listener.run();
        } catch (Throwable t) {
            LogManager.getLogger("Engine").error("Window close listener threw", t);
        }
    }

    public boolean isMinimized() {
        return minimized;
    }

    public boolean isMaximized() {
        return windowOptions.isMaximized();
    }

    /**
     * Main thread, from the GLFW callback. Updates the state now and delivers the listener on the render thread.
     */
    private void notifyMinimized(boolean nowMinimized) {
        minimized = nowMinimized;
        fire(nowMinimized ? minimizeListener : restoreListener);
    }

    /**
     * Main thread, from the GLFW callback. See {@link #notifyMinimized}.
     */
    private void notifyMaximized(boolean nowMaximized) {
        // Entering fullscreen un-maximizes the OS window, but the request survives it.
        if (!windowOptions.isFullscreen()) windowOptions.syncMaximized(nowMaximized);
        fire(nowMaximized ? maximizeListener : restoreListener);
    }

    private static void fire(Runnable listener) {
        if (listener != null) Scheduler.runLater(listener);
    }

    /**
     * Main thread, from the GLFW window-size callback (not the framebuffer-size one).
     * See {@link #onResize}. The listener is handed the size current when it runs, not the
     * one from the callback, so it never disagrees with {@link WindowOptions} when several
     * resizes arrive before the render thread gets to it.
     */
    void notifyResized(int width, int height) {
        java.util.function.BiConsumer<Integer, Integer> listener = resizeListener;
        fire(listener == null ? null : () -> listener.accept(windowOptions.getWidth(), windowOptions.getHeight()));
    }

    /**
     * Closes the window, as if the user had clicked its close button. Closing the engine's first window, the one
     * given to {@link Engine#start}, closes every window and ends the engine loop. Safe from any thread.
     */
    public void close() {
        if (glfWindow != null) glfWindow.runOnMainThread(glfWindow::applyClose);
    }

    /**
     * Opens this window beside the running engine's others, as {@link Engine#open} does. Safe from any thread. A
     * window that was closed can be opened again.
     *
     * @throws IllegalStateException if no engine is running
     */
    public void open() {
        Engine engine = Engine.getRunning();
        if (engine == null) throw new IllegalStateException("No engine is running; start one with Engine.start(window)");
        engine.open(this);
    }

    public InputManager getInputManager() {
        return inputManager;
    }

    /**
     * The keyboard and mouse state for game code to poll, and the window's {@link me.piitex.engine.input.InputContext
     * input contexts}. See {@link Input}.
     */
    public Input getInput() {
        return input;
    }

    /**
     * The window's stack of game {@link me.piitex.engine.game.Scene scenes}. Scenes are drawn below every container
     * added with {@link #addContainer(Container)}. See {@link SceneManager}.
     */
    public SceneManager getSceneManager() {
        return sceneManager;
    }

    /**
     * Renders the whole window (backdrop plus every enabled container) into a {@link Snapshot}. See {@link Snapshot#of(Window, float)}.
     */
    public Snapshot takeSnapshot(float scale) {
        return Snapshot.of(this, scale);
    }

    /**
     * Captures at the start of the next frame and calls {@code callback} on the render thread. Safe from any thread.
     */
    public void takeSnapshotAsync(float scale, java.util.function.Consumer<Snapshot> callback) {
        Snapshot.ofAsync(this, scale, callback);
    }

    /**
     * What the window's canvas is cleared to at the start of every frame, before any
     * Container draws. This is the backdrop behind everything else, not any individual
     * Container's own background. A plain {@link Color} is the common case and is
     * cleared directly with no extra draw call. A
     * {@link me.piitex.engine.ui.color.LinearGradient} or
     * {@link me.piitex.engine.ui.color.ScrollingGradient} gives a gradient backdrop,
     * which Engine draws as a full-window quad each frame instead, since Skia's canvas
     * clear takes only a single flat color. A {@link me.piitex.engine.ui.color.RainbowColor}
     * is a Color, so it is cleared directly too and cycles through the colour wheel. This follows the active
     * {@link Theme#getWindowBackground()} by default (see {@link #applyTheme}), so it
     * does not need to be set just to follow a theme switch. Never null.
     */
    public Paint getBackgroundColor() {
        return backgroundColor.get();
    }

    /**
     * Sets the window backdrop, pinning it against future theme switches the same way
     * every other themed property in this engine pins an explicit set (see {@link
     * Overridable}). Pass null to reset to white. Call {@link #followThemeBackground()}
     * to unpin it and follow the theme again.
     */
    public void setBackgroundColor(Paint backgroundColor) {
        this.backgroundColor.set(backgroundColor == null ? Color.WHITE : backgroundColor);
    }

    /**
     * Undoes {@link #setBackgroundColor}: the backdrop follows the current {@link Theme}'s window
     * color again, now and on every later {@link ThemeManager#setCurrent}. Does nothing visible if
     * it was never pinned.
     */
    public void followThemeBackground() {
        backgroundColor.clearExplicit();
        background.clearExplicit();
        applyTheme(ThemeManager.getCurrent());
    }

    /**
     * A drawable, optionally animated effect painted over the flat {@link
     * #getBackgroundColor()} fill, behind every Container. This is the window-level
     * counterpart to {@link me.piitex.engine.ui.Element#setBackground}: the same
     * {@link Background} implementations, such as {@link
     * me.piitex.engine.ui.background.ConstellationBackground}, {@link
     * me.piitex.engine.ui.background.ElectricNodeBackground}, or {@link
     * me.piitex.engine.ui.background.DotGridBackground}, work here at the window's own
     * size instead of a single Element's. Null, the default, draws nothing extra.
     */
    public Background getBackground() {
        return background.get();
    }

    /**
     * Sets the window's attached background effect. Pass null to remove it. It is drawn
     * as-is on top of whatever {@link #getBackgroundColor()} resolves to, every frame.
     * Like {@link #setBackgroundColor} it pins the effect, so the one a theme asks for,
     * see {@link Theme#getWindowEffect()}, no longer replaces it; {@link #followThemeBackground()}
     * unpins it.
     */
    public void setBackground(Background background) {
        Background old = this.background.get();
        this.background.set(background);
        if (old != null && old != this.background.get()) old.onDetached();
    }

    /**
     * Applies {@code theme}'s window token as a baseline. This is a no-op once {@link #setBackgroundColor} has pinned the background. It is called automatically at construction and on every {@link ThemeManager#setCurrent}, since this Window registers itself. See the class docs.
     */
    @Override
    public void applyTheme(Theme theme) {
        Paint windowBackground = theme.getWindowBackground();
        backgroundColor.applyTheme(windowBackground == null ? Color.WHITE : Styling.ownCopy(windowBackground));
        Background made = null;
        if (theme.getWindowEffect() != null) {
            try {
                made = BackgroundKinds.apply(themeEffect, theme.getWindowEffect(), theme.assetBase());
            } catch (IllegalArgumentException e) {
                made = null; // a theme with a bad effect gives none, rather than breaking the window
            }
        }
        themeEffect = made;
        background.applyTheme(made);
    }

    public ConcurrentSkipListMap<Integer, Container> getContainers() {
        return containers;
    }

    public void addContainer(Container container) {
        // Never below 0, where the scene containers live (see SceneManager).
        int nextIndex = containers.isEmpty() ? 0 : Math.max(0, containers.lastKey() + 1);
        containers.put(nextIndex, container);
        container.setWindow(this);
    }

    /**
     * Adds every container of {@code containers} in order, each one on top of the previous. Same as calling
     * {@link #addContainer(Container)} for each. Null entries are skipped.
     */
    public void addContainers(Collection<? extends Container> containers) {
        for (Container container : containers) {
            if (container != null) addContainer(container);
        }
    }

    /**
     * As {@link #addContainers(Collection)}: {@code window.addContainers(background, main, hud)}.
     */
    public void addContainers(Container... containers) {
        addContainers(Arrays.asList(containers));
    }

    public void addContainer(Container container, int index) {
        containers.put(index, container);
        container.setWindow(this);
    }

    public boolean removeContainer(int index) {
        if (containers.containsKey(index)) {
            containers.remove(index);
            return true;
        }
        return false;
    }

    public boolean removeContainer(Container container) {
        Map<Integer, Container> toRemove = new HashMap<>();
        containers.entrySet().forEach(entry -> {
            if (entry.getValue() == container) {
                toRemove.put(entry.getKey(), entry.getValue());
            }
        });

        if (!toRemove.isEmpty()) {
            toRemove.forEach(containers::remove);
            return true;
        }

        return false;
    }

    public boolean removeContainer(int index, Container container) {
        return containers.remove(index, container);
    }

    public void clear() {
        containers.clear();
    }

    /**
     * Shows {@code popup} as a top-level Container above everything else, on the
     * floating layer that dropdown lists, menus, and dialogs live on. Only one popup is
     * open at a time: showing another closes the current one first. While it is open, a
     * mouse press outside {@code popup} or the Escape key dismisses it (see
     * {@link me.piitex.engine.input.InputProcessor}), and that press is consumed rather
     * than also reaching whatever is underneath. {@code onDismiss} runs whenever the
     * popup closes, by any route, so its owner can reset its own state.
     * <p>
     * The popup stays where it was placed. Use {@link #showPopup(Container, Element, Runnable)}
     * to have it follow the Element that opened it.
     */
    public void showPopup(Container popup, Runnable onDismiss) {
        showPopup(popup, null, onDismiss);
    }

    /**
     * As {@link #showPopup(Container, Runnable)}, but the popup follows {@code anchor}: it keeps
     * the offset from {@code anchor} it had when shown, so it moves along when the anchor is
     * dragged, scrolled or re-laid out. Place the popup before calling this.
     * <p>
     * It is kept inside the window while it follows, including when the window shrinks. A popup
     * shown below its anchor (a dropdown list) flips above it when there is no room below and
     * there is above, and one shown above flips below the same way; otherwise it is pushed back
     * inside the edge. It closes on its own once the anchor is removed from the window, disabled,
     * or clipped entirely out of view (a dropdown scrolled out of its scroll container, say). A
     * null {@code anchor} leaves the popup where it is.
     */
    public void showPopup(Container popup, Element anchor, Runnable onDismiss) {
        closePopup();
        this.popup = popup;
        this.popupDismissListener = onDismiss;
        this.popupAnchor = anchor;
        if (anchor != null) {
            anchorStartX = anchor.getAbsoluteX();
            anchorStartY = anchor.getAbsoluteY();
            popupShiftX = 0;
            popupShiftY = 0;
            double anchorBottom = anchorStartY + anchor.getHeight();
            double popupBottom = popup.getY() + popup.getHeight();
            if (popup.getY() >= anchorBottom - 0.5) {
                popupSide = 1;
                popupGap = popup.getY() - anchorBottom;
            } else if (popupBottom <= anchorStartY + 0.5) {
                popupSide = -1;
                popupGap = anchorStartY - popupBottom;
            } else {
                popupSide = 0;
                popupGap = 0;
            }
        }
        addContainer(popup);
    }

    /**
     * The whole window: popups are kept inside it.
     */
    @Override
    public double[] getPopupArea() {
        return new double[]{0, 0, windowOptions.getWidth(), windowOptions.getHeight()};
    }

    /**
     * The Element the open popup follows, or null if there is no popup or it is not anchored.
     */
    public Element getPopupAnchor() {
        return popupAnchor;
    }

    /**
     * Moves the open popup to stay at its offset from its anchor, inside the window, or closes it
     * if the anchor is no longer showing. The engine calls this each frame after the update pass,
     * once layouts have placed their children.
     */
    void followPopupAnchor() {
        Container open = popup;
        Element anchor = popupAnchor;
        if (open == null || anchor == null) return;
        if (!isShowing(anchor)) {
            closePopup();
            return;
        }
        // Where the popup would be had the anchor never moved. Read live, so a popup that resizes
        // itself (a menu opening a submenu) is still measured correctly.
        double baseX = open.getX() - popupShiftX;
        double baseY = open.getY() - popupShiftY;
        double width = open.getWidth();
        double height = open.getHeight();
        double winW = windowOptions.getWidth();
        double winH = windowOptions.getHeight();
        double anchorTop = anchor.getAbsoluteY();
        double anchorBottom = anchorTop + anchor.getHeight();

        double x = baseX + anchor.getAbsoluteX() - anchorStartX;
        double y = baseY + anchorTop - anchorStartY;
        if (popupSide > 0 && y + height > winH && anchorTop - popupGap - height >= 0) {
            y = anchorTop - popupGap - height; // no room below any more, so flip above
        } else if (popupSide < 0 && y < 0 && anchorBottom + popupGap + height <= winH) {
            y = anchorBottom + popupGap; // no room above any more, so flip below
        }
        // Push back inside the window; the left/top edge wins for a popup larger than the window.
        x = Math.max(0, Math.min(x, winW - width));
        y = Math.max(0, Math.min(y, winH - height));

        popupShiftX = x - baseX;
        popupShiftY = y - baseY;
        if (x != open.getX()) open.setX(x);
        if (y != open.getY()) open.setY(y);
    }

    /**
     * Whether {@code element} is on this window, enabled along with all its ancestors, and not
     * clipped entirely away by a clipping ancestor or the window's edges.
     */
    private boolean isShowing(Element element) {
        double left = element.getAbsoluteX();
        double top = element.getAbsoluteY();
        double right = left + element.getWidth();
        double bottom = top + element.getHeight();
        Element node = element;
        while (node.owner != null) {
            Container owner = node.owner;
            if (!node.isEnabled() || !owner.getElements().containsValue(node)) return false;
            if (owner.isClipping()) {
                left = Math.max(left, owner.getAbsoluteX());
                top = Math.max(top, owner.getAbsoluteY());
                right = Math.min(right, owner.getAbsoluteX() + owner.getWidth());
                bottom = Math.min(bottom, owner.getAbsoluteY() + owner.getHeight());
            }
            node = owner;
        }
        if (!node.isEnabled() || !(node instanceof Container root) || !containers.containsValue(root)) return false;
        right = Math.min(right, windowOptions.getWidth());
        bottom = Math.min(bottom, windowOptions.getHeight());
        return Math.max(left, 0) <= right && Math.max(top, 0) <= bottom;
    }

    /**
     * Closes the current popup, if any, firing its dismiss callback.
     */
    public void closePopup() {
        if (popup == null) return;
        Container closing = popup;
        Runnable listener = popupDismissListener;
        popup = null;
        popupDismissListener = null;
        popupAnchor = null;
        removeContainer(closing);
        if (listener != null) listener.run();
    }

    /**
     * Shows {@code modal} above everything, including any modal that is already open, and clears
     * keyboard focus. Modals stack: the new one becomes the active modal ({@link #getModal()}),
     * takes the keyboard and Tab traversal, and closing it reveals the one beneath. Any open popup
     * is closed first. Showing a modal that is already open moves it back to the top. This is
     * normally called through {@link ModalContainer#show(Window)}.
     */
    public void showModal(ModalContainer modal) {
        closePopup();
        requestFocus(null);
        if (modals.remove(modal)) removeContainer(modal);
        modals.add(modal);
        addContainer(modal);
    }

    /**
     * Closes the active (topmost) modal, if any, and clears focus. The modal beneath it, if there is
     * one, becomes active again.
     */
    public void closeModal() {
        if (!modals.isEmpty()) closeModal(modals.getLast());
    }

    /**
     * Closes {@code modal} wherever it is in the stack, and clears focus. A no-op if it is not
     * showing in this window.
     */
    public void closeModal(ModalContainer modal) {
        if (!modals.remove(modal)) return;
        removeContainer(modal);
        requestFocus(null);
        modal.notifyClosed();
    }

    /**
     * Closes every open modal, topmost first.
     */
    public void closeAllModals() {
        while (!modals.isEmpty()) closeModal();
    }

    /**
     * True while a locking transition is playing, a {@link me.piitex.engine.game.SceneTransition#lock scene} one or an
     * {@link me.piitex.engine.ui.animation.Transition#isLocking() Element} one: the scenes are frozen, nothing is hovered and the window ignores
     * presses and the wheel. A key press or click ends the {@link me.piitex.engine.ui.animation.Transition#isSkippable() skippable} ones. An
     * Element's transition is noticed on the frame after it starts.
     */
    public boolean isInputLocked() {
        return sceneManager.isInputLocked() || !lockingElements.isEmpty();
    }

    /**
     * Render thread, called by the input processor for a key press or repeat, a mouse button press, a character or a
     * wheel turn. Returns true if the game is {@link #isInputLocked() locked}, so the event must go no further. A
     * press ({@code skip} true, so not a wheel turn, a character or a key repeat) first ends the locking transitions
     * that are skippable.
     */
    public boolean swallowInput(boolean skip) {
        if (sceneManager.swallowInput(skip)) return true;
        if (lockingElements.isEmpty()) return false;
        if (skip) {
            for (Element element : List.copyOf(lockingElements)) element.skipLockingTransitions();
            refreshTransitionLock();
        }
        return true;
    }

    /**
     * Called by the engine once a frame, after the update pass: looks through the enabled Elements for ones with a
     * running locking transition, which {@link #isInputLocked()} then reports until the next frame or a skip.
     */
    public void refreshTransitionLock() {
        lockingElements.clear();
        for (Container container : containers.values()) collectLocking(container);
    }

    private void collectLocking(Element element) {
        if (!element.isEnabled()) return;
        if (element.hasLockingTransition()) lockingElements.add(element);
        if (element instanceof Container container) {
            for (Element child : container.getElements().values()) collectLocking(child);
        }
    }

    /**
     * The active modal: the topmost one, or null if none is open.
     */
    public ModalContainer getModal() {
        return modals.isEmpty() ? null : modals.getLast();
    }

    /**
     * Every open modal, bottom to top. A copy.
     */
    public List<ModalContainer> getModals() {
        return List.copyOf(modals);
    }

    /**
     * Whether {@code modal} is open in this window, at any depth in the stack.
     */
    public boolean isShowingModal(ModalContainer modal) {
        return modals.contains(modal);
    }

    /**
     * The currently open popup, or null.
     */
    public Container getPopup() {
        return popup;
    }

    /**
     * The Element currently receiving KEY/CHAR input, or null if nothing is focused.
     */
    public Element getFocusedElement() {
        return focusedElement;
    }

    /**
     * Adds a {@link me.piitex.engine.input.ShortcutHandler} that is offered every key combination before the focused
     * element or the game. A {@link me.piitex.engine.ui.menu.MenuBarOverlay} adds itself while it is in the window.
     * Adding the same handler twice has no effect.
     */
    public void addShortcutHandler(me.piitex.engine.input.ShortcutHandler handler) {
        shortcutHandlers.addIfAbsent(handler);
    }

    public void removeShortcutHandler(me.piitex.engine.input.ShortcutHandler handler) {
        shortcutHandlers.remove(handler);
    }

    /**
     * Offers a key press to the shortcut handlers in the order they were added; true if one used it.
     */
    public boolean dispatchShortcut(int key, int mods) {
        for (me.piitex.engine.input.ShortcutHandler handler : shortcutHandlers) {
            if (handler.onShortcut(key, mods)) return true;
        }
        return false;
    }

    /**
     * Moves keyboard focus to {@code element}, firing {@link Focusable#onFocusLost()}
     * on whatever previously held it and {@link Focusable#onFocusGained()} on the new
     * one. Either callback is skipped if that Element is not {@link Focusable}. Pass
     * null to clear focus entirely, such as when a click lands on empty space. A no-op
     * if {@code element} already holds focus.
     */
    public void requestFocus(Element element) {
        if (element != null && element.isEffectivelyDisabled()) element = null; // a disabled Element cannot take the keyboard
        if (focusedElement == element) return;

        if (focusedElement instanceof Focusable focusable) {
            focusable.onFocusLost();
        }
        focusedElement = element;
        if (focusedElement instanceof Focusable focusable) {
            focusable.onFocusGained();
        }
    }

    /**
     * Moves focus to the next tab stop after {@link #getFocusedElement()}. See
     * {@link Element#isFocusTraversable()} and {@link Element#getTabIndex()} for what
     * qualifies and what order the stops come in. Focus wraps from the last stop back to
     * the first. If nothing is currently focused, the first stop is focused; it is a
     * no-op if there are no tab stops at all.
     * {@link me.piitex.engine.input.InputProcessor} calls this on a plain Tab key press,
     * so it is rarely called directly.
     */
    public void focusNext() {
        moveFocus(1);
    }

    /**
     * As {@link #focusNext()}, but backwards, wrapping from the first stop to the last. Called on Shift+Tab.
     */
    public void focusPrevious() {
        moveFocus(-1);
    }

    private void moveFocus(int direction) {
        List<Element> stops = collectFocusTraversalOrder();
        if (stops.isEmpty()) return;

        int currentIndex = stops.indexOf(focusedElement);
        int nextIndex = currentIndex < 0
                ? (direction > 0 ? 0 : stops.size() - 1)
                : Math.floorMod(currentIndex + direction, stops.size());
        requestFocus(stops.get(nextIndex));
    }

    /**
     * Every enabled, {@link Element#isFocusTraversable()} Element that also implements
     * {@link Focusable}, walked depth-first across {@link #containers} in draw order,
     * the same order {@link Container#renderChildren(Renderer)} draws in. The result is
     * then stably sorted so any non-negative {@link Element#getTabIndex()} comes first
     * in ascending order, ahead of natural order, per that method's docs.
     */
    private List<Element> collectFocusTraversalOrder() {
        List<Element> stops = new ArrayList<>();
        ModalContainer modal = getModal();
        if (modal != null) {
            collectFocusTraversalOrder(modal, stops); // the active modal traps Tab
        } else {
            for (Container container : containers.values()) {
                collectFocusTraversalOrder(container, stops);
            }
        }
        me.piitex.engine.input.UiHitTest.sortTabOrder(stops);
        return stops;
    }

    private void collectFocusTraversalOrder(Container container, List<Element> out) {
        me.piitex.engine.input.UiHitTest.collectFocusStops(container, out);
    }
}
