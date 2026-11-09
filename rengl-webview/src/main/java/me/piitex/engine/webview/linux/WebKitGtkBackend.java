package me.piitex.engine.webview.linux;

import me.piitex.engine.webview.WebViewOptions;
import me.piitex.engine.webview.internal.Profiles;
import me.piitex.engine.webview.webkit.UsLayout;
import me.piitex.engine.webview.webkit.WebKitBackend;
import me.piitex.engine.webview.webkit.WebKitJs;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.*;

/**
 * WebKitGTK (the GTK 3 flavours, {@code libwebkit2gtk-4.1} and {@code -4.0}) driven through the foreign-function API: no
 * native code of ours, and nothing for an application to ship, but the distribution's WebKitGTK has to be installed and a
 * graphical session (X11 or Wayland) has to exist. {@link WebViewOptions.Mode#PIXELS} only.
 * <p>
 * <b>Thread.</b> GTK runs on one shared thread ({@link GtkLoop}); every call below happens there.
 * <p>
 * <b>The window.</b> The view sits in a {@code GtkOffscreenWindow}, which is never shown on any screen. Pictures come from
 * {@code webkit_web_view_get_snapshot}, which the web process paints itself, so no compositor is involved (accelerated
 * compositing is switched off for the process because it has no window to draw into).
 * <p>
 * <b>Input</b> is built as {@code GdkEvent}s and delivered to the view with {@code gtk_widget_event}, so it does not depend
 * on a seat, a pointer or the focus of the real windows. A typed character is a key press carrying it (its key symbol comes
 * from the character, so any layout works). The input method's preedit cannot be injected, so {@code composition} does
 * nothing; the committed text arrives as typed characters.
 * <p>
 * <b>Never run.</b> Written without a Linux desktop. See {@code docs/webview} for what to check first.
 */
public final class WebKitGtkBackend extends WebKitBackend {
    private static final Logger log = LogManager.getLogger("WebView");

    private static final ConcurrentHashMap<Long, WebKitGtkBackend> BY_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Consumer<MemorySegment>> ASYNC = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong(1);

    // GdkEvent field offsets (64-bit); the structs are public ABI
    private static final long EVENT_WINDOW = 8, EVENT_SEND = 16;
    private static final long KEY_TIME = 20, KEY_STATE = 24, KEY_KEYVAL = 28, KEY_LENGTH = 32, KEY_STRING = 40, KEY_HARDWARE = 48;
    private static final long BUTTON_TIME = 20, BUTTON_X = 24, BUTTON_Y = 32, BUTTON_STATE = 48, BUTTON_BUTTON = 52, BUTTON_XROOT = 64, BUTTON_YROOT = 72;
    private static final long MOTION_TIME = 20, MOTION_X = 24, MOTION_Y = 32, MOTION_STATE = 48, MOTION_XROOT = 64, MOTION_YROOT = 72;
    private static final long SCROLL_TIME = 20, SCROLL_X = 24, SCROLL_Y = 32, SCROLL_STATE = 40, SCROLL_DIRECTION = 44,
            SCROLL_XROOT = 56, SCROLL_YROOT = 64, SCROLL_DX = 72, SCROLL_DY = 80;
    private static final long CROSSING_TIME = 32, CROSSING_X = 40, CROSSING_Y = 48, CROSSING_STATE = 84;
    private static final long FOCUS_IN = 18;

    /** Whether WebKitGTK and a graphical session are here. */
    public static boolean isAvailable() {
        return Gtk.available();
    }

    /** Why {@link #isAvailable()} is false, or null. */
    public static String unavailableReason() {
        return Gtk.failure();
    }

    /** Which WebKitGTK generation loaded ("4.1" or "4.0"), or null. */
    public static String version() {
        return Gtk.api();
    }

    /** The C function pointers, made once. */
    static final class Stubs {
        static final MemorySegment DECIDE = Gtk.stub(WebKitGtkBackend.class, "onDecidePolicy",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        static final MemorySegment CREATE = Gtk.stub(WebKitGtkBackend.class, "onCreate",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        static final MemorySegment LOAD_CHANGED = Gtk.stub(WebKitGtkBackend.class, "onLoadChanged",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));
        static final MemorySegment LOAD_FAILED = Gtk.stub(WebKitGtkBackend.class, "onLoadFailed",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        static final MemorySegment PERMISSION = Gtk.stub(WebKitGtkBackend.class, "onPermission",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        static final MemorySegment CONTEXT_MENU = Gtk.stub(WebKitGtkBackend.class, "onContextMenu",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        static final MemorySegment SCRIPT_DIALOG = Gtk.stub(WebKitGtkBackend.class, "onScriptDialog",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        static final MemorySegment TERMINATED = Gtk.stub(WebKitGtkBackend.class, "onTerminated",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));
        static final MemorySegment MESSAGE = Gtk.stub(WebKitGtkBackend.class, "onScriptMessage",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        static final MemorySegment ASYNC_DONE = Gtk.stub(WebKitGtkBackend.class, "onAsyncDone",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    }

    private final long id = IDS.getAndIncrement();
    private final Profiles.Profile profile;

    // GTK thread
    private MemorySegment window, view, content, keyboard, pointer, keymap;
    private boolean loadFailed;

    public WebKitGtkBackend(WebViewOptions options, Listener listener) {
        super(options, listener);
        if (!isAvailable()) throw new IllegalStateException(unavailableReason());
        if (options.getMode() == WebViewOptions.Mode.NATIVE) {
            log.info("Web view: WebKitGTK draws pictures only; a native view is drawn as PIXELS");
        }
        profile = options.getProfile() == null ? null : Profiles.open(options);
        GtkLoop.ensureStarted();
        BY_ID.put(id, this);
        GtkLoop.register(this);
        GtkLoop.post(this::start);
    }

    @Override
    protected void runOnUiThread(Runnable task) {
        GtkLoop.post(task);
    }

    void tickFromLoop() {
        tick();
    }

    // ------------------------------------------------------------------------------------------------ start and stop

    private void start() {
        if (isClosed()) return;
        try {
            MemorySegment manager;
            if (profile == null) {
                manager = Gtk.ptr("webkit_website_data_manager_new_ephemeral");
            } else {
                String data = profile.folder().toAbsolutePath().toString();
                manager = Gtk.variadicPtr("webkit_website_data_manager_new", 1, "base-data-directory", data,
                        "base-cache-directory", data + "/cache", MemorySegment.NULL);
            }
            MemorySegment context = Gtk.ptr("webkit_web_context_new_with_website_data_manager", manager);
            view = Gtk.ptr("webkit_web_view_new_with_context", context);
            Gtk.v("g_object_unref", context);
            Gtk.v("g_object_unref", manager);
            content = Gtk.ptr("webkit_web_view_get_user_content_manager", view);

            MemorySegment settings = Gtk.ptr("webkit_web_view_get_settings", view);
            Gtk.v("webkit_settings_set_javascript_can_open_windows_automatically", settings, 1);
            Gtk.v("webkit_settings_set_enable_developer_extras", settings, options.isDevTools() ? 1 : 0);
            Gtk.v("webkit_settings_set_enable_media_stream", settings, 0);

            Gtk.connect(view, "decide-policy", Stubs.DECIDE, id);
            Gtk.connect(view, "create", Stubs.CREATE, id);
            Gtk.connect(view, "load-changed", Stubs.LOAD_CHANGED, id);
            Gtk.connect(view, "load-failed", Stubs.LOAD_FAILED, id);
            Gtk.connect(view, "permission-request", Stubs.PERMISSION, id);
            Gtk.connect(view, "context-menu", Stubs.CONTEXT_MENU, id);
            Gtk.connect(view, "script-dialog", Stubs.SCRIPT_DIALOG, id);
            Gtk.connect(view, "web-process-terminated", Stubs.TERMINATED, id);

            if (options.isScriptBridge()) {
                MemorySegment script = Gtk.ptr("webkit_user_script_new", WebKitJs.bridgeShim(), 0 /* all frames */,
                        0 /* at document start */, MemorySegment.NULL, MemorySegment.NULL);
                Gtk.v("webkit_user_content_manager_add_script", content, script);
                Gtk.v("webkit_user_script_unref", script);
                Gtk.connect(content, "script-message-received::" + WebKitJs.HANDLER, Stubs.MESSAGE, id);
                Gtk.i("webkit_user_content_manager_register_script_message_handler", content, WebKitJs.HANDLER);
            }

            window = Gtk.ptr("gtk_offscreen_window_new");
            Gtk.v("gtk_container_add", window, view);
            Gtk.v("gtk_widget_set_size_request", view, width, height);
            Gtk.v("gtk_widget_show_all", window);

            MemorySegment display = Gtk.ptr("gdk_display_get_default");
            MemorySegment seat = Gtk.ptr("gdk_display_get_default_seat", display);
            pointer = Gtk.ptr("gdk_seat_get_pointer", seat);
            keyboard = Gtk.ptr("gdk_seat_get_keyboard", seat);
            keymap = Gtk.ptr("gdk_keymap_get_for_display", display);
        } catch (Throwable t) {
            failed(t.getMessage() == null ? t.toString() : t.getMessage());
            return;
        }
        becomeReady();
    }

    @Override
    protected void shutdown() {
        GtkLoop.unregister(this);
        BY_ID.remove(id);
        try {
            if (window != null) Gtk.v("gtk_widget_destroy", window); // destroys the view with it
        } finally {
            window = null;
            view = null;
            content = null;
            if (profile != null) profile.release();
        }
    }

    // ------------------------------------------------------------------------------------------------ callbacks from GTK

    private static WebKitGtkBackend backend(MemorySegment data) {
        return BY_ID.get(data.address());
    }

    /** {@code decide-policy}: TRUE when handled. */
    static int onDecidePolicy(MemorySegment webView, MemorySegment decision, int type, MemorySegment data) {
        try {
            WebKitGtkBackend b = backend(data);
            return b != null && b.decide(decision, type) ? 1 : 0;
        } catch (Throwable t) {
            log.warn("A web view navigation check failed: {}", t.toString());
            try {
                Gtk.v("webkit_policy_decision_ignore", decision);
            } catch (Throwable ignored) {
                // nothing more to do
            }
            return 1;
        }
    }

    private boolean decide(MemorySegment decision, int type) {
        switch (type) {
            case 0, 1 -> { // navigation action, new window action
                MemorySegment action = Gtk.ptr("webkit_navigation_policy_decision_get_navigation_action", decision);
                String url = Gtk.string(Gtk.ptr("webkit_uri_request_get_uri", Gtk.ptr("webkit_navigation_action_get_request", action)));
                boolean user = Gtk.i("webkit_navigation_action_is_user_gesture", action) != 0;
                boolean redirected = Gtk.has("webkit_navigation_action_is_redirect") && Gtk.i("webkit_navigation_action_is_redirect", action) != 0;
                boolean allow;
                if (url == null) {
                    allow = false;
                } else if (type == 1) {
                    newWindowRequested(url, user);
                    allow = false;
                } else {
                    allow = allowNavigation(url, user, redirected);
                }
                Gtk.v(allow ? "webkit_policy_decision_use" : "webkit_policy_decision_ignore", decision);
                return true;
            }
            case 2 -> { // a response: shown if WebKit can show it, never turned into a download
                if (Gtk.i("webkit_response_policy_decision_is_mime_type_supported", decision) == 0) {
                    Gtk.v("webkit_policy_decision_ignore", decision);
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /** {@code create} (window.open): no window is made. */
    static MemorySegment onCreate(MemorySegment webView, MemorySegment action, MemorySegment data) {
        try {
            WebKitGtkBackend b = backend(data);
            if (b != null) {
                String url = Gtk.string(Gtk.ptr("webkit_uri_request_get_uri", Gtk.ptr("webkit_navigation_action_get_request", action)));
                if (url != null) b.newWindowRequested(url, Gtk.i("webkit_navigation_action_is_user_gesture", action) != 0);
            }
        } catch (Throwable t) {
            log.warn("A web view window request failed: {}", t.toString());
        }
        return MemorySegment.NULL;
    }

    static void onLoadChanged(MemorySegment webView, int event, MemorySegment data) {
        try {
            WebKitGtkBackend b = backend(data);
            if (b == null || event != 3 /* FINISHED */) return;
            if (b.loadFailed) {
                b.loadFailed = false; // already reported by load-failed
                return;
            }
            b.navigationFinished(true, 0);
        } catch (Throwable t) {
            log.warn("A web view load callback failed: {}", t.toString());
        }
    }

    static int onLoadFailed(MemorySegment webView, int event, MemorySegment uri, MemorySegment error, MemorySegment data) {
        try {
            WebKitGtkBackend b = backend(data);
            if (b != null) {
                b.loadFailed = true;
                int code = Gtk.isNull(error) ? -1 : error.reinterpret(16).get(JAVA_INT, 4);
                b.navigationFinished(false, code);
            }
        } catch (Throwable t) {
            log.warn("A web view load callback failed: {}", t.toString());
        }
        return 0;
    }

    /** {@code permission-request}: refused unless the options allow the kind. */
    static int onPermission(MemorySegment webView, MemorySegment request, MemorySegment data) {
        boolean allowed = false;
        try {
            WebKitGtkBackend b = backend(data);
            allowed = b != null && b.permissionAllowed(request);
        } catch (Throwable t) {
            allowed = false;
        }
        try {
            Gtk.v(allowed ? "webkit_permission_request_allow" : "webkit_permission_request_deny", request);
        } catch (Throwable t) {
            log.warn("Could not answer a permission request: {}", t.toString());
        }
        return 1;
    }

    private boolean permissionAllowed(MemorySegment request) {
        if (options.getPermissions().isEmpty()) return false;
        if (isKind(request, "webkit_user_media_permission_request_get_type")) {
            boolean audio = Gtk.has("webkit_user_media_permission_is_for_audio_device")
                    && Gtk.i("webkit_user_media_permission_is_for_audio_device", request) != 0;
            boolean video = Gtk.has("webkit_user_media_permission_is_for_video_device")
                    && Gtk.i("webkit_user_media_permission_is_for_video_device", request) != 0;
            return (audio || video) && (!audio || permits(WebViewOptions.Permission.MICROPHONE))
                    && (!video || permits(WebViewOptions.Permission.CAMERA));
        }
        if (isKind(request, "webkit_geolocation_permission_request_get_type")) return permits(WebViewOptions.Permission.GEOLOCATION);
        if (isKind(request, "webkit_notification_permission_request_get_type")) return permits(WebViewOptions.Permission.NOTIFICATIONS);
        return false;
    }

    private static boolean isKind(MemorySegment object, String typeFunction) {
        return Gtk.has(typeFunction) && Gtk.i("g_type_check_instance_is_a", object, Gtk.l(typeFunction)) != 0;
    }

    /** {@code context-menu}: there is none. */
    static int onContextMenu(MemorySegment webView, MemorySegment menu, MemorySegment event, MemorySegment hit, MemorySegment data) {
        return 1;
    }

    /** {@code script-dialog}: nothing is shown; an alert is dismissed, a confirm says no, leaving a page is allowed. */
    static int onScriptDialog(MemorySegment webView, MemorySegment dialog, MemorySegment data) {
        try {
            int type = Gtk.i("webkit_script_dialog_get_dialog_type", dialog); // 0 alert, 1 confirm, 2 prompt, 3 before unload
            if (type == 1) Gtk.v("webkit_script_dialog_confirm_set_confirmed", dialog, 0);
            else if (type == 3) Gtk.v("webkit_script_dialog_confirm_set_confirmed", dialog, 1);
        } catch (Throwable t) {
            log.debug("A script dialog could not be answered: {}", t.toString());
        }
        return 1;
    }

    static void onTerminated(MemorySegment webView, int reason, MemorySegment data) {
        try {
            WebKitGtkBackend b = backend(data);
            if (b != null) b.failed("The browser process failed (reason " + reason + ")");
        } catch (Throwable t) {
            log.warn("A web view callback failed: {}", t.toString());
        }
    }

    static void onScriptMessage(MemorySegment manager, MemorySegment value, MemorySegment data) {
        try {
            WebKitGtkBackend b = backend(data);
            if (b == null || Gtk.isNull(value)) return;
            String json = Gtk.takeString(Gtk.ptr("jsc_value_to_string", value));
            b.messageFromPage(b.currentUrl(), json);
        } catch (Throwable t) {
            log.warn("A web view message failed: {}", t.toString());
        }
    }

    /** A {@code GAsyncReadyCallback}: hands the result to whoever started the call. */
    static void onAsyncDone(MemorySegment source, MemorySegment result, MemorySegment data) {
        Consumer<MemorySegment> handler = ASYNC.remove(data.address());
        if (handler == null) return;
        try {
            handler.accept(result);
        } catch (Throwable t) {
            log.warn("A web view completion failed: {}", t.toString());
        }
    }

    private MemorySegment asyncToken(Consumer<MemorySegment> handler) {
        long token = IDS.getAndIncrement();
        ASYNC.put(token, handler);
        return MemorySegment.ofAddress(token);
    }

    // ------------------------------------------------------------------------------------------------ navigation

    @Override
    protected void loadUrl(String url) {
        Gtk.v("webkit_web_view_load_uri", view, url);
    }

    @Override
    protected void loadHtmlNow(String html) {
        Gtk.v("webkit_web_view_load_html", view, html, MemorySegment.NULL);
    }

    @Override
    protected void reloadNow() {
        Gtk.v("webkit_web_view_reload", view);
    }

    @Override
    protected void stopNow() {
        Gtk.v("webkit_web_view_stop_loading", view);
    }

    @Override
    protected void goBackNow() {
        Gtk.v("webkit_web_view_go_back", view);
    }

    @Override
    protected void goForwardNow() {
        Gtk.v("webkit_web_view_go_forward", view);
    }

    @Override
    protected String currentUrl() {
        return view == null ? null : Gtk.string(Gtk.ptr("webkit_web_view_get_uri", view));
    }

    @Override
    protected String currentTitle() {
        return view == null ? null : Gtk.string(Gtk.ptr("webkit_web_view_get_title", view));
    }

    @Override
    protected boolean canGoBackNow() {
        return view != null && Gtk.i("webkit_web_view_can_go_back", view) != 0;
    }

    @Override
    protected boolean canGoForwardNow() {
        return view != null && Gtk.i("webkit_web_view_can_go_forward", view) != 0;
    }

    @Override
    protected void applySize(int w, int h) {
        if (view != null) Gtk.v("gtk_widget_set_size_request", view, w, h);
    }

    // ------------------------------------------------------------------------------------------------ script and pictures

    @Override
    protected void evaluateRaw(String script, Consumer<String> result) {
        if (view == null) {
            if (result != null) result.accept(null);
            return;
        }
        MemorySegment webView = view;
        boolean modern = Gtk.has("webkit_web_view_evaluate_javascript");
        MemorySegment callback = MemorySegment.NULL, token = MemorySegment.NULL;
        if (result != null) {
            callback = Stubs.ASYNC_DONE;
            token = asyncToken(asyncResult -> result.accept(finishScript(webView, asyncResult, modern)));
        }
        if (modern) {
            Gtk.v("webkit_web_view_evaluate_javascript", view, script, -1L, MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, callback, token);
        } else {
            Gtk.v("webkit_web_view_run_javascript", view, script, MemorySegment.NULL, callback, token);
        }
    }

    /** The script's value as text, or null when it threw. */
    private static String finishScript(MemorySegment webView, MemorySegment asyncResult, boolean modern) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment error = a.allocate(8);
            MemorySegment value, wrapper = MemorySegment.NULL;
            if (modern) {
                value = Gtk.ptr("webkit_web_view_evaluate_javascript_finish", webView, asyncResult, error);
            } else {
                wrapper = Gtk.ptr("webkit_web_view_run_javascript_finish", webView, asyncResult, error);
                value = Gtk.isNull(wrapper) ? MemorySegment.NULL : Gtk.ptr("webkit_javascript_result_get_js_value", wrapper);
            }
            MemorySegment gerror = error.get(ADDRESS, 0);
            if (!Gtk.isNull(gerror)) Gtk.v("g_error_free", gerror);
            if (Gtk.isNull(value)) {
                if (!Gtk.isNull(wrapper)) Gtk.v("webkit_javascript_result_unref", wrapper);
                return null;
            }
            String text = Gtk.takeString(Gtk.ptr("jsc_value_to_string", value));
            if (modern) Gtk.v("g_object_unref", value);
            else Gtk.v("webkit_javascript_result_unref", wrapper);
            return text;
        }
    }

    @Override
    protected void capture(Consumer<Picture> done) {
        if (view == null) {
            done.accept(null);
            return;
        }
        MemorySegment webView = view;
        MemorySegment token = asyncToken(asyncResult -> done.accept(finishSnapshot(webView, asyncResult)));
        Gtk.v("webkit_web_view_get_snapshot", view, 0 /* the visible region */, 0 /* no options */, MemorySegment.NULL,
                Stubs.ASYNC_DONE, token);
    }

    private static Picture finishSnapshot(MemorySegment webView, MemorySegment asyncResult) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment error = a.allocate(8);
            MemorySegment surface = Gtk.ptr("webkit_web_view_get_snapshot_finish", webView, asyncResult, error);
            MemorySegment gerror = error.get(ADDRESS, 0);
            if (!Gtk.isNull(gerror)) Gtk.v("g_error_free", gerror);
            if (Gtk.isNull(surface)) return null;
            try {
                Gtk.v("cairo_surface_flush", surface);
                int w = Gtk.i("cairo_image_surface_get_width", surface), h = Gtk.i("cairo_image_surface_get_height", surface);
                int stride = Gtk.i("cairo_image_surface_get_stride", surface);
                int format = Gtk.i("cairo_image_surface_get_format", surface); // 0 ARGB32, 1 RGB24
                MemorySegment data = Gtk.ptr("cairo_image_surface_get_data", surface);
                if (w <= 0 || h <= 0 || Gtk.isNull(data) || (format != 0 && format != 1)) return null;
                MemorySegment pixels = data.reinterpret((long) stride * h);
                byte[] out = new byte[w * h * 4];
                MemorySegment target = MemorySegment.ofArray(out);
                for (int y = 0; y < h; y++) MemorySegment.copy(pixels, (long) y * stride, target, (long) y * w * 4, w * 4L);
                if (format == 1) for (int i = 3; i < out.length; i += 4) out[i] = (byte) 0xFF; // no alpha channel: opaque
                return new Picture(w, h, out);
            } finally {
                Gtk.v("cairo_surface_destroy", surface);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ input

    private static long now() {
        return Gtk.l("g_get_monotonic_time") / 1000L;
    }

    /** A new event of this type aimed at the view's window, with the pointer or keyboard as its device. Free with {@link #deliver}. */
    private MemorySegment event(int type, boolean forKeyboard) {
        MemorySegment e = Gtk.ptr("gdk_event_new", type).reinterpret(128);
        MemorySegment gdkWindow = Gtk.ptr("gtk_widget_get_window", view);
        Gtk.v("g_object_ref", gdkWindow);
        e.set(ADDRESS, EVENT_WINDOW, gdkWindow);
        e.set(JAVA_BYTE, EVENT_SEND, (byte) 0);
        MemorySegment device = forKeyboard ? keyboard : pointer;
        if (!Gtk.isNull(device)) {
            Gtk.v("g_object_ref", device);
            Gtk.v("gdk_event_set_device", e, device);
            Gtk.v("gdk_event_set_source_device", e, device);
        }
        return e;
    }

    private void deliver(MemorySegment e) {
        try {
            Gtk.i("gtk_widget_event", view, e);
        } finally {
            Gtk.v("gdk_event_free", e);
        }
    }

    @Override
    protected void sendMove(double x, double y, int buttons, int mods) {
        MemorySegment e = event(GdkKeys.MOTION_NOTIFY, false);
        e.set(JAVA_INT, MOTION_TIME, (int) now());
        e.set(JAVA_DOUBLE, MOTION_X, x);
        e.set(JAVA_DOUBLE, MOTION_Y, y);
        e.set(JAVA_INT, MOTION_STATE, GdkKeys.state(mods) | GdkKeys.heldMask(buttons));
        e.set(JAVA_DOUBLE, MOTION_XROOT, x);
        e.set(JAVA_DOUBLE, MOTION_YROOT, y);
        deliver(e);
    }

    @Override
    protected void sendButton(double x, double y, int glfwButton, boolean pressed, int clickCount, int mods) {
        int button = GdkKeys.button(glfwButton);
        MemorySegment e = event(pressed ? GdkKeys.BUTTON_PRESS : GdkKeys.BUTTON_RELEASE, false);
        e.set(JAVA_INT, BUTTON_TIME, (int) now());
        e.set(JAVA_DOUBLE, BUTTON_X, x);
        e.set(JAVA_DOUBLE, BUTTON_Y, y);
        e.set(JAVA_INT, BUTTON_STATE, GdkKeys.state(mods) | (pressed ? 0 : GdkKeys.buttonMask(button)));
        e.set(JAVA_INT, BUTTON_BUTTON, button);
        e.set(JAVA_DOUBLE, BUTTON_XROOT, x);
        e.set(JAVA_DOUBLE, BUTTON_YROOT, y);
        deliver(e); // WebKit counts the clicks itself, from the times and places of the presses
    }

    @Override
    protected void sendScroll(double x, double y, double dx, double dy, int mods) {
        MemorySegment e = event(GdkKeys.SCROLL, false);
        e.set(JAVA_INT, SCROLL_TIME, (int) now());
        e.set(JAVA_DOUBLE, SCROLL_X, x);
        e.set(JAVA_DOUBLE, SCROLL_Y, y);
        e.set(JAVA_INT, SCROLL_STATE, GdkKeys.state(mods));
        e.set(JAVA_INT, SCROLL_DIRECTION, GdkKeys.SCROLL_SMOOTH);
        e.set(JAVA_DOUBLE, SCROLL_XROOT, x);
        e.set(JAVA_DOUBLE, SCROLL_YROOT, y);
        e.set(JAVA_DOUBLE, SCROLL_DX, dx);
        e.set(JAVA_DOUBLE, SCROLL_DY, -dy); // GDK counts scrolling down as positive
        deliver(e);
    }

    @Override
    protected void sendLeave() {
        MemorySegment e = event(GdkKeys.LEAVE_NOTIFY, false);
        e.set(JAVA_INT, CROSSING_TIME, (int) now());
        e.set(JAVA_DOUBLE, CROSSING_X, -1.0);
        e.set(JAVA_DOUBLE, CROSSING_Y, -1.0);
        e.set(JAVA_INT, CROSSING_STATE, 0);
        deliver(e);
    }

    /** The hardware key code that types {@code keyval} on the current layout, or 0. */
    private int hardwareKeycode(int keyval) {
        if (Gtk.isNull(keymap)) return 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment keys = a.allocate(8), count = a.allocate(4);
            if (Gtk.i("gdk_keymap_get_entries_for_keyval", keymap, keyval, keys, count) == 0) return 0;
            MemorySegment array = keys.get(ADDRESS, 0);
            int n = count.get(JAVA_INT, 0);
            int code = n > 0 ? array.reinterpret(12L * n).get(JAVA_INT, 0) : 0; // GdkKeymapKey { keycode, group, level }
            Gtk.v("g_free", array);
            return code;
        }
    }

    private void keyEvent(int type, int keyval, int state, String text) {
        MemorySegment e = event(type, true);
        e.set(JAVA_INT, KEY_TIME, (int) now());
        e.set(JAVA_INT, KEY_STATE, state);
        e.set(JAVA_INT, KEY_KEYVAL, keyval);
        if (text != null) {
            byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
            e.set(JAVA_INT, KEY_LENGTH, utf8.length);
            e.set(ADDRESS, KEY_STRING, Gtk.ptr("g_strdup", text)); // gdk_event_free frees it with g_free
        }
        e.set(JAVA_SHORT, KEY_HARDWARE, (short) hardwareKeycode(keyval));
        deliver(e);
    }

    @Override
    protected void sendKey(int glfwKey, int action, int mods) {
        int keyval = GdkKeys.keyval(glfwKey);
        if (keyval == 0) return;
        keyEvent(action == GLFW.GLFW_RELEASE ? GdkKeys.KEY_RELEASE : GdkKeys.KEY_PRESS, keyval, GdkKeys.state(mods), null);
    }

    @Override
    protected void sendEditCommand(String command) {
        String name = GdkKeys.editingCommand(command);
        if (name != null && view != null) Gtk.v("webkit_web_view_execute_editing_command", view, name);
    }

    @Override
    protected void sendText(int codePoint) {
        if (view == null) return;
        int keyval = Gtk.i("gdk_unicode_to_keyval", codePoint);
        int state = UsLayout.needsShift(codePoint) ? GdkKeys.SHIFT_MASK : 0;
        String s = new String(Character.toChars(codePoint));
        keyEvent(GdkKeys.KEY_PRESS, keyval, state, s);
        keyEvent(GdkKeys.KEY_RELEASE, keyval, state, s);
    }

    @Override
    protected void sendComposition(String text, int caret) {
        // WebKitGTK reads the preedit from its own GtkIMContext, which cannot be fed from here; the committed text arrives as typed characters
    }

    @Override
    protected void sendFocus(boolean focused) {
        if (view == null) return;
        if (focused) Gtk.v("gtk_widget_grab_focus", view);
        MemorySegment e = event(GdkKeys.FOCUS_CHANGE, true);
        e.set(JAVA_SHORT, FOCUS_IN, (short) (focused ? 1 : 0));
        deliver(e);
    }
}
