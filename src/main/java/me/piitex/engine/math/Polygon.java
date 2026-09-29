package me.piitex.engine.math;

import me.piitex.engine.utils.flags.GeneratedClass;
import me.piitex.engine.utils.flags.GeneratedFragments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A convex polygon, such as a turned hit box or a slope. A concave outline (an L shape, a star) is built from several
 * convex ones, which {@link #decompose} works out. The points may be given in either winding; the polygon keeps them
 * clockwise on screen.
 * <p>
 * Immutable: {@link #translate} and {@link #rotate} return new polygons.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
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

    /**
     * Cuts an outline of any shape, concave included (an L, a star, a hill), into convex polygons that together cover
     * it exactly, as few as it easily can. A convex outline comes back as one polygon. The points are packed as
     * {@code x0, y0, x1, y1, ...} in either winding; repeated points and points on a straight line are dropped.
     * <pre>{@code
     * for (Polygon piece : Polygon.decompose(0, 0, 90, 0, 90, 30, 30, 30, 30, 90, 0, 90)) walls.add(piece); // an L
     * }</pre>
     *
     * @throws IllegalArgumentException if the outline crosses itself, has no area, or has fewer than 3 points
     */
    public static List<Polygon> decompose(float... outline) {
        if (outline.length % 2 != 0) throw new IllegalArgumentException("points must be x, y pairs, got " + outline.length + " floats");
        float[] p = simplify(outline);
        if (p.length < 6 || !(signedArea(p) != 0f)) throw new IllegalArgumentException("outline has no area: " + Arrays.toString(outline));
        if (signedArea(p) < 0f) reverse(p);
        int n = p.length / 2;

        // Ear clipping: cut off corners that bulge outwards and hold no other point, until a triangle is left.
        List<Integer> left = new ArrayList<>(n);
        for (int i = 0; i < n; i++) left.add(i);
        List<int[]> pieces = new ArrayList<>();
        while (left.size() > 3) {
            boolean cut = false;
            for (int k = 0; k < left.size(); k++) {
                int a = left.get((k + left.size() - 1) % left.size()), b = left.get(k), c = left.get((k + 1) % left.size());
                if (!(cross(p, a, b, c) > 0f) || holdsAnother(p, left, a, b, c)) continue;
                pieces.add(new int[]{a, b, c});
                left.remove(k);
                cut = true;
                break;
            }
            if (!cut) throw new IllegalArgumentException("outline crosses itself: " + Arrays.toString(outline));
        }
        pieces.add(new int[]{left.get(0), left.get(1), left.get(2)});

        // Hertel-Mehlhorn: join pieces across the cuts wherever the joined piece is still convex.
        boolean joined = true;
        while (joined) {
            joined = false;
            search:
            for (int i = 0; i < pieces.size(); i++) {
                for (int j = i + 1; j < pieces.size(); j++) {
                    int[] merged = join(p, pieces.get(i), pieces.get(j));
                    if (merged == null) continue;
                    pieces.set(i, merged);
                    pieces.remove(j);
                    joined = true;
                    break search;
                }
            }
        }

        List<Polygon> polygons = new ArrayList<>(pieces.size());
        for (int[] piece : pieces) {
            float[] packed = new float[piece.length * 2];
            for (int i = 0; i < piece.length; i++) {
                packed[i * 2] = p[piece[i] * 2];
                packed[i * 2 + 1] = p[piece[i] * 2 + 1];
            }
            polygons.add(new Polygon(simplify(packed)));
        }
        return polygons;
    }

    /**
     * The outline without repeated points or points in the middle of a straight edge.
     */
    private static float[] simplify(float[] points) {
        List<Float> kept = new ArrayList<>();
        int n = points.length / 2;
        for (int i = 0; i < n; i++) {
            float x = points[i * 2], y = points[i * 2 + 1];
            float px = points[((i + n - 1) % n) * 2], py = points[((i + n - 1) % n) * 2 + 1];
            float nx = points[((i + 1) % n) * 2], ny = points[((i + 1) % n) * 2 + 1];
            if (x == px && y == py) continue;
            float cross = (x - px) * (ny - y) - (y - py) * (nx - x);
            float dot = (x - px) * (nx - x) + (y - py) * (ny - y);
            float scale = (Math.abs(x - px) + Math.abs(y - py)) * (Math.abs(nx - x) + Math.abs(ny - y));
            if (Math.abs(cross) <= 1e-6f * scale && dot > 0f) continue; // straight on
            kept.add(x);
            kept.add(y);
        }
        float[] out = new float[kept.size()];
        for (int i = 0; i < out.length; i++) out[i] = kept.get(i);
        return out.length < points.length ? simplify(out) : out;
    }

    private static float cross(float[] p, int a, int b, int c) {
        return (p[b * 2] - p[a * 2]) * (p[c * 2 + 1] - p[b * 2 + 1]) - (p[b * 2 + 1] - p[a * 2 + 1]) * (p[c * 2] - p[b * 2]);
    }

    private static boolean holdsAnother(float[] p, List<Integer> left, int a, int b, int c) {
        for (int i : left) {
            if (i == a || i == b || i == c) continue;
            float x = p[i * 2], y = p[i * 2 + 1];
            if ((x == p[a * 2] && y == p[a * 2 + 1]) || (x == p[b * 2] && y == p[b * 2 + 1]) || (x == p[c * 2] && y == p[c * 2 + 1])) continue;
            if (side(p, a, b, x, y) >= 0f && side(p, b, c, x, y) >= 0f && side(p, c, a, x, y) >= 0f) return true;
        }
        return false;
    }

    private static float side(float[] p, int a, int b, float x, float y) {
        return (p[b * 2] - p[a * 2]) * (y - p[a * 2 + 1]) - (p[b * 2 + 1] - p[a * 2 + 1]) * (x - p[a * 2]);
    }

    /**
     * The two pieces as one, if they share an edge and the result is convex; otherwise null.
     */
    private static int[] join(float[] p, int[] first, int[] second) {
        for (int i = 0; i < first.length; i++) {
            int u = first[i], v = first[(i + 1) % first.length];
            for (int j = 0; j < second.length; j++) {
                if (second[j] != v || second[(j + 1) % second.length] != u) continue;
                // first runs ... u, v ...; second runs ... v, u ...: go round first from v to u, then second past u to v.
                int[] merged = new int[first.length + second.length - 2];
                int at = 0;
                for (int k = 0; k < first.length; k++) merged[at++] = first[(i + 1 + k) % first.length];
                for (int k = 2; k < second.length; k++) merged[at++] = second[(j + k) % second.length];
                for (int k = 0; k < merged.length; k++) {
                    int a = merged[k], b = merged[(k + 1) % merged.length], c = merged[(k + 2) % merged.length];
                    if (cross(p, a, b, c) < 0f) return null;
                }
                return merged;
            }
        }
        return null;
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
