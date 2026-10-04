package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Vec3;

/**
 * A cylinder with a half ball on each end, standing along Y: the usual shape for characters. {@code halfHeight} is half
 * the length of the straight part, so the whole capsule is {@code 2 * (halfHeight + radius)} tall. A half height of 0
 * makes a ball.
 */
public record CapsuleShape(float radius, float halfHeight) implements Shape {
    public CapsuleShape {
        if (!(radius > 0f)) throw new IllegalArgumentException("The radius must be above 0, not " + radius);
        if (!(halfHeight >= 0f)) throw new IllegalArgumentException("The half height can't be below 0, not " + halfHeight);
    }

    /**
     * A capsule of the given total height, ends included, such as {@code CapsuleShape.ofHeight(0.3f, 1.8f)} for a person.
     */
    public static CapsuleShape ofHeight(float radius, float height) {
        return new CapsuleShape(radius, Math.max(0f, height * 0.5f - radius));
    }

    @Override
    public float volume() {
        double r = radius, h = halfHeight;
        return (float) (Math.PI * r * r * (2 * h) + 4.0 / 3.0 * Math.PI * r * r * r);
    }

    @Override
    public Vec3 unitInertia() {
        double r = radius, h = halfHeight;
        double cylinder = Math.PI * r * r * (2 * h), ends = 4.0 / 3.0 * Math.PI * r * r * r, total = cylinder + ends;
        double mc = cylinder / total, me = ends / total;
        // The two half balls sit apart, so add their offset from the middle (the centre of each is 3r/8 from its flat face).
        double across = mc * (r * r / 4 + h * h / 3) + me * (0.4 * r * r + h * h + 0.75 * h * r);
        double along = mc * r * r / 2 + me * 0.4 * r * r;
        return new Vec3((float) across, (float) along, (float) across);
    }

    @Override
    public Bounds bounds() {
        return new Bounds(new Vec3(-radius, -halfHeight - radius, -radius), new Vec3(radius, halfHeight + radius, radius));
    }

    @Override
    public CapsuleShape scaled(Vec3 scale) {
        float round = Math.max(Math.abs(scale.x()), Math.abs(scale.z()));
        return new CapsuleShape(radius * round, halfHeight * Math.abs(scale.y()));
    }
}
