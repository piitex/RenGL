package me.piitex.engine.ui.background;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.image.ImageLoader;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.jetbrains.skia.Image;

import java.io.File;
import java.nio.file.Path;

/**
 * A picture behind an element or a window: the static background. How it fills the space is its {@link Fit}.
 * <pre>{@code
 * ImageBackground sky = new ImageBackground(new File("assets/sky.png"));
 * sky.setFit(ImageBackground.Fit.COVER);
 * window.setBackground(sky);
 * }</pre>
 * A layout or theme file names the picture by a path, which is read from the project's folder, and keeps the path when
 * the picture can't be found so it isn't lost; {@link #isMissing()} says so. It extends {@link AbstractBackground}, so
 * it has an opacity and can be faded.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public class ImageBackground extends AbstractBackground {
    /** How the picture fills the space it is behind. */
    public enum Fit {
        /** Fills all of it, keeping the picture's proportions and cutting off what doesn't fit. */
        COVER,
        /** Shows all of the picture, keeping its proportions, with the space left over on two sides empty. */
        CONTAIN,
        /** Fills all of it and shows all of the picture, squeezing or stretching it as needed. */
        STRETCH,
        /** Repeats the picture at its own size from the top left. */
        TILE
    }

    private static final long MAX_TILES = 20_000;

    private String path = "";
    private Image image;
    private boolean missing;
    private Fit fit = Fit.COVER;

    public ImageBackground() {
    }

    /** A background of the picture in {@code file}. */
    public ImageBackground(File file) {
        setPath(file.getPath());
        load(file);
    }

    /**
     * The path as it was given, or empty for none. A file keeps it, with any picture that couldn't be read.
     */
    public String getPath() {
        return path;
    }

    /**
     * Names the picture without reading it, which {@link #resolveFrom} does. An empty path is no picture.
     */
    public void setPath(String path) {
        String next = path == null ? "" : path.strip();
        if (next.equals(this.path)) return;
        this.path = next;
        this.image = null;
        this.missing = false;
    }

    /**
     * Reads the picture {@link #getPath()} names, looking for it in {@code base}. If it can't be read the picture is
     * left empty and {@link #isMissing()} is true.
     */
    public void resolveFrom(Path base) {
        if (path.isEmpty()) {
            image = null;
            missing = false;
            return;
        }
        load((base == null ? Path.of("") : base).resolve(path).toFile());
    }

    private void load(File file) {
        try {
            image = ImageLoader.load(file);
            missing = false;
        } catch (RuntimeException e) {
            image = null;
            missing = true;
        }
    }

    /** Uses a picture that is already decoded, such as one made in code. */
    public void setImage(Image image) {
        this.image = image;
        this.missing = false;
    }

    /** The picture, or null if there is none or it couldn't be read. */
    public Image getImage() {
        return image;
    }

    /** Whether a path is set but its picture couldn't be read. */
    public boolean isMissing() {
        return missing;
    }

    public Fit getFit() {
        return fit;
    }

    public void setFit(Fit fit) {
        this.fit = fit == null ? Fit.COVER : fit;
    }

    @Override
    protected void renderEffect(Renderer renderer, float x, float y, float width, float height) {
        Image picture = image;
        if (picture == null || !(width > 0) || !(height > 0)) return;
        float iw = picture.getWidth(), ih = picture.getHeight();
        if (!(iw > 0) || !(ih > 0)) return;

        renderer.pushClip(x, y, width, height);
        switch (fit) {
            case STRETCH -> renderer.drawImage(picture, x, y, width, height, 0f, 1f);
            case COVER -> {
                float scale = Math.max(width / iw, height / ih);
                float sw = width / scale, sh = height / scale;
                renderer.drawImageRegion(picture, (iw - sw) / 2f, (ih - sh) / 2f, sw, sh, x, y, width, height, null, true);
            }
            case CONTAIN -> {
                float scale = Math.min(width / iw, height / ih);
                float dw = iw * scale, dh = ih * scale;
                renderer.drawImage(picture, x + (width - dw) / 2f, y + (height - dh) / 2f, dw, dh, 0f, 1f);
            }
            case TILE -> {
                if ((long) Math.ceil(width / iw) * (long) Math.ceil(height / ih) <= MAX_TILES) {
                    for (float ty = 0f; ty < height; ty += ih) {
                        for (float tx = 0f; tx < width; tx += iw) {
                            renderer.drawImage(picture, x + tx, y + ty, iw, ih, 0f, 1f);
                        }
                    }
                }
            }
        }
        renderer.popClip();
    }
}
