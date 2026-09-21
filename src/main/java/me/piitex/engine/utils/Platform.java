package me.piitex.engine.utils;

import java.util.Locale;

/** The operating system family the JVM is running on. */
public enum Platform {
    WINDOWS, MACOS, LINUX, OTHER;

    private static final Platform CURRENT = detect(System.getProperty("os.name", ""));

    /** The platform this JVM is running on. Detected once. */
    public static Platform getCurrent() {
        return CURRENT;
    }

    static Platform detect(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return MACOS;
        if (os.contains("nux") || os.contains("nix") || os.contains("aix") || os.contains("bsd")) return LINUX;
        return OTHER;
    }
}
