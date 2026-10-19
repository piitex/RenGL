package me.piitex.engine.ui;

/**
 * Implemented by the Container handed to {@link me.piitex.engine.Window#showPopup} when
 * the popup wants keyboard input (arrow-key navigation in a menu). {@link
 * me.piitex.engine.input.InputProcessor} routes every key press/repeat to the open popup
 * first if it implements this, including Escape and Tab, so the popup decides what they
 * mean, and stops there. A popup that does not implement it keeps the default behavior:
 * Escape closes it and other keys reach the focused Element.
 */
public interface PopupKeyListener {

    /**
     * A GLFW key code pressed or repeated while this popup is open; {@code mods} is the GLFW modifier bitmask.
     */
    void onPopupKey(int key, int mods);
}
