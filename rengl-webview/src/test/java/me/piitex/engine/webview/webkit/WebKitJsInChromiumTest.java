package me.piitex.engine.webview.webkit;

import me.piitex.engine.webview.WebViewBackend;
import me.piitex.engine.webview.WebViewOptions;
import me.piitex.engine.webview.WebViews;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two WebKit engines cannot be run here, but the scripts they inject are plain JavaScript, so a Chromium (any machine
 * that has one installed) can check that they parse and behave. Skipped where there is none.
 */
class WebKitJsInChromiumTest {
    private WebViewBackend backend;

    @BeforeEach
    void start() throws Exception {
        WebViewOptions options = new WebViewOptions().setEngine(WebViewOptions.Engine.CHROMIUM);
        assumeTrue(WebViews.isAvailable(options), "no Chromium here");
        CountDownLatch ready = new CountDownLatch(1);
        backend = WebViews.create(options, new WebViewBackend.Listener() {
            @Override
            public void onReady() {
                ready.countDown();
            }
        });
        backend.resize(200, 100);
        backend.keepAlive();
        assertTrue(ready.await(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void stop() {
        if (backend != null) backend.close();
    }

    private String eval(String js) throws Exception {
        CompletableFuture<String> out = new CompletableFuture<>();
        backend.execute(js, json -> out.complete(json));
        return out.get(15, TimeUnit.SECONDS);
    }

    /** What the WebKit engines hand back for a script: the string the wrapper produced, which is JSON. */
    private String wrapped(String script) throws Exception {
        String quoted = eval(WebKitJs.wrapForJson(script));
        if (quoted == null) return null;
        // the string the wrapper made, which comes back JSON-quoted: take the quotes and the escapes off
        return quoted.substring(1, quoted.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    @Test
    void numbersStringsObjectsAndNothingComeBackAsJson() throws Exception {
        assertEquals("42", wrapped("6*7"));
        assertEquals("\"a\"", wrapped("'a'"));
        assertEquals("{\"x\":[1,2]}", wrapped("({x:[1,2]})"));
        assertEquals("null", wrapped("undefined"));
        assertEquals("null", wrapped("(function(){})"), "no JSON form");
        assertEquals("true", wrapped("document.readyState !== undefined"));
    }

    @Test
    void aThrowingScriptFails() throws Exception {
        assertNull(wrapped("throw new Error('x')"));
    }

    @Test
    void theScriptRunsInTheGlobalScope() throws Exception {
        eval(WebKitJs.wrapForJson("var viaWrapper = 5"));
        assertEquals("5", wrapped("window.viaWrapper"));
    }

    @Test
    void theBridgeShimPostsJsonTextToTheHandlerAndDeliversMessages() throws Exception {
        eval("window.webkit = {messageHandlers: {" + WebKitJs.HANDLER + ": {postMessage: function(m) { window.__got = m; }}}}");
        eval(WebKitJs.bridgeShim());
        eval("chrome.webview.postMessage({a: 1}); 0");
        assertEquals("\"{\\\"a\\\":1}\"", eval("window.__got"), "the handler gets JSON text, as the engines read it");
        eval("chrome.webview.postMessage('hello'); 0");
        assertEquals("\"\\\"hello\\\"\"", eval("window.__got"));

        eval("window.__heard = []; chrome.webview.addEventListener('message', function(e) { window.__heard.push(e.data); }); 0");
        eval(WebKitJs.deliver("{\"n\":7}"));
        eval(WebKitJs.deliver("\"text\""));
        assertEquals("[{\"n\":7},\"text\"]", eval("window.__heard"));
    }

    @Test
    void theShimRunsOnlyOnce() throws Exception {
        eval("window.webkit = {messageHandlers: {" + WebKitJs.HANDLER + ": {postMessage: function(m) {}}}}");
        eval(WebKitJs.bridgeShim());
        eval("window.__first = chrome.webview; 0");
        eval(WebKitJs.bridgeShim());
        assertEquals("true", eval("window.__first === chrome.webview"));
    }
}
