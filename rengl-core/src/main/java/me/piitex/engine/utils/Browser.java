package me.piitex.engine.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Opens a web address in the operating system's default browser, with no AWT dependency: it
 * shells out to {@code open} on macOS, {@code rundll32} on Windows and {@code xdg-open} on Linux.
 * <p>
 * Only {@code http}, {@code https} and {@code mailto} addresses are opened. A string that is not one
 * of those (a {@code file:} path, a custom scheme, an executable) is refused, because a link's text
 * often comes from outside the application and handing it to the OS opener could launch a program.
 * <p>
 * {@link #open(Path)} and {@link #reveal(Path)} do the same for a file or folder on disk: open it with the
 * program the OS has for it, or show it selected in Finder / Explorer. {@link #openAsText(Path)} opens a file in the
 * text editor.
 */
public final class Browser {
    private static final Logger log = LogManager.getLogger("Engine");
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "mailto");

    /**
     * Extensions {@link #open(Path)} will not hand to the OS, because opening them runs a program. A file that
     * came out of an archive is the usual case. {@link #reveal} still shows them.
     */
    private static final Set<String> PROGRAM_EXTENSIONS = Set.of("app", "bat", "cmd", "com", "command", "desktop",
            "exe", "jar", "js", "jse", "lnk", "msi", "pif", "ps1", "reg", "scr", "sh", "vbe", "vbs", "wsf");

    private Browser() {
    }

    /**
     * Opens {@code url} in the default browser. A bare address such as {@code "example.com"} is
     * treated as {@code https://example.com}.
     *
     * @return true if the OS opener was launched; false if the address was refused or no opener
     * exists. It cannot tell whether the browser then loaded the page.
     */
    public static boolean open(String url) {
        URI uri = parse(url);
        if (uri == null) {
            log.warn("Refusing to open '{}': not an http(s) or mailto address", url);
            return false;
        }
        String target = uri.toString();
        String[] command = switch (Platform.getCurrent()) {
            case MACOS -> new String[]{"open", target};
            case WINDOWS -> new String[]{"rundll32", "url.dll,FileProtocolHandler", target};
            case LINUX -> new String[]{"xdg-open", target};
            case OTHER -> null;
        };
        if (command == null) {
            log.warn("No way to open a browser on this platform");
            return false;
        }
        try {
            new ProcessBuilder(command).redirectErrorStream(true).start();
            return true;
        } catch (IOException e) {
            log.warn("Could not launch '{}' to open {}: {}", command[0], target, e.getMessage());
            return false;
        }
    }

    /**
     * Opens a file or folder with the program the OS has for it, as double-clicking it would: a folder opens in
     * Finder / Explorer, a {@code .zip} in the archive program, a picture in the viewer.
     * <p>
     * A program (an {@code .exe}, {@code .bat}, {@code .sh}, {@code .app}, {@code .jar} and so on) is refused, since
     * opening it would run it; the path may have come from an archive. Use {@link #reveal} to show such a file.
     *
     * @return true if the OS opener was launched; false if the path does not exist, is a program, or no opener
     * exists. It cannot tell whether the program then opened the file.
     */
    public static boolean open(Path path) {
        if (path == null || !Files.exists(path)) {
            log.warn("Cannot open '{}': it does not exist", path);
            return false;
        }
        if (isProgram(path)) {
            log.warn("Refusing to open '{}': it is a program", path);
            return false;
        }
        return launch(openCommand(Platform.getCurrent(), path.toAbsolutePath()), path.toString());
    }

    /**
     * Shows a file or folder in Finder / Explorer, selected inside its parent folder. On Linux, where file managers
     * have no common way to select a file, the parent folder is opened.
     *
     * @return true if the OS opener was launched; false if the path does not exist or no opener exists
     */
    public static boolean reveal(Path path) {
        if (path == null || !Files.exists(path)) {
            log.warn("Cannot reveal '{}': it does not exist", path);
            return false;
        }
        return launch(revealCommand(Platform.getCurrent(), path.toAbsolutePath()), path.toString());
    }

    /**
     * Opens a file in the system's text editor, for a file {@link #open(Path)} refuses because opening it would run it
     * ({@code .sh}, {@code .bat}, {@code .js}, {@code .jar}) or one with no program of its own. It uses TextEdit on
     * macOS ({@code open -t}), Notepad on Windows, and the default {@code text/plain} program on Linux (found with
     * {@code xdg-mime}, started with {@code gtk-launch}). Nothing is ever run: the editor is given the file to read.
     * Folders are refused.
     *
     * @return true if an editor was launched; false if the path does not exist, is a folder, or no editor was found
     */
    public static boolean openAsText(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            log.warn("Cannot open '{}' as text: it is not a file", path);
            return false;
        }
        Path absolute = path.toAbsolutePath();
        Platform platform = Platform.getCurrent();
        String[] command = platform == Platform.LINUX
                ? linuxTextCommand(defaultLinuxTextEditor(), absolute)
                : textCommand(platform, absolute);
        return launch(command, path.toString());
    }

    static String[] textCommand(Platform platform, Path path) {
        String target = path.toString();
        return switch (platform) {
            case MACOS -> new String[]{"open", "-t", target};
            case WINDOWS -> new String[]{"notepad.exe", target};
            case LINUX, OTHER -> null;
        };
    }

    /**
     * The command that opens {@code path} in the program whose desktop entry is {@code desktopEntry}, or null if there
     * is none (an empty answer from {@code xdg-mime}).
     */
    static String[] linuxTextCommand(String desktopEntry, Path path) {
        if (desktopEntry == null || desktopEntry.isBlank()) return null;
        return new String[]{"gtk-launch", desktopEntry.strip(), path.toString()};
    }

    private static String defaultLinuxTextEditor() {
        try {
            Process process = new ProcessBuilder("xdg-mime", "query", "default", "text/plain")
                    .redirectErrorStream(true).start();
            String answer = new String(process.getInputStream().readAllBytes()).strip();
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return answer;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean launch(String[] command, String target) {
        if (command == null) {
            log.warn("No way to open '{}' on this platform", target);
            return false;
        }
        try {
            new ProcessBuilder(command).redirectErrorStream(true).start();
            return true;
        } catch (IOException e) {
            log.warn("Could not launch '{}' to open {}: {}", command[0], target, e.getMessage());
            return false;
        }
    }

    /**
     * Whether opening {@code path} would run a program. Folders are never programs, except a macOS {@code .app}.
     */
    static boolean isProgram(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) return false;
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String extension = name.substring(dot + 1);
        if (extension.equals("app")) return true;
        return PROGRAM_EXTENSIONS.contains(extension) && !Files.isDirectory(path);
    }

    static String[] openCommand(Platform platform, Path path) {
        String target = path.toString();
        return switch (platform) {
            case MACOS -> new String[]{"open", target};
            case WINDOWS -> Files.isDirectory(path)
                    ? new String[]{"explorer.exe", target}
                    : new String[]{"rundll32", "url.dll,FileProtocolHandler", target};
            case LINUX -> new String[]{"xdg-open", target};
            case OTHER -> null;
        };
    }

    static String[] revealCommand(Platform platform, Path path) {
        return switch (platform) {
            case MACOS -> new String[]{"open", "-R", path.toString()};
            // Two arguments: Java would quote a single "/select,<path with spaces>" in a way Explorer misreads.
            case WINDOWS -> new String[]{"explorer.exe", "/select,", path.toString()};
            case LINUX -> {
                Path parent = path.getParent();
                yield new String[]{"xdg-open", (parent == null ? path : parent).toString()};
            }
            case OTHER -> null;
        };
    }

    /**
     * The address as a URI if {@link #open} would accept it, or null.
     */
    static URI parse(String url) {
        if (url == null) return null;
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return null;
        if (!trimmed.contains(":")) trimmed = "https://" + trimmed;
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) return null;
            if (!scheme.equalsIgnoreCase("mailto") && (uri.getHost() == null || uri.getHost().isEmpty())) return null;
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
