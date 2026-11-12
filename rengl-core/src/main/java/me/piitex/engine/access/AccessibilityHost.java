package me.piitex.engine.access;

import me.piitex.engine.Window;
import me.piitex.engine.ui.Element;

/**
 * What listens for a window's accessibility events and turns them into something a person can use: speech, a
 * screen reader's tree, a braille line. The engine only reports what happened; the code behind this interface, which
 * lives in the {@code rengl-accessibility} module, decides what to do about it.
 * <p>
 * Install one with {@link Window#setAccessibilityHost}. Every method is called on the thread that moved the focus or made
 * the announcement, usually the main thread, so an implementation hands slow work to a thread of its own.
 */
public interface AccessibilityHost {
    /**
     * Keyboard focus moved. Either side may be null: focus came from nothing, or left everything.
     *
     * @param byKeyboard true when Tab or a key moved it; false when a click, or code, did
     */
    void focusChanged(Window window, Element previous, Element now, boolean byKeyboard);

    /**
     * Something happened that is worth saying even though focus did not move: a toast, a score, "Game saved".
     *
     * @param urgent true to cut off whatever is being said; false to say it after
     */
    void announce(Window window, String text, boolean urgent);

    /**
     * The focused Element's value or state changed under the user's hands: a slider moved with the arrows, a check box
     * toggled with Space. Say the new one. Fired by {@link Element#notifyAccessibleChange()}.
     */
    default void valueChanged(Window window, Element element) {
    }

    /**
     * The host was replaced or the window closed. Free anything held.
     */
    default void close() {
    }
}
