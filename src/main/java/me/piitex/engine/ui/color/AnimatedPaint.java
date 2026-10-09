package me.piitex.engine.ui.color;

/**
 * A Paint whose internal state changes over time, such as a scrolling gradient or a
 * pulsing color. advance() is called once per frame with that frame's delta, in seconds
 * since the last frame, which is the same delta already threaded through
 * Element.update() and ElementUpdateEvent.
 * <p>
 * Implementations should keep advance() cheap, since it runs every frame for every
 * element using this Paint whether or not that element is visible. Because advance()
 * runs once per element using the Paint, an instance shared by several elements
 * advances several times per frame. For a color that changes over time and can be
 * shared, {@link RainbowColor} is clock-driven instead and is not an AnimatedPaint.
 */
public interface AnimatedPaint extends Paint {
    void advance(float deltaSeconds);

    /**
     * What the built-in fills draw for this paint right now: a {@link Color} or a {@link LinearGradient}, built from
     * the paint's current state. Called every time the paint is drawn, after {@link #advance}. A paint that
     * overrides this is drawn by every fill that takes a {@link Paint}: backgrounds, borders, text and the parts of
     * the widgets. The default is the paint itself, which is drawn only if it already is a Color or a gradient, as a
     * {@link ScrollingGradient} is. Return null to draw nothing.
     */
    default Paint current() {
        return this;
    }

    /**
     * What to draw for {@code paint}: itself, or for an AnimatedPaint what {@link #current()} gives, followed
     * through if that is another AnimatedPaint (up to a few steps, so a paint that returns itself or loops is safe).
     */
    static Paint resolve(Paint paint) {
        for (int i = 0; i < 4 && paint instanceof AnimatedPaint animated; i++) {
            Paint next = animated.current();
            if (next == paint) break;
            paint = next;
        }
        return paint;
    }

    /** Advances {@code paint} if it is an AnimatedPaint; does nothing for any other paint, or null. */
    static void advanceIfAnimated(Paint paint, float deltaSeconds) {
        if (paint instanceof AnimatedPaint animated) {
            animated.advance(deltaSeconds);
        }
    }
}