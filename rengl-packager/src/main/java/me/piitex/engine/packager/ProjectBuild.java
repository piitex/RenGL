package me.piitex.engine.packager;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds a Maven project's app-images in one step: builds the project with Maven, gathers its jars and every platform's
 * natives into {@code target/input}, and runs the packager on them. It is {@code java -jar rengl-packager.jar project};
 * {@code package-app.ps1} (PowerShell) and {@code package-app.sh} (any POSIX shell) only find this jar and start it, so
 * a project builds the same way on Windows, macOS and Linux.
 */
final class ProjectBuild {
    static final String COMMAND = "project";

    private static final String CONFIG_NAME = "rengl-package.properties";
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final Path REPOSITORY = Path.of(System.getProperty("user.home"), ".m2", "repository", "io", "github", "piitex");
    /** Keeps Maven's own JVM from printing its native-access and Unsafe warnings over the build's output. */
    private static final String MAVEN_OPTS = "--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow";

    /** The engine's natives artifact for each platform: its jar holds Skiko's runtime and its pom brings LWJGL's natives. */
    private static final Map<Platform, String> NATIVES = new EnumMap<>(Map.of(
            Platform.WINDOWS_X64, "rengl-natives-windows",
            Platform.LINUX_X64, "rengl-natives-linux",
            Platform.LINUX_ARM64, "rengl-natives-linux-arm64",
            Platform.MACOS_X64, "rengl-natives-macos",
            Platform.MACOS_ARM64, "rengl-natives-macos-arm64"));

    /** The settings, spelled as in the settings file. On the command line they are -Name, --name or --main-class alike. */
    private static final List<String> SETTINGS = List.of("name", "mainClass", "mainJar", "platform", "installer", "noArchive",
            "icon", "version", "vendor", "description", "identifier", "addModules", "content", "fileAssociation", "shellVerb",
            "documentTypes", "output");
    /** Settings given once per value, on several lines or several times (their values hold commas and bars, so are not split). */
    private static final Set<String> REPEATING = Set.of("fileAssociation", "shellVerb");
    /** Settings that are comma separated lists. */
    private static final Set<String> LISTS = Set.of("icon", "content");
    /** Settings that are paths in the project. */
    private static final Set<String> PATHS = Set.of("mainJar", "icon", "content", "documentTypes", "output");

    private static final Pattern ENGINE_JAR = Pattern.compile("^(RenGL|rengl-core)-(\\d+(\\.\\d+)+.*?)(-natives-.+)?\\.jar$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_MAIN_JAR = Pattern.compile("-(sources|javadoc|tests)\\.jar$|^original-", Pattern.CASE_INSENSITIVE);

    private static final String HELP = """
            RenGL packager, project mode: builds a Maven project and packages it, in one step.

              java -jar rengl-packager.jar project -Init       write rengl-package.properties and a launcher, then stop
              java -jar rengl-packager.jar project             build with the settings in rengl-package.properties

            package-app.ps1 and package-app.sh in the RenGL folder's rengl-packager do the same and find the jar for you.
            A setting on the command line wins over the file. Write it -Name value, --name value or --name=value.

              -Project <dir>          The project folder. Default: the current folder
              -Name <name>            The app's name, also its launcher's file name
              -MainClass <class>      The class with main(), e.g. com.example.Main
              -MainJar <file>         The app's jar in target. Default: the jar Maven built there
              -Platform <list>        Comma separated: %s, or all. Default: all
              -Installer <value>      false (default): a .zip / .tar.gz per platform. true: a setup.exe with a RenGL window
                                      and a .deb and .rpm. plain: the same with the small plain Windows window
              -NoArchive              Leave the folders; write no archives or installers
              -Icon <files>           .ico (Windows), .png (Linux), icns=<square 1024 px png> (macOS); comma separated
              -Version <x.y.z>        Default: 1.0.0
              -Vendor <text>          -Description <text>   -Identifier <macOS bundle id>
              -AddModules <list>      Java modules the app loads by reflection, e.g. java.logging,java.prefs
              -Content <dirs>         Folders of files to ship beside the jars; comma separated
              -FileAssociation <a>    ext[,mime[,description]]; repeat for several
              -ShellVerb <v>          target|id|label|args, an Explorer right-click entry of the Windows setup; repeat
              -DocumentTypes <file>   macOS Info.plist document types, relative to the project
              -Output <dir>           Where to write, relative to the project. Default: dist/app-images
              -RenglHome <dir>        The RenGL folder, for its Maven wrapper and the setup's RenGL window. The scripts
                                      pass it; otherwise RENGL_HOME, or the folder this jar was built in
            """.formatted(Platform.all());

    private ProjectBuild() {
    }

    static void run(String[] args) throws Exception {
        Map<String, List<String>> given = new LinkedHashMap<>();
        boolean init = false;
        String projectArg = null;
        String homeArg = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-") || arg.length() < 2) throw new IllegalArgumentException("Unexpected '" + arg + "'. Settings are written -Name value or --name value");
            String key = arg.replaceFirst("^-+", "");
            String value = null;
            int eq = key.indexOf('=');
            if (eq >= 0) {
                value = key.substring(eq + 1);
                key = key.substring(0, eq);
            }
            String setting = canonical(key);
            switch (setting) {
                case "help", "?", "h" -> {
                    System.out.println(HELP);
                    return;
                }
                case "init" -> {
                    init = true;
                    continue;
                }
                case "noArchive" -> {
                    if (value != null && !value.matches("(?i)true|false")) throw new IllegalArgumentException("-NoArchive is true or false, not '" + value + "'");
                    given.put(setting, List.of(value == null ? "true" : value.toLowerCase(Locale.ROOT)));
                    continue;
                }
                default -> {
                }
            }
            if (value == null) {
                if (i + 1 >= args.length) throw new IllegalArgumentException(arg + " needs a value");
                value = args[++i];
            }
            switch (setting) {
                case "project" -> projectArg = value;
                case "renglHome" -> homeArg = value;
                default -> {
                    if (REPEATING.contains(setting)) given.computeIfAbsent(setting, k -> new ArrayList<>()).add(value);
                    else if (LISTS.contains(setting)) given.computeIfAbsent(setting, k -> new ArrayList<>()).addAll(split(value));
                    else given.put(setting, List.of(value));
                }
            }
        }

        Path project = Path.of(projectArg == null ? "" : projectArg).toAbsolutePath().normalize();
        if (!Files.isRegularFile(project.resolve("pom.xml"))) {
            throw new IllegalStateException("There is no pom.xml in " + project + ". Run this from your project's folder, or pass -Project <folder>.");
        }
        Path home = renglHome(homeArg);

        if (init) {
            init(project, home);
            return;
        }
        build(project, home, settings(project, given));
    }

    // ---- settings -------------------------------------------------------------------------------------------------

    /** {@code Name}, {@code main-class} or {@code mainclass} to {@code mainClass}; anything else as it was, lower case. */
    private static String canonical(String key) {
        String plain = key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        for (String setting : SETTINGS) {
            if (setting.toLowerCase(Locale.ROOT).equals(plain)) return setting;
        }
        return switch (plain) {
            case "project", "init", "help", "?", "h" -> plain;
            case "renglhome" -> "renglHome";
            default -> throw new IllegalArgumentException("'" + key + "' is not a setting. The settings are: " + String.join(", ", SETTINGS) + "; and project, init, help");
        };
    }

    /** The settings file, then the command line over it. */
    private static Map<String, List<String>> settings(Path project, Map<String, List<String>> given) throws IOException {
        Map<String, List<String>> settings = new LinkedHashMap<>();
        Path config = project.resolve(CONFIG_NAME);
        if (Files.isRegularFile(config)) {
            System.out.println("==> Settings from " + CONFIG_NAME);
            int number = 0;
            for (String line : Files.readAllLines(config, StandardCharsets.UTF_8)) {
                number++;
                String text = line.strip();
                if (number == 1 && text.startsWith("﻿")) text = text.substring(1).strip();
                if (text.isEmpty() || text.startsWith("#")) continue;
                String where = CONFIG_NAME + ", line " + number + ": ";
                int eq = text.indexOf('=');
                if (eq < 1) throw new IllegalStateException(where + "expected setting=value, found '" + text + "'.");
                String key = text.substring(0, eq).strip();
                String value = text.substring(eq + 1).strip();
                if (key.equalsIgnoreCase("installerUi")) {
                    throw new IllegalStateException(where + "installerUi is gone. installer=true now gives the setup with the RenGL window; installer=plain gives the small plain Windows window. Delete this line.");
                }
                String setting = SETTINGS.stream().filter(s -> s.equalsIgnoreCase(key)).findFirst()
                        .orElseThrow(() -> new IllegalStateException(where + "'" + key + "' is not a setting. The settings are: " + String.join(", ", SETTINGS) + "."));
                if (value.isEmpty()) continue;
                if (setting.equals("installer") && !value.matches("true|false|plain")) throw new IllegalStateException(where + "installer must be true, false or plain, not '" + value + "'.");
                if (setting.equals("noArchive") && !value.matches("true|false")) throw new IllegalStateException(where + "noArchive must be true or false, not '" + value + "'.");
                if (REPEATING.contains(setting)) settings.computeIfAbsent(setting, k -> new ArrayList<>()).add(value);
                else if (LISTS.contains(setting)) settings.put(setting, split(value));
                else settings.put(setting, List.of(value));
            }
        }
        settings.putAll(given);
        if (!WINDOWS) {
            // A settings file written on Windows may say dist\app-images; on macOS and Linux a backslash is part of the name.
            for (String setting : PATHS) settings.computeIfPresent(setting, (k, values) -> values.stream().map(v -> v.replace('\\', '/')).toList());
        }
        return settings;
    }

    private static List<String> split(String value) {
        return Stream.of(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String single(Map<String, List<String>> settings, String key, String fallback) {
        List<String> values = settings.get(key);
        return values == null || values.isEmpty() ? fallback : values.getLast();
    }

    // ---- the build ------------------------------------------------------------------------------------------------

    private static void build(Path project, Path home, Map<String, List<String>> settings) throws Exception {
        String name = single(settings, "name", "");
        String mainClass = single(settings, "mainClass", "");
        if (name.isEmpty()) throw new IllegalStateException("The app has no name. Set name= in " + CONFIG_NAME + " (-Init writes it), or pass -Name.");
        if (mainClass.isEmpty()) throw new IllegalStateException("The app has no main class. Set mainClass= in " + CONFIG_NAME + " (-Init writes it), or pass -MainClass.");
        String installer = single(settings, "installer", "false");
        if (!installer.matches("true|false|plain")) throw new IllegalArgumentException("-Installer is true, false or plain, not '" + installer + "'");

        String platformList = single(settings, "platform", "all");
        List<Platform> platforms = platformList.equals("all") ? List.copyOf(NATIVES.keySet())
                : new LinkedHashSet<>(split(platformList).stream().map(Platform::parse).toList()).stream().toList();
        if (platforms.isEmpty()) throw new IllegalArgumentException("-Platform names no platform");
        requireJdk();

        Path target = project.resolve("target");
        Path input = target.resolve("input");
        Maven maven = maven(project, home);

        System.out.println("==> Building " + project);
        // junit comes in through RenGL's dependencies and has no business in an app.
        List<String> command = new ArrayList<>(maven.command());
        command.addAll(List.of("-q", "-f", project.resolve("pom.xml").toString(), "-DskipTests", "clean", "package", "dependency:copy-dependencies",
                "-DincludeScope=runtime", "-DexcludeArtifactIds=junit", "-DoutputDirectory=" + input));
        int exit = exec(command, maven.dir());
        if (exit != 0) throw new IllegalStateException("The Maven build failed (exit code " + exit + ")");

        String mainJar = single(settings, "mainJar", "");
        if (mainJar.isEmpty()) mainJar = builtJar(target);
        Path jar = target.resolve(mainJar);
        if (!Files.isRegularFile(jar)) throw new IllegalStateException(jar + " does not exist.");
        Files.createDirectories(input);
        Files.copy(jar, input.resolve(jar.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        mainJar = jar.getFileName().toString();

        String version = engineVersion(input, mainJar);
        fetchNatives(platforms, version, maven, target, input);

        List<String> args = new ArrayList<>(List.of(
                "--name", name, "--app-version", single(settings, "version", "1.0.0"),
                "--input", input.toString(), "--main-jar", mainJar, "--main-class", mainClass,
                "--platform", String.join(",", platforms.stream().map(Platform::id).toList()),
                "--output", project.resolve(single(settings, "output", "dist/app-images")).toString()));
        optional(args, "--vendor", single(settings, "vendor", ""));
        optional(args, "--description", single(settings, "description", ""));
        optional(args, "--identifier", single(settings, "identifier", ""));
        optional(args, "--add-modules", single(settings, "addModules", ""));
        for (String icon : settings.getOrDefault("icon", List.of())) args.addAll(List.of("--icon", projectIcon(project, icon)));
        for (String content : settings.getOrDefault("content", List.of())) args.addAll(List.of("--content", project.resolve(content).toString()));
        for (String association : settings.getOrDefault("fileAssociation", List.of())) args.addAll(List.of("--file-association", association));
        for (String verb : settings.getOrDefault("shellVerb", List.of())) args.addAll(List.of("--shell-verb", verb));
        String documentTypes = single(settings, "documentTypes", "");
        if (!documentTypes.isEmpty()) args.addAll(List.of("--document-types", project.resolve(documentTypes).toString()));
        // -XstartOnFirstThread is added for macOS by the packager, where it belongs.
        args.addAll(List.of("--java-option", "--enable-native-access=ALL-UNNAMED"));
        if (installer.equals("true")) {
            Path ui = home == null ? null : home.resolve("rengl-packager").resolve("target").resolve("ui-input");
            if (ui == null || !Files.isDirectory(ui)) {
                throw new IllegalStateException("The setup's RenGL window is not built" + (ui == null ? " (the RenGL folder is not known: start this with package-app.ps1 or package-app.sh, or set RENGL_HOME)" : " (" + ui + ")")
                        + ". In the RenGL folder run: " + mvnw() + " install -DskipTests   (or use installer=plain for the small plain Windows window)");
            }
            args.addAll(List.of("--installer-ui", ui.toString()));
        } else if (installer.equals("plain")) {
            args.add("--installer");
        }
        if (single(settings, "noArchive", "false").equals("true")) args.add("--no-archive");

        System.out.println("==> RenGL packager (" + String.join(", ", platforms.stream().map(Platform::id).toList()) + ")");
        Packager.run(args.toArray(String[]::new));
    }

    private static void optional(List<String> args, String option, String value) {
        if (!value.isEmpty()) args.addAll(List.of(option, value));
    }

    /** An icon setting with its path made absolute, keeping an {@code icns=} in front. */
    private static String projectIcon(Path project, String icon) {
        int eq = icon.indexOf('=');
        if (eq > 0 && Set.of("ico", "icns", "png").contains(icon.substring(0, eq))) {
            return icon.substring(0, eq + 1) + project.resolve(icon.substring(eq + 1));
        }
        return project.resolve(icon).toString();
    }

    private static void requireJdk() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        for (String tool : List.of("jdeps", "jlink")) {
            if (!Files.exists(bin.resolve(WINDOWS ? tool + ".exe" : tool))) {
                throw new IllegalStateException(tool + " is not in " + bin + ". Run this with JDK 25 (a full JDK, not a JRE): put its bin folder on PATH or set JAVA_HOME.");
            }
        }
    }

    /** The jar Maven built in target: the newest one that is not sources, javadoc or tests. */
    private static String builtJar(Path target) throws IOException {
        try (Stream<Path> files = Files.list(target)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(f -> !NOT_MAIN_JAR.matcher(f.getFileName().toString()).find())
                    .max(Comparator.comparing(ProjectBuild::modified))
                    .map(f -> f.getFileName().toString())
                    .orElseThrow(() -> new IllegalStateException("Maven built no jar in " + target + ". Does the project's pom make one?"));
        }
    }

    /**
     * The engine's version: from the jar the build brought (the whole RenGL engine, or just rengl-core), else the newest
     * RenGL in the local Maven repository. The match ignores case: file names on Windows don't care either.
     */
    private static String engineVersion(Path input, String mainJar) throws IOException {
        try (Stream<Path> files = Files.list(input)) {
            for (String file : files.map(f -> f.getFileName().toString()).sorted().toList()) {
                Matcher m = ENGINE_JAR.matcher(file);
                if (m.matches()) return m.group(2);
            }
        }
        Path engine = REPOSITORY.resolve("RenGL");
        String newest = null;
        if (Files.isDirectory(engine)) {
            try (Stream<Path> dirs = Files.list(engine)) {
                newest = dirs.filter(Files::isDirectory).filter(d -> !d.getFileName().toString().contains("$"))
                        .max(Comparator.comparing(ProjectBuild::modified)).map(d -> d.getFileName().toString()).orElse(null);
            }
        }
        if (newest == null) throw new IllegalStateException("The RenGL engine is not in your Maven repository. In the RenGL folder run: " + mvnw() + " install -DskipTests");
        System.out.println("Warning: " + mainJar + "'s dependencies hold no RenGL jar; using the natives of RenGL " + newest + ".");
        return newest;
    }

    /**
     * The build brings the natives of the computer it runs on. The packager picks natives by file name and gives each image
     * only its own platform's, so the other platforms' jars just have to be in the folder. They are fetched with Maven from
     * a throwaway pom that depends on each missing platform's rengl-natives artifact, which brings LWJGL's natives along.
     */
    private static void fetchNatives(List<Platform> platforms, String version, Maven maven, Path target, Path input) throws IOException, InterruptedException {
        List<Platform> missing = platforms.stream().filter(p -> !Files.exists(input.resolve(NATIVES.get(p) + "-" + version + ".jar"))).toList();
        if (missing.isEmpty()) return;
        StringBuilder pom = new StringBuilder("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + "<groupId>local</groupId><artifactId>natives</artifactId><version>1</version><dependencies>");
        for (Platform p : missing) {
            pom.append("<dependency><groupId>io.github.piitex</groupId><artifactId>").append(NATIVES.get(p))
                    .append("</artifactId><version>").append(version).append("</version></dependency>");
        }
        pom.append("</dependencies></project>");
        Path nativesPom = target.resolve("natives-pom.xml");
        Files.writeString(nativesPom, pom, StandardCharsets.UTF_8);

        String list = String.join(", ", missing.stream().map(Platform::id).toList());
        System.out.println("==> Natives for " + list);
        List<String> command = new ArrayList<>(maven.command());
        // An absolute path: a relative one is taken from the throwaway pom's folder (target), not from the project.
        command.addAll(List.of("-q", "-f", nativesPom.toString(), "dependency:copy-dependencies", "-DincludeScope=runtime", "-DoutputDirectory=" + input));
        if (exec(command, maven.dir()) != 0) {
            throw new IllegalStateException("The " + list + " natives could not be fetched. In the RenGL folder run: " + installNatives());
        }
    }

    // ---- -Init ----------------------------------------------------------------------------------------------------

    private static void init(Path project, Path home) throws IOException {
        Path config = project.resolve(CONFIG_NAME);
        Path launcher = project.resolve(WINDOWS ? "package.cmd" : "package.sh");
        String run = WINDOWS ? "package.cmd" : "./package.sh";
        boolean writeConfig = !Files.exists(config);
        if (!writeConfig && Files.exists(launcher)) {
            throw new IllegalStateException(CONFIG_NAME + " and " + launcher.getFileName() + " already exist in " + project + ". Edit the settings, or delete " + CONFIG_NAME + " to start over.");
        }
        String nl = System.lineSeparator();

        String guessMain = "";
        if (writeConfig) {
            String pom = Files.readString(project.resolve("pom.xml"));
            // The parent's id and version are not the project's own.
            String own = pom.replaceAll("(?s)<parent>.*?</parent>", "");
            String guessName = group(own, "<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
            if (guessName.isEmpty()) guessName = project.getFileName().toString();
            String guessVersion = group(own, "(?s)</artifactId>\\s*<version>\\s*(\\d+(?:\\.\\d+)*)");
            if (guessVersion.isEmpty()) guessVersion = "1.0.0";

            Set<String> mains = new LinkedHashSet<>();
            String pomMain = group(pom, "<mainClass>\\s*([^<\\s]+)\\s*</mainClass>");
            if (!pomMain.isEmpty() && !pomMain.contains("${")) mains.add(pomMain);
            Path src = project.resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(src)) {
                Pattern main = Pattern.compile("static\\s+void\\s+main\\s*\\(");
                try (Stream<Path> files = Files.walk(src)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                        if (main.matcher(Files.readString(file)).find()) {
                            String relative = src.relativize(file).toString().replaceFirst("\\.java$", "");
                            mains.add(relative.replace('\\', '.').replace('/', '.'));
                        }
                    }
                }
            }
            guessMain = mains.isEmpty() ? "" : mains.iterator().next();
            Platform host = Platform.host();

            List<String> lines = new ArrayList<>(List.of(
                    "# RenGL packaging settings for this project. Build with " + run + ", in this folder.",
                    "# Lines starting with # are comments, and a setting with nothing after the = is ignored.",
                    "# A parameter on the command line wins over this file: " + run + " -Platform linux-x64",
                    "",
                    "# The app's name, which is also the name of its launcher (MyApp.exe).",
                    "name=" + guessName,
                    "",
                    "# The class with the main method, package included."));
            if (mains.size() > 1) lines.add("# Classes with a main method: " + String.join(", ", mains));
            if (mains.isEmpty()) lines.add("# No main method was found in src/main/java; fill this in.");
            lines.addAll(List.of(
                    "mainClass=" + guessMain,
                    "version=" + guessVersion,
                    "",
                    "# What to build: windows-x64, linux-x64, linux-arm64, macos-x64, macos-arm64, or all (comma separated).",
                    "# Each platform other than this one downloads its Java modules the first time (about 85 MB each).",
                    "platform=" + (host == null ? "windows-x64" : host.id()),
                    "",
                    "# false: a .zip and .tar.gz per platform.",
                    "# true: a setup.exe for Windows (with a RenGL window) and a .deb and .rpm for Linux, instead.",
                    "# plain: the same, with the small plain Windows window in the setup (about 50 MB smaller).",
                    "installer=false",
                    "",
                    "# More settings, if you want them. Remove the # to use one.",
                    "# The Windows, Linux and macOS icons. The macOS one is made from a square 1024 px png.",
                    "# icon=MyApp.ico,MyApp.png,icns=MyApp-1024.png",
                    "# vendor=Your Name",
                    "# description=One line about the app",
                    "# A folder of files to ship beside the jars (assets, default data).",
                    "# content=assets",
                    "# Java modules the app loads by reflection, which the packager cannot see.",
                    "# addModules=java.logging,java.prefs"));
            Files.writeString(config, String.join(nl, lines) + nl, StandardCharsets.UTF_8);
            System.out.println("Wrote " + CONFIG_NAME + ":");
            System.out.println("  name=" + guessName + "  mainClass=" + guessMain + "  version=" + guessVersion);
        } else {
            System.out.println(CONFIG_NAME + " already exists; left as it is.");
        }

        if (Files.exists(launcher)) {
            System.out.println(launcher.getFileName() + " already exists; left as it is.");
        } else {
            Files.writeString(launcher, launcher(home), StandardCharsets.UTF_8);
            if (!WINDOWS) {
                try {
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(launcher);
                    permissions.addAll(Set.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE));
                    Files.setPosixFilePermissions(launcher, permissions);
                } catch (UnsupportedOperationException | IOException e) {
                    System.out.println("Could not mark package.sh executable; run it with: sh package.sh");
                }
            }
            System.out.println("Wrote " + launcher.getFileName());
        }
        if (writeConfig && guessMain.isEmpty()) System.out.println("Warning: no class with a main method was found. Set mainClass in " + CONFIG_NAME + ".");
        System.out.println();
        System.out.println("Check " + CONFIG_NAME + ", then build with: " + run);
    }

    /**
     * The project's launcher: package.cmd on Windows, package.sh elsewhere. It starts the RenGL folder's script, which finds
     * the packager jar each time (so it follows engine updates); with no RenGL folder known, it starts this very jar.
     */
    private static String launcher(Path home) {
        Path jar = ownJar();
        if (WINDOWS) {
            String start = home != null
                    ? "powershell -NoProfile -ExecutionPolicy Bypass -File \"" + home.resolve("rengl-packager").resolve("package-app.ps1") + "\" %*"
                    : "java -jar \"" + jar + "\" project %*";
            return String.join("\r\n",
                    "@echo off",
                    "rem Builds this project's app with the RenGL packager. The settings are in rengl-package.properties.",
                    "rem Parameters pass through: package.cmd -Installer true   or   package.cmd -Platform linux-x64",
                    start,
                    "exit /b %errorlevel%") + "\r\n";
        }
        String start = home != null
                ? "exec sh " + shellQuote(home.resolve("rengl-packager").resolve("package-app.sh").toString()) + " \"$@\""
                : "exec java -jar " + shellQuote(String.valueOf(jar)) + " project \"$@\"";
        return String.join("\n",
                "#!/bin/sh",
                "# Builds this project's app with the RenGL packager. The settings are in rengl-package.properties.",
                "# Parameters pass through: ./package.sh -Installer true   or   ./package.sh -Platform linux-x64",
                start) + "\n";
    }

    private static String shellQuote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    private static String group(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    // ---- tools ----------------------------------------------------------------------------------------------------

    /**
     * The RenGL folder: as the scripts pass it, else RENGL_HOME, else the folder this jar was built in (its
     * rengl-packager/target). Null when none is known, as for a jar run straight from the Maven repository.
     */
    private static Path renglHome(String given) {
        String value = given != null ? given : System.getenv("RENGL_HOME");
        if (value != null && !value.isBlank()) {
            Path home = Path.of(value).toAbsolutePath().normalize();
            if (!Files.isDirectory(home)) throw new IllegalArgumentException("The RenGL folder " + home + " does not exist");
            return home;
        }
        Path jar = ownJar();
        if (jar != null && jar.getParent() != null && jar.getParent().getFileName() != null && jar.getParent().getFileName().toString().equals("target")) {
            Path module = jar.getParent().getParent();
            if (module != null && module.getParent() != null && Files.isRegularFile(module.getParent().resolve("pom.xml"))) return module.getParent();
        }
        return null;
    }

    private static Path ownJar() {
        try {
            Path path = Path.of(ProjectBuild.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(path) ? path : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** How to start Maven, and the folder to start it in. The commands name the pom with -f, so any folder works. */
    private record Maven(List<String> command, Path dir) {
    }

    /**
     * The project's own Maven wrapper, or the RenGL folder's, or Maven on the PATH. A wrapper reads its settings from the
     * .mvn folder of the folder it is started in, so it starts in its own folder.
     */
    private static Maven maven(Path project, Path home) {
        String wrapper = WINDOWS ? "mvnw.cmd" : "mvnw";
        List<Path> dirs = home == null ? List.of(project) : List.of(project, home);
        for (Path dir : dirs) {
            Path file = dir.resolve(wrapper);
            if (Files.isRegularFile(file) && Files.isDirectory(dir.resolve(".mvn").resolve("wrapper"))) {
                // A wrapper checked out on Windows or unpacked from a zip may have lost its executable bit.
                return new Maven(WINDOWS || Files.isExecutable(file) ? List.of(file.toString()) : List.of("sh", file.toString()), dir);
            }
        }
        String exe = WINDOWS ? "mvn.cmd" : "mvn";
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Path file = Path.of(dir.strip()).resolve(exe);
                if (Files.isRegularFile(file)) return new Maven(List.of(file.toString()), project);
            }
        }
        throw new IllegalStateException("Maven was not found: the project has no " + wrapper + ", the RenGL folder is not known, and there is no " + exe
                + " on the PATH. Start this with the RenGL folder's package-app script, or install Maven.");
    }

    private static int exec(List<String> command, Path dir) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).inheritIO();
        builder.environment().put("MAVEN_OPTS", MAVEN_OPTS);
        return builder.start().waitFor();
    }

    private static java.nio.file.attribute.FileTime modified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return java.nio.file.attribute.FileTime.fromMillis(0);
        }
    }

    private static String mvnw() {
        return WINDOWS ? ".\\mvnw.cmd" : "./mvnw";
    }

    private static String installNatives() {
        return WINDOWS ? "powershell -ExecutionPolicy Bypass -File rengl-packager\\install-natives.ps1" : "sh rengl-packager/install-natives.sh";
    }
}
