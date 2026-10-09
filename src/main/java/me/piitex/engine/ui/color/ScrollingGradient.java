package me.piitex.engine.ui.color;

import me.piitex.engine.utils.flags.GeneratedFragments;

import java.util.List;

/**
 * A LinearGradient whose position scrolls over time. The offset accumulates in
 * advance() (driven by Element.update()) and should be applied wherever the
 * gradient's start and end coordinates are turned into a Skia shader, by shifting both
 * x0 and x1, or y0 and y1 for a vertical scroll, by getOffset() before calling
 * GradientUtil.makeLinear(...).
 * <p>
 * Each TextOverlay or Element should own its own ScrollingGradient instance rather than
 * sharing one. The offset is mutable per-instance state, so two elements sharing one
 * instance would scroll in lockstep and write to the same offset value. For a solid
 * color that animates and can be shared, see {@link RainbowColor}.
 * <p>
 * The speed is in points per second along the gradient's own line, which is set by its
 * {@link #getAngle() angle}; a negative speed scrolls the other way.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public class ScrollingGradient extends LinearGradient implements AnimatedPaint {
    private final float speed;         // px/sec along the gradient's own axis
    private float offset = 0f;

    public ScrollingGradient(Color startColor, Color endColor, float speed) {
        super(startColor, endColor);
        this.speed = speed;
    }

    /** As {@link LinearGradient#LinearGradient(Color, Color, float)}, scrolling {@code speed} points a second. */
    public ScrollingGradient(Color startColor, Color endColor, float angle, float speed) {
        super(startColor, endColor, angle);
        this.speed = speed;
    }

    /** As {@link LinearGradient#LinearGradient(List, float)}, scrolling {@code speed} points a second. */
    public ScrollingGradient(List<Color> colors, float angle, float speed) {
        super(colors, angle);
        this.speed = speed;
    }

    @Override
    public void advance(float deltaSeconds) {
        offset += speed * deltaSeconds;
    }

    /** How far it scrolls in a second, in points. */
    public float getSpeed() {
        return speed;
    }

    /**
     * Current scroll offset in pixels, to be added to the gradient's coordinates when building the shader.
     */
    public float getOffset() {
        return offset;
    }
}
