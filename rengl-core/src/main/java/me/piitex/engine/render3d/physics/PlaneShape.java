package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Vec3;

/**
 * An endless flat ground through the shape's origin, solid on the side away from {@code normal}: {@code new
 * PlaneShape(Vec3.UP)} is a floor at the body's height. It has no volume, so it belongs on static and kinematic bodies.
 */
public record PlaneShape(Vec3 normal) implements Shape {
    public PlaneShape {
        if (normal == null || normal.lengthSquared() < 1e-12f) throw new IllegalArgumentException("A plane needs a normal, not " + normal);
        normal = normal.normalized();
    }

    /**
     * A floor facing up.
     */
    public PlaneShape() {
        this(Vec3.UP);
    }

    @Override
    public float volume() {
        return 0f;
    }

    @Override
    public Vec3 unitInertia() {
        return Vec3.ZERO;
    }

    @Override
    public Bounds bounds() {
        float inf = Float.POSITIVE_INFINITY;
        return new Bounds(new Vec3(-inf, -inf, -inf), new Vec3(inf, inf, inf));
    }

    @Override
    public PlaneShape scaled(Vec3 scale) {
        // Stretching tilts a plane the opposite way to its surface, so its normal divides by the scale.
        return new PlaneShape(new Vec3(normal.x() / nonZero(scale.x()), normal.y() / nonZero(scale.y()), normal.z() / nonZero(scale.z())));
    }

    private static float nonZero(float v) {
        return Math.abs(v) < 1e-6f ? Math.copySign(1e-6f, v) : v;
    }
}
