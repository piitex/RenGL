package me.piitex.engine.io;

import me.piitex.engine.config.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.function.Consumer;

// Config files that hold what the user wrote (characters, users, the hub layout). Same format as Config, but the
// text is encrypted on disk. A plain file from before they were encrypted still loads and is encrypted by the next save.
public final class EncryptedConfig {
    private static final byte[] MAGIC = "RENGLENGINE".getBytes(StandardCharsets.US_ASCII);

    private EncryptedConfig() {
    }

    public static boolean isEncrypted(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        return startsWithMagic(Files.readAllBytes(file));
    }

    // Loads the file, or starts empty if it isn't there. Defaults are filled in and the file is written when it's new,
    // was plain, or the defaults added something.
    public static Config loadOrCreate(FileCrypter crypter, Path file, Consumer<Config> defaults) throws IOException {
        boolean existed = Files.exists(file);
        boolean plain = false;
        Config config;
        if (existed) {
            byte[] bytes = Files.readAllBytes(file);
            plain = !startsWithMagic(bytes);
            String text = plain
                    ? new String(bytes, StandardCharsets.UTF_8)
                    : new String(crypter.decrypt(Arrays.copyOfRange(bytes, MAGIC.length, bytes.length)), StandardCharsets.UTF_8);
            config = Config.parse(text);
        } else {
            config = new Config();
        }
        config.setSourcePath(file);

        String before = config.toString();
        defaults.accept(config);
        if (!existed || plain || !before.equals(config.toString())) save(crypter, config);
        return config;
    }

    // Writes to the file the config was loaded from. The old file is replaced in one move so a crash can't leave half of it.
    public static void save(FileCrypter crypter, Config config) throws IOException {
        Path file = config.getSourcePath().toAbsolutePath();
        byte[] encrypted = crypter.encrypt(config.toString().getBytes(StandardCharsets.UTF_8));
        byte[] contents = new byte[MAGIC.length + encrypted.length];
        System.arraycopy(MAGIC, 0, contents, 0, MAGIC.length);
        System.arraycopy(encrypted, 0, contents, MAGIC.length, encrypted.length);

        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, contents);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean startsWithMagic(byte[] bytes) {
        return bytes.length > MAGIC.length && Arrays.equals(bytes, 0, MAGIC.length, MAGIC, 0, MAGIC.length);
    }
}
