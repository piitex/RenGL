package me.piitex.engine.webview.internal;

import org.lwjgl.glfw.GLFW;

/**
 * Translates the engine's GLFW key codes to what a web page expects: Windows virtual-key codes and the {@code key} and
 * {@code code} names of a DOM keyboard event.
 */
public final class Keys {
    private Keys() {
    }

    /** The Windows virtual-key code for a GLFW key, or 0 for a key the page has no use for. */
    public static int virtualKey(int key) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) return key; // 'A'..'Z' are the same numbers
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) return key;
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F12) return 0x70 + (key - GLFW.GLFW_KEY_F1);
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) return 0x60 + (key - GLFW.GLFW_KEY_KP_0);
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> 0x20;
            case GLFW.GLFW_KEY_APOSTROPHE -> 0xDE;
            case GLFW.GLFW_KEY_COMMA -> 0xBC;
            case GLFW.GLFW_KEY_MINUS -> 0xBD;
            case GLFW.GLFW_KEY_PERIOD -> 0xBE;
            case GLFW.GLFW_KEY_SLASH -> 0xBF;
            case GLFW.GLFW_KEY_SEMICOLON -> 0xBA;
            case GLFW.GLFW_KEY_EQUAL -> 0xBB;
            case GLFW.GLFW_KEY_LEFT_BRACKET -> 0xDB;
            case GLFW.GLFW_KEY_BACKSLASH -> 0xDC;
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> 0xDD;
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> 0xC0;
            case GLFW.GLFW_KEY_ESCAPE -> 0x1B;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> 0x0D;
            case GLFW.GLFW_KEY_TAB -> 0x09;
            case GLFW.GLFW_KEY_BACKSPACE -> 0x08;
            case GLFW.GLFW_KEY_INSERT -> 0x2D;
            case GLFW.GLFW_KEY_DELETE -> 0x2E;
            case GLFW.GLFW_KEY_RIGHT -> 0x27;
            case GLFW.GLFW_KEY_LEFT -> 0x25;
            case GLFW.GLFW_KEY_DOWN -> 0x28;
            case GLFW.GLFW_KEY_UP -> 0x26;
            case GLFW.GLFW_KEY_PAGE_UP -> 0x21;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 0x22;
            case GLFW.GLFW_KEY_HOME -> 0x24;
            case GLFW.GLFW_KEY_END -> 0x23;
            case GLFW.GLFW_KEY_CAPS_LOCK -> 0x14;
            case GLFW.GLFW_KEY_KP_DECIMAL -> 0x6E;
            case GLFW.GLFW_KEY_KP_DIVIDE -> 0x6F;
            case GLFW.GLFW_KEY_KP_MULTIPLY -> 0x6A;
            case GLFW.GLFW_KEY_KP_SUBTRACT -> 0x6D;
            case GLFW.GLFW_KEY_KP_ADD -> 0x6B;
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> 0x10;
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> 0x11;
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> 0x12;
            case GLFW.GLFW_KEY_LEFT_SUPER -> 0x5B;
            case GLFW.GLFW_KEY_RIGHT_SUPER -> 0x5C;
            default -> 0;
        };
    }

    /** The DOM {@code KeyboardEvent.key} for a key press with no text of its own. */
    public static String keyName(int key) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) return String.valueOf((char) ('a' + (key - GLFW.GLFW_KEY_A)));
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) return String.valueOf((char) key);
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F12) return "F" + (1 + key - GLFW.GLFW_KEY_F1);
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) return String.valueOf((char) ('0' + key - GLFW.GLFW_KEY_KP_0));
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> " ";
            case GLFW.GLFW_KEY_APOSTROPHE -> "'";
            case GLFW.GLFW_KEY_COMMA -> ",";
            case GLFW.GLFW_KEY_MINUS -> "-";
            case GLFW.GLFW_KEY_PERIOD -> ".";
            case GLFW.GLFW_KEY_SLASH -> "/";
            case GLFW.GLFW_KEY_SEMICOLON -> ";";
            case GLFW.GLFW_KEY_EQUAL -> "=";
            case GLFW.GLFW_KEY_LEFT_BRACKET -> "[";
            case GLFW.GLFW_KEY_BACKSLASH -> "\\";
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> "]";
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> "`";
            case GLFW.GLFW_KEY_ESCAPE -> "Escape";
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> "Enter";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW.GLFW_KEY_INSERT -> "Insert";
            case GLFW.GLFW_KEY_DELETE -> "Delete";
            case GLFW.GLFW_KEY_RIGHT -> "ArrowRight";
            case GLFW.GLFW_KEY_LEFT -> "ArrowLeft";
            case GLFW.GLFW_KEY_DOWN -> "ArrowDown";
            case GLFW.GLFW_KEY_UP -> "ArrowUp";
            case GLFW.GLFW_KEY_PAGE_UP -> "PageUp";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PageDown";
            case GLFW.GLFW_KEY_HOME -> "Home";
            case GLFW.GLFW_KEY_END -> "End";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "CapsLock";
            case GLFW.GLFW_KEY_KP_DECIMAL -> ".";
            case GLFW.GLFW_KEY_KP_DIVIDE -> "/";
            case GLFW.GLFW_KEY_KP_MULTIPLY -> "*";
            case GLFW.GLFW_KEY_KP_SUBTRACT -> "-";
            case GLFW.GLFW_KEY_KP_ADD -> "+";
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> "Shift";
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> "Control";
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> "Alt";
            case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> "Meta";
            default -> "Unidentified";
        };
    }

    /** The DOM {@code KeyboardEvent.code}, the physical key. */
    public static String codeName(int key) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) return "Key" + (char) key;
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) return "Digit" + (char) key;
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F12) return "F" + (1 + key - GLFW.GLFW_KEY_F1);
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) return "Numpad" + (key - GLFW.GLFW_KEY_KP_0);
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_APOSTROPHE -> "Quote";
            case GLFW.GLFW_KEY_COMMA -> "Comma";
            case GLFW.GLFW_KEY_MINUS -> "Minus";
            case GLFW.GLFW_KEY_PERIOD -> "Period";
            case GLFW.GLFW_KEY_SLASH -> "Slash";
            case GLFW.GLFW_KEY_SEMICOLON -> "Semicolon";
            case GLFW.GLFW_KEY_EQUAL -> "Equal";
            case GLFW.GLFW_KEY_LEFT_BRACKET -> "BracketLeft";
            case GLFW.GLFW_KEY_BACKSLASH -> "Backslash";
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> "BracketRight";
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> "Backquote";
            case GLFW.GLFW_KEY_ESCAPE -> "Escape";
            case GLFW.GLFW_KEY_ENTER -> "Enter";
            case GLFW.GLFW_KEY_KP_ENTER -> "NumpadEnter";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW.GLFW_KEY_INSERT -> "Insert";
            case GLFW.GLFW_KEY_DELETE -> "Delete";
            case GLFW.GLFW_KEY_RIGHT -> "ArrowRight";
            case GLFW.GLFW_KEY_LEFT -> "ArrowLeft";
            case GLFW.GLFW_KEY_DOWN -> "ArrowDown";
            case GLFW.GLFW_KEY_UP -> "ArrowUp";
            case GLFW.GLFW_KEY_PAGE_UP -> "PageUp";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PageDown";
            case GLFW.GLFW_KEY_HOME -> "Home";
            case GLFW.GLFW_KEY_END -> "End";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "CapsLock";
            case GLFW.GLFW_KEY_KP_DECIMAL -> "NumpadDecimal";
            case GLFW.GLFW_KEY_KP_DIVIDE -> "NumpadDivide";
            case GLFW.GLFW_KEY_KP_MULTIPLY -> "NumpadMultiply";
            case GLFW.GLFW_KEY_KP_SUBTRACT -> "NumpadSubtract";
            case GLFW.GLFW_KEY_KP_ADD -> "NumpadAdd";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "ShiftLeft";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "ShiftRight";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "ControlLeft";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "ControlRight";
            case GLFW.GLFW_KEY_LEFT_ALT -> "AltLeft";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "AltRight";
            case GLFW.GLFW_KEY_LEFT_SUPER -> "MetaLeft";
            case GLFW.GLFW_KEY_RIGHT_SUPER -> "MetaRight";
            default -> "";
        };
    }

    /** GLFW modifier bits to the bit mask the DevTools input calls take (Alt 1, Ctrl 2, Meta 4, Shift 8). */
    public static int modifiers(int glfwMods) {
        int m = 0;
        if ((glfwMods & GLFW.GLFW_MOD_ALT) != 0) m |= 1;
        if ((glfwMods & GLFW.GLFW_MOD_CONTROL) != 0) m |= 2;
        if ((glfwMods & GLFW.GLFW_MOD_SUPER) != 0) m |= 4;
        if ((glfwMods & GLFW.GLFW_MOD_SHIFT) != 0) m |= 8;
        return m;
    }

    /**
     * The editing command a Ctrl (or Cmd) chord stands for, or null. The page runs it on its own selection, which is
     * how copy, paste and undo work in a view the system gave no real keyboard focus.
     */
    public static String command(int key, int glfwMods) {
        if ((glfwMods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) == 0 || (glfwMods & GLFW.GLFW_MOD_ALT) != 0) return null;
        boolean shift = (glfwMods & GLFW.GLFW_MOD_SHIFT) != 0;
        return switch (key) {
            case GLFW.GLFW_KEY_A -> shift ? null : "selectAll";
            case GLFW.GLFW_KEY_C -> shift ? null : "copy";
            case GLFW.GLFW_KEY_X -> shift ? null : "cut";
            case GLFW.GLFW_KEY_V -> shift ? "pasteAndMatchStyle" : "paste";
            case GLFW.GLFW_KEY_Z -> shift ? "redo" : "undo";
            case GLFW.GLFW_KEY_Y -> shift ? null : "redo";
            default -> null;
        };
    }
}
