package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.GpuTexture;
import me.piitex.engine.render3d.RenderTarget;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * One shadow picture shared by every shadow view of every light: a sampled target cut into square tiles, so the scene
 * shader reads all shadows through one texture binding whatever the lights are. The target only grows, and its layout is
 * worked out again each frame from what the lights ask for.
 */
final class ShadowAtlas {
    /**
     * A tile's place in the atlas in pixels, measured from the top-left.
     */
    record Rect(int x, int y, int size) {
    }

    /**
     * Where the tiles go, in the order they were asked for, and how much of the atlas they use.
     */
    record Layout(int width, int height, List<Rect> rects) {
    }

    /**
     * What a light is given: the tile size each of its views got after any shrinking, and the layout of every view of every
     * light in order.
     */
    record Fit(int[] lightSizes, Layout layout) {
    }

    static final int MIN_TILE = 128;

    private final GpuDevice device;
    private RenderTarget target;

    ShadowAtlas(GpuDevice device) {
        this.device = device;
    }

    /**
     * Places square tiles in rows, the biggest first, in an atlas {@code width} wide.
     *
     * @return the layout, or null if the rows would be taller than {@code maxHeight}
     */
    static Layout pack(int[] sizes, int width, int maxHeight) {
        Integer[] order = new Integer[sizes.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.<Integer>comparingInt(i -> -sizes[i]).thenComparingInt(i -> i));
        Rect[] rects = new Rect[sizes.length];
        int x = 0, y = 0, rowHeight = 0;
        for (int i : order) {
            int s = sizes[i];
            if (x + s > width && x > 0) {
                y += rowHeight;
                x = 0;
                rowHeight = 0;
            }
            if (x == 0) rowHeight = s;
            rects[i] = new Rect(x, y, s);
            x += s;
        }
        int height = y + rowHeight;
        if (height > maxHeight) return null;
        return new Layout(width, height, List.of(rects));
    }

    /**
     * The atlas of the least area that holds every tile within {@code maxSize} in both directions, as square as possible
     * among equals; or the least overflowing one when nothing fits (a single tile is never refused, however big).
     */
    static Layout layout(int[] sizes, int maxSize) {
        if (sizes.length == 0) return new Layout(0, 0, List.of());
        int largest = Arrays.stream(sizes).max().getAsInt();
        int limit = Math.max(maxSize, largest);
        int widest = nextPow2(limit);
        Layout best = null;
        long bestArea = Long.MAX_VALUE;
        int bestSide = Integer.MAX_VALUE;
        for (int w = nextPow2(largest); w <= widest; w *= 2) {
            Layout layout = pack(sizes, w, limit);
            if (layout == null) continue;
            int h = roundUp(layout.height());
            long area = (long) w * h;
            int side = Math.max(w, h);
            if (area < bestArea || area == bestArea && side < bestSide) {
                best = layout;
                bestArea = area;
                bestSide = side;
            }
        }
        return best != null ? best : pack(sizes, widest, Integer.MAX_VALUE);
    }

    /**
     * Gives every light a tile size, shrinking the biggest (halving) until all views fit.
     *
     * @param requested the tile size each light asks for
     * @param views     how many views (tiles) each light needs
     * @param maxSize   the atlas's largest width or height; a single tile bigger than this is still allowed
     */
    static Fit fit(int[] requested, int[] views, int maxSize) {
        int[] sizes = requested.clone();
        while (true) {
            int total = Arrays.stream(views).sum();
            int[] tiles = new int[total];
            int t = 0;
            for (int i = 0; i < sizes.length; i++) for (int v = 0; v < views[i]; v++) tiles[t++] = sizes[i];
            Layout layout = layout(tiles, maxSize);
            int limit = Math.max(maxSize, Arrays.stream(tiles).max().orElse(0));
            int biggest = -1;
            for (int i = 0; i < sizes.length; i++) if (sizes[i] > MIN_TILE && (biggest < 0 || sizes[i] > sizes[biggest])) biggest = i;
            if (layout.width() <= limit && layout.height() <= limit || biggest < 0) return new Fit(sizes, layout);
            sizes[biggest] = Math.max(MIN_TILE, sizes[biggest] / 2);
        }
    }

    /**
     * The height a layout's target is made, a multiple of 256 so a layout that grows a little doesn't make a new target each time.
     */
    static int roundUp(int height) {
        return (height + 255) / 256 * 256;
    }

    static int nextPow2(int v) {
        return v <= 1 ? 1 : Integer.highestOneBit(v - 1) << 1;
    }

    /**
     * The texture to draw into and then read from, big enough for the layout; reused until a bigger one is needed.
     */
    RenderTarget target(Layout layout) {
        int w = layout.width(), h = roundUp(layout.height());
        if (target == null || target.width() < w || target.height() < h) {
            int nw = Math.max(w, target == null ? 0 : target.width());
            int nh = Math.max(h, target == null ? 0 : target.height());
            if (target != null) target.close();
            target = device.createSampledTarget(nw, nh, true, false);
        }
        return target;
    }

    RenderTarget current() {
        return target;
    }

    GpuTexture texture() {
        return target.texture();
    }

    long bytes() {
        return target == null ? 0L : (long) target.width() * target.height() * 8L;
    }

    void close() {
        if (target != null) target.close();
        target = null;
    }
}
