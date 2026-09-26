package me.piitex.engine.ui;

import me.piitex.engine.Window;

/**
 * Implemented by an {@link Element} that wants keyboard input routed to it, which in
 * practice means text fields. {@link me.piitex.engine.input.InputProcessor} grants focus to
 * whichever Focusable Element was topmost under a mouse click (clicking anything else,
 * including empty space, clears focus), and forwards every {@code KEY}/{@code CHAR}
 * event to {@link Window#getFocusedElement()} for as long as it holds focus.
 */
public interface Focusable {

    /**
     * Called once when this Element becomes {@link Window#getFocusedElement()}.
     */
    void onFocusGained();

    /**
     * Called once when focus moves away from this Element (including on click-away).
     */
    void onFocusLost();

    /**
     * A Unicode codepoint typed while this Element is focused, already resolved
     * against the OS keyboard layout/shift state/dead keys by GLFW's char callback.
     * This is the source for text content. See {@link #onKeyPressed} for non-printable
     * control keys.
     */
    void onCharTyped(int codepoint);

    /**
     * A raw GLFW key code pressed, repeated or released while this Element is focused;
     * {@code action} says which, and most controls ignore {@code GLFW_RELEASE}. See
     * {@link me.piitex.engine.input.InputProcessor}. This is intended for control keys
     * such as backspace, delete, the arrows, and enter, which do not arrive through
     * {@link #onCharTyped}, and for modifier-driven shortcuts such as Ctrl/Cmd+A through
     * {@code mods}, a GLFW modifier bitmask of GLFW_MOD_SHIFT, CONTROL, ALT, and SUPER.
     */
    void onKeyPressed(int key, int action, int mods);
}
