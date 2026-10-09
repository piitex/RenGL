package me.piitex.engine.input;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Whether the operating system is set to a light or a dark appearance, and a callback for when the user changes it.
 * See {@code Engine.getSystemAppearance()} and {@code Engine.onSystemAppearanceChange}.
 * <p>
 * The setting is read the way each platform documents it: {@code defaults read -g AppleInterfaceStyle} on macOS, the
 * {@code AppsUseLightTheme} registry value on Windows, and {@code gsettings} on Linux (GNOME's {@code color-scheme},
 * else a GTK theme with "dark" in its name). An appearance that cannot be read is {@link Mode#LIGHT}.
 * <p>
 * There is no change notification all three share, so a listener makes a background thread read the setting every
 * couple of seconds; a change reaches the listener on the render thread on the next frame.
 */
public final class SystemAppearance {
    /**
     * The two system appearances.
     */
    public enum Mode {
        LIGHT, DARK
    }

    private static final Logger log = LogManager.getLogger("Engine");
    private static final long POLL_MILLIS = 2000;
    private static final Object LOCK = new Object();

    private static Consumer<Mode> listener;       // guarded by LOCK
    private static Mode known;                    // the last value read; guarded by LOCK
    private static Mode announced;                // what the listener last heard (or was started with); guarded by LOCK
    private static Mode pending;                  // a change waiting for the render thread; guarded by LOCK
    private static Thread poller;                 // guarded by LOCK

    private SystemAppearance() {
    }

    /**
     * The appearance now. With a listener set this is the value the background thread last read; otherwise it reads
     * the setting, which starts a short system command (tens of milliseconds), so keep it out of per-frame code.
     */
    public static Mode get() {
        synchronized (LOCK) {
            if (poller != null && known != null) return known;
        }
        return read();
    }

    /**
     * Sets the listener, replacing the last one; null removes it and stops the background thread. It is called with
     * the new appearance on the render thread when the user changes the system setting while the application
     * runs, not with the appearance it starts under (ask {@link #get()} for that).
     */
    public static void onChange(Consumer<Mode> newListener) {
        synchronized (LOCK) {
            listener = newListener;
            if (newListener == null) {
                pending = null;
                if (poller != null) poller.interrupt();
                poller = null;
                return;
            }
            if (poller != null) return;
            known = null;
            announced = null;
            Thread thread = new Thread(SystemAppearance::poll, "SystemAppearance");
            thread.setDaemon(true);
            poller = thread;
            thread.start();
        }
    }

    /**
     * Render thread, once per frame. Hands a change to the listener.
     */
    public static void drain() {
        Consumer<Mode> target;
        Mode change;
        synchronized (LOCK) {
            if (listener == null || pending == null) return;
            target = listener;
            change = pending;
            pending = null;
        }
        try {
            target.accept(change);
        } catch (Throwable t) {
            log.error("System appearance listener threw", t);
        }
    }

    private static void poll() {
        Thread self = Thread.currentThread();
        while (!self.isInterrupted()) {
            Mode now = read();
            synchronized (LOCK) {
                if (poller != self) return;
                known = now;
                if (announced == null) {
                    announced = now; // the starting appearance is not a change
                } else if (announced != now) {
                    announced = now;
                    pending = now;
                }
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * Reads the setting now.
     */
    public static Mode read() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                return parseMac(run("defaults", "read", "-g", "AppleInterfaceStyle"));
            }
            if (os.contains("win")) {
                return parseWindows(run("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme"));
            }
            String scheme = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
            if (scheme != null && !scheme.isBlank() && parseLinux(scheme) == Mode.DARK) return Mode.DARK;
            return parseLinux(run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"));
        } catch (RuntimeException e) {
            log.debug("Could not read the system appearance", e);
            return Mode.LIGHT;
        }
    }

    /**
     * macOS: the command prints {@code Dark} in dark mode and fails (no output) in light mode.
     */
    static Mode parseMac(String output) {
        return output != null && output.trim().equalsIgnoreCase("dark") ? Mode.DARK : Mode.LIGHT;
    }

    /**
     * Windows: {@code reg query} prints {@code AppsUseLightTheme    REG_DWORD    0x0} for dark and {@code 0x1} for light.
     */
    static Mode parseWindows(String output) {
        if (output == null) return Mode.LIGHT;
        for (String line : output.split("\\R")) {
            if (!line.contains("AppsUseLightTheme")) continue;
            String[] parts = line.trim().split("\\s+");
            String value = parts[parts.length - 1];
            return value.equalsIgnoreCase("0x0") || value.equals("0") ? Mode.DARK : Mode.LIGHT;
        }
        return Mode.LIGHT;
    }

    /**
     * Linux: {@code 'prefer-dark'} from {@code color-scheme}, or a theme name such as {@code 'Adwaita-dark'}.
     */
    static Mode parseLinux(String output) {
        if (output == null) return Mode.LIGHT;
        String value = output.trim().toLowerCase(Locale.ROOT);
        return value.contains("dark") ? Mode.DARK : Mode.LIGHT;
    }

    private static String run(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(List.of(command)).redirectErrorStream(true).start();
            process.getOutputStream().close();
            String out;
            try (InputStream in = process.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? out : null;
        } catch (java.io.IOException e) {
            return null; // the program is not on this system
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    /**
     * For tests: removes the listener, the background thread and anything queued.
     */
    public static void reset() {
        onChange(null);
        synchronized (LOCK) {
            known = null;
            announced = null;
            pending = null;
        }
    }
}
