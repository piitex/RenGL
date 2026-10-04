package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

/**
 * A ball of the given radius.
 */
public record SphereShape(float radius) implements Shape {
    public SphereShape {
        if (!(radius > 0f)) throw new IllegalArgumentException("The radius must be above 0, not " + radius);
    }

    @Override
    public float volume() {
        return (float) (4.0 / 3.0 * Math.PI * radius * radius * radius);
    }

    @Override
    public Vec3 unitInertia() {
        float i = 0.4f * radius * radius;
        return new Vec3(i, i, i);
    }

    @Override
    public SphereShape scaled(Vec3 scale) {
        return new SphereShape(radius * Math.max(Math.abs(scale.x()), Math.max(Math.abs(scale.y()), Math.abs(scale.z()))));
    }
}
