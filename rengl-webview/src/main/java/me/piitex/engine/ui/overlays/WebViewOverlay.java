package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.ImePreedit;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.PointerInput;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.webview.WebViewBackend;
import me.piitex.engine.webview.WebViewOptions;
import me.piitex.engine.webview.WebViews;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.ImageInfo;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A web page inside the element's bounds. Put it in a container like any other element:
 * <pre>{@code
 * WebViewOverlay web = new WebViewOverlay("https://example.com", 900, 600);
 * container.addElement(web);
 * }</pre>
 * Clicking it gives it the keyboard, and then typing, the mouse, the wheel and an input method (Chinese, Japanese and
 * Korean text) all go to the page. Tab moves between the page's own fields; click outside it to leave.
 * <p>
 * <b>A login portal</b> is a page you do not control that has to hand your program a result. Restrict where it can go,
 * and take the result from the address it finishes on rather than reading the page:
 * <pre>{@code
 * WebViewOverlay login = new WebViewOverlay(new WebViewOptions().allowHost("login.example.com"), 420, 560);
 * login.onRedirect("mygame://signed-in", url -> finishSignIn(url));   // the page is stopped before it loads
 * login.load(signInUrl);
 * }</pre>
 * See {@link me.piitex.engine.webview.LoginFlow} for the checks to do on that address, and for sign-in with the
 * system browser, which providers such as Google require.
 * <p>
 * <b>Two ways to draw</b>, chosen by {@link WebViewOptions#setMode}. The default, {@link WebViewOptions.Mode#PIXELS},
 * has the browser draw off screen and shows its pictures like an image, so the page can sit under a menu, fade, be
 * clipped by a scrolling container and have rounded corners; the cost is 10 to 25 pictures a second, so scrolling is
 * not smooth and video is poor. {@link WebViewOptions.Mode#NATIVE} gives the page a window of its own over the engine's
 * at full speed, but it is always on top, square, and ignores clipping and fading.
 * <p>
 * Everything the overlay reports ({@link #onUrlChanged}, {@link #onMessage}, ...) is called on the render thread, in the
 * frame after it happened. Only {@link #setNavigationFilter} runs on the browser's own thread.
 * <p>
 * The browser starts the first time the element is updated, and owns a thread and a browser process until
 * {@link #dispose()}. Removing the overlay from its container hides it and pauses the pictures but keeps the page;
 * letting it go without disposing it leaves the browser running until the program exits. Whether this machine can show
 * pages at all is {@link WebViews#isAvailable()}.
 */
public class WebViewOverlay extends Overlay implements Focusable, PointerInput {
    private static final Logger log = LogManager.getLogger("WebView");
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RenGL-WebView-decode");
        t.setDaemon(true);
        return t;
    });

    /**
     * Where the browser is in starting up.
     */
    public enum State {
        /** Not asked to start yet: the overlay has not been updated. */
        NOT_STARTED,
        /** Starting; commands are kept and carried out when it is ready. */
        STARTING,
        READY,
        /** It could not start, or crashed. See {@link #getFailure()}. */
        FAILED
    }

    private record Redirect(String prefix, Consumer<String> handler) {
    }

    private final WebViewOptions options;
    private volatile WebViewBackend backend;
    private volatile State state = State.NOT_STARTED;
    private volatile String failure;
    private final ConcurrentLinkedQueue<Runnable> events = new ConcurrentLinkedQueue<>();
    private final List<Redirect> redirects = new CopyOnWriteArrayList<>();
    private volatile Predicate<String> navigationFilter;

    // what to do once the browser exists
    private String startUrl;
    private String startHtml;
    private boolean disposed;
    private boolean focused;

    // the page
    private volatile String url = "";
    private volatile String title = "";
    private volatile boolean loading;
    private volatile boolean canGoBack, canGoForward;
    private volatile boolean editing;

    // pictures
    private final AtomicReference<WebViewBackend.Frame> latestFrame = new AtomicReference<>();
    private final AtomicReference<Image> decoded = new AtomicReference<>();
    private final AtomicBoolean decoding = new AtomicBoolean();
    private Image image;
    private volatile boolean everDrawn;
    private int sentWidth = -1, sentHeight = -1;
    private int boundsX = Integer.MIN_VALUE, boundsY, boundsW, boundsH;
    private boolean boundsVisible;

    private Consumer<String> urlListener, titleListener, newWindowListener, messageListener, blockedListener, failedListener;
    private Consumer<Boolean> loadingListener;
    private Runnable readyListener;

    /**
     * A page of the given size, blank until {@link #load} is called.
     */
    public WebViewOverlay(double width, double height) {
        this(new WebViewOptions(), width, height);
    }

    /**
     * A page of the given size that starts by loading {@code url}.
     */
    public WebViewOverlay(String url, double width, double height) {
        this(new WebViewOptions(), width, height);
        load(url);
    }

    public WebViewOverlay(WebViewOptions options, double width, double height) {
        this.options = options == null ? new WebViewOptions() : options;
        setWidth(width);
        setHeight(height);
        getStyling().applyThemeBackgroundColor(Color.WHITE);
        getStyling().applyThemeHoverColor(Color.WHITE);
        getStyling().applyThemeBorderThickness(0f);
    }

    public WebViewOptions getOptions() {
        return options;
    }

    // ------------------------------------------------------------------------------------------------ navigation

    /**
     * Opens an address. Allowed only if it passes {@link WebViewOptions#allowHost}; otherwise the blocked-navigation
     * listener hears of it. Without a scheme, {@code https://} is assumed.
     */
    public void load(String address) {
        String full = address == null ? "about:blank" : address.contains(":") ? address : "https://" + address;
        WebViewBackend b = backend;
        if (b != null) b.navigate(full);
        else {
            startUrl = full;
            startHtml = null;
        }
    }

    /**
     * Shows HTML you provide, as a page of its own.
     */
    public void loadHtml(String html) {
        WebViewBackend b = backend;
        if (b != null) b.loadHtml(html);
        else {
            startHtml = html;
            startUrl = null;
        }
    }

    public void reload() {
        WebViewBackend b = backend;
        if (b != null) b.reload();
    }

    public void stop() {
        WebViewBackend b = backend;
        if (b != null) b.stop();
    }

    public void goBack() {
        WebViewBackend b = backend;
        if (b != null) b.goBack();
    }

    public void goForward() {
        WebViewBackend b = backend;
        if (b != null) b.goForward();
    }

    /** The page's current address. */
    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    /** Whether a page is loading. */
    public boolean isLoading() {
        return loading;
    }

    public boolean canGoBack() {
        return canGoBack;
    }

    public boolean canGoForward() {
        return canGoForward;
    }

    public State getState() {
        return state;
    }

    /** Why the browser failed, or null. */
    public String getFailure() {
        return failure;
    }

    // ------------------------------------------------------------------------------------------------ script and policy

    /**
     * Runs JavaScript in the page. {@code result} is called on the render thread with the value as JSON (a string comes
     * back quoted), or null if the script failed. Not available before the browser is ready.
     */
    public void evaluate(String script, Consumer<String> result) {
        WebViewBackend b = backend;
        if (b == null) return;
        b.execute(script, json -> events.add(() -> result.accept(json)));
    }

    /**
     * Sends a JSON message to the page ({@code chrome.webview.addEventListener('message', ...)}). Needs
     * {@link WebViewOptions#setScriptBridge}.
     */
    public void postMessage(String json) {
        WebViewBackend b = backend;
        if (b != null) b.postMessage(json);
    }

    /**
     * Stops any navigation the test refuses. It runs on the <b>browser's thread</b> and the page waits for it, so keep it
     * quick and do not touch the interface from it. Applies on top of {@link WebViewOptions#allowHost}.
     */
    public WebViewOverlay setNavigationFilter(Predicate<String> allow) {
        this.navigationFilter = allow;
        return this;
    }

    /**
     * Watches for the page going to an address that starts with {@code uriPrefix} and, instead of loading it, stops there
     * and hands it to {@code handler} (on the render thread). This is how a login portal gives its result back: an OAuth
     * or SAML sign-in ends by redirecting to an address of your choosing, and the code or token is in that address. The
     * prefix is compared ignoring case, so use the whole redirect address, such as
     * {@code https://myapp.example/callback} or {@code mygame://signed-in}.
     */
    public WebViewOverlay onRedirect(String uriPrefix, Consumer<String> handler) {
        redirects.add(new Redirect(uriPrefix, handler));
        return this;
    }

    // ------------------------------------------------------------------------------------------------ listeners

    public WebViewOverlay onUrlChanged(Consumer<String> listener) {
        this.urlListener = listener;
        return this;
    }

    public WebViewOverlay onTitleChanged(Consumer<String> listener) {
        this.titleListener = listener;
        return this;
    }

    /** Called with true when a page starts loading and false when it has finished or failed. */
    public WebViewOverlay onLoading(Consumer<Boolean> listener) {
        this.loadingListener = listener;
        return this;
    }

    /** The page tried to open a new window; it did not (see {@link WebViewOptions#setNewWindows}). */
    public WebViewOverlay onNewWindow(Consumer<String> listener) {
        this.newWindowListener = listener;
        return this;
    }

    /** A message from the page, as JSON. Needs {@link WebViewOptions#setScriptBridge}. */
    public WebViewOverlay onMessage(Consumer<String> listener) {
        this.messageListener = listener;
        return this;
    }

    /** A navigation was refused because its host is not allowed. */
    public WebViewOverlay onBlocked(Consumer<String> listener) {
        this.blockedListener = listener;
        return this;
    }

    /** The browser could not start or crashed. */
    public WebViewOverlay onFailed(Consumer<String> listener) {
        this.failedListener = listener;
        return this;
    }

    public WebViewOverlay onReady(Runnable listener) {
        this.readyListener = listener;
        return this;
    }

    // ------------------------------------------------------------------------------------------------ the browser

    private void startBrowser() {
        state = State.STARTING;
        if (!WebViews.isAvailable(options)) {
            fail(WebViews.describe(options));
            return;
        }
        if (options.getMode() == WebViewOptions.Mode.NATIVE && !WebViews.supportsNative(options)) {
            // this engine cannot draw a window of its own (the browser is run headless), so the page is drawn as pictures
            log.info("Web view: {} cannot draw a native window; drawing pictures instead", WebViews.resolve(options));
            options.setMode(WebViewOptions.Mode.PIXELS);
        }
        if (options.getMode() == WebViewOptions.Mode.NATIVE) {
            long handle = getWindow() == null ? 0 : getWindow().getNativeHandle();
            if (handle == 0) {
                state = State.NOT_STARTED; // not in an open window yet
                return;
            }
            options.setNativeWindow(handle);
        }
        try {
            WebViewBackend b = WebViews.create(options, new Hearing());
            backend = b;
            if (startHtml != null) b.loadHtml(startHtml);
            else if (startUrl != null) b.navigate(startUrl);
            startUrl = null;
            startHtml = null;
        } catch (RuntimeException e) {
            fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void fail(String reason) {
        failure = reason;
        state = State.FAILED;
        log.warn("Web view: {}", reason);
        Consumer<String> listener = failedListener;
        if (listener != null) listener.accept(reason);
    }

    /** What the browser reports, on its own thread: kept to cheap, thread-safe work, and queued for the render thread. */
    private final class Hearing implements WebViewBackend.Listener {
        @Override
        public void onReady() {
            events.add(() -> {
                if (state != State.FAILED) state = State.READY;
                if (readyListener != null) readyListener.run();
            });
        }

        @Override
        public void onFrame(WebViewBackend.Frame frame) {
            latestFrame.set(frame);
            if (decoding.compareAndSet(false, true)) DECODER.execute(WebViewOverlay.this::decodeFrames);
        }

        @Override
        public void onNavigationStarting(WebViewBackend.NavigationRequest request) {
            String address = request.url();
            for (Redirect r : redirects) {
                if (address != null && address.regionMatches(true, 0, r.prefix(), 0, r.prefix().length())) {
                    request.cancel();
                    events.add(() -> r.handler().accept(address));
                    return;
                }
            }
            Predicate<String> filter = navigationFilter;
            if (filter != null && !filter.test(address)) {
                request.cancel();
                events.add(() -> {
                    if (blockedListener != null) blockedListener.accept(address);
                });
                return;
            }
        }

        @Override
        public void onNavigationStarted(String address) {
            loading = true;
            events.add(() -> {
                if (loadingListener != null) loadingListener.accept(true);
            });
        }

        @Override
        public void onNavigationBlocked(String address) {
            events.add(() -> {
                if (blockedListener != null) blockedListener.accept(address);
            });
        }

        @Override
        public void onNavigationCompleted(String address, boolean success, int status) {
            loading = false;
            events.add(() -> {
                if (loadingListener != null) loadingListener.accept(false);
            });
        }

        @Override
        public void onUrlChanged(String address) {
            url = address;
            events.add(() -> {
                if (urlListener != null) urlListener.accept(address);
            });
        }

        @Override
        public void onTitleChanged(String newTitle) {
            title = newTitle == null ? "" : newTitle;
            events.add(() -> {
                if (titleListener != null) titleListener.accept(title);
            });
        }

        @Override
        public void onHistoryChanged(boolean back, boolean forward) {
            canGoBack = back;
            canGoForward = forward;
        }

        @Override
        public void onNewWindow(String address, boolean userInitiated) {
            events.add(() -> {
                if (newWindowListener != null) newWindowListener.accept(address);
            });
        }

        @Override
        public void onMessage(String json) {
            events.add(() -> {
                if (messageListener != null) messageListener.accept(json);
            });
        }

        @Override
        public void onCursor(CursorMode mode) {
            events.add(() -> setCursorMode(mode));
        }

        @Override
        public void onFocusChanged(boolean hasFocus) {
            // a click on a native page never reaches the engine, so the engine is told whose keyboard it now is
            if (hasFocus) {
                events.add(() -> {
                    if (getWindow() != null && getWindow().getFocusedElement() != WebViewOverlay.this) getWindow().requestFocus(WebViewOverlay.this);
                });
            }
        }

        @Override
        public void onTextInputChanged(boolean isEditing) {
            editing = isEditing;
        }

        @Override
        public void onFailed(String reason) {
            events.add(() -> fail(reason));
        }
    }

    // ------------------------------------------------------------------------------------------------ pictures

    /** Decodes pictures on a thread of its own, newest first, so the render thread never waits for a decode. */
    private void decodeFrames() {
        try {
            WebViewBackend.Frame frame;
            while ((frame = latestFrame.getAndSet(null)) != null) {
                Image img = decode(frame);
                if (img == null) continue;
                Image old = decoded.getAndSet(img);
                if (old != null) old.close();
            }
        } finally {
            decoding.set(false);
            if (latestFrame.get() != null && decoding.compareAndSet(false, true)) DECODER.execute(this::decodeFrames);
        }
    }

    private static Image decode(WebViewBackend.Frame frame) {
        try {
            if (!frame.encoded()) {
                return Image.Companion.makeRaster(ImageInfo.Companion.makeN32Premul(frame.width(), frame.height()), frame.data(), frame.width() * 4);
            }
            try (Image encoded = Image.Companion.makeFromEncoded(frame.data()); Bitmap bitmap = new Bitmap()) {
                ImageInfo info = ImageInfo.Companion.makeN32Premul(encoded.getWidth(), encoded.getHeight());
                if (!bitmap.allocPixels(info) || !encoded.readPixels(bitmap)) return null;
                bitmap.setImmutable();
                return Image.Companion.makeFromBitmap(bitmap);
            }
        } catch (RuntimeException e) {
            log.debug("Could not decode a web view picture: {}", e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ element

    @Override
    public void update(float delta) {
        super.update(delta);
        if (!isEnabled() || disposed) return;
        if (state == State.NOT_STARTED) startBrowser();
        WebViewBackend b = backend;
        if (b == null) return;

        int w = (int) Math.round(getWidth()), h = (int) Math.round(getHeight());
        if (w > 0 && h > 0) {
            if (options.getMode() == WebViewOptions.Mode.NATIVE) {
                int x = (int) Math.round(getAbsoluteX()), y = (int) Math.round(getAbsoluteY());
                if (x != boundsX || y != boundsY || w != boundsW || h != boundsH || !boundsVisible) {
                    boundsX = x;
                    boundsY = y;
                    boundsW = w;
                    boundsH = h;
                    boundsVisible = true;
                    b.setBounds(x, y, w, h, true);
                }
            } else {
                b.keepAlive();
                if (w != sentWidth || h != sentHeight) {
                    sentWidth = w;
                    sentHeight = h;
                    b.resize(w, h);
                }
            }
        }

        Image fresh = decoded.getAndSet(null);
        if (fresh != null) {
            Image old = image;
            image = fresh;
            if (old != null) old.close();
            everDrawn = true;
        }
        Runnable event;
        int budget = 64;
        while (budget-- > 0 && (event = events.poll()) != null) {
            try {
                event.run();
            } catch (RuntimeException e) {
                log.warn("A web view listener failed", e);
            }
        }
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);
        Image img = image;
        if (img == null || options.getMode() == WebViewOptions.Mode.NATIVE) return;
        float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
        if (!(w > 0) || !(h > 0)) return;
        renderer.drawImage(img, x, y, w, h, getStyling().getCornerRadius(), getOpacity());
    }

    /**
     * Hides the page (in {@link WebViewOptions.Mode#NATIVE} its window) and pauses the pictures. The page is kept.
     */
    @Override
    public void onRemoved() {
        hideNative();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) hideNative();
    }

    private void hideNative() {
        WebViewBackend b = backend;
        if (b != null && options.getMode() == WebViewOptions.Mode.NATIVE && boundsVisible) {
            boundsVisible = false;
            b.setBounds(boundsX, boundsY, Math.max(1, boundsW), Math.max(1, boundsH), false);
        }
    }

    /**
     * Closes the page and the browser, and frees the picture. The overlay then draws only its background.
     */
    public void dispose() {
        disposed = true;
        WebViewBackend b = backend;
        backend = null;
        if (b != null) b.close();
        Image old = image;
        image = null;
        if (old != null) old.close();
        Image pending = decoded.getAndSet(null);
        if (pending != null) pending.close();
        latestFrame.set(null);
        if (state != State.FAILED) state = State.NOT_STARTED;
    }

    // ------------------------------------------------------------------------------------------------ keyboard

    @Override
    public void onFocusGained() {
        focused = true;
        WebViewBackend b = backend;
        if (b != null) b.setFocused(true);
    }

    @Override
    public void onFocusLost() {
        focused = false;
        WebViewBackend b = backend;
        if (b != null) b.setFocused(false);
    }

    @Override
    public void onCharTyped(int codepoint) {
        WebViewBackend b = backend;
        if (b != null) b.text(codepoint);
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        WebViewBackend b = backend;
        if (b != null) b.key(key, action, mods);
    }

    /** Tab belongs to the page, so it can move between its own fields. */
    @Override
    public boolean wantsTab() {
        return true;
    }

    /** The input method is on while a text field in the page has the cursor. */
    @Override
    public boolean acceptsTextInput() {
        return editing && options.getMode() == WebViewOptions.Mode.PIXELS;
    }

    @Override
    public void onPreedit(ImePreedit preedit) {
        WebViewBackend b = backend;
        if (b != null) b.composition(preedit.text(), preedit.caret());
    }

    // ------------------------------------------------------------------------------------------------ pointer

    @Override
    public void onPointerMove(double x, double y, int buttons, int mods) {
        WebViewBackend b = backend;
        if (b != null && options.getMode() == WebViewOptions.Mode.PIXELS) b.pointerMove(x, y, buttons, mods);
    }

    @Override
    public void onPointerButton(double x, double y, int button, boolean pressed, int mods) {
        WebViewBackend b = backend;
        if (b != null && options.getMode() == WebViewOptions.Mode.PIXELS) b.pointerButton(x, y, button, pressed, mods);
    }

    @Override
    public void onPointerScroll(double x, double y, double deltaX, double deltaY, int mods) {
        WebViewBackend b = backend;
        if (b != null && options.getMode() == WebViewOptions.Mode.PIXELS) b.pointerScroll(x, y, deltaX, deltaY, mods);
    }

    @Override
    public void onPointerLeave() {
        WebViewBackend b = backend;
        if (b != null && options.getMode() == WebViewOptions.Mode.PIXELS) b.pointerLeave();
        setCursorMode(null);
    }

    /** True once the first picture has been drawn, for fading the page in over a loading screen. */
    public boolean hasPicture() {
        return everDrawn;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "WebViewOverlay[%s %s]", state, url);
    }
}
