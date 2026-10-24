package me.piitex.engine.packager;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A platform an app-image can be built for. The packager can build any of them from any host. */
public enum Platform {
    WINDOWS_X64(Os.WINDOWS, "x64"),
    LINUX_X64(Os.LINUX, "x64"),
    LINUX_ARM64(Os.LINUX, "arm64"),
    MACOS_X64(Os.MACOS, "x64"),
    MACOS_ARM64(Os.MACOS, "arm64");

    public enum Os { WINDOWS, LINUX, MACOS }

    /** LWJGL and RenGL name their natives jars {@code natives-windows}, {@code natives-linux-arm64}, ... */
    private static final Pattern NATIVES_JAR = Pattern.compile("natives-([a-z0-9]+)(-[a-z0-9]+)?\\.jar$");
    /** Skiko names its runtime jars {@code skiko-awt-runtime-windows-x64-<version>.jar}. */
    private static final Pattern SKIKO_JAR = Pattern.compile("-runtime-(windows|linux|macos)-(x64|arm64)-");

    private final Os os;
    private final String arch;

    Platform(Os os, String arch) {
        this.os = os;
        this.arch = arch;
    }

    public Os os() {
        return os;
    }

    public String arch() {
        return arch;
    }

    /** {@code windows-x64}, as used on the command line and in output names. */
    public String id() {
        return os.name().toLowerCase(Locale.ROOT) + "-" + arch;
    }

    /** The Corretto download that holds this platform's {@code jmods}, for the given version or {@code null} for the latest. */
    String correttoUrl(String version) {
        String cpu = arch.equals("x64") ? "x64" : "aarch64";
        if (version == null) {
            String os = this.os.name().toLowerCase(Locale.ROOT);
            return "https://corretto.aws/downloads/latest/amazon-corretto-25-" + cpu + "-" + os + "-jdk." + (this.os == Os.WINDOWS ? "zip" : "tar.gz");
        }
        String base = "https://corretto.aws/downloads/resources/" + version + "/amazon-corretto-" + version + "-";
        return switch (os) {
            case WINDOWS -> base + "windows-" + cpu + "-jdk.zip";
            case LINUX -> base + "linux-" + cpu + ".tar.gz";
            case MACOS -> base + "macosx-" + cpu + ".tar.gz";
        };
    }

    public static Platform parse(String id) {
        for (Platform p : values()) {
            if (p.id().equals(id.toLowerCase(Locale.ROOT))) return p;
        }
        throw new IllegalArgumentException("Unknown platform '" + id + "'. Use one of: " + all());
    }

    public static String all() {
        StringBuilder sb = new StringBuilder();
        for (Platform p : values()) sb.append(sb.isEmpty() ? "" : ", ").append(p.id());
        return sb.toString();
    }

    /** The platform this JVM is running on, or {@code null} if it is not one the packager builds for. */
    public static Platform host() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String cpu = arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64";
        for (Platform p : values()) {
            boolean sameOs = os.contains("win") ? p.os == Os.WINDOWS : os.contains("mac") ? p.os == Os.MACOS : p.os == Os.LINUX;
            if (sameOs && p.arch.equals(cpu)) return p;
        }
        return null;
    }

    /** What a jar in the input folder is for. */
    public sealed interface JarTarget {
        /** Plain code: goes into every image. */
        record Any() implements JarTarget { }
        /** Natives for one platform: goes into that platform's image only. */
        record Only(Platform platform) implements JarTarget { }
        /** Natives for a platform the packager does not build for (32-bit Windows, ...): goes nowhere. */
        record Unsupported() implements JarTarget { }
    }

    /** Sorts a jar by its file name into code for everyone, natives for one platform, or natives for none we build. */
    public static JarTarget classify(String jarName) {
        String name = jarName.toLowerCase(Locale.ROOT);
        Matcher skiko = SKIKO_JAR.matcher(name);
        if (skiko.find()) return only(skiko.group(1), skiko.group(2));

        Matcher natives = NATIVES_JAR.matcher(name);
        if (natives.find()) {
            String os = natives.group(1);
            String suffix = natives.group(2);
            if (!os.equals("windows") && !os.equals("linux") && !os.equals("macos")) return new JarTarget.Unsupported();
            return only(os, suffix == null ? "x64" : suffix.substring(1));
        }
        return new JarTarget.Any();
    }

    private static JarTarget only(String os, String arch) {
        for (Platform p : values()) {
            if (p.os.name().equalsIgnoreCase(os) && p.arch.equals(arch)) return new JarTarget.Only(p);
        }
        return new JarTarget.Unsupported();
    }
}
