package me.piitex.engine.packager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Lays out one platform's app-image, in the same shape jpackage's {@code app-image} has:
 * <pre>
 * Windows   MyApp/MyApp.exe, app/, runtime/
 * Linux     MyApp/bin/MyApp, lib/app/, lib/runtime/
 * macOS     MyApp.app/Contents/MacOS/MyApp, Contents/app/, Contents/runtime/, Contents/Info.plist
 * </pre>
 */
final class ImageBuilder {
    private static final int EXECUTABLE = 0755;
    private static final int PLAIN = 0644;

    private final AppSpec app;
    private final Platform platform;
    private final List<Path> jars;
    private final Path runtime;

    /**
     * @param jars    the jars for this platform, already filtered
     * @param runtime a jlink output for this platform; it is moved into the image
     */
    ImageBuilder(AppSpec app, Platform platform, List<Path> jars, Path runtime) {
        this.app = app;
        this.platform = platform;
        this.jars = jars;
        this.runtime = runtime;
    }

    /** Builds the image inside {@code platformDir} and returns the folder to ship ({@code MyApp} or {@code MyApp.app}). */
    Path build(Path platformDir) throws IOException {
        return switch (platform.os()) {
            case WINDOWS -> windows(platformDir);
            case LINUX -> linux(platformDir);
            case MACOS -> macos(platformDir);
        };
    }

    private Path windows(Path platformDir) throws IOException {
        Path root = Files.createDirectories(platformDir.resolve(app.name()));
        Path appDir = Files.createDirectories(root.resolve("app"));
        copyJars(appDir);

        Path exe = root.resolve(app.name() + ".exe");
        try (InputStream launcher = ImageBuilder.class.getResourceAsStream("launcher-" + platform.id() + ".exe")) {
            if (launcher == null) throw new IOException("This packager has no launcher for " + platform.id() + " (launcher-" + platform.id() + ".exe is missing)");
            Files.copy(launcher, exe);
        }

        Path ico = app.icons().get("ico");
        if (WindowsIcon.available()) {
            try {
                if (ico != null) WindowsIcon.apply(exe, ico);
                WindowsVersion.apply(exe, new WindowsVersion.Info(app.name(), app.version(), app.vendor(), app.description(), exe.getFileName().toString()));
            } catch (Throwable t) {
                throw new IOException("Could not set the icon and version of the .exe: " + t.getMessage(), t);
            }
        } else {
            System.out.println("  Note: the .exe icon and version can only be set when building on Windows; the defaults were left in place.");
        }

        StringBuilder cfg = new StringBuilder("main-class=").append(app.mainClass()).append('\n');
        for (Path jar : jars) cfg.append("cp=").append(jar.getFileName()).append('\n');
        for (String option : javaOptions()) cfg.append("java-option=").append(option).append('\n');
        Files.writeString(appDir.resolve(app.name() + ".cfg"), cfg, StandardCharsets.UTF_8);

        move(runtime, root.resolve("runtime"));
        return root;
    }

    private Path linux(Path platformDir) throws IOException {
        Path root = Files.createDirectories(platformDir.resolve(app.name()));
        Path appDir = Files.createDirectories(root.resolve("lib").resolve("app"));
        copyJars(appDir);
        move(runtime, root.resolve("lib").resolve("runtime"));

        Path icon = app.icons().get("png");
        if (icon != null) Files.copy(icon, root.resolve("lib").resolve(app.name() + ".png"), StandardCopyOption.REPLACE_EXISTING);

        Files.createDirectories(root.resolve("bin"));
        Files.writeString(root.resolve("bin").resolve(app.name()), script("../lib"), StandardCharsets.UTF_8);
        return root;
    }

    private Path macos(Path platformDir) throws IOException {
        Path root = Files.createDirectories(platformDir.resolve(app.name() + ".app"));
        Path contents = Files.createDirectories(root.resolve("Contents"));
        Path appDir = Files.createDirectories(contents.resolve("app"));
        copyJars(appDir);
        move(runtime, contents.resolve("runtime"));

        String iconFile = "";
        Path icns = app.icons().get("icns");
        if (icns != null) {
            Files.createDirectories(contents.resolve("Resources"));
            Files.copy(icns, contents.resolve("Resources").resolve(app.name() + ".icns"), StandardCopyOption.REPLACE_EXISTING);
            iconFile = app.name() + ".icns";
        }
        Files.writeString(contents.resolve("Info.plist"), infoPlist(iconFile), StandardCharsets.UTF_8);

        Files.createDirectories(contents.resolve("MacOS"));
        Files.writeString(contents.resolve("MacOS").resolve(app.name()), script(".."), StandardCharsets.UTF_8);
        return root;
    }

    private List<String> javaOptions() {
        List<String> options = new ArrayList<>(app.javaOptions());
        // GLFW has to run on the first thread on macOS, and so does Vulkan through MoltenVK.
        if (platform.os() == Platform.Os.MACOS) options.add("-XstartOnFirstThread");
        options.add("--enable-native-access=ALL-UNNAMED");
        return options;
    }

    /** The shell launcher for Linux and macOS. It execs the bundled java, so the process is the app: Dock, Apple events and signals all reach it. */
    private String script(String baseFromScript) {
        StringBuilder classPath = new StringBuilder();
        for (Path jar : jars) classPath.append(classPath.isEmpty() ? "" : ":").append("$BASE/app/").append(jar.getFileName());
        StringBuilder options = new StringBuilder();
        for (String option : javaOptions()) options.append(' ').append(quote(option.replace("$APPDIR", "$BASE/app").replace("$ROOTDIR", "$BASE")));

        return "#!/bin/sh\n"
                + "# Generated by the RenGL packager.\n"
                + "SELF=\"$0\"\n"
                + "while [ -h \"$SELF\" ]; do\n"
                + "  LINK=$(readlink \"$SELF\")\n"
                + "  case \"$LINK\" in /*) SELF=\"$LINK\" ;; *) SELF=\"$(dirname \"$SELF\")/$LINK\" ;; esac\n"
                + "done\n"
                + "BASE=\"$(cd \"$(dirname \"$SELF\")/" + baseFromScript + "\" && pwd)\"\n"
                + "exec \"$BASE/runtime/bin/java\" -Djpackage.app-path=\"$SELF\"" + options
                + " -cp \"" + classPath + "\" " + app.mainClass() + " \"$@\"\n";
    }

    private static String quote(String value) {
        // Double quotes keep $BASE expandable; escape what would end or change them.
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`") + "\"";
    }

    private String infoPlist(String iconFile) {
        StringBuilder p = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                """);
        entry(p, "CFBundleName", app.name());
        entry(p, "CFBundleDisplayName", app.name());
        entry(p, "CFBundleIdentifier", app.identifier());
        entry(p, "CFBundleExecutable", app.name());
        entry(p, "CFBundleShortVersionString", app.version());
        entry(p, "CFBundleVersion", app.version());
        entry(p, "CFBundlePackageType", "APPL");
        entry(p, "CFBundleInfoDictionaryVersion", "6.0");
        if (!iconFile.isEmpty()) entry(p, "CFBundleIconFile", iconFile);
        if (!app.vendor().isEmpty()) entry(p, "NSHumanReadableCopyright", "Copyright " + app.vendor());
        p.append("  <key>NSHighResolutionCapable</key><true/>\n");
        p.append("  <key>LSMinimumSystemVersion</key><string>11.0</string>\n");
        if (!app.fileAssociations().isEmpty()) {
            p.append("  <key>CFBundleDocumentTypes</key>\n  <array>\n");
            for (AppSpec.FileAssociation a : app.fileAssociations()) {
                p.append("    <dict>\n");
                p.append("      <key>CFBundleTypeName</key><string>").append(xml(a.description())).append("</string>\n");
                p.append("      <key>CFBundleTypeRole</key><string>Editor</string>\n");
                p.append("      <key>CFBundleTypeExtensions</key><array><string>").append(xml(a.extension())).append("</string></array>\n");
                if (!a.mimeType().isEmpty()) {
                    p.append("      <key>CFBundleTypeMIMETypes</key><array><string>").append(xml(a.mimeType())).append("</string></array>\n");
                }
                p.append("    </dict>\n");
            }
            p.append("  </array>\n");
        }
        return p.append("</dict>\n</plist>\n").toString();
    }

    private static void entry(StringBuilder p, String key, String value) {
        p.append("  <key>").append(key).append("</key><string>").append(xml(value)).append("</string>\n");
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void copyJars(Path appDir) throws IOException {
        for (Path jar : jars) Files.copy(jar, appDir.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        for (Path content : app.contentDirs()) copyTree(content, appDir);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> tree = Files.walk(from)) {
            for (Path source : tree.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.move(from, to);
    }

    // ---- archives -------------------------------------------------------------------------------------------------

    /** Packs the image into one file to hand out: a {@code .zip} for Windows, a {@code .tar.gz} (with executable bits kept) for the others. */
    Path archive(Path image, Path outputDir) throws IOException {
        String base = app.name() + "-" + app.version() + "-" + platform.id();
        if (platform.os() == Platform.Os.WINDOWS) {
            Path zip = outputDir.resolve(base + ".zip");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                for (Path file : files(image)) {
                    out.putNextEntry(new ZipEntry(entryName(image, file)));
                    Files.copy(file, out);
                    out.closeEntry();
                }
            }
            return zip;
        }
        Path tar = outputDir.resolve(base + ".tar.gz");
        try (Tar.Writer out = new Tar.Writer(new GZIPOutputStream(Files.newOutputStream(tar), 1 << 16))) {
            String top = image.getFileName().toString();
            out.directory(top, EXECUTABLE);
            try (Stream<Path> walk = Files.walk(image)) {
                for (Path path : (Iterable<Path>) walk.skip(1).sorted()::iterator) {
                    String name = entryName(image, path);
                    if (Files.isDirectory(path)) out.directory(name, EXECUTABLE);
                    else out.file(name, path, isExecutable(image, path) ? EXECUTABLE : PLAIN);
                }
            }
        }
        return tar;
    }

    private static List<Path> files(Path image) throws IOException {
        try (Stream<Path> walk = Files.walk(image)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static String entryName(Path image, Path file) {
        return image.getFileName() + "/" + image.relativize(file).toString().replace('\\', '/');
    }

    /** A Windows host cannot record Unix permissions on disk, so decide them here from where the file is. */
    static boolean isExecutable(Path image, Path file) {
        String rel = image.relativize(file).toString().replace('\\', '/');
        String name = file.getFileName().toString();
        return rel.contains("runtime/bin/")
                || rel.endsWith("MacOS/" + name)
                || rel.startsWith("bin/")
                || name.equals("jspawnhelper") || name.equals("jexec")
                || name.endsWith(".so") || name.endsWith(".dylib");
    }
}
