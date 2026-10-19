package me.piitex.engine.ui.color;

import me.piitex.engine.gl.renderer.Renderer;

/**
 * Small helpers for widgets that mix or fade between two {@link Paint}s.
 */
public final class Paints {
    private Paints() {
    }

    /**
     * Fills a rectangle with {@code from} at {@code t} = 0 and {@code to} at {@code t} = 1. Two plain colors are
     * blended; with a gradient involved, {@code to} is drawn over {@code from} at opacity {@code t}.
     */
    public static void fillMixed(Renderer renderer, float x, float y, float width, float height, Paint from, Paint to, float t,
                                 float cornerRadius) {
        if (AnimatedPaint.resolve(from) instanceof Color a && AnimatedPaint.resolve(to) instanceof Color b) {
            renderer.drawQuad(x, y, width, height, mix(a, b, t), cornerRadius);
            return;
        }
        renderer.drawQuad(x, y, width, height, from, cornerRadius);
        if (t <= 0f) return;
        renderer.pushAlpha(t);
        renderer.drawQuad(x, y, width, height, to, cornerRadius);
        renderer.popAlpha();
    }

    /**
     * The color a fraction {@code t} of the way from {@code a} to {@code b}, channel by channel.
     */
    public static Color mix(Color a, Color b, float t) {
        return new Color(
                a.getR() + (b.getR() - a.getR()) * t,
                a.getG() + (b.getG() - a.getG()) * t,
                a.getB() + (b.getB() - a.getB()) * t,
                a.getA() + (b.getA() - a.getA()) * t);
    }
}
