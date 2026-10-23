package me.piitex.engine.math;

import me.piitex.engine.utils.flags.GeneratedFragments;

/**
 * A circle centred on ({@code x}, {@code y}). A radius of 0 or less is empty for {@link #intersects}.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public record Circle(float x, float y, float radius) implements Shape {

    /**
     * Whether the point is inside or on the outline.
     */
    @Override
    public boolean contains(float px, float py) {
        float dx = px - x, dy = py - y;
        return dx * dx + dy * dy <= radius * radius;
    }

    @Override
    public Rect bounds() {
        float r = Math.max(radius, 0f);
        return new Rect(x - r, y - r, r * 2f, r * 2f);
    }

    @Override
    public Circle translate(float dx, float dy) {
        return new Circle(x + dx, y + dy, radius);
    }

    @Override
    public RayHit raycast(float originX, float originY, float directionX, float directionY, float maxDistance) {
        Collide.checkRay(directionX, directionY, maxDistance);
        if (contains(originX, originY)) return new RayHit(0f, new Vec2(originX, originY), Vec2.ZERO);
        if (!(radius > 0f)) return null;
        float length = (float) Math.sqrt(directionX * directionX + directionY * directionY);
        float dx = directionX / length, dy = directionY / length;
        float mx = originX - x, my = originY - y;
        float b = mx * dx + my * dy;
        if (b > 0f) return null; // outside and heading away
        float c = mx * mx + my * my - radius * radius;
        float disc = b * b - c;
        if (disc < 0f) return null;
        float t = Math.max(-b - (float) Math.sqrt(disc), 0f);
        if (t > maxDistance) return null;
        float px = originX + dx * t, py = originY + dy * t;
        return new RayHit(t, new Vec2(px, py), new Vec2((px - x) / radius, (py - y) / radius));
    }
}
