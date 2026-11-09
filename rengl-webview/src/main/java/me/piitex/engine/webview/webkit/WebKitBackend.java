package me.piitex.engine.webview.webkit;

import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.webview.WebViewBackend;
import me.piitex.engine.webview.WebViewOptions;
import me.piitex.engine.webview.internal.CdpInput;
import me.piitex.engine.webview.internal.Keys;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * What the two WebKit engines (WKWebView on macOS, WebKitGTK on Linux) have in common, so that only the calls into the
 * toolkit are left to each: the thread discipline, the navigation rules of {@link WebViewOptions}, the pace of pictures,
 * the page's address and title, the cursor, and how keys, text and script reach the page. Everything here is plain Java
 * and is exercised by a fake engine in the tests; the subclasses are the part that needs a real Mac or Linux machine.
 * <p>
 * <b>Threads.</b> A toolkit is called from one thread only (macOS's main thread, GTK's main-loop thread). The subclass says
 * how to get onto it ({@link #runOnUiThread}); every hook below that is not marked otherwise runs there, and so does all
 * the state in this class marked "UI thread". The public methods of {@link WebViewBackend} only queue.
 * <p>
 * <b>Pictures.</b> Neither toolkit pushes frames. A subclass can take a snapshot of the view ({@link #capture}); this
 * class decides when ({@link FramePacer}) and reports it as a raw BGRA {@link WebViewBackend.Frame}.
 */
public abstract class WebKitBackend implements WebViewBackend {
    private static final Logger log = LogManager.getLogger("WebView");

    private static final long SHOWN_FOR_NANOS = 400_000_000L;
    private static final long POLL_PAGE_NANOS = 100_000_000L;
    private static final long CURSOR_POLL_NANOS = 90_000_000L;
    private static final long CAPTURE_TIMEOUT_NANOS = 4_000_000_000L;

    /** A picture of the page: 8-bit BGRA with premultiplied alpha, {@code width * 4} bytes a row. */
    public record Picture(int width, int height, byte[] bgra) {
    }

    protected final WebViewOptions options;
    private volatile Listener listener;
    private volatile boolean closed;

    // UI thread
    private final List<Runnable> afterReady = new ArrayList<>();
    private boolean ready;
    private boolean shutDown;
    /** The page's size in pixels, as last set. */
    protected int width = 1024, height = 640;
    private final FramePacer pacer = new FramePacer();
    private final ClickTracker clicks = new ClickTracker();
    private boolean capturing;
    private long captureStartedAt;
    private long captureToken;
    private long nextPagePoll;
    private String lastUrl = "", lastTitle = "";
    private boolean lastBack, lastForward;
    private boolean cursorPolling;
    private long nextCursorPoll;
    private CursorMode lastCursor;
    private boolean lastEditing;

    // any thread
    private volatile long lastSeen;
    private volatile double pointerX, pointerY;
    private volatile boolean pointerOver;

    protected WebKitBackend(WebViewOptions options, Listener listener) {
        this.options = options;
        this.listener = listener == null ? new Listener() {
        } : listener;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener == null ? new Listener() {
        } : listener;
    }

    protected final Listener listener() {
        return listener;
    }

    protected final boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------------------------------------ what a toolkit provides

    /** Runs {@code task} on the toolkit's thread, later or at once if already there. Callable from any thread; must not block. */
    protected abstract void runOnUiThread(Runnable task);

    protected abstract void loadUrl(String url);

    /** Shows this HTML with no base address. */
    protected abstract void loadHtmlNow(String html);

    protected abstract void reloadNow();

    protected abstract void stopNow();

    protected abstract void goBackNow();

    protected abstract void goForwardNow();

    /** The page is now this many pixels. */
    protected abstract void applySize(int width, int height);

    /** The page's current address, or null. */
    protected abstract String currentUrl();

    protected abstract String currentTitle();

    protected abstract boolean canGoBackNow();

    protected abstract boolean canGoForwardNow();

    /**
     * Runs a script and gives its result as text (a string result is the string itself), or null when it failed or
     * produced nothing. {@code result} may be null when the answer is not wanted. The callback may come on any later
     * turn of the UI thread.
     */
    protected abstract void evaluateRaw(String script, Consumer<String> result);

    /** Takes a picture of the page and passes it to {@code done} (null if it could not). Called only while the page is shown. */
    protected abstract void capture(Consumer<Picture> done);

    protected abstract void sendMove(double x, double y, int buttons, int mods);

    /** @param clickCount 1 for a first click, 2 for a double click, 0 for a release */
    protected abstract void sendButton(double x, double y, int glfwButton, boolean pressed, int clickCount, int mods);

    protected abstract void sendScroll(double x, double y, double dx, double dy, int mods);

    protected abstract void sendLeave();

    /** A key that types no character of its own (see {@link KeyRouting}), or a Ctrl/Cmd chord that is not an editing command. */
    protected abstract void sendKey(int glfwKey, int action, int mods);

    /** One of the names {@link Keys#command} returns ({@code selectAll}, {@code copy}, {@code cut}, {@code paste}, ...). */
    protected abstract void sendEditCommand(String command);

    /** A typed character. */
    protected abstract void sendText(int codePoint);

    protected abstract void sendComposition(String text, int caret);

    protected abstract void sendFocus(boolean focused);

    /** Frees everything. Runs once, on the UI thread, after the last command. */
    protected abstract void shutdown();

    // ------------------------------------------------------------------------------------------------ plumbing

    /** Queues a command for the UI thread. */
    protected final void post(Runnable command) {
        if (closed) return;
        runOnUiThread(() -> {
            try {
                command.run();
            } catch (RuntimeException e) {
                log.warn("A web view command failed: {}", e.toString());
            }
        });
    }

    /** Queues a command that needs the page, which may not exist yet: it runs when it does. */
    protected final void submit(Runnable command) {
        post(() -> {
            if (ready) command.run();
            else afterReady.add(command);
        });
    }

    /** The subclass calls this, on the UI thread, once the view exists and takes commands. */
    protected final void becomeReady() {
        if (ready || closed) return;
        ready = true;
        List<Runnable> waiting = new ArrayList<>(afterReady);
        afterReady.clear();
        for (Runnable r : waiting) {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warn("A web view command failed: {}", e.toString());
            }
        }
        listener.onReady();
    }

    /** The subclass calls this when the browser could not start or died. */
    protected final void failed(String reason) {
        log.warn("Web view: {}", reason);
        listener.onFailed(reason);
    }

    // ------------------------------------------------------------------------------------------------ the periodic work

    /** The subclass calls this about every 40 ms on the UI thread. */
    protected final void tick() {
        if (!ready || closed) return;
        long now = System.nanoTime();
        if (now >= nextPagePoll) {
            nextPagePoll = now + POLL_PAGE_NANOS;
            pollPage();
        }
        if (!shown(now)) return;
        if (capturing && now - captureStartedAt > CAPTURE_TIMEOUT_NANOS) {
            capturing = false; // a picture that never came; a late one is ignored
            captureToken++;
            pacer.failed(now);
        }
        if (!capturing && pacer.due(now)) startCapture(now);
        if (pointerOver && !cursorPolling && now >= nextCursorPoll) pollCursor(now);
    }

    private boolean shown(long now) {
        return now - lastSeen < SHOWN_FOR_NANOS;
    }

    private void activity() {
        pacer.activity(System.nanoTime());
    }

    /** Reports a change of address, title or history. */
    protected final void pollPage() {
        String url = currentUrl();
        if (url != null && !url.equals(lastUrl)) {
            lastUrl = url;
            listener.onUrlChanged(url);
        }
        String title = currentTitle();
        if (title != null && !title.equals(lastTitle)) {
            lastTitle = title;
            listener.onTitleChanged(title);
        }
        boolean back = canGoBackNow(), forward = canGoForwardNow();
        if (back != lastBack || forward != lastForward) {
            lastBack = back;
            lastForward = forward;
            listener.onHistoryChanged(back, forward);
        }
    }

    private void startCapture(long now) {
        capturing = true;
        captureStartedAt = now;
        long token = ++captureToken;
        try {
            capture(picture -> {
                if (token != captureToken) return; // timed out already
                capturing = false;
                afterPicture(picture);
            });
        } catch (RuntimeException e) {
            capturing = false;
            log.debug("Could not take a picture: {}", e.toString());
            pacer.failed(now);
        }
    }

    private void afterPicture(Picture picture) {
        long now = System.nanoTime();
        if (picture == null || picture.bgra() == null || picture.width() <= 0 || picture.height() <= 0) {
            pacer.failed(now);
            return;
        }
        if (pacer.captured(now, picture.width(), picture.height(), Arrays.hashCode(picture.bgra()))) {
            listener.onFrame(new Frame(picture.width(), picture.height(), picture.bgra(), false, true));
        }
    }

    private void pollCursor(long now) {
        nextCursorPoll = now + CURSOR_POLL_NANOS;
        cursorPolling = true;
        evaluateRaw(CdpInput.probeScript(pointerX, pointerY), result -> {
            cursorPolling = false;
            CdpInput.Probe probe = CdpInput.parseProbe(result);
            if (probe == null) return;
            if (probe.cursor() != lastCursor) {
                lastCursor = probe.cursor();
                listener.onCursor(probe.cursor());
            }
            if (probe.editing() != lastEditing) {
                lastEditing = probe.editing();
                listener.onTextInputChanged(probe.editing());
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ navigation rules

    /** Whether an address is one a web view loads itself. Anything else (mailto:, an app's own scheme) is never loaded. */
    public static boolean isWebScheme(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:blank")
                || lower.startsWith("data:") || lower.startsWith("blob:");
    }

    /**
     * Decides a navigation the page is about to make. The application's own checks go first (a sign-in's redirect
     * address is not a host to refuse), then the host list, then the scheme. Reports the outcome to the listener and
     * returns whether the navigation may go ahead. UI thread.
     */
    protected final boolean allowNavigation(String url, boolean userInitiated, boolean redirected) {
        NavigationRequest request = new NavigationRequest(url, userInitiated, redirected);
        listener.onNavigationStarting(request);
        if (!request.cancelled() && (!options.allows(url) || !isWebScheme(url))) {
            request.cancel();
            listener.onNavigationBlocked(url);
        }
        if (request.cancelled()) return false;
        listener.onNavigationStarted(url);
        activity();
        return true;
    }

    /** The page tried to open a window. It is not opened; the address loads here unless the options say otherwise. UI thread. */
    protected final void newWindowRequested(String url, boolean userInitiated) {
        listener.onNewWindow(url, userInitiated);
        if (options.getNewWindows() == WebViewOptions.NewWindows.SAME_VIEW && url != null && options.allows(url) && isWebScheme(url)) {
            post(() -> loadUrl(url));
        }
    }

    /** A navigation ended. UI thread. */
    protected final void navigationFinished(boolean success, int status) {
        pollPage();
        String url = currentUrl();
        listener.onNavigationCompleted(url == null ? lastUrl : url, success, status);
        pacer.resized(); // a new page: even a picture that looks like the last one is reported
        activity();
    }

    /** A message arrived from the page's bridge, from the page at {@code source}. UI thread. */
    protected final void messageFromPage(String source, String json) {
        if (!options.isScriptBridge() || json == null) return;
        if (source != null && !options.allows(source)) return;
        listener.onMessage(json);
    }

    /** Whether the page may use a permission. */
    protected final boolean permits(WebViewOptions.Permission permission) {
        return permission != null && options.getPermissions().contains(permission);
    }

    // ------------------------------------------------------------------------------------------------ WebViewBackend: navigation

    @Override
    public void navigate(String url) {
        if (!options.allows(url)) {
            listener.onNavigationBlocked(url);
            return;
        }
        submit(() -> {
            loadUrl(url);
            activity();
        });
    }

    @Override
    public void loadHtml(String html) {
        submit(() -> {
            loadHtmlNow(html);
            activity();
        });
    }

    @Override
    public void reload() {
        submit(this::reloadNow);
    }

    @Override
    public void stop() {
        submit(this::stopNow);
    }

    @Override
    public void goBack() {
        submit(this::goBackNow);
    }

    @Override
    public void goForward() {
        submit(this::goForwardNow);
    }

    // ------------------------------------------------------------------------------------------------ WebViewBackend: display

    @Override
    public void resize(int w, int h) {
        int fw = Math.max(1, w), fh = Math.max(1, h);
        post(() -> {
            if (fw == width && fh == height) return;
            width = fw;
            height = fh;
            applySize(fw, fh);
            pacer.resized();
            activity();
        });
    }

    @Override
    public void keepAlive() {
        long now = System.nanoTime();
        boolean wasHidden = !shown(now);
        lastSeen = now;
        if (wasHidden) post(() -> pacer.shownAgain(System.nanoTime()));
    }

    // ------------------------------------------------------------------------------------------------ WebViewBackend: input

    @Override
    public void pointerMove(double x, double y, int buttons, int mods) {
        pointerX = x;
        pointerY = y;
        pointerOver = true;
        submit(() -> {
            sendMove(x, y, buttons, mods);
            activity();
        });
    }

    @Override
    public void pointerButton(double x, double y, int button, boolean pressed, int mods) {
        pointerX = x;
        pointerY = y;
        submit(() -> {
            int count = pressed ? clicks.press(button, x, y, System.nanoTime()) : 0;
            sendButton(x, y, button, pressed, count, mods);
            activity();
        });
    }

    @Override
    public void pointerScroll(double x, double y, double dx, double dy, int mods) {
        submit(() -> {
            sendScroll(x, y, dx, dy, mods);
            activity();
        });
    }

    @Override
    public void pointerLeave() {
        pointerOver = false;
        submit(this::sendLeave);
    }

    @Override
    public void key(int glfwKey, int action, int mods) {
        submit(() -> {
            switch (KeyRouting.route(glfwKey, action, mods)) {
                case EDIT_COMMAND -> sendEditCommand(Keys.command(glfwKey, mods));
                case KEY_EVENT -> sendKey(glfwKey, action, mods);
                case IGNORE -> {
                }
            }
            activity();
        });
    }

    @Override
    public void text(int codePoint) {
        if (codePoint < 0x20 || codePoint == 0x7F) return;
        submit(() -> {
            sendText(codePoint);
            activity();
        });
    }

    @Override
    public void composition(String text, int caret) {
        String t = text == null ? "" : text;
        submit(() -> {
            sendComposition(t, Math.max(0, Math.min(caret, t.length())));
            activity();
        });
    }

    @Override
    public void setFocused(boolean focused) {
        submit(() -> {
            sendFocus(focused);
            activity();
        });
    }

    // ------------------------------------------------------------------------------------------------ WebViewBackend: script

    @Override
    public void execute(String script, Consumer<String> result) {
        submit(() -> evaluateRaw(WebKitJs.wrapForJson(script), result));
    }

    @Override
    public void postMessage(String json) {
        if (!options.isScriptBridge()) return;
        submit(() -> evaluateRaw(WebKitJs.deliver(json), null));
    }

    // ------------------------------------------------------------------------------------------------ closing

    @Override
    public void close() {
        if (closed) return;
        closed = true; // no new commands
        runOnUiThread(() -> {
            if (shutDown) return;
            shutDown = true;
            try {
                shutdown();
            } catch (RuntimeException e) {
                log.debug("Closing the web view: {}", e.toString());
            }
        });
    }
}
