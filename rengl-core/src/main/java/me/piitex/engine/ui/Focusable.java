package me.piitex.engine.ui;

import me.piitex.engine.utils.flags.GeneratedFragments;
import me.piitex.engine.Window;

/**
 * Implemented by an {@link Element} that wants keyboard input routed to it, which in
 * practice means text fields. {@link me.piitex.engine.input.InputProcessor} grants focus to
 * whichever Focusable Element was topmost under a mouse click (clicking anything else,
 * including empty space, clears focus), and forwards every {@code KEY}/{@code CHAR}
 * event to {@link Window#getFocusedElement()} for as long as it holds focus.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
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

    /**
     * Whether this Element takes typed text, so the operating system's input method (IME) should be on while it has
     * focus: what lets a keyboard type Chinese, Japanese, Korean and other languages with more characters than keys.
     * False by default, and for a field that cannot be edited or hides what is typed.
     */
    default boolean acceptsTextInput() {
        return false;
    }

    /**
     * The input method's composition changed: text the user is still composing, such as kana not yet converted to kanji.
     * It is shown at the caret but is not part of the text; when the user commits it, it arrives through
     * {@link #onCharTyped} and the composition is cleared. Called only if {@link #acceptsTextInput()} is true.
     */
    default void onPreedit(me.piitex.engine.input.ImePreedit preedit) {
    }

    /**
     * Where the caret is, as {x, y, width, height} in window coordinates, for the input method to open its candidate list
     * beside. Null when unknown, which leaves the list wherever the system puts it.
     */
    default float[] imeCaretRect() {
        return null;
    }

    /**
     * Whether the Tab key is this Element's to use, as a code editor uses it to indent, instead of moving focus to the
     * next Element. False by default, which is what every other Focusable wants.
     */
    default boolean wantsTab() {
        return false;
    }

    /**
     * Whether this Element, once it has focus, takes the keyboard away from the game, which is what a text field does
     * and the default. A push button returns false: a click does not focus it, so it never keeps WASD from the game,
     * and when Tab does focus it only the keys {@link #handlesKey} names reach it. Typed characters go to the game.
     */
    default boolean grabsKeyboard() {
        return true;
    }

    /**
     * For an Element that does not {@link #grabsKeyboard() grab the keyboard}: whether {@code key} is one it uses
     * while focused. Any other key falls through to the game or the shortcuts, as if nothing had focus.
     */
    default boolean handlesKey(int key, int mods) {
        return true;
    }
}
