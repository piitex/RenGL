package me.piitex.engine.webview;

/** The contract against WebView2 (Windows). */
class WebView2BackendTest extends BackendContractTest {
    @Override
    protected WebViewOptions.Engine engine() {
        return WebViewOptions.Engine.WEBVIEW2;
    }
}
