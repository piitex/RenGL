package me.piitex.engine.webview.macos;

import org.lwjgl.glfw.GLFW;

/**
 * The engine's keys and mouse as AppKit knows them: virtual key codes ({@code kVK_*}, for an ANSI keyboard), the private
 * Unicode characters AppKit gives function keys, modifier flags and the event types. Pure tables, so they are tested
 * without a Mac.
 */
final class MacKeys {
    private MacKeys() {
    }

    // NSEventType
    static final int LEFT_MOUSE_DOWN = 1, LEFT_MOUSE_UP = 2, RIGHT_MOUSE_DOWN = 3, RIGHT_MOUSE_UP = 4, MOUSE_MOVED = 5,
            LEFT_MOUSE_DRAGGED = 6, RIGHT_MOUSE_DRAGGED = 7, MOUSE_ENTERED = 8, MOUSE_EXITED = 9, KEY_DOWN = 10, KEY_UP = 11,
            OTHER_MOUSE_DOWN = 25, OTHER_MOUSE_UP = 26, OTHER_MOUSE_DRAGGED = 27;

    // NSEventModifierFlags
    static final long FLAG_SHIFT = 1L << 17, FLAG_CONTROL = 1L << 18, FLAG_OPTION = 1L << 19, FLAG_COMMAND = 1L << 20,
            FLAG_NUMERIC_PAD = 1L << 21, FLAG_FUNCTION = 1L << 23;

    /** GLFW modifier bits as AppKit's modifier flags. */
    static long modifierFlags(int glfwMods) {
        long f = 0;
        if ((glfwMods & GLFW.GLFW_MOD_SHIFT) != 0) f |= FLAG_SHIFT;
        if ((glfwMods & GLFW.GLFW_MOD_CONTROL) != 0) f |= FLAG_CONTROL;
        if ((glfwMods & GLFW.GLFW_MOD_ALT) != 0) f |= FLAG_OPTION;
        if ((glfwMods & GLFW.GLFW_MOD_SUPER) != 0) f |= FLAG_COMMAND;
        return f;
    }

    /** The event type of a mouse button going down or up. {@code glfwButton}: 0 left, 1 right, anything else other. */
    static int mouseButtonType(int glfwButton, boolean pressed) {
        return switch (glfwButton) {
            case 0 -> pressed ? LEFT_MOUSE_DOWN : LEFT_MOUSE_UP;
            case 1 -> pressed ? RIGHT_MOUSE_DOWN : RIGHT_MOUSE_UP;
            default -> pressed ? OTHER_MOUSE_DOWN : OTHER_MOUSE_UP;
        };
    }

    /** The event type of a pointer move with these buttons held (bit 0 left, 1 right, 2 other). */
    static int mouseMoveType(int buttons) {
        if ((buttons & 1) != 0) return LEFT_MOUSE_DRAGGED;
        if ((buttons & 2) != 0) return RIGHT_MOUSE_DRAGGED;
        if ((buttons & 4) != 0) return OTHER_MOUSE_DRAGGED;
        return MOUSE_MOVED;
    }

    /** The {@code NSResponder} method an event of this type is delivered with. */
    static String responderSelector(int type) {
        return switch (type) {
            case LEFT_MOUSE_DOWN -> "mouseDown:";
            case LEFT_MOUSE_UP -> "mouseUp:";
            case RIGHT_MOUSE_DOWN -> "rightMouseDown:";
            case RIGHT_MOUSE_UP -> "rightMouseUp:";
            case OTHER_MOUSE_DOWN -> "otherMouseDown:";
            case OTHER_MOUSE_UP -> "otherMouseUp:";
            case MOUSE_MOVED -> "mouseMoved:";
            case LEFT_MOUSE_DRAGGED -> "mouseDragged:";
            case RIGHT_MOUSE_DRAGGED -> "rightMouseDragged:";
            case OTHER_MOUSE_DRAGGED -> "otherMouseDragged:";
            case MOUSE_ENTERED -> "mouseEntered:";
            case MOUSE_EXITED -> "mouseExited:";
            case KEY_DOWN -> "keyDown:";
            case KEY_UP -> "keyUp:";
            default -> throw new IllegalArgumentException("No responder method for event type " + type);
        };
    }

    /** The virtual key code ({@code kVK_*}) of a GLFW key on an ANSI keyboard, or -1 for a key with none. */
    static int keyCode(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_A -> 0x00;
            case GLFW.GLFW_KEY_S -> 0x01;
            case GLFW.GLFW_KEY_D -> 0x02;
            case GLFW.GLFW_KEY_F -> 0x03;
            case GLFW.GLFW_KEY_H -> 0x04;
            case GLFW.GLFW_KEY_G -> 0x05;
            case GLFW.GLFW_KEY_Z -> 0x06;
            case GLFW.GLFW_KEY_X -> 0x07;
            case GLFW.GLFW_KEY_C -> 0x08;
            case GLFW.GLFW_KEY_V -> 0x09;
            case GLFW.GLFW_KEY_B -> 0x0B;
            case GLFW.GLFW_KEY_Q -> 0x0C;
            case GLFW.GLFW_KEY_W -> 0x0D;
            case GLFW.GLFW_KEY_E -> 0x0E;
            case GLFW.GLFW_KEY_R -> 0x0F;
            case GLFW.GLFW_KEY_Y -> 0x10;
            case GLFW.GLFW_KEY_T -> 0x11;
            case GLFW.GLFW_KEY_1 -> 0x12;
            case GLFW.GLFW_KEY_2 -> 0x13;
            case GLFW.GLFW_KEY_3 -> 0x14;
            case GLFW.GLFW_KEY_4 -> 0x15;
            case GLFW.GLFW_KEY_6 -> 0x16;
            case GLFW.GLFW_KEY_5 -> 0x17;
            case GLFW.GLFW_KEY_EQUAL -> 0x18;
            case GLFW.GLFW_KEY_9 -> 0x19;
            case GLFW.GLFW_KEY_7 -> 0x1A;
            case GLFW.GLFW_KEY_MINUS -> 0x1B;
            case GLFW.GLFW_KEY_8 -> 0x1C;
            case GLFW.GLFW_KEY_0 -> 0x1D;
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> 0x1E;
            case GLFW.GLFW_KEY_O -> 0x1F;
            case GLFW.GLFW_KEY_U -> 0x20;
            case GLFW.GLFW_KEY_LEFT_BRACKET -> 0x21;
            case GLFW.GLFW_KEY_I -> 0x22;
            case GLFW.GLFW_KEY_P -> 0x23;
            case GLFW.GLFW_KEY_ENTER -> 0x24;
            case GLFW.GLFW_KEY_L -> 0x25;
            case GLFW.GLFW_KEY_J -> 0x26;
            case GLFW.GLFW_KEY_APOSTROPHE -> 0x27;
            case GLFW.GLFW_KEY_K -> 0x28;
            case GLFW.GLFW_KEY_SEMICOLON -> 0x29;
            case GLFW.GLFW_KEY_BACKSLASH -> 0x2A;
            case GLFW.GLFW_KEY_COMMA -> 0x2B;
            case GLFW.GLFW_KEY_SLASH -> 0x2C;
            case GLFW.GLFW_KEY_N -> 0x2D;
            case GLFW.GLFW_KEY_M -> 0x2E;
            case GLFW.GLFW_KEY_PERIOD -> 0x2F;
            case GLFW.GLFW_KEY_TAB -> 0x30;
            case GLFW.GLFW_KEY_SPACE -> 0x31;
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> 0x32;
            case GLFW.GLFW_KEY_BACKSPACE -> 0x33;
            case GLFW.GLFW_KEY_ESCAPE -> 0x35;
            case GLFW.GLFW_KEY_KP_DECIMAL -> 0x41;
            case GLFW.GLFW_KEY_KP_MULTIPLY -> 0x43;
            case GLFW.GLFW_KEY_KP_ADD -> 0x45;
            case GLFW.GLFW_KEY_KP_DIVIDE -> 0x4B;
            case GLFW.GLFW_KEY_KP_ENTER -> 0x4C;
            case GLFW.GLFW_KEY_KP_SUBTRACT -> 0x4E;
            case GLFW.GLFW_KEY_KP_0 -> 0x52;
            case GLFW.GLFW_KEY_KP_1 -> 0x53;
            case GLFW.GLFW_KEY_KP_2 -> 0x54;
            case GLFW.GLFW_KEY_KP_3 -> 0x55;
            case GLFW.GLFW_KEY_KP_4 -> 0x56;
            case GLFW.GLFW_KEY_KP_5 -> 0x57;
            case GLFW.GLFW_KEY_KP_6 -> 0x58;
            case GLFW.GLFW_KEY_KP_7 -> 0x59;
            case GLFW.GLFW_KEY_KP_8 -> 0x5B;
            case GLFW.GLFW_KEY_KP_9 -> 0x5C;
            case GLFW.GLFW_KEY_F5 -> 0x60;
            case GLFW.GLFW_KEY_F6 -> 0x61;
            case GLFW.GLFW_KEY_F7 -> 0x62;
            case GLFW.GLFW_KEY_F3 -> 0x63;
            case GLFW.GLFW_KEY_F8 -> 0x64;
            case GLFW.GLFW_KEY_F9 -> 0x65;
            case GLFW.GLFW_KEY_F11 -> 0x67;
            case GLFW.GLFW_KEY_F10 -> 0x6D;
            case GLFW.GLFW_KEY_F12 -> 0x6F;
            case GLFW.GLFW_KEY_INSERT -> 0x72; // the Help key
            case GLFW.GLFW_KEY_HOME -> 0x73;
            case GLFW.GLFW_KEY_PAGE_UP -> 0x74;
            case GLFW.GLFW_KEY_DELETE -> 0x75;
            case GLFW.GLFW_KEY_F4 -> 0x76;
            case GLFW.GLFW_KEY_END -> 0x77;
            case GLFW.GLFW_KEY_F2 -> 0x78;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 0x79;
            case GLFW.GLFW_KEY_F1 -> 0x7A;
            case GLFW.GLFW_KEY_LEFT -> 0x7B;
            case GLFW.GLFW_KEY_RIGHT -> 0x7C;
            case GLFW.GLFW_KEY_DOWN -> 0x7D;
            case GLFW.GLFW_KEY_UP -> 0x7E;
            default -> -1;
        };
    }

    /** The virtual key code of the US-keyboard key that types a character (shifted or not), or -1. */
    static int keyCodeForChar(int codePoint) {
        int base = me.piitex.engine.webview.webkit.UsLayout.unshifted(codePoint);
        if (base >= 'a' && base <= 'z') return keyCode(GLFW.GLFW_KEY_A + (base - 'a'));
        if (base >= '0' && base <= '9') return keyCode(GLFW.GLFW_KEY_0 + (base - '0'));
        return switch (base) {
            case ' ' -> 0x31;
            case '`' -> 0x32;
            case '-' -> 0x1B;
            case '=' -> 0x18;
            case '[' -> 0x21;
            case ']' -> 0x1E;
            case '\\' -> 0x2A;
            case ';' -> 0x29;
            case '\'' -> 0x27;
            case ',' -> 0x2B;
            case '.' -> 0x2F;
            case '/' -> 0x2C;
            default -> -1;
        };
    }

    /**
     * The text AppKit puts on a key event for a key that types no character of its own: the control characters for Enter,
     * Tab, Backspace and Escape, and AppKit's private-use characters for the arrows, F-keys and the editing keys.
     * AppKit turns these into commands ({@code insertNewline:}, {@code moveLeft:}, ...).
     */
    static String functionCharacters(int key) {
        int c = switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> '\r';
            case GLFW.GLFW_KEY_TAB -> '\t';
            case GLFW.GLFW_KEY_BACKSPACE -> 0x7F;
            case GLFW.GLFW_KEY_ESCAPE -> 0x1B;
            case GLFW.GLFW_KEY_UP -> 0xF700;
            case GLFW.GLFW_KEY_DOWN -> 0xF701;
            case GLFW.GLFW_KEY_LEFT -> 0xF702;
            case GLFW.GLFW_KEY_RIGHT -> 0xF703;
            case GLFW.GLFW_KEY_INSERT -> 0xF727;
            case GLFW.GLFW_KEY_DELETE -> 0xF728;
            case GLFW.GLFW_KEY_HOME -> 0xF729;
            case GLFW.GLFW_KEY_END -> 0xF72B;
            case GLFW.GLFW_KEY_PAGE_UP -> 0xF72C;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 0xF72D;
            default -> {
                if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F12) yield 0xF704 + (key - GLFW.GLFW_KEY_F1);
                yield -1;
            }
        };
        return c < 0 ? null : String.valueOf((char) c);
    }

    /** Whether AppKit sends this key with the Function flag (arrows, F-keys, the editing keys). */
    static boolean isFunctionKey(int key) {
        String s = functionCharacters(key);
        return s != null && s.charAt(0) >= 0xF700;
    }

    /** Flags that go with a key event for this key, on top of the modifiers held. */
    static long keyFlags(int key, int glfwMods) {
        long f = modifierFlags(glfwMods);
        if (isFunctionKey(key)) f |= FLAG_FUNCTION;
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN
                || (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_EQUAL)) f |= FLAG_NUMERIC_PAD;
        return f;
    }

    /**
     * The characters of a key event for a key chord or key with no function character: the key's unshifted character
     * (what {@code Keys.keyName} gives), or null if it has none.
     */
    static String baseCharacter(int key) {
        String name = me.piitex.engine.webview.internal.Keys.keyName(key);
        return name.codePointCount(0, name.length()) == 1 ? name : null;
    }
}
