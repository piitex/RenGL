package me.piitex.engine.math;

/**
 * How two overlapping shapes are to be pushed apart, from {@link Shape#overlap}: move the first shape {@code depth}
 * along {@code normal} and they only touch. The normal has length 1 and points away from the second shape, the way
 * the first must go. Moving the second shape the other way, or each half the depth, separates them just the same.
 * <pre>{@code
 * Overlap overlap = player.hitBox().overlap(crate.hitBox());
 * if (overlap != null) {
 *     player.x += overlap.pushX();
 *     player.y += overlap.pushY();
 * }
 * }</pre>
 * It is the shortest way out, so a shape moving fast enough to pass most of the way through another is pushed out of
 * the far side. {@link Slide#move} moves in small enough steps that this doesn't happen.
 */
public record Overlap(Vec2 normal, float depth) {

    /**
     * How far to move the first shape across: {@code normal.x() * depth}.
     */
    public float pushX() {
        return normal.x() * depth;
    }

    /**
     * How far to move the first shape down: {@code normal.y() * depth}.
     */
    public float pushY() {
        return normal.y() * depth;
    }

    /**
     * The whole push, {@code normal} times {@code depth}.
     */
    public Vec2 push() {
        return new Vec2(pushX(), pushY());
    }
}
