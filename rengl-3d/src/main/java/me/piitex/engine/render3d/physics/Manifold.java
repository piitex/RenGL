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

    boolean full() {
        return count == CAPACITY;
    }

    /**
     * Adds a point. When full, the new point takes the place of the shallowest if it is deeper, so a hull with many
     * corners near the ground keeps the ones that matter.
     */
    void add(Vec3 point, float depth) {
        if (count < CAPACITY) {
            points[count] = point;
            depths[count] = depth;
            count++;
            return;
        }
        int shallowest = 0;
        for (int i = 1; i < CAPACITY; i++) if (depths[i] < depths[shallowest]) shallowest = i;
        if (depth > depths[shallowest]) {
            points[shallowest] = point;
            depths[shallowest] = depth;
        }
    }

    /**
     * Keeps at most four points that hold the shapes steady. Points closer than a millimetre are one point (clipping
     * equal faces gives doubles at the corners). Then: the deepest point, so the shapes never sink in where nothing
     * holds them (a round hull touches at one corner among many just above the ground), with ties of depth going to
     * the point farthest from the middle so the choice doesn't flicker; the point farthest from that; and the one
     * making the widest triangle with them on each side of the line through the first two.
     */
    void reduce() {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            int same = -1;
            for (int j = 0; j < kept && same < 0; j++) if (points[j].sub(points[i]).lengthSquared() < 1e-6f) same = j;
            if (same >= 0) {
                depths[same] = Math.max(depths[same], depths[i]);
            } else {
                points[kept] = points[i];
                depths[kept] = depths[i];
                kept++;
            }
        }
        count = kept;
        if (count <= MAX_POINTS) return;

        Vec3 middle = Vec3.ZERO;
        for (int i = 0; i < count; i++) middle = middle.add(points[i]);
        middle = middle.mul(1f / count);
        int first = deepest(middle);
        int second = farthest(points[first], first);
        Vec3 line = points[second].sub(points[first]);
        int third = -1, fourth = -1;
        float most = 0f, least = 0f;
        for (int i = 0; i < count; i++) {
            if (i == first || i == second) continue;
            float side = line.cross(points[i].sub(points[first])).dot(normal);
            if (side > most) {
                most = side;
                third = i;
            } else if (side < least) {
                least = side;
                fourth = i;
            }
        }
        int[] chosen = {first, second, third, fourth};
        Vec3[] keptPoints = new Vec3[MAX_POINTS];
        float[] keptDepths = new float[MAX_POINTS];
        int n = 0;
        for (int index : chosen) {
            if (index < 0) continue;
            keptPoints[n] = points[index];
            keptDepths[n] = depths[index];
            n++;
        }
        for (int i = 0; i < n; i++) {
            points[i] = keptPoints[i];
            depths[i] = keptDepths[i];
        }
        count = n;
    }

    private int deepest(Vec3 middle) {
        float most = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) most = Math.max(most, depths[i]);
        int best = -1;
        float distance = -1f;
        for (int i = 0; i < count; i++) {
            float d = points[i].sub(middle).lengthSquared();
            if (depths[i] >= most - 1e-4f && d > distance) {
                distance = d;
                best = i;
            }
        }
        return best;
    }

    private int farthest(Vec3 from, int skip) {
        int best = -1;
        float distance = -1f;
        for (int i = 0; i < count; i++) {
            float d = points[i].sub(from).lengthSquared();
            if (i != skip && d > distance) {
                distance = d;
                best = i;
            }
        }
        return best;
    }
}
