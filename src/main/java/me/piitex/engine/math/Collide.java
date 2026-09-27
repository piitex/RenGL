package me.piitex.engine.math;

/**
 * The overlap tests behind {@link Shape#intersects}. Every pair needs overlap by some area, so shapes that only touch
 * don't count. Polygons and rectangles use the separating axis test: two convex shapes are apart exactly when some
 * edge direction has their shadows apart.
 */
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

    private static float[] corners(Rect r) {
        return new float[]{r.x(), r.y(), r.right(), r.y(), r.right(), r.bottom(), r.x(), r.bottom()};
    }
}
