package me.piitex.engine.webview.webkit;

import me.piitex.engine.webview.internal.CdpInput;

/**
 * The scripts both WebKit engines (WKWebView and WebKitGTK) run in the page. Neither engine gives a result as JSON the
 * way WebView2 does, so a script is wrapped to produce JSON text itself, and the page's {@code chrome.webview} bridge is
 * built on WebKit's own {@code webkit.messageHandlers}.
 */
public final class WebKitJs {
    private WebKitJs() {
    }

    /** The name of the script message handler the bridge posts to. */
    public static final String HANDLER = "renglBridge";

    /**
     * A script that evaluates {@code script} in the page's global scope and returns its value as JSON text, or the text
     * {@code null} for {@code undefined} and for what has no JSON form. An exception is not caught, so the engine reports
     * a failure, which is what {@code execute} means by a null result.
     */
    public static String wrapForJson(String script) {
        return "(function(){var r=(0,eval)(" + CdpInput.quote(script) + ");"
                + "var s=JSON.stringify(r===undefined?null:r);return s===undefined?'null':s;})()";
    }

    /** The script that runs in every page before its own, when the bridge is on. */
    public static String bridgeShim() {
        return """
                (function() {
                  if (window.__renglShim) return;
                  window.__renglShim = 1;
                  var listeners = [];
                  window.chrome = window.chrome || {};
                  window.chrome.webview = {
                    postMessage: function(m) {
                      try { window.webkit.messageHandlers.%s.postMessage(JSON.stringify(m === undefined ? null : m)); } catch (e) {}
                    },
                    addEventListener: function(t, f) { if (t === 'message') listeners.push(f); },
                    removeEventListener: function(t, f) { listeners = listeners.filter(function(x) { return x !== f; }); }
                  };
                  window.__renglDeliver = function(json) {
                    var e = { data: JSON.parse(json) };
                    listeners.slice().forEach(function(f) { f(e); });
                  };
                })();""".formatted(HANDLER);
    }

    /** The script that hands a JSON message from the application to the page's {@code message} listeners. */
    public static String deliver(String json) {
        return "if (window.__renglDeliver) window.__renglDeliver(" + CdpInput.quote(json) + "); 0";
    }
}
