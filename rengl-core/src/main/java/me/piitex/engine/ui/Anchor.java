package me.piitex.engine.ui;

/**
 * A point in a box, as fractions of its width and height: (0, 0) is the top-left corner, (1, 1) the bottom-right and
 * (0.5, 0.5) the center. {@link Element#setAnchor} uses one to keep an element at the same place in its container
 * however the container is resized.
 * <p>
 * The nine constants cover the corners, edge centers and the center. {@link #at} gives any other point, such as
 * {@code Anchor.at(0.5, 0.3)} for 30% of the way down the middle.
 *
 * @param x the fraction of the width, from the left
 * @param y the fraction of the height, from the top
 */
public record Anchor(double x, double y) {
    public static final Anchor TOP_LEFT = new Anchor(0, 0);
    public static final Anchor TOP_CENTER = new Anchor(0.5, 0);
    public static final Anchor TOP_RIGHT = new Anchor(1, 0);
    public static final Anchor CENTER_LEFT = new Anchor(0, 0.5);
    public static final Anchor CENTER = new Anchor(0.5, 0.5);
    public static final Anchor CENTER_RIGHT = new Anchor(1, 0.5);
    public static final Anchor BOTTOM_LEFT = new Anchor(0, 1);
    public static final Anchor BOTTOM_CENTER = new Anchor(0.5, 1);
    public static final Anchor BOTTOM_RIGHT = new Anchor(1, 1);

    /**
     * The point {@code x} of the way across and {@code y} of the way down.
     */
    public static Anchor at(double x, double y) {
        return new Anchor(x, y);
    }
}
