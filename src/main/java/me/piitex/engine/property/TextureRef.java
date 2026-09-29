package me.piitex.engine.property;

import me.piitex.engine.graphics.Texture;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A reference to an image file, for use as a {@link Property}. It is saved as its path, and an editor knows to offer
 * an image picker for it, which it could not for a plain {@link String}.
 * <pre>{@code
 * @Property public TextureRef image = TextureRef.of("assets/hero.png");
 *
 * Texture texture = image.load();
 * }</pre>
 * The path is a file path. A relative one is relative to the {@link #setBaseDirectory base directory}, which is the
 * working directory unless something (an editor with a game project open) sets another. A reference is immutable;
 * point a property at another image by assigning a new one.
 */
public final class TextureRef {
    private static volatile Path baseDirectory;

    private final String path;
    private volatile Texture texture;

    private TextureRef(String path) {
        this.path = path;
    }

    public static TextureRef of(String path) {
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("path must not be empty");
        return new TextureRef(path);
    }

    public String getPath() {
        return path;
    }

    /**
     * Where relative paths are found from; null, the default, is the working directory. An editor sets it to the
     * game project it has open, so the paths in the project's files work as they do when the game runs. References
     * already loaded keep their image.
     */
    public static void setBaseDirectory(Path directory) {
        baseDirectory = directory;
    }

    public static Path getBaseDirectory() {
        return baseDirectory;
    }

    /**
     * The file the path names: the path itself if it is absolute or there is no base directory, else the path
     * under the base directory.
     */
    public Path resolve() {
        Path file = Path.of(path);
        Path base = baseDirectory;
        return base == null || file.isAbsolute() ? file : base.resolve(file);
    }

    /**
     * The texture, loaded on the first call and kept after. Decoded images are shared between every load of the same
     * file, so several references to one path cost one image.
     *
     * @throws RuntimeException if the file can't be read or decoded
     */
    public Texture load() {
        Texture loaded = texture;
        if (loaded == null) {
            loaded = Texture.load(resolve().toFile());
            texture = loaded;
        }
        return loaded;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TextureRef other && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    @Override
    public String toString() {
        return path;
    }
}
