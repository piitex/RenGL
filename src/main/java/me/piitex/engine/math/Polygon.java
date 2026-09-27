package me.piitex.engine.math;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.Arrays;

/**
 * A convex polygon, such as a turned hit box or a slope. A concave outline (an L shape, a star) is built from several
 * convex ones. The points may be given in either winding; the polygon keeps them clockwise on screen.
 * <p>
 * Immutable: {@link #translate} and {@link #rotate} return new polygons.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class Polygon implements Shape {
    private final float[] points; // x0, y0, x1, y1, ... clockwise on screen (positive signed area with y down)
    private final Rect bounds;

    /**
     * A polygon through the given points, packed as {@code x0, y0, x1, y1, ...}. The array is copied.
     *
     * @throws IllegalArgumentException if there are fewer than 3 points, the outline is not convex, it crosses itself,
     *                                  or it has no area
     */
    public Polygon(float... points) {
        if (points.length % 2 != 0) throw new IllegalArgumentException("points must be x, y pairs, got " + points.length + " floats");
        if (points.length < 6) throw new IllegalArgumentException("a polygon needs at least 3 points, got " + points.length / 2);
        float[] copy = points.clone();
        if (signedArea(copy) < 0f) reverse(copy);
        checkConvex(copy);
        this.points = copy;
        this.bounds = computeBounds(copy);
    }

    public static Polygon of(Vec2... points) {
        float[] packed = new float[points.length * 2];
        for (int i = 0; i < points.length; i++) {
            packed[i * 2] = points[i].x();
            packed[i * 2 + 1] = points[i].y();
        }
        return new Polygon(packed);
    }

    /**
     * A {@code width} by {@code height} box centred on ({@code centerX}, {@code centerY}), turned {@code degrees}
     * clockwise: the hit box of a turned sprite.
     */
    public static Polygon box(float centerX, float centerY, float width, float height, float degrees) {
        float hw = width / 2f, hh = height / 2f;
        return new Polygon(-hw, -hh, hw, -hh, hw, hh, -hw, hh).rotate(degrees, 0f, 0f).translate(centerX, centerY);
    }

    /**
     * A regular polygon with {@code sides} corners on a circle of {@code radius}, the first one straight right of the
     * centre.
     */
    public static Polygon regular(float centerX, float centerY, float radius, int sides) {
        if (sides < 3) throw new IllegalArgumentException("a polygon needs at least 3 sides, got " + sides);
        float[] packed = new float[sides * 2];
        for (int i = 0; i < sides; i++) {
            double angle = Math.PI * 2 * i / sides;
            packed[i * 2] = centerX + radius * (float) Math.cos(angle);
            packed[i * 2 + 1] = centerY + radius * (float) Math.sin(angle);
        }
        return new Polygon(packed);
    }

    private Polygon(float[] points, Rect bounds) {
        this.points = points;
        this.bounds = bounds;
    }

    public int getPointCount() {
        return points.length / 2;
    }

    public float getX(int index) {
        return points[index * 2];
    }

    public float getY(int index) {
        return points[index * 2 + 1];
    }

    /**
     * A copy of the points, packed as {@code x0, y0, x1, y1, ...} in clockwise order on screen, ready for
     * {@link me.piitex.engine.graphics.DebugDraw#polygon}.
     */
    public float[] getPoints() {
        return points.clone();
    }

    /**
     * Whether the point is inside or on the outline.
     */
    @Override
    public boolean contains(float x, float y) {
        int n = points.length / 2;
        for (int i = 0; i < n; i++) {
            float ax = points[i * 2], ay = points[i * 2 + 1];
            int j = (i + 1) % n;
            float ex = points[j * 2] - ax, ey = points[j * 2 + 1] - ay;
            if (ex * (y - ay) - ey * (x - ax) < 0f) return false;
        }
        return true;
    }

    @Override
    public Rect bounds() {
        return bounds;
    }

    @Override
    public Polygon translate(float dx, float dy) {
        float[] moved = points.clone();
        for (int i = 0; i < moved.length; i += 2) {
            moved[i] += dx;
            moved[i + 1] += dy;
        }
        return new Polygon(moved, bounds.translate(dx, dy));
    }

    /**
     * The polygon turned {@code degrees} clockwise around ({@code originX}, {@code originY}).
     */
    public Polygon rotate(float degrees, float originX, float originY) {
        double radians = Math.toRadians(degrees);
        float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
        float[] turned = new float[points.length];
        for (int i = 0; i < points.length; i += 2) {
            float x = points[i] - originX, y = points[i + 1] - originY;
            turned[i] = originX + x * cos - y * sin;
            turned[i + 1] = originY + x * sin + y * cos;
        }
        return new Polygon(turned, computeBounds(turned));
    }

    @Override
    public RayHit raycast(float originX, float originY, float directionX, float directionY, float maxDistance) {
        Collide.checkRay(directionX, directionY, maxDistance);
        if (contains(originX, originY)) return new RayHit(0f, new Vec2(originX, originY), Vec2.ZERO);
        float length = (float) Math.sqrt(directionX * directionX + directionY * directionY);
        float dx = directionX / length, dy = directionY / length;

        // Clip the ray against each edge's half-plane (Cyrus-Beck); the last edge it enters through is the one it hits.
        float enter = 0f, leave = maxDistance, normalX = 0f, normalY = 0f;
        int n = points.length / 2;
        for (int i = 0; i < n; i++) {
            float ax = points[i * 2], ay = points[i * 2 + 1];
            int j = (i + 1) % n;
            float nx = points[j * 2 + 1] - ay, ny = -(points[j * 2] - ax); // outward normal
            if (nx == 0f && ny == 0f) continue;
            float denom = nx * dx + ny * dy;
            float num = nx * (ax - originX) + ny * (ay - originY);
            if (denom == 0f) {
                if (num < 0f) return null; // parallel and outside this edge
            } else if (denom < 0f) {
                float t = num / denom;
                if (t > enter || normalX == 0f && normalY == 0f) {
                    enter = Math.max(t, enter);
                    normalX = nx;
                    normalY = ny;
                }
            } else {
                leave = Math.min(leave, num / denom);
            }
            if (enter > leave) return null;
        }
        if (normalX == 0f && normalY == 0f) return null;
        float normalLength = (float) Math.sqrt(normalX * normalX + normalY * normalY);
        return new RayHit(enter, new Vec2(originX + dx * enter, originY + dy * enter),
                new Vec2(normalX / normalLength, normalY / normalLength));
    }

    float[] points() {
        return points;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Polygon other && Arrays.equals(points, other.points);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(points);
    }

    @Override
    public String toString() {
        return "Polygon" + Arrays.toString(points);
    }

    private static float signedArea(float[] p) {
        float sum = 0f;
        int n = p.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1];
        }
        return sum / 2f;
    }

    private static void reverse(float[] p) {
        for (int i = 0, j = p.length / 2 - 1; i < j; i++, j--) {
            float x = p[i * 2], y = p[i * 2 + 1];
            p[i * 2] = p[j * 2];
            p[i * 2 + 1] = p[j * 2 + 1];
            p[j * 2] = x;
            p[j * 2 + 1] = y;
        }
    }

    private static void checkConvex(float[] p) {
        if (!(signedArea(p) > 0f)) throw new IllegalArgumentException("polygon has no area: " + Arrays.toString(p));
        int n = p.length / 2;
        double turning = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n, k = (i + 2) % n;
            float e1x = p[j * 2] - p[i * 2], e1y = p[j * 2 + 1] - p[i * 2 + 1];
            float e2x = p[k * 2] - p[j * 2], e2y = p[k * 2 + 1] - p[j * 2 + 1];
            float cross = e1x * e2y - e1y * e2x;
            float scale = Math.max(Math.abs(e1x) + Math.abs(e1y), 1f) * Math.max(Math.abs(e2x) + Math.abs(e2y), 1f);
            if (cross < -1e-5f * scale) throw new IllegalArgumentException("polygon is not convex: " + Arrays.toString(p));
            if ((e1x != 0f || e1y != 0f) && (e2x != 0f || e2y != 0f)) turning += Math.atan2(cross, e1x * e2x + e1y * e2y);
        }
        // A convex outline turns once around; a star whose every corner turns the same way goes round more than once.
        if (turning > Math.PI * 2 + 1e-3) throw new IllegalArgumentException("polygon crosses itself: " + Arrays.toString(p));
    }

    private static Rect computeBounds(float[] p) {
        float minX = p[0], minY = p[1], maxX = p[0], maxY = p[1];
        for (int i = 2; i < p.length; i += 2) {
            minX = Math.min(minX, p[i]);
            maxX = Math.max(maxX, p[i]);
            minY = Math.min(minY, p[i + 1]);
            maxY = Math.max(maxY, p[i + 1]);
        }
        return new Rect(minX, minY, maxX - minX, maxY - minY);
    }
}
