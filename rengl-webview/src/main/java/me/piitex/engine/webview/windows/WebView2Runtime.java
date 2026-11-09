package me.piitex.engine.webview.windows;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds the WebView2 runtime on the machine. It is the "Evergreen" runtime Windows 11 and recent Windows 10 carry, and
 * the one Microsoft Edge updates, so nothing has to be shipped with an application.
 * <p>
 * The runtime's {@code EmbeddedBrowserWebView.dll} is called directly instead of through Microsoft's
 * {@code WebView2Loader.dll}, which is only a small shim that does the same lookup and calls the same function.
 */
public final class WebView2Runtime {
    private WebView2Runtime() {
    }

    private static final Object LOCK = new Object();
    private static boolean searched;
    private static Path dll;

    /**
     * Whether this is Windows and a runtime is installed.
     */
    public static boolean isInstalled() {
        return dll() != null;
    }

    /**
     * The runtime's version folder name, such as {@code 156.0.4314.58}, or null when there is none.
     */
    public static String version() {
        Path d = dll();
        // ...\Application\<version>\EBWebView\<arch>\EmbeddedBrowserWebView.dll
        return d == null ? null : d.getParent().getParent().getParent().getFileName().toString();
    }

    /** The runtime's library, or null. */
    static Path dll() {
        synchronized (LOCK) {
            if (!searched) {
                searched = true;
                dll = find();
            }
            return dll;
        }
    }

    private static Path find() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) return null;
        String arch = switch (System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "arm64";
            case "x86", "i386" -> "x86";
            default -> "x64";
        };
        List<Path> roots = new ArrayList<>();
        for (String variable : new String[]{"ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA"}) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) roots.add(Path.of(value, "Microsoft", "EdgeWebView", "Application"));
        }
        Path best = null;
        int[] bestVersion = null;
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (DirectoryStream<Path> versions = Files.newDirectoryStream(root)) {
                for (Path folder : versions) {
                    Path candidate = folder.resolve("EBWebView").resolve(arch).resolve("EmbeddedBrowserWebView.dll");
                    int[] version = parse(folder.getFileName().toString());
                    if (version == null || !Files.isRegularFile(candidate)) continue;
                    if (bestVersion == null || compare(version, bestVersion) > 0) {
                        best = candidate;
                        bestVersion = version;
                    }
                }
            } catch (IOException ignored) {
                // an unreadable folder just has no runtime in it
            }
        }
        return best;
    }

    private static int[] parse(String name) {
        String[] parts = name.split("\\.");
        if (parts.length < 2) return null;
        int[] numbers = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) numbers[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return numbers;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
}
