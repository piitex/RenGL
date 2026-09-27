package me.piitex.engine.math;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * An axis-aligned rectangle: its top-left corner at ({@code x}, {@code y}), with y growing downwards like every other
 * coordinate in the engine. A width or height of 0 or less is empty: it contains no point, overlaps nothing and no
 * ray hits it.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public record Rect(float x, float y, float width, float height) implements Shape {

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
    @Override
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
        if (isEmpty() || !(owidth > 0f) || !(oheight > 0f)) return false;
        return ox < x + width && x < ox + owidth && oy < y + height && y < oy + oheight;
    }

    /**
     * Whether {@code other} lies wholly inside this rectangle, edges included. An empty rectangle is inside nothing.
     */
    public boolean contains(Rect other) {
        return !other.isEmpty() && other.x >= x && other.y >= y && other.right() <= right() && other.bottom() <= bottom();
    }

    /**
     * The smallest rectangle holding both. An empty rectangle adds nothing.
     */
    public Rect union(Rect other) {
        if (other.isEmpty()) return this;
        if (isEmpty()) return other;
        return fromCorners(Math.min(x, other.x), Math.min(y, other.y),
                Math.max(right(), other.right()), Math.max(bottom(), other.bottom()));
    }

    /**
     * The rectangle grown by {@code amount} on every side, or shrunk for a negative amount.
     */
    public Rect expand(float amount) {
        return new Rect(x - amount, y - amount, width + amount * 2f, height + amount * 2f);
    }

    @Override
    public Rect bounds() {
        return this;
    }

    @Override
    public Rect translate(float dx, float dy) {
        return new Rect(x + dx, y + dy, width, height);
    }

    /**
     * {@inheritDoc} For this test the rectangle includes all four edges.
     */
    @Override
    public RayHit raycast(float originX, float originY, float directionX, float directionY, float maxDistance) {
        Collide.checkRay(directionX, directionY, maxDistance);
        if (isEmpty()) return null;
        if (originX >= x && originX <= right() && originY >= y && originY <= bottom()) {
            return new RayHit(0f, new Vec2(originX, originY), Vec2.ZERO);
        }
        float length = (float) Math.sqrt(directionX * directionX + directionY * directionY);
        float dx = directionX / length, dy = directionY / length;

        // Slab test: the ray is inside the rectangle where it is between both pairs of edges at once.
        float enter = 0f, leave = maxDistance, normalX = 0f, normalY = 0f;
        if (dx == 0f) {
            if (originX < x || originX > right()) return null;
        } else {
            float near = ((dx > 0f ? x : right()) - originX) / dx;
            float far = ((dx > 0f ? right() : x) - originX) / dx;
            if (near > enter) {
                enter = near;
                normalX = dx > 0f ? -1f : 1f;
            }
            leave = Math.min(leave, far);
        }
        if (dy == 0f) {
            if (originY < y || originY > bottom()) return null;
        } else {
            float near = ((dy > 0f ? y : bottom()) - originY) / dy;
            float far = ((dy > 0f ? bottom() : y) - originY) / dy;
            if (near > enter) {
                enter = near;
                normalX = 0f;
                normalY = dy > 0f ? -1f : 1f;
            }
            leave = Math.min(leave, far);
        }
        if (enter > leave || normalX == 0f && normalY == 0f) return null;
        return new RayHit(enter, new Vec2(originX + dx * enter, originY + dy * enter), new Vec2(normalX, normalY));
    }
}
