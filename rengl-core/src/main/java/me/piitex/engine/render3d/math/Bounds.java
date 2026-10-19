package me.piitex.engine.render3d.math;

/**
 * An axis-aligned box.
 */
public record Bounds(Vec3 min, Vec3 max) {
    public Vec3 center() {
        return min.add(max).mul(0.5f);
    }

    /**
     * The radius of the sphere around {@link #center()} that holds the whole box.
     */
    public float radius() {
        return max.sub(min).length() * 0.5f;
    }

    public Bounds union(Bounds o) {
        if (o == null) return this;
        return new Bounds(
                new Vec3(Math.min(min.x(), o.min.x()), Math.min(min.y(), o.min.y()), Math.min(min.z(), o.min.z())),
                new Vec3(Math.max(max.x(), o.max.x()), Math.max(max.y(), o.max.y()), Math.max(max.z(), o.max.z())));
    }

    /**
     * The box that holds this one after {@code m} moves it.
     */
    public Bounds transform(Mat4 m) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = m.transformPoint(new Vec3((i & 1) == 0 ? min.x() : max.x(),
                    (i & 2) == 0 ? min.y() : max.y(), (i & 4) == 0 ? min.z() : max.z()));
            minX = Math.min(minX, corner.x());
            minY = Math.min(minY, corner.y());
            minZ = Math.min(minZ, corner.z());
            maxX = Math.max(maxX, corner.x());
            maxY = Math.max(maxY, corner.y());
            maxZ = Math.max(maxZ, corner.z());
        }
        return new Bounds(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ));
    }
}
