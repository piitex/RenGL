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
}