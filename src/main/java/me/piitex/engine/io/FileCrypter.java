package me.piitex.cca.crypto;

import me.piitex.engine.io.AppEnvironment;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * AES-256-GCM encryption for everything the user writes: characters, users, chats, the hub layout and API keys. The key is generated once per install and kept in
 * secret.key in the data directory. On its own that only stops someone casually opening the files, the key lives
 * right next to the data.
 * <p>
 * An app password fixes that. It doesn't re-encrypt anything: the same key is kept in secret.key but locked with
 * a key made from the password (PBKDF2), so nothing can be read until the password is given. A forgotten password
 * can't be recovered.
 * <p>
 * Every encrypt picks a new IV and puts it in front of the output, so decrypt reads it back from there.
 */
public final class FileCrypter {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int KEY_BYTES = 32;

    // A locked secret.key is this, the PBKDF2 rounds, the salt, the IV and the encrypted key. A plain one is just the
    // 32 key bytes, so anything longer than that is locked.
    private static final byte[] MAGIC = "CCAK1".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_BYTES = 16;
    private static final int ITERATIONS = 600_000;
    public static final int MIN_PASSWORD_LENGTH = 8;

    // The key once it's been read or unlocked, so the password is only asked for once a run.
    private static volatile SecretKey loaded;

    private final SecretKey key;

    private FileCrypter(SecretKey key) {
        this.key = key;
    }

    // Loads secret.key, or creates one on the first run. A locked key has to be unlocked first.
    public static FileCrypter forEnvironment(AppEnvironment environment) throws IOException {
        return new FileCrypter(loadKey(environment));
    }

    // What's wrong with a new password, or null if it's fine.
    public static String passwordProblem(String password, String confirm) {
        if (password.length() < MIN_PASSWORD_LENGTH) return "Use at least " + MIN_PASSWORD_LENGTH + " characters.";
        if (!password.equals(confirm)) return "The passwords don't match.";
        return null;
    }

    // True when the app password is set and hasn't been entered yet this run.
    public static boolean isLocked(AppEnvironment environment) {
        return loaded == null && isPasswordProtected(environment);
    }

    public static boolean isPasswordProtected(AppEnvironment environment) {
        try {
            Path keyFile = environment.getDataPath("secret.key");
            return Files.exists(keyFile) && Files.size(keyFile) > KEY_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    // False if the password is wrong. Slow on purpose, so keep it off the render thread.
    public static boolean unlock(AppEnvironment environment, char[] password) throws IOException {
        Path keyFile = environment.getDataPath("secret.key");
        byte[] stored = Files.readAllBytes(keyFile);
        if (stored.length <= KEY_BYTES) return true;
        byte[] keyBytes = unwrap(stored, password);
        if (keyBytes == null) return false;
        loaded = new SecretKeySpec(keyBytes, "AES");
        Arrays.fill(keyBytes, (byte) 0);
        return true;
    }

    // Locks the key with a password. The key has to be readable, so unlock first if it's already protected.
    public static void setPassword(AppEnvironment environment, char[] password) throws IOException {
        byte[] keyBytes = loadKey(environment).getEncoded();
        writeKey(environment, wrap(keyBytes, password));
        Arrays.fill(keyBytes, (byte) 0);
    }

    // Checks the current password first. Returns false if it's wrong.
    public static boolean changePassword(AppEnvironment environment, char[] current, char[] password) throws IOException {
        if (!verify(environment, current)) return false;
        setPassword(environment, password);
        return true;
    }

    // Puts the plain key back. Returns false if the password is wrong.
    public static boolean removePassword(AppEnvironment environment, char[] current) throws IOException {
        if (!verify(environment, current)) return false;
        byte[] keyBytes = loadKey(environment).getEncoded();
        writeKey(environment, keyBytes);
        Arrays.fill(keyBytes, (byte) 0);
        return true;
    }

    // For a forgotten password. Everything encrypted with the old key is unreadable after this, so the caller has to
    // clear it out, and the next run makes a new key.
    public static void discardKey(AppEnvironment environment) throws IOException {
        loaded = null;
        Files.deleteIfExists(environment.getDataPath("secret.key"));
    }

    private static boolean verify(AppEnvironment environment, char[] password) throws IOException {
        Path keyFile = environment.getDataPath("secret.key");
        if (!Files.exists(keyFile)) return false;
        byte[] stored = Files.readAllBytes(keyFile);
        if (stored.length <= KEY_BYTES) return true; // No password to check.
        byte[] keyBytes = unwrap(stored, password);
        if (keyBytes == null) return false;
        Arrays.fill(keyBytes, (byte) 0);
        return true;
    }

    private static SecretKey loadKey(AppEnvironment environment) throws IOException {
        SecretKey cached = loaded;
        if (cached != null) return cached;

        Path keyFile = environment.getDataPath("secret.key");
        byte[] keyBytes;
        if (Files.exists(keyFile)) {
            keyBytes = Files.readAllBytes(keyFile);
            if (keyBytes.length > KEY_BYTES) throw new IllegalStateException("The app password hasn't been entered yet.");
        } else {
            keyBytes = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(keyBytes);
            writeKey(environment, keyBytes);
        }
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        loaded = key;
        return key;
    }

    // Written next to secret.key first and moved over it, so losing power halfway can't leave half a key.
    private static void writeKey(AppEnvironment environment, byte[] contents) throws IOException {
        Path keyFile = environment.getDataPath("secret.key");
        Files.createDirectories(keyFile.getParent());
        Path temp = keyFile.resolveSibling("secret.key.tmp");
        Files.write(temp, contents);
        Files.move(temp, keyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static byte[] wrap(byte[] keyBytes, char[] password) throws IOException {
        try {
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            SecureRandom random = new SecureRandom();
            random.nextBytes(salt);
            random.nextBytes(iv);

            byte[] header = ByteBuffer.allocate(MAGIC.length + 4 + SALT_BYTES + IV_BYTES)
                    .put(MAGIC).putInt(ITERATIONS).put(salt).put(iv).array();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, passwordKey(password, salt, ITERATIONS), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(header); // The header can't be swapped for another one without it failing.
            byte[] encrypted = cipher.doFinal(keyBytes);

            byte[] out = new byte[header.length + encrypted.length];
            System.arraycopy(header, 0, out, 0, header.length);
            System.arraycopy(encrypted, 0, out, header.length, encrypted.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not lock the key", e);
        }
    }

    // Null when the password is wrong.
    private static byte[] unwrap(byte[] stored, char[] password) throws IOException {
        int headerLength = MAGIC.length + 4 + SALT_BYTES + IV_BYTES;
        if (stored.length <= headerLength || !Arrays.equals(Arrays.copyOf(stored, MAGIC.length), MAGIC)) {
            throw new IOException("secret.key is not in a format this version knows.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(stored);
        buffer.position(MAGIC.length);
        int iterations = buffer.getInt();
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        buffer.get(salt);
        buffer.get(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, passwordKey(password, salt, iterations), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(stored, 0, headerLength);
            return cipher.doFinal(stored, headerLength, stored.length - headerLength);
        } catch (javax.crypto.AEADBadTagException e) {
            return null;
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not unlock the key", e);
        }
    }

    private static SecretKey passwordKey(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BYTES * 8);
        try {
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(derived, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    // A name that can't be turned back into the label without the key, for folders that would otherwise give away a
    // character's id. The same label always gives the same name.
    public String scramble(String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
            byte[] hash = mac.doFinal(("cca-name:" + label).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not scramble a name", e);
        }
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] out = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ciphertext, 0, out, IV_BYTES, ciphertext.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt data", e);
        }
    }

    public byte[] decrypt(byte[] data) {
        try {
            byte[] iv = Arrays.copyOfRange(data, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(data, IV_BYTES, data.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt data. The file is corrupt or from a different install.", e);
        }
    }
}
