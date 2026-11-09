package me.piitex.engine.webview.chromium;

import me.piitex.engine.webview.WebViewOptions;
import me.piitex.engine.webview.WebViews;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Where the module looks for a browser, per system. Needs no browser, so it runs everywhere. */
class ChromiumLocatorTest {
    private static List<Path> candidatesAs(String os) {
        String before = System.getProperty("os.name");
        System.setProperty("os.name", os);
        try {
            return ChromiumLocator.candidates();
        } finally {
            System.setProperty("os.name", before);
        }
    }

    private static boolean has(List<Path> paths, String part) {
        return paths.stream().anyMatch(p -> p.toString().replace('\\', '/').contains(part));
    }

    @Test
    void macLooksInApplications() {
        List<Path> mac = candidatesAs("Mac OS X");
        assertTrue(has(mac, "Google Chrome.app/Contents/MacOS/Google Chrome"));
        assertTrue(has(mac, "Microsoft Edge.app"));
        assertTrue(has(mac, "/Applications/"));
    }

    @Test
    void linuxLooksForTheCommonNamesAndKeepsSnapsLast() {
        List<Path> linux = candidatesAs("Linux");
        assertTrue(has(linux, "/usr/bin/google-chrome"));
        assertTrue(has(linux, "/usr/bin/chromium"));
        assertTrue(has(linux, "microsoft-edge"));
        assertEquals(Path.of("/snap/bin/chromium"), linux.getLast(), "a snap cannot read the temporary profile folder, so it is the last resort");
    }

    @Test
    void windowsLooksInProgramFilesForEdgeAndChrome() {
        List<Path> windows = candidatesAs("Windows 11");
        if (System.getenv("ProgramFiles") != null) {
            assertTrue(has(windows, "Microsoft/Edge/Application/msedge.exe"));
            assertTrue(has(windows, "Google/Chrome/Application/chrome.exe"));
        }
    }

    @Test
    void aNamedBrowserThatIsNotThereMeansNoBrowserNotADifferentOne() {
        WebViewOptions options = new WebViewOptions().setEngine(WebViewOptions.Engine.CHROMIUM).setBrowserPath(Path.of("no", "such", "browser"));
        assertNull(ChromiumLocator.find(options));
        assertFalse(WebViews.isAvailable(options));
        assertTrue(WebViews.describe(options).contains("setBrowserPath"), WebViews.describe(options));
    }

    @Test
    void anEngineThatCannotRunHereIsNotChosen() {
        WebViewOptions options = new WebViewOptions().setEngine(WebViewOptions.Engine.CHROMIUM).setBrowserPath(Path.of("no", "such", "browser"));
        assertNull(WebViews.resolve(options));
        assertFalse(WebViews.supportsNative(options));
    }
}
