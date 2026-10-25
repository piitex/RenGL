package me.piitex.engine.packager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Makes an RPM package ({@code .rpm}) of a Linux app-image, without rpmbuild: a lead, a signature header (sizes and
 * digests, nothing signed), the package header, and a gzip-compressed cpio archive of the files. It installs what
 * {@link DebPackage} does: the image under {@code /opt/<name>}, the launcher linked into {@code /usr/bin}, and a menu entry
 * with the icon.
 */
final class RpmPackage {
    // Header entry types.
    private static final int INT16 = 3, INT32 = 4, STRING = 6, BIN = 7, STRING_ARRAY = 8, I18N_STRING = 9;

    // Tags of the package header.
    private static final int HEADER_IMMUTABLE = 63, HEADER_I18N_TABLE = 100;
    private static final int NAME = 1000, VERSION = 1001, RELEASE = 1002, SUMMARY = 1004, DESCRIPTION = 1005, BUILD_TIME = 1006,
            BUILD_HOST = 1007, SIZE = 1009, LICENSE = 1014, PACKAGER = 1015, GROUP = 1016, OS = 1021, ARCH = 1022,
            FILE_SIZES = 1028, FILE_MODES = 1030, FILE_RDEVS = 1033, FILE_MTIMES = 1034, FILE_DIGESTS = 1035,
            FILE_LINK_TOS = 1036, FILE_FLAGS = 1037, FILE_USER = 1039, FILE_GROUP = 1040, FILE_VERIFY_FLAGS = 1045,
            ARCHIVE_SIZE = 1046, PROVIDE_NAME = 1047, REQUIRE_FLAGS = 1048, REQUIRE_NAME = 1049, REQUIRE_VERSION = 1050,
            RPM_VERSION = 1064, FILE_DEVICES = 1095, FILE_INODES = 1096, FILE_LANGS = 1097, PROVIDE_FLAGS = 1112,
            PROVIDE_VERSION = 1113, DIR_INDEXES = 1116, BASE_NAMES = 1117, DIR_NAMES = 1118, PAYLOAD_FORMAT = 1124,
            PAYLOAD_COMPRESSOR = 1125, PAYLOAD_FLAGS = 1126, FILE_DIGEST_ALGO = 5011;

    // Tags of the signature header.
    private static final int HEADER_SIGNATURES = 62, SHA1 = 269, SHA256 = 273, SIG_SIZE = 1000, MD5 = 1004, PAYLOAD_SIZE = 1007;

    private static final int FLAG_EQUAL = 0x08, FLAG_LESS_OR_EQUAL = 0x0A, FLAG_RPMLIB = 0x01000000;
    private static final int SHA256_ALGORITHM = 8;
    private static final int DIRECTORY = 0755;

    private RpmPackage() {
    }

    /** One thing the package installs. {@code source} is a file to copy, {@code content} the bytes of a small one or the target of a link. */
    record Item(String path, int mode, Path source, byte[] content, long size) {
        boolean isFile() {
            return (mode & 0170000) == Cpio.FILE;
        }

        boolean isLink() {
            return (mode & 0170000) == Cpio.SYMLINK;
        }
    }

    static Path build(AppSpec app, Platform platform, Path image, Path outputDir) throws IOException {
        String pkg = DebPackage.packageName(app.name());
        String version = rpmVersion(app.version());
        String arch = platform.arch().equals("x64") ? "x86_64" : "aarch64";
        Path rpm = outputDir.resolve(app.name() + "-" + app.version() + "-" + platform.id() + ".rpm");

        List<Item> items = items(app, pkg, image);
        long now = System.currentTimeMillis() / 1000;

        Path payload = Files.createTempFile("rengl-payload", ".cpio.gz");
        try {
            long archiveSize = writePayload(items, now, payload);
            byte[] header = packageHeader(app, pkg, version, arch, items, now, archiveSize);
            byte[] signatures = signatureHeader(header, payload, archiveSize);
            try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(rpm), 1 << 16)) {
                out.write(lead(pkg + "-" + version + "-1", arch));
                out.write(signatures);
                out.write(header);
                Files.copy(payload, out);
            }
        } finally {
            Files.deleteIfExists(payload);
        }
        return rpm;
    }

    /** RPM versions may not contain a hyphen, and want to start with a digit. */
    static String rpmVersion(String version) {
        String v = version.replaceAll("[^A-Za-z0-9._+~^]", ".");
        return v.isEmpty() || !Character.isDigit(v.charAt(0)) ? "0." + v : v;
    }

    // ---- what goes in ---------------------------------------------------------------------------------------------

    /** Everything the package owns, sorted by path as the file list has to be. The system's own folders ({@code /opt}, {@code /usr/bin}) are not ours to list. */
    static List<Item> items(AppSpec app, String pkg, Path image) throws IOException {
        String home = "/opt/" + app.name();
        Map<String, Item> items = new TreeMap<>();
        items.put(home, directory(home));
        try (Stream<Path> walk = Files.walk(image)) {
            for (Path path : (Iterable<Path>) walk.skip(1)::iterator) {
                String name = home + "/" + image.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    items.put(name, directory(name));
                } else {
                    int mode = Cpio.FILE | (ImageBuilder.isExecutable(image, path) ? 0755 : 0644);
                    items.put(name, new Item(name, mode, path, null, Files.size(path)));
                }
            }
        }
        byte[] target = (home + "/bin/" + app.name()).getBytes(StandardCharsets.UTF_8);
        items.put("/usr/bin/" + pkg, new Item("/usr/bin/" + pkg, Cpio.SYMLINK | 0777, null, target, target.length));

        Path icon = app.icons().get("png");
        if (icon != null) {
            String name = "/usr/share/pixmaps/" + pkg + ".png";
            items.put(name, new Item(name, Cpio.FILE | 0644, icon, null, Files.size(icon)));
        }
        byte[] entry = DebPackage.desktopEntry(app, pkg, home, icon != null).getBytes(StandardCharsets.UTF_8);
        String desktop = "/usr/share/applications/" + pkg + ".desktop";
        items.put(desktop, new Item(desktop, Cpio.FILE | 0644, null, entry, entry.length));
        return new ArrayList<>(items.values());
    }

    private static Item directory(String path) {
        return new Item(path, Cpio.DIRECTORY | DIRECTORY, null, null, 0);
    }

    /** Writes the gzip-compressed cpio archive and returns its size before compression. */
    private static long writePayload(List<Item> items, long mtime, Path file) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file), 1 << 16)) {
            Cpio.Writer cpio = new Cpio.Writer(out);
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                String name = "." + item.path();   // rpm wants every name to start with "./"
                if (item.source() != null) cpio.file(name, i + 1, item.mode(), mtime, item.source());
                else cpio.entry(name, i + 1, item.mode(), mtime, item.content() == null ? new byte[0] : item.content());
            }
            cpio.finish();
            return cpio.size();
        }
    }

    // ---- the package header ---------------------------------------------------------------------------------------

    private static byte[] packageHeader(AppSpec app, String pkg, String version, String arch, List<Item> items, long now, long archiveSize) throws IOException {
        String summary = app.description().replace('\n', ' ');
        Header h = new Header();
        h.strings(HEADER_I18N_TABLE, List.of("C"));
        h.string(NAME, pkg);
        h.string(VERSION, version);
        h.string(RELEASE, "1");
        h.i18n(SUMMARY, summary);
        h.i18n(DESCRIPTION, summary);
        h.int32(BUILD_TIME, (int) now);
        h.string(BUILD_HOST, "localhost");
        long installed = 0;
        for (Item item : items) if (item.isFile()) installed += item.size();
        h.int32(SIZE, (int) Math.min(installed, Integer.MAX_VALUE));
        h.string(LICENSE, "Proprietary");
        h.string(PACKAGER, app.vendor().isEmpty() ? app.name() : app.vendor());
        h.i18n(GROUP, "Unspecified");
        h.string(OS, "linux");
        h.string(ARCH, arch);

        // The file list: names as a folder and a name in it, so a long folder is written once.
        List<String> directories = new ArrayList<>();
        Map<String, Integer> directoryIndex = new LinkedHashMap<>();
        int count = items.size();
        int[] sizes = new int[count], mtimes = new int[count], flags = new int[count], verify = new int[count], devices = new int[count], inodes = new int[count], dirIndexes = new int[count];
        short[] modes = new short[count], rdevs = new short[count];
        List<String> baseNames = new ArrayList<>(), digests = new ArrayList<>(), linkTargets = new ArrayList<>(), users = new ArrayList<>(), groups = new ArrayList<>(), languages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Item item = items.get(i);
            int slash = item.path().lastIndexOf('/');
            String folder = item.path().substring(0, slash + 1);
            Integer index = directoryIndex.get(folder);
            if (index == null) {
                index = directories.size();
                directoryIndex.put(folder, index);
                directories.add(folder);
            }
            dirIndexes[i] = index;
            baseNames.add(item.path().substring(slash + 1));
            sizes[i] = (int) Math.min(item.size(), Integer.MAX_VALUE);
            modes[i] = (short) item.mode();
            mtimes[i] = (int) now;
            verify[i] = -1;
            devices[i] = 1;
            inodes[i] = i + 1;
            digests.add(item.isFile() ? sha256(item) : "");
            linkTargets.add(item.isLink() ? new String(item.content(), StandardCharsets.UTF_8) : "");
            users.add("root");
            groups.add("root");
            languages.add("");
        }
        h.int32(FILE_SIZES, sizes);
        h.int16(FILE_MODES, modes);
        h.int16(FILE_RDEVS, rdevs);
        h.int32(FILE_MTIMES, mtimes);
        h.strings(FILE_DIGESTS, digests);
        h.strings(FILE_LINK_TOS, linkTargets);
        h.int32(FILE_FLAGS, new int[count]);
        h.strings(FILE_USER, users);
        h.strings(FILE_GROUP, groups);
        h.int32(FILE_VERIFY_FLAGS, verify);
        h.int32(ARCHIVE_SIZE, (int) Math.min(archiveSize, Integer.MAX_VALUE));
        h.strings(PROVIDE_NAME, List.of(pkg));
        h.int32(PROVIDE_FLAGS, FLAG_EQUAL);
        h.strings(PROVIDE_VERSION, List.of(version + "-1"));
        // What this package relies on rpm itself to understand.
        h.strings(REQUIRE_NAME, List.of("rpmlib(CompressedFileNames)", "rpmlib(FileDigests)", "rpmlib(PayloadFilesHavePrefix)"));
        h.int32(REQUIRE_FLAGS, FLAG_RPMLIB | FLAG_LESS_OR_EQUAL, FLAG_RPMLIB | FLAG_LESS_OR_EQUAL, FLAG_RPMLIB | FLAG_LESS_OR_EQUAL);
        h.strings(REQUIRE_VERSION, List.of("3.0.4-1", "4.6.0-1", "4.0-1"));
        h.string(RPM_VERSION, "4.18.0");
        h.int32(FILE_DEVICES, devices);
        h.int32(FILE_INODES, inodes);
        h.strings(FILE_LANGS, languages);
        h.int32(DIR_INDEXES, dirIndexes);
        h.strings(BASE_NAMES, baseNames);
        h.strings(DIR_NAMES, directories);
        h.string(PAYLOAD_FORMAT, "cpio");
        h.string(PAYLOAD_COMPRESSOR, "gzip");
        h.string(PAYLOAD_FLAGS, "9");
        h.int32(FILE_DIGEST_ALGO, SHA256_ALGORITHM);
        return h.build(HEADER_IMMUTABLE);
    }

    private static String sha256(Item item) throws IOException {
        MessageDigest digest = digest("SHA-256");
        if (item.source() != null) {
            try (InputStream in = Files.newInputStream(item.source())) {
                update(digest, in);
            }
        } else if (item.content() != null) {
            digest.update(item.content());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // ---- the signature header and the lead ------------------------------------------------------------------------

    /** Sizes and digests, so the package can be checked; there is no signature. Padded so that the package header starts on an 8-byte boundary. */
    private static byte[] signatureHeader(byte[] header, Path payload, long archiveSize) throws IOException {
        MessageDigest md5 = digest("MD5");
        md5.update(header);
        try (InputStream in = Files.newInputStream(payload)) {
            update(md5, in);
        }
        long size = header.length + Files.size(payload);
        if (size > Integer.MAX_VALUE) throw new IOException("The package is too big for an rpm header (2 GB)");

        Header h = new Header();
        h.string(SHA1, HexFormat.of().formatHex(digest("SHA-1").digest(header)));
        h.string(SHA256, HexFormat.of().formatHex(digest("SHA-256").digest(header)));
        h.int32(SIG_SIZE, (int) size);
        h.bin(MD5, md5.digest());
        h.int32(PAYLOAD_SIZE, (int) Math.min(archiveSize, Integer.MAX_VALUE));
        byte[] bytes = h.build(HEADER_SIGNATURES);

        byte[] padded = new byte[(bytes.length + 7) / 8 * 8];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        return padded;
    }

    /** The 96 bytes at the start of every rpm. Modern rpm reads little from it, but checks the magic and the version. */
    private static byte[] lead(String nameVersionRelease, String arch) {
        ByteBuffer lead = ByteBuffer.allocate(96);
        lead.putInt(0xEDABEEDB);
        lead.put((byte) 3).put((byte) 0);   // format 3.0
        lead.putShort((short) 0);           // a binary package
        lead.putShort((short) (arch.equals("x86_64") ? 1 : 12));
        byte[] name = nameVersionRelease.getBytes(StandardCharsets.US_ASCII);
        lead.put(name, 0, Math.min(name.length, 65));
        lead.position(10 + 66);
        lead.putShort((short) 1);           // Linux
        lead.putShort((short) 5);           // signatures are in a header
        return lead.array();
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(MessageDigest digest, InputStream in) throws IOException {
        byte[] buffer = new byte[1 << 16];
        for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
    }

    // ---- a header structure ---------------------------------------------------------------------------------------

    /**
     * An rpm header: an index of (tag, type, offset, count) entries in tag order, then the data they point at. The first
     * entry is a "region" tag whose data is a trailer saying how many index entries the region covers, which is how rpm
     * knows the whole header is one signed-or-digested block. Everything is big-endian.
     */
    private static final class Header {
        private record Entry(int type, int count, byte[] data) {
        }

        private final TreeMap<Integer, Entry> entries = new TreeMap<>();

        void string(int tag, String value) {
            entries.put(tag, new Entry(STRING, 1, terminated(value)));
        }

        void i18n(int tag, String value) {
            entries.put(tag, new Entry(I18N_STRING, 1, terminated(value)));
        }

        void strings(int tag, List<String> values) {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            for (String value : values) data.writeBytes(terminated(value));
            entries.put(tag, new Entry(STRING_ARRAY, values.size(), data.toByteArray()));
        }

        void int32(int tag, int... values) {
            ByteBuffer data = ByteBuffer.allocate(4 * values.length);
            for (int value : values) data.putInt(value);
            entries.put(tag, new Entry(INT32, values.length, data.array()));
        }

        void int16(int tag, short... values) {
            ByteBuffer data = ByteBuffer.allocate(2 * values.length);
            for (short value : values) data.putShort(value);
            entries.put(tag, new Entry(INT16, values.length, data.array()));
        }

        void bin(int tag, byte[] value) {
            entries.put(tag, new Entry(BIN, value.length, value));
        }

        private static byte[] terminated(String value) {
            byte[] text = value.getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[text.length + 1];
            System.arraycopy(text, 0, bytes, 0, text.length);
            return bytes;
        }

        byte[] build(int regionTag) {
            int total = entries.size() + 1;   // the region tag counts
            ByteArrayOutputStream store = new ByteArrayOutputStream();
            ByteBuffer index = ByteBuffer.allocate(16 * total);

            // The region's own entry sorts first; its data goes last.
            index.position(16);
            for (Map.Entry<Integer, Entry> e : entries.entrySet()) {
                Entry entry = e.getValue();
                int alignment = switch (entry.type()) {
                    case INT16 -> 2;
                    case INT32 -> 4;
                    default -> 1;
                };
                while (store.size() % alignment != 0) store.write(0);
                index.putInt(e.getKey()).putInt(entry.type()).putInt(store.size()).putInt(entry.count());
                store.writeBytes(entry.data());
            }
            index.position(0);
            index.putInt(regionTag).putInt(BIN).putInt(store.size()).putInt(16);
            store.writeBytes(ByteBuffer.allocate(16).putInt(regionTag).putInt(BIN).putInt(-16 * total).putInt(16).array());

            ByteBuffer out = ByteBuffer.allocate(16 + index.capacity() + store.size());
            out.put(new byte[] {(byte) 0x8E, (byte) 0xAD, (byte) 0xE8, 0x01, 0, 0, 0, 0});
            out.putInt(total).putInt(store.size());
            out.put(index.array());
            out.put(store.toByteArray());
            return out.array();
        }
    }
}
