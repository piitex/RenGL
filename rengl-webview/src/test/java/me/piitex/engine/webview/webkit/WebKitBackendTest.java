package me.piitex.engine.webview.webkit;

import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.webview.WebViewBackend;
import me.piitex.engine.webview.WebViewOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The logic both WebKit engines share, run against a fake toolkit that records what it is told. This is everything of
 * the engines except the calls into AppKit and GTK.
 */
class WebKitBackendTest {

    /** A toolkit with one thread, which records every call. */
    static final class Fake extends WebKitBackend {
        final ExecutorService ui = Executors.newSingleThreadExecutor();
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        volatile String url = "about:blank", title = "";
        volatile boolean back, forward;
        volatile Consumer<Picture> pendingPicture;
        volatile Consumer<String> pendingScript;
        volatile String lastScript;
        int shutdowns;

        Fake(WebViewOptions options, Listener listener) {
            super(options, listener);
        }

        void ready() {
            run(this::becomeReady);
        }

        /** Runs on the toolkit's thread and waits. */
        void run(Runnable r) {
            try {
                ui.submit(r).get();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        void drain() {
            run(() -> {
            });
        }

        void tickNow() {
            run(this::tick);
        }

        @Override
        protected void runOnUiThread(Runnable task) {
            ui.execute(task);
        }

        @Override
        protected void loadUrl(String u) {
            calls.add("load " + u);
        }

        @Override
        protected void loadHtmlNow(String html) {
            calls.add("html " + html);
        }

        @Override
        protected void reloadNow() {
            calls.add("reload");
        }

        @Override
        protected void stopNow() {
            calls.add("stop");
        }

        @Override
        protected void goBackNow() {
            calls.add("back");
        }

        @Override
        protected void goForwardNow() {
            calls.add("forward");
        }

        @Override
        protected void applySize(int w, int h) {
            calls.add("size " + w + "x" + h);
        }

        @Override
        protected String currentUrl() {
            return url;
        }

        @Override
        protected String currentTitle() {
            return title;
        }

        @Override
        protected boolean canGoBackNow() {
            return back;
        }

        @Override
        protected boolean canGoForwardNow() {
            return forward;
        }

        @Override
        protected void evaluateRaw(String script, Consumer<String> result) {
            lastScript = script;
            calls.add("script " + script);
            pendingScript = result;
        }

        @Override
        protected void capture(Consumer<Picture> done) {
            calls.add("capture");
            pendingPicture = done;
        }

        @Override
        protected void sendMove(double x, double y, int buttons, int mods) {
            calls.add("move " + (int) x + "," + (int) y + " b" + buttons);
        }

        @Override
        protected void sendButton(double x, double y, int glfwButton, boolean pressed, int clickCount, int mods) {
            calls.add("button " + glfwButton + (pressed ? " down" : " up") + " x" + clickCount);
        }

        @Override
        protected void sendScroll(double x, double y, double dx, double dy, int mods) {
            calls.add("scroll " + dx + "," + dy);
        }

        @Override
        protected void sendLeave() {
            calls.add("leave");
        }

        @Override
        protected void sendKey(int glfwKey, int action, int mods) {
            calls.add("key " + glfwKey + " a" + action + " m" + mods);
        }

        @Override
        protected void sendEditCommand(String command) {
            calls.add("edit " + command);
        }

        @Override
        protected void sendText(int codePoint) {
            calls.add("text " + new String(Character.toChars(codePoint)));
        }

        @Override
        protected void sendComposition(String text, int caret) {
            calls.add("composition " + text + "@" + caret);
        }

        @Override
        protected void sendFocus(boolean focused) {
            calls.add("focus " + focused);
        }

        @Override
        protected void shutdown() {
            shutdowns++;
            calls.add("shutdown");
        }

        // the protected helpers, reachable from tests
        boolean navigationAllowed(String u, boolean user, boolean redirected) {
            boolean[] out = new boolean[1];
            run(() -> out[0] = allowNavigation(u, user, redirected));
            return out[0];
        }

        void newWindow(String u) {
            run(() -> newWindowRequested(u, true));
            drain();
        }

        void finished(boolean ok, int status) {
            run(() -> navigationFinished(ok, status));
        }

        void message(String source, String json) {
            run(() -> messageFromPage(source, json));
        }

        boolean permitted(WebViewOptions.Permission p) {
            return permits(p);
        }
    }

    /** What the listener heard. */
    static final class Heard implements WebViewBackend.Listener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<WebViewBackend.Frame> frames = Collections.synchronizedList(new ArrayList<>());
        final List<CursorMode> cursors = Collections.synchronizedList(new ArrayList<>());
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        volatile String cancelPrefix;

        @Override
        public void onReady() {
            events.add("ready");
        }

        @Override
        public void onFrame(WebViewBackend.Frame frame) {
            frames.add(frame);
        }

        @Override
        public void onNavigationStarting(WebViewBackend.NavigationRequest request) {
            String prefix = cancelPrefix;
            if (prefix != null && request.url().startsWith(prefix)) {
                request.cancel();
                events.add("claimed " + request.url());
            }
        }

        @Override
        public void onNavigationStarted(String url) {
            events.add("started " + url);
        }

        @Override
        public void onNavigationBlocked(String url) {
            events.add("blocked " + url);
        }

        @Override
        public void onNavigationCompleted(String url, boolean success, int status) {
            events.add("completed " + url + " " + success + " " + status);
        }

        @Override
        public void onUrlChanged(String url) {
            events.add("url " + url);
        }

        @Override
        public void onTitleChanged(String title) {
            events.add("title " + title);
        }

        @Override
        public void onHistoryChanged(boolean canGoBack, boolean canGoForward) {
            events.add("history " + canGoBack + " " + canGoForward);
        }

        @Override
        public void onNewWindow(String url, boolean userInitiated) {
            events.add("window " + url);
        }

        @Override
        public void onMessage(String json) {
            messages.add(json);
        }

        @Override
        public void onCursor(CursorMode mode) {
            cursors.add(mode == null ? CursorMode.DEFAULT : mode);
        }

        @Override
        public void onTextInputChanged(boolean editing) {
            events.add("editing " + editing);
        }

        @Override
        public void onFailed(String reason) {
            events.add("failed " + reason);
        }
    }

    private Fake fake;
    private final Heard heard = new Heard();

    private Fake start(WebViewOptions options) {
        fake = new Fake(options, heard);
        fake.ready();
        return fake;
    }

    @AfterEach
    void stop() {
        if (fake != null) {
            fake.close();
            fake.ui.shutdown();
        }
    }

    private static byte[] pixels(int w, int h, int fill) {
        byte[] b = new byte[w * h * 4];
        java.util.Arrays.fill(b, (byte) fill);
        return b;
    }

    // ------------------------------------------------------------------------------------------------ start up

    @Test
    void commandsGivenBeforeTheViewExistsAreCarriedOutWhenItDoes() {
        fake = new Fake(new WebViewOptions(), heard);
        fake.navigate("https://example.com/");
        fake.loadHtml("<p>x");
        fake.drain();
        assertTrue(fake.calls.isEmpty(), "held back: " + fake.calls);
        fake.ready();
        assertEquals(List.of("load https://example.com/", "html <p>x"), fake.calls);
        assertEquals("ready", heard.events.getLast());
    }

    @Test
    void theViewIsReadyOnlyOnce() {
        start(new WebViewOptions());
        fake.ready();
        assertEquals(1, heard.events.stream().filter("ready"::equals).count());
    }

    // ------------------------------------------------------------------------------------------------ navigation rules

    @Test
    void anAddressOutsideTheAllowedHostsIsRefusedBeforeItIsLoaded() {
        start(new WebViewOptions().allowHost("example.com"));
        fake.navigate("https://evil.example.org/");
        fake.navigate("https://login.example.com/");
        fake.drain();
        assertEquals(List.of("load https://login.example.com/"), fake.calls);
        assertTrue(heard.events.contains("blocked https://evil.example.org/"));
    }

    @Test
    void aNavigationTheViewMakesIsCheckedAgainstTheHostListToo() {
        start(new WebViewOptions().allowHost("example.com"));
        assertTrue(fake.navigationAllowed("https://login.example.com/a", true, false));
        assertFalse(fake.navigationAllowed("https://evilexample.com/", false, false));
        assertFalse(fake.navigationAllowed("https://example.com@evil.org/", false, false), "the host follows the @");
        assertTrue(heard.events.contains("started https://login.example.com/a"));
        assertTrue(heard.events.contains("blocked https://evilexample.com/"));
    }

    @Test
    void theApplicationsOwnRedirectAddressIsClaimedBeforeTheHostListAndAnyScheme() {
        start(new WebViewOptions().allowHost("login.example.com"));
        heard.cancelPrefix = "mygame://";
        assertFalse(fake.navigationAllowed("mygame://signed-in?code=1", false, true));
        assertTrue(heard.events.contains("claimed mygame://signed-in?code=1"));
        assertFalse(heard.events.stream().anyMatch(e -> e.startsWith("blocked mygame")), "claimed, not refused");
    }

    @Test
    void addressesThatAreNotTheWebAreNeverLoaded() {
        start(new WebViewOptions());
        assertFalse(fake.navigationAllowed("mailto:someone@example.com", true, false));
        assertFalse(fake.navigationAllowed("tel:+15551234", true, false));
        assertFalse(fake.navigationAllowed("javascript:alert(1)", true, false));
        assertFalse(fake.navigationAllowed("file:///etc/passwd", true, false));
        assertFalse(fake.navigationAllowed("itms-apps://x", true, false));
        assertTrue(fake.navigationAllowed("about:blank", false, false));
        assertTrue(fake.navigationAllowed("https://example.com/", true, false));
        assertTrue(fake.navigationAllowed("data:text/html,<p>hi", false, false));
    }

    @Test
    void theWebSchemesAreRecognised() {
        assertTrue(WebKitBackend.isWebScheme("HTTPS://Example.com"));
        assertTrue(WebKitBackend.isWebScheme("http://localhost:8080"));
        assertTrue(WebKitBackend.isWebScheme("blob:https://x/1"));
        assertFalse(WebKitBackend.isWebScheme(null));
        assertFalse(WebKitBackend.isWebScheme("ftp://x"));
    }

    @Test
    void aNewWindowLoadsInTheSameViewUnlessBlocked() {
        start(new WebViewOptions());
        fake.newWindow("https://example.com/popup");
        assertTrue(fake.calls.contains("load https://example.com/popup"));
        assertTrue(heard.events.contains("window https://example.com/popup"));

        fake.calls.clear();
        fake.newWindow("mailto:x@example.com");
        assertFalse(fake.calls.stream().anyMatch(c -> c.startsWith("load")), "only web addresses");
    }

    @Test
    void blockedNewWindowsOnlyReport() {
        start(new WebViewOptions().setNewWindows(WebViewOptions.NewWindows.BLOCK));
        fake.newWindow("https://example.com/popup");
        assertTrue(heard.events.contains("window https://example.com/popup"));
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    void aNewWindowToAForbiddenHostIsNotFollowed() {
        start(new WebViewOptions().allowHost("example.com"));
        fake.newWindow("https://evil.org/");
        assertFalse(fake.calls.stream().anyMatch(c -> c.startsWith("load")));
    }

    @Test
    void aFinishedNavigationReportsTheAddressItEndedAt() {
        start(new WebViewOptions());
        fake.url = "https://example.com/done";
        fake.finished(true, 0);
        assertTrue(heard.events.contains("url https://example.com/done"));
        assertEquals("completed https://example.com/done true 0", heard.events.getLast());
        fake.finished(false, -1009);
        assertEquals("completed https://example.com/done false -1009", heard.events.getLast());
    }

    @Test
    void addressTitleAndHistoryChangesAreReportedOnceEach() throws Exception {
        start(new WebViewOptions());
        fake.url = "https://example.com/";
        fake.title = "Example";
        fake.back = true;
        fake.tickNow();
        fake.tickNow();
        assertEquals(1, heard.events.stream().filter("url https://example.com/"::equals).count());
        assertEquals(1, heard.events.stream().filter("title Example"::equals).count());
        assertEquals(1, heard.events.stream().filter("history true false"::equals).count());
        assertFalse(heard.events.contains("title "), "an empty title is not news");
    }

    // ------------------------------------------------------------------------------------------------ pictures

    @Test
    void aHiddenPageTakesNoPictures() {
        start(new WebViewOptions());
        fake.tickNow();
        fake.tickNow();
        assertFalse(fake.calls.contains("capture"));
    }

    @Test
    void aShownPageIsPhotographedAndAChangeIsReportedOnce() {
        start(new WebViewOptions());
        fake.resize(40, 20);
        fake.keepAlive();
        fake.drain();
        fake.tickNow();
        assertTrue(fake.calls.contains("capture"));

        Consumer<WebKitBackend.Picture> done = fake.pendingPicture;
        fake.run(() -> done.accept(new WebKitBackend.Picture(40, 20, pixels(40, 20, 1))));
        assertEquals(1, heard.frames.size());
        WebViewBackend.Frame f = heard.frames.getFirst();
        assertEquals(40, f.width());
        assertEquals(20, f.height());
        assertFalse(f.encoded(), "raw BGRA");
        assertTrue(f.crisp(), "snapshots are lossless");

        fake.pendingPicture = null;
        fake.keepAlive();
        fake.tickNow();
        assertNotNull(fake.pendingPicture, "the next picture follows at once while it moves");
        fake.run(() -> fake.pendingPicture.accept(new WebKitBackend.Picture(40, 20, pixels(40, 20, 1))));
        assertEquals(1, heard.frames.size(), "the same picture twice is reported once");
    }

    @Test
    void onlyOnePictureIsAskedForAtATime() {
        start(new WebViewOptions());
        fake.keepAlive();
        fake.tickNow();
        assertEquals(1, fake.calls.stream().filter("capture"::equals).count());
        fake.tickNow();
        fake.tickNow();
        assertEquals(1, fake.calls.stream().filter("capture"::equals).count(), "still waiting for the first");
    }

    @Test
    void aFailedPictureIsNotReported() {
        start(new WebViewOptions());
        fake.keepAlive();
        fake.tickNow();
        fake.run(() -> fake.pendingPicture.accept(null));
        assertTrue(heard.frames.isEmpty());
    }

    @Test
    void aMalformedPictureIsNotReported() {
        start(new WebViewOptions());
        fake.keepAlive();
        fake.tickNow();
        fake.run(() -> fake.pendingPicture.accept(new WebKitBackend.Picture(0, 0, new byte[0])));
        assertTrue(heard.frames.isEmpty());
    }

    @Test
    void aResizeIsPassedOnOnlyWhenTheSizeChanges() {
        start(new WebViewOptions());
        fake.resize(300, 200);
        fake.resize(300, 200);
        fake.resize(0, -5);
        fake.drain();
        assertEquals(List.of("size 300x200", "size 1x1"), fake.calls);
    }

    // ------------------------------------------------------------------------------------------------ input

    @Test
    void aKeyThatTypesACharacterIsLeftToTheTextCallAndSoNothingIsTypedTwice() {
        start(new WebViewOptions());
        fake.key(GLFW.GLFW_KEY_H, GLFW.GLFW_PRESS, 0);
        fake.text('h');
        fake.key(GLFW.GLFW_KEY_H, GLFW.GLFW_RELEASE, 0);
        fake.drain();
        assertEquals(List.of("text h"), fake.calls);
    }

    @Test
    void specialKeysAndShortcutsAreRoutedTheirOwnWays() {
        start(new WebViewOptions());
        fake.key(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0);
        fake.key(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_RELEASE, 0);
        fake.key(GLFW.GLFW_KEY_A, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL);
        fake.key(GLFW.GLFW_KEY_A, GLFW.GLFW_RELEASE, GLFW.GLFW_MOD_CONTROL);
        fake.key(GLFW.GLFW_KEY_V, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_SHIFT);
        fake.key(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
        fake.drain();
        assertEquals(List.of("key 257 a1 m0", "key 257 a0 m0", "edit selectAll", "edit pasteAndMatchStyle"), fake.calls);
    }

    @Test
    void controlCharactersAreNotTyped() {
        start(new WebViewOptions());
        fake.text(0x08);
        fake.text(0x7F);
        fake.text('\n');
        fake.text('é');
        fake.text(0x1F600);
        fake.drain();
        assertEquals(List.of("text é", "text " + new String(Character.toChars(0x1F600))), fake.calls);
    }

    @Test
    void pointerEventsCarryTheirClickCounts() {
        start(new WebViewOptions());
        fake.pointerMove(10, 12, 0, 0);
        fake.pointerButton(10, 12, 0, true, 0);
        fake.pointerButton(10, 12, 0, false, 0);
        fake.pointerButton(10, 12, 0, true, 0);
        fake.pointerButton(10, 12, 0, false, 0);
        fake.pointerScroll(10, 12, 0, -3, 0);
        fake.pointerLeave();
        fake.drain();
        assertEquals(List.of("move 10,12 b0", "button 0 down x1", "button 0 up x0", "button 0 down x2", "button 0 up x0",
                "scroll 0.0,-3.0", "leave"), fake.calls);
    }

    @Test
    void aCompositionIsClampedToItsText() {
        start(new WebViewOptions());
        fake.composition("ab", 9);
        fake.composition(null, 3);
        fake.drain();
        assertEquals(List.of("composition ab@2", "composition @0"), fake.calls);
    }

    @Test
    void focusIsPassedOn() {
        start(new WebViewOptions());
        fake.setFocused(true);
        fake.setFocused(false);
        fake.drain();
        assertEquals(List.of("focus true", "focus false"), fake.calls);
    }

    // ------------------------------------------------------------------------------------------------ script

    @Test
    void aScriptIsWrappedSoItsResultIsJson() {
        start(new WebViewOptions());
        fake.execute("6*7", r -> {
        });
        fake.drain();
        assertEquals(WebKitJs.wrapForJson("6*7"), fake.lastScript);
    }

    @Test
    void theCursorUnderThePointerIsReadFromThePage() {
        start(new WebViewOptions());
        fake.keepAlive();
        fake.pointerMove(350, 70, 0, 0);
        fake.drain();
        fake.tickNow();
        assertTrue(fake.lastScript.endsWith("})(350, 70)"), fake.lastScript);
        fake.run(() -> fake.pendingScript.accept("\"pointer|1\""));
        assertEquals(List.of(CursorMode.POINTER), heard.cursors);
        assertTrue(heard.events.contains("editing true"));
    }

    @Test
    void theCursorIsNotAskedForWhenThePointerIsAway() {
        start(new WebViewOptions());
        fake.keepAlive();
        fake.pointerMove(5, 5, 0, 0);
        fake.pointerLeave();
        fake.drain();
        fake.calls.clear();
        fake.tickNow();
        assertFalse(fake.calls.stream().anyMatch(c -> c.startsWith("script")), fake.calls.toString());
    }

    @Test
    void theMessageBridgeIsOffUnlessAsked() {
        start(new WebViewOptions());
        fake.postMessage("{\"a\":1}");
        fake.message("https://example.com/", "{\"a\":1}");
        fake.drain();
        assertTrue(heard.messages.isEmpty());
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void theMessageBridgeWorksBothWaysAndOnlyForAllowedHosts() {
        start(new WebViewOptions().setScriptBridge(true).allowHost("example.com"));
        fake.message("https://login.example.com/x", "\"hello\"");
        fake.message("https://evil.org/", "\"nope\"");
        fake.message(null, "\"unknown source\"");
        fake.postMessage("{\"n\":7}");
        fake.drain();
        assertEquals(List.of("\"hello\"", "\"unknown source\""), heard.messages);
        assertEquals(WebKitJs.deliver("{\"n\":7}"), fake.lastScript);
    }

    @Test
    void permissionsAreRefusedUnlessNamed() {
        start(new WebViewOptions().allow(WebViewOptions.Permission.CAMERA));
        assertTrue(fake.permitted(WebViewOptions.Permission.CAMERA));
        assertFalse(fake.permitted(WebViewOptions.Permission.MICROPHONE));
        assertFalse(fake.permitted(null));
    }

    // ------------------------------------------------------------------------------------------------ closing

    @Test
    void closingFreesTheViewOnceAndIgnoresLaterCommands() {
        start(new WebViewOptions());
        fake.close();
        fake.close();
        fake.navigate("https://example.com/");
        fake.text('a');
        fake.drain();
        assertEquals(List.of("shutdown"), fake.calls);
        assertEquals(1, fake.shutdowns);
    }

    @Test
    void aViewClosedBeforeItWasReadyStillFreesWhatItHas() {
        fake = new Fake(new WebViewOptions(), heard);
        fake.close();
        fake.drain();
        assertEquals(1, fake.shutdowns);
    }

    @Test
    void aFailureIsReported() {
        start(new WebViewOptions());
        fake.run(() -> fake.listener().onFailed("boom"));
        assertEquals("failed boom", heard.events.getLast());
    }
}
