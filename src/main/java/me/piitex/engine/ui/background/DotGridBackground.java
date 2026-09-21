package me.piitex.engine.ui.background;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;

/**
 * A tiled grid of small dots, the "infinite canvas" background used by node editors and
 * whiteboard-style applications such as Obsidian's Canvas view, Figma, Miro, and
 * Excalidraw. See {@link Background} and
 * {@link me.piitex.engine.ui.Element#setBackground} for how to attach one of these to
 * something.
 * <p>
 * This background is fully static. Unlike {@link ConstellationBackground}, nothing here
 * changes frame to frame, so {@link #updateEffect} is left at its no-op default. The
 * grid is recomputed from {@link #getSpacing()} and whatever bounds
 * {@link #renderEffect} is given on each call, which is a nested loop over however many
 * dots fit and is cheap enough not to cache.
 * <p>
 * It extends {@link AbstractBackground} for its own independent enable state,
 * {@link #fadeIn}, and {@link #fadeOut}, so {@code dotGrid.fadeIn(1f)} works directly on
 * this object with no Overlay Element involved.
 */
public class DotGridBackground extends AbstractBackground {
    private float spacing = 24f;
    private float dotRadius = 1.5f;
    private Color dotColor = new Color(0.5f, 0.5f, 0.5f, 0.35f);

    /** Distance, in points, between one dot and the next, in both directions, so the grid is always square-tiled rather than stretched. */
    public float getSpacing() {
        return spacing;
    }

    public void setSpacing(float spacing) {
        this.spacing = Math.max(1f, spacing);
    }

    public float getDotRadius() {
        return dotRadius;
    }

    public void setDotRadius(float dotRadius) {
        this.dotRadius = Math.max(0f, dotRadius);
    }

    public Color getDotColor() {
        return dotColor;
    }

    public void setDotColor(Color dotColor) {
        this.dotColor = dotColor;
    }

    @Override
    protected void renderEffect(Renderer renderer, float x, float y, float width, float height) {
        if (dotColor == null || spacing <= 0f) return;

        renderer.pushClip(x, y, width, height);

        for (float dy = 0f; dy <= height; dy += spacing) {
            for (float dx = 0f; dx <= width; dx += spacing) {
                renderer.drawCircle(x + dx, y + dy, dotRadius, dotColor);
            }
        }

        renderer.popClip();
    }
}
