package me.piitex.engine.webview;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** Which engine {@link WebViews} picks, and that the two engines nobody has run yet are only ever asked for. */
class WebViewsEngineTest {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    @Test
    void autoNeverPicksAnEngineThatHasNotBeenRun() {
        WebViewOptions.Engine picked = WebViews.resolve(new WebViewOptions());
        assertNotEquals(WebViewOptions.Engine.WKWEBVIEW, picked);
        assertNotEquals(WebViewOptions.Engine.WEBKITGTK, picked);
        assertEquals(picked, WebViews.resolve(null));
    }

    @Test
    void autoStillPrefersWebView2ThenAnInstalledBrowser() {
        WebViewOptions.Engine picked = WebViews.resolve(new WebViewOptions());
        if (picked == null) return; // a machine with neither
        assertTrue(picked == WebViewOptions.Engine.WEBVIEW2 || picked == WebViewOptions.Engine.CHROMIUM, "" + picked);
        if (picked == WebViewOptions.Engine.CHROMIUM) {
            assertNull(WebViews.resolve(new WebViewOptions().setEngine(WebViewOptions.Engine.WEBVIEW2)), "so WebView2 was not there");
        }
    }

    @Test
    void theAppleEngineIsOnlyOnAMac() {
        WebViewOptions options = new WebViewOptions().setEngine(WebViewOptions.Engine.WKWEBVIEW);
        if (!OS.contains("mac")) {
            assertNull(WebViews.resolve(options));
            assertFalse(WebViews.isAvailable(options));
            assertTrue(WebViews.describe(options).contains("macOS"), WebViews.describe(options));
            assertThrows(IllegalStateException.class, () -> WebViews.create(options, null));
        }
    }

    @Test
    void theLinuxEngineIsOnlyOnLinux() {
        WebViewOptions options = new WebViewOptions().setEngine(WebViewOptions.Engine.WEBKITGTK);
        if (!OS.contains("linux")) {
            assertNull(WebViews.resolve(options));
            assertFalse(WebViews.isAvailable(options));
            assertTrue(WebViews.describe(options).contains("Linux"), WebViews.describe(options));
            assertThrows(IllegalStateException.class, () -> WebViews.create(options, null));
        }
    }

    @Test
    void neitherWebKitEngineDrawsANativeWindow() {
        assertFalse(WebViews.supportsNative(new WebViewOptions().setEngine(WebViewOptions.Engine.WKWEBVIEW)));
        assertFalse(WebViews.supportsNative(new WebViewOptions().setEngine(WebViewOptions.Engine.WEBKITGTK)));
    }

    @Test
    void engineSelectionStillWorks() {
        assertEquals(WebViewOptions.Engine.AUTO, new WebViewOptions().getEngine());
        assertEquals(WebViewOptions.Engine.WKWEBVIEW, new WebViewOptions().setEngine(WebViewOptions.Engine.WKWEBVIEW).getEngine());
        assertEquals(WebViewOptions.Engine.AUTO, new WebViewOptions().setEngine(null).getEngine());
        assertEquals(5, WebViewOptions.Engine.values().length);
    }
}
