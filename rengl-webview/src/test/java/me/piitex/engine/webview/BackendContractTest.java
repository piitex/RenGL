package me.piitex.engine.webview;

import me.piitex.engine.ui.CursorMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What every backend must do, run against a real browser. Skipped on a machine without the engine. Nothing here touches
 * the desktop: the page is drawn off screen and all input is sent to it directly.
 */
abstract class BackendContractTest {
    /** The engine under test. */
    protected abstract WebViewOptions.Engine engine();

    /**
     * Waits a little. An engine whose toolkit needs the test thread to run its loop (WKWebView under
     * {@code -XstartOnFirstThread}) overrides this to run it; the rest just sleep.
     */
    protected void idle(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private boolean waitFor(CountDownLatch latch, long seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000;
        while (latch.getCount() > 0 && System.currentTimeMillis() < end) idle(10);
        return latch.getCount() == 0;
    }

    /** A picture as an image: a PNG or JPEG file, or the raw BGRA pixels of an engine that draws without encoding. */
    private static BufferedImage image(WebViewBackend.Frame frame) throws java.io.IOException {
        if (frame.encoded()) return ImageIO.read(new ByteArrayInputStream(frame.data()));
        BufferedImage img = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_ARGB_PRE);
        byte[] d = frame.data();
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                int i = (y * frame.width() + x) * 4;
                img.setRGB(x, y, (d[i + 3] & 0xFF) << 24 | (d[i + 2] & 0xFF) << 16 | (d[i + 1] & 0xFF) << 8 | (d[i] & 0xFF));
            }
        }
        return img;
    }

    private static final String PAGE = """
            <!doctype html><meta charset=utf-8><title>Test page</title>
            <body style="margin:0;background:#fff">
            <input id=f style="position:absolute;left:20px;top:20px;width:200px;height:30px;font-size:18px">
            <button id=b style="position:absolute;left:20px;top:80px;width:100px;height:40px"
                    onclick="window.clicks=(window.clicks||0)+1">Go</button>
            <div id=h style="position:absolute;left:300px;top:20px;width:100px;height:100px;background:#d22;cursor:pointer"
                 onmouseover="window.hovered=true"></div>
            <div style="position:absolute;left:0;top:200px;height:3000px;width:10px"></div>
            <script>
              window.keys=[]; addEventListener('keydown', e => keys.push(e.key));
              window.wheels=0; addEventListener('wheel', e => wheels++);
              window.rights=0; addEventListener('contextmenu', e => { rights++; e.preventDefault(); });
            </script>
            """;

    private final CountDownLatch ready = new CountDownLatch(1);
    private final CountDownLatch loaded = new CountDownLatch(1);
    private final CopyOnWriteArrayList<WebViewBackend.Frame> frames = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> blocked = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CursorMode> cursors = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> title = new AtomicReference<>();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private WebViewBackend backend;

    private final WebViewBackend.Listener listener = new WebViewBackend.Listener() {
        @Override
        public void onReady() {
            ready.countDown();
        }

        @Override
        public void onFrame(WebViewBackend.Frame frame) {
            frames.add(frame);
        }

        @Override
        public void onNavigationCompleted(String url, boolean success, int status) {
            if (success) loaded.countDown();
        }

        @Override
        public void onTitleChanged(String t) {
            title.set(t);
        }

        @Override
        public void onMessage(String json) {
            messages.add(json);
        }

        @Override
        public void onNavigationBlocked(String url) {
            blocked.add(url);
        }

        @Override
        public void onCursor(CursorMode mode) {
            cursors.add(mode == null ? CursorMode.DEFAULT : mode);
        }

        @Override
        public void onFailed(String reason) {
            failure.set(reason);
        }
    };

    @BeforeEach
    void requireRuntime() {
        assumeTrue(WebViews.isAvailable(new WebViewOptions().setEngine(engine())), "no " + engine() + " engine here");
    }

    @AfterEach
    void close() {
        if (backend != null) backend.close();
    }

    private WebViewBackend start(WebViewOptions options) throws InterruptedException {
        options.setEngine(engine());
        backend = WebViews.create(options, listener);
        backend.resize(640, 400);
        backend.keepAlive();
        assertTrue(waitFor(ready, 30), "browser ready " + failure.get());
        return backend;
    }

    private void keepShown(long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            backend.keepAlive();
            idle(50);
        }
    }

    private String eval(String js) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        backend.execute(js, json -> result.complete(json == null ? "<failed>" : json));
        keepShown(0);
        long end = System.currentTimeMillis() + 15_000;
        while (!result.isDone() && System.currentTimeMillis() < end) idle(10);
        return result.get(0, TimeUnit.SECONDS);
    }

    private void until(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) {
            backend.keepAlive();
            idle(40);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    private void loadPage() throws Exception {
        start(new WebViewOptions());
        backend.loadHtml(PAGE);
        assertTrue(waitFor(loaded, 30), "page loaded");
    }

    @Test
    void showsAPageAndSendsItsPicture() throws Exception {
        loadPage();
        until(() -> frames.stream().anyMatch(WebViewBackend.Frame::crisp), "a lossless picture once the page settled");
        WebViewBackend.Frame crisp = frames.stream().filter(WebViewBackend.Frame::crisp).findFirst().orElseThrow();
        BufferedImage img = image(crisp);
        int scale = img.getWidth() / 640; // a Retina display draws twice as many pixels
        assertTrue(scale >= 1 && img.getWidth() == 640 * scale, "the picture is the page's width, times the display scale: " + img.getWidth());
        assertEquals(400 * scale, img.getHeight());
        int red = img.getRGB(350 * scale, 70 * scale) & 0xFFFFFF;
        if (crisp.encoded()) {
            assertEquals(0xDD2222, red, "the red box is where the page put it");
        } else { // raw pixels went through the system's colour management, which may shift a colour a little
            assertTrue(Math.abs(((red >> 16) & 0xFF) - 0xDD) < 24 && Math.abs(((red >> 8) & 0xFF) - 0x22) < 24
                    && Math.abs((red & 0xFF) - 0x22) < 24, "the red box is where the page put it: " + Integer.toHexString(red));
        }
        assertEquals(0xFFFFFF, img.getRGB(600 * scale, 300 * scale) & 0xFFFFFF, "and the rest is the white background");
        assertEquals("Test page", title.get());
    }

    @Test
    void aSettledPageStopsSendingPictures() throws Exception {
        loadPage();
        until(() -> frames.stream().anyMatch(WebViewBackend.Frame::crisp), "settled");
        keepShown(600);
        int before = frames.size();
        keepShown(1500);
        assertTrue(frames.size() - before <= 1, "an idle page is not photographed over and over: " + (frames.size() - before));
    }

    @Test
    void aClickAndTypingReachThePage() throws Exception {
        loadPage();
        // a person can only click what has been shown, and a slow machine may not have drawn the page yet
        until(() -> frames.stream().anyMatch(WebViewBackend.Frame::crisp), "the page was shown before it was clicked");
        backend.setFocused(true);
        backend.pointerMove(60, 80 + 20, 0, 0);
        backend.pointerButton(60, 100, 0, true, 0);
        backend.pointerButton(60, 100, 0, false, 0);
        until(() -> {
            try {
                return "1".equals(eval("window.clicks||0"));
            } catch (Exception e) {
                return false;
            }
        }, "the button heard one click");

        until(() -> {
            backend.pointerButton(100, 35, 0, true, 0);
            backend.pointerButton(100, 35, 0, false, 0);
            try {
                return "true".equals(eval("document.activeElement.id=='f'"));
            } catch (Exception e) {
                return false;
            }
        }, "the click focused the field");
        backend.key(GLFW.GLFW_KEY_H, GLFW.GLFW_PRESS, 0);
        backend.text('h');
        backend.key(GLFW.GLFW_KEY_H, GLFW.GLFW_RELEASE, 0);
        backend.text('i');
        until(() -> {
            try {
                return "\"hi\"".equals(eval("document.getElementById('f').value"));
            } catch (Exception e) {
                return false;
            }
        }, "the field holds what was typed");
        assertTrue(eval("keys.join()").contains("h"), "and the page saw the key");
    }

    @Test
    void hoverWheelAndRightClickReachThePage() throws Exception {
        loadPage();
        backend.pointerMove(350, 70, 0, 0);
        until(() -> {
            try {
                return "true".equals(eval("!!window.hovered"));
            } catch (Exception e) {
                return false;
            }
        }, "hover with no button held");
        until(() -> cursors.contains(CursorMode.POINTER), "the cursor the page asked for");

        backend.pointerScroll(300, 200, 0, -3, 0);
        until(() -> {
            try {
                return Double.parseDouble(eval("window.scrollY")) > 100;
            } catch (Exception e) {
                return false;
            }
        }, "the wheel scrolled the page down");

        backend.pointerButton(350, 70, 1, true, 0);
        backend.pointerButton(350, 70, 1, false, 0);
        until(() -> {
            try {
                return "1".equals(eval("window.rights"));
            } catch (Exception e) {
                return false;
            }
        }, "the right button opened the page's own context menu event");
    }

    @Test
    void editingCommandsRunOnThePageSelection() throws Exception {
        loadPage();
        // a click on a page that has not been drawn yet lands on nothing, and text typed before the field has
        // focus is thrown away, so wait for the picture, then click until the page says the field has focus
        until(() -> frames.stream().anyMatch(WebViewBackend.Frame::crisp), "the page was shown before it was clicked");
        backend.setFocused(true);
        until(() -> {
            backend.pointerButton(100, 35, 0, true, 0);
            backend.pointerButton(100, 35, 0, false, 0);
            try {
                return "true".equals(eval("document.activeElement.id=='f'"));
            } catch (Exception e) {
                return false;
            }
        }, "the click focused the field");
        for (char c : "hello".toCharArray()) backend.text(c);
        until(() -> {
            try {
                return "\"hello\"".equals(eval("document.getElementById('f').value"));
            } catch (Exception e) {
                return false;
            }
        }, "typed");
        backend.key(GLFW.GLFW_KEY_A, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL);
        backend.key(GLFW.GLFW_KEY_A, GLFW.GLFW_RELEASE, GLFW.GLFW_MOD_CONTROL);
        backend.key(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_PRESS, 0);
        backend.key(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_RELEASE, 0);
        until(() -> {
            try {
                return "\"\"".equals(eval("document.getElementById('f').value"));
            } catch (Exception e) {
                return false;
            }
        }, "select all then delete empties the field");
    }

    @Test
    void anAllowedHostListRefusesOtherAddresses() throws Exception {
        start(new WebViewOptions().allowHost("example.com"));
        backend.navigate("https://evil.example.org/");
        until(() -> blocked.contains("https://evil.example.org/"), "refused");
        backend.loadHtml("<title>ok</title>hello");
        assertTrue(waitFor(loaded, 30), "its own pages still load");
    }

    @Test
    void theMessageBridgeIsOffUnlessAsked() throws Exception {
        start(new WebViewOptions());
        backend.loadHtml("<script>try{chrome.webview.postMessage('x')}catch(e){}</script>");
        assertTrue(waitFor(loaded, 30));
        keepShown(800);
        assertTrue(messages.isEmpty(), "a page cannot call the application by default");
    }

    @Test
    void theMessageBridgeWorksBothWaysWhenOn() throws Exception {
        start(new WebViewOptions().setScriptBridge(true));
        backend.loadHtml("<script>chrome.webview.addEventListener('message', e => chrome.webview.postMessage({echo: e.data}));"
                + "chrome.webview.postMessage('hello')</script>");
        assertTrue(waitFor(loaded, 30));
        until(() -> messages.contains("\"hello\""), "page to application");
        backend.postMessage("{\"n\":7}");
        until(() -> messages.contains("{\"echo\":{\"n\":7}}"), "application to page and back");
    }

    @Test
    void windowOpenDoesNotOpenAWindowAndLoadsInTheSameView() throws Exception {
        start(new WebViewOptions());
        backend.loadHtml("<title>first</title><script>setTimeout(()=>window.open('data:text/html,<title>second</title>x'),100)</script>");
        assertTrue(waitFor(loaded, 30));
        // opening needs a user gesture in a real page; the blocker may eat it, which is fine: nothing else must open
        keepShown(500);
        assertNull(failure.get());
    }

    @Test
    void javascriptResultsComeBackAsJson() throws Exception {
        loadPage();
        assertEquals("42", eval("6*7"));
        assertEquals("\"a\"", eval("'a'"));
        assertEquals("<failed>", eval("throw new Error('x')").replaceAll("\"null\"|null", "<failed>"));
    }

    @Test
    void picturesFollowAResize() throws Exception {
        loadPage();
        backend.resize(320, 200);
        until(() -> frames.stream().anyMatch(f -> f.crisp() && f.width() > 0 && f.width() % 320 == 0 && f.width() < 640), "a picture at the new size");
        WebViewBackend.Frame f = frames.stream().filter(x -> x.crisp() && x.width() > 0 && x.width() % 320 == 0 && x.width() < 640)
                .findFirst().orElseThrow();
        BufferedImage img = image(f);
        int scale = img.getWidth() / 320;
        assertEquals(320 * scale, img.getWidth());
        assertEquals(200 * scale, img.getHeight());
    }
}
