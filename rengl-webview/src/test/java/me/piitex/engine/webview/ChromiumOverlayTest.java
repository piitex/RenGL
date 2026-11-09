package me.piitex.engine.webview;

/** The overlay on an installed Chrome, Edge or Chromium, run headless. */
class ChromiumOverlayTest extends OverlayContractTest {
    @Override
    protected WebViewOptions.Engine engine() {
        return WebViewOptions.Engine.CHROMIUM;
    }
}
