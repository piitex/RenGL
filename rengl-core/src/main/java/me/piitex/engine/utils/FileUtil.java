package me.piitex.engine.utils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.function.Function;

/**
 * Cross-platform locations for the directories an application usually needs: the user's home, the
 * current working directory, where the application itself lives, and the per-user data folders.
 * <p>
 * The data folders follow each OS's convention so files end up where users expect them:
 * <pre>
 *                  getAppData                      getLocalAppData
 * Windows   %APPDATA%  (Roaming)         %LOCALAPPDATA%
 * macOS     ~/Library/Application Support  (same)
 * Linux     $XDG_CONFIG_HOME or ~/.config  $XDG_DATA_HOME or ~/.local/share
 * </pre>
 * Use {@code getAppData} for settings that should follow the user and {@code getLocalAppData} for bulky or
 * machine-specific data (caches, saves you do not want roamed). All methods return absolute paths.
 * The plain getters never touch the disk; the overloads taking an application name create the directory.
 */
public final class FileUtil {
    private FileUtil() {
    }

    /**
     * The user's home directory.
     */
    public static Path getHome() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("The JVM did not report a user home directory");
        }
        return Path.of(home).toAbsolutePath().normalize();
    }

    /**
     * The directory the process was started from ({@code user.dir}), which can differ from where the app is installed.
     */
    public static Path getWorkingDirectory() {
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /**
     * The directory the application was loaded from: the folder holding the jar, or the classes folder when
     * running from an IDE or {@code mvn} ({@code target/classes}). Falls back to {@link #getWorkingDirectory()}
     * if the location cannot be determined.
     */
    public static Path getApplicationDirectory() {
        try {
            CodeSource source = FileUtil.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
                return Files.isDirectory(location) ? location : location.getParent();
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // fall through to the working directory
        }
        return getWorkingDirectory();
    }

    /**
     * The per-user, roaming settings folder for this OS (see the class table).
     */
    public static Path getAppData() {
        return resolveAppData(Platform.getCurrent(), System::getenv, getHome(), false);
    }

    /**
     * The per-user, machine-local data folder for this OS (see the class table).
     */
    public static Path getLocalAppData() {
        return resolveAppData(Platform.getCurrent(), System::getenv, getHome(), true);
    }

    /**
     * The system temporary directory.
     */
    public static Path getTempDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
    }

    /**
     * {@code getAppData()/appName}, created if it does not exist.
     */
    public static Path getAppData(String appName) throws IOException {
        return Files.createDirectories(getAppData().resolve(checkName(appName)));
    }

    /**
     * {@code getLocalAppData()/appName}, created if it does not exist.
     */
    public static Path getLocalAppData(String appName) throws IOException {
        return Files.createDirectories(getLocalAppData().resolve(checkName(appName)));
    }

    // Package-private so every platform's rules can be tested from any OS.
    static Path resolveAppData(Platform platform, Function<String, String> env, Path home, boolean local) {
        switch (platform) {
            case WINDOWS -> {
                Path dir = absoluteEnv(env, local ? "LOCALAPPDATA" : "APPDATA");
                return dir != null ? dir : home.resolve("AppData").resolve(local ? "Local" : "Roaming");
            }
            case MACOS -> {
                return home.resolve("Library").resolve("Application Support");
            }
            default -> {
                // The XDG spec says a relative value must be ignored.
                Path dir = absoluteEnv(env, local ? "XDG_DATA_HOME" : "XDG_CONFIG_HOME");
                return dir != null ? dir : home.resolve(local ? ".local/share" : ".config");
            }
        }
    }

    private static Path absoluteEnv(Function<String, String> env, String name) {
        String value = env.apply(name);
        if (value == null || value.isBlank()) return null;
        try {
            Path p = Path.of(value);
            return p.isAbsolute() ? p.normalize() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String checkName(String appName) {
        if (appName == null || appName.isBlank() || appName.equals(".") || appName.equals("..")
                || appName.contains("/") || appName.contains("\\")) {
            throw new IllegalArgumentException("Invalid application name: '" + appName + "'");
        }
        return appName;
    }
}
