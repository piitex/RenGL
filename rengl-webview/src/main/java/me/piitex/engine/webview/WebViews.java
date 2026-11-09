package me.piitex.engine.webview;

import me.piitex.engine.webview.chromium.ChromiumBackend;
import me.piitex.engine.webview.chromium.ChromiumLocator;
import me.piitex.engine.webview.linux.WebKitGtkBackend;
import me.piitex.engine.webview.macos.WKWebViewBackend;
import me.piitex.engine.webview.windows.WebView2Backend;
import me.piitex.engine.webview.windows.WebView2Runtime;

import java.util.Locale;

/**
 * Whether this machine can show web pages, and the way to start a browser. Most programs use
 * {@link me.piitex.engine.ui.overlays.WebViewOverlay}, which calls this.
 * <p>
 * Four engines are built in. <b>WebView2</b> is the Chromium that Microsoft Edge is made of, which comes with Windows 11
 * and current Windows 10, and it can draw as a window of its own as well as into pictures. <b>Chromium</b> is any Chrome,
 * Edge, Chromium or Brave browser the machine has, run headless; it works on Windows, macOS and Linux, and draws into
 * pictures only. <b>WKWebView</b> (macOS) and <b>WebKitGTK</b> (Linux) are the systems' own WebKit, needing no browser
 * installed; they draw into pictures only and have not been run on a real machine yet, so
 * {@link WebViewOptions.Engine#AUTO} never picks them: it uses WebView2 where it can and an installed browser otherwise.
 */
public final class WebViews {
    private WebViews() {
    }

    /**
     * The engine that would show a page with these options, or null when none can.
     */
    public static WebViewOptions.Engine resolve(WebViewOptions options) {
        WebViewOptions.Engine wanted = options == null ? WebViewOptions.Engine.AUTO : options.getEngine();
        return switch (wanted) {
            case WEBVIEW2 -> webView2() ? WebViewOptions.Engine.WEBVIEW2 : null;
            case CHROMIUM -> chromium(options) ? WebViewOptions.Engine.CHROMIUM : null;
            case WKWEBVIEW -> isMac() && WKWebViewBackend.isAvailable() ? WebViewOptions.Engine.WKWEBVIEW : null;
            case WEBKITGTK -> isLinux() && WebKitGtkBackend.isAvailable() ? WebViewOptions.Engine.WEBKITGTK : null;
            case AUTO -> webView2() ? WebViewOptions.Engine.WEBVIEW2 : chromium(options) ? WebViewOptions.Engine.CHROMIUM : null;
        };
    }

    private static boolean webView2() {
        return isWindows() && WebView2Backend.isAvailable();
    }

    private static boolean chromium(WebViewOptions options) {
        return ChromiumLocator.find(options) != null;
    }

    /**
     * Whether a web view can be created here with the default engine choice.
     */
    public static boolean isAvailable() {
        return isAvailable(null);
    }

    /**
     * Whether a web view can be created here with these options.
     */
    public static boolean isAvailable(WebViewOptions options) {
        return resolve(options) != null;
    }

    /**
     * Whether the engine these options would use can draw {@link WebViewOptions.Mode#NATIVE}, a window of its own.
     * A view asking for it on an engine that cannot is drawn as pictures instead.
     */
    public static boolean supportsNative(WebViewOptions options) {
        return resolve(options) == WebViewOptions.Engine.WEBVIEW2;
    }

    /**
     * Why web views are not available, or a short description of the engine they use when they are. For a message to
     * show the user or write to a log.
     */
    public static String describe() {
        return describe(null);
    }

    public static String describe(WebViewOptions options) {
        WebViewOptions.Engine engine = resolve(options);
        if (engine == WebViewOptions.Engine.WEBVIEW2) return "WebView2 " + WebView2Runtime.version();
        if (engine == WebViewOptions.Engine.CHROMIUM) return "Chromium: " + ChromiumLocator.describe(options);
        if (engine == WebViewOptions.Engine.WKWEBVIEW) return "WKWebView (macOS)";
        if (engine == WebViewOptions.Engine.WEBKITGTK) return "WebKitGTK " + WebKitGtkBackend.version();
        WebViewOptions.Engine wanted = options == null ? WebViewOptions.Engine.AUTO : options.getEngine();
        if (wanted == WebViewOptions.Engine.WKWEBVIEW) {
            return isMac() ? String.valueOf(WKWebViewBackend.unavailableReason()) : "WKWebView is only available on macOS.";
        }
        if (wanted == WebViewOptions.Engine.WEBKITGTK) {
            return isLinux()
                    ? String.valueOf(WebKitGtkBackend.unavailableReason()) + ". The package is libwebkit2gtk-4.1-0 on Debian and Ubuntu, webkit2gtk4.1 on Fedora."
                    : "WebKitGTK is only available on Linux.";
        }
        if (wanted == WebViewOptions.Engine.WEBVIEW2) {
            return isWindows()
                    ? "The Microsoft Edge WebView2 runtime is not installed. It can be downloaded from https://developer.microsoft.com/microsoft-edge/webview2/"
                    : "WebView2 is only available on Windows.";
        }
        if (wanted == WebViewOptions.Engine.CHROMIUM || !isWindows()) return ChromiumLocator.describe(options);
        return "Neither the Microsoft Edge WebView2 runtime nor a Chrome, Edge, Chromium or Brave browser was found. The runtime can be "
                + "downloaded from https://developer.microsoft.com/microsoft-edge/webview2/";
    }

    /**
     * Starts a browser. It runs on its own thread and reports to {@code listener}.
     *
     * @throws IllegalStateException if {@link #isAvailable(WebViewOptions)} is false
     */
    public static WebViewBackend create(WebViewOptions options, WebViewBackend.Listener listener) {
        WebViewOptions.Engine engine = resolve(options);
        if (engine == null) throw new IllegalStateException(describe(options));
        return switch (engine) {
            case WEBVIEW2 -> new WebView2Backend(options, listener);
            case WKWEBVIEW -> new WKWebViewBackend(options, listener);
            case WEBKITGTK -> new WebKitGtkBackend(options, listener);
            default -> new ChromiumBackend(options, listener);
        };
    }

    private static boolean isWindows() {
        return osName().contains("windows");
    }

    private static boolean isMac() {
        return osName().contains("mac");
    }

    private static boolean isLinux() {
        return osName().contains("linux");
    }

    private static String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }
}
