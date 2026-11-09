package me.piitex.engine.webview;

import me.piitex.engine.ui.overlays.WebViewOverlay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The overlay on top of a real browser: what it hands to the application, and what it keeps from it. */
abstract class OverlayContractTest {
    /** The engine under test. */
    protected abstract WebViewOptions.Engine engine();

    private WebViewOptions options() {
        return new WebViewOptions().setEngine(engine());
    }

    private WebViewOverlay web;
    private final CopyOnWriteArrayList<String> redirects = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> blocked = new CopyOnWriteArrayList<>();

    @BeforeEach
    void requireRuntime() {
        assumeTrue(WebViews.isAvailable(options()), "no " + engine() + " engine here");
    }

    @AfterEach
    void dispose() {
        if (web != null) web.dispose();
    }

    /** Runs the overlay's frames, as the engine would, until the condition holds. */
    private void frames(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 30_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) {
            web.update(0.016f);
            Thread.sleep(16);
        }
        assertTrue(condition.getAsBoolean(), what + " (state " + web.getState() + ", " + web.getFailure() + ")");
    }

    @Test
    void startsOnTheFirstUpdateAndShowsAPage() throws Exception {
        web = new WebViewOverlay(options(), 400, 300);
        assertEquals(WebViewOverlay.State.NOT_STARTED, web.getState());
        web.loadHtml("<title>Hello</title><body style='background:#0a0'>x");
        frames(() -> web.hasPicture(), "a picture of the page");
        assertEquals(WebViewOverlay.State.READY, web.getState());
        frames(() -> "Hello".equals(web.getTitle()), "its title");
    }

    @Test
    void aRedirectToTheAppsAddressIsHandedOverAndNeverLoaded() throws Exception {
        // the portal may only go to its own host, yet the app's redirect address is not refused as a stranger
        web = new WebViewOverlay(options().allowHost("never-leaves.invalid"), 400, 300);
        web.onRedirect("mygame://signed-in", redirects::add);
        web.loadHtml("<title>portal</title>");
        frames(() -> "portal".equals(web.getTitle()), "the portal page");
        web.evaluate("location.href = 'mygame://signed-in?code=abc%20d&state=s1'", json -> {
        });
        frames(() -> !redirects.isEmpty(), "the redirect");
        assertEquals("mygame://signed-in?code=abc%20d&state=s1", redirects.getFirst());
        LoginFlow.Result result = LoginFlow.parse(redirects.getFirst());
        assertEquals("abc d", result.get("code"));
        assertTrue(result.stateMatches("s1"));
        assertEquals("portal", web.getTitle(), "the page stayed where it was");
    }

    @Test
    void aNavigationFilterAndAHostListBothRefuse() throws Exception {
        web = new WebViewOverlay(options().allowHost("example.com"), 400, 300);
        web.onBlocked(blocked::add);
        web.setNavigationFilter(url -> !url.contains("forbidden"));
        web.load("https://example.org/elsewhere");
        frames(() -> blocked.contains("https://example.org/elsewhere"), "the host list refusing");
        web.loadHtml("<title>ok</title>");
        frames(() -> "ok".equals(web.getTitle()), "its own page still loads");
    }

    @Test
    void pointerAndKeyboardInputGoThroughTheOverlay() throws Exception {
        web = new WebViewOverlay(options(), 400, 300);
        web.loadHtml("<body style='margin:0'><input id=f style='width:300px;height:40px;font-size:20px'>"
                + "<script>window.moved=0;addEventListener('mousemove',()=>moved++)</script>");
        frames(() -> web.hasPicture(), "a picture");
        web.onFocusGained();
        web.onPointerMove(50, 20, 0, 0);
        web.onPointerButton(50, 20, 0, true, 0);
        web.onPointerButton(50, 20, 0, false, 0);
        for (char c : "abc".toCharArray()) web.onCharTyped(c);
        web.onKeyPressed(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_PRESS, 0);
        web.onKeyPressed(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_RELEASE, 0);
        String[] value = {null};
        frames(() -> {
            web.evaluate("document.getElementById('f').value", v -> value[0] = v);
            return "\"ab\"".equals(value[0]);
        }, "the field to hold ab");
    }

    @Test
    void aServersRedirectToTheAppsAddressIsHandedOverAndNeverLoaded() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/portal", exchange -> {
            byte[] page = "<title>portal</title><body>sign in".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        server.createContext("/finish", exchange -> {
            exchange.getResponseHeaders().add("Location", "mygame://signed-in?code=xyz&state=s2");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            web = new WebViewOverlay(options().allowHost("127.0.0.1"), 400, 300);
            web.onRedirect("mygame://signed-in", redirects::add);
            web.load(base + "/portal");
            frames(() -> "portal".equals(web.getTitle()), "the portal page");
            web.load(base + "/finish");
            frames(() -> !redirects.isEmpty(), "the server's redirect to the app's address");
            assertEquals("mygame://signed-in?code=xyz&state=s2", redirects.getFirst());
            assertTrue(LoginFlow.parse(redirects.getFirst()).stateMatches("s2"));
            assertEquals("portal", web.getTitle(), "the page stayed where it was");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disposeIsSafeToRepeat() throws Exception {
        web = new WebViewOverlay(options(), 400, 300);
        web.loadHtml("x");
        frames(() -> web.getState() == WebViewOverlay.State.READY, "ready");
        web.dispose();
        web.dispose();
        web.update(0.016f); // does nothing once disposed
    }
}
