package me.piitex.engine.ui.color;

/**
 * A LinearGradient whose position scrolls over time. The offset accumulates in
 * advance() (driven by Element.update()) and should be applied wherever the
 * gradient's start and end coordinates are turned into a Skia shader, by shifting both
 * x0 and x1, or y0 and y1 for a vertical scroll, by getOffset() before calling
 * GradientUtil.makeLinear(...).
 * <p>
 * Each TextOverlay or Element should own its own ScrollingGradient instance rather than
 * sharing one. The offset is mutable per-instance state, so two elements sharing one
 * instance would scroll in lockstep and write to the same offset value.
 */
public class ScrollingGradient extends LinearGradient implements AnimatedPaint {
    private final float speed;         // px/sec along the gradient's own axis
    private float offset = 0f;

    public ScrollingGradient(Color startColor, Color endColor, float speed) {
        super(startColor, endColor);
        this.speed = speed;
    }

    @Override
    public void advance(float deltaSeconds) {
        offset += speed * deltaSeconds;
    }

    /**
     * Current scroll offset in pixels, to be added to the gradient's coordinates when building the shader.
     */
    public float getOffset() {
        return offset;
    }
}