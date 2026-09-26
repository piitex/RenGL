package me.piitex.engine.math;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * An axis-aligned rectangle: its top-left corner at ({@code x}, {@code y}), with y growing downwards like every other
 * coordinate in the engine. A negative width or height is treated as empty.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public record Rect(float x, float y, float width, float height) {

    /**
     * The rectangle spanning two corners, in either order.
     */
    public static Rect fromCorners(float x0, float y0, float x1, float y1) {
        return new Rect(Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0), Math.abs(y1 - y0));
    }

    public float right() {
        return x + width;
    }

    public float bottom() {
        return y + height;
    }

    public float centerX() {
        return x + width / 2f;
    }

    public float centerY() {
        return y + height / 2f;
    }

    public boolean isEmpty() {
        return !(width > 0f) || !(height > 0f);
    }

    /**
     * Whether the point lies inside, counting the top and left edges but not the bottom and right ones, so
     * rectangles that share an edge never both contain a point on it.
     */
    public boolean contains(float px, float py) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }

    /**
     * Whether the two overlap by some area. Rectangles that only touch along an edge don't.
     */
    public boolean intersects(Rect other) {
        return intersects(other.x, other.y, other.width, other.height);
    }

    public boolean intersects(float ox, float oy, float owidth, float oheight) {
        return ox < x + width && x < ox + owidth && oy < y + height && y < oy + oheight;
    }
}
