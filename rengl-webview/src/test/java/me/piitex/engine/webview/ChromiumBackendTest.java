package me.piitex.engine.webview;

/** The contract against an installed Chrome, Edge or Chromium, run headless (every system). */
class ChromiumBackendTest extends BackendContractTest {
    @Override
    protected WebViewOptions.Engine engine() {
        return WebViewOptions.Engine.CHROMIUM;
    }
}
