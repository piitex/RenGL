package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.scene.TextureData;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The pictures a scene file names, read once and shared: a texture used by many materials is one {@link TextureData},
 * and so one picture on the GPU. A picture is read again when its file has changed.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
final class Textures {
    private static final Logger log = LogManager.getLogger("Engine");
    private static final int LIMIT = 64;

    private record Key(Path file, long modified) {
    }

    private static final Map<Key, TextureData> CACHE = new HashMap<>();
    private static final Map<Key, me.piitex.engine.render3d.scene.HdrTextureData> HDR_CACHE = new HashMap<>();
    private static final Map<Path, Boolean> REPORTED = new HashMap<>();

    private Textures() {
    }

    /**
     * The picture at {@code path} (relative to {@code base}), or null if it isn't there or can't be read, which is
     * logged once.
     */
    static synchronized TextureData load(String path, Path base) {
        if (path == null || path.isEmpty()) return null;
        Path file = base.resolve(path).toAbsolutePath().normalize();
        try {
            Key key = new Key(file, Files.getLastModifiedTime(file).toMillis());
            TextureData cached = CACHE.get(key);
            if (cached != null) return cached;
            TextureData loaded = TextureData.load(file);
            if (CACHE.size() >= LIMIT) CACHE.clear();
            CACHE.put(key, loaded);
            REPORTED.remove(file);
            return loaded;
        } catch (IOException | RuntimeException e) {
            if (REPORTED.put(file, true) == null) log.warn("Could not read the picture {}: {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * The same for a picture of light with no upper limit (a Radiance .hdr file).
     */
    static synchronized me.piitex.engine.render3d.scene.HdrTextureData loadHdr(String path, Path base) {
        if (path == null || path.isEmpty()) return null;
        Path file = base.resolve(path).toAbsolutePath().normalize();
        try {
            Key key = new Key(file, Files.getLastModifiedTime(file).toMillis());
            var cached = HDR_CACHE.get(key);
            if (cached != null) return cached;
            var loaded = me.piitex.engine.render3d.scene.HdrTextureData.load(file);
            if (HDR_CACHE.size() >= 8) HDR_CACHE.clear();
            HDR_CACHE.put(key, loaded);
            REPORTED.remove(file);
            return loaded;
        } catch (IOException | RuntimeException e) {
            if (REPORTED.put(file, true) == null) log.warn("Could not read the picture {}: {}", file, e.getMessage());
            return null;
        }
    }
}
