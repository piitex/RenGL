package me.piitex.engine.io;

import me.piitex.engine.config.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class AssetManager {
    private final List<FileSystem> mountedPacks = new ArrayList<>();
    private final Path localAssetDirectory;
    private static final Logger logger = LogManager.getLogger("Engine");

    public AssetManager(String fallBackAssetDirectory) {
        this.localAssetDirectory = Paths.get(fallBackAssetDirectory);
    }

    /**
     * Mounts a .pak (zip) file into the virtual file system.
     * @return The newly created {@link FileSystem} for the asset pack.
     */
    public FileSystem mountPack(String fallBackDirectory) throws IOException {
        Path path = Paths.get(fallBackDirectory);
        FileSystem fs = FileSystems.newFileSystem(path, (ClassLoader) null);
        mountedPacks.add(fs);
        logger.info("Successfully mounted pack: {}", fallBackDirectory);
        return fs;
    }

    public void unmountPack(FileSystem fileSystem) {
        mountedPacks.remove(fileSystem);
        try {
            fileSystem.close();
        } catch (IOException e) {
            logger.error("Could not unmount file-system!", e);
        }
    }

    public void unmountAll() {
        for (FileSystem fileSystem : new ArrayList<>(mountedPacks)) {
            mountedPacks.remove(fileSystem);

            try {
                fileSystem.close();
            } catch (IOException e) {
                logger.error("Could not unmount file-system!", e);
            }
        }
    }

    /**
     * Resolves the path by checking mounted packs first, then local folders.
     */
    private Path resolveAssetPath(String assetName) throws FileNotFoundException {
        // Strip leading slash to prevent Path.resolve from treating it as an absolute root path
        if (assetName.startsWith("/") || assetName.startsWith("\\")) {
            assetName = assetName.substring(1);
        }

        // 1. Check mounted .pak files first (Release mode priority)
        for (FileSystem fs : mountedPacks) {
            Path pathInZip = fs.getPath(assetName);
            if (Files.exists(pathInZip)) {
                return pathInZip;
            }
        }

        // 2. Fallback to loose files (Development mode priority)
        Path localPath = localAssetDirectory.resolve(assetName);
        if (Files.exists(localPath)) {
            return localPath;
        }

        throw new FileNotFoundException("Asset not found in packs or local directory: " + assetName + " (Resolved to: " + localPath.toAbsolutePath() + ")");
    }

    /**
     * Reads the asset into a Direct ByteBuffer for LWJGL native C interop.
     */
    public ByteBuffer loadAssetAsBuffer(String assetName) {
        try {
            Path assetPath = resolveAssetPath(assetName);
            return readToDirectBuffer(assetPath);
        } catch (IOException e) {
            throw new RuntimeException("Could not load asset: " + assetName, e);
        }
    }

    /**
     * Reads the asset into a String. Ideal for loading shader source code (.glsl).
     */
    public String loadAssetAsString(String assetName) {
        try {
            Path assetPath = resolveAssetPath(assetName);
            return Files.readString(assetPath);
        } catch (IOException e) {
            throw new RuntimeException("Could not load asset as string: " + assetName, e);
        }
    }

    /**
     * Loads a {@link Config}. Unlike other assets, the loose file in the fallback directory is checked
     * <b>first</b>, then mounted packs: packs ship the defaults, and the loose file holds the user's
     * saved changes (see {@link #saveConfig}), so it has to win.
     *
     * @throws RuntimeException if the config exists nowhere, or cannot be parsed
     */
    public Config loadConfig(String assetName) {
        try {
            Path path = resolveConfigPath(assetName);
            return Config.parse(Files.readString(path));
        } catch (IOException e) {
            throw new RuntimeException("Could not load config: " + assetName, e);
        }
    }

    /** Like {@link #loadConfig}, but returns an empty config if the file does not exist yet. */
    public Config loadConfigOrEmpty(String assetName) {
        try {
            resolveConfigPath(assetName);
        } catch (FileNotFoundException e) {
            return new Config();
        }
        return loadConfig(assetName);
    }

    /**
     * Saves a config as a loose file under the fallback directory (packs are read-only), creating
     * folders as needed. The next {@link #loadConfig} for this name returns the saved copy.
     */
    public void saveConfig(String assetName, Config config) {
        try {
            config.save(localConfigPath(assetName));
        } catch (IOException e) {
            throw new RuntimeException("Could not save config: " + assetName, e);
        }
    }

    private Path resolveConfigPath(String assetName) throws FileNotFoundException {
        Path local = localConfigPath(assetName);
        return Files.exists(local) ? local : resolveAssetPath(assetName);
    }

    private Path localConfigPath(String assetName) {
        if (assetName.startsWith("/") || assetName.startsWith("\\")) {
            assetName = assetName.substring(1);
        }
        Path base = localAssetDirectory.toAbsolutePath().normalize();
        Path resolved = base.resolve(assetName).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Config path escapes the asset directory: " + assetName);
        }
        return resolved;
    }

    private ByteBuffer readToDirectBuffer(Path path) throws IOException {
        ByteBuffer buffer;
        try (ReadableByteChannel channel = Files.newByteChannel(path)) {
            int bufferSize = (int) Files.size(path);
            // Native LWJGL libraries require direct (off-heap) buffers
            buffer = ByteBuffer.allocateDirect(bufferSize + 1);

            while (channel.read(buffer) != -1) {
                // Keep reading until EOF
            }

            buffer.flip();
        }
        return buffer;
    }

    public void cleanup() {
        for (FileSystem fs : mountedPacks) {
            try {
                fs.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}