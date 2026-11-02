package me.piitex.engine.packager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Makes {@code MyApp-1.0.0-windows-x64-setup.exe}: the prebuilt setup program ({@code native/windows/setup.c}) with a zip
 * of the app-image appended, so the one file is the installer and what it installs. The zip starts with a {@code setup.ini}
 * that names the app, then holds the image's files relative to the install folder.
 * <p>
 * With a {@link Window} (the {@code me.piitex.engine.installer} package) the zip also holds the files of that window under
 * {@code ui/}, and {@code setup.ini} says {@code ui=<its launcher>}. The setup program then unpacks {@code ui/} to the temp
 * folder and shows that RenGL window instead of its built-in wizard; the files under {@code ui/} are never installed.
 * <p>
 * The window is either an app-image of its own, with a Java runtime of its own (about 50 MB more in the setup), or, when the
 * app is built from the same jars, just a launcher and the window's jar: {@code setup.ini} then says {@code ui-share=1} and
 * the setup program also unpacks the app's {@code runtime/} and {@code app/*.jar} beside them, which are in the zip anyway.
 */
final class WindowsSetup {
    /** The launcher of the installer window inside {@code ui/}. */
    static final String UI_NAME = "installer";
    static final String INSTALLER_MAIN_CLASS = "me.piitex.engine.installer.InstallerMain";

    private WindowsSetup() {
    }

    /**
     * The installer window's files.
     *
     * @param image     the window's own app-image, or null when it runs on the app's runtime
     * @param jar       the window's jar, for one that runs on the app's runtime
     * @param classPath the jars (names in the app folder) that one runs with, its own first
     */
    record Window(Path image, Path jar, List<String> classPath) {
        static Window own(Path image) {
            return new Window(image, null, List.of());
        }

        static Window shared(Path jar, List<String> classPath) {
            return new Window(null, jar, classPath);
        }
    }

    static Path build(AppSpec app, Platform platform, Path image, Window ui, Path outputDir) throws IOException {
        Path setup = outputDir.resolve(app.name() + "-" + app.version() + "-" + platform.id() + "-setup.exe");
        try (InputStream stub = WindowsSetup.class.getResourceAsStream("setup-" + platform.id() + ".exe")) {
            if (stub == null) throw new IOException("This packager has no setup program for " + platform.id() + " (setup-" + platform.id() + ".exe is missing)");
            Files.copy(stub, setup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        // The icon and version go in before the payload: rewriting the exe's resources would drop what is appended to it.
        Path ico = app.icons().get("ico");
        if (WindowsIcon.available()) {
            try {
                if (ico != null) WindowsIcon.apply(setup, ico);
                WindowsVersion.apply(setup, new WindowsVersion.Info(app.name(), app.version(), app.vendor(), app.name() + " Setup", setup.getFileName().toString()));
            } catch (Throwable t) {
                throw new IOException("Could not set the setup program's icon and version: " + t.getMessage(), t);
            }
        }

        byte[] ini = ("name=" + app.name() + "\n"
                + "version=" + app.version() + "\n"
                + "vendor=" + app.vendor() + "\n"
                + "description=" + app.description().replace('\n', ' ') + "\n"
                + "id=" + app.identifier() + "\n"
                + "exe=" + app.name() + ".exe\n"
                + (ui != null ? "ui=" + UI_NAME + ".exe\n" : "")
                + (ui != null && ui.image() == null ? "ui-share=1\n" : "")
                + registrations(app)).getBytes(StandardCharsets.UTF_8);

        try (OutputStream file = Files.newOutputStream(setup, StandardOpenOption.APPEND);
             ZipOutputStream zip = new ZipOutputStream(new java.io.BufferedOutputStream(file, 1 << 16))) {
            ZipEntry settings = new ZipEntry("setup.ini");
            CRC32 crc = new CRC32();
            crc.update(ini);
            settings.setMethod(ZipEntry.STORED);
            settings.setSize(ini.length);
            settings.setCompressedSize(ini.length);
            settings.setCrc(crc.getValue());
            zip.putNextEntry(settings);
            zip.write(ini);
            zip.closeEntry();

            addTree(zip, image, "");
            if (ui != null && ui.image() != null) addTree(zip, ui.image(), "ui/");
            if (ui != null && ui.image() == null) addSharedWindow(zip, app, ui);
        }
        return setup;
    }

    /**
     * The {@code setup.ini} lines that tell the setup program what to add to the registry: {@code verb=<target>|<id>|<label>|<args>}
     * for each right-click entry and {@code assoc=<.ext>|<description>} for each file type the app opens.
     */
    static String registrations(AppSpec app) {
        StringBuilder lines = new StringBuilder();
        for (AppSpec.ShellVerb verb : app.shellVerbs()) {
            lines.append("verb=").append(verb.target()).append('|').append(verb.id()).append('|').append(verb.label()).append('|').append(verb.args()).append('\n');
        }
        for (AppSpec.FileAssociation type : app.fileAssociations()) {
            lines.append("assoc=.").append(type.extension().toLowerCase(java.util.Locale.ROOT)).append('|').append(type.description().replace('\n', ' ').replace('|', '/')).append('\n');
        }
        return lines.toString();
    }

    /** The launcher, its configuration and the jar: the rest of the window comes from the app's own files. */
    private static void addSharedWindow(ZipOutputStream zip, AppSpec app, Window ui) throws IOException {
        Path launcher = Files.createTempFile("rengl-installer", ".exe");
        try {
            try (InputStream stub = WindowsSetup.class.getResourceAsStream("launcher-windows-x64.exe")) {
                if (stub == null) throw new IOException("This packager has no launcher for windows-x64 (launcher-windows-x64.exe is missing)");
                Files.copy(stub, launcher, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (WindowsIcon.available()) {
                try {
                    Path ico = app.icons().get("ico");
                    if (ico != null) WindowsIcon.apply(launcher, ico);
                    WindowsVersion.apply(launcher, new WindowsVersion.Info(app.name(), app.version(), app.vendor(), app.name() + " Setup", UI_NAME + ".exe"));
                } catch (Throwable t) {
                    throw new IOException("Could not set the installer window's icon and version: " + t.getMessage(), t);
                }
            }
            zip.putNextEntry(new ZipEntry("ui/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ui/" + UI_NAME + ".exe"));
            Files.copy(launcher, zip);
            zip.closeEntry();
        } finally {
            Files.deleteIfExists(launcher);
        }

        StringBuilder cfg = new StringBuilder("main-class=").append(INSTALLER_MAIN_CLASS).append('\n');
        for (String jar : ui.classPath()) cfg.append("cp=").append(jar).append('\n');
        cfg.append("java-option=--enable-native-access=ALL-UNNAMED\n");
        zip.putNextEntry(new ZipEntry("ui/app/"));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("ui/app/" + UI_NAME + ".cfg"));
        zip.write(cfg.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("ui/app/" + ui.jar().getFileName()));
        Files.copy(ui.jar(), zip);
        zip.closeEntry();
    }

    private static void addTree(ZipOutputStream zip, Path root, String prefix) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.skip(1).sorted()::iterator) {
                String name = prefix + root.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    zip.putNextEntry(new ZipEntry(name + "/"));
                } else {
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(path, zip);
                }
                zip.closeEntry();
            }
        }
    }
}
