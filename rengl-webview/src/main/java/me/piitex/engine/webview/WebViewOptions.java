package me.piitex.engine.webview;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * How a web view starts. The defaults suit a login portal inside a game's own window: nothing is kept after the view
 * is closed, nothing but the page itself is asked of the user, and the page can do no more than show and take input.
 * <pre>{@code
 * WebViewOptions options = new WebViewOptions()
 *         .allowHost("login.example.com")      // go nowhere else
 *         .setQuality(Quality.CRISP);
 * }</pre>
 * A browser, by contrast, wants {@link #setProfile a profile folder} to keep its logins and native
 * drawing for speed.
 */
public final class WebViewOptions {
    /**
     * How the page gets onto the screen.
     */
    public enum Mode {
        /**
         * The browser draws off screen and the engine draws its pictures as an ordinary element: it can sit under other
         * elements, be clipped, faded and put in a scrolling area. Pictures come at roughly 10 to 25 a second, so
         * scrolling and animation are not smooth. The right choice for a login page or a form.
         */
        PIXELS,
        /**
         * The browser draws itself in a child window over the engine's window, at the screen's own speed. Always on top
         * of everything the engine draws, and square: no clipping to a container, no fading, no rounded corners. The
         * right choice for a browser pane. Windows only, and it needs the window's native handle.
         */
        NATIVE
    }

    /**
     * How the pictures of a {@link Mode#PIXELS} view are made.
     */
    public enum Quality {
        /** Lossy pictures while the page moves and a lossless one when it settles. Fast and sharp. */
        AUTO,
        /** Always lossless. Sharp text and transparency at about half the rate. */
        CRISP,
        /** Always lossy. The highest rate, with soft text. */
        SMOOTH
    }

    /**
     * What happens when a page opens a new window ({@code window.open}, a link meant for a new tab).
     */
    public enum NewWindows {
        /** Opens the address in this view. The page loses the link to the window that opened it. */
        SAME_VIEW,
        /** Does nothing, only reports it. */
        BLOCK
    }

    /**
     * What a page may ask permission for. All are refused unless {@link #allow} names them.
     */
    public enum Permission {
        MICROPHONE(1), CAMERA(2), GEOLOCATION(3), NOTIFICATIONS(4), SENSORS(5), CLIPBOARD_READ(6);

        private final int code;

        Permission(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static Permission of(int code) {
            for (Permission p : values()) if (p.code == code) return p;
            return null;
        }
    }

    /**
     * Which browser engine shows the page.
     */
    public enum Engine {
        /**
         * WebView2 on Windows if it is there, else a Chromium-based browser. It never picks {@link #WKWEBVIEW} or
         * {@link #WEBKITGTK}, which have not been run on a real machine yet; ask for them with
         * {@link WebViewOptions#setEngine}.
         */
        AUTO,
        /** Microsoft's WebView2 (Windows only). */
        WEBVIEW2,
        /**
         * A Chrome, Edge, Chromium or Brave browser installed on the machine, driven in headless mode. Works on every
         * system, but the browser has to be installed, and it can only draw {@link Mode#PIXELS}.
         */
        CHROMIUM,
        /**
         * Apple's WKWebView (macOS only), which is part of the system, so no browser has to be installed. Pictures only
         * ({@link Mode#PIXELS}). Written without a Mac to run it on: see the web view documentation for what is untested.
         */
        WKWEBVIEW,
        /**
         * WebKitGTK (Linux only, the GTK 3 builds {@code libwebkit2gtk-4.1} and {@code -4.0}), which most desktop
         * distributions have or offer as a package. Needs a graphical session. Pictures only. Written without a Linux
         * desktop to run it on: see the web view documentation for what is untested.
         */
        WEBKITGTK
    }

    private Engine engine = Engine.AUTO;
    private Path browserPath;
    private Mode mode = Mode.PIXELS;
    private Quality quality = Quality.AUTO;
    private Path profile;
    private NewWindows newWindows = NewWindows.SAME_VIEW;
    private boolean scriptBridge;
    private boolean devTools;
    private boolean contextMenus;
    private long nativeWindow;
    private final Set<String> hosts = new LinkedHashSet<>();
    private final Set<Permission> permissions = new LinkedHashSet<>();

    public Engine getEngine() {
        return engine;
    }

    /**
     * Chooses the browser engine. The default, {@link Engine#AUTO}, is right for most programs.
     */
    public WebViewOptions setEngine(Engine engine) {
        this.engine = engine == null ? Engine.AUTO : engine;
        return this;
    }

    public Path getBrowserPath() {
        return browserPath;
    }

    /**
     * For {@link Engine#CHROMIUM}: the browser's executable, when it is not where the module looks (or to prefer one of
     * several). The {@code RENGL_BROWSER} environment variable does the same for the whole program.
     */
    public WebViewOptions setBrowserPath(Path browserPath) {
        this.browserPath = browserPath;
        return this;
    }

    public Mode getMode() {
        return mode;
    }

    public WebViewOptions setMode(Mode mode) {
        this.mode = mode == null ? Mode.PIXELS : mode;
        return this;
    }

    public Quality getQuality() {
        return quality;
    }

    public WebViewOptions setQuality(Quality quality) {
        this.quality = quality == null ? Quality.AUTO : quality;
        return this;
    }

    /**
     * The folder the browser keeps its cookies, logins and cache in, or null (the default) for a fresh one that is
     * deleted when the view closes. A view with no profile never sees what an earlier one stored, which is what a login
     * portal wants; give it a folder to stay signed in between runs. Views sharing a folder share their logins.
     */
    public Path getProfile() {
        return profile;
    }

    public WebViewOptions setProfile(Path profile) {
        this.profile = profile;
        return this;
    }

    public NewWindows getNewWindows() {
        return newWindows;
    }

    public WebViewOptions setNewWindows(NewWindows newWindows) {
        this.newWindows = newWindows == null ? NewWindows.SAME_VIEW : newWindows;
        return this;
    }

    /**
     * Whether the page can send messages to the application ({@code chrome.webview.postMessage}) and receive them. Off
     * by default, so a page you do not control cannot call into your code.
     */
    public boolean isScriptBridge() {
        return scriptBridge;
    }

    public WebViewOptions setScriptBridge(boolean scriptBridge) {
        this.scriptBridge = scriptBridge;
        return this;
    }

    /** Whether F12 and the inspector are available. Off by default. */
    public boolean isDevTools() {
        return devTools;
    }

    public WebViewOptions setDevTools(boolean devTools) {
        this.devTools = devTools;
        return this;
    }

    /**
     * Whether a right-click opens the browser's own menu (back, copy, inspect). Off by default; in
     * {@link Mode#PIXELS} the menu would open off screen, so it is always off there.
     */
    public boolean isContextMenus() {
        return contextMenus;
    }

    public WebViewOptions setContextMenus(boolean contextMenus) {
        this.contextMenus = contextMenus;
        return this;
    }

    /**
     * The native handle of the window that hosts a {@link Mode#NATIVE} view. The overlay fills it in from its window;
     * it is public for code that creates a backend itself.
     */
    public long getNativeWindow() {
        return nativeWindow;
    }

    public WebViewOptions setNativeWindow(long nativeWindow) {
        this.nativeWindow = nativeWindow;
        return this;
    }

    /**
     * Limits the view to this host and its subdomains: a navigation anywhere else is refused, and reported to the
     * overlay's blocked-navigation listener. Call it once for each host. With none, any address is allowed. Matching is
     * by the host name, so {@code example.com} allows {@code login.example.com} but not {@code evilexample.com}.
     */
    public WebViewOptions allowHost(String host) {
        if (host != null && !host.isBlank()) hosts.add(host.toLowerCase(Locale.ROOT).trim());
        return this;
    }

    public Set<String> getAllowedHosts() {
        return Set.copyOf(hosts);
    }

    /**
     * Whether {@code url} may be shown: always for the page's own blank and in-memory pages, otherwise if no host
     * restriction is set or its host is an allowed one or below one. A web view never allows {@code file:} pages.
     */
    public boolean allows(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.equals("about:blank") || lower.startsWith("data:text/html")) return true;
        if (lower.startsWith("file:")) return false;
        if (hosts.isEmpty()) return true;
        String host = hostOf(lower);
        if (host == null) return false;
        for (String allowed : hosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
        }
        return false;
    }

    /**
     * The host of an address, lower-cased, or null when it has none. Userinfo and the port are dropped.
     */
    public static String hostOf(String url) {
        if (url == null) return null;
        int scheme = url.indexOf("://");
        if (scheme < 0) return null;
        int start = scheme + 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = url.substring(start, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            return close < 0 ? null : authority.substring(0, close + 1).toLowerCase(Locale.ROOT);
        }
        int colon = authority.indexOf(':');
        if (colon >= 0) authority = authority.substring(0, colon);
        return authority.isEmpty() ? null : authority.toLowerCase(Locale.ROOT);
    }

    /**
     * Lets pages ask for these permissions and grants them. Anything not named is refused without asking the user.
     */
    public WebViewOptions allow(Permission... allowed) {
        permissions.addAll(java.util.List.of(allowed));
        return this;
    }

    public Set<Permission> getPermissions() {
        return Set.copyOf(permissions);
    }
}
