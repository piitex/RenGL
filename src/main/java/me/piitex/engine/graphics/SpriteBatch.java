package me.piitex.engine.graphics;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.math.Rect;
import me.piitex.engine.utils.flags.GeneratedClass;
import me.piitex.engine.utils.flags.GeneratedFragments;
import org.jetbrains.skia.Image;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Draws many {@link Sprite}s at once: in layer order, leaving out those that are out of view, and in as few draw
 * calls as it can. Add the sprites to draw each frame, then draw the batch, which empties it for the next frame.
 * <pre>{@code
 * private final SpriteBatch batch = new SpriteBatch();
 *
 * protected void render(Renderer renderer, float alpha) {
 *     for (Enemy enemy : enemies) batch.add(enemy.sprite);
 *     batch.add(player.sprite);
 *
 *     camera.begin(renderer);
 *     batch.draw(renderer, camera.getVisibleBounds()); // skip what the camera can't see
 *     camera.end(renderer);
 * }
 * }</pre>
 * <b>Order.</b> Sprites are drawn by {@link Sprite#getLayer() layer}, lowest first. Within a layer they are drawn in
 * the order they were added, unless a {@link #setSortKey sort key} is set: then by that key, lowest first, and in the
 * order added where keys are equal. {@link #BY_Y} draws sprites further down in front, as a top-down game wants; any
 * other order is one function away, such as {@code s -> s.getX() + s.getY()} for an isometric view.
 * <p>
 * <b>Snapshots.</b> {@link #add} records the sprite as it is at that moment. Changing the sprite afterwards doesn't
 * change what the batch draws, so one sprite can be moved and added again to draw the same picture in many places,
 * such as the tiles of a map.
 * <p>
 * <b>Batching.</b> After sorting, each run of sprites from the same {@link Texture} is drawn with a single
 * {@link Renderer#drawImageQuads} call, so sprites that share a texture atlas and sit next to each other in the draw
 * order cost one call however many there are. Putting related pictures on one atlas is the way to keep runs long.
 * <p>
 * Not thread-safe. Use it on the render thread.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class SpriteBatch {

    /**
     * A value to sort sprites by within a layer, lowest drawn first. Read once, when the sprite is added.
     */
    @FunctionalInterface
    public interface SortKey {
        float of(Sprite sprite);
    }

    /**
     * Sorts by y, so sprites further down the screen are drawn in front of those above them. With the pivot at a
     * sprite's feet, {@code setPivot(0.5f, 1f)}, characters then overlap the way they stand.
     */
    public static final SortKey BY_Y = Sprite::getY;

    private static final Comparator<Entry> ORDER = (a, b) -> {
        if (a.layer != b.layer) return Integer.compare(a.layer, b.layer);
        int byKey = Float.compare(a.key, b.key);
        return byKey != 0 ? byKey : Integer.compare(a.order, b.order);
    };

    private Entry[] entries = new Entry[64];
    private int size;
    private SortKey sortKey;

    // Reused between draws: the quads of one run, laid out for Renderer.drawImageQuads.
    private float[] positions = new float[64 * 8];
    private float[] texCoords = new float[64 * 8];
    private int[] colors = new int[64];

    private int drawnCount, culledCount, callCount;

    /**
     * Records {@code sprite} to be drawn by the next {@link #draw}, as it is now. A sprite without a region, hidden,
     * or fully transparent is skipped.
     */
    public void add(Sprite sprite) {
        if (size == entries.length) entries = Arrays.copyOf(entries, size * 2);
        Entry entry = entries[size];
        if (entry == null) entry = entries[size] = new Entry();
        if (!sprite.writeQuad(entry.positions, 0, entry.texCoords, 0)) return;
        Texture texture = sprite.getRegion().getTexture();
        entry.image = texture.getImage();
        entry.smooth = texture.isSmooth();
        entry.color = sprite.packedColor();
        entry.layer = sprite.getLayer();
        entry.key = sortKey == null ? 0f : sortKey.of(sprite);
        entry.order = size;
        float[] p = entry.positions;
        entry.minX = Math.min(Math.min(p[0], p[2]), Math.min(p[4], p[6]));
        entry.maxX = Math.max(Math.max(p[0], p[2]), Math.max(p[4], p[6]));
        entry.minY = Math.min(Math.min(p[1], p[3]), Math.min(p[5], p[7]));
        entry.maxY = Math.max(Math.max(p[1], p[3]), Math.max(p[5], p[7]));
        size++;
    }

    /**
     * Draws every sprite added since the last draw, then empties the batch.
     */
    public void draw(Renderer renderer) {
        draw(renderer, null);
    }

    /**
     * Draws the sprites added since the last draw that overlap {@code visible}, such as
     * {@link Camera#getVisibleBounds()}, then empties the batch. A null {@code visible} draws them all.
     */
    public void draw(Renderer renderer, Rect visible) {
        int kept = 0;
        for (int i = 0; i < size; i++) {
            Entry entry = entries[i];
            if (visible != null && !(entry.maxX > visible.x() && entry.minX < visible.right()
                    && entry.maxY > visible.y() && entry.minY < visible.bottom())) {
                continue;
            }
            // Move the kept entries to the front, swapping so every pooled Entry stays in the array.
            entries[i] = entries[kept];
            entries[kept++] = entry;
        }
        culledCount = size - kept;
        drawnCount = kept;
        callCount = 0;
        Arrays.sort(entries, 0, kept, ORDER);

        int start = 0;
        while (start < kept) {
            Image image = entries[start].image;
            boolean smooth = entries[start].smooth;
            int end = start + 1;
            while (end < kept && entries[end].image == image && entries[end].smooth == smooth) end++;
            drawRun(renderer, image, smooth, start, end);
            start = end;
        }
        clear();
    }

    private void drawRun(Renderer renderer, Image image, boolean smooth, int start, int end) {
        int count = end - start;
        if (colors.length < count) {
            int capacity = Math.max(count, colors.length * 2);
            positions = new float[capacity * 8];
            texCoords = new float[capacity * 8];
            colors = new int[capacity];
        }
        for (int i = 0; i < count; i++) {
            Entry entry = entries[start + i];
            System.arraycopy(entry.positions, 0, positions, i * 8, 8);
            System.arraycopy(entry.texCoords, 0, texCoords, i * 8, 8);
            colors[i] = entry.color;
        }
        renderer.drawImageQuads(image, smooth, positions, texCoords, colors, count);
        callCount++;
    }

    /**
     * Forgets the sprites added since the last draw without drawing them.
     */
    public void clear() {
        for (int i = 0; i < size; i++) entries[i].image = null; // don't keep images alive for the batch's sake
        size = 0;
    }

    /**
     * The number of sprites waiting to be drawn.
     */
    public int size() {
        return size;
    }

    public SortKey getSortKey() {
        return sortKey;
    }

    /**
     * Sorts sprites within a layer by {@code key}, lowest first, such as {@link #BY_Y}. Null, the default, keeps
     * the order they were added in. Applies to sprites added after this call.
     */
    public void setSortKey(SortKey key) {
        this.sortKey = key;
    }

    /**
     * How many sprites the last {@link #draw} drew.
     */
    public int getDrawnCount() {
        return drawnCount;
    }

    /**
     * How many sprites the last {@link #draw} left out for being out of view.
     */
    public int getCulledCount() {
        return culledCount;
    }

    /**
     * How many {@link Renderer#drawImageQuads} calls the last {@link #draw} made: one per run of sprites sharing a
     * texture. Far fewer than {@link #getDrawnCount()} is what batching is for.
     */
    public int getDrawCallCount() {
        return callCount;
    }

    private static final class Entry {
        final float[] positions = new float[8];
        final float[] texCoords = new float[8];
        Image image;
        boolean smooth;
        int color;
        int layer;
        float key;
        int order;
        float minX, minY, maxX, maxY;
    }
}
