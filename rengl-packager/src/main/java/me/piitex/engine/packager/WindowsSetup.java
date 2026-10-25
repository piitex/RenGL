package me.piitex.engine.packager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Makes {@code MyApp-1.0.0-windows-x64-setup.exe}: the prebuilt setup program ({@code native/windows/setup.c}) with a zip
 * of the app-image appended, so the one file is the installer and what it installs. The zip starts with a {@code setup.ini}
 * that names the app, then holds the image's files relative to the install folder.
 * <p>
 * With a {@code uiImage} (the app-image of the {@code me.piitex.engine.installer} package) the zip also holds that image's files under
 * {@code ui/}, and {@code setup.ini} says {@code ui=<its launcher>}. The setup program then unpacks {@code ui/} to the temp
 * folder and shows that RenGL window instead of its built-in wizard; the files under {@code ui/} are never installed.
 */
final class WindowsSetup {
    /** The launcher of the installer window inside {@code ui/}. */
    static final String UI_NAME = "installer";

    private WindowsSetup() {
    }

    static Path build(AppSpec app, Platform platform, Path image, Path uiImage, Path outputDir) throws IOException {
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
                + (uiImage != null ? "ui=" + UI_NAME + ".exe\n" : "")).getBytes(StandardCharsets.UTF_8);

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
            if (uiImage != null) addTree(zip, uiImage, "ui/");
        }
        return setup;
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
