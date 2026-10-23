package me.piitex.engine;

import org.jetbrains.skia.Image;

/**
 * The settings of a {@link Window}, in one place. Build it before the window opens and hand it to
 * {@code new Window(options)}; the window opens with those settings.
 * <p>
 * It stays connected to its window afterwards: calling a setter on a running window changes the window. So
 * {@code window.getWindowOptions().setWidth(1280)} resizes it, and {@code setTitle}, {@code setStyle},
 * {@code setFullscreen}, {@code setMaximized} and the rest behave the same. Getters always return the window's current state, including
 * a size the user dragged it to. Every setter is safe to call from any thread.
 * <p>
 * {@link #setRenderer} is live too, but it costs the most: the window is created for one graphics API, so the
 * engine closes the OS window and opens it again.
 */
public class WindowOptions {
    private volatile Window window; // the window these options drive, once it is constructed
    private volatile String title;
    // Written by the main thread on OS resize, read by the render thread.
    private volatile int width, height;
    private volatile int designWidth, designHeight; // what the window was asked to open at; see markOpened
    private volatile boolean fullscreen = false;
    private volatile boolean maximized = false;
    private volatile WindowStyle style = WindowStyle.STANDARD;
    private volatile boolean alwaysOnTop = false;
    private volatile boolean vsync = true;
    private volatile boolean showCursor = true;
    private volatile boolean handleFocus = false;
    private volatile boolean resizable = true;
    private volatile RenderAPI renderer = RenderAPI.AUTO;
    private volatile int maxFPS = 60;
    private volatile int backgroundFPS = 30;
    private volatile String iconPath = null;
    private volatile Image icon = null;

    public WindowOptions(String title) {
        this.title = title;
    }

    /**
     * Called by the {@link Window} constructor. A set of options drives exactly one window.
     */
    void bind(Window window) {
        if (this.window != null && this.window != window) {
            throw new IllegalStateException("These WindowOptions already belong to another Window; use one WindowOptions per Window");
        }
        this.window = window;
    }

    /**
     * The OS reported a new size. Records it without telling the window to resize, since it already has that size.
     */
    void syncDimensions(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     * The window opened. Remembers the size it was asked for, before the OS maximized it or fit it to the screen.
     * Only the first open counts: a window reopened for a renderer swap keeps its design size.
     */
    void markOpened() {
        if (designWidth == 0) {
            designWidth = width;
            designHeight = height;
        }
    }

    /**
     * The width the window was asked to open at, which the UI was built against. It differs from {@link #getWidth()}
     * when the window opened maximized or the OS shrank it to fit the screen. 0 until the window has opened.
     */
    public int getDesignWidth() {
        return designWidth;
    }

    /**
     * The height the window was asked to open at. See {@link #getDesignWidth()}.
     */
    public int getDesignHeight() {
        return designHeight;
    }

    /**
     * The size of the window's content area in screen coordinates. On a running window this resizes it, which fires
     * {@link Window#onResize} on the next frame the same as a drag of the border would.
     */
    public WindowOptions setDimensions(int width, int height) {
        this.width = width;
        this.height = height;
        Window w = window;
        if (w != null) w.pushSize(width, height);
        return this;
    }

    /**
     * Changes the width and keeps the height. See {@link #setDimensions}.
     */
    public WindowOptions setWidth(int width) {
        return setDimensions(width, height);
    }

    /**
     * Changes the height and keeps the width. See {@link #setDimensions}.
     */
    public WindowOptions setHeight(int height) {
        return setDimensions(width, height);
    }

    /**
     * Enters or leaves fullscreen. Follows the current {@link WindowStyle}: {@code BORDERLESS} gives borderless
     * fullscreen (no display mode switch), {@code STANDARD} takes the monitor exclusively (on macOS both give a frameless
     * window the size of the screen). Leaving restores the
     * position and size from before.
     */
    public WindowOptions setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
        Window w = window;
        if (w != null) w.pushFullscreen();
        return this;
    }

    /**
     * Maximizes the window or returns it to its normal size (default off). Before the window opens it starts
     * maximized. Does nothing while the window is not {@linkplain #setResizable resizable}. While it is fullscreen the
     * request is remembered and applies once fullscreen ends. {@link #isMaximized()} also follows the user's own
     * maximize and restore clicks.
     */
    public WindowOptions setMaximized(boolean maximized) {
        this.maximized = maximized;
        Window w = window;
        if (w != null) w.pushMaximized();
        return this;
    }

    /**
     * The OS maximized or restored the window (a title bar click, a snap). Records it without telling the window.
     */
    void syncMaximized(boolean maximized) {
        this.maximized = maximized;
    }

    /**
     * {@code false} is the same as {@link WindowStyle#BORDERLESS}, {@code true} as {@link WindowStyle#STANDARD}.
     */
    public WindowOptions setWindowed(boolean windowed) {
        return setStyle(windowed ? WindowStyle.STANDARD : WindowStyle.BORDERLESS);
    }

    /**
     * The frame around the window (default {@link WindowStyle#STANDARD}), for example {@link WindowStyle#BORDERLESS}
     * for a splash screen and {@code STANDARD} once the main UI is ready.
     */
    public WindowOptions setStyle(WindowStyle style) {
        this.style = style == null ? WindowStyle.STANDARD : style;
        Window w = window;
        if (w != null) w.pushStyle();
        return this;
    }

    /**
     * Keeps the window above other windows (default off).
     */
    public WindowOptions setAlwaysOnTop(boolean alwaysOnTop) {
        this.alwaysOnTop = alwaysOnTop;
        Window w = window;
        if (w != null) w.pushAlwaysOnTop();
        return this;
    }

    /**
     * Syncs frame presentation to the display's refresh rate (default on). While on, the frame rate follows the
     * display and {@link #setMaxFPS} is not consulted. While off, the loop runs at {@code maxFPS}.
     * The render loop picks up a change on the next frame.
     */
    public WindowOptions setVsync(boolean vsync) {
        this.vsync = vsync;
        return this;
    }

    /**
     * The graphics API to draw with (default {@link RenderAPI#AUTO}). A window is made for one API, so on a running
     * window the engine closes the OS window and opens it again with the new one. The {@link Window}, its containers
     * and scenes are kept and no close listener runs, but the window flickers, its 3D viewports start afresh, and
     * its renderer changes on the next frames rather than at once. A backend this machine can't run falls back to the
     * platform default; {@link Window#getRenderer()} says what the window really uses.
     *
     * @throws IllegalStateException when OpenGL is asked for on macOS (the option keeps its old value)
     */
    public WindowOptions setRenderer(RenderAPI renderer) {
        RenderAPI previous = this.renderer;
        this.renderer = renderer == null ? RenderAPI.AUTO : renderer;
        Window w = window;
        if (w != null) {
            try {
                w.pushRenderer();
            } catch (RuntimeException e) {
                this.renderer = previous;
                throw e;
            }
        }
        return this;
    }

    /**
     * Puts the API back after the engine could not start the one asked for. Does not touch the window.
     */
    void restoreRenderer(RenderAPI renderer) {
        this.renderer = renderer;
    }

    /**
     * Shows or hides the OS cursor over the window (default shown). It still moves freely. While the cursor is
     * {@linkplain Window#setCursorLocked locked} it stays hidden, and this takes effect once it is freed.
     */
    public WindowOptions setShowCursor(boolean showCursor) {
        this.showCursor = showCursor;
        Window w = window;
        if (w != null) w.pushCursorVisible();
        return this;
    }

    /**
     * The frame rate cap used while vsync is off (default 60). Zero or negative means unlimited. The render loop
     * picks up a change on the next frame.
     */
    public WindowOptions setMaxFPS(int maxFPS) {
        this.maxFPS = maxFPS;
        return this;
    }

    /**
     * The frame rate cap while no window of the application has focus (default 30), vsync or not. A background
     * window redraws less often, which saves power and heat; it speeds up again the moment a window is focused.
     * Zero or negative means no cap of its own: the usual vsync or {@link #setMaxFPS} pacing applies. The cap is read from
     * the window that paces the loop, and the render loop picks up a change on the next frame.
     */
    public WindowOptions setBackgroundFPS(int backgroundFPS) {
        this.backgroundFPS = backgroundFPS;
        return this;
    }

    /**
     * When on, keyboard focus is cleared as the OS window loses focus (default off). Checked every frame.
     */
    public WindowOptions setHandleFocus(boolean handleFocus) {
        this.handleFocus = handleFocus;
        return this;
    }

    /**
     * An icon file on disk or on the classpath. See {@link #setIcon(Image)} to use an already loaded image instead.
     * On macOS this sets the Dock icon, since macOS windows have no icon of their own.
     */
    public WindowOptions setIconPath(String iconPath) {
        this.iconPath = iconPath;
        Window w = window;
        if (w != null && iconPath != null) w.pushIconPath(iconPath);
        return this;
    }

    /**
     * The window icon, as an image already loaded through {@code ImageCache.load(...)}. Takes precedence over
     * {@link #setIconPath}. On macOS this sets the Dock icon, since macOS windows have no icon of their own. Pass
     * null for no icon.
     */
    public WindowOptions setIcon(Image icon) {
        this.icon = icon;
        Window w = window;
        if (w != null) w.pushIcon();
        return this;
    }

    /**
     * The OS window title.
     */
    public WindowOptions setTitle(String title) {
        this.title = title;
        Window w = window;
        if (w != null) w.pushTitle();
        return this;
    }

    /**
     * Allows or forbids the user resizing the window (default allowed). {@link #setDimensions} still works.
     */
    public WindowOptions setResizable(boolean resizable) {
        this.resizable = resizable;
        Window w = window;
        if (w != null) w.pushResizable();
        return this;
    }

    public String getTitle() {
        return title;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public boolean isMaximized() {
        return maximized;
    }

    public boolean isWindowed() {
        return style == WindowStyle.STANDARD;
    }

    public WindowStyle getStyle() {
        return style;
    }

    public boolean isAlwaysOnTop() {
        return alwaysOnTop;
    }

    public boolean isVsync() {
        return vsync;
    }

    public boolean isShowCursor() {
        return showCursor;
    }

    public int getMaxFPS() {
        return maxFPS;
    }

    public int getBackgroundFPS() {
        return backgroundFPS;
    }

    public boolean isHandleFocus() {
        return handleFocus;
    }

    public RenderAPI getRenderer() {
        return renderer;
    }

    public String getIconPath() {
        return iconPath;
    }

    public Image getIcon() {
        return icon;
    }

    public boolean isResizable() {
        return resizable;
    }

}