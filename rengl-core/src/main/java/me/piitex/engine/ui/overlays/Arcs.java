package me.piitex.engine.ui.overlays;

/**
 * Points along circular arcs, for the round widgets ({@link KnobOverlay}, {@link GaugeOverlay}). Angles are in degrees,
 * measured clockwise from the 3 o'clock direction, which is the way y grows on screen: 0 is right, 90 is down, 180 is
 * left, 270 is up.
 */
public final class Arcs {
    private Arcs() {
    }

    /**
     * The points of an arc of radius {@code r} around ({@code cx}, {@code cy}) from {@code startDeg}, sweeping {@code
     * sweepDeg} clockwise (negative sweeps go the other way), as x, y pairs. Enough segments are used that the arc looks
     * round at that radius; the result always has at least two points.
     */
    public static float[] arc(float cx, float cy, float r, float startDeg, float sweepDeg) {
        int segments = Math.max(2, (int) Math.ceil(Math.abs(sweepDeg) / 360f * Math.max(24f, r * 1.2f)));
        float[] points = new float[(segments + 1) * 2];
        for (int i = 0; i <= segments; i++) {
            double a = Math.toRadians(startDeg + sweepDeg * i / segments);
            points[i * 2] = cx + (float) Math.cos(a) * r;
            points[i * 2 + 1] = cy + (float) Math.sin(a) * r;
        }
        return points;
    }

    /**
     * How many points are in an array {@link #arc} returned, which is what {@code drawPolyline} wants as its point count.
     */
    public static int count(float[] arc) {
        return arc.length / 2;
    }
}
