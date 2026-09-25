package me.piitex.engine.ui;

/**
 * Implemented by an Element that wants continuous mouse-drag tracking, such as a text
 * field extending its selection while the mouse moves with the button still held.
 * <p>
 * {@link me.piitex.engine.input.InputProcessor} calls {@link #onDragStart} once, with
 * the same hit-testing used for an ordinary click, when a fresh left-button press lands
 * on this Element, or on an Element below it that hands the press up (one that is not
 * Draggable, or a Container whose {@link #canDrag} says no, unless it has a click
 * listener: an Element that listens for clicks keeps its press and nothing above it drags); {@link #onDragUpdate} on
 * every subsequent mouse-move while that button stays held, regardless of whether the
 * cursor is still over this Element; and {@link #onDragEnd} once the left button is
 * released. Other mouse buttons neither start nor end a drag. Unlike
 * {@link Element#onMouseClick}, this does not replace or interfere with a click
 * listener; both fire independently off the same press.
 */
public interface Draggable {

    /**
     * Whether a press at (x, y) should start a drag on this Element. Only consulted for a {@link
     * me.piitex.engine.ui.containers.Container}: a container that answers false (a plain layout filling its
     * parent, say) hands the press up to the nearest Draggable ancestor that wants it, instead of swallowing it.
     * Any other Draggable is always the target when the press reaches it, so this default is right for them.
     */
    default boolean canDrag(double x, double y) {
        return true;
    }

    /**
     * The left button was just pressed down on this Element or on a child that handed the press up, at (x, y) in
     * window coordinates.
     */
    void onDragStart(double x, double y);

    /**
     * The mouse moved to (x, y) while the button from {@link #onDragStart} is still held.
     */
    void onDragUpdate(double x, double y);

    /**
     * The button was released at (x, y), ending the drag started by {@link #onDragStart}.
     */
    void onDragEnd(double x, double y);
}
