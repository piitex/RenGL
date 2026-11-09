package me.piitex.engine.webview.internal;

import me.piitex.engine.webview.WebViewOptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * The folder a browser keeps its cookies and cache in. Unless the application names one, it is a fresh temporary
 * folder that is deleted when the view closes, so a login portal keeps nothing.
 */
public final class Profiles {
    private Profiles() {
    }

    /** A profile folder and whether it is temporary (deleted when the view closes). */
    public record Profile(Path folder, boolean temporary) {
        /** Call when the browser has closed. Deletes a temporary folder at once if it can be, else retries while the browser's processes let go of it. */
        public void release() {
            if (temporary && !deleteTree(folder)) deleteLater(folder);
        }
    }

    public static Profile open(WebViewOptions options) {
        Path profile = options.getProfile();
        try {
            if (profile == null) {
                sweepOld();
                return new Profile(Files.createTempDirectory("rengl-webview-"), true);
            }
            Files.createDirectories(profile);
            return new Profile(profile, false);
        } catch (IOException e) {
            throw new IllegalStateException("Could not make the profile folder", e);
        }
    }

    private static void deleteLater(Path folder) {
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                if (deleteTree(folder)) return;
            }
        }, "RenGL-WebView-cleanup");
        t.setDaemon(true);
        t.start();
    }

    public static boolean deleteTree(Path folder) {
        if (!Files.exists(folder)) return true;
        try (var walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return true;
        } catch (IOException | UncheckedIOException e) {
            return false;
        }
    }

    /** Removes profiles earlier runs left behind (a run that was killed), once they are a day old. */
    private static void sweepOld() {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        long cutoff = System.currentTimeMillis() - 24L * 3600_000L;
        try (var children = Files.newDirectoryStream(temp, "rengl-webview-*")) {
            for (Path p : children) {
                try {
                    if (Files.isDirectory(p) && Files.getLastModifiedTime(p).toMillis() < cutoff) deleteTree(p);
                } catch (IOException ignored) {
                    // not ours to worry about
                }
            }
        } catch (IOException ignored) {
            // no temp folder to sweep
        }
    }
}
