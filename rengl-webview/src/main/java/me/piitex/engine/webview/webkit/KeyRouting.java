package me.piitex.engine.webview.webkit;

import me.piitex.engine.webview.internal.Keys;
import org.lwjgl.glfw.GLFW;

/**
 * Decides how a key the engine reports reaches a WebKit page. The engine delivers a keypress ({@code key}) and the
 * character it typed ({@code text}) separately, and the system's text input would insert the character twice if both
 * were sent as key events. So:
 * <ul>
 *   <li>A key that types a character (a letter, a digit, punctuation, space) is <b>ignored</b> here; its character
 *       arrives through {@code text} and is sent as a key press with the character on it, which gives the page one
 *       {@code keydown} with the right {@code key} and one inserted character, whatever the keyboard layout.</li>
 *   <li>A key that types nothing (Enter, arrows, Backspace, F-keys) is sent as a key event.</li>
 *   <li>Ctrl or Cmd with a letter that is an editing shortcut (copy, paste, select all, undo) runs that command on the
 *       page directly, since neither toolkit turns a bare key event into one.</li>
 * </ul>
 */
public final class KeyRouting {
    private KeyRouting() {
    }

    public enum Route {
        /** Send nothing. */
        IGNORE,
        /** Run {@link Keys#command} on the page. */
        EDIT_COMMAND,
        /** Send a key event. */
        KEY_EVENT
    }

    /** Whether the key types a character by itself, on a US keyboard layout. */
    public static boolean isPrintable(int key) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) return true;
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) return true;
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) return true;
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_APOSTROPHE, GLFW.GLFW_KEY_COMMA, GLFW.GLFW_KEY_MINUS,
                 GLFW.GLFW_KEY_PERIOD, GLFW.GLFW_KEY_SLASH, GLFW.GLFW_KEY_SEMICOLON, GLFW.GLFW_KEY_EQUAL,
                 GLFW.GLFW_KEY_LEFT_BRACKET, GLFW.GLFW_KEY_BACKSLASH, GLFW.GLFW_KEY_RIGHT_BRACKET,
                 GLFW.GLFW_KEY_GRAVE_ACCENT, GLFW.GLFW_KEY_KP_DECIMAL, GLFW.GLFW_KEY_KP_DIVIDE,
                 GLFW.GLFW_KEY_KP_MULTIPLY, GLFW.GLFW_KEY_KP_SUBTRACT, GLFW.GLFW_KEY_KP_ADD, GLFW.GLFW_KEY_KP_EQUAL -> true;
            default -> false;
        };
    }

    /** Whether the key only changes a modifier. */
    public static boolean isModifier(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT, GLFW.GLFW_KEY_LEFT_CONTROL,
                 GLFW.GLFW_KEY_RIGHT_CONTROL, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT,
                 GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER, GLFW.GLFW_KEY_CAPS_LOCK,
                 GLFW.GLFW_KEY_NUM_LOCK, GLFW.GLFW_KEY_SCROLL_LOCK -> true;
            default -> false;
        };
    }

    /**
     * @param action GLFW's: 0 release, 1 press, 2 repeat
     */
    public static Route route(int key, int action, int mods) {
        if (isModifier(key) || Keys.virtualKey(key) == 0) return Route.IGNORE;
        boolean chord = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_ALT)) != 0;
        if (isPrintable(key) && !chord) return Route.IGNORE;
        if (Keys.command(key, mods) != null) return action == GLFW.GLFW_RELEASE ? Route.IGNORE : Route.EDIT_COMMAND;
        return Route.KEY_EVENT;
    }
}
