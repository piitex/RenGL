package me.piitex.engine.packager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        boolean installer = options.containsKey("installer");

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
                System.out.println("  Setup:   " + WindowsSetup.build(app, platform, image, output));
            } else if (archive && installer && platform.os() == Platform.Os.LINUX) {
                System.out.println("  Package: " + DebPackage.build(app, platform, image, output));
            } else if (archive) {
                System.out.println("  Archive: " + builder.archive(image, output));
            }
        }
        System.out.println("\nDone.");
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
