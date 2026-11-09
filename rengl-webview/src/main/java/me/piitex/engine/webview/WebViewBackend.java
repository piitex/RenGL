package me.piitex.engine.webview;

import me.piitex.engine.ui.CursorMode;

import java.util.function.Consumer;

/**
 * One browser engine driven from Java: what {@link me.piitex.engine.ui.overlays.WebViewOverlay} needs from the system's
 * web view, with nothing of the engine's UI in it. {@link WebViews#create} picks the implementation for the platform
 * (WebView2 on Windows, or a Chromium-based browser run headless, which works on every system).
 * <p>
 * Every method may be called from the thread that owns the overlay and returns at once; the work is queued. The
 * {@link Listener} is called on the backend's own thread, which is <b>not</b> the render thread, so a listener keeps to
 * short, thread-safe work and hands anything else on. The overlay does exactly that.
 */
public interface WebViewBackend extends AutoCloseable {

    /**
     * What a backend reports. Every method has an empty default so a listener overrides only what it needs.
     */
    interface Listener {
        /** The browser started and is taking commands (those given before are carried out now). */
        default void onReady() {
        }

        /** A new picture of the page. Only for {@link WebViewOptions.Mode#PIXELS}. */
        default void onFrame(Frame frame) {
        }

        /**
         * A navigation is about to start. Call {@link NavigationRequest#cancel()} to stop it. It runs on the browser's
         * thread and the page waits for it, so answer quickly.
         */
        default void onNavigationStarting(NavigationRequest request) {
        }

        /** A navigation was not refused and is under way. */
        default void onNavigationStarted(String url) {
        }

        /** A navigation was refused because its host is not one of the allowed ones; it did not happen. */
        default void onNavigationBlocked(String url) {
        }

        /** A navigation finished; {@code status} is the browser's error code, 0 when it worked. */
        default void onNavigationCompleted(String url, boolean success, int status) {
        }

        /** The address of the page changed, including by a link, a redirect or script. */
        default void onUrlChanged(String url) {
        }

        default void onTitleChanged(String title) {
        }

        default void onHistoryChanged(boolean canGoBack, boolean canGoForward) {
        }

        /** The page asked to open a new window ({@code window.open}, a link with a target). It was not opened. */
        default void onNewWindow(String url, boolean userInitiated) {
        }

        /** The page posted a message through the script bridge, as JSON. */
        default void onMessage(String json) {
        }

        /** The cursor the page wants under the pointer. Null means the default one. */
        default void onCursor(CursorMode mode) {
        }

        /** Whether a text field or an editable area has the page's focus, so an input method should be on. */
        default void onTextInputChanged(boolean editing) {
        }

        /**
         * The browser's own window took or lost the keyboard because the user clicked in it or elsewhere. Only for
         * {@link WebViewOptions.Mode#NATIVE}, where clicks on the page never pass through the engine.
         */
        default void onFocusChanged(boolean focused) {
        }

        /** The browser could not start, or its process failed. */
        default void onFailed(String reason) {
        }
    }

    /**
     * A picture of the page. {@code data} is a PNG or JPEG file when {@code encoded}, and 8-bit BGRA with
     * premultiplied alpha, {@code width * 4} bytes a row, when not.
     *
     * @param crisp true for a lossless picture of a page that has stopped changing, false for a quick, lossy one
     *              taken while it moves
     */
    record Frame(int width, int height, byte[] data, boolean encoded, boolean crisp) {
    }

    /**
     * A navigation about to happen, which the listener can refuse.
     */
    final class NavigationRequest {
        private final String url;
        private final boolean userInitiated;
        private final boolean redirected;
        private boolean cancelled;

        public NavigationRequest(String url, boolean userInitiated, boolean redirected) {
            this.url = url;
            this.userInitiated = userInitiated;
            this.redirected = redirected;
        }

        public String url() {
            return url;
        }

        /** True when the user clicked or typed to cause it, false when the page did it on its own. */
        public boolean userInitiated() {
            return userInitiated;
        }

        public boolean redirected() {
            return redirected;
        }

        public void cancel() {
            cancelled = true;
        }

        public boolean cancelled() {
            return cancelled;
        }
    }

    void setListener(Listener listener);

    // --- navigation
    void navigate(String url);

    /** Shows this HTML as a page. The address stays {@code about:blank} and the page can reach no other site's data. */
    void loadHtml(String html);

    void reload();

    void stop();

    void goBack();

    void goForward();

    // --- display
    /** The size of the page in pixels. */
    void resize(int width, int height);

    /**
     * Tells the backend the page is on screen. Call it every frame the page is drawn; a backend that is not told for a
     * moment stops producing pictures, so a page that is hidden or removed costs nothing.
     */
    void keepAlive();

    /**
     * For {@link WebViewOptions.Mode#NATIVE}: where the page sits in its window's client area, in pixels.
     */
    default void setBounds(int x, int y, int width, int height, boolean visible) {
    }

    // --- input, with positions in the page's own pixels
    void pointerMove(double x, double y, int buttons, int mods);

    void pointerButton(double x, double y, int button, boolean pressed, int mods);

    void pointerScroll(double x, double y, double deltaX, double deltaY, int mods);

    void pointerLeave();

    /** A key (a GLFW key code) went down, repeated or came up. */
    void key(int glfwKey, int action, int mods);

    /** A typed character (a code point). */
    void text(int codePoint);

    /** The input method's unfinished text, empty to clear it. */
    void composition(String text, int caret);

    /** Whether the page has the keyboard. */
    void setFocused(boolean focused);

    // --- script
    /** Runs JavaScript in the page; {@code result} gets the value as JSON, or null if it failed. */
    void execute(String script, Consumer<String> result);

    /** Sends a JSON message to the page's {@code chrome.webview} message listeners. */
    void postMessage(String json);

    @Override
    void close();
}
