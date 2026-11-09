package me.piitex.engine.webview.linux;

import org.lwjgl.glfw.GLFW;

/**
 * The engine's keys and mouse as GDK knows them: X11 key symbols, modifier masks, button numbers and event types. Pure
 * tables, so they are tested without GTK.
 */
final class GdkKeys {
    private GdkKeys() {
    }

    // GdkEventType
    static final int MOTION_NOTIFY = 3, BUTTON_PRESS = 4, BUTTON_RELEASE = 7, KEY_PRESS = 8, KEY_RELEASE = 9,
            LEAVE_NOTIFY = 11, FOCUS_CHANGE = 12, SCROLL = 31;

    /** GdkScrollDirection: smooth scrolling carries its distance in the event's deltas. */
    static final int SCROLL_SMOOTH = 4;

    // GdkModifierType
    static final int SHIFT_MASK = 1, LOCK_MASK = 1 << 1, CONTROL_MASK = 1 << 2, MOD1_MASK = 1 << 3, BUTTON1_MASK = 1 << 8,
            BUTTON2_MASK = 1 << 9, BUTTON3_MASK = 1 << 10, SUPER_MASK = 1 << 26;

    /** GLFW modifier bits as GDK's modifier mask. */
    static int state(int glfwMods) {
        int s = 0;
        if ((glfwMods & GLFW.GLFW_MOD_SHIFT) != 0) s |= SHIFT_MASK;
        if ((glfwMods & GLFW.GLFW_MOD_CONTROL) != 0) s |= CONTROL_MASK;
        if ((glfwMods & GLFW.GLFW_MOD_ALT) != 0) s |= MOD1_MASK;
        if ((glfwMods & GLFW.GLFW_MOD_SUPER) != 0) s |= SUPER_MASK;
        if ((glfwMods & GLFW.GLFW_MOD_CAPS_LOCK) != 0) s |= LOCK_MASK;
        return s;
    }

    /** GDK's button number: 1 left, 2 middle, 3 right. GLFW's are 0 left, 1 right, 2 middle. */
    static int button(int glfwButton) {
        return switch (glfwButton) {
            case 0 -> 1;
            case 1 -> 3;
            case 2 -> 2;
            default -> glfwButton + 1;
        };
    }

    /** The mask bit of a GDK button number (buttons 1 to 3; the rest have none that matters). */
    static int buttonMask(int gdkButton) {
        return switch (gdkButton) {
            case 1 -> BUTTON1_MASK;
            case 2 -> BUTTON2_MASK;
            case 3 -> BUTTON3_MASK;
            default -> 0;
        };
    }

    /** The mask bits for the buttons held, from the engine's bit set (bit 0 left, 1 right, 2 middle). */
    static int heldMask(int buttons) {
        int s = 0;
        if ((buttons & 1) != 0) s |= BUTTON1_MASK;
        if ((buttons & 2) != 0) s |= BUTTON3_MASK;
        if ((buttons & 4) != 0) s |= BUTTON2_MASK;
        return s;
    }

    /** The X key symbol of a GLFW key that types no character of its own, or a letter or digit's symbol; 0 for a key with none. */
    static int keyval(int key) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) return 'a' + (key - GLFW.GLFW_KEY_A);
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) return key;
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F12) return 0xFFBE + (key - GLFW.GLFW_KEY_F1);
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) return 0xFFB0 + (key - GLFW.GLFW_KEY_KP_0);
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> 0x20;
            case GLFW.GLFW_KEY_APOSTROPHE -> '\'';
            case GLFW.GLFW_KEY_COMMA -> ',';
            case GLFW.GLFW_KEY_MINUS -> '-';
            case GLFW.GLFW_KEY_PERIOD -> '.';
            case GLFW.GLFW_KEY_SLASH -> '/';
            case GLFW.GLFW_KEY_SEMICOLON -> ';';
            case GLFW.GLFW_KEY_EQUAL -> '=';
            case GLFW.GLFW_KEY_LEFT_BRACKET -> '[';
            case GLFW.GLFW_KEY_BACKSLASH -> '\\';
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> ']';
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> '`';
            case GLFW.GLFW_KEY_ESCAPE -> 0xFF1B;
            case GLFW.GLFW_KEY_ENTER -> 0xFF0D;
            case GLFW.GLFW_KEY_KP_ENTER -> 0xFF8D;
            case GLFW.GLFW_KEY_TAB -> 0xFF09;
            case GLFW.GLFW_KEY_BACKSPACE -> 0xFF08;
            case GLFW.GLFW_KEY_INSERT -> 0xFF63;
            case GLFW.GLFW_KEY_DELETE -> 0xFFFF;
            case GLFW.GLFW_KEY_RIGHT -> 0xFF53;
            case GLFW.GLFW_KEY_LEFT -> 0xFF51;
            case GLFW.GLFW_KEY_DOWN -> 0xFF54;
            case GLFW.GLFW_KEY_UP -> 0xFF52;
            case GLFW.GLFW_KEY_PAGE_UP -> 0xFF55;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 0xFF56;
            case GLFW.GLFW_KEY_HOME -> 0xFF50;
            case GLFW.GLFW_KEY_END -> 0xFF57;
            case GLFW.GLFW_KEY_CAPS_LOCK -> 0xFFE5;
            case GLFW.GLFW_KEY_KP_DECIMAL -> 0xFFAE;
            case GLFW.GLFW_KEY_KP_DIVIDE -> 0xFFAF;
            case GLFW.GLFW_KEY_KP_MULTIPLY -> 0xFFAA;
            case GLFW.GLFW_KEY_KP_SUBTRACT -> 0xFFAD;
            case GLFW.GLFW_KEY_KP_ADD -> 0xFFAB;
            default -> 0;
        };
    }

    /** The editing-command name WebKitGTK's {@code execute_editing_command} takes for the names {@code Keys.command} returns. */
    static String editingCommand(String command) {
        return switch (command) {
            case "selectAll" -> "SelectAll";
            case "copy" -> "Copy";
            case "cut" -> "Cut";
            case "paste" -> "Paste";
            case "pasteAndMatchStyle" -> "PasteAsPlainText";
            case "undo" -> "Undo";
            case "redo" -> "Redo";
            default -> null;
        };
    }
}
