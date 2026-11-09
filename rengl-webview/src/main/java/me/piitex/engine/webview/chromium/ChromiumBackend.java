package me.piitex.engine.webview.chromium;

import me.piitex.engine.io.JsonWriter;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.webview.WebViewBackend;
import me.piitex.engine.webview.WebViewOptions;
import me.piitex.engine.webview.internal.CdpInput;
import me.piitex.engine.webview.internal.Profiles;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Any Chromium-based browser installed on the machine (Chrome, Edge, Chromium, Brave), run headless and driven through
 * the Chrome DevTools protocol over a loopback WebSocket. It is the same code on Windows, macOS and Linux, and needs no
 * native code of ours. The browser has to be installed; the page is drawn off screen and reaches the engine as pictures
 * ({@link WebViewOptions.Mode#PIXELS} only).
 * <p>
 * <b>Pictures.</b> The browser's screencast sends a lossy picture each time the page changes, and once it has stopped
 * changing a lossless one is taken (see {@link WebViewOptions.Quality}). Nothing is sent while the page is hidden.
 * <p>
 * <b>Input</b> is the same DevTools {@code Input} calls the WebView2 backend uses ({@link CdpInput}).
 * <p>
 * <b>Navigation</b> is checked in the browser's network layer before a request leaves, so a refused address is never
 * contacted. A redirect to an address that is not a web address (an app's own scheme) is caught in the response.
 * <p>
 * <b>Security.</b> The browser's debugging port listens on the loopback address only, for the life of the view; another
 * program on the same machine that finds the port can drive the browser. A login portal that must withstand that should
 * use {@code SystemBrowserLogin}.
 */
public final class ChromiumBackend implements WebViewBackend, CdpConnection.Handler {
    private static final Logger log = LogManager.getLogger("WebView");

    private static final long SHOWN_FOR_NANOS = 400_000_000L;
    private static final long CURSOR_POLL_NANOS = 90_000_000L;
    private static final long SETTLE_MILLIS = 250;

    /** Whether a browser to drive is installed. */
    public static boolean isAvailable() {
        return ChromiumLocator.isInstalled();
    }

    private final WebViewOptions options;
    private volatile Listener listener;
    private final ScheduledThreadPoolExecutor executor;
    private final Profiles.Profile profile;
    private final CdpInput input = new CdpInput(this::page);
    private final List<Runnable> afterReady = new ArrayList<>();
    private volatile boolean closed;

    // thread-confined (the executor's one thread)
    private Process process;
    private Thread killer;
    private CdpConnection cdp;
    private String session, targetId, mainFrameId;
    private boolean ready;
    private int width = 1024, height = 640;
    private String lastUrl = "", lastTitle = "";
    private boolean historyBack, historyForward;
    private String pendingHtml;
    private boolean htmlPending;
    private final Map<Integer, String> contextOrigins = new HashMap<>();

    // pictures
    private volatile long lastSeen;
    private volatile boolean casting;
    private int lastLength = -1, lastHash;
    private ScheduledFuture<?> settle;
    private boolean capturing;

    // pointer
    private volatile double pointerX, pointerY;
    private volatile boolean pointerOver;
    private long nextCursorPoll;
    private boolean cursorPolling;
    private boolean cursorKnown;
    private CursorMode lastCursor;
    private boolean lastEditing;

    public ChromiumBackend(WebViewOptions options, Listener listener) {
        this.options = options;
        this.listener = listener == null ? new Listener() {
        } : listener;
        if (ChromiumLocator.find(options) == null) throw new IllegalStateException(ChromiumLocator.describe(options));
        profile = Profiles.open(options);
        executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "RenGL-WebView");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.execute(this::launch);
        executor.scheduleWithFixedDelay(this::tick, 50, 40, TimeUnit.MILLISECONDS);
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener == null ? new Listener() {
        } : listener;
    }

    // ------------------------------------------------------------------------------------------------ plumbing

    private void post(Runnable command) {
        if (closed) return;
        try {
            executor.execute(() -> {
                try {
                    command.run();
                } catch (RuntimeException e) {
                    log.warn("A web view command failed: {}", e.toString());
                }
            });
        } catch (RuntimeException rejected) {
            // the executor is shutting down
        }
    }

    private void whenReady(Runnable command) {
        if (ready) command.run();
        else afterReady.add(command);
    }

    private void submit(Runnable command) {
        post(() -> whenReady(command));
    }

    /** Runs a DevTools method on the page and forgets the answer. */
    private void page(String method, String params) {
        CdpConnection c = cdp;
        if (c != null && session != null) c.send(session, method, params, null);
    }

    private void page(String method, String params, Consumer<Map<String, Object>> reply) {
        CdpConnection c = cdp;
        if (c != null && session != null) c.send(session, method, params, reply);
        else reply.accept(null);
    }

    private void browser(String method, String params, Consumer<Map<String, Object>> reply) {
        CdpConnection c = cdp;
        if (c != null) c.send(null, method, params, reply);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> reply) {
        return reply != null && reply.get("result") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private void fail(String reason) {
        log.warn("Web view: {}", reason);
        listener.onFailed(reason);
    }

    // ------------------------------------------------------------------------------------------------ start up

    private void launch() {
        try {
            Path exe = ChromiumLocator.find(options);
            if (exe == null) throw new IllegalStateException(ChromiumLocator.describe(options));
            Path folder = profile.folder().toAbsolutePath();
            Files.deleteIfExists(folder.resolve("DevToolsActivePort"));
            List<String> command = new ArrayList<>(List.of(exe.toString(),
                    "--headless=new", "--remote-debugging-port=0", "--user-data-dir=" + folder,
                    "--no-first-run", "--no-default-browser-check", "--disable-extensions", "--disable-sync",
                    "--disable-background-networking", "--disable-component-update", "--disable-breakpad",
                    "--disable-features=Translate,MediaRouter,OptimizationHints,InterestFeedContentSuggestions",
                    "--password-store=basic", "--use-mock-keychain", "--mute-audio", "--deny-permission-prompts",
                    "--force-device-scale-factor=1", "--window-size=" + width + "," + height, "about:blank"));
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux") && "root".equals(System.getProperty("user.name"))) {
                // Chrome will not start as root with its sandbox (a container, usually); the page is still the browser's own
                log.warn("Web view: running as root, so the browser's sandbox is turned off");
                command.add(command.size() - 1, "--no-sandbox");
            }
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            Process started = process;
            String marker = folder.toString();
            killer = new Thread(() -> kill(started, marker), "RenGL-WebView-exit");
            try {
                Runtime.getRuntime().addShutdownHook(killer);
            } catch (IllegalStateException shuttingDown) {
                kill(started, marker);
                return;
            }
            URI endpoint = waitForEndpoint(folder.resolve("DevToolsActivePort"), started);
            cdp = new CdpConnection(this, executor);
            cdp.connect(endpoint);
            attach();
        } catch (Throwable t) {
            if (!closed) fail(t.getMessage() == null ? t.toString() : t.getMessage());
        }
    }

    private static URI waitForEndpoint(Path file, Process started) throws IOException, InterruptedException {
        long end = System.nanoTime() + 25_000_000_000L;
        while (System.nanoTime() < end) {
            // some launchers (Edge on Windows) hand over to another process and exit with 0; only a failure code is an error
            if (!started.isAlive() && started.exitValue() != 0) {
                throw new IllegalStateException("The browser exited at start (code " + started.exitValue() + ")");
            }
            if (Files.isRegularFile(file)) {
                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    if (lines.size() >= 2 && !lines.get(0).isBlank() && !lines.get(1).isBlank()) {
                        return URI.create("ws://127.0.0.1:" + lines.get(0).trim() + lines.get(1).trim());
                    }
                } catch (IOException stillBeingWritten) {
                    // Windows refuses to read a file the browser has open for writing; the next try will get it
                }
            }
            Thread.sleep(40);
        }
        throw new IllegalStateException("The browser did not open its debugging port");
    }

    private void attach() {
        browser("Target.getTargets", null, reply -> {
            String id = null;
            Map<String, Object> r = result(reply);
            if (r != null && r.get("targetInfos") instanceof List<?> infos) {
                for (Object o : infos) {
                    Map<String, Object> info = map(o);
                    if ("page".equals(info.get("type"))) {
                        id = (String) info.get("targetId");
                        break;
                    }
                }
            }
            if (id == null) {
                browser("Target.createTarget", "{\"url\":\"about:blank\"}", created -> {
                    Map<String, Object> cr = result(created);
                    if (cr == null || !(cr.get("targetId") instanceof String t)) fail("The browser has no page to show");
                    else attachTo(t);
                });
            } else {
                attachTo(id);
            }
        });
    }

    private void attachTo(String id) {
        targetId = id;
        browser("Target.attachToTarget", "{\"targetId\":\"" + id + "\",\"flatten\":true}", reply -> {
            Map<String, Object> r = result(reply);
            if (r == null || !(r.get("sessionId") instanceof String s)) {
                fail("Could not attach to the browser's page");
                return;
            }
            session = s;
            configure();
        });
    }

    private static final String SHIM = """
            (function() {
              if (window.__renglShim) return;
              window.__renglShim = 1;
              var open = function(u) {
                try { window.renglOpen(String(u === undefined ? '' : new URL(u, location.href).href)); } catch (e) {}
                return null;
              };
              try { window.open = open; } catch (e) {}
              addEventListener('click', function(e) {
                var a = e.target && e.target.closest && e.target.closest('a[target]');
                if (a && a.href && !/^_(self|top|parent)$/i.test(a.target)) { e.preventDefault(); window.renglOpen(a.href); }
              }, true);
              %s
            })();""";

    private static final String BRIDGE = """
              var listeners = [];
              window.chrome = window.chrome || {};
              window.chrome.webview = {
                postMessage: function(m) { window.renglBridge(JSON.stringify(m)); },
                addEventListener: function(t, f) { if (t === 'message') listeners.push(f); },
                removeEventListener: function(t, f) { listeners = listeners.filter(function(x) { return x !== f; }); }
              };
              window.__renglDeliver = function(json) {
                var e = { data: JSON.parse(json) };
                listeners.slice().forEach(function(f) { f(e); });
              };""";

    private void configure() {
        page("Page.enable", null);
        page("Runtime.enable", null);
        page("Page.getFrameTree", null, reply -> {
            Map<String, Object> r = result(reply);
            if (r != null) {
                Object frame = map(map(r.get("frameTree"))).get("frame");
                if (map(frame).get("id") instanceof String id) mainFrameId = id;
            }
        });
        page("Fetch.enable", "{\"patterns\":[{\"urlPattern\":\"*\",\"resourceType\":\"Document\",\"requestStage\":\"Request\"},"
                + "{\"urlPattern\":\"*\",\"resourceType\":\"Document\",\"requestStage\":\"Response\"}]}");
        applyMetrics();
        page("Emulation.setFocusEmulationEnabled", "{\"enabled\":false}");
        page("Runtime.addBinding", "{\"name\":\"renglOpen\"}");
        if (options.isScriptBridge()) page("Runtime.addBinding", "{\"name\":\"renglBridge\"}");
        page("Page.addScriptToEvaluateOnNewDocument", "{\"source\":"
                + CdpInput.quote(String.format(SHIM, options.isScriptBridge() ? BRIDGE : "")) + "}");
        browser("Browser.setDownloadBehavior", "{\"behavior\":\"deny\"}", reply -> {
        });
        List<String> granted = new ArrayList<>();
        for (WebViewOptions.Permission p : options.getPermissions()) {
            switch (p) {
                case MICROPHONE -> granted.add("\"audioCapture\"");
                case CAMERA -> granted.add("\"videoCapture\"");
                case GEOLOCATION -> granted.add("\"geolocation\"");
                case NOTIFICATIONS -> granted.add("\"notifications\"");
                case SENSORS -> granted.add("\"sensors\"");
                case CLIPBOARD_READ -> granted.add("\"clipboardReadWrite\",\"clipboardSanitizedWrite\"");
            }
        }
        if (!granted.isEmpty()) browser("Browser.grantPermissions", "{\"permissions\":[" + String.join(",", granted) + "]}", reply -> {
        });
        browser("Target.setDiscoverTargets", "{\"discover\":true}", reply -> {
        });
        startCast();
        // the last command's answer means all before it were done
        page("Runtime.evaluate", "{\"expression\":\"1\"}", reply -> {
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
            requestPicture(150);
        });
    }

    private void applyMetrics() {
        page("Emulation.setDeviceMetricsOverride", String.format(Locale.ROOT,
                "{\"width\":%d,\"height\":%d,\"deviceScaleFactor\":1,\"mobile\":false}", width, height));
    }

    // ------------------------------------------------------------------------------------------------ events

    @Override
    public void event(String method, Map<String, Object> params, String sessionId) {
        if (closed) return;
        try {
            switch (method) {
                case "Page.screencastFrame" -> onScreencastFrame(params);
                case "Fetch.requestPaused" -> onRequestPaused(params);
                case "Page.frameNavigated" -> onFrameNavigated(params);
                case "Page.navigatedWithinDocument" -> {
                    if (mainFrameId == null || mainFrameId.equals(params.get("frameId"))) urlChanged((String) params.get("url"));
                    refreshHistory();
                }
                case "Page.frameStoppedLoading" -> {
                    if (mainFrameId == null || mainFrameId.equals(params.get("frameId"))) onLoaded();
                }
                case "Page.frameRequestedNavigation" -> onRequestedNavigation(params);
                case "Page.javascriptDialogOpening" ->
                        page("Page.handleJavaScriptDialog", "{\"accept\":" + "beforeunload".equals(params.get("type")) + "}");
                case "Runtime.executionContextCreated" -> {
                    Map<String, Object> ctx = map(params.get("context"));
                    if (ctx.get("id") instanceof Number id && ctx.get("origin") instanceof String origin) {
                        contextOrigins.put(id.intValue(), origin);
                    }
                }
                case "Runtime.executionContextDestroyed" -> {
                    if (params.get("executionContextId") instanceof Number id) contextOrigins.remove(id.intValue());
                }
                case "Runtime.executionContextsCleared" -> contextOrigins.clear();
                case "Runtime.bindingCalled" -> onBinding(params);
                case "Target.targetInfoChanged" -> {
                    Map<String, Object> info = map(params.get("targetInfo"));
                    if (targetId != null && targetId.equals(info.get("targetId")) && info.get("title") instanceof String title && !title.equals(lastTitle)) {
                        lastTitle = title;
                        listener.onTitleChanged(title);
                    }
                }
                case "Target.targetCreated" -> onTargetCreated(map(params.get("targetInfo")));
                case "Inspector.targetCrashed", "Target.targetCrashed" -> fail("The browser page crashed");
                default -> {
                }
            }
        } catch (RuntimeException e) {
            log.warn("A browser event ({}) failed: {}", method, e.toString());
        }
    }

    @Override
    public void closed(String reason) {
        if (!closed) fail(reason);
    }

    private void onFrameNavigated(Map<String, Object> params) {
        Map<String, Object> frame = map(params.get("frame"));
        if (frame.get("parentId") != null) return; // a frame inside the page
        if (frame.get("id") instanceof String id) mainFrameId = id;
        String url = frame.get("url") instanceof String u ? u : "";
        if (url.startsWith("chrome-error:")) return; // the browser's own error page: the address that failed is what the app knows
        if (frame.get("urlFragment") instanceof String f) url += f;
        urlChanged(url);
        refreshHistory();
    }

    private void urlChanged(String url) {
        if (url == null || url.equals(lastUrl)) return;
        lastUrl = url;
        listener.onUrlChanged(url);
        requestPicture(120);
    }

    private void onLoaded() {
        if (htmlPending) return; // the page of the app's own HTML is still being written
        refreshHistory();
        refreshTitle();
        listener.onNavigationCompleted(lastUrl, true, 0);
        requestPicture(100);
    }

    /** The target event reports a title change late or not at all for a page written in place, so it is read after a load too. */
    private void refreshTitle() {
        executeNow("document.title", json -> {
            if (json == null) return;
            try {
                if (me.piitex.engine.io.Json.parse(json) instanceof String title && !title.equals(lastTitle)) {
                    lastTitle = title;
                    listener.onTitleChanged(title);
                }
            } catch (IllegalArgumentException ignored) {
                // not a string: no title
            }
        });
    }

    private void refreshHistory() {
        page("Page.getNavigationHistory", null, reply -> {
            Map<String, Object> r = result(reply);
            if (r == null || !(r.get("entries") instanceof List<?> entries) || !(r.get("currentIndex") instanceof Number index)) return;
            boolean back = index.intValue() > 0, forward = index.intValue() < entries.size() - 1;
            if (back != historyBack || forward != historyForward) {
                historyBack = back;
                historyForward = forward;
                listener.onHistoryChanged(back, forward);
            }
        });
    }

    private void onRequestedNavigation(Map<String, Object> params) {
        if (mainFrameId != null && !mainFrameId.equals(params.get("frameId"))) return;
        if (!(params.get("url") instanceof String url)) return;
        String lower = url.toLowerCase(Locale.ROOT);
        for (String ordinary : new String[]{"http:", "https:", "about:", "data:", "blob:", "javascript:", "chrome-error:"}) {
            if (lower.startsWith(ordinary)) return; // the network layer sees those
        }
        // an address that is not a web address (an app's own scheme, or a file): the browser cannot load it, but the app may be waiting for it
        NavigationRequest request = new NavigationRequest(url, "link".equals(params.get("reason")) || "formSubmissionGet".equals(params.get("reason")), false);
        listener.onNavigationStarting(request);
        if (!request.cancelled() && !options.allows(url)) {
            request.cancel();
            listener.onNavigationBlocked(url);
        }
        if (request.cancelled()) page("Page.stopLoading", null);
        else listener.onNavigationStarted(url);
    }

    private void onRequestPaused(Map<String, Object> params) {
        String requestId = (String) params.get("requestId");
        if (requestId == null) return;
        boolean response = params.get("responseStatusCode") != null || params.get("responseErrorReason") != null;
        try {
            Map<String, Object> request = map(params.get("request"));
            String url = String.valueOf(request.get("url"));
            if (request.get("urlFragment") instanceof String f) url += f;
            boolean main = mainFrameId == null || mainFrameId.equals(params.get("frameId"));
            if (!main || !"Document".equals(params.get("resourceType"))) {
                proceed(requestId, response);
                return;
            }
            if (!response) {
                decide(requestId, false, url, params.get("redirectedRequestId") != null);
                return;
            }
            // the answer is a redirect to an address the network layer will not see?
            int status = params.get("responseStatusCode") instanceof Number n ? n.intValue() : 0;
            String location = null;
            if (status >= 300 && status < 400 && params.get("responseHeaders") instanceof List<?> headers) {
                for (Object h : headers) {
                    Map<String, Object> header = map(h);
                    if ("location".equalsIgnoreCase(String.valueOf(header.get("name")))) location = String.valueOf(header.get("value"));
                }
            }
            if (location != null) {
                String target = resolve(url, location);
                String lower = target.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("http:") && !lower.startsWith("https:")) {
                    decide(requestId, true, target, true);
                    return;
                }
            }
            proceed(requestId, true);
        } catch (RuntimeException e) {
            proceed(requestId, response); // never leave the page waiting
            throw e;
        }
    }

    private static String resolve(String base, String location) {
        try {
            return URI.create(base).resolve(location).toString();
        } catch (IllegalArgumentException e) {
            return location;
        }
    }

    /** The application's checks first (an address it has claimed is not a host to refuse), then the host list. */
    private void decide(String requestId, boolean response, String url, boolean redirected) {
        NavigationRequest request = new NavigationRequest(url, false, redirected);
        listener.onNavigationStarting(request);
        if (!request.cancelled() && !options.allows(url)) {
            request.cancel();
            listener.onNavigationBlocked(url);
        }
        if (request.cancelled()) {
            // "204 No Content" is how a browser is told to stay where it is; failing the request would swap the page for an error page
            page("Fetch.fulfillRequest", "{\"requestId\":" + CdpInput.quote(requestId) + ",\"responseCode\":204,\"responseHeaders\":[]}");
        } else {
            listener.onNavigationStarted(url);
            proceed(requestId, response);
        }
    }

    private void proceed(String requestId, boolean response) {
        String id = CdpInput.quote(requestId);
        if (!response) {
            page("Fetch.continueRequest", "{\"requestId\":" + id + "}");
            return;
        }
        page("Fetch.continueResponse", "{\"requestId\":" + id + "}", reply -> {
            if (reply != null && reply.get("error") != null) page("Fetch.continueRequest", "{\"requestId\":" + id + "}");
        });
    }

    private void onBinding(Map<String, Object> params) {
        String name = String.valueOf(params.get("name"));
        String payload = params.get("payload") instanceof String p ? p : "";
        if ("renglOpen".equals(name)) {
            listener.onNewWindow(payload, true);
            if (options.getNewWindows() == WebViewOptions.NewWindows.SAME_VIEW && !payload.isBlank() && options.allows(payload)) navigateNow(payload);
        } else if ("renglBridge".equals(name) && options.isScriptBridge()) {
            String origin = params.get("executionContextId") instanceof Number id ? contextOrigins.get(id.intValue()) : null;
            if (origin == null || "null".equals(origin) || origin.isEmpty()) origin = "about:blank";
            if (options.allows(origin)) listener.onMessage(payload);
        }
    }

    private void onTargetCreated(Map<String, Object> info) {
        // a window the page managed to open despite the script that turns window.open into a message: close it
        if (!"page".equals(info.get("type")) || info.get("openerId") == null) return;
        String id = (String) info.get("targetId");
        if (id == null || id.equals(targetId)) return;
        browser("Target.closeTarget", "{\"targetId\":\"" + id + "\"}", reply -> {
        });
        String url = info.get("url") instanceof String u ? u : "";
        listener.onNewWindow(url, false);
        if (options.getNewWindows() == WebViewOptions.NewWindows.SAME_VIEW && !url.isBlank() && !url.equals("about:blank") && options.allows(url)) {
            navigateNow(url);
        }
    }

    // ------------------------------------------------------------------------------------------------ navigation

    private void navigateNow(String url) {
        page("Page.navigate", "{\"url\":" + CdpInput.quote(url) + "}", reply -> {
            Map<String, Object> r = result(reply);
            String error = r == null ? null : (String) r.get("errorText");
            if (error != null && !error.contains("ERR_BLOCKED_BY_CLIENT") && !error.contains("ERR_ABORTED")) {
                listener.onNavigationCompleted(url, false, 1);
            }
        });
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
            // a navigation to a blank page first, so the page has no earlier site's origin
            pendingHtml = html;
            htmlPending = true;
            page("Page.navigate", "{\"url\":\"about:blank\"}", reply -> {
                String h = pendingHtml;
                pendingHtml = null;
                if (h == null) return;
                page("Runtime.evaluate", "{\"expression\":" + CdpInput.quote("document.open();document.write(" + CdpInput.quote(h)
                        + ");document.close();") + "}", written -> {
                    htmlPending = false;
                    refreshTitle();
                    listener.onNavigationCompleted("about:blank", true, 0);
                    requestPicture(100);
                });
            });
        });
    }

    @Override
    public void reload() {
        submit(() -> page("Page.reload", "{}"));
    }

    @Override
    public void stop() {
        submit(() -> page("Page.stopLoading", null));
    }

    @Override
    public void goBack() {
        submit(() -> history(-1));
    }

    @Override
    public void goForward() {
        submit(() -> history(1));
    }

    private void history(int step) {
        page("Page.getNavigationHistory", null, reply -> {
            Map<String, Object> r = result(reply);
            if (r == null || !(r.get("entries") instanceof List<?> entries) || !(r.get("currentIndex") instanceof Number index)) return;
            int to = index.intValue() + step;
            if (to < 0 || to >= entries.size()) return;
            if (map(entries.get(to)).get("id") instanceof Number id) page("Page.navigateToHistoryEntry", "{\"entryId\":" + id.intValue() + "}");
        });
    }

    // ------------------------------------------------------------------------------------------------ display

    @Override
    public void resize(int w, int h) {
        int nw = Math.max(1, w), nh = Math.max(1, h);
        post(() -> {
            if (nw == width && nh == height) return;
            width = nw;
            height = nh;
            if (!ready) return;
            applyMetrics();
            if (casting) {
                page("Page.stopScreencast", null);
                startCast();
            }
            requestPicture(120);
        });
    }

    @Override
    public void keepAlive() {
        long now = System.nanoTime();
        lastSeen = now;
        if (!casting && ready) post(this::tick);
    }

    private boolean shown(long now) {
        return now - lastSeen < SHOWN_FOR_NANOS;
    }

    // ------------------------------------------------------------------------------------------------ pictures

    private boolean jpegFormat() {
        return options.getQuality() != WebViewOptions.Quality.CRISP;
    }

    private void startCast() {
        lastLength = -1;
        casting = true;
        page("Page.startScreencast", String.format(Locale.ROOT,
                "{\"format\":\"%s\",\"quality\":70,\"maxWidth\":%d,\"maxHeight\":%d,\"everyNthFrame\":1}",
                jpegFormat() ? "jpeg" : "png", width, height));
    }

    private void onScreencastFrame(Map<String, Object> params) {
        if (params.get("sessionId") instanceof Number ack) page("Page.screencastFrameAck", "{\"sessionId\":" + ack.intValue() + "}");
        if (!(params.get("data") instanceof String data)) return;
        byte[] bytes = Base64.getDecoder().decode(data);
        int hash = Arrays.hashCode(bytes);
        if (bytes.length == lastLength && hash == lastHash) return; // the same picture again: a lossless one of it may be showing
        lastLength = bytes.length;
        lastHash = hash;
        listener.onFrame(new Frame(width, height, bytes, true, !jpegFormat()));
        scheduleSettle();
    }

    /** Once the page has stopped changing, one lossless picture of it. */
    private void scheduleSettle() {
        if (options.getQuality() != WebViewOptions.Quality.AUTO) return;
        if (settle != null) settle.cancel(false);
        settle = executor.schedule(() -> {
            try {
                capture(true);
            } catch (RuntimeException e) {
                log.debug("Settle picture: {}", e.toString());
            }
        }, SETTLE_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** A picture soon: after a load or a size change the screencast may have nothing new to say. */
    private void requestPicture(long delayMillis) {
        if (closed) return;
        if (settle != null) settle.cancel(false);
        settle = executor.schedule(() -> {
            try {
                capture(options.getQuality() != WebViewOptions.Quality.SMOOTH);
            } catch (RuntimeException e) {
                log.debug("Picture: {}", e.toString());
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void capture(boolean png) {
        if (closed || capturing || !ready || !shown(System.nanoTime())) return;
        capturing = true;
        page("Page.captureScreenshot", png ? "{\"format\":\"png\"}" : "{\"format\":\"jpeg\",\"quality\":85}", reply -> {
            capturing = false;
            Map<String, Object> r = result(reply);
            if (r == null || !(r.get("data") instanceof String data)) return;
            byte[] bytes = Base64.getDecoder().decode(data);
            listener.onFrame(new Frame(width, height, bytes, true, png));
        });
    }

    private void tick() {
        if (closed || !ready) return;
        long now = System.nanoTime();
        boolean visible = shown(now);
        if (!visible && casting) {
            casting = false;
            page("Page.stopScreencast", null);
            if (settle != null) settle.cancel(false);
        } else if (visible && !casting) {
            startCast();
            requestPicture(80);
        }
        if (visible && pointerOver && !cursorPolling && now >= nextCursorPoll) pollCursor(now);
    }

    private void pollCursor(long now) {
        nextCursorPoll = now + CURSOR_POLL_NANOS;
        cursorPolling = true;
        executeNow(CdpInput.probeScript(pointerX, pointerY), result -> {
            cursorPolling = false;
            CdpInput.Probe probe = CdpInput.parseProbe(result);
            if (probe == null) return;
            if (!cursorKnown || probe.cursor() != lastCursor) {
                cursorKnown = true;
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

    @Override
    public void pointerMove(double x, double y, int buttons, int mods) {
        pointerX = x;
        pointerY = y;
        pointerOver = true;
        submit(() -> input.pointerMove(x, y, buttons, mods));
    }

    @Override
    public void pointerButton(double x, double y, int button, boolean pressed, int mods) {
        pointerX = x;
        pointerY = y;
        submit(() -> input.pointerButton(x, y, button, pressed, mods));
    }

    @Override
    public void pointerScroll(double x, double y, double dx, double dy, int mods) {
        submit(() -> input.pointerScroll(x, y, dx, dy, mods));
    }

    @Override
    public void pointerLeave() {
        pointerOver = false;
        submit(input::pointerLeave);
    }

    @Override
    public void key(int glfwKey, int action, int mods) {
        submit(() -> input.key(glfwKey, action, mods));
    }

    @Override
    public void text(int codePoint) {
        submit(() -> input.text(codePoint));
    }

    @Override
    public void composition(String text, int caret) {
        submit(() -> input.composition(text, caret));
    }

    @Override
    public void setFocused(boolean focused) {
        submit(() -> page("Emulation.setFocusEmulationEnabled", "{\"enabled\":" + focused + "}"));
    }

    // ------------------------------------------------------------------------------------------------ script

    private void executeNow(String script, Consumer<String> result) {
        page("Runtime.evaluate", "{\"expression\":" + CdpInput.quote(script) + ",\"returnByValue\":true,\"awaitPromise\":true}", reply -> {
            Map<String, Object> r = result(reply);
            if (r == null || r.get("exceptionDetails") != null) {
                result.accept(null);
                return;
            }
            Object value = map(r.get("result")).get("value");
            String json;
            try {
                json = value == null ? "null" : JsonWriter.writeCompact(value);
            } catch (IllegalArgumentException e) {
                json = "null";
            }
            result.accept(json);
        });
    }

    @Override
    public void execute(String script, Consumer<String> result) {
        submit(() -> executeNow(script, result));
    }

    @Override
    public void postMessage(String json) {
        if (!options.isScriptBridge()) return;
        submit(() -> page("Runtime.evaluate", "{\"expression\":" + CdpInput.quote(
                "window.__renglDeliver && window.__renglDeliver(" + CdpInput.quote(json) + ")") + "}"));
    }

    // ------------------------------------------------------------------------------------------------ closing

    @Override
    public void close() {
        if (closed) return;
        try {
            executor.execute(this::teardown);
        } catch (RuntimeException rejected) {
            return;
        }
        closed = true; // no new commands
        executor.shutdown();
        if (!isOnExecutor()) {
            try {
                executor.awaitTermination(4, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean isOnExecutor() {
        return Thread.currentThread().getName().equals("RenGL-WebView") && Thread.currentThread().isDaemon();
    }

    private void teardown() {
        if (settle != null) settle.cancel(false);
        CdpConnection c = cdp;
        Process p = process;
        String marker = profile.folder().toAbsolutePath().toString();
        try {
            if (c != null) {
                c.send(null, "Browser.close", null, null);
                c.close();
            }
        } catch (RuntimeException e) {
            log.debug("Closing the browser: {}", e.toString());
        }
        if (p != null) {
            // the browser may be a process the launcher started and left, so it is found by its profile folder
            long end = System.nanoTime() + 2_500_000_000L;
            try {
                while (System.nanoTime() < end && (p.isAlive() || !browserProcesses(marker).isEmpty())) Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            kill(p, marker);
        }
        Thread hook = killer;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // the program is already ending
            }
        }
        profile.release();
    }

    /** The processes running with this profile folder: the browser, which may not be the one that was launched. */
    private static List<ProcessHandle> browserProcesses(String marker) {
        List<ProcessHandle> found = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(h -> {
            if (h.info().commandLine().map(line -> line.contains(marker)).orElse(false)) found.add(h);
        });
        return found;
    }

    /** Ends the browser and the processes it started. */
    private static void kill(Process p, String marker) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        browserProcesses(marker).forEach(ProcessHandle::destroyForcibly);
    }
}
