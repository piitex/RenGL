package me.piitex.engine.ui;

/**
 * Implemented by an Element that wants continuous mouse-drag tracking, such as a text
 * field extending its selection while the mouse moves with the button still held.
 * <p>
 * {@link me.piitex.engine.input.InputProcessor} calls {@link #onDragStart} once, with
 * the same hit-testing used for an ordinary click, when this Element is the topmost
 * hit under a fresh mouse press; {@link #onDragUpdate} on every subsequent mouse-move
 * while that button stays held, regardless of whether the cursor is still over this
 * Element; and {@link #onDragEnd} once the button is released. Unlike
 * {@link Element#onMouseClick}, this does not replace or interfere with a click
 * listener; both fire independently off the same press.
 */
public interface Draggable {

    /**
     * Whether a press at (x, y) should start a drag on this Element. Only consulted for a {@link
     * me.piitex.engine.ui.containers.Container}: a container that answers false (a plain layout filling its
     * parent, say) lets the press fall through to the nearest ancestor that answers true, instead of swallowing
     * it. Any other Draggable is always the target when it is hit, so this default is right for them.
     */
    default boolean canDrag(double x, double y) {
        return true;
    }

    /** The button was just pressed down on this Element, at (x, y) in window coordinates. */
    void onDragStart(double x, double y);

    /** The mouse moved to (x, y) while the button from {@link #onDragStart} is still held. */
    void onDragUpdate(double x, double y);

    /** The button was released at (x, y), ending the drag started by {@link #onDragStart}. */
    void onDragEnd(double x, double y);
}
