package me.piitex.engine.webview.chromium;

import me.piitex.engine.webview.WebViewOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds a Chromium-based browser on the machine: Google Chrome, Microsoft Edge, Chromium, Brave or Vivaldi. The
 * {@link ChromiumBackend} drives it in headless mode, so nothing has to be shipped with an application, but one of them
 * has to be installed. A path can be given in {@link WebViewOptions#setBrowserPath} or the {@code RENGL_BROWSER}
 * environment variable.
 */
public final class ChromiumLocator {
    private ChromiumLocator() {
    }

    /** The browser to use, or null when there is none. */
    public static Path find(WebViewOptions options) {
        Path given = options == null ? null : options.getBrowserPath();
        if (given != null) return Files.isRegularFile(given) ? given : null;
        String env = System.getenv("RENGL_BROWSER");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env.trim());
            return Files.isRegularFile(p) ? p : null;
        }
        for (Path candidate : candidates()) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    public static boolean isInstalled() {
        return find(null) != null;
    }

    /** The places to look, best first. */
    static List<Path> candidates() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<Path> list = new ArrayList<>();
        if (os.contains("windows")) {
            String[] relative = {
                    "Microsoft\\Edge\\Application\\msedge.exe",
                    "Google\\Chrome\\Application\\chrome.exe",
                    "BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                    "Chromium\\Application\\chrome.exe",
                    "Vivaldi\\Application\\vivaldi.exe",
            };
            for (String variable : new String[]{"ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA"}) {
                String root = System.getenv(variable);
                if (root == null || root.isBlank()) continue;
                for (String r : relative) list.add(Path.of(root, r));
            }
        } else if (os.contains("mac")) {
            String[] apps = {
                    "Google Chrome.app/Contents/MacOS/Google Chrome",
                    "Chromium.app/Contents/MacOS/Chromium",
                    "Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                    "Brave Browser.app/Contents/MacOS/Brave Browser",
                    "Vivaldi.app/Contents/MacOS/Vivaldi",
            };
            String home = System.getProperty("user.home", "");
            for (String a : apps) list.add(Path.of("/Applications", a));
            for (String a : apps) list.add(Path.of(home, "Applications", a));
        } else {
            // a snap's browser cannot read the temporary profile folder, so the plain packages come first
            String[] names = {"google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge",
                    "microsoft-edge-stable", "brave-browser", "vivaldi", "chrome"};
            String path = System.getenv("PATH");
            if (path != null) {
                for (String dir : path.split(java.io.File.pathSeparator)) {
                    if (dir.isBlank() || dir.startsWith("/snap/")) continue;
                    for (String n : names) list.add(Path.of(dir, n));
                }
            }
            for (String n : names) list.add(Path.of("/usr/bin", n));
            list.add(Path.of("/opt/google/chrome/chrome"));
            list.add(Path.of("/opt/microsoft/msedge/msedge"));
            list.add(Path.of("/snap/bin/chromium"));
        }
        return list;
    }

    /** Words for a person: which browser is used, or what to install. */
    public static String describe(WebViewOptions options) {
        Path p = find(options);
        if (p != null) return "headless " + p.getFileName() + " (" + p + ")";
        return "No Chrome, Edge, Chromium or Brave browser was found. Install one, or name it with "
                + "WebViewOptions.setBrowserPath or the RENGL_BROWSER environment variable.";
    }
}
