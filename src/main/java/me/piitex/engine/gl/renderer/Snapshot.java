package me.piitex.engine.gl.renderer;

import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.Window;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.background.Background;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.overlays.ImageOverlay;
import org.jetbrains.skia.EncodedImageFormat;
import org.jetbrains.skia.Image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A rendered picture of an {@link Element} (typically a container) or a whole {@link Window}, taken with
 * {@link Element#takeSnapshot()} or {@link Window#takeSnapshot(float)}. It is a plain copy: later changes to the UI do not
 * affect it.
 * <p>
 * A snapshot owns a native image. Call {@link #close()} when you are done with it, unless you handed it to
 * {@link #toOverlay} (see there).
 * <pre>{@code
 * try (Snapshot shot = sceneContainer.takeSnapshot(0.25f)) {
 *     shot.savePng(Path.of("saves/slot1.png"));
 * }
 * }</pre>
 */
public final class Snapshot implements AutoCloseable {
    private final Image image;
    private final float scale;

    private Snapshot(Image image, float scale) {
        this.image = image;
        this.scale = scale;
    }

    /**
     * Pixel width of the picture: the element's width in points times the capture scale.
     */
    public int getWidth() {
        return image.getWidth();
    }

    public int getHeight() {
        return image.getHeight();
    }

    /**
     * Pixels per point this snapshot was captured at.
     */
    public float getScale() {
        return scale;
    }

    /**
     * The underlying Skia image, still owned by this snapshot.
     */
    public Image getImage() {
        return image;
    }

    /**
     * The picture encoded as PNG, transparency preserved.
     */
    public byte[] toPngBytes() {
        var data = image.encodeToData(EncodedImageFormat.PNG, 100, 0);
        if (data == null) throw new IllegalStateException("PNG encoding failed");
        try {
            return data.getBytes();
        } finally {
            data.close();
        }
    }

    /**
     * Writes the picture to {@code file} as PNG, creating missing parent directories.
     */
    public void savePng(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, toPngBytes());
    }

    /**
     * Wraps this picture in an {@link ImageOverlay} of the given size in points, for showing it in the UI without a
     * disk round trip (a save slot preview, say). The overlay draws this snapshot's image, so keep the snapshot
     * open for as long as the overlay is in use and close it when the overlay is discarded.
     */
    public ImageOverlay toOverlay(double width, double height) {
        return ImageOverlay.of(image, width, height);
    }

    @Override
    public void close() {
        image.close();
    }

    /**
     * Renders {@code element} on its own into a new snapshot: its background, border, children and current
     * opacity, at its own size. Anything outside its bounds (a child sticking out, a tooltip) is cut off.
     * Render thread only; from elsewhere use {@link #ofAsync}.
     */
    public static Snapshot of(Element element, float scale) {
        checkScale(scale);
        int w = (int) Math.ceil(element.getWidth());
        int h = (int) Math.ceil(element.getHeight());
        if (w <= 0 || h <= 0)
            throw new IllegalArgumentException("Cannot snapshot an element with no size: " + w + "x" + h);

        try (OffscreenRenderer renderer = new OffscreenRenderer(w, h, scale)) {
            renderer.begin();
            // The element draws itself at its own x/y (relative to its parent), so shift that to the origin.
            renderer.pushTranslate((float) -element.getX(), (float) -element.getY());
            element.renderWithTransitions(renderer);
            renderer.popTranslate();
            renderer.end();
            return new Snapshot(renderer.makeImage(), scale);
        }
    }

    /**
     * Renders everything the {@code window} would draw right now (its backdrop and every enabled container, in
     * order) at its configured size. Tooltips are not included. Render thread only.
     */
    public static Snapshot of(Window window, float scale) {
        checkScale(scale);
        int w = window.getWindowOptions().getWidth();
        int h = window.getWindowOptions().getHeight();

        try (OffscreenRenderer renderer = new OffscreenRenderer(w, h, scale)) {
            Paint background = AnimatedPaint.resolve(window.getBackgroundColor());
            if (background instanceof Color solid) {
                renderer.clear(solid.getR(), solid.getG(), solid.getB(), solid.getA());
            }
            renderer.begin();
            if (background instanceof LinearGradient gradient) {
                renderer.drawGradient(0f, 0f, w, h, gradient, 0f);
            }
            Background windowBackground = window.getBackground();
            if (windowBackground != null) {
                windowBackground.render(renderer, 0f, 0f, w, h);
            }
            for (Map.Entry<Integer, ? extends Element> entry : window.getContainers().entrySet()) {
                if (entry.getValue().isEnabled()) {
                    entry.getValue().renderWithTransitions(renderer);
                }
            }
            renderer.end();
            return new Snapshot(renderer.makeImage(), scale);
        }
    }

    /**
     * Captures {@code element} at the start of the next frame and hands the result to {@code callback}, both on the
     * render thread. Safe to call from any thread. The callback owns the snapshot and should close it.
     */
    public static void ofAsync(Element element, float scale, Consumer<Snapshot> callback) {
        Scheduler.runLater(() -> callback.accept(of(element, scale)));
    }

    /**
     * As {@link #ofAsync(Element, float, Consumer)}, for a whole window.
     */
    public static void ofAsync(Window window, float scale, Consumer<Snapshot> callback) {
        Scheduler.runLater(() -> callback.accept(of(window, scale)));
    }

    private static void checkScale(float scale) {
        if (!(scale > 0f)) throw new IllegalArgumentException("scale must be positive: " + scale);
    }
}
