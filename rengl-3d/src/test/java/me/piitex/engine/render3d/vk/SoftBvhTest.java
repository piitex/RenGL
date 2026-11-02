package me.piitex.engine.render3d.vk;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hierarchy the software ray tracer walks: every box is in it once, it is no deeper than the shader's stack, and a walk
 * through it that follows the shader's rules finds what a loop over every triangle finds.
 */
class SoftBvhTest {
    /** Triangles scattered in a box, as nine floats each. */
    private static float[] soup(Random random, int count, float spread) {
        float[] tris = new float[count * 9];
        for (int i = 0; i < count; i++) {
            float cx = random.nextFloat() * 20f - 10f, cy = random.nextFloat() * 20f - 10f, cz = random.nextFloat() * 20f - 10f;
            for (int v = 0; v < 3; v++) {
                tris[i * 9 + v * 3] = cx + (random.nextFloat() - 0.5f) * spread;
                tris[i * 9 + v * 3 + 1] = cy + (random.nextFloat() - 0.5f) * spread;
                tris[i * 9 + v * 3 + 2] = cz + (random.nextFloat() - 0.5f) * spread;
            }
        }
        return tris;
    }

    private static float[] boxesOf(float[] tris) {
        int count = tris.length / 9;
        float[] boxes = new float[count * 6];
        for (int i = 0; i < count; i++) {
            for (int a = 0; a < 3; a++) {
                float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
                for (int v = 0; v < 3; v++) {
                    lo = Math.min(lo, tris[i * 9 + v * 3 + a]);
                    hi = Math.max(hi, tris[i * 9 + v * 3 + a]);
                }
                boxes[i * 6 + a] = lo;
                boxes[i * 6 + 3 + a] = hi;
            }
        }
        return boxes;
    }

    /** The distance to a triangle along a ray (Möller-Trumbore, two-sided), or infinity. */
    private static double triangle(float[] t, int i, double[] o, double[] d, double tmin, double tmax) {
        double[] p0 = {t[i * 9], t[i * 9 + 1], t[i * 9 + 2]};
        double[] e1 = {t[i * 9 + 3] - p0[0], t[i * 9 + 4] - p0[1], t[i * 9 + 5] - p0[2]};
        double[] e2 = {t[i * 9 + 6] - p0[0], t[i * 9 + 7] - p0[1], t[i * 9 + 8] - p0[2]};
        double[] pv = cross(d, e2);
        double det = dot(e1, pv);
        if (Math.abs(det) < 1e-20) return Double.POSITIVE_INFINITY;
        double[] tv = {o[0] - p0[0], o[1] - p0[1], o[2] - p0[2]};
        double u = dot(tv, pv) / det;
        if (u < -1e-6 || u > 1 + 1e-6) return Double.POSITIVE_INFINITY;
        double[] qv = cross(tv, e1);
        double v = dot(d, qv) / det;
        if (v < -1e-6 || u + v > 1 + 1e-6) return Double.POSITIVE_INFINITY;
        double hit = dot(e2, qv) / det;
        return hit >= tmin && hit < tmax ? hit : Double.POSITIVE_INFINITY;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double bruteForce(float[] tris, double[] o, double[] d, double tmin, double tmax) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < tris.length / 9; i++) best = Math.min(best, triangle(tris, i, o, d, tmin, Math.min(tmax, best)));
        return best;
    }

    /** The walk the shader makes, over the hierarchy built from the same triangles: the nearest hit, or the first one found. */
    private static double walk(SoftBvh bvh, float[] tris, double[] o, double[] d, double tmin, double tmax, boolean anyHit) {
        double[] inv = new double[3];
        for (int a = 0; a < 3; a++) inv[a] = 1.0 / (Math.abs(d[a]) < 1e-9 ? 1e-9 : d[a]);
        double best = tmax;
        double found = Double.POSITIVE_INFINITY;
        int[] stack = new int[SoftBvh.MAX_DEPTH];
        int sp = 0, node = 0;
        while (true) {
            int at = node * SoftBvh.NODE_WORDS;
            double enter = tmin, exit = best;
            for (int a = 0; a < 3; a++) {
                double lo = (Float.intBitsToFloat(bvh.nodes[at + a]) - o[a]) * inv[a];
                double hi = (Float.intBitsToFloat(bvh.nodes[at + 4 + a]) - o[a]) * inv[a];
                enter = Math.max(enter, Math.min(lo, hi));
                exit = Math.min(exit, Math.max(lo, hi) * 1.0000005);
            }
            if (enter <= exit) {
                int tail = bvh.nodes[at + 7];
                if ((tail & SoftBvh.INNER) != 0) {
                    int axis = tail & 3, right = bvh.nodes[at + 3], left = node + 1;
                    boolean rightFirst = d[axis] < 0;
                    stack[sp++] = rightFirst ? left : right;
                    node = rightFirst ? right : left;
                    continue;
                }
                for (int k = 0; k < tail; k++) {
                    double t = triangle(tris, bvh.order[bvh.nodes[at + 3] + k], o, d, tmin, best);
                    if (t < best) {
                        best = t;
                        found = t;
                        if (anyHit) return found;
                    }
                }
            }
            if (sp == 0) return found;
            node = stack[--sp];
        }
    }

    @Test
    void everyBoxIsInTheHierarchyOnce() {
        float[] tris = soup(new Random(1), 3000, 1.5f);
        SoftBvh bvh = SoftBvh.build(boxesOf(tris), 3000, 4);
        boolean[] seen = new boolean[3000];
        int leaves = 0, total = 0;
        for (int n = 0; n < bvh.nodeCount; n++) {
            int tail = bvh.nodes[n * SoftBvh.NODE_WORDS + 7];
            if ((tail & SoftBvh.INNER) != 0) continue;
            leaves++;
            total += tail;
            assertTrue(tail >= 1 && tail <= 8, "a leaf holds " + tail);
            for (int k = 0; k < tail; k++) {
                int box = bvh.order[bvh.nodes[n * SoftBvh.NODE_WORDS + 3] + k];
                assertTrue(!seen[box], "box " + box + " is in two leaves");
                seen[box] = true;
            }
        }
        assertEquals(3000, total);
        assertEquals(bvh.nodeCount, 2 * leaves - 1, "a binary tree has one inner node fewer than it has leaves");
    }

    @Test
    void anInnerNodeBoxHoldsItsChildren() {
        float[] tris = soup(new Random(2), 500, 2f);
        SoftBvh bvh = SoftBvh.build(boxesOf(tris), 500, 2);
        for (int n = 0; n < bvh.nodeCount; n++) {
            int at = n * SoftBvh.NODE_WORDS;
            if ((bvh.nodes[at + 7] & SoftBvh.INNER) == 0) continue;
            for (int child : new int[]{n + 1, bvh.nodes[at + 3]}) {
                for (int a = 0; a < 3; a++) {
                    assertTrue(Float.intBitsToFloat(bvh.nodes[child * 8 + a]) >= Float.intBitsToFloat(bvh.nodes[at + a]));
                    assertTrue(Float.intBitsToFloat(bvh.nodes[child * 8 + 4 + a]) <= Float.intBitsToFloat(bvh.nodes[at + 4 + a]));
                }
            }
        }
    }

    @Test
    void aWalkFindsWhatEveryTriangleFinds() {
        Random random = new Random(3);
        float[] tris = soup(random, 2500, 2.5f);
        SoftBvh bvh = SoftBvh.build(boxesOf(tris), 2500, 4);
        int hits = 0;
        for (int r = 0; r < 600; r++) {
            double[] o = {random.nextDouble() * 30 - 15, random.nextDouble() * 30 - 15, random.nextDouble() * 30 - 15};
            double[] d = {random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5};
            if (r % 50 == 0) d[1] = 0; // along a plane, where 1 / d is not finite
            double length = Math.sqrt(dot(d, d));
            for (int a = 0; a < 3; a++) d[a] /= length;
            double tmax = r % 3 == 0 ? 6 : 100;
            double expected = bruteForce(tris, o, d, 0.001, tmax);
            double nearest = walk(bvh, tris, o, d, 0.001, tmax, false);
            assertEquals(expected, nearest, 1e-9, "ray " + r);
            double any = walk(bvh, tris, o, d, 0.001, tmax, true);
            assertEquals(Double.isInfinite(expected), Double.isInfinite(any), "any hit of ray " + r);
            if (!Double.isInfinite(expected)) hits++;
        }
        assertTrue(hits > 40, "the rays should hit something now and then, hit " + hits);
    }

    @Test
    void aSingleBoxIsALeaf() {
        SoftBvh bvh = SoftBvh.build(new float[]{0, 0, 0, 1, 1, 1}, 1, 4);
        assertEquals(1, bvh.nodeCount);
        assertEquals(1, bvh.nodes[7]);
        assertEquals(1f, bvh.maxX());
    }

    @Test
    void aHeapOfIdenticalBoxesStaysShallow() {
        int count = 200_000;
        float[] boxes = new float[count * 6];
        for (int i = 0; i < count; i++) {
            boxes[i * 6 + 3] = boxes[i * 6 + 4] = boxes[i * 6 + 5] = 1f;
        }
        SoftBvh bvh = SoftBvh.build(boxes, count, 4);
        assertTrue(bvh.depth <= SoftBvh.MAX_DEPTH, "depth " + bvh.depth);
    }

    @Test
    void aLongLineOfBoxesStaysShallow() {
        // The cut that costs least keeps peeling off one box when they are spaced by a doubling distance.
        int count = 60;
        float[] boxes = new float[count * 6];
        for (int i = 0; i < count; i++) {
            float x = (float) Math.pow(1.5, i);
            boxes[i * 6] = x;
            boxes[i * 6 + 3] = x + 0.1f;
            boxes[i * 6 + 4] = boxes[i * 6 + 5] = 0.1f;
        }
        SoftBvh bvh = SoftBvh.build(boxes, count, 1);
        assertTrue(bvh.depth <= SoftBvh.MAX_DEPTH, "depth " + bvh.depth);
    }
}
