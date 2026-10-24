package me.piitex.engine.packager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Makes a Debian package ({@code .deb}) of a Linux app-image, without dpkg: an {@code ar} archive of a control tarball and
 * a data tarball. It installs the image under {@code /opt/<name>}, links the launcher into {@code /usr/bin}, and adds a
 * menu entry (the {@code .desktop} file) with the icon, so the app shows up in the desktop's application menu.
 */
final class DebPackage {
    private static final int DIRECTORY = 0755;

    private DebPackage() {
    }

    static Path build(AppSpec app, Platform platform, Path image, Path outputDir) throws IOException {
        String pkg = packageName(app.name());
        String arch = platform.arch().equals("x64") ? "amd64" : "arm64";
        String version = debVersion(app.version());
        Path deb = outputDir.resolve(app.name() + "-" + app.version() + "-" + platform.id() + ".deb");
        String home = "/opt/" + app.name();

        Path dataTar = Files.createTempFile("rengl-data", ".tar.gz");
        Path controlTar = Files.createTempFile("rengl-control", ".tar.gz");
        try {
            long kilobytes = writeData(app, pkg, image, home, dataTar);
            writeControl(app, pkg, arch, version, kilobytes, controlTar);
            try (OutputStream out = Files.newOutputStream(deb)) {
                out.write("!<arch>\n".getBytes(StandardCharsets.US_ASCII));
                member(out, "debian-binary", "2.0\n".getBytes(StandardCharsets.US_ASCII));
                member(out, "control.tar.gz", controlTar);
                member(out, "data.tar.gz", dataTar);
            }
        } finally {
            Files.deleteIfExists(dataTar);
            Files.deleteIfExists(controlTar);
        }
        return deb;
    }

    /** Debian package names are lower case letters, digits and "+-.", and start with a letter or digit. */
    static String packageName(String name) {
        String pkg = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+.-]+", "-").replaceAll("^[^a-z0-9]+", "");
        return pkg.length() < 2 ? "app-" + pkg : pkg;
    }

    private static String debVersion(String version) {
        String v = version.replaceAll("[^A-Za-z0-9.+~-]", ".");
        return v.isEmpty() || !Character.isDigit(v.charAt(0)) ? "0." + v : v;
    }

    /** Writes the data tarball and returns the size of what it installs, in KiB. */
    private static long writeData(AppSpec app, String pkg, Path image, String home, Path file) throws IOException {
        long bytes = 0;
        try (Tar.Writer tar = new Tar.Writer(new GZIPOutputStream(Files.newOutputStream(file), 1 << 16))) {
            for (String dir : new String[] {"./", "./opt/", "." + home + "/", "./usr/", "./usr/bin/", "./usr/share/", "./usr/share/applications/", "./usr/share/pixmaps/"}) {
                tar.directory(dir, DIRECTORY);
            }
            try (Stream<Path> walk = Files.walk(image)) {
                for (Path path : (Iterable<Path>) walk.skip(1).sorted()::iterator) {
                    String name = "." + home + "/" + image.relativize(path).toString().replace('\\', '/');
                    if (Files.isDirectory(path)) {
                        tar.directory(name, DIRECTORY);
                    } else {
                        tar.file(name, path, ImageBuilder.isExecutable(image, path) ? 0755 : 0644);
                        bytes += Files.size(path);
                    }
                }
            }
            tar.symlink("./usr/bin/" + pkg, home + "/bin/" + app.name(), 0777);

            Path icon = app.icons().get("png");
            if (icon != null) {
                tar.file("./usr/share/pixmaps/" + pkg + ".png", icon, 0644);
                bytes += Files.size(icon);
            }
            byte[] entry = desktopEntry(app, pkg, home, icon != null).getBytes(StandardCharsets.UTF_8);
            tar.file("./usr/share/applications/" + pkg + ".desktop", entry, 0644);
        }
        return Math.max(1, bytes / 1024);
    }

    private static String desktopEntry(AppSpec app, String pkg, String home, boolean icon) {
        String exec = home + "/bin/" + app.name();
        // The Exec key needs a path with spaces in double quotes.
        if (exec.contains(" ")) exec = "\"" + exec + "\"";
        return "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=" + app.name() + "\n"
                + "Comment=" + app.description().replace('\n', ' ') + "\n"
                + "Exec=" + exec + "\n"
                + (icon ? "Icon=" + pkg + "\n" : "")
                + "Terminal=false\n"
                + "Categories=Utility;\n";
    }

    private static void writeControl(AppSpec app, String pkg, String arch, String version, long kilobytes, Path file) throws IOException {
        String vendor = app.vendor().isEmpty() ? app.name() : app.vendor();
        String control = "Package: " + pkg + "\n"
                + "Version: " + version + "\n"
                + "Architecture: " + arch + "\n"
                + "Maintainer: " + vendor + " <noreply@localhost>\n"
                + "Installed-Size: " + kilobytes + "\n"
                + "Section: utils\n"
                + "Priority: optional\n"
                + "Description: " + app.description().replace('\n', ' ') + "\n";
        try (Tar.Writer tar = new Tar.Writer(new GZIPOutputStream(Files.newOutputStream(file)))) {
            tar.directory("./", DIRECTORY);
            tar.file("./control", control.getBytes(StandardCharsets.UTF_8), 0644);
        }
    }

    // ---- ar -------------------------------------------------------------------------------------------------------

    private static void member(OutputStream out, String name, byte[] content) throws IOException {
        header(out, name, content.length);
        out.write(content);
        if (content.length % 2 != 0) out.write('\n');
    }

    private static void member(OutputStream out, String name, Path content) throws IOException {
        long size = Files.size(content);
        header(out, name, size);
        Files.copy(content, out);
        if (size % 2 != 0) out.write('\n');
    }

    private static void header(OutputStream out, String name, long size) throws IOException {
        ByteArrayOutputStream h = new ByteArrayOutputStream(60);
        h.writeBytes(pad(name + "/", 16).getBytes(StandardCharsets.US_ASCII));
        h.writeBytes(pad(Long.toString(System.currentTimeMillis() / 1000), 12).getBytes(StandardCharsets.US_ASCII));
        h.writeBytes(pad("0", 6).getBytes(StandardCharsets.US_ASCII));
        h.writeBytes(pad("0", 6).getBytes(StandardCharsets.US_ASCII));
        h.writeBytes(pad("100644", 8).getBytes(StandardCharsets.US_ASCII));
        h.writeBytes(pad(Long.toString(size), 10).getBytes(StandardCharsets.US_ASCII));
        h.writeBytes("`\n".getBytes(StandardCharsets.US_ASCII));
        out.write(h.toByteArray());
    }

    private static String pad(String value, int width) {
        if (value.length() > width) throw new IllegalArgumentException(value + " does not fit an ar header");
        return value + " ".repeat(width - value.length());
    }
}
