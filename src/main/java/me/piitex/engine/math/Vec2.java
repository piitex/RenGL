package me.piitex.engine.math;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * A point or a direction in 2D, such as a position converted by {@link me.piitex.engine.graphics.Camera}. It is
 * immutable: every operation returns a new vector. y grows downwards like every other coordinate in the engine, so
 * a positive angle turns clockwise on screen, the same way {@link me.piitex.engine.graphics.Camera#setRotation}
 * does. Angles are in degrees.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public record Vec2(float x, float y) {
    public static final Vec2 ZERO = new Vec2(0f, 0f);

    /**
     * The unit vector pointing {@code degrees} clockwise from the positive x axis: 0 is right, 90 is down.
     */
    public static Vec2 fromAngle(float degrees) {
        double radians = Math.toRadians(degrees);
        return new Vec2((float) Math.cos(radians), (float) Math.sin(radians));
    }

    /**
     * The point {@code t} of the way from {@code a} to {@code b}: {@code a} at 0, {@code b} at 1. {@code t} is not
     * clamped, so values outside 0-1 carry on along the line.
     */
    public static Vec2 lerp(Vec2 a, Vec2 b, float t) {
        return new Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
    }

    public Vec2 add(Vec2 other) {
        return new Vec2(x + other.x, y + other.y);
    }

    public Vec2 add(float dx, float dy) {
        return new Vec2(x + dx, y + dy);
    }

    public Vec2 sub(Vec2 other) {
        return new Vec2(x - other.x, y - other.y);
    }

    public Vec2 scale(float factor) {
        return new Vec2(x * factor, y * factor);
    }

    public Vec2 negate() {
        return new Vec2(-x, -y);
    }

    public float dot(Vec2 other) {
        return x * other.x + y * other.y;
    }

    /**
     * The z part of the 3D cross product: positive when {@code other} is clockwise of this vector on screen, negative
     * when it is anticlockwise, 0 when they are parallel.
     */
    public float cross(Vec2 other) {
        return x * other.y - y * other.x;
    }

    public float length() {
        return (float) Math.sqrt(x * x + y * y);
    }

    /**
     * The length squared, which is cheaper than {@link #length()} and enough to compare distances.
     */
    public float lengthSquared() {
        return x * x + y * y;
    }

    public float distance(Vec2 other) {
        return (float) Math.sqrt(distanceSquared(other));
    }

    public float distanceSquared(Vec2 other) {
        float dx = other.x - x, dy = other.y - y;
        return dx * dx + dy * dy;
    }

    /**
     * The same direction with length 1, or {@link #ZERO} for the zero vector.
     */
    public Vec2 normalize() {
        float length = length();
        return length > 0f ? new Vec2(x / length, y / length) : ZERO;
    }

    /**
     * The vector turned 90 degrees clockwise on screen.
     */
    public Vec2 perpendicular() {
        return new Vec2(-y, x);
    }

    /**
     * The vector turned {@code degrees} clockwise on screen.
     */
    public Vec2 rotate(float degrees) {
        double radians = Math.toRadians(degrees);
        float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
        return new Vec2(x * cos - y * sin, x * sin + y * cos);
    }

    /**
     * The direction in degrees clockwise from the positive x axis, from -180 to 180. 0 for the zero vector.
     */
    public float angle() {
        return (float) Math.toDegrees(Math.atan2(y, x));
    }
}
