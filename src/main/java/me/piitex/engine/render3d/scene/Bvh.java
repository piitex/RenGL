package me.piitex.engine.render3d.scene;

/**
 * A bounding-volume hierarchy over a mesh's triangles, so a ray visits the few triangles near its path instead of all of
 * them. Built once, from the mesh's own space, the first time the mesh is picked. Each node is a box; a node is either
 * a leaf holding a few triangles or has two children.
 */
final class Bvh {
    private static final int LEAF_SIZE = 4;

    /**
     * The nearest triangle a ray found.
     */
    static final class Result {
        float distance;
        int triangle = -1;
        float nx, ny, nz;
    }

    private final float[] vertices;
    private final int[] indices;
    private final int stride;
    private final int[] order;        // triangle numbers, grouped so every node owns a contiguous run
    private float[] box = new float[6 * 64]; // min xyz, max xyz per node
    private int[] first = new int[64];       // leaf: start in order[]; inner: the left child (the right is next to it)
    private int[] count = new int[64];       // leaf: triangle count; inner: 0
    private int nodes;

    Bvh(float[] vertices, int[] indices, int stride) {
        this.vertices = vertices;
        this.indices = indices;
        this.stride = stride;
        int triangles = indices.length / 3;
        order = new int[triangles];
        for (int i = 0; i < triangles; i++) order[i] = i;
        float[] centroid = new float[triangles * 3];
        for (int t = 0; t < triangles; t++) {
            for (int axis = 0; axis < 3; axis++) {
                centroid[t * 3 + axis] = (vertices[indices[t * 3] * stride + axis] + vertices[indices[t * 3 + 1] * stride + axis]
                        + vertices[indices[t * 3 + 2] * stride + axis]) / 3f;
            }
        }
        int root = newNode();
        build(root, 0, triangles, centroid);
    }

    private int newNode() {
        if (nodes == first.length) {
            first = java.util.Arrays.copyOf(first, nodes * 2);
            count = java.util.Arrays.copyOf(count, nodes * 2);
            box = java.util.Arrays.copyOf(box, nodes * 12);
        }
        return nodes++;
    }

    private void build(int node, int start, int end, float[] centroid) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        float cMinX = Float.MAX_VALUE, cMinY = Float.MAX_VALUE, cMinZ = Float.MAX_VALUE;
        float cMaxX = -Float.MAX_VALUE, cMaxY = -Float.MAX_VALUE, cMaxZ = -Float.MAX_VALUE;
        for (int i = start; i < end; i++) {
            int t = order[i];
            for (int k = 0; k < 3; k++) {
                int v = indices[t * 3 + k] * stride;
                minX = Math.min(minX, vertices[v]);
                minY = Math.min(minY, vertices[v + 1]);
                minZ = Math.min(minZ, vertices[v + 2]);
                maxX = Math.max(maxX, vertices[v]);
                maxY = Math.max(maxY, vertices[v + 1]);
                maxZ = Math.max(maxZ, vertices[v + 2]);
            }
            cMinX = Math.min(cMinX, centroid[t * 3]);
            cMinY = Math.min(cMinY, centroid[t * 3 + 1]);
            cMinZ = Math.min(cMinZ, centroid[t * 3 + 2]);
            cMaxX = Math.max(cMaxX, centroid[t * 3]);
            cMaxY = Math.max(cMaxY, centroid[t * 3 + 1]);
            cMaxZ = Math.max(cMaxZ, centroid[t * 3 + 2]);
        }
        int b = node * 6;
        box[b] = minX;
        box[b + 1] = minY;
        box[b + 2] = minZ;
        box[b + 3] = maxX;
        box[b + 4] = maxY;
        box[b + 5] = maxZ;

        int n = end - start;
        float ex = cMaxX - cMinX, ey = cMaxY - cMinY, ez = cMaxZ - cMinZ;
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
        build(left, start, mid, centroid);
        build(right, mid, end, centroid);
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
     * The nearest triangle the ray hits closer than {@code result.distance}, which starts as the farthest distance of
     * interest. A hit updates the result. Distances are in units of the direction's length.
     */
    void nearest(float ox, float oy, float oz, float dx, float dy, float dz, boolean frontOnly, Result result) {
        float ix = invert(dx), iy = invert(dy), iz = invert(dz);
        int[] stack = new int[64];
        int top = 0;
        stack[top++] = 0;
        while (top > 0) {
            int node = stack[--top];
            if (!hitsBox(node * 6, ox, oy, oz, ix, iy, iz, result.distance)) continue;
            if (count[node] == 0) {
                // Visit the nearer child first so the farther one is often skipped.
                int left = first[node], right = left + 1;
                stack[top++] = right;
                stack[top++] = left;
            } else {
                for (int i = first[node], end = i + count[node]; i < end; i++) {
                    testTriangle(order[i], ox, oy, oz, dx, dy, dz, frontOnly, result);
                }
            }
        }
    }

    /**
     * 1/d, with a huge number for a zero component so the slab test never multiplies zero by infinity.
     */
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

    private void testTriangle(int t, float ox, float oy, float oz, float dx, float dy, float dz, boolean frontOnly, Result r) {
        int a = indices[t * 3] * stride, b = indices[t * 3 + 1] * stride, c = indices[t * 3 + 2] * stride;
        float[] v = vertices;
        float e1x = v[b] - v[a], e1y = v[b + 1] - v[a + 1], e1z = v[b + 2] - v[a + 2];
        float e2x = v[c] - v[a], e2y = v[c + 1] - v[a + 1], e2z = v[c + 2] - v[a + 2];
        // Moller-Trumbore. The determinant is positive when the ray comes at the counter-clockwise (front) side.
        float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (frontOnly ? det < 1e-20f : Math.abs(det) < 1e-20f) return;
        float invDet = 1f / det;
        float tx = ox - v[a], ty = oy - v[a + 1], tz = oz - v[a + 2];
        float u = (tx * px + ty * py + tz * pz) * invDet;
        if (u < 0f || u > 1f) return;
        float qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
        float w = (dx * qx + dy * qy + dz * qz) * invDet;
        if (w < 0f || u + w > 1f) return;
        float distance = (e2x * qx + e2y * qy + e2z * qz) * invDet;
        if (distance < 0f || distance >= r.distance) return;
        r.distance = distance;
        r.triangle = t;
        r.nx = e1y * e2z - e1z * e2y;
        r.ny = e1z * e2x - e1x * e2z;
        r.nz = e1x * e2y - e1y * e2x;
    }
}
