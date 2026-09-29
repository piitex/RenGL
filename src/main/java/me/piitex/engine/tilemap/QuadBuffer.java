package me.piitex.engine.tilemap;

import me.piitex.engine.gl.renderer.Renderer;
import org.jetbrains.skia.Image;

import java.util.Arrays;

/**
 * Quads waiting to be drawn from one image, sent in one {@link Renderer#drawImageQuads} call when the image
 * changes or the layer is done. One per map, reused, so drawing makes no garbage.
 */
final class QuadBuffer {
    float[] positions = new float[256 * 8];
    float[] texCoords = new float[256 * 8];
    int[] colors = new int[256];
    private int count;
    private Image image;
    private boolean smooth;
    int calls, quadsDrawn; // for tests

    /**
     * Makes room for a quad from {@code image}, drawing what is waiting first if it is from another image, and
     * returns the quad's index.
     */
    int start(Renderer renderer, Image image, boolean smooth) {
        if (count > 0 && (image != this.image || smooth != this.smooth)) flush(renderer);
        this.image = image;
        this.smooth = smooth;
        if (count == colors.length) {
            positions = Arrays.copyOf(positions, positions.length * 2);
            texCoords = Arrays.copyOf(texCoords, texCoords.length * 2);
            colors = Arrays.copyOf(colors, colors.length * 2);
        }
        return count++;
    }

    void flush(Renderer renderer) {
        if (count > 0) {
            renderer.drawImageQuads(image, smooth, positions, texCoords, colors, count);
            calls++;
            quadsDrawn += count;
        }
        count = 0;
        image = null;
    }
}
