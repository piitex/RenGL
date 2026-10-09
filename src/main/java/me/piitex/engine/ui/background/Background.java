package me.piitex.engine.ui.background;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.Element;

/**
 * A drawable, optionally animated effect painted behind an Element's own content,
 * attached through {@link Element#setBackground}. Unlike a flat
 * {@link me.piitex.engine.ui.color.Paint}, which is a single solid or gradient fill that
 * {@link Element#render} draws itself, a Background owns its whole draw routine and,
 * through {@link #update}, its own per-frame state. {@link ConstellationBackground}'s
 * particle simulation, {@link ElectricNodeBackground}'s bouncing lightning orbs, and
 * {@link DotGridBackground}'s tiled dot grid are examples.
 * <p>
 * Bounds are passed in on every call rather than owned by the Background, so the same
 * instance works no matter which Element it is attached to. That is also why a
 * Background is attached rather than added as a child Element: a
 * {@link me.piitex.engine.ui.layout.Layout} positions every child it has, and a
 * full-size decorative effect added as a real child would be caught up in that
 * positioning like any other. {@link Element#setBackground} is not a child at all, so a
 * Layout never touches it, and
 * {@code formLayout.setBackground(new ConstellationBackground())} paints the effect
 * behind formLayout's real children without disturbing their layout.
 * <p>
 * Extend {@link AbstractBackground} rather than implementing this directly when the
 * background should support its own independent enable, fade-in, and fade-out. See that
 * class's docs.
 */
public interface Background {
    /**
     * Draws this background within (x, y, width, height), the owning Element's own bounds, in the coordinate space it is currently being drawn in. That space is the canvas of the container it sits in, so x and y are its position relative to that container.
     */
    void render(Renderer renderer, float x, float y, float width, float height);

    /**
     * Advances this background's per-frame state, if it has any. The default is a no-op, which suits a purely static background such as {@link DotGridBackground}. Unlike in {@link #render}, x and y here are the owning Element's position <b>in the window</b>, because mouse-relative and obstacle-relative effects compare against window-space positions and nothing is being drawn at update time, so there is no canvas space.
     */
    default void update(float delta, float x, float y, float width, float height) {
    }

    /**
     * Called when this background is taken away: replaced by another with {@link Element#setBackground} (or the window's),
     * or removed with null. For a background that holds something it should let go of, such as a 3D scene's GPU
     * objects. It may be attached again later, so it should be able to start afresh. The default does nothing.
     */
    default void onDetached() {
    }
}
