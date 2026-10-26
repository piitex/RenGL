package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

/**
 * The closest points between a segment (a point when both ends are the same) and a convex set of corners, by the
 * Gilbert-Johnson-Keerthi algorithm: walk a simplex of up to four corners of their difference toward the origin. It
 * gives the gap between a sphere's centre or a capsule's middle line and a hull or triangle, rounded shapes then
 * being those lines grown by their radius.
 */
final class Gjk {
    private static final int MAX_ITERATIONS = 64;

    private Gjk() {
    }

    /**
     * The gap and where it is. {@code corners} has a bit for each of the set's corners the nearest point is made of,
     * for the first 32; 0 when they overlap.
     */
    record Result(float distance, Vec3 onSegment, Vec3 onSet, int corners) {
        boolean overlapping() {
            return distance <= 0f;
        }
    }

    static Result closest(Vec3 p0, Vec3 p1, Vec3[] set) {
        return closest(new Vec3[]{p0, p1}, set);
    }

    /**
     * The same between two sets of corners: {@code onSegment} is the nearest point of the first set, whatever its size.
     */
    static Result closest(Vec3[] ends, Vec3[] set) {
        Vec3 p0 = ends[0];
        Vec3[] w = new Vec3[4];
        int[] ia = new int[4], ib = new int[4];
        double[] lambda = new double[4];
        int n = 1;
        ia[0] = 0;
        ib[0] = 0;
        w[0] = p0.sub(set[0]);
        lambda[0] = 1;
        Vec3 v = w[0];
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            float vv = v.lengthSquared();
            if (vv < 1e-14f) return new Result(0f, p0, p0, 0);
            // The corner of the difference farthest toward the origin.
            int sa = 0;
            float least = Float.POSITIVE_INFINITY;
            for (int i = 0; i < ends.length; i++) {
                float d = ends[i].dot(v);
                if (d < least) {
                    least = d;
                    sa = i;
                }
            }
            int sb = 0;
            float most = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < set.length; i++) {
                float d = set[i].dot(v);
                if (d > most) {
                    most = d;
                    sb = i;
                }
            }
            Vec3 next = ends[sa].sub(set[sb]);
            if (vv - v.dot(next) <= 1e-6f * vv + 1e-12f) break; // no closer: v is the answer
            boolean again = false;
            for (int i = 0; i < n && !again; i++) again = ia[i] == sa && ib[i] == sb;
            if (again) break;
            ia[n] = sa;
            ib[n] = sb;
            w[n] = next;
            n++;
            n = reduce(w, ia, ib, lambda, n);
            if (n == 4) return new Result(0f, p0, p0, 0); // the origin is inside the simplex
            v = Vec3.ZERO;
            for (int i = 0; i < n; i++) v = v.add(w[i].mul((float) lambda[i]));
        }
        Vec3 onA = Vec3.ZERO, onB = Vec3.ZERO;
        int corners = 0;
        for (int i = 0; i < n; i++) {
            onA = onA.add(ends[ia[i]].mul((float) lambda[i]));
            onB = onB.add(set[ib[i]].mul((float) lambda[i]));
            if (ib[i] < 32) corners |= 1 << ib[i];
        }
        float distance = onA.distance(onB);
        if (distance < 1e-7f) return new Result(0f, p0, p0, 0);
        return new Result(distance, onA, onB, corners);
    }

    /**
     * Finds the point of the simplex nearest the origin by trying every face of it (every subset of its corners): the
     * nearest projection of the origin that falls inside its face. Keeps only the corners of that face, with their
     * weights in {@code lambda}, and returns how many there are.
     */
    private static int reduce(Vec3[] w, int[] ia, int[] ib, double[] lambda, int n) {
        int bestMask = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        double[] bestWeights = new double[4];
        double[] weights = new double[4];
        int[] members = new int[4];
        for (int mask = 1; mask < 1 << n; mask++) {
            int k = 0;
            for (int i = 0; i < n; i++) if ((mask & 1 << i) != 0) members[k++] = i;
            if (!project(w, members, k, weights)) continue;
            boolean inside = true;
            for (int i = 0; i < k && inside; i++) inside = weights[i] > 0;
            if (!inside) continue;
            double x = 0, y = 0, z = 0;
            for (int i = 0; i < k; i++) {
                x += weights[i] * w[members[i]].x();
                y += weights[i] * w[members[i]].y();
                z += weights[i] * w[members[i]].z();
            }
            double distance = x * x + y * y + z * z;
            if (distance < bestDistance - 1e-15) {
                bestDistance = distance;
                bestMask = mask;
                System.arraycopy(weights, 0, bestWeights, 0, k);
            }
        }
        if (bestMask == 0) { // nothing solved cleanly: keep the newest corner alone
            w[0] = w[n - 1];
            ia[0] = ia[n - 1];
            ib[0] = ib[n - 1];
            lambda[0] = 1;
            return 1;
        }
        int k = 0;
        for (int i = 0; i < n; i++) {
            if ((bestMask & 1 << i) == 0) continue;
            w[k] = w[i];
            ia[k] = ia[i];
            ib[k] = ib[i];
            lambda[k] = bestWeights[k];
            k++;
        }
        return k;
    }

    /**
     * Projects the origin onto the line, plane or space through the chosen corners; the weights of the corners that
     * make the projection, summing to 1. False when the corners are too close to lying in a smaller space.
     */
    private static boolean project(Vec3[] w, int[] members, int k, double[] weights) {
        if (k == 1) {
            weights[0] = 1;
            return true;
        }
        Vec3 y0 = w[members[0]];
        double[][] e = new double[k - 1][3];
        for (int i = 1; i < k; i++) {
            Vec3 d = w[members[i]].sub(y0);
            e[i - 1][0] = d.x();
            e[i - 1][1] = d.y();
            e[i - 1][2] = d.z();
        }
        double[] o = {y0.x(), y0.y(), y0.z()};
        int m = k - 1;
        double[][] g = new double[m][m];
        double[] r = new double[m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) g[i][j] = dot(e[i], e[j]);
            r[i] = -dot(e[i], o);
        }
        double[] mu = new double[m];
        if (m == 1) {
            if (g[0][0] < 1e-18) return false;
            mu[0] = r[0] / g[0][0];
        } else if (m == 2) {
            double det = g[0][0] * g[1][1] - g[0][1] * g[1][0];
            if (det <= 1e-10 * g[0][0] * g[1][1] || det < 1e-30) return false;
            mu[0] = (r[0] * g[1][1] - g[0][1] * r[1]) / det;
            mu[1] = (g[0][0] * r[1] - r[0] * g[1][0]) / det;
        } else {
            double det = det3(g[0], g[1], g[2]);
            if (det <= 1e-10 * g[0][0] * g[1][1] * g[2][2] || det < 1e-40) return false;
            double[][] c = new double[3][3];
            for (int col = 0; col < 3; col++) {
                for (int row = 0; row < 3; row++) {
                    for (int j = 0; j < 3; j++) c[row][j] = j == col ? r[row] : g[row][j];
                }
                mu[col] = det3(c[0], c[1], c[2]) / det;
            }
        }
        double sum = 0;
        for (int i = 0; i < m; i++) {
            weights[i + 1] = mu[i];
            sum += mu[i];
        }
        weights[0] = 1 - sum;
        return true;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double det3(double[] a, double[] b, double[] c) {
        return a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1] - b[1] * c[0]);
    }
}
