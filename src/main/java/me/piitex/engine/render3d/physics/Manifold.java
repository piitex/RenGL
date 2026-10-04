package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

/**
 * The contact points the narrowphase finds between two shapes, before they become a {@link Contact}. The normal points
 * from the first shape to the second, and a point's depth is how far the shapes overlap there (below 0 when they are
 * apart but within the margin).
 */
final class Manifold {
    static final int MAX_POINTS = 4;
    private static final int CAPACITY = 16;

    Vec3 normal = Vec3.UP;
    final Vec3[] points = new Vec3[CAPACITY];
    final float[] depths = new float[CAPACITY];
    int count;

    void reset() {
        count = 0;
        normal = Vec3.UP;
    }

    void add(Vec3 point, float depth) {
        if (count < CAPACITY) {
            points[count] = point;
            depths[count] = depth;
            count++;
        }
    }

    /**
     * Keeps at most four points that hold the shapes steady: the deepest, the one farthest from it, the one making the
     * widest triangle with those, and the one that widens it most into a four-sided patch.
     */
    void reduce() {
        if (count <= MAX_POINTS) return;
        int first = 0;
        for (int i = 1; i < count; i++) if (depths[i] > depths[first]) first = i;
        int second = -1;
        float best = -1f;
        for (int i = 0; i < count; i++) {
            float d = points[i].sub(points[first]).lengthSquared();
            if (i != first && d > best) {
                best = d;
                second = i;
            }
        }
        int third = -1;
        best = -1f;
        for (int i = 0; i < count; i++) {
            if (i == first || i == second) continue;
            float area = area(points[first], points[second], points[i]);
            if (area > best) {
                best = area;
                third = i;
            }
        }
        int fourth = -1;
        best = -1f;
        for (int i = 0; i < count; i++) {
            if (i == first || i == second || i == third) continue;
            Vec3 p = points[i];
            // Inside the triangle this equals its area; outside it grows with how far the point reaches.
            float area = area(points[first], points[second], p) + area(points[second], points[third], p) + area(points[third], points[first], p);
            if (area > best) {
                best = area;
                fourth = i;
            }
        }
        Vec3[] keptPoints = {points[first], points[second], points[third], points[fourth]};
        float[] keptDepths = {depths[first], depths[second], depths[third], depths[fourth]};
        for (int i = 0; i < MAX_POINTS; i++) {
            points[i] = keptPoints[i];
            depths[i] = keptDepths[i];
        }
        count = MAX_POINTS;
    }

    private static float area(Vec3 a, Vec3 b, Vec3 c) {
        return b.sub(a).cross(c.sub(a)).length();
    }
}
