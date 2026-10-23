package me.piitex.engine.ui.scroll;

import me.piitex.engine.ui.Draggable;

/**
 * Implemented by an Element that wants mouse-wheel and trackpad input routed to it,
 * which in practice means
 * {@link me.piitex.engine.ui.scroll.ScrollContainer}.
 * {@link me.piitex.engine.input.InputProcessor} calls {@link #onScroll} on whichever
 * Scrollable is found under the cursor's current position when a wheel event arrives,
 * preferring the innermost one, so a scrollable list nested inside another scrollable
 * area scrolls whichever one the cursor is over. Unlike {@link Draggable}, this fires no
 * matter what is under the cursor at that point, including one of this Element's own
 * children, rather than only over its empty background.
 */
public interface Scrollable {

    /**
     * Raw GLFW scroll offsets for this event. A positive {@code deltaY} is a wheel notch
     * or trackpad swipe up, away from the user, and a negative one is down.
     * {@code deltaX} is usually 0 except for a horizontal trackpad swipe or a
     * shift-scroll gesture. How these translate into direction and speed is up to the
     * implementation.
     */
    void onScroll(double deltaX, double deltaY);

    /**
     * Whether {@link #onScroll} with these offsets would actually move anything. When this
     * returns false, {@link me.piitex.engine.input.InputProcessor} hands the event to the
     * next Scrollable outward instead, so a nested one with nothing to scroll (or already at
     * its limit in that direction) doesn't swallow the wheel. Defaults to true.
     */
    default boolean canScroll(double deltaX, double deltaY) {
        return true;
    }
}
