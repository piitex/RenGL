package me.piitex.engine.ui.text;

/**
 * Where a line of text sits horizontally: against the left edge, centered, or against the right edge. Used by
 * {@link me.piitex.engine.gl.renderer.Renderer#drawText(String, float, float, org.jetbrains.skia.Font,
 * me.piitex.engine.ui.color.Paint, TextAlign) Renderer.drawText}, where the x given is that edge or the center, and by
 * {@link me.piitex.engine.ui.overlays.TextFlowOverlay} for its wrapped lines.
 */
public enum TextAlign {
    LEFT(0f), CENTER(0.5f), RIGHT(1f);

    private final float fraction;

    TextAlign(float fraction) {
        this.fraction = fraction;
    }

    /**
     * How far along a line's width its anchor is: 0 for {@link #LEFT}, 0.5 for {@link #CENTER}, 1 for {@link #RIGHT}.
     * A line {@code width} wide anchored at x starts at {@code x - fraction() * width}.
     */
    public float fraction() {
        return fraction;
    }
}
