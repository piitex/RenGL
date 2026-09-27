package me.piitex.engine.math;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * A 2D area that can be tested against points, other shapes and rays: a {@link Rect}, a {@link Circle} or a convex
 * {@link Polygon}. These are queries only. Nothing moves or pushes anything apart; what a hit means is up to the game.
 * <p>
 * Shapes are immutable. Keep a hit box relative to its owner and {@link #translate} it to the owner's position when
 * testing, or build a new one each step.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public sealed interface Shape permits Rect, Circle, Polygon {

    /**
     * Whether the point is inside. For a {@link Circle} or {@link Polygon} the outline counts as inside; for a
     * {@link Rect} only its top and left edges do, so tiles that share an edge never both contain a point on it.
     */
    boolean contains(float x, float y);

    default boolean contains(Vec2 point) {
        return contains(point.x(), point.y());
    }

    /**
     * Whether the two shapes overlap by some area. Shapes that only touch along an edge or at a point don't, and an
     * empty shape (a zero-size rectangle, a zero radius) never overlaps anything.
     */
    default boolean intersects(Shape other) {
        return Collide.intersects(this, other);
    }

    /**
     * The smallest axis-aligned rectangle around the shape, for a cheap first test or for a spatial index.
     */
    Rect bounds();

    /**
     * The shape moved by ({@code dx}, {@code dy}).
     */
    Shape translate(float dx, float dy);

    /**
     * Where a ray first hits the shape's outline, or null if it misses within {@code maxDistance}. The ray starts
     * at ({@code originX}, {@code originY}) and heads along ({@code directionX}, {@code directionY}), which needn't
     * be of length 1; distances are measured in the same units as the coordinates. A ray that starts inside the shape
     * hits it at distance 0 with a zero normal. Use {@link Float#POSITIVE_INFINITY} for a ray with no end.
     *
     * @throws IllegalArgumentException if the direction is zero or {@code maxDistance} is negative
     */
    RayHit raycast(float originX, float originY, float directionX, float directionY, float maxDistance);

    default RayHit raycast(Vec2 origin, Vec2 direction, float maxDistance) {
        return raycast(origin.x(), origin.y(), direction.x(), direction.y(), maxDistance);
    }

    /**
     * Where the segment from ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}) first touches the shape, or null if
     * it doesn't: a line-of-sight test. A zero-length segment hits only if its point is inside.
     */
    default RayHit segmentCast(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        if (dx == 0f && dy == 0f) return contains(x0, y0) ? new RayHit(0f, new Vec2(x0, y0), Vec2.ZERO) : null;
        return raycast(x0, y0, dx, dy, (float) Math.sqrt(dx * dx + dy * dy));
    }
}
