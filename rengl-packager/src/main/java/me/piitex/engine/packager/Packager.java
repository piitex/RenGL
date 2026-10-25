package me.piitex.engine.packager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Builds app-images (a native launcher, a bundled Java runtime and your jars) for Windows, macOS and Linux, from any of
 * them, without jpackage. Run {@code java -jar rengl-packager-<version>.jar --help}.
 */
public final class Packager {
    private static final String MARKER = ".rengl-app-image";

    private static final String HELP = """
            RenGL packager: builds app-images for Windows, macOS and Linux without jpackage.

            Required
              --name <name>            App name; also the launcher's file name
              --input <dir>            Folder of jars: your app and every dependency, with the natives jars of
                                       every platform you build for (jars are picked per platform by file name)
              --main-jar <file>        Your app's jar, in the input folder
              --main-class <class>     The class with main()

            Optional
              --platform <list>        Comma-separated: %s, or "all". Default: this machine's
              --output <dir>           Where to write. Default: app-images
              --app-version <x.y.z>    Default: 1.0.0
              --vendor <text>          --description <text>
              --identifier <id>        macOS bundle id. Default: me.rengl.<name>
              --icon <file>            .ico for Windows, .icns for macOS, .png for Linux; repeat for several.
                                       icns=<file.png> turns a square 128 to 1024 px PNG into the macOS icon
              --java-option <opt>      Extra JVM option for every platform; repeat. $APPDIR expands to the jar folder
              --add-modules <list>     Extra Java modules for the bundled runtime, on top of what jdeps finds
              --modules <list>         Exactly these Java modules (skips jdeps), e.g. java.se,jdk.unsupported
              --content <dir>          Folder of files to ship beside the jars (assets, default data); its contents are
                                       copied into every platform's app folder. Repeat for several
              --file-association <e>   Extension to register on macOS, as ext[,mime[,description]]; repeat
              --jdk <platform>=<path>  Use this JDK for a platform instead of downloading Corretto's jmods; repeat
              --installer              Write an installer instead of the .zip / .tar.gz where there is one: a setup.exe
                                       for Windows (folder, desktop and Start menu shortcuts, uninstaller; run it with
                                       /S for a silent install) and a .deb for Linux. macOS keeps its .tar.gz
              --installer-ui <dir>     Like --installer, and the Windows setup shows a RenGL window instead of the plain
                                       Windows one. <dir> is the folder of jars the rengl-packager module builds
                                       (rengl-packager/target/ui-input). The window runs on the app's own Java runtime
                                       when the app is built from the same RenGL jars; otherwise it carries a runtime of
                                       its own, about 50 MB more. The setup falls back to the plain window on a computer
                                       that cannot open it
              --no-archive             Leave the folders; do not write the .zip / .tar.gz files (or installers)
              --help
            """.formatted(Platform.all());

    private Packager() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Run with --help for the options.");
            System.exit(2);
        } catch (FileSystemException e) {
            // The message of these is only the path; say what went wrong, and the usual reason on Windows.
            System.err.println("Error: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + (e.getReason() == null ? "" : " (" + e.getReason() + ")"));
            System.err.println("A file in the output folder is probably open in another program: a running copy of the app, its setup, or a virus scan. Close it and build again.");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        Map<String, List<String>> options = parse(args);
        if (options.containsKey("help") || args.length == 0) {
            System.out.println(HELP);
            return;
        }

        String name = required(options, "name");
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9 ._-]*")) throw new IllegalArgumentException("--name may only use letters, digits, space, '.', '_' and '-'");
        Path input = Path.of(required(options, "input")).toAbsolutePath();
        if (!Files.isDirectory(input)) throw new IllegalArgumentException("--input " + input + " is not a folder");
        String mainJar = required(options, "main-jar");
        if (!Files.isRegularFile(input.resolve(mainJar))) throw new IllegalArgumentException(mainJar + " is not in " + input);

        AppSpec app = new AppSpec(name,
                single(options, "app-version", "1.0.0"),
                single(options, "vendor", ""),
                single(options, "description", name),
                single(options, "identifier", "me.rengl." + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")),
                input, mainJar, required(options, "main-class"),
                options.getOrDefault("java-option", List.of()),
                icons(options.getOrDefault("icon", List.of())),
                options.getOrDefault("file-association", List.of()).stream().map(Packager::association).toList(),
                contentDirs(options.getOrDefault("content", List.of())));

        List<Platform> platforms = platforms(single(options, "platform", ""));
        Path output = Path.of(single(options, "output", "app-images")).toAbsolutePath();
        boolean archive = !options.containsKey("no-archive");
        Path installerUi = installerUi(options);
        boolean installer = options.containsKey("installer") || installerUi != null;

        Map<Platform, Path> jdks = new EnumMap<>(Platform.class);
        for (String entry : options.getOrDefault("jdk", List.of())) {
            int eq = entry.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("--jdk expects <platform>=<path>, got '" + entry + "'");
            jdks.put(Platform.parse(entry.substring(0, eq)), Path.of(entry.substring(eq + 1)).toAbsolutePath());
        }
        Jdks jdkFinder = new Jdks(jdks, Path.of(System.getProperty("user.home"), ".rengl", "jdks"));

        List<Path> allJars = jars(input, mainJar);
        List<String> modules = modules(options, allJars);
        System.out.println("Java modules in the runtime: " + String.join(", ", modules));

        Files.createDirectories(output);
        for (Platform platform : platforms) {
            System.out.println("\n== " + platform.id() + " ==");
            List<Path> jars = jarsFor(allJars, platform);
            Path platformDir = freshDir(output.resolve(platform.id()));

            Path jmods = jdkFinder.jmods(platform);
            Path runtime = platformDir.resolveSibling(platform.id() + ".runtime-tmp");
            deleteTree(runtime);
            System.out.println("  Building the Java runtime (jlink)");
            Tools.jlink(jmods, modules, runtime);

            ImageBuilder builder = new ImageBuilder(app, platform, jars, runtime);
            Path image = builder.build(platformDir);
            System.out.println("  Image:   " + image);
            if (archive && installer && platform.os() == Platform.Os.WINDOWS) {
                Path uiDir = null;
                try {
                    WindowsSetup.Window window = null;
                    if (installerUi != null) {
                        uiDir = output.resolve(platform.id() + ".ui");
                        window = installerWindow(app, installerUi, platform, jars, modules, jdkFinder, uiDir);
                    }
                    System.out.println("  Setup:   " + WindowsSetup.build(app, platform, image, window, output));
                } finally {
                    if (uiDir != null) deleteTree(uiDir);
                }
            } else if (archive && installer && platform.os() == Platform.Os.LINUX) {
                System.out.println("  Package: " + DebPackage.build(app, platform, image, output));
            } else if (archive) {
                System.out.println("  Archive: " + builder.archive(image, output));
            }
        }
        System.out.println("\nDone.");
    }

    // ---- the installer window -------------------------------------------------------------------------------------

    private static final String INSTALLER_JAR_SUFFIX = "-ui.jar";   // rengl-packager-<version>-ui.jar

    private static Path installerUi(Map<String, List<String>> options) {
        if (!options.containsKey("installer-ui")) return null;
        Path dir = Path.of(required(options, "installer-ui")).toAbsolutePath();
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("--installer-ui " + dir + " is not a folder. Build the rengl-packager module first (mvnw install in the RenGL folder)");
        installerJar(dir);
        return dir;
    }

    private static String installerJar(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(INSTALLER_JAR_SUFFIX))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("--installer-ui " + dir + " holds no <version>" + INSTALLER_JAR_SUFFIX + ". It should be the rengl-packager module's target/ui-input folder"));
        } catch (IOException e) {
            throw new IllegalArgumentException("--installer-ui " + dir + " cannot be read: " + e.getMessage());
        }
    }

    /**
     * The installer window's files. When the app is built from the very jars the window needs (a RenGL app, with the same
     * engine build) and its runtime has the modules the window needs, the window runs on the app's own runtime and the
     * setup carries only its launcher and jar. Otherwise it gets an app-image of its own, with a Java runtime of its own,
     * which makes the setup about 50 MB bigger.
     */
    private static WindowsSetup.Window installerWindow(AppSpec app, Path input, Platform platform, List<Path> appJars, List<String> appModules,
                                                       Jdks jdkFinder, Path dir) throws Exception {
        String mainJar = installerJar(input);
        List<Path> allJars = jars(input, mainJar);
        List<Path> uiJars = jarsFor(allJars, platform);
        List<String> modules = modules(Map.of(), allJars);

        String problem = sharingProblem(uiJars, mainJar, appJars, modules, appModules);
        if (problem == null) {
            System.out.println("  Installer window: runs on the app's own Java runtime");
            return WindowsSetup.Window.shared(input.resolve(mainJar), uiJars.stream().map(jar -> jar.getFileName().toString()).toList());
        }
        System.out.println("  Installer window: carries a Java runtime of its own (" + problem + ")");

        AppSpec ui = new AppSpec(WindowsSetup.UI_NAME, app.version(), app.vendor(), app.name() + " Setup", app.identifier() + ".installer",
                input, mainJar, WindowsSetup.INSTALLER_MAIN_CLASS, List.of(), Map.of(), List.of(), List.of());
        System.out.println("  Building the installer window's Java runtime (jlink): " + String.join(", ", modules));
        Path runtime = dir.resolveSibling(dir.getFileName() + "-runtime-tmp");
        deleteTree(runtime);
        Tools.jlink(jdkFinder.jmods(platform), modules, runtime);
        return WindowsSetup.Window.own(new ImageBuilder(ui, platform, uiJars, runtime).build(freshDir(dir)));
    }

    /** Why the installer window cannot run on the app's runtime and jars, or null when it can. */
    static String sharingProblem(List<Path> uiJars, String uiMainJar, List<Path> appJars, List<String> uiModules, List<String> appModules) {
        for (Path jar : uiJars) {
            String name = jar.getFileName().toString();
            if (name.equals(uiMainJar)) continue;
            Path same = appJars.stream().filter(candidate -> candidate.getFileName().toString().equals(name)).findFirst().orElse(null);
            if (same == null) return name + " is not one of the app's jars";
            try {
                if (!sameContent(jar, same)) return name + " in the app is a different build";
            } catch (IOException e) {
                return name + " could not be compared: " + e.getMessage();
            }
        }
        for (String module : uiModules) {
            if (!appModules.contains(module)) return "the app's runtime has no " + module + " module";
        }
        return null;
    }

    private static boolean sameContent(Path a, Path b) throws IOException {
        return Files.size(a) == Files.size(b) && Arrays.equals(sha256(a), sha256(b));
    }

    private static byte[] sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- arguments ------------------------------------------------------------------------------------------------

    private static final Set<String> FLAGS = Set.of("help", "no-archive", "installer");

    private static Map<String, List<String>> parse(String[] args) {
        Map<String, List<String>> options = new TreeMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) throw new IllegalArgumentException("Unexpected '" + arg + "'");
            String key = arg.substring(2);
            if (FLAGS.contains(key)) {
                options.computeIfAbsent(key, k -> new ArrayList<>());
                continue;
            }
            if (i + 1 >= args.length) throw new IllegalArgumentException("--" + key + " needs a value");
            options.computeIfAbsent(key, k -> new ArrayList<>()).add(args[++i]);
        }
        return options;
    }

    private static String required(Map<String, List<String>> options, String key) {
        List<String> values = options.get(key);
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("--" + key + " is required");
        return values.getLast();
    }

    private static String single(Map<String, List<String>> options, String key, String fallback) {
        List<String> values = options.get(key);
        return values == null || values.isEmpty() ? fallback : values.getLast();
    }

    private static List<Platform> platforms(String value) {
        if (value.isEmpty()) {
            Platform host = Platform.host();
            if (host == null) throw new IllegalArgumentException("Cannot tell which platform this is; pass --platform");
            return List.of(host);
        }
        if (value.equals("all")) return List.of(Platform.values());
        return new LinkedHashSet<>(Stream.of(value.split(",")).map(String::strip).map(Platform::parse).toList()).stream().toList();
    }

    private static Map<String, Path> icons(List<String> files) throws IOException {
        Map<String, Path> icons = new TreeMap<>();
        for (String file : files) {
            // "icns=picture.png" says what the icon is for when the extension cannot: a PNG that becomes the macOS icon.
            String kind = null;
            int equals = file.indexOf('=');
            if (equals > 0 && Set.of("ico", "icns", "png").contains(file.substring(0, equals))) {
                kind = file.substring(0, equals);
                file = file.substring(equals + 1);
            }
            Path path = Path.of(file).toAbsolutePath();
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Icon " + path + " does not exist");
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            String extension = name.substring(name.lastIndexOf('.') + 1);
            if (!Set.of("ico", "icns", "png").contains(extension)) throw new IllegalArgumentException("Icon must be .ico, .icns or .png: " + file);
            if ("icns".equals(kind) && extension.equals("png")) {
                path = Icns.fromPng(path);
                extension = "icns";
            } else if (kind != null && !kind.equals(extension)) {
                throw new IllegalArgumentException("Icon " + file + " is not a ." + kind + " file");
            }
            icons.put(extension, path);
        }
        return icons;
    }

    private static List<Path> contentDirs(List<String> folders) {
        List<Path> dirs = new ArrayList<>();
        for (String folder : folders) {
            Path dir = Path.of(folder).toAbsolutePath();
            if (!Files.isDirectory(dir)) throw new IllegalArgumentException("--content " + dir + " is not a folder");
            dirs.add(dir);
        }
        return dirs;
    }

    private static AppSpec.FileAssociation association(String spec) {
        String[] parts = spec.split(",", 3);
        String extension = parts[0].strip().replaceFirst("^\\.", "");
        if (extension.isEmpty()) throw new IllegalArgumentException("--file-association needs an extension");
        return new AppSpec.FileAssociation(extension, parts.length > 1 ? parts[1].strip() : "", parts.length > 2 ? parts[2].strip() : extension.toUpperCase(Locale.ROOT) + " file");
    }

    // ---- jars and modules -----------------------------------------------------------------------------------------

    private static List<Path> jars(Path input, String mainJar) throws IOException {
        try (Stream<Path> files = Files.list(input)) {
            List<Path> jars = new ArrayList<>(files.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().toList());
            // The app's own jar first, so its resources win over a dependency's.
            jars.sort((a, b) -> Boolean.compare(b.getFileName().toString().equals(mainJar), a.getFileName().toString().equals(mainJar)));
            return jars;
        }
    }

    private static List<Path> jarsFor(List<Path> jars, Platform platform) {
        return jars.stream().filter(jar -> switch (Platform.classify(jar.getFileName().toString())) {
            case Platform.JarTarget.Any any -> true;
            case Platform.JarTarget.Only only -> only.platform() == platform;
            case Platform.JarTarget.Unsupported unsupported -> false;
        }).toList();
    }

    private static List<String> modules(Map<String, List<String>> options, List<Path> jars) {
        if (options.containsKey("modules")) return List.of(single(options, "modules", "").split(","));

        List<String> found = Tools.detectModules(jars.stream().filter(jar -> Platform.classify(jar.getFileName().toString()) instanceof Platform.JarTarget.Any).toList());
        Set<String> modules = new LinkedHashSet<>();
        if (found == null) {
            System.out.println("jdeps could not work out the modules; using java.se. Pass --modules to choose.");
            modules.add("java.se");
        } else {
            modules.addAll(found);
        }
        // Not visible to jdeps: LWJGL uses Unsafe, and text files in other charsets need jdk.charsets.
        modules.add("jdk.unsupported");
        modules.add("jdk.charsets");
        for (String extra : options.getOrDefault("add-modules", List.of())) modules.addAll(List.of(extra.split(",")));
        return List.copyOf(modules);
    }

    // ---- folders --------------------------------------------------------------------------------------------------

    /** Empties a folder this tool made on an earlier run. Refuses any other folder, so a typo in --output cannot delete anything. */
    private static Path freshDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            if (!Files.exists(dir.resolve(MARKER))) throw new IOException(dir + " exists and was not made by the packager; remove it or choose another --output");
            deleteTree(dir);
        }
        Files.createDirectories(dir);
        Files.createFile(dir.resolve(MARKER));
        return dir;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
}
