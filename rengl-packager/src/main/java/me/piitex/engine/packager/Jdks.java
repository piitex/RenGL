package me.piitex.engine.packager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Finds the {@code jmods} of the JDK for a target platform. {@code jlink} builds a runtime for another platform when
 * it is given that platform's jmods, so this is what lets one machine build every app-image.
 * <p>
 * The running JDK serves its own platform. For the others the jmods are downloaded from Amazon Corretto once and
 * cached, taking the same release as the running JDK when that is a Corretto, because {@code jlink} refuses jmods
 * of another version.
 */
final class Jdks {
    private final Map<Platform, Path> overrides;
    private final Path cache;

    Jdks(Map<Platform, Path> overrides, Path cache) {
        this.overrides = overrides;
        this.cache = cache;
    }

    Path jmods(Platform target) throws IOException, InterruptedException {
        Path given = overrides.get(target);
        if (given != null) return jmodsIn(given);

        if (target == Platform.host()) return jmodsIn(Path.of(System.getProperty("java.home")));

        Path dir = cache.resolve(version() == null ? "latest" : version()).resolve(target.id()).resolve("jmods");
        if (!Files.isDirectory(dir) || isEmpty(dir)) download(target, dir);
        return dir;
    }

    private static Path jmodsIn(Path jdk) throws IOException {
        if (Files.exists(jdk.resolve("java.base.jmod"))) return jdk;
        Path jmods = jdk.resolve("jmods");
        if (Files.exists(jmods.resolve("java.base.jmod"))) return jmods;
        throw new IOException(jdk + " is not a JDK with a jmods folder (java.base.jmod not found). Use a full JDK, not a JRE.");
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.findAny().isEmpty();
        }
    }

    /** The running JDK's Corretto release (25.0.2.10.1), or {@code null} if it is some other build. */
    private static String version() {
        String vendor = System.getProperty("java.vendor.version", "");
        Matcher m = Pattern.compile("Corretto-(\\d+(?:\\.\\d+)+)").matcher(vendor);
        return m.find() ? m.group(1) : null;
    }

    private void download(Platform target, Path jmods) throws IOException, InterruptedException {
        String version = version();
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        String url = target.correttoUrl(version);
        HttpResponse<InputStream> response = get(client, url);
        if (response.statusCode() != 200 && version != null) {
            System.out.println("  Corretto " + version + " has no " + target.id() + " build; using the latest instead.");
            url = target.correttoUrl(null);
            response = get(client, url);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Could not download " + url + " (HTTP " + response.statusCode() + "). Pass --jdk " + target.id() + "=<path to a JDK> instead.");
        }

        System.out.println("  Downloading the " + target.id() + " JDK's jmods from " + url);
        Path partial = Files.createDirectories(jmods.getParent()).resolve("jmods.partial");
        Files.createDirectories(partial);
        try (InputStream body = response.body()) {
            if (url.endsWith(".zip")) {
                extractZip(body, partial);
            } else {
                try (InputStream tar = new GZIPInputStream(body, 1 << 16)) {
                    Tar.read(tar, (name, content) -> save(name, content, partial));
                }
            }
        }
        if (isEmpty(partial)) throw new IOException("No jmods found in " + url);
        if (Files.exists(jmods)) Files.delete(jmods);
        Files.move(partial, jmods, StandardCopyOption.REPLACE_EXISTING);
    }

    private static HttpResponse<InputStream> get(HttpClient client, String url) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static void extractZip(InputStream in, Path into) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
            if (!e.isDirectory()) save(e.getName(), zip, into);
        }
    }

    /** Keeps only {@code <jdk>/jmods/*.jmod} (and macOS's {@code Contents/Home/jmods}). */
    private static void save(String entry, InputStream content, Path into) throws IOException {
        String[] parts = entry.split("/");
        if (parts.length < 2 || !parts[parts.length - 2].equals("jmods") || !parts[parts.length - 1].endsWith(".jmod")) return;
        try (OutputStream out = Files.newOutputStream(into.resolve(parts[parts.length - 1]))) {
            content.transferTo(out);
        }
    }
}
