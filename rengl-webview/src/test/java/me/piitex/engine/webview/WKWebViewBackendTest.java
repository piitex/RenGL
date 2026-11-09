package me.piitex.engine.webview;

import me.piitex.engine.webview.macos.WKWebViewBackend;

/**
 * The contract against WKWebView. Skipped everywhere but a Mac. The backend's work runs on the main thread, so a test
 * run with {@code -XstartOnFirstThread} (the macOS profile does) lends it the thread while it waits.
 */
class WKWebViewBackendTest extends BackendContractTest {
    @Override
    protected WebViewOptions.Engine engine() {
        return WebViewOptions.Engine.WKWEBVIEW;
    }

    @Override
    protected void idle(long millis) {
        WKWebViewBackend.pumpMainThread(millis);
    }
}
