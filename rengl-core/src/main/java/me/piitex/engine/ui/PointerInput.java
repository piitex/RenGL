package me.piitex.engine.ui;

/**
 * Implemented by an {@link Element} that wants the raw pointer, not just clicks: every move over it with no button held
 * (hover), every press and release of every button, and the wheel with its position and modifier keys. It is what an
 * element showing someone else's interface needs, such as a web page, a remote desktop or an emulator, where the
 * engine's own click, drag and context-menu handling would get in the way.
 * <p>
 * Differences from the usual handling, for an element that implements this:
 * <ul>
 *   <li>A press on it is its own. It does not start a {@link Draggable} parent's drag, open a context menu and is not
 *       passed to the game as input; right and middle buttons reach it like the left one.</li>
 *   <li>Once a button is pressed over it, it keeps the pointer until every button is released, so a drag that leaves
 *       its bounds, or the window, still reaches it. Moves during that time arrive with the buttons held.</li>
 *   <li>Positions are in the element's own coordinates, with the top-left corner at 0, 0, not window coordinates.</li>
 * </ul>
 * An element is found by the same hit test as a click: the topmost thing under the pointer, or the nearest one of its
 * owners that implements this. An open popup keeps the pointer from it.
 * <p>
 * Modifier keys are GLFW bit masks ({@code GLFW_MOD_SHIFT}, {@code GLFW_MOD_CONTROL}, {@code GLFW_MOD_ALT},
 * {@code GLFW_MOD_SUPER}).
 */
public interface PointerInput {

    /**
     * The pointer moved while over this element, or while it holds the pointer. {@code buttons} is a bit mask of the
     * mouse buttons held, with bit {@code n} set for GLFW button {@code n} (left is bit 0, right bit 1, middle bit 2).
     */
    void onPointerMove(double x, double y, int buttons, int mods);

    /**
     * A mouse button went down ({@code pressed} true) or up. A release is reported to the element that took the press
     * even when the pointer is no longer over it.
     */
    void onPointerButton(double x, double y, int button, boolean pressed, int mods);

    /**
     * The wheel or trackpad moved while the pointer was over this element. A positive {@code deltaY} is away from the
     * user, as in {@link me.piitex.engine.ui.scroll.Scrollable#onScroll}.
     */
    void onPointerScroll(double x, double y, double deltaX, double deltaY, int mods);

    /**
     * The pointer left this element with no button held, or the window. A hover state should end here.
     */
    default void onPointerLeave() {
    }
}
