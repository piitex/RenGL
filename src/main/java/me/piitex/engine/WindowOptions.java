package me.piitex.engine;

import org.jetbrains.skia.Image;

public class WindowOptions {
    private volatile String title;
    // Written by the main thread on OS resize, read by the render thread.
    private volatile int width, height;
    private volatile boolean fullscreen = false;
    private volatile WindowStyle style = WindowStyle.STANDARD;
    private volatile boolean alwaysOnTop = false;
    private volatile boolean vsync = true;
    private volatile boolean showCursor = true;
    private boolean handleFocus = false;
    private volatile boolean resizable = true;
    private volatile RenderBackend renderBackend = RenderBackend.AUTO;
    private volatile int maxFPS = 60;
    private String iconPath = null;
    private volatile Image icon = null;

    public WindowOptions(String title) {
        this.title = title;
    }

    public WindowOptions setDimensions(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public WindowOptions setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
        return this;
    }

    /**
     * {@code false} is the same as {@link WindowStyle#BORDERLESS}, {@code true} as {@link WindowStyle#STANDARD}.
     */
    public WindowOptions setWindowed(boolean windowed) {
        return setStyle(windowed ? WindowStyle.STANDARD : WindowStyle.BORDERLESS);
    }

    /**
     * The frame around the window (default {@link WindowStyle#STANDARD}). Can be changed while running through {@link Window#setStyle}.
     */
    public WindowOptions setStyle(WindowStyle style) {
        this.style = style == null ? WindowStyle.STANDARD : style;
        return this;
    }

    /**
     * Keeps the window above other windows (default off). Can be changed while running through {@link Window#setAlwaysOnTop}.
     */
    public WindowOptions setAlwaysOnTop(boolean alwaysOnTop) {
        this.alwaysOnTop = alwaysOnTop;
        return this;
    }

    /**
     * Syncs frame presentation to the display's refresh rate (default on). While on, the frame rate follows the
     * display and {@link #setMaxFPS} is not consulted. While off, the loop runs at {@code maxFPS}.
     * Unlike most options this can be changed while running; the render loop picks it up on the next frame.
     */
    public WindowOptions setVsync(boolean vsync) {
        this.vsync = vsync;
        return this;
    }

    /**
     * The graphics API to draw with (default {@link RenderBackend#AUTO}). The window is created for one API, so this
     * only has an effect before the window opens. A backend this machine can't run falls back to the platform default;
     * {@link Window#getRenderBackend()} says what the window really uses.
     */
    public WindowOptions setRenderBackend(RenderBackend renderBackend) {
        this.renderBackend = renderBackend == null ? RenderBackend.AUTO : renderBackend;
        return this;
    }

    public WindowOptions setShowCursor(boolean showCursor) {
        this.showCursor = showCursor;
        return this;
    }

    /**
     * The frame rate cap used while vsync is off (default 60). Zero or negative means unlimited. Can be changed
     * while running.
     */
    public WindowOptions setMaxFPS(int maxFPS) {
        this.maxFPS = maxFPS;
        return this;
    }

    public WindowOptions setHandleFocus(boolean handleFocus) {
        this.handleFocus = handleFocus;
        return this;
    }

    /**
     * An icon file on disk or on the classpath. See {@link #setIcon(Image)} to use an already loaded image instead.
     */
    public WindowOptions setIconPath(String iconPath) {
        this.iconPath = iconPath;
        return this;
    }

    /**
     * The window icon, as an image already loaded through {@code ImageCache.load(...)}. Takes precedence over
     * {@link #setIconPath}. Can be changed while running through {@link Window#setIcon}. On macOS this sets the
     * Dock icon, since macOS windows have no icon of their own. Pass null for no icon.
     */
    public WindowOptions setIcon(Image icon) {
        this.icon = icon;
        return this;
    }

    /**
     * Applies at startup only. While running, use {@link Window#setTitle}, which keeps this in step.
     */
    public WindowOptions setTitle(String title) {
        this.title = title;
        return this;
    }

    public WindowOptions setResizable(boolean resizable) {
        this.resizable = resizable;
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

    public boolean isHandleFocus() {
        return handleFocus;
    }

    public RenderBackend getRenderBackend() {
        return renderBackend;
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