package me.piitex.engine.math;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Moves a shape among solid ones: it stops at walls, lands on floors and slides along both, the way a character
 * moves through a level. {@link #move} does the moving and the result says where the shape ended up and what it hit.
 * <pre>{@code
 * protected void fixedUpdate(float step) {
 *     velocityY += GRAVITY * step;
 *     Slide slide = Slide.move(player.hitBox(), velocityX * step, velocityY * step, walls);
 *     player.x += slide.movedX();
 *     player.y += slide.movedY();
 *     Vec2 velocity = slide.slideVelocity(velocityX, velocityY); // stop falling on a floor, stop running at a wall
 *     velocityX = velocity.x();
 *     velocityY = velocity.y();
 *     grounded = slide.onFloor();
 * }
 * }</pre>
 * The shape moves in steps of at most half its own smaller side, so it can't pass through a solid even when it moves
 * fast. After each step every overlap is pushed out, deepest first, which lets it run along a floor made of many
 * tiles without catching on the seams between them.
 * <p>
 * A surface that faces up, up to 45 degrees off level, is a floor. The shape is pushed straight up off floors, so it
 * stands still on a slope instead of creeping down it. Steeper surfaces are walls, and those facing down are
 * ceilings. Running down a slope faster than gravity pulls leaves the floor for a moment at each step, as in most
 * simple platformers.
 * <p>
 * Every solid is tested at every step, so for a large level pass only the solids near the move, such as from a
 * {@link SpatialGrid} query of the area it covers:
 * {@code grid.query(box.bounds().union(box.translate(dx, dy).bounds()).expand(1f), nearby)}.
 */
public final class Slide {
    /** How far off level a floor may slope: its normal points at least this much up. The cosine of 45 degrees. */
    private static final float FLOOR = 0.7071f;
    /** Pushes out of one step's overlaps at most this many times, for a shape wedged between several solids. */
    private static final int PUSHES_PER_STEP = 4;
    /** Moves in at most this many steps, however far and however small the shape. */
    private static final int MAX_STEPS = 1000;

    private final Shape shape;
    private final float movedX, movedY;
    private final List<Vec2> normals;
    private final boolean floor, ceiling, wall;

    private Slide(Shape shape, float movedX, float movedY, List<Vec2> normals) {
        this.shape = shape;
        this.movedX = movedX;
        this.movedY = movedY;
        this.normals = Collections.unmodifiableList(normals);
        boolean floor = false, ceiling = false, wall = false;
        for (Vec2 normal : normals) {
            if (normal.y() <= -FLOOR) floor = true;
            else if (normal.y() >= FLOOR) ceiling = true;
            else wall = true;
        }
        this.floor = floor;
        this.ceiling = ceiling;
        this.wall = wall;
    }

    /**
     * Moves {@code mover} by ({@code dx}, {@code dy}), stopping at and sliding along {@code solids}. A mover that
     * starts inside a solid is pushed out of it.
     *
     * @param mover  the moving shape where it is now, such as a hit box
     * @param dx     how far it means to move across
     * @param dy     how far it means to move down
     * @param solids what it can't pass through. Leave the mover itself out
     */
    public static Slide move(Shape mover, float dx, float dy, Iterable<? extends Shape> solids) {
        Rect bounds = mover.bounds();
        float maxStep = Math.min(bounds.width(), bounds.height()) / 2f;
        float distance = MathUtil.length(dx, dy);
        int steps = maxStep > 0f ? (int) Math.clamp((long) Math.ceil(distance / maxStep), 1L, MAX_STEPS) : 1;
        float stepX = dx / steps, stepY = dy / steps;

        Shape current = mover;
        float movedX = 0f, movedY = 0f;
        List<Vec2> normals = new ArrayList<>(2);
        for (int step = 0; step <= steps; step++) {
            // Step 0 moves nothing, only pushes the mover out of anything it starts in.
            if (step > 0 && (stepX != 0f || stepY != 0f)) {
                current = current.translate(stepX, stepY);
                movedX += stepX;
                movedY += stepY;
            }
            for (int push = 0; push < PUSHES_PER_STEP; push++) {
                Overlap deepest = null;
                for (Shape solid : solids) {
                    Overlap overlap = current.overlap(solid);
                    if (overlap != null && overlap.depth() > 0f && (deepest == null || overlap.depth() > deepest.depth())) {
                        deepest = overlap;
                    }
                }
                if (deepest == null) break;
                Vec2 normal = deepest.normal();
                float pushX, pushY;
                if (normal.y() <= -FLOOR) {
                    // Straight up off a floor, as far as it takes to clear it along its normal.
                    pushX = 0f;
                    pushY = deepest.depth() / normal.y();
                } else {
                    pushX = deepest.pushX();
                    pushY = deepest.pushY();
                }
                current = current.translate(pushX, pushY);
                movedX += pushX;
                movedY += pushY;
                addNormal(normals, normal);
                // The rest of the move keeps only the part along the surface.
                Vec2 along = along(stepX, stepY, normal);
                stepX = along.x();
                stepY = along.y();
            }
        }
        return new Slide(current, movedX, movedY, normals);
    }

    private static void addNormal(List<Vec2> normals, Vec2 normal) {
        for (Vec2 known : normals) {
            if (Math.abs(known.x() - normal.x()) < 1e-4f && Math.abs(known.y() - normal.y()) < 1e-4f) return;
        }
        normals.add(normal);
    }

    /**
     * The shape where it ended up.
     */
    public Shape shape() {
        return shape;
    }

    /**
     * How far it actually moved across: add it to the position of whatever the shape belongs to.
     */
    public float movedX() {
        return movedX;
    }

    /**
     * How far it actually moved down.
     */
    public float movedY() {
        return movedY;
    }

    /**
     * Whether it hit anything.
     */
    public boolean hit() {
        return !normals.isEmpty();
    }

    /**
     * Whether it hit a surface below it that faces up, up to 45 degrees off level: whether it is standing on
     * something. Moving onto a floor only counts while the move presses into it, so keep gravity on while standing.
     */
    public boolean onFloor() {
        return floor;
    }

    /**
     * Whether it hit a surface above it that faces down, such as bumping its head.
     */
    public boolean onCeiling() {
        return ceiling;
    }

    /**
     * Whether it hit a surface steeper than 45 degrees.
     */
    public boolean onWall() {
        return wall;
    }

    /**
     * The direction each different surface it hit faces, as vectors of length 1 pointing out of the solid, in the
     * order they were hit.
     */
    public List<Vec2> normals() {
        return normals;
    }

    /**
     * ({@code velocityX}, {@code velocityY}) without the part heading into the surfaces it hit, so it slides along
     * them: landing zeroes a fall and keeps the run, and hitting a wall keeps the fall. On a floor only the vertical
     * speed changes, so a run up a slope climbs it and standing on one doesn't slide down it. Velocity heading away
     * from a surface, as in a jump off a floor, is kept.
     */
    public Vec2 slideVelocity(float velocityX, float velocityY) {
        Vec2 velocity = new Vec2(velocityX, velocityY);
        for (Vec2 normal : normals) velocity = along(velocity.x(), velocity.y(), normal);
        return velocity;
    }

    /**
     * (x, y) without the part heading into a surface facing {@code normal}. On a floor only the vertical part
     * changes, to follow the slope, so standing still on a slope stays still and running up one climbs it.
     */
    private static Vec2 along(float x, float y, Vec2 normal) {
        float into = x * normal.x() + y * normal.y();
        if (!(into < 0f)) return new Vec2(x, y);
        if (normal.y() <= -FLOOR) return new Vec2(x, -x * normal.x() / normal.y());
        return new Vec2(x - into * normal.x(), y - into * normal.y());
    }
}
