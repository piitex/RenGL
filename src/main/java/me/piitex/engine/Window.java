package me.piitex.engine;

import me.piitex.engine.gl.GLFWindow;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.gl.renderer.Snapshot;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.input.InputManager;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.containers.ModalContainer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
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
public class Window implements Themeable {
    private final WindowOptions windowOptions;
    private final ConcurrentSkipListMap<Integer, Container> containers = new ConcurrentSkipListMap<>();
    private final InputManager inputManager = new InputManager();
    private GLFWindow glfWindow;
    private Element focusedElement;
    private final Overridable<Paint> backgroundColor = new Overridable<>(Color.WHITE);
    private Container popup;
    private Runnable popupDismissListener;
    private ModalContainer modal;
    private volatile boolean hidden;
    private volatile boolean minimized, maximized;
    private volatile Runnable closeListener;
    private volatile Runnable minimizeListener, maximizeListener, restoreListener;


    public Window(WindowOptions windowOptions) {
        this.windowOptions = windowOptions;
        ThemeManager.register(this);
    }

    protected void initializeGLFW() {
        glfWindow = new GLFWindow(inputManager);
        glfWindow.setIconifyHandler(this::notifyMinimized);
        glfWindow.setMaximizeHandler(this::notifyMaximized);
    }

    public WindowOptions getWindowOptions() {
        return windowOptions;
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
        if (glfWindow != null) {
            WindowStyle applied = windowOptions.getStyle();
            glfWindow.runOnMainThread(() -> glfWindow.applyStyle(applied));
        }
    }

    public WindowStyle getStyle() {
        return windowOptions.getStyle();
    }

    /** Keeps the window above other windows, or stops doing so. Safe from any thread. */
    public void setAlwaysOnTop(boolean alwaysOnTop) {
        windowOptions.setAlwaysOnTop(alwaysOnTop);
        if (glfWindow != null) {
            glfWindow.runOnMainThread(() -> glfWindow.applyAlwaysOnTop(alwaysOnTop));
        }
    }

    /** Resizes the window (in screen coordinates). Only works once the engine has started. Safe from any thread. */
    public void setSize(int width, int height) {
        if (glfWindow != null) {
            glfWindow.runOnMainThread(() -> glfWindow.applySize(width, height));
        }
    }

    /** Centers the window on the primary monitor. Only works once the engine has started. Safe from any thread. */
    public void center() {
        if (glfWindow != null) {
            glfWindow.runOnMainThread(glfWindow::applyCenter);
        }
    }

    /** Changes the OS window title. Only works once the engine has started. Safe from any thread. */
    public void setTitle(String title) {
        windowOptions.setTitle(title);
        if (glfWindow != null) {
            glfWindow.runOnMainThread(() -> glfWindow.applyTitle(title));
        }
    }

    /**
     * Changes the window icon while running, from an image loaded with {@code ImageCache.load(...)}. On macOS this
     * sets the Dock icon, since macOS windows have no icon of their own. Safe from any thread.
     */
    public void setIcon(Image icon) {
        windowOptions.setIcon(icon);
        if (glfWindow != null) {
            glfWindow.runOnMainThread(() -> glfWindow.applyIcon(icon));
        }
    }

    /** Allows or forbids the user resizing the window. {@link #setSize} still works. Safe from any thread. */
    public void setResizable(boolean resizable) {
        windowOptions.setResizable(resizable);
        if (glfWindow != null) {
            glfWindow.runOnMainThread(() -> glfWindow.applyResizable(resizable));
        }
    }

    /**
     * Enters or leaves fullscreen. Follows the current {@link WindowStyle}: {@code BORDERLESS} gives borderless
     * fullscreen (no display mode switch), {@code STANDARD} takes the monitor exclusively. Leaving restores the
     * position and size from before. Safe from any thread.
     */
    public void setFullscreen(boolean fullscreen) {
        windowOptions.setFullscreen(fullscreen);
        if (glfWindow != null) {
            boolean borderless = windowOptions.getStyle() == WindowStyle.BORDERLESS;
            glfWindow.runOnMainThread(() -> glfWindow.applyFullscreen(fullscreen, borderless));
        }
    }

    public boolean isFullscreen() {
        return windowOptions.isFullscreen();
    }

    /** Moves the window's top-left corner to {@code x, y} in screen coordinates. Safe from any thread. */
    public void setPosition(int x, int y) {
        if (glfWindow != null) {
            glfWindow.runOnMainThread(() -> glfWindow.applyPosition(x, y));
        }
    }

    /** Limits how small or large the user can resize the window. A negative value means no limit on that side. Safe from any thread. */
    public void setSizeLimits(int minWidth, int minHeight, int maxWidth, int maxHeight) {
        if (glfWindow != null) {
            glfWindow.runOnMainThread(() -> glfWindow.applySizeLimits(minWidth, minHeight, maxWidth, maxHeight));
        }
    }

    /** Whole-window opacity from 0 (invisible) to 1. Not every platform supports it. Safe from any thread. */
    public void setOpacity(float opacity) {
        float clamped = Math.max(0f, Math.min(1f, opacity));
        if (glfWindow != null) {
            glfWindow.runOnMainThread(() -> glfWindow.applyOpacity(clamped));
        }
    }

    /** Shows or hides the OS cursor over the window. It still moves freely. Safe from any thread. */
    public void setCursorVisible(boolean visible) {
        windowOptions.setShowCursor(visible);
        if (glfWindow != null) {
            glfWindow.runOnMainThread(() -> glfWindow.applyCursorVisible(visible));
        }
    }

    /** See {@link WindowOptions#setVsync}. Picked up on the next frame. Safe from any thread. */
    public void setVsync(boolean vsync) {
        windowOptions.setVsync(vsync);
    }

    /** See {@link WindowOptions#setMaxFPS}. Only used while vsync is off. Safe from any thread. */
    public void setMaxFPS(int maxFPS) {
        windowOptions.setMaxFPS(maxFPS);
    }

    /** Minimizes to the Dock or taskbar. Safe from any thread. */
    public void minimize() {
        if (glfWindow != null) glfWindow.runOnMainThread(glfWindow::applyMinimize);
    }

    /** Maximizes the window. Does nothing if it isn't resizable. Safe from any thread. */
    public void maximize() {
        if (glfWindow != null) glfWindow.runOnMainThread(glfWindow::applyMaximize);
    }

    /** Undoes {@link #minimize()} or {@link #maximize()}. Safe from any thread. */
    public void restore() {
        if (glfWindow != null) glfWindow.runOnMainThread(glfWindow::applyRestore);
    }

    /** {@code true} minimizes, {@code false} un-minimizes. Does nothing if the window is already in that state. Safe from any thread. */
    public void setMinimized(boolean minimized) {
        if (glfWindow != null) glfWindow.runOnMainThread(() -> glfWindow.applyMinimized(minimized));
    }

    /**
     * {@code true} maximizes, {@code false} returns to normal size. Does nothing if the window is already in that
     * state, if it isn't resizable, or (for {@code false}) while it is minimized. Safe from any thread.
     */
    public void setMaximized(boolean maximized) {
        if (glfWindow != null) glfWindow.runOnMainThread(() -> glfWindow.applyMaximized(maximized));
    }

    /** Brings the window to the front and gives it OS focus. Safe from any thread. */
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

    /** Shows the window again after {@link #hide()}. Safe from any thread. */
    public void show() {
        hidden = false;
        if (glfWindow != null) glfWindow.runOnMainThread(glfWindow::applyShow);
    }

    /** True between a {@link #hide()} and the next {@link #show()}. */
    public boolean isHidden() {
        return hidden;
    }

    /** Runs when the window is minimized, by the user or by {@link #minimize()}. Runs on the render thread. Pass null to remove. */
    public void onMinimize(Runnable listener) {
        this.minimizeListener = listener;
    }

    /** Runs when the window is maximized, by the user or by {@link #maximize()}. Runs on the render thread. Pass null to remove. */
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
     * Runs once when the window is closing, by the user's close button, {@link #close()}, or the engine
     * stopping, after rendering has stopped and before {@link Engine#start} returns (so before the JVM exits
     * if that is the last thing {@code main} does). Use it for cleanup: saving state, closing files and
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

    /** Called by the engine once, after the loop has ended. */
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
        return maximized;
    }

    /** Main thread, from the GLFW callback. Updates the state now and delivers the listener on the render thread. */
    private void notifyMinimized(boolean nowMinimized) {
        minimized = nowMinimized;
        fire(nowMinimized ? minimizeListener : restoreListener);
    }

    /** Main thread, from the GLFW callback. See {@link #notifyMinimized}. */
    private void notifyMaximized(boolean nowMaximized) {
        maximized = nowMaximized;
        fire(nowMaximized ? maximizeListener : restoreListener);
    }

    private static void fire(Runnable listener) {
        if (listener != null) Scheduler.runLater(listener);
    }

    /** Ends the engine loop, as if the user had clicked the close button. Safe from any thread. */
    public void close() {
        if (glfWindow != null) glfWindow.runOnMainThread(glfWindow::applyClose);
    }

    public InputManager getInputManager() {
        return inputManager;
    }

    /** Renders the whole window (backdrop plus every enabled container) into a {@link Snapshot}. See {@link Snapshot#of(Window, float)}. */
    public Snapshot takeSnapshot(float scale) {
        return Snapshot.of(this, scale);
    }

    /** Captures at the start of the next frame and calls {@code callback} on the render thread. Safe from any thread. */
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
     * clear takes only a single flat color. This follows the active
     * {@link Theme#getWindowBackground()} by default (see {@link #applyTheme}), so it
     * does not need to be set just to follow a theme switch. Never null.
     */
    public Paint getBackgroundColor() {
        return backgroundColor.get();
    }

    /**
     * Sets the window backdrop, pinning it against future theme switches the same way
     * every other themed property in this engine pins an explicit set (see {@link
     * Overridable}). Pass null to reset to white. There is no way to unpin it and go
     * back to following the theme; construct a fresh Window instead, as elsewhere in
     * this toolkit.
     */
    public void setBackgroundColor(Paint backgroundColor) {
        this.backgroundColor.set(backgroundColor == null ? Color.WHITE : backgroundColor);
    }

    /** Applies {@code theme}'s window token as a baseline. This is a no-op once {@link #setBackgroundColor} has pinned the background. It is called automatically at construction and on every {@link ThemeManager#setCurrent}, since this Window registers itself. See the class docs. */
    @Override
    public void applyTheme(Theme theme) {
        Paint windowBackground = theme.getWindowBackground();
        backgroundColor.applyTheme(windowBackground == null ? Color.WHITE : windowBackground);
    }

    public ConcurrentSkipListMap<Integer, Container> getContainers() {
        return containers;
    }

    public void addContainer(Container container) {
        int nextIndex = containers.isEmpty() ? 0 : containers.lastKey() + 1;
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

    /** As {@link #addContainers(Collection)}: {@code window.addContainers(background, main, hud)}. */
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

    /**
     * Shows {@code popup} as a top-level Container above everything else, on the
     * floating layer that dropdown lists, menus, and dialogs live on. Only one popup is
     * open at a time: showing another closes the current one first. While it is open, a
     * mouse press outside {@code popup} or the Escape key dismisses it (see
     * {@link me.piitex.engine.input.InputProcessor}), and that press is consumed rather
     * than also reaching whatever is underneath. {@code onDismiss} runs whenever the
     * popup closes, by any route, so its owner can reset its own state.
     */
    public void showPopup(Container popup, Runnable onDismiss) {
        closePopup();
        this.popup = popup;
        this.popupDismissListener = onDismiss;
        addContainer(popup);
    }

    /** Closes the current popup, if any, firing its dismiss callback. */
    public void closePopup() {
        if (popup == null) return;
        Container closing = popup;
        Runnable listener = popupDismissListener;
        popup = null;
        popupDismissListener = null;
        removeContainer(closing);
        if (listener != null) listener.run();
    }

    /**
     * Shows {@code modal} above everything, closing any open popup or previous modal
     * first, and clears keyboard focus. Tab traversal is then limited to the modal; see
     * {@link #focusNext()}. This is normally called through
     * {@link ModalContainer#show(Window)}.
     */
    public void showModal(ModalContainer modal) {
        closePopup();
        closeModal();
        requestFocus(null);
        this.modal = modal;
        addContainer(modal);
    }

    /** Closes the current modal, if any, and clears focus. */
    public void closeModal() {
        if (modal == null) return;
        ModalContainer closing = modal;
        modal = null;
        removeContainer(closing);
        requestFocus(null);
        closing.notifyClosed();
    }

    /** The currently shown modal, or null. */
    public ModalContainer getModal() {
        return modal;
    }

    /** The currently open popup, or null. */
    public Container getPopup() {
        return popup;
    }

    /** The Element currently receiving KEY/CHAR input, or null if nothing is focused. */
    public Element getFocusedElement() {
        return focusedElement;
    }

    /**
     * Moves keyboard focus to {@code element}, firing {@link Focusable#onFocusLost()}
     * on whatever previously held it and {@link Focusable#onFocusGained()} on the new
     * one. Either callback is skipped if that Element is not {@link Focusable}. Pass
     * null to clear focus entirely, such as when a click lands on empty space. A no-op
     * if {@code element} already holds focus.
     */
    public void requestFocus(Element element) {
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

    /** As {@link #focusNext()}, but backwards, wrapping from the first stop to the last. Called on Shift+Tab. */
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
        if (modal != null) {
            collectFocusTraversalOrder(modal, stops); // a modal traps Tab
        } else {
            for (Container container : containers.values()) {
                collectFocusTraversalOrder(container, stops);
            }
        }
        stops.sort(Comparator.comparingInt(e -> e.getTabIndex() < 0 ? Integer.MAX_VALUE : e.getTabIndex()));
        return stops;
    }

    private void collectFocusTraversalOrder(Container container, List<Element> out) {
        if (!container.isEnabled()) return;
        for (Element child : container.getElements().values()) {
            if (!child.isEnabled()) continue;
            if (child.isFocusTraversable() && child instanceof Focusable) {
                out.add(child);
            }
            if (child instanceof Container childContainer) {
                collectFocusTraversalOrder(childContainer, out);
            }
        }
    }
}
