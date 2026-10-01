package me.piitex.engine.render3d.math;

/**
 * A half line: the point it starts at and the direction it goes. Distances along it are in units of the direction's
 * length, so give it a direction of length 1 (see {@link #of}) to measure in scene units.
 */
public record Ray(Vec3 origin, Vec3 direction) {
    /**
     * A ray whose direction is {@code direction} scaled to length 1.
     */
    public static Ray of(Vec3 origin, Vec3 direction) {
        return new Ray(origin, direction.normalized());
    }

    /**
     * The point {@code distance} along the ray.
     */
    public Vec3 at(float distance) {
        return origin.add(direction.mul(distance));
    }

    /**
     * How far along the ray it meets the box: where it enters, or 0 when it starts inside. -1 when it misses.
     */
    public float distanceTo(Bounds box) {
        float near = 0f, far = Float.POSITIVE_INFINITY;
        float[] o = {origin.x(), origin.y(), origin.z()};
        float[] d = {direction.x(), direction.y(), direction.z()};
        float[] lo = {box.min().x(), box.min().y(), box.min().z()};
        float[] hi = {box.max().x(), box.max().y(), box.max().z()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1e-20f) {
                // Parallel to this pair of faces: a miss unless the origin lies between them.
                if (o[axis] < lo[axis] || o[axis] > hi[axis]) return -1f;
                continue;
            }
            float t1 = (lo[axis] - o[axis]) / d[axis];
            float t2 = (hi[axis] - o[axis]) / d[axis];
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
            if (near > far) return -1f;
        }
        return near;
    }
}
