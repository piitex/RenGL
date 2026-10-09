package me.piitex.engine.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Opens a web address in the operating system's default browser, with no AWT dependency: it
 * shells out to {@code open} on macOS, {@code rundll32} on Windows and {@code xdg-open} on Linux.
 * <p>
 * Only {@code http}, {@code https} and {@code mailto} addresses are opened. A string that is not one
 * of those (a {@code file:} path, a custom scheme, an executable) is refused, because a link's text
 * often comes from outside the application and handing it to the OS opener could launch a program.
 */
public final class Browser {
    private static final Logger log = LogManager.getLogger("Engine");
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "mailto");

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
