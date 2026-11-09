package me.piitex.engine.webview;

/** The overlay on WebView2 (Windows). */
class WebView2OverlayTest extends OverlayContractTest {
    @Override
    protected WebViewOptions.Engine engine() {
        return WebViewOptions.Engine.WEBVIEW2;
    }
}
