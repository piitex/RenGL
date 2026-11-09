package me.piitex.engine.webview.macos;

import me.piitex.engine.webview.WebViewOptions;
import me.piitex.engine.webview.internal.CdpInput;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Apple's WKWebView, driven through the Objective-C runtime from Java: nothing to install on a Mac and no native code of
 * ours. The page is drawn off screen and reaches the engine as pictures ({@link WebViewOptions.Mode#PIXELS} only).
 * <p>
 * <b>Thread.</b> AppKit may only be called from the main thread, so every call is queued onto it with
 * {@code dispatch_async_f}. That works when the main thread runs its run loop, which a program with a window does anyway
 * (GLFW's {@code glfwPollEvents} is the run loop, under {@code -XstartOnFirstThread}). A program that does not (a test, a
 * command-line tool) has to call {@link #pumpMainThread} from the main thread.
 * <p>
 * <b>The window.</b> A web view only paints inside a window, so it sits in a borderless window that is fully transparent,
 * ignores the mouse, is ordered behind everything and sits at the screen's bottom-left corner. It never takes the
 * keyboard from the engine's own window: it answers {@code isKeyWindow} for itself, so the page believes it has focus when
 * the engine says it does.
 * <p>
 * <b>Pictures</b> come from {@code takeSnapshotWithConfiguration:}, lossless, at the display's pixel size (twice the page
 * size on a Retina screen, which makes the page sharper; the element scales it).
 * <p>
 * <b>Input</b> is built as {@code NSEvent}s and handed to the view directly, so it does not depend on which window is
 * active or where the mouse really is. A typed character goes in through {@code insertText:} (or a key press carrying
 * it), so the layout and the input method are the system's own.
 * <p>
 * <b>Never run.</b> Written without a Mac, from Apple's documentation and WebKit's source. See {@code docs/webview} for
 * what to check first.
 */
public final class WKWebViewBackend extends WebKitBackend {
    private static final Logger log = LogManager.getLogger("WebView");

    private static final double PIXELS_PER_NOTCH = 80;
    private static final String WINDOW_CLASS = "RenGLWebViewWindow";

    private static final ConcurrentHashMap<Long, WKWebViewBackend> BY_WINDOW = new ConcurrentHashMap<>();

    /** Whether a Mac with WKWebView is here. */
    public static boolean isAvailable() {
        return ObjC.available() && ObjC.hasClass("WKWebView");
    }

    /** Why {@link #isAvailable()} is false, or null. */
    public static String unavailableReason() {
        if (isAvailable()) return null;
        String why = ObjC.failure();
        return why != null ? why : "WKWebView was not found in the WebKit framework";
    }

    /**
     * Runs the main thread's run loop for about this long, if called from it. The backend's work is queued onto that
     * thread; a program that has no loop of its own (a test) calls this while it waits. From any other thread it only
     * sleeps.
     */
    public static void pumpMainThread(long millis) {
        try {
            if (ObjC.available() && ObjC.isMainThread()) ObjC.pump(millis);
            else Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // main thread
    private MemorySegment window, webView, delegate, userContent;
    private boolean hovering, marked;
    private int windowNumber;
    private volatile boolean keyWindow;
    private ScheduledExecutorService clock;
    private final AtomicBoolean tickQueued = new AtomicBoolean();

    public WKWebViewBackend(WebViewOptions options, Listener listener) {
        super(options, listener);
        if (!isAvailable()) throw new IllegalStateException(unavailableReason());
        if (options.getMode() == WebViewOptions.Mode.NATIVE) {
            log.info("Web view: WKWebView draws pictures only; a native view is drawn as PIXELS");
        }
        ObjC.onMainThread(this::start);
    }

    @Override
    protected void runOnUiThread(Runnable task) {
        ObjC.onMainThread(task);
    }

    // ------------------------------------------------------------------------------------------------ start and stop

    private void start() {
        if (isClosed()) return;
        try (ObjC.Pool pool = ObjC.pool(); Arena a = Arena.ofConfined()) {
            MemorySegment app = ObjC.id(ObjC.cls("NSApplication"), "sharedApplication");
            // a program with no bundle starts "prohibited" and cannot have windows; "accessory" has windows and no Dock icon
            if (ObjC.lng(app, "activationPolicy") == 2) ObjC.run(app, "setActivationPolicy:", 1L);

            MemorySegment config = ObjC.id(ObjC.alloc("WKWebViewConfiguration"), "init");
            ObjC.run(ObjC.id(config, "preferences"), "setJavaScriptCanOpenWindowsAutomatically:", true);
            ObjC.run(config, "setWebsiteDataStore:", dataStore());
            userContent = ObjC.id(config, "userContentController");
            delegate = WkDelegate.create(this);
            if (options.isScriptBridge()) {
                MemorySegment script = ObjC.id(ObjC.alloc("WKUserScript"), "initWithSource:injectionTime:forMainFrameOnly:",
                        ObjC.string(a, WebKitJs.bridgeShim()), 0L /* at document start */, false);
                ObjC.run(userContent, "addUserScript:", script);
                ObjC.release(script);
                ObjC.run(userContent, "addScriptMessageHandler:name:", delegate, ObjC.string(a, WebKitJs.HANDLER));
            }

            webView = ObjC.id(ObjC.alloc("WKWebView"), "initWithFrame:configuration:", ObjC.rect(a, 0, 0, width, height), config);
            ObjC.release(config);
            ObjC.run(webView, "setNavigationDelegate:", delegate);
            ObjC.run(webView, "setUIDelegate:", delegate);
            ObjC.run(webView, "setAllowsBackForwardNavigationGestures:", false);
            if (options.isDevTools() && ObjC.responds(webView, "setInspectable:")) ObjC.run(webView, "setInspectable:", true);

            MemorySegment windowClass = ObjC.defineClass(WINDOW_CLASS, "NSWindow", List.of(), windowImps());
            window = ObjC.id(ObjC.id(windowClass, "alloc"), "initWithContentRect:styleMask:backing:defer:",
                    ObjC.rect(a, 0, 0, width, height), 0L /* borderless */, 2L /* buffered */, false);
            BY_WINDOW.put(window.address(), this);
            ObjC.run(window, "setReleasedWhenClosed:", false);
            ObjC.run(window, "setContentView:", webView);
            ObjC.run(window, "setAlphaValue:", 0.0);
            ObjC.run(window, "setIgnoresMouseEvents:", true);
            ObjC.run(window, "setHasShadow:", false);
            ObjC.run(window, "setFrameOrigin:", ObjC.point(a, 0, 0));
            ObjC.run(window, "orderBack:", MemorySegment.NULL);
            windowNumber = (int) ObjC.lng(window, "windowNumber");
        } catch (Throwable t) {
            failed(t.getMessage() == null ? t.toString() : t.getMessage());
            return;
        }
        clock = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RenGL-WebView-clock");
            t.setDaemon(true);
            return t;
        });
        clock.scheduleWithFixedDelay(() -> {
            if (tickQueued.compareAndSet(false, true)) runOnUiThread(() -> {
                tickQueued.set(false);
                tick();
            });
        }, 40, 40, TimeUnit.MILLISECONDS);
        becomeReady();
    }

    /** Nothing kept (the default), or a store of the app's own for a named profile folder. */
    private MemorySegment dataStore() {
        MemorySegment store = ObjC.cls("WKWebsiteDataStore");
        if (options.getProfile() == null) return ObjC.id(store, "nonPersistentDataStore");
        if (ObjC.responds(store, "dataStoreForIdentifier:")) { // macOS 14: a store per identifier; a folder cannot be named
            UUID id = UUID.nameUUIDFromBytes(options.getProfile().toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            MemorySegment uuid = ObjC.id(ObjC.alloc("NSUUID"), "initWithUUIDString:", ObjC.string(id.toString().toUpperCase()));
            MemorySegment persistent = ObjC.id(store, "dataStoreForIdentifier:", uuid);
            ObjC.release(uuid);
            if (!ObjC.isNull(persistent)) return persistent;
        }
        log.info("Web view: WKWebView cannot keep a profile in a named folder before macOS 14; using the app's default store");
        return ObjC.id(store, "defaultDataStore");
    }

    @Override
    protected void shutdown() {
        if (clock != null) clock.shutdownNow();
        if (!ObjC.available()) return;
        try (ObjC.Pool pool = ObjC.pool()) {
            if (webView != null) {
                ObjC.run(webView, "stopLoading");
                ObjC.run(webView, "setNavigationDelegate:", MemorySegment.NULL);
                ObjC.run(webView, "setUIDelegate:", MemorySegment.NULL);
            }
            if (userContent != null && options.isScriptBridge()) {
                ObjC.run(userContent, "removeScriptMessageHandlerForName:", ObjC.string(WebKitJs.HANDLER));
            }
            if (window != null) {
                BY_WINDOW.remove(window.address());
                ObjC.run(window, "close");
            }
            ObjC.release(window);
            ObjC.release(webView);
            if (delegate != null) {
                WkDelegate.forget(delegate);
                ObjC.release(delegate);
            }
            window = null;
            webView = null;
            delegate = null;
            userContent = null;
        }
    }

    // ------------------------------------------------------------------------------------------------ what the delegate reports

    /** Called by the delegate for each navigation about to start; returns whether it may. */
    boolean decideNavigation(MemorySegment action) {
        String url = actionUrl(action);
        if (url == null) return false;
        long type = ObjC.lng(action, "navigationType"); // 0 link, 1 form, 2 back/forward, 3 reload, 4 resubmit, -1 other
        boolean user = type == 0 || type == 1;
        if (ObjC.isNull(ObjC.id(action, "targetFrame"))) { // a link with a target: a new window
            newWindowRequested(url, user);
            return false;
        }
        return allowNavigation(url, user, false);
    }

    void windowOpenRequested(MemorySegment action) {
        String url = actionUrl(action);
        if (url != null) newWindowRequested(url, ObjC.lng(action, "navigationType") == 0);
    }

    private static String actionUrl(MemorySegment action) {
        return ObjC.text(ObjC.id(ObjC.id(ObjC.id(action, "request"), "URL"), "absoluteString"));
    }

    void navigationDone(boolean success, int status) {
        navigationFinished(success, status);
    }

    void processDied() {
        failed("The browser process failed");
    }

    /** @param type 0 camera, 1 microphone, 2 both */
    boolean mediaAllowed(long type) {
        return switch ((int) type) {
            case 0 -> permits(WebViewOptions.Permission.CAMERA);
            case 1 -> permits(WebViewOptions.Permission.MICROPHONE);
            case 2 -> permits(WebViewOptions.Permission.CAMERA) && permits(WebViewOptions.Permission.MICROPHONE);
            default -> false;
        };
    }

    void scriptMessage(MemorySegment message) {
        if (!WebKitJs.HANDLER.equals(ObjC.text(ObjC.id(message, "name")))) return;
        MemorySegment request = ObjC.id(ObjC.id(message, "frameInfo"), "request");
        String source = ObjC.text(ObjC.id(ObjC.id(request, "URL"), "absoluteString"));
        messageFromPage(source, ObjC.describe(ObjC.id(message, "body")));
    }

    // ------------------------------------------------------------------------------------------------ the window's key state

    /** The methods of the window class that answer for the key state. */
    static List<ObjC.Imp> windowImps() {
        FunctionDescriptor yesNo = FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS);
        return List.of(
                ObjC.imp("isKeyWindow", "c@:", WKWebViewBackend.class, "windowIsKey", yesNo),
                ObjC.imp("canBecomeKeyWindow", "c@:", WKWebViewBackend.class, "windowYes", yesNo));
    }

    static byte windowIsKey(MemorySegment self, MemorySegment cmd) {
        try {
            WKWebViewBackend b = BY_WINDOW.get(self.address());
            return (byte) (b != null && b.keyWindow ? 1 : 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    static byte windowYes(MemorySegment self, MemorySegment cmd) {
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ navigation

    @Override
    protected void loadUrl(String url) {
        MemorySegment target = ObjC.url(url);
        if (ObjC.isNull(target)) {
            log.warn("Web view: not an address: {}", url);
            return;
        }
        ObjC.run(webView, "loadRequest:", ObjC.id(ObjC.cls("NSURLRequest"), "requestWithURL:", target));
    }

    @Override
    protected void loadHtmlNow(String html) {
        ObjC.run(webView, "loadHTMLString:baseURL:", ObjC.string(html), MemorySegment.NULL);
    }

    @Override
    protected void reloadNow() {
        ObjC.run(webView, "reload");
    }

    @Override
    protected void stopNow() {
        ObjC.run(webView, "stopLoading");
    }

    @Override
    protected void goBackNow() {
        ObjC.run(webView, "goBack");
    }

    @Override
    protected void goForwardNow() {
        ObjC.run(webView, "goForward");
    }

    @Override
    protected String currentUrl() {
        return webView == null ? null : ObjC.text(ObjC.id(ObjC.id(webView, "URL"), "absoluteString"));
    }

    @Override
    protected String currentTitle() {
        return webView == null ? null : ObjC.text(ObjC.id(webView, "title"));
    }

    @Override
    protected boolean canGoBackNow() {
        return webView != null && ObjC.bool(webView, "canGoBack");
    }

    @Override
    protected boolean canGoForwardNow() {
        return webView != null && ObjC.bool(webView, "canGoForward");
    }

    // ------------------------------------------------------------------------------------------------ display

    @Override
    protected void applySize(int w, int h) {
        if (webView == null) return;
        try (Arena a = Arena.ofConfined()) {
            ObjC.run(window, "setContentSize:", ObjC.point(a, w, h));
            ObjC.run(window, "setFrameOrigin:", ObjC.point(a, 0, 0)); // resizing keeps the top left; the corner is what the scroll maths wants
            ObjC.run(webView, "setFrame:", ObjC.rect(a, 0, 0, w, h));
        }
    }

    @Override
    protected void capture(Consumer<Picture> done) {
        if (webView == null) {
            done.accept(null);
            return;
        }
        MemorySegment configuration = ObjC.id(ObjC.alloc("WKSnapshotConfiguration"), "init");
        MemorySegment block = ObjC.block((image, error) -> done.accept(ObjC.isNull(image) ? null : ObjC.picture(image)));
        ObjC.run(webView, "takeSnapshotWithConfiguration:completionHandler:", configuration, block);
        ObjC.release(configuration);
    }

    // ------------------------------------------------------------------------------------------------ script

    @Override
    protected void evaluateRaw(String script, Consumer<String> result) {
        if (webView == null) {
            if (result != null) result.accept(null);
            return;
        }
        MemorySegment handler = result == null ? MemorySegment.NULL
                : ObjC.block((value, error) -> result.accept(ObjC.isNull(error) ? ObjC.describe(value) : null));
        ObjC.run(webView, "evaluateJavaScript:completionHandler:", ObjC.string(script), handler);
    }

    // ------------------------------------------------------------------------------------------------ input

    private static double uptime() {
        return ObjC.dbl(ObjC.id(ObjC.cls("NSProcessInfo"), "processInfo"), "systemUptime");
    }

    private void mouseEvent(int type, double x, double y, long flags, int clickCount) {
        if (webView == null) return;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment event;
            if (type == MacKeys.MOUSE_ENTERED || type == MacKeys.MOUSE_EXITED) {
                event = ObjC.id(ObjC.cls("NSEvent"),
                        "enterExitEventWithType:location:modifierFlags:timestamp:windowNumber:context:eventNumber:trackingNumber:userData:",
                        (long) type, ObjC.point(a, x, height - y), flags, uptime(), (long) windowNumber, MemorySegment.NULL,
                        0L, 0L, MemorySegment.NULL);
            } else {
                boolean down = type == MacKeys.LEFT_MOUSE_DOWN || type == MacKeys.RIGHT_MOUSE_DOWN || type == MacKeys.OTHER_MOUSE_DOWN;
                event = ObjC.id(ObjC.cls("NSEvent"),
                        "mouseEventWithType:location:modifierFlags:timestamp:windowNumber:context:eventNumber:clickCount:pressure:",
                        (long) type, ObjC.point(a, x, height - y), flags, uptime(), (long) windowNumber, MemorySegment.NULL,
                        0L, (long) clickCount, down ? 1f : 0f);
            }
            if (!ObjC.isNull(event)) ObjC.run(webView, MacKeys.responderSelector(type), event);
        }
    }

    @Override
    protected void sendMove(double x, double y, int buttons, int mods) {
        long flags = MacKeys.modifierFlags(mods);
        if (!hovering) {
            hovering = true;
            mouseEvent(MacKeys.MOUSE_ENTERED, x, y, flags, 0);
        }
        mouseEvent(MacKeys.mouseMoveType(buttons), x, y, flags, 0);
    }

    @Override
    protected void sendButton(double x, double y, int glfwButton, boolean pressed, int clickCount, int mods) {
        mouseEvent(MacKeys.mouseButtonType(glfwButton, pressed), x, y, MacKeys.modifierFlags(mods), Math.max(pressed ? 1 : 0, clickCount));
    }

    @Override
    protected void sendScroll(double x, double y, double dx, double dy, int mods) {
        if (webView == null) return;
        int pixelsY = (int) Math.round(dy * PIXELS_PER_NOTCH);
        int pixelsX = (int) Math.round(-dx * PIXELS_PER_NOTCH); // CoreGraphics counts a scroll to the left as positive
        try (Arena a = Arena.ofConfined()) {
            // window coordinates equal screen coordinates because the window's corner is the screen's corner; CoreGraphics
            // measures down from the top of the display
            double displayY = ObjC.mainDisplayHeight() - (height - y);
            MemorySegment event = ObjC.scrollEvent(a, pixelsX, pixelsY, x, displayY);
            if (!ObjC.isNull(event)) {
                ObjC.run(webView, "scrollWheel:", event);
                return;
            }
        } catch (RuntimeException e) {
            log.debug("Web view: a scroll event could not be made: {}", e.toString());
        }
        evaluateRaw("window.scrollBy(" + (-pixelsX) + "," + (-pixelsY) + "); 0", null);
    }

    @Override
    protected void sendLeave() {
        if (hovering) {
            hovering = false;
            mouseEvent(MacKeys.MOUSE_EXITED, 0, 0, 0, 0);
        }
    }

    private void keyEvent(int type, String characters, String charactersIgnoringModifiers, long flags, int keyCode, boolean repeat) {
        if (webView == null || keyCode < 0) return;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment event = ObjC.id(ObjC.cls("NSEvent"),
                    "keyEventWithType:location:modifierFlags:timestamp:windowNumber:context:characters:charactersIgnoringModifiers:isARepeat:keyCode:",
                    (long) type, ObjC.point(a, 0, 0), flags, uptime(), (long) windowNumber, MemorySegment.NULL,
                    ObjC.string(a, characters), ObjC.string(a, charactersIgnoringModifiers), repeat, (short) keyCode);
            if (!ObjC.isNull(event)) ObjC.run(webView, MacKeys.responderSelector(type), event);
        }
    }

    @Override
    protected void sendKey(int glfwKey, int action, int mods) {
        int code = MacKeys.keyCode(glfwKey);
        if (code < 0) return;
        String chars = MacKeys.functionCharacters(glfwKey);
        if (chars == null) chars = MacKeys.baseCharacter(glfwKey);
        if (chars == null) chars = "";
        keyEvent(action == GLFW.GLFW_RELEASE ? MacKeys.KEY_UP : MacKeys.KEY_DOWN, chars, chars,
                MacKeys.keyFlags(glfwKey, mods), code, action == GLFW.GLFW_REPEAT);
    }

    @Override
    protected void sendEditCommand(String command) {
        if (webView == null) return;
        String selector = switch (command) {
            case "selectAll" -> "selectAll:";
            case "copy" -> "copy:";
            case "cut" -> "cut:";
            case "paste" -> "paste:";
            case "pasteAndMatchStyle" -> "pasteAsPlainText:";
            default -> null;
        };
        if (selector != null && ObjC.responds(webView, selector)) {
            ObjC.run(webView, selector, MemorySegment.NULL);
            return;
        }
        evaluateRaw("document.execCommand(" + CdpInput.quote(command.equals("pasteAndMatchStyle") ? "paste" : command) + "); 0", null);
    }

    @Override
    protected void sendText(int codePoint) {
        if (webView == null) return;
        String s = new String(Character.toChars(codePoint));
        int code = MacKeys.keyCodeForChar(codePoint);
        // an ordinary typed character is a key press carrying it, so the page gets keydown, keypress and input events; anything
        // else (an accented letter, text from an input method) goes in as text
        if (!marked && codePoint < 128 && code >= 0 && UsLayout.hasKey(codePoint)) {
            long flags = UsLayout.needsShift(codePoint) ? MacKeys.FLAG_SHIFT : 0;
            String base = new String(Character.toChars(UsLayout.unshifted(codePoint)));
            keyEvent(MacKeys.KEY_DOWN, s, base, flags, code, false);
            keyEvent(MacKeys.KEY_UP, s, base, flags, code, false);
            return;
        }
        if (ObjC.responds(webView, "insertText:replacementRange:")) {
            try (Arena a = Arena.ofConfined()) {
                ObjC.run(webView, "insertText:replacementRange:", ObjC.string(a, s), ObjC.range(a, ObjC.NOT_FOUND, 0));
            }
            marked = false;
        }
    }

    @Override
    protected void sendComposition(String text, int caret) {
        if (webView == null || !ObjC.responds(webView, "setMarkedText:selectedRange:replacementRange:")) return;
        try (Arena a = Arena.ofConfined()) {
            if (text.isEmpty()) {
                if (marked) {
                    ObjC.run(webView, "setMarkedText:selectedRange:replacementRange:", ObjC.string(a, ""),
                            ObjC.range(a, 0, 0), ObjC.range(a, ObjC.NOT_FOUND, 0));
                    ObjC.run(webView, "unmarkText");
                    marked = false;
                }
                return;
            }
            ObjC.run(webView, "setMarkedText:selectedRange:replacementRange:", ObjC.string(a, text),
                    ObjC.range(a, caret, 0), ObjC.range(a, ObjC.NOT_FOUND, 0));
            marked = true;
        }
    }

    @Override
    protected void sendFocus(boolean focused) {
        if (window == null) return;
        keyWindow = focused;
        if (focused) ObjC.run(window, "makeFirstResponder:", webView);
        // the page learns it is active from the window's key notifications; there is no real key change to announce
        MemorySegment center = ObjC.id(ObjC.cls("NSNotificationCenter"), "defaultCenter");
        ObjC.run(center, "postNotificationName:object:",
                ObjC.string(focused ? "NSWindowDidBecomeKeyNotification" : "NSWindowDidResignKeyNotification"), window);
    }
}
