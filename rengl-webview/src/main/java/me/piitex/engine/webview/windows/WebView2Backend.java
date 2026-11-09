package me.piitex.engine.webview.windows;

import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.webview.WebViewBackend;
import me.piitex.engine.webview.WebViewOptions;
import me.piitex.engine.webview.internal.CdpInput;
import me.piitex.engine.webview.internal.Profiles;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.*;
import static me.piitex.engine.webview.windows.Com.*;

/**
 * Microsoft's WebView2 (the Chromium that Edge is made of), driven through COM from Java with no native code of ours.
 * <p>
 * <b>Threads.</b> WebView2 lives in a single-threaded COM apartment and reports back as window messages, so the backend
 * runs its own thread with a message loop, and everything below happens there; the public methods only queue. In
 * {@link WebViewOptions.Mode#PIXELS} the browser sits in a window nobody sees, and in {@link WebViewOptions.Mode#NATIVE}
 * it is a child of the engine's window (the engine's own thread owns that parent, which Windows allows).
 * <p>
 * <b>Pictures.</b> {@code CapturePreview} is the only way to get the page's pixels out of a windowed WebView2 without
 * Windows' composition APIs. It takes 40 ms or more, so pictures come at 10 to 25 a second, and the loop takes them only
 * while the page is changing (see {@link #afterCapture}).
 * <p>
 * <b>Input.</b> The mouse, keyboard, wheel and input method reach the page through the DevTools protocol's
 * {@code Input} domain. Those events are indistinguishable from real ones to the page (they are trusted), and they work
 * whatever window has the system's focus.
 */
public final class WebView2Backend implements WebViewBackend {
    private static final Logger log = LogManager.getLogger("WebView");

    // --- vtable slots (counted from IUnknown's three), checked against the SDK's interface order
    // ICoreWebView2Environment
    private static final int ENV_CREATE_CONTROLLER = 3;
    // ICoreWebView2Controller
    private static final int CTL_PUT_VISIBLE = 4, CTL_PUT_BOUNDS = 6, CTL_MOVE_FOCUS = 12, CTL_ADD_GOT_FOCUS = 15,
            CTL_ADD_LOST_FOCUS = 17, CTL_CLOSE = 24, CTL_GET_WEBVIEW = 25;
    // ICoreWebView2
    private static final int WV_GET_SETTINGS = 3, WV_GET_SOURCE = 4, WV_NAVIGATE = 5, WV_NAVIGATE_TO_STRING = 6,
            WV_ADD_NAVIGATION_STARTING = 7, WV_ADD_SOURCE_CHANGED = 11, WV_ADD_HISTORY_CHANGED = 13,
            WV_ADD_NAVIGATION_COMPLETED = 15, WV_ADD_PERMISSION_REQUESTED = 23, WV_ADD_PROCESS_FAILED = 25,
            WV_EXECUTE_SCRIPT = 29, WV_CAPTURE_PREVIEW = 30, WV_RELOAD = 31, WV_POST_WEB_MESSAGE_AS_JSON = 32,
            WV_ADD_WEB_MESSAGE_RECEIVED = 34, WV_CALL_DEVTOOLS = 36, WV_CAN_GO_BACK = 38, WV_CAN_GO_FORWARD = 39,
            WV_GO_BACK = 40, WV_GO_FORWARD = 41, WV_STOP = 43, WV_ADD_NEW_WINDOW_REQUESTED = 44,
            WV_ADD_TITLE_CHANGED = 46, WV_GET_TITLE = 48;
    // ICoreWebView2Settings
    private static final int ST_PUT_SCRIPT = 4, ST_PUT_WEB_MESSAGE = 6, ST_PUT_DIALOGS = 8, ST_PUT_STATUS_BAR = 10,
            ST_PUT_DEV_TOOLS = 12, ST_PUT_CONTEXT_MENUS = 14, ST_PUT_HOST_OBJECTS = 16;

    private static final int PNG = 0, JPEG = 1;
    private static final long SHOWN_FOR_NANOS = 400_000_000L;
    private static final long ACTIVE_NANOS = 1_200_000_000L;
    private static final long IDLE_POLL_NANOS = 500_000_000L;
    private static final long CURSOR_POLL_NANOS = 90_000_000L;

    private static final FunctionDescriptor PTR_PTR_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor INT_PTR_PTR_ARGS = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor SEEK = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor READ = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);

    /**
     * Whether a runtime is installed and the libraries to drive it could load.
     */
    public static boolean isAvailable() {
        return WebView2Runtime.isInstalled();
    }

    private final WebViewOptions options;
    private final boolean nativeMode;
    private volatile Listener listener;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final List<Runnable> afterReady = new ArrayList<>();
    private final Profiles.Profile profile;
    private final CdpInput input = new CdpInput(this::devtools);

    private Thread thread;
    private volatile int threadId;
    private MemorySegment host;
    private volatile boolean running = true;
    private volatile boolean closed;

    // thread-confined
    private MemorySegment env, controller, webview;
    private int width = 1024, height = 640;
    private boolean visible = true;
    private int boundsX, boundsY;
    private String lastUrl = "";
    private boolean scaleOverridden;

    // pictures (pixel mode)
    private volatile long lastSeen;
    private volatile long activeUntil;
    private long nextCaptureAt;
    private boolean capturing;
    private int lastHash;
    private int lastLength = -1;
    private boolean settled;
    private boolean sawSameJpeg;

    // pointer state
    private volatile double pointerX, pointerY;
    private volatile boolean pointerOver;
    private long nextCursorPoll;
    private boolean cursorPolling;
    private CursorMode lastCursor;
    private boolean lastEditing;

    public WebView2Backend(WebViewOptions options, Listener listener) {
        this.options = options;
        this.listener = listener == null ? new Listener() {
        } : listener;
        this.nativeMode = options.getMode() == WebViewOptions.Mode.NATIVE;
        if (!isAvailable()) throw new IllegalStateException("The WebView2 runtime is not installed");
        profile = Profiles.open(options);
        if (nativeMode && options.getNativeWindow() == 0) throw new IllegalStateException("A native web view needs its window's handle");
        thread = new Thread(this::run, "RenGL-WebView");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener == null ? new Listener() {
        } : listener;
    }

    // ------------------------------------------------------------------------------------------------ the thread

    private void run() {
        try {
            threadId = currentThreadId();
            coInitialize();
            // a native view is a child of the engine's window; the other kind sits in a window nobody sees
            host = nativeMode ? MemorySegment.ofAddress(options.getNativeWindow()) : createHiddenHost(width, height);
            if (host.address() == 0) throw new IllegalStateException("Could not create the host window");
            begin();
            try (Arena a = Arena.ofConfined()) {
                MemorySegment msg = a.allocate(64, 8);
                while (running) {
                    long now = System.nanoTime();
                    long wait = 50;
                    if (shown(now) && !capturing && webview != null) wait = Math.max(0, Math.min(wait, (nextCaptureAt - now) / 1_000_000L));
                    waitForMessage((int) wait);
                    dispatchMessages(msg);
                    Runnable task;
                    while ((task = tasks.poll()) != null) {
                        try {
                            task.run();
                        } catch (RuntimeException e) {
                            log.warn("A web view command failed: {}", e.toString());
                        }
                    }
                    if (running && webview != null) tick(System.nanoTime());
                }
            }
        } catch (Throwable t) {
            log.error("The web view thread failed", t);
            listener.onFailed(String.valueOf(t.getMessage()));
        } finally {
            try {
                teardown();
            } catch (Throwable t) {
                log.debug("Closing the web view", t);
            }
            try {
                coUninitialize();
            } catch (Throwable ignored) {
                // the thread is ending
            }
        }
    }

    /** Queues a command for the browser's thread. */
    private void post(Runnable command) {
        if (closed) return;
        tasks.add(command);
        poke();
    }

    private void poke() {
        int id = threadId;
        if (id != 0) wakeThread(id);
    }

    /** Runs a command now if the page exists, else when it does. Browser thread only. */
    private void whenReady(Runnable command) {
        if (webview != null) command.run();
        else afterReady.add(command);
    }

    private void submit(Runnable command) {
        post(() -> whenReady(command));
    }

    // ------------------------------------------------------------------------------------------------ start up

    private void begin() {
        try {
            String dll = WebView2Runtime.dll().toString();
            MemorySegment export = exportOf(dll, "CreateWebViewEnvironmentWithOptionsInternal");
            MethodHandle create = LINKER.downcallHandle(export,
                    FunctionDescriptor.of(JAVA_INT, JAVA_BYTE, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            MemorySegment folder = wide(GLOBAL, profile.folder().toAbsolutePath().toString());
            int hr = 0;
            for (int runtimeType = 0; runtimeType <= 1; runtimeType++) {
                MemorySegment handler = onResult(this::environmentReady);
                hr = (int) create.invokeExact((byte) 1, runtimeType, folder, MemorySegment.NULL, handler);
                if (hr >= 0) return;
            }
            throw new IllegalStateException("The WebView2 runtime would not start (" + hex(hr) + ")");
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void environmentReady(int hr, MemorySegment environment) {
        if (hr < 0 || environment.address() == 0) {
            fail(new IllegalStateException("No WebView2 environment (" + hex(hr) + ")"));
            return;
        }
        addRef(environment); // the callback only borrows it
        env = environment;
        try {
            check(call(env, ENV_CREATE_CONTROLLER, PTR_PTR, host, onResult(this::controllerReady)), "CreateCoreWebView2Controller");
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void controllerReady(int hr, MemorySegment ctl) {
        if (hr < 0 || ctl.address() == 0) {
            fail(new IllegalStateException("No WebView2 controller (" + hex(hr) + ")"));
            return;
        }
        addRef(ctl);
        controller = ctl;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(8);
            check(call(controller, CTL_GET_WEBVIEW, PTR, out), "get_CoreWebView2");
            webview = out.get(ADDRESS, 0);
            configure();
            applyBounds();
            check(call(controller, CTL_PUT_VISIBLE, INT, nativeMode && !visible ? 0 : 1), "put_IsVisible");
            subscribe();
            if (!nativeMode) {
                devtools("Emulation.setFocusEmulationEnabled", "{\"enabled\":false}");
                activeUntil = System.nanoTime() + ACTIVE_NANOS;
            }
        } catch (Throwable t) {
            fail(t);
            return;
        }
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

    private void configure() {
        MemorySegment settings;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(8);
            check(call(webview, WV_GET_SETTINGS, PTR, out), "get_Settings");
            settings = out.get(ADDRESS, 0);
        }
        try {
            call(settings, ST_PUT_SCRIPT, INT, 1);
            call(settings, ST_PUT_WEB_MESSAGE, INT, options.isScriptBridge() ? 1 : 0);
            call(settings, ST_PUT_DIALOGS, INT, 0);
            call(settings, ST_PUT_STATUS_BAR, INT, 0);
            call(settings, ST_PUT_DEV_TOOLS, INT, options.isDevTools() ? 1 : 0);
            // in pixel mode the browser's menu would open in the hidden window, off screen
            call(settings, ST_PUT_CONTEXT_MENUS, INT, nativeMode && options.isContextMenus() ? 1 : 0);
            call(settings, ST_PUT_HOST_OBJECTS, INT, 0);
        } finally {
            release(settings);
        }
    }

    private void subscribe() {
        event(WV_ADD_NAVIGATION_STARTING, (sender, args) -> {
            String url = getString(args, 3);
            boolean user = getInt(args, 4) != 0;
            boolean redirected = getInt(args, 5) != 0;
            NavigationRequest request = new NavigationRequest(url, user, redirected);
            // the application's own checks go first: an address it has claimed (a sign-in's redirect) is not a host to refuse
            listener.onNavigationStarting(request);
            if (!request.cancelled() && !options.allows(url)) {
                request.cancel();
                listener.onNavigationBlocked(url);
            }
            if (request.cancelled()) {
                call(args, 8, INT, 1);
            } else {
                listener.onNavigationStarted(url);
                activity(System.nanoTime());
            }
        });
        event(WV_ADD_NAVIGATION_COMPLETED, (sender, args) -> {
            boolean success = getInt(args, 3) != 0;
            int status = getInt(args, 4);
            String url = getString(webview, WV_GET_SOURCE);
            listener.onNavigationCompleted(url == null ? lastUrl : url, success, status);
            if (!nativeMode) checkScale();
            activity(System.nanoTime());
        });
        event(WV_ADD_SOURCE_CHANGED, (sender, args) -> {
            String url = getString(webview, WV_GET_SOURCE);
            if (url != null && !url.equals(lastUrl)) {
                lastUrl = url;
                listener.onUrlChanged(url);
            }
        });
        event(WV_ADD_TITLE_CHANGED, (sender, args) -> listener.onTitleChanged(getString(webview, WV_GET_TITLE)));
        event(WV_ADD_HISTORY_CHANGED, (sender, args) ->
                listener.onHistoryChanged(getInt(webview, WV_CAN_GO_BACK) != 0, getInt(webview, WV_CAN_GO_FORWARD) != 0));
        event(WV_ADD_NEW_WINDOW_REQUESTED, (sender, args) -> {
            String url = getString(args, 3);
            boolean user = getInt(args, 8) != 0;
            call(args, 6, INT, 1); // handled: no window of its own opens
            listener.onNewWindow(url, user);
            if (options.getNewWindows() == WebViewOptions.NewWindows.SAME_VIEW && options.allows(url)) navigateNow(url);
        });
        event(WV_ADD_PERMISSION_REQUESTED, (sender, args) -> {
            WebViewOptions.Permission kind = WebViewOptions.Permission.of(getInt(args, 4));
            boolean allowed = kind != null && options.getPermissions().contains(kind);
            call(args, 7, INT, allowed ? 1 : 2);
        });
        event(WV_ADD_PROCESS_FAILED, (sender, args) -> listener.onFailed("The browser process failed (kind " + getInt(args, 3) + ")"));
        if (nativeMode) {
            event(controller, CTL_ADD_GOT_FOCUS, (sender, args) -> listener.onFocusChanged(true));
            event(controller, CTL_ADD_LOST_FOCUS, (sender, args) -> listener.onFocusChanged(false));
        }
        if (options.isScriptBridge()) {
            event(WV_ADD_WEB_MESSAGE_RECEIVED, (sender, args) -> {
                String source = getString(args, 3);
                if (!options.allows(source)) return;
                String json = getString(args, 4);
                if (json != null) listener.onMessage(json);
            });
        }
    }

    private void event(int slot, EventHandler handler) {
        event(webview, slot, handler);
    }

    private static void event(MemorySegment source, int slot, EventHandler handler) {
        try (Arena a = Arena.ofConfined()) {
            check(call(source, slot, PTR_PTR, onEvent(handler), a.allocate(8)), "add event (slot " + slot + ")");
        }
    }

    private void fail(Throwable t) {
        log.error("The web view could not start", t);
        listener.onFailed(t.getMessage() == null ? t.toString() : t.getMessage());
        running = false;
    }

    // ------------------------------------------------------------------------------------------------ navigation

    private void navigateNow(String url) {
        try (Arena a = Arena.ofConfined()) {
            check(call(webview, WV_NAVIGATE, PTR, wide(a, url)), "Navigate");
        }
        activity(System.nanoTime());
    }

    @Override
    public void navigate(String url) {
        if (!options.allows(url)) {
            listener.onNavigationBlocked(url);
            return;
        }
        submit(() -> navigateNow(url));
    }

    @Override
    public void loadHtml(String html) {
        submit(() -> {
            try (Arena a = Arena.ofConfined()) {
                check(call(webview, WV_NAVIGATE_TO_STRING, PTR, wide(a, html)), "NavigateToString");
            }
            activity(System.nanoTime());
        });
    }

    @Override
    public void reload() {
        submit(() -> call(webview, WV_RELOAD, NO_ARGS));
    }

    @Override
    public void stop() {
        submit(() -> call(webview, WV_STOP, NO_ARGS));
    }

    @Override
    public void goBack() {
        submit(() -> call(webview, WV_GO_BACK, NO_ARGS));
    }

    @Override
    public void goForward() {
        submit(() -> call(webview, WV_GO_FORWARD, NO_ARGS));
    }

    // ------------------------------------------------------------------------------------------------ display

    @Override
    public void resize(int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        int fw = w, fh = h;
        post(() -> {
            if (fw == width && fh == height) return;
            width = fw;
            height = fh;
            lastLength = -1;
            settled = false;
            if (webview != null) {
                applyBounds();
                if (scaleOverridden) applyScale();
            }
            activity(System.nanoTime());
        });
    }

    @Override
    public void setBounds(int x, int y, int w, int h, boolean show) {
        post(() -> {
            int nw = Math.max(1, w), nh = Math.max(1, h);
            boolean changed = x != boundsX || y != boundsY || nw != width || nh != height;
            boundsX = x;
            boundsY = y;
            width = nw;
            height = nh;
            boolean showChanged = show != visible;
            visible = show;
            if (webview == null) return;
            if (changed) applyBounds();
            if (showChanged) call(controller, CTL_PUT_VISIBLE, INT, show ? 1 : 0);
        });
    }

    /** Sets the page's rectangle: its size in the hidden window, or its place in the engine's window. */
    private void applyBounds() {
        try (Arena a = Arena.ofConfined()) {
            if (nativeMode) {
                check(call(controller, CTL_PUT_BOUNDS, RECT_ARG, rect(a, boundsX, boundsY, boundsX + width, boundsY + height)), "put_Bounds");
            } else {
                moveWindow(host, -10000, -10000, width, height);
                check(call(controller, CTL_PUT_BOUNDS, RECT_ARG, rect(a, 0, 0, width, height)), "put_Bounds");
            }
        }
    }

    /**
     * The engine sizes everything in pixels, so the page must be laid out at one page pixel to a picture pixel. On a
     * display scaled above 100% the browser would lay out at the scale and send a bigger picture; when that is seen the
     * page is told its pixel ratio is 1 and its size is the view's.
     */
    private void checkScale() {
        executeNow("window.devicePixelRatio", json -> {
            try {
                if (json != null && Math.abs(Double.parseDouble(json) - 1.0) > 0.01 && !scaleOverridden) {
                    scaleOverridden = true;
                    applyScale();
                }
            } catch (NumberFormatException ignored) {
                // not a number: leave the page as it is
            }
        });
    }

    private void applyScale() {
        devtools("Emulation.setDeviceMetricsOverride", String.format(Locale.ROOT,
                "{\"width\":%d,\"height\":%d,\"deviceScaleFactor\":1,\"mobile\":false}", width, height));
    }

    @Override
    public void keepAlive() {
        long now = System.nanoTime();
        boolean wasHidden = !shown(now);
        lastSeen = now;
        if (wasHidden && !nativeMode) {
            activeUntil = now + ACTIVE_NANOS;
            nextCaptureAt = 0;
            poke();
        }
    }

    private boolean shown(long now) {
        return now - lastSeen < SHOWN_FOR_NANOS;
    }

    /** Asks for pictures at full rate for a while: something is happening on the page. */
    private void activity(long now) {
        activeUntil = Math.max(activeUntil, now + ACTIVE_NANOS);
        nextCaptureAt = Math.min(nextCaptureAt, now);
    }

    // ------------------------------------------------------------------------------------------------ pictures

    private void tick(long now) {
        if (nativeMode || !shown(now)) return;
        if (!capturing && now >= nextCaptureAt) capture(now);
        if (pointerOver && !cursorPolling && now >= nextCursorPoll) pollCursor(now);
    }

    private void capture(long now) {
        WebViewOptions.Quality quality = options.getQuality();
        boolean png = quality == WebViewOptions.Quality.CRISP || (quality == WebViewOptions.Quality.AUTO && sawSameJpeg && !settled);
        MemorySegment stream = newMemoryStream();
        capturing = true;
        try {
            int hr = call(webview, WV_CAPTURE_PREVIEW, INT_PTR_PTR_ARGS, png ? PNG : JPEG, stream,
                    onStatus(status -> captured(stream, status, png)));
            if (hr < 0) {
                capturing = false;
                release(stream);
                nextCaptureAt = now + 250_000_000L;
            }
        } catch (RuntimeException e) {
            capturing = false;
            release(stream);
            nextCaptureAt = now + 250_000_000L;
        }
    }

    private void captured(MemorySegment stream, int status, boolean png) {
        long now = System.nanoTime();
        byte[] bytes = null;
        try {
            if (status >= 0) bytes = readStream(stream);
        } catch (RuntimeException e) {
            log.debug("Could not read a picture: {}", e.toString());
        } finally {
            release(stream);
            capturing = false;
        }
        if (bytes == null || bytes.length == 0) {
            nextCaptureAt = now + 250_000_000L;
            return;
        }
        afterCapture(now, png, bytes);
    }

    /**
     * Decides what to do with a picture and when to take the next. A page that changed is reported and photographed
     * again at once. One that looked the same twice in a row is photographed once more losslessly (in
     * {@link WebViewOptions.Quality#AUTO}), reported, and then left, checked a couple of times a second in case
     * something moves. The check uses the quick lossy pictures, compared with the last of those, so a settled page is
     * never replaced by a worse picture of itself.
     */
    private void afterCapture(long now, boolean png, byte[] bytes) {
        int w = width, h = height;
        WebViewOptions.Quality quality = options.getQuality();
        if (png && quality == WebViewOptions.Quality.AUTO) {
            settled = true;
            sawSameJpeg = false;
            listener.onFrame(new Frame(w, h, bytes, true, true));
            nextCaptureAt = now + IDLE_POLL_NANOS;
            return;
        }
        int hash = Arrays.hashCode(bytes);
        boolean same = bytes.length == lastLength && hash == lastHash;
        lastLength = bytes.length;
        lastHash = hash;
        if (!same) {
            sawSameJpeg = false;
            settled = false;
            activeUntil = Math.max(activeUntil, now + 300_000_000L);
            listener.onFrame(new Frame(w, h, bytes, true, quality == WebViewOptions.Quality.CRISP));
            nextCaptureAt = now;
        } else if (quality == WebViewOptions.Quality.AUTO && !settled) {
            sawSameJpeg = true;
            nextCaptureAt = now; // the lossless one
        } else {
            nextCaptureAt = now < activeUntil ? now : now + IDLE_POLL_NANOS;
        }
    }

    private byte[] readStream(MemorySegment s) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment position = a.allocate(8);
            check(call(s, 5, SEEK, 0L, 2 /* STREAM_SEEK_END */, position), "Seek");
            long size = position.get(JAVA_LONG, 0);
            check(call(s, 5, SEEK, 0L, 0 /* STREAM_SEEK_SET */, position), "Seek");
            MemorySegment buffer = a.allocate(size), read = a.allocate(4);
            check(call(s, 3, READ, buffer, (int) size, read), "Read");
            return buffer.asSlice(0, read.get(JAVA_INT, 0) & 0xFFFFFFFFL).toArray(JAVA_BYTE);
        }
    }

    // ------------------------------------------------------------------------------------------------ cursor

    private void pollCursor(long now) {
        nextCursorPoll = now + CURSOR_POLL_NANOS;
        cursorPolling = true;
        executeNow(CdpInput.probeScript(pointerX, pointerY), result -> {
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

    // ------------------------------------------------------------------------------------------------ input

    private void devtools(String method, String params) {
        try (Arena a = Arena.ofConfined()) {
            call(webview, WV_CALL_DEVTOOLS, PTR_PTR_PTR, wide(a, method), wide(a, params), ignoreResult());
        }
    }

    @Override
    public void pointerMove(double x, double y, int buttons, int mods) {
        pointerX = x;
        pointerY = y;
        pointerOver = true;
        activeUntil = Math.max(activeUntil, System.nanoTime() + ACTIVE_NANOS);
        submit(() -> {
            input.pointerMove(x, y, buttons, mods);
            activity(System.nanoTime());
        });
    }

    @Override
    public void pointerButton(double x, double y, int button, boolean pressed, int mods) {
        pointerX = x;
        pointerY = y;
        submit(() -> {
            input.pointerButton(x, y, button, pressed, mods);
            activity(System.nanoTime());
        });
    }

    @Override
    public void pointerScroll(double x, double y, double dx, double dy, int mods) {
        submit(() -> {
            input.pointerScroll(x, y, dx, dy, mods);
            activity(System.nanoTime());
        });
    }

    @Override
    public void pointerLeave() {
        pointerOver = false;
        submit(input::pointerLeave);
    }

    @Override
    public void key(int glfwKey, int action, int mods) {
        submit(() -> {
            input.key(glfwKey, action, mods);
            activity(System.nanoTime());
        });
    }

    @Override
    public void text(int codePoint) {
        submit(() -> {
            input.text(codePoint);
            activity(System.nanoTime());
        });
    }

    @Override
    public void composition(String text, int caret) {
        submit(() -> {
            input.composition(text, caret);
            activity(System.nanoTime());
        });
    }

    @Override
    public void setFocused(boolean focused) {
        submit(() -> {
            if (nativeMode) {
                if (focused) call(controller, CTL_MOVE_FOCUS, INT, 0);
                else setFocus(host);
            } else {
                devtools("Emulation.setFocusEmulationEnabled", "{\"enabled\":" + focused + "}");
                activity(System.nanoTime());
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ script

    private void executeNow(String script, Consumer<String> result) {
        try (Arena a = Arena.ofConfined()) {
            int hr = call(webview, WV_EXECUTE_SCRIPT, PTR_PTR, wide(a, script), onResult((status, json) -> {
                result.accept(status < 0 ? null : readWide(json));
            }));
            if (hr < 0) result.accept(null);
        }
    }

    @Override
    public void execute(String script, Consumer<String> result) {
        submit(() -> executeNow(script, result));
    }

    @Override
    public void postMessage(String json) {
        if (!options.isScriptBridge()) return;
        submit(() -> {
            try (Arena a = Arena.ofConfined()) {
                call(webview, WV_POST_WEB_MESSAGE_AS_JSON, PTR, wide(a, json));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ closing

    @Override
    public void close() {
        if (closed) return;
        Thread t = thread;
        closed = true; // no new commands
        running = false;
        poke();
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Closes the page and frees everything. Runs once, on the browser's thread. */
    private void teardown() {
        MemorySegment ctl = controller, wv = webview, environment = env;
        controller = null;
        webview = null;
        env = null;
        try {
            if (ctl != null) call(ctl, CTL_CLOSE, NO_ARGS);
        } catch (RuntimeException e) {
            log.debug("Closing the controller: {}", e.toString());
        }
        release(wv);
        release(ctl);
        release(environment);
        MemorySegment h = host;
        if (!nativeMode && h != null && h.address() != 0) {
            destroyWindow(h);
            host = null;
        }
        profile.release();
    }
}
