package me.piitex.engine.graphics;

import me.piitex.engine.ui.image.ImageLoader;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.jetbrains.skia.Image;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An image that sprites are cut from, and how it is sampled when drawn scaled. Every {@link TextureRegion} cut from
 * a texture shares it, so {@link #setSmooth} applies to all of them at once.
 * <pre>{@code
 * Texture sheet = Texture.load("assets/hero.png");
 * sheet.setSmooth(false); // pixel art: keep hard pixel edges when scaled up
 * }</pre>
 * Loading can happen on any thread, for example in a {@link me.piitex.engine.scheduler.Loader} step; the image goes
 * to the graphics card the first time it is drawn.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class Texture {
    private final Image image;
    private volatile boolean smooth = true;

    public Texture(Image image) {
        if (image == null) throw new IllegalArgumentException("image must not be null");
        this.image = image;
    }

    /**
     * Loads a PNG, JPEG, WEBP, GIF or BMP file through {@link ImageLoader}, which shares one decoded image between
     * every load of the same file.
     */
    public static Texture load(File file) {
        return new Texture(ImageLoader.load(file));
    }

    public static Texture load(String path) {
        return load(new File(path));
    }

    /**
     * Loads an image from any path, including one inside a zip file system such as a mounted asset pack. Files on
     * the normal file system go through {@link ImageLoader} as with {@link #load(File)}.
     */
    public static Texture load(Path path) {
        if (path.getFileSystem() == FileSystems.getDefault()) return load(path.toFile());
        try {
            return decode(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read image: " + path, e);
        }
    }

    /**
     * Decodes an encoded image (the bytes of a PNG file, say). Not cached.
     */
    public static Texture decode(byte[] bytes) {
        Image image = Image.Companion.makeFromEncoded(bytes);
        if (image == null) throw new IllegalArgumentException("Not a decodable image");
        return new Texture(image);
    }

    public Image getImage() {
        return image;
    }

    public int getWidth() {
        return image.getWidth();
    }

    public int getHeight() {
        return image.getHeight();
    }

    /**
     * Whether drawing this texture scaled blends neighbouring pixels (true, the default) or keeps hard pixel edges
     * (false), as pixel art wants.
     */
    public void setSmooth(boolean smooth) {
        this.smooth = smooth;
    }

    public boolean isSmooth() {
        return smooth;
    }
}
