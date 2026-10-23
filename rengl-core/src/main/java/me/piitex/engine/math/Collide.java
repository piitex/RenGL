package me.piitex.engine.math;

import me.piitex.engine.utils.flags.GeneratedFragments;

/**
 * The overlap tests behind {@link Shape#intersects}. Every pair needs overlap by some area, so shapes that only touch
 * don't count. Polygons and rectangles use the separating axis test: two convex shapes are apart exactly when some
 * edge direction has their shadows apart.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
final class Collide {

    private Collide() {
    }

    static boolean intersects(Shape a, Shape b) {
        return switch (a) {
            case Rect r -> switch (b) {
                case Rect o -> r.intersects(o);
                case Circle c -> rectCircle(r, c);
                case Polygon p -> rectPolygon(r, p);
            };
            case Circle c -> switch (b) {
                case Rect r -> rectCircle(r, c);
                case Circle o -> circles(c, o);
                case Polygon p -> polygonCircle(p, c);
            };
            case Polygon p -> switch (b) {
                case Rect r -> rectPolygon(r, p);
                case Circle c -> polygonCircle(p, c);
                case Polygon o -> polygons(p.points(), o.points());
            };
        };
    }

    /**
     * How to push {@code a} out of {@code b}, or null when {@link #intersects} says they don't overlap.
     */
    static Overlap overlap(Shape a, Shape b) {
        if (!intersects(a, b)) return null;
        if (a instanceof Circle ca && b instanceof Circle cb) return circlesOverlap(ca, cb);
        if (a instanceof Rect ra && b instanceof Rect rb) return rectsOverlap(ra, rb);
        if (a instanceof Circle ca && b instanceof Rect rb) return circleOutOfRect(ca, rb);
        if (a instanceof Rect ra && b instanceof Circle cb) {
            Overlap out = circleOutOfRect(cb, ra);
            return new Overlap(new Vec2(0f - out.normal().x(), 0f - out.normal().y()), out.depth()); // 0f - keeps no -0
        }
        // Everything else has a polygon, and a rectangle takes part as its four corners.
        return separate(outline(a), a instanceof Circle c ? c : null, outline(b), b instanceof Circle c ? c : null);
    }

    static void checkRay(float directionX, float directionY, float maxDistance) {
        if (directionX == 0f && directionY == 0f) throw new IllegalArgumentException("ray direction must not be zero");
        if (!(maxDistance >= 0f)) throw new IllegalArgumentException("maxDistance must not be negative: " + maxDistance);
    }

    private static boolean circles(Circle a, Circle b) {
        if (!(a.radius() > 0f) || !(b.radius() > 0f)) return false;
        float dx = b.x() - a.x(), dy = b.y() - a.y(), r = a.radius() + b.radius();
        return dx * dx + dy * dy < r * r;
    }

    private static boolean rectCircle(Rect r, Circle c) {
        if (r.isEmpty() || !(c.radius() > 0f)) return false;
        float nearestX = Math.clamp(c.x(), r.x(), r.right());
        float nearestY = Math.clamp(c.y(), r.y(), r.bottom());
        float dx = c.x() - nearestX, dy = c.y() - nearestY;
        return dx * dx + dy * dy < c.radius() * c.radius();
    }

    private static boolean rectPolygon(Rect r, Polygon p) {
        if (r.isEmpty() || !r.intersects(p.bounds())) return false;
        return polygons(corners(r), p.points());
    }

    private static boolean polygons(float[] a, float[] b) {
        return !separatedOnEdges(a, a, b) && !separatedOnEdges(b, a, b);
    }

    /**
     * Whether a normal of one of {@code edges}' edges separates the shadows of {@code a} and {@code b}.
     */
    private static boolean separatedOnEdges(float[] edges, float[] a, float[] b) {
        int n = edges.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float nx = edges[j * 2 + 1] - edges[i * 2 + 1], ny = edges[i * 2] - edges[j * 2];
            if (nx == 0f && ny == 0f) continue;
            if (apart(a, b, nx, ny)) return true;
        }
        return false;
    }

    private static boolean apart(float[] a, float[] b, float axisX, float axisY) {
        float minA = Float.POSITIVE_INFINITY, maxA = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < a.length; i += 2) {
            float d = a[i] * axisX + a[i + 1] * axisY;
            minA = Math.min(minA, d);
            maxA = Math.max(maxA, d);
        }
        float minB = Float.POSITIVE_INFINITY, maxB = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < b.length; i += 2) {
            float d = b[i] * axisX + b[i + 1] * axisY;
            minB = Math.min(minB, d);
            maxB = Math.max(maxB, d);
        }
        return !(minA < maxB && minB < maxA);
    }

    private static boolean polygonCircle(Polygon p, Circle c) {
        if (!(c.radius() > 0f)) return false;
        float[] points = p.points();
        int n = points.length / 2;
        float r = c.radius();

        // Axes: every edge normal, plus the direction from the nearest corner to the centre.
        int nearest = 0;
        float nearestDistance = Float.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            float dx = c.x() - points[i * 2], dy = c.y() - points[i * 2 + 1];
            float d = dx * dx + dy * dy;
            if (d < nearestDistance) {
                nearestDistance = d;
                nearest = i;
            }
            int j = (i + 1) % n;
            float nx = points[j * 2 + 1] - points[i * 2 + 1], ny = points[i * 2] - points[j * 2];
            if ((nx != 0f || ny != 0f) && apartFromCircle(points, c, r, nx, ny)) return false;
        }
        float ax = c.x() - points[nearest * 2], ay = c.y() - points[nearest * 2 + 1];
        return ax == 0f && ay == 0f || !apartFromCircle(points, c, r, ax, ay);
    }

    private static boolean apartFromCircle(float[] points, Circle c, float r, float axisX, float axisY) {
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < points.length; i += 2) {
            float d = points[i] * axisX + points[i + 1] * axisY;
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        float centre = c.x() * axisX + c.y() * axisY;
        float extent = r * (float) Math.sqrt(axisX * axisX + axisY * axisY);
        return !(centre - extent < max && min < centre + extent);
    }

    private static Overlap circlesOverlap(Circle a, Circle b) {
        float dx = a.x() - b.x(), dy = a.y() - b.y();
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        float depth = a.radius() + b.radius() - distance;
        // Circles on the same centre have no way out that is better than another: go up.
        return distance > 0f ? new Overlap(new Vec2(dx / distance, dy / distance), depth) : new Overlap(new Vec2(0f, -1f), depth);
    }

    private static Overlap rectsOverlap(Rect a, Rect b) {
        // On each axis a can leave by either side of b; the shorter way on the shorter axis wins, down or up on a tie.
        float left = a.right() - b.x(), right = b.right() - a.x();
        float up = a.bottom() - b.y(), down = b.bottom() - a.y();
        float acrossDepth = Math.min(left, right), verticalDepth = Math.min(up, down);
        if (acrossDepth < verticalDepth) return new Overlap(new Vec2(left <= right ? -1f : 1f, 0f), acrossDepth);
        return new Overlap(new Vec2(0f, up <= down ? -1f : 1f), verticalDepth);
    }

    private static Overlap circleOutOfRect(Circle c, Rect r) {
        float nearestX = Math.clamp(c.x(), r.x(), r.right());
        float nearestY = Math.clamp(c.y(), r.y(), r.bottom());
        float dx = c.x() - nearestX, dy = c.y() - nearestY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance > 0f) return new Overlap(new Vec2(dx / distance, dy / distance), c.radius() - distance);
        // The centre is inside: leave through the nearest edge, the top one on a tie.
        float top = c.y() - r.y(), bottom = r.bottom() - c.y(), left = c.x() - r.x(), right = r.right() - c.x();
        float nearest = Math.min(Math.min(top, bottom), Math.min(left, right));
        Vec2 normal = nearest == top ? new Vec2(0f, -1f) : nearest == bottom ? new Vec2(0f, 1f)
                : nearest == left ? new Vec2(-1f, 0f) : new Vec2(1f, 0f);
        return new Overlap(normal, nearest + c.radius());
    }

    // A shape's corners for the separating axis test, or null for a circle.
    private static float[] outline(Shape shape) {
        return switch (shape) {
            case Polygon p -> p.points();
            case Rect r -> corners(r);
            case Circle c -> null;
        };
    }

    /**
     * The shortest push of a out of b over every separating axis: each edge normal, and for a circle the direction
     * from the polygon's nearest corner to its centre. Each shape is either a polygon's points or a circle.
     */
    private static Overlap separate(float[] a, Circle circleA, float[] b, Circle circleB) {
        float[] best = {Float.POSITIVE_INFINITY, 0f, 0f}; // depth, normal x, normal y
        if (a != null) tryEdges(a, a, circleA, b, circleB, best);
        if (b != null) tryEdges(b, a, circleA, b, circleB, best);
        Circle circle = circleA != null ? circleA : circleB;
        if (circle != null) {
            float[] points = circleA != null ? b : a;
            int nearest = 0;
            float nearestDistance = Float.POSITIVE_INFINITY;
            for (int i = 0; i < points.length; i += 2) {
                float dx = circle.x() - points[i], dy = circle.y() - points[i + 1];
                float d = dx * dx + dy * dy;
                if (d < nearestDistance) {
                    nearestDistance = d;
                    nearest = i;
                }
            }
            tryAxis(circle.x() - points[nearest], circle.y() - points[nearest + 1], a, circleA, b, circleB, best);
        }
        return new Overlap(new Vec2(best[1], best[2]), Math.max(0f, best[0]));
    }

    private static void tryEdges(float[] edges, float[] a, Circle circleA, float[] b, Circle circleB, float[] best) {
        int n = edges.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            tryAxis(edges[j * 2 + 1] - edges[i * 2 + 1], edges[i * 2] - edges[j * 2], a, circleA, b, circleB, best);
        }
    }

    private static void tryAxis(float axisX, float axisY, float[] a, Circle circleA, float[] b, Circle circleB, float[] best) {
        float length = (float) Math.sqrt(axisX * axisX + axisY * axisY);
        if (!(length > 0f)) return;
        axisX /= length;
        axisY /= length;
        float[] shadowA = shadow(a, circleA, axisX, axisY), shadowB = shadow(b, circleB, axisX, axisY);
        // a can leave towards the low end of the axis (past b's minimum) or the high end (past b's maximum).
        float towardsLow = shadowA[1] - shadowB[0], towardsHigh = shadowB[1] - shadowA[0];
        float depth = Math.min(towardsLow, towardsHigh);
        if (depth < best[0]) {
            float sign = towardsHigh < towardsLow ? 1f : -1f;
            best[0] = depth;
            best[1] = axisX * sign + 0f; // + 0f turns -0 into 0, so a level normal reads (0, -1)
            best[2] = axisY * sign + 0f;
        }
    }

    // The span {min, max} a polygon or circle covers along a unit axis.
    private static float[] shadow(float[] points, Circle circle, float axisX, float axisY) {
        if (points == null) {
            float centre = circle.x() * axisX + circle.y() * axisY;
            return new float[]{centre - circle.radius(), centre + circle.radius()};
        }
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < points.length; i += 2) {
            float d = points[i] * axisX + points[i + 1] * axisY;
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        return new float[]{min, max};
    }

    private static float[] corners(Rect r) {
        return new float[]{r.x(), r.y(), r.right(), r.y(), r.right(), r.bottom(), r.x(), r.bottom()};
    }
}
