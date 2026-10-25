package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Vec3;

import java.util.Arrays;

/**
 * A small depth picture drawn on the CPU from the scene's occluders, to ask whether something is wholly hidden behind them.
 * <p>
 * It errs on the side of drawing. An occluder's triangle only marks a pixel when it covers the whole pixel (all four corners are
 * inside it), and then with the <em>farthest</em> depth it has anywhere in that pixel; where occluders overlap the nearer of
 * those wins. A box is hidden only if every pixel of its screen rectangle is marked with a depth nearer than the nearest point
 * of the box. A rectangle that runs off the screen is judged by the part on it, and a box with a corner behind the camera is
 * never hidden. Depth is the projected {@code z / w}, which runs 0 at the near plane to 1 at the far plane and is a straight
 * function of the screen position across a flat triangle. Plain arithmetic, checked without a GPU.
 */
final class OcclusionBuffer {
    static final int WIDTH = 256, HEIGHT = 144;
    /** Depth meaning "nothing marked here". */
    private static final float NONE = Float.POSITIVE_INFINITY;

    private final float[] depth = new float[WIDTH * HEIGHT];
    private float[] m;
    /** Triangles drawn into the buffer this frame. */
    int triangles;

    /** Empties the buffer and sets the view-projection matrix (column-major) that everything is drawn and asked about with. */
    void begin(float[] viewProj) {
        m = viewProj;
        Arrays.fill(depth, NONE);
        triangles = 0;
    }

    /** Whether any occluder marked a pixel. */
    boolean isEmpty() {
        return triangles == 0;
    }

    float depthAt(int x, int y) {
        return depth[y * WIDTH + x];
    }

    // ---- drawing occluders ----------------------------------------------------------------------------------------

    /**
     * Draws a world-space triangle. The part behind the near plane is cut off first, so a floor under the camera still counts.
     */
    void addTriangle(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz) {
        int n = 0;
        double[][] v = {clip(ax, ay, az), clip(bx, by, bz), clip(cx, cy, cz)};
        double[][] out = new double[5][];
        for (int i = 0; i < 3; i++) {
            double[] p = v[i], q = v[(i + 1) % 3];
            boolean pin = p[2] >= 0 && p[3] > 0, qin = q[2] >= 0 && q[3] > 0;
            if (pin) out[n++] = p;
            if (pin != qin) {
                double t = p[2] / (p[2] - q[2]);
                out[n++] = new double[]{p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t, 0, p[3] + (q[3] - p[3]) * t};
            }
        }
        if (n < 3) return;
        triangles++;
        double[][] s = new double[n][];
        for (int i = 0; i < n; i++) {
            double w = Math.max(out[i][3], 1e-9);
            s[i] = new double[]{(out[i][0] / w * 0.5 + 0.5) * WIDTH, (out[i][1] / w * 0.5 + 0.5) * HEIGHT, Math.min(1.0, Math.max(0.0, out[i][2] / w))};
        }
        for (int i = 1; i + 1 < n; i++) fill(s[0], s[i], s[i + 1]);
    }

    private double[] clip(double x, double y, double z) {
        return new double[]{m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14], m[3] * x + m[7] * y + m[11] * z + m[15]};
    }

    /** Marks the pixels a screen-space triangle {@code {x, y, depth}} covers completely. */
    private void fill(double[] a, double[] b, double[] c) {
        double area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        if (Math.abs(area) < 1e-9) return;
        // Depth is a plane over the screen: z = a.z + dzdx * (x - a.x) + dzdy * (y - a.y).
        double dzdx = ((b[2] - a[2]) * (c[1] - a[1]) - (c[2] - a[2]) * (b[1] - a[1])) / area;
        double dzdy = ((c[2] - a[2]) * (b[0] - a[0]) - (b[2] - a[2]) * (c[0] - a[0])) / area;
        int x0 = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int x1 = (int) Math.min(WIDTH - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))) - 1);
        int y0 = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int y1 = (int) Math.min(HEIGHT - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))) - 1);
        double sign = area > 0 ? 1 : -1;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (!inside(a, b, c, x, y, sign) || !inside(a, b, c, x + 1, y, sign)
                        || !inside(a, b, c, x, y + 1, sign) || !inside(a, b, c, x + 1, y + 1, sign)) continue;
                double far = Math.max(Math.max(plane(a, dzdx, dzdy, x, y), plane(a, dzdx, dzdy, x + 1, y)),
                        Math.max(plane(a, dzdx, dzdy, x, y + 1), plane(a, dzdx, dzdy, x + 1, y + 1)));
                float f = (float) Math.min(1.0, Math.nextUp((float) far));
                int i = y * WIDTH + x;
                if (f < depth[i]) depth[i] = f;
            }
        }
    }

    private static double plane(double[] a, double dzdx, double dzdy, double x, double y) {
        return a[2] + dzdx * (x - a[0]) + dzdy * (y - a[1]);
    }

    private static boolean inside(double[] a, double[] b, double[] c, double x, double y, double sign) {
        return sign * edge(a, b, x, y) >= -1e-9 && sign * edge(b, c, x, y) >= -1e-9 && sign * edge(c, a, x, y) >= -1e-9;
    }

    private static double edge(double[] p, double[] q, double x, double y) {
        return (q[0] - p[0]) * (y - p[1]) - (q[1] - p[1]) * (x - p[0]);
    }

    // ---- asking ---------------------------------------------------------------------------------------------------

    /**
     * Whether a box (its eight corners, as {@code x, y, z} triples) is hidden behind what was drawn.
     */
    boolean hides(double[] corners) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, nearest = Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            double[] p = clip(corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2]);
            if (p[3] <= 1e-6 || p[2] < 0) return false; // a corner at or behind the near plane: it may be on the screen
            double sx = (p[0] / p[3] * 0.5 + 0.5) * WIDTH, sy = (p[1] / p[3] * 0.5 + 0.5) * HEIGHT;
            minX = Math.min(minX, sx);
            maxX = Math.max(maxX, sx);
            minY = Math.min(minY, sy);
            maxY = Math.max(maxY, sy);
            nearest = Math.min(nearest, p[2] / p[3]);
        }
        int x0 = (int) Math.max(0, Math.floor(minX)), x1 = (int) Math.min(WIDTH - 1, Math.ceil(maxX) - 1);
        int y0 = (int) Math.max(0, Math.floor(minY)), y1 = (int) Math.min(HEIGHT - 1, Math.ceil(maxY) - 1);
        if (x0 > x1 || y0 > y1) return false; // off the screen: the frustum test deals with that
        float limit = (float) nearest;
        for (int y = y0; y <= y1; y++) {
            int row = y * WIDTH;
            for (int x = x0; x <= x1; x++) {
                if (!(depth[row + x] < limit)) return false;
            }
        }
        return true;
    }

    /** The eight corners of a box in world space after a column-major transform, as {@code x, y, z} triples. */
    static double[] corners(Bounds bounds, float[] w) {
        Vec3 lo = bounds.min(), hi = bounds.max();
        double[] out = new double[24];
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? lo.x() : hi.x(), y = (i & 2) == 0 ? lo.y() : hi.y(), z = (i & 4) == 0 ? lo.z() : hi.z();
            out[i * 3] = w[0] * x + w[4] * y + w[8] * z + w[12];
            out[i * 3 + 1] = w[1] * x + w[5] * y + w[9] * z + w[13];
            out[i * 3 + 2] = w[2] * x + w[6] * y + w[10] * z + w[14];
        }
        return out;
    }
}
