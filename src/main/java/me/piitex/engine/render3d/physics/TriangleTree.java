package me.piitex.engine.render3d.physics;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * A bounding-volume hierarchy over a {@link MeshShape}'s triangles, so a contact test or a ray visits the few triangles
 * near it instead of all of them. Each node is a box; a node is either a leaf with a few triangles or has two children,
 * split at the middle triangle along the node's longest side.
 */
final class TriangleTree {
    private static final int LEAF_SIZE = 4;

    private final float[] positions;
    private final int[] indices;
    private final int[] order;               // triangle numbers, grouped so every node owns a contiguous run
    private float[] box = new float[6 * 64]; // min xyz, max xyz per node
    private int[] first = new int[64];       // leaf: start in order[]; inner: the left child (the right is next to it)
    private int[] count = new int[64];       // leaf: triangle count; inner: 0
    private int nodes;
    private int depth;

    TriangleTree(float[] positions, int[] indices) {
        this.positions = positions;
        this.indices = indices;
        int triangles = indices.length / 3;
        order = new int[triangles];
        for (int i = 0; i < triangles; i++) order[i] = i;
        float[] centroid = new float[triangles * 3];
        for (int t = 0; t < triangles; t++) {
            for (int axis = 0; axis < 3; axis++) {
                centroid[t * 3 + axis] = (positions[indices[t * 3] * 3 + axis] + positions[indices[t * 3 + 1] * 3 + axis]
                        + positions[indices[t * 3 + 2] * 3 + axis]) / 3f;
            }
        }
        build(newNode(), 0, triangles, centroid, 1);
    }

    private int newNode() {
        if (nodes == first.length) {
            first = Arrays.copyOf(first, nodes * 2);
            count = Arrays.copyOf(count, nodes * 2);
            box = Arrays.copyOf(box, nodes * 12);
        }
        return nodes++;
    }

    private void build(int node, int start, int end, float[] centroid, int level) {
        depth = Math.max(depth, level);
        float[] b = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        float[] c = b.clone();
        for (int i = start; i < end; i++) {
            int t = order[i];
            for (int k = 0; k < 3; k++) {
                int v = indices[t * 3 + k] * 3;
                for (int axis = 0; axis < 3; axis++) {
                    b[axis] = Math.min(b[axis], positions[v + axis]);
                    b[axis + 3] = Math.max(b[axis + 3], positions[v + axis]);
                }
            }
            for (int axis = 0; axis < 3; axis++) {
                c[axis] = Math.min(c[axis], centroid[t * 3 + axis]);
                c[axis + 3] = Math.max(c[axis + 3], centroid[t * 3 + axis]);
            }
        }
        System.arraycopy(b, 0, box, node * 6, 6);

        int n = end - start;
        float ex = c[3] - c[0], ey = c[4] - c[1], ez = c[5] - c[2];
        if (n <= LEAF_SIZE || Math.max(ex, Math.max(ey, ez)) <= 0f) {
            first[node] = start;
            count[node] = n;
            return;
        }
        int axis = ex >= ey && ex >= ez ? 0 : ey >= ez ? 1 : 2;
        int mid = start + n / 2;
        select(start, end - 1, mid, axis, centroid);
        int left = newNode();
        int right = newNode(); // consecutive, so the node stores only the left index
        first[node] = left;
        count[node] = 0;
        build(left, start, mid, centroid, level + 1);
        build(right, mid, end, centroid, level + 1);
    }

    /**
     * Quickselect: afterwards order[k] holds the k-th smallest centroid on the axis, smaller ones before it.
     */
    private void select(int lo, int hi, int k, int axis, float[] centroid) {
        while (lo < hi) {
            float pivot = centroid[order[(lo + hi) >>> 1] * 3 + axis];
            int i = lo, j = hi;
            while (i <= j) {
                while (centroid[order[i] * 3 + axis] < pivot) i++;
                while (centroid[order[j] * 3 + axis] > pivot) j--;
                if (i <= j) {
                    int tmp = order[i];
                    order[i] = order[j];
                    order[j] = tmp;
                    i++;
                    j--;
                }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else return;
        }
    }

    /**
     * Calls {@code visit} with each triangle whose box overlaps the box from {@code min} to {@code max}.
     */
    void overlapping(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, IntConsumer visit) {
        int[] stack = new int[depth + 2];
        int top = 0;
        stack[top++] = 0;
        while (top > 0) {
            int node = stack[--top];
            int b = node * 6;
            if (box[b] > maxX || box[b + 3] < minX || box[b + 1] > maxY || box[b + 4] < minY || box[b + 2] > maxZ || box[b + 5] < minZ) continue;
            if (count[node] == 0) {
                stack[top++] = first[node] + 1;
                stack[top++] = first[node];
            } else {
                for (int i = first[node], end = i + count[node]; i < end; i++) {
                    int t = order[i];
                    if (triangleOverlaps(t, minX, minY, minZ, maxX, maxY, maxZ)) visit.accept(t);
                }
            }
        }
    }

    private boolean triangleOverlaps(int t, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int a = indices[t * 3] * 3, b = indices[t * 3 + 1] * 3, c = indices[t * 3 + 2] * 3;
        float[] p = positions;
        return Math.max(p[a], Math.max(p[b], p[c])) >= minX && Math.min(p[a], Math.min(p[b], p[c])) <= maxX
                && Math.max(p[a + 1], Math.max(p[b + 1], p[c + 1])) >= minY && Math.min(p[a + 1], Math.min(p[b + 1], p[c + 1])) <= maxY
                && Math.max(p[a + 2], Math.max(p[b + 2], p[c + 2])) >= minZ && Math.min(p[a + 2], Math.min(p[b + 2], p[c + 2])) <= maxZ;
    }

    /**
     * The nearest triangle the ray hits from either side, closer than {@code limit}: {triangle, distance} in
     * {@code out}, and true when there was one.
     */
    boolean nearest(float ox, float oy, float oz, float dx, float dy, float dz, float limit, float[] out) {
        float ix = invert(dx), iy = invert(dy), iz = invert(dz);
        float best = limit;
        int hit = -1;
        int[] stack = new int[depth + 2];
        int top = 0;
        stack[top++] = 0;
        while (top > 0) {
            int node = stack[--top];
            if (!hitsBox(node * 6, ox, oy, oz, ix, iy, iz, best)) continue;
            if (count[node] == 0) {
                stack[top++] = first[node] + 1;
                stack[top++] = first[node];
            } else {
                for (int i = first[node], end = i + count[node]; i < end; i++) {
                    float d = intersect(order[i], ox, oy, oz, dx, dy, dz);
                    if (d >= 0f && d < best) {
                        best = d;
                        hit = order[i];
                    }
                }
            }
        }
        if (hit < 0) return false;
        out[0] = hit;
        out[1] = best;
        return true;
    }

    private static float invert(float d) {
        return d == 0f ? 1e30f : 1f / d;
    }

    private boolean hitsBox(int b, float ox, float oy, float oz, float ix, float iy, float iz, float limit) {
        float t1 = (box[b] - ox) * ix, t2 = (box[b + 3] - ox) * ix;
        float near = Math.min(t1, t2), far = Math.max(t1, t2);
        t1 = (box[b + 1] - oy) * iy;
        t2 = (box[b + 4] - oy) * iy;
        near = Math.max(near, Math.min(t1, t2));
        far = Math.min(far, Math.max(t1, t2));
        t1 = (box[b + 2] - oz) * iz;
        t2 = (box[b + 5] - oz) * iz;
        near = Math.max(near, Math.min(t1, t2));
        far = Math.min(far, Math.max(t1, t2));
        return far >= Math.max(near, 0f) && near < limit;
    }

    /**
     * Moller-Trumbore from either side: the distance along the ray, or -1 for a miss.
     */
    private float intersect(int t, float ox, float oy, float oz, float dx, float dy, float dz) {
        int a = indices[t * 3] * 3, b = indices[t * 3 + 1] * 3, c = indices[t * 3 + 2] * 3;
        float[] v = positions;
        float e1x = v[b] - v[a], e1y = v[b + 1] - v[a + 1], e1z = v[b + 2] - v[a + 2];
        float e2x = v[c] - v[a], e2y = v[c + 1] - v[a + 1], e2z = v[c + 2] - v[a + 2];
        float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-20f) return -1f;
        float invDet = 1f / det;
        float tx = ox - v[a], ty = oy - v[a + 1], tz = oz - v[a + 2];
        float u = (tx * px + ty * py + tz * pz) * invDet;
        if (u < 0f || u > 1f) return -1f;
        float qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
        float w = (dx * qx + dy * qy + dz * qz) * invDet;
        if (w < 0f || u + w > 1f) return -1f;
        return (e2x * qx + e2y * qy + e2z * qz) * invDet;
    }
}
