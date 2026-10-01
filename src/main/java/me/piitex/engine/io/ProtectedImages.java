package me.piitex.cca.crypto;

import me.piitex.cca.App;
import me.piitex.engine.ui.overlays.ImageOverlay;
import org.jetbrains.skia.Image;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

// The icons of characters, users and templates. On disk they're encrypted like everything else the user made, so
// nothing can be looked at from the file system. Decoding happens here, the engine only ever sees the decoded picture.
// A file without the header is read as a plain image, that's a picture just picked from the user's own folders.
public final class ProtectedImages {
    private static final byte[] MAGIC = "CCAI1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_CACHED = 64;

    private record Cached(long modified, long size, Image image) {
    }

    // Least recently used goes first. Nothing is closed on the way out, an overlay may still be drawing the picture.
    private static final Map<Path, Cached> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Cached> eldest) {
            return size() > MAX_CACHED;
        }
    };

    private ProtectedImages() {
    }

    public static boolean isProtected(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            byte[] head = new byte[MAGIC.length];
            return in.read(head) == MAGIC.length && Arrays.equals(head, MAGIC);
        }
    }

    // Reads source (plain or encrypted) and writes it to dest encrypted. Source and dest can be the same file.
    public static void write(FileCrypter crypter, Path dest, Path source) throws IOException {
        if (isSame(dest, source) && isProtected(source)) return;
        byte[] encrypted = crypter.encrypt(read(crypter, source));
        byte[] contents = new byte[MAGIC.length + encrypted.length];
        System.arraycopy(MAGIC, 0, contents, 0, MAGIC.length);
        System.arraycopy(encrypted, 0, contents, MAGIC.length, encrypted.length);

        Files.createDirectories(dest.toAbsolutePath().getParent());
        Path temp = dest.resolveSibling(dest.getFileName() + ".tmp");
        Files.write(temp, contents);
        try {
            Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        synchronized (CACHE) {
            CACHE.remove(dest.toAbsolutePath().normalize());
        }
    }

    // The image file's bytes, decrypted if they were encrypted.
    public static byte[] read(Path file) throws IOException {
        return read(FileCrypter.forEnvironment(App.environment), file);
    }

    private static byte[] read(FileCrypter crypter, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length <= MAGIC.length || !Arrays.equals(bytes, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) return bytes;
        return crypter.decrypt(Arrays.copyOfRange(bytes, MAGIC.length, bytes.length));
    }

    // One decoded picture per file, since the hubs draw every card again on each render. Null if it can't be read.
    public static Image load(Path file) {
        Path key = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(key)) return null;
        try {
            long modified = Files.getLastModifiedTime(key).toMillis();
            long size = Files.size(key);
            synchronized (CACHE) {
                Cached cached = CACHE.get(key);
                if (cached != null && cached.modified() == modified && cached.size() == size) return cached.image();
            }
            Image image = Image.Companion.makeFromEncoded(read(key));
            if (image == null) return null;
            synchronized (CACHE) {
                CACHE.put(key, new Cached(modified, size, image));
            }
            return image;
        } catch (IOException | RuntimeException e) {
            App.logger.warn("Could not read an icon", e);
            return null;
        }
    }

    // The picture sized to the box, or null if the file isn't a readable image.
    public static ImageOverlay overlay(Path file, double width, double height) {
        Image image = load(file);
        return image == null ? null : ImageOverlay.of(image, width, height);
    }

    private static boolean isSame(Path a, Path b) {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }
}
