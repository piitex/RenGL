package me.piitex.engine.ui.animation;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;

/**
 * A self-contained, timed effect that can be attached to any {@link Element} via
 * {@link Element#playTransition(Transition)}. The engine drives it from then on,
 * calling {@link #update} once per frame to apply whatever it does to {@code target},
 * such as adjusting its {@link Element#getOpacity() opacity} or moving it. It then calls
 * {@link #render} once {@code target}, and for a {@link Container} everything it drew,
 * is fully drawn, which serves a transition that needs to draw something of its own on
 * top rather than only changing a property. {@link FadeTransition} does not use
 * {@link #render}, since {@link Element#renderWithTransitions} already applies opacity
 * for it. {@link #isFinished()} tells the Element when to stop calling the transition
 * and drop it; it receives exactly one more {@link #render} call, at its final state, in
 * the frame it reports finished.
 * <p>
 * This is the seam other animation types, such as move or scale, plug into. Implement
 * this interface rather than an {@link Element#onUpdate} hook: an Element holds only one
 * of those at a time, so registering a second replaces the first, while playing a
 * Transition does not compete with it.
 */
public interface Transition {

    /**
     * Advances this transition by {@code delta} seconds, applying whatever effect it has on {@code target} (its opacity, a position, ...).
     */
    void update(Element target, float delta);

    /**
     * Draws this transition's own visual effect behind {@code target}'s content, before
     * any of it, including its opacity layer, is drawn. An example is the solid color a
     * color-crossfading {@link FadeTransition} dissolves from and to. This is a no-op by
     * default, since most transitions do not need it.
     */
    default void renderBackground(Renderer renderer, Element target) {
    }

    /**
     * Draws this transition's own visual effect for the current frame, if it has one.
     * Most transitions, including a fade, a move, or a scale, draw nothing here, since
     * {@link #update} has already applied their effect to {@code target} directly.
     */
    void render(Renderer renderer, Element target);

    /**
     * Stops this transition where it is, without firing any completion callback, leaving its target as it
     * currently is. The Element drops it on its next update. Returns true if it was running and is now stopped,
     * false if it could not be stopped (already finished or stopped, or this type does not support stopping,
     * which is the default).
     */
    default boolean stop() {
        return false;
    }

    /**
     * Whether this transition has run its course and should be removed from its Element after this frame's {@link #render}.
     */
    boolean isFinished();
}
