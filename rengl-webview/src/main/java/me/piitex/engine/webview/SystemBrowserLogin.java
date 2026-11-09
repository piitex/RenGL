package me.piitex.engine.webview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sign-in through the user's own browser, which is what providers that refuse embedded browsers (Google, for one) ask
 * for, and often better for the user, who is already signed in there and trusts its address bar.
 * <p>
 * A one-shot web server listens on the loopback address only. You send the browser to the provider with
 * {@link #redirectUri()} as the redirect, the provider sends it back to that address with the result, the page tells the
 * user to return to the application, and {@link #result()} completes:
 * <pre>{@code
 * try (SystemBrowserLogin login = SystemBrowserLogin.start()) {
 *     String state = LoginFlow.randomState(), verifier = LoginFlow.pkceVerifier();
 *     login.open(authorizeUrl(login.redirectUri(), state, LoginFlow.pkceChallenge(verifier)));
 *     LoginFlow.Result r = login.await(5, TimeUnit.MINUTES);
 *     if (r != null && r.stateMatches(state)) exchangeForToken(r.get("code"), verifier, login.redirectUri());
 * }
 * }</pre>
 * The port is chosen by the system. A provider that wants a fixed redirect address registered ahead of time accepts any
 * port on {@code http://127.0.0.1} for "desktop" clients. Only the authorization-code flow works here: the fragment of
 * an implicit-flow redirect never reaches a server.
 */
public final class SystemBrowserLogin implements AutoCloseable {
    private final HttpServer server;
    private final CompletableFuture<LoginFlow.Result> result = new CompletableFuture<>();
    private final String redirectUri;
    private final String path;

    private SystemBrowserLogin(HttpServer server, String path) {
        this.server = server;
        this.path = path;
        this.redirectUri = "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** Starts listening on {@code /callback}. */
    public static SystemBrowserLogin start() throws IOException {
        return start("/callback");
    }

    public static SystemBrowserLogin start(String path) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        SystemBrowserLogin login = new SystemBrowserLogin(server, path);
        server.createContext("/", login::handle);
        server.start();
        return login;
    }

    /** The address to give the provider as the redirect. */
    public String redirectUri() {
        return redirectUri;
    }

    /** Completes with what the provider redirected back with, once. */
    public CompletableFuture<LoginFlow.Result> result() {
        return result;
    }

    /**
     * Waits for the result, or gives up after the time and returns null.
     */
    public LoginFlow.Result await(long timeout, TimeUnit unit) {
        try {
            return result.get(timeout, unit);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Opens a web address in the user's default browser.
     */
    public void open(String url) throws IOException {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) throw new IOException("Only web addresses open in the browser");
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
            java.awt.Desktop.getDesktop().browse(uri);
        } else if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString()).start();
        } else {
            throw new IOException("No way to open a browser here");
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            boolean ours = exchange.getRequestURI().getPath().equals(path) && "GET".equals(exchange.getRequestMethod());
            String body = ours
                    ? "<!doctype html><meta charset=utf-8><title>Signed in</title><body style=\"font:16px sans-serif;margin:3em\">"
                    + "<h2>You can close this window</h2>Return to the application to carry on."
                    : "<!doctype html><meta charset=utf-8><title>Not found</title>Not found";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.sendResponseHeaders(ours ? 200 : 404, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            if (ours) {
                String query = exchange.getRequestURI().getRawQuery();
                result.complete(LoginFlow.parse(redirectUri + (query == null ? "" : "?" + query)));
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        result.cancel(false);
    }
}
