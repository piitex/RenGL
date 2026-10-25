package me.piitex.engine.packager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the package back with a reader written from the rpm file format, not from the writer: lead, signature header,
 * header, then the cpio payload, checking the structure rpm itself insists on (the region trailers, the sort order, the
 * alignment, the digests) and that the file list and the payload agree. It cannot say that rpm installs it; that needs rpm.
 */
class RpmPackageTest {
    @TempDir
    Path dir;

    private Path rpm;
    private byte[] file;

    @BeforeEach
    void build() throws IOException {
        Path image = Files.createDirectories(dir.resolve("image").resolve("My App"));
        Files.createDirectories(image.resolve("bin"));
        Files.writeString(image.resolve("bin").resolve("My App"), "#!/bin/sh\necho hi\n");
        Files.createDirectories(image.resolve("lib").resolve("app"));
        Files.writeString(image.resolve("lib").resolve("app").resolve("app.jar"), "not really a jar");
        Files.createDirectories(image.resolve("lib").resolve("runtime").resolve("bin"));
        Files.write(image.resolve("lib").resolve("runtime").resolve("bin").resolve("java"), new byte[5000]);
        Files.createFile(image.resolve("lib").resolve("empty.txt"));
        Path icon = Files.write(dir.resolve("icon.png"), new byte[] {1, 2, 3});

        AppSpec app = new AppSpec("My App", "1.2.3-beta", "Acme", "Does things\nacross two lines", "me.rengl.my-app", dir, "app.jar", "Main",
                List.of(), Map.of("png", icon), List.of(), List.of());
        rpm = RpmPackage.build(app, Platform.LINUX_X64, image, Files.createDirectories(dir.resolve("out")));
        file = Files.readAllBytes(rpm);
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    private record Header(byte[] bytes, int indexCount, Map<Integer, Object> tags) {
        String string(int tag) {
            return (String) tags.get(tag);
        }

        @SuppressWarnings("unchecked")
        List<String> strings(int tag) {
            return (List<String>) tags.get(tag);
        }

        int[] ints(int tag) {
            return (int[]) tags.get(tag);
        }
    }

    private static Header readHeader(byte[] file, int start) {
        ByteBuffer in = ByteBuffer.wrap(file);
        assertArrayEquals(new byte[] {(byte) 0x8E, (byte) 0xAD, (byte) 0xE8, 1, 0, 0, 0, 0}, java.util.Arrays.copyOfRange(file, start, start + 8), "header magic");
        int count = in.getInt(start + 8);
        int dataLength = in.getInt(start + 12);
        int indexStart = start + 16;
        int dataStart = indexStart + 16 * count;
        Map<Integer, Object> tags = new TreeMap<>();

        int previousTag = -1;
        for (int i = 0; i < count; i++) {
            int at = indexStart + 16 * i;
            int tag = in.getInt(at), type = in.getInt(at + 4), offset = in.getInt(at + 8), n = in.getInt(at + 12);
            assertTrue(tag > previousTag, "tags are in ascending order, " + tag + " after " + previousTag);
            previousTag = tag;
            assertTrue(offset >= 0 && offset < dataLength, "tag " + tag + " points into the data");
            int alignment = type == 3 ? 2 : type == 4 ? 4 : 1;
            assertEquals(0, offset % alignment, "tag " + tag + " is aligned for its type");
            int p = dataStart + offset;
            Object value;
            switch (type) {
                case 3 -> {
                    short[] v = new short[n];
                    for (int k = 0; k < n; k++) v[k] = in.getShort(p + 2 * k);
                    value = v;
                }
                case 4 -> {
                    int[] v = new int[n];
                    for (int k = 0; k < n; k++) v[k] = in.getInt(p + 4 * k);
                    value = v;
                }
                case 6 -> value = cString(file, p);
                case 7 -> value = java.util.Arrays.copyOfRange(file, p, p + n);
                case 8, 9 -> {
                    List<String> v = new ArrayList<>();
                    int q = p;
                    for (int k = 0; k < n; k++) {
                        String s = cString(file, q);
                        v.add(s);
                        q += s.getBytes(StandardCharsets.UTF_8).length + 1;
                    }
                    value = type == 9 ? v.getFirst() : v;
                }
                default -> throw new AssertionError("type " + type + " of tag " + tag);
            }
            tags.put(tag, value);
            if (i == 0) {
                // The region tag: its data is a trailer entry that says how many index entries the region covers.
                assertEquals(7, type);
                assertEquals(16, n);
                assertEquals(tag, in.getInt(p), "the trailer repeats the tag");
                assertEquals(7, in.getInt(p + 4));
                assertEquals(-16 * count, in.getInt(p + 8), "and counts the whole index");
                assertEquals(16, in.getInt(p + 12));
                assertEquals(dataLength, offset + 16, "and ends the data");
            }
        }
        int length = 16 + 16 * count + dataLength;
        return new Header(java.util.Arrays.copyOfRange(file, start, start + length), count, tags);
    }

    private static String cString(byte[] bytes, int from) {
        int end = from;
        while (bytes[end] != 0) end++;
        return new String(bytes, from, end - from, StandardCharsets.UTF_8);
    }

    private record Cpio(String name, int number, int mode, byte[] data) {
    }

    private static List<Cpio> readCpio(byte[] archive) throws IOException {
        List<Cpio> entries = new ArrayList<>();
        int p = 0;
        while (true) {
            assertEquals("070701", new String(archive, p, 6, StandardCharsets.US_ASCII), "newc magic");
            int number = hex(archive, p + 6), mode = hex(archive, p + 14), size = hex(archive, p + 54), nameSize = hex(archive, p + 94);
            String name = new String(archive, p + 110, nameSize - 1, StandardCharsets.UTF_8);
            assertEquals(0, archive[p + 110 + nameSize - 1], "the name ends with a NUL");
            p = align(p + 110 + nameSize);
            if (name.equals("TRAILER!!!")) break;
            entries.add(new Cpio(name, number, mode, java.util.Arrays.copyOfRange(archive, p, p + size)));
            p = align(p + size);
        }
        assertEquals(archive.length, p, "nothing follows the trailer");
        return entries;
    }

    private static int hex(byte[] b, int at) {
        return (int) Long.parseLong(new String(b, at, 8, StandardCharsets.US_ASCII), 16);
    }

    private static int align(int n) {
        return (n + 3) / 4 * 4;
    }

    private static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    private static byte[] digest(String algorithm, byte[]... parts) throws Exception {
        MessageDigest d = MessageDigest.getInstance(algorithm);
        for (byte[] part : parts) d.update(part);
        return d.digest();
    }

    // ---- tests ------------------------------------------------------------------------------------------------------

    @Test
    void theLeadSaysThisIsAnRpmWithASignatureHeader() {
        ByteBuffer in = ByteBuffer.wrap(file);
        assertEquals(0xEDABEEDB, in.getInt(0));
        assertEquals(3, file[4]);
        assertEquals(0, in.getShort(6), "a binary package");
        assertEquals("my-app-1.2.3.beta-1", cString(file, 10));
        assertEquals(1, in.getShort(76), "for Linux");
        assertEquals(5, in.getShort(78), "with its signatures in a header");
    }

    @Test
    void theSignatureHeaderHoldsTheDigestsOfWhatFollows() throws Exception {
        Header signatures = readHeader(file, 96);
        int mainStart = 96 + (signatures.bytes().length + 7) / 8 * 8;
        assertEquals(0, mainStart % 8, "the package header starts on an 8-byte boundary");
        Header header = readHeader(file, mainStart);
        byte[] payload = java.util.Arrays.copyOfRange(file, mainStart + header.bytes().length, file.length);

        assertEquals(hex(digest("SHA-1", header.bytes())), signatures.string(269));
        assertEquals(hex(digest("SHA-256", header.bytes())), signatures.string(273));
        assertArrayEquals(digest("MD5", header.bytes(), payload), (byte[]) signatures.tags().get(1004));
        assertEquals(header.bytes().length + payload.length, signatures.ints(1000)[0], "the size of header and payload");
        assertEquals(readCpioSize(payload), signatures.ints(1007)[0], "the payload's size once unpacked");
        assertEquals(readCpioSize(payload), header.ints(1046)[0]);
    }

    private static int readCpioSize(byte[] payload) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(payload))) {
            return in.readAllBytes().length;
        }
    }

    @Test
    void theHeaderNamesThePackageAndWhatItNeedsFromRpm() {
        Header signatures = readHeader(file, 96);
        Header header = readHeader(file, 96 + (signatures.bytes().length + 7) / 8 * 8);

        assertEquals("my-app", header.string(1000));
        assertEquals("1.2.3.beta", header.string(1001));
        assertEquals("1", header.string(1002));
        assertEquals("Does things across two lines", header.string(1004));
        assertEquals("linux", header.string(1021));
        assertEquals("x86_64", header.string(1022));
        assertEquals("cpio", header.string(1124));
        assertEquals("gzip", header.string(1125));
        assertEquals(8, header.ints(5011)[0], "SHA-256 file digests");
        assertEquals(List.of("C"), header.strings(100));
        assertEquals(List.of("rpmlib(CompressedFileNames)", "rpmlib(FileDigests)", "rpmlib(PayloadFilesHavePrefix)"), header.strings(1049));
        assertEquals(3, header.ints(1048).length);
        assertEquals(List.of("my-app"), header.strings(1047));
    }

    @Test
    void theFileListAndThePayloadAgree() throws Exception {
        Header signatures = readHeader(file, 96);
        int mainStart = 96 + (signatures.bytes().length + 7) / 8 * 8;
        Header header = readHeader(file, mainStart);
        byte[] compressed = java.util.Arrays.copyOfRange(file, mainStart + header.bytes().length, file.length);
        List<Cpio> payload;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            payload = readCpio(in.readAllBytes());
        }

        List<String> baseNames = header.strings(1117), directories = header.strings(1118), digests = header.strings(1035), links = header.strings(1036);
        int[] dirIndexes = header.ints(1116), sizes = header.ints(1028), inodes = header.ints(1096);
        short[] modes = (short[]) header.tags().get(1030);
        assertEquals(payload.size(), baseNames.size());

        List<String> paths = new ArrayList<>();
        for (int i = 0; i < baseNames.size(); i++) {
            String path = directories.get(dirIndexes[i]) + baseNames.get(i);
            paths.add(path);
            Cpio entry = payload.get(i);
            assertEquals("." + path, entry.name(), "the payload is in file-list order, every name with a ./ prefix");
            assertEquals(i + 1, entry.number());
            assertEquals(i + 1, inodes[i]);
            assertEquals(entry.mode(), modes[i] & 0xFFFF, path);
            int type = entry.mode() & 0170000;
            if (type == REGULAR) {
                assertEquals(entry.data().length, sizes[i], path);
                assertEquals(hex(digest("SHA-256", entry.data())), digests.get(i), path);
                assertEquals("", links.get(i));
            } else if (type == LINK) {
                assertEquals(new String(entry.data(), StandardCharsets.UTF_8), links.get(i), path);
                assertEquals("", digests.get(i));
            } else {
                assertEquals(DIRECTORY, type, path);
                assertEquals(0, entry.data().length);
                assertEquals("", digests.get(i));
            }
        }
        List<String> sorted = new ArrayList<>(paths);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, paths, "the file list is sorted by path");
        assertEquals(paths.size(), new java.util.HashSet<>(paths).size(), "and has no path twice");

        assertEquals(List.of(
                "/opt/My App", "/opt/My App/bin", "/opt/My App/bin/My App", "/opt/My App/lib", "/opt/My App/lib/app", "/opt/My App/lib/app/app.jar",
                "/opt/My App/lib/empty.txt", "/opt/My App/lib/runtime", "/opt/My App/lib/runtime/bin", "/opt/My App/lib/runtime/bin/java",
                "/usr/bin/my-app", "/usr/share/applications/my-app.desktop", "/usr/share/pixmaps/my-app.png"), paths);
        Map<String, Cpio> byPath = new TreeMap<>();
        for (int i = 0; i < paths.size(); i++) byPath.put(paths.get(i), payload.get(i));
        assertEquals(0100755, byPath.get("/opt/My App/bin/My App").mode());
        assertEquals(0100755, byPath.get("/opt/My App/lib/runtime/bin/java").mode());
        assertEquals(0100644, byPath.get("/opt/My App/lib/app/app.jar").mode());
        assertEquals(040755, byPath.get("/opt/My App/lib").mode());
        assertEquals("/opt/My App/bin/My App", new String(byPath.get("/usr/bin/my-app").data(), StandardCharsets.UTF_8));
        assertTrue(new String(byPath.get("/usr/share/applications/my-app.desktop").data(), StandardCharsets.UTF_8).contains("Exec=\"/opt/My App/bin/My App\""));

        long installed = 0;
        for (Cpio entry : payload) if ((entry.mode() & 0170000) == REGULAR) installed += entry.data().length;
        assertEquals(installed, header.ints(1009)[0] & 0xFFFFFFFFL, "the installed size");
    }

    private static final int REGULAR = 0100000, LINK = 0120000, DIRECTORY = 040000;
}
