package me.piitex.engine.webview.webkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebKitJsTest {
    @Test
    void aScriptIsWrappedToProduceJsonAndEvaluatedGlobally() {
        String w = WebKitJs.wrapForJson("6*7");
        assertTrue(w.contains("(0,eval)(\"6*7\")"), w);
        assertTrue(w.contains("JSON.stringify"), w);
    }

    @Test
    void aScriptWithQuotesAndNewlinesIsEmbeddedAsALiteral() {
        String w = WebKitJs.wrapForJson("var a = \"x\";\n'y' + a + '\\'");
        assertTrue(w.contains("\\\"x\\\""), w);
        assertTrue(w.contains("\\n"), w);
        assertFalse(w.contains("\n"), "no raw newline inside the wrapper");
    }

    @Test
    void theBridgeShimPostsToTheNamedHandler() {
        String shim = WebKitJs.bridgeShim();
        assertTrue(shim.contains("window.webkit.messageHandlers." + WebKitJs.HANDLER + ".postMessage"), shim);
        assertTrue(shim.contains("window.chrome.webview"), shim);
        assertTrue(shim.contains("__renglDeliver"), shim);
        assertTrue(shim.contains("JSON.stringify(m === undefined ? null : m)"), shim);
    }

    @Test
    void deliveringAMessageQuotesTheJson() {
        assertEquals("if (window.__renglDeliver) window.__renglDeliver(\"{\\\"n\\\":7}\"); 0", WebKitJs.deliver("{\"n\":7}"));
    }
}
