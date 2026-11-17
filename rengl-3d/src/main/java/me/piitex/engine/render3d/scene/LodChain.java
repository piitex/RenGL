package me.piitex.engine.render3d.scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The levels of detail of a mesh: the mesh itself, then simpler versions of it, each with fewer triangles, to draw when it
 * is too small on screen for the detail to show. A mesh gets them with {@link Mesh#addLod}; the scene picks a level for
 * every node and every instance each frame, so nothing else changes in the game.
 * <p>
 * Each level says how far, in the mesh's own units, its surface may be from the full mesh's (its <em>error</em>). The scene
 * draws the simplest level whose error, seen from the camera, covers less than {@link Scene3D#setLodPixelError} pixels, so a
 * level is used from the distance where the difference can't be seen, whatever size the object, the window or the field of
 * view. Level 0 is the mesh itself, with no error.
 * <p>
 * Immutable: adding a level makes a new chain, so a mesh can be given levels on a worker thread while it is drawn.
 */
public final class LodChain {
    /**
     * How far past the limit a node's level has to be before it changes, as a share of the limit: a level is kept until its
     * error on screen is 15% above the limit, and a simpler one is only taken once it is 15% below, so a node at the border
     * doesn't flick between two levels as the camera sways.
     */
    static final float HYSTERESIS = 0.15f;

    /**
     * One level of detail.
     *
     * @param mesh     what is drawn at this level
     * @param error    the farthest, in the mesh's own units, the level's surface is from the full mesh's; 0 for level 0
     * @param handMade whether it was made by hand (given with {@link Mesh#addLod}) rather than generated
     */
    public record Level(Mesh mesh, float error, boolean handMade) {
        public Level {
            if (mesh == null) throw new IllegalArgumentException("A level needs a mesh");
            if (!(error >= 0f) || Float.isInfinite(error)) throw new IllegalArgumentException("A level's error must be 0 or more, not " + error);
        }
    }

    /**
     * How far, as a share of the mesh's radius, a picture card ({@link Mesh#setImpostor}) is taken to be off from the mesh:
     * its views are a few degrees apart, so its outline can be off by about this much between them. A card is drawn once
     * this is under the pixel error, which for the default of a pixel is a mesh under about 40 pixels across.
     */
    static final float IMPOSTOR_ERROR = 0.05f;

    private final List<Level> levels;
    private final boolean impostor;

    private LodChain(List<Level> levels) {
        this(levels, false);
    }

    private LodChain(List<Level> levels, boolean impostor) {
        this.levels = Collections.unmodifiableList(levels);
        this.impostor = impostor;
    }

    /** A new chain with or without a picture card after its simplest level. */
    LodChain withImpostor(boolean on) {
        return on == impostor ? this : new LodChain(new ArrayList<>(levels), on);
    }

    /**
     * Whether the chain ends in a picture card: a flat card facing the camera showing pictures of the mesh taken from all
     * round, drawn when the mesh is so small that even its simplest level wastes triangles ({@link Mesh#setImpostor}). Its
     * level number is {@link #size()}, one past the simplest mesh.
     */
    public boolean hasImpostor() {
        return impostor;
    }

    /** The error of the picture card, in the mesh's units: never less than the simplest level's. */
    public float impostorError() {
        return Math.max(levels.get(levels.size() - 1).error(), IMPOSTOR_ERROR * base().bounds().radius());
    }

    private float errorAt(int level) {
        return level < levels.size() ? levels.get(level).error() : impostorError();
    }

    /**
     * A chain of {@code base} alone.
     */
    static LodChain of(Mesh base) {
        return new LodChain(List.of(new Level(base, 0f, true)));
    }

    /**
     * A new chain with {@code level} added among the others by its error (after any with the same error).
     */
    LodChain with(Level level) {
        Mesh base = levels.get(0).mesh();
        if (level.mesh() == base) throw new IllegalArgumentException("A mesh can't be a level of itself");
        if (level.mesh().isLines() != base.isLines()) throw new IllegalArgumentException("A level must be lines when the mesh is lines, and triangles when it is triangles");
        List<Level> next = new ArrayList<>(levels);
        int at = next.size();
        while (at > 1 && next.get(at - 1).error() > level.error()) at--;
        next.add(at, level);
        return new LodChain(next, impostor);
    }

    /**
     * A new chain where the level at {@code index} (1 or more) is {@code level}, or {@code level} is added at the end when
     * the chain is that short; the errors are put back in order after.
     */
    LodChain replace(int index, Level level) {
        if (index < 1) throw new IllegalArgumentException("Level 0 is the mesh itself");
        List<Level> next = new ArrayList<>(levels);
        if (index < next.size()) next.remove(index);
        LodChain without = new LodChain(next, impostor);
        return without.with(level);
    }

    /** How many levels there are, the mesh itself included. */
    public int size() {
        return levels.size();
    }

    /** The level at {@code index}; 0 is the mesh itself. */
    public Level level(int index) {
        return levels.get(index);
    }

    /** Every level, the mesh itself first, by error. */
    public List<Level> levels() {
        return levels;
    }

    /** The mesh itself. */
    public Mesh base() {
        return levels.get(0).mesh();
    }

    /** The simplest level's mesh. */
    public Mesh coarsest() {
        return levels.get(levels.size() - 1).mesh();
    }

    /**
     * The level to draw.
     *
     * @param pixelsPerUnit how many pixels one unit of the mesh's own space covers on screen where the node is (its scale
     *                      included)
     * @param maxPixels     how many pixels of error may show
     * @param previous      the level drawn last time, or -1 for none, so a node near a border keeps its level
     * @param last          the last level that may be used (levels past it don't suit the node); {@link #size()} for the
     *                      picture card, when the chain has one
     */
    int select(float pixelsPerUnit, float maxPixels, int previous, int last) {
        last = Math.min(last, impostor ? levels.size() : levels.size() - 1);
        if (last <= 0) return 0;
        // Nothing on screen at all is the simplest level; a size that is not a number is the full mesh, to be safe.
        if (!(pixelsPerUnit > 0f)) return pixelsPerUnit == 0f ? last : 0;
        if (previous < 0 || previous > last) return simplest(pixelsPerUnit, maxPixels, last);
        // The simplest level that passes a limit 15% tighter, and the simplest that passes one 15% looser: between the two
        // the level stays as it was.
        int strict = simplest(pixelsPerUnit, maxPixels * (1f - HYSTERESIS), last);
        int loose = simplest(pixelsPerUnit, maxPixels * (1f + HYSTERESIS), last);
        if (previous < strict) return strict;
        if (previous > loose) return loose;
        return previous;
    }

    private int simplest(float pixelsPerUnit, float maxPixels, int last) {
        int best = 0;
        for (int i = 1; i <= last; i++) {
            if (errorAt(i) * pixelsPerUnit <= maxPixels) best = i;
            else break;
        }
        return best;
    }
}
