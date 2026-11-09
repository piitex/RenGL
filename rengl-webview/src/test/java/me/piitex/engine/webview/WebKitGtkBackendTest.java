package me.piitex.engine.webview;

/** The contract against WebKitGTK. Skipped everywhere but a Linux desktop with the library installed. */
class WebKitGtkBackendTest extends BackendContractTest {
    @Override
    protected WebViewOptions.Engine engine() {
        return WebViewOptions.Engine.WEBKITGTK;
    }
}
