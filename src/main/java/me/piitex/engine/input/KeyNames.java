package me.piitex.engine.input;

import me.piitex.engine.utils.Platform;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Names of the GLFW keys: the fixed ones {@link Binding} saves and parses ({@code SPACE}, {@code LEFT_SHIFT},
 * {@code A}), and the ones shown to players, which follow the keyboard layout where the OS reports it.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
final class KeyNames {
    private static final Map<Integer, String> NAMES = new HashMap<>();
    private static final Map<String, Integer> CODES = new HashMap<>();
    private static final Map<Integer, String> SYMBOLS = Map.of(
            GLFW.GLFW_KEY_APOSTROPHE, "'", GLFW.GLFW_KEY_COMMA, ",", GLFW.GLFW_KEY_MINUS, "-",
            GLFW.GLFW_KEY_PERIOD, ".", GLFW.GLFW_KEY_SLASH, "/", GLFW.GLFW_KEY_SEMICOLON, ";",
            GLFW.GLFW_KEY_EQUAL, "=", GLFW.GLFW_KEY_LEFT_BRACKET, "[", GLFW.GLFW_KEY_BACKSLASH, "\\",
            GLFW.GLFW_KEY_RIGHT_BRACKET, "]");
    private static final Map<Integer, String> KEYPAD = Map.of(
            GLFW.GLFW_KEY_KP_DECIMAL, ".", GLFW.GLFW_KEY_KP_DIVIDE, "/", GLFW.GLFW_KEY_KP_MULTIPLY, "*",
            GLFW.GLFW_KEY_KP_SUBTRACT, "-", GLFW.GLFW_KEY_KP_ADD, "+", GLFW.GLFW_KEY_KP_EQUAL, "=");

    /**
     * What the keys print on the current keyboard layout, from GLFW. Empty until the engine fills it.
     */
    private static volatile Map<Integer, String> layout = Map.of();

    static {
        add(GLFW.GLFW_KEY_SPACE, "SPACE");
        add(GLFW.GLFW_KEY_APOSTROPHE, "APOSTROPHE");
        add(GLFW.GLFW_KEY_COMMA, "COMMA");
        add(GLFW.GLFW_KEY_MINUS, "MINUS");
        add(GLFW.GLFW_KEY_PERIOD, "PERIOD");
        add(GLFW.GLFW_KEY_SLASH, "SLASH");
        for (int i = 0; i <= 9; i++) add(GLFW.GLFW_KEY_0 + i, String.valueOf(i));
        add(GLFW.GLFW_KEY_SEMICOLON, "SEMICOLON");
        add(GLFW.GLFW_KEY_EQUAL, "EQUAL");
        for (int i = 0; i < 26; i++) add(GLFW.GLFW_KEY_A + i, String.valueOf((char) ('A' + i)));
        add(GLFW.GLFW_KEY_LEFT_BRACKET, "LEFT_BRACKET");
        add(GLFW.GLFW_KEY_BACKSLASH, "BACKSLASH");
        add(GLFW.GLFW_KEY_RIGHT_BRACKET, "RIGHT_BRACKET");
        add(GLFW.GLFW_KEY_GRAVE_ACCENT, "GRAVE_ACCENT");
        add(GLFW.GLFW_KEY_WORLD_1, "WORLD_1");
        add(GLFW.GLFW_KEY_WORLD_2, "WORLD_2");
        addRun(GLFW.GLFW_KEY_ESCAPE, "ESCAPE", "ENTER", "TAB", "BACKSPACE", "INSERT", "DELETE", "RIGHT", "LEFT",
                "DOWN", "UP", "PAGE_UP", "PAGE_DOWN", "HOME", "END");
        addRun(GLFW.GLFW_KEY_CAPS_LOCK, "CAPS_LOCK", "SCROLL_LOCK", "NUM_LOCK", "PRINT_SCREEN", "PAUSE");
        for (int i = 0; i < 25; i++) add(GLFW.GLFW_KEY_F1 + i, "F" + (i + 1));
        for (int i = 0; i <= 9; i++) add(GLFW.GLFW_KEY_KP_0 + i, "KP_" + i);
        addRun(GLFW.GLFW_KEY_KP_DECIMAL, "KP_DECIMAL", "KP_DIVIDE", "KP_MULTIPLY", "KP_SUBTRACT", "KP_ADD",
                "KP_ENTER", "KP_EQUAL");
        addRun(GLFW.GLFW_KEY_LEFT_SHIFT, "LEFT_SHIFT", "LEFT_CONTROL", "LEFT_ALT", "LEFT_SUPER", "RIGHT_SHIFT",
                "RIGHT_CONTROL", "RIGHT_ALT", "RIGHT_SUPER", "MENU");
    }

    private KeyNames() {
    }

    private static void add(int code, String name) {
        NAMES.put(code, name);
        CODES.put(name, code);
    }

    private static void addRun(int first, String... names) {
        for (int i = 0; i < names.length; i++) add(first + i, names[i]);
    }

    /**
     * The saved name of a GLFW key, such as {@code SPACE}, or null if it isn't a known key.
     */
    static String name(int key) {
        return NAMES.get(key);
    }

    /**
     * The GLFW key with the saved name {@code name} (upper case), or -1.
     */
    static int code(String name) {
        Integer code = CODES.get(name);
        return code == null ? -1 : code;
    }

    /**
     * Main thread only (GLFW's rule for {@code glfwGetKeyName}). Reads what the printable keys show on the current
     * keyboard layout, so a key saved as {@code W} shows as "Z" on a French keyboard.
     */
    static void refreshLayout() {
        Map<Integer, String> names = new HashMap<>();
        for (int key : NAMES.keySet()) {
            if (key > GLFW.GLFW_KEY_WORLD_2) continue; // only the printable keys have layout names
            if (GLFW.glfwGetKeyScancode(key) == -1) continue; // not on this keyboard; asking would raise a GLFW error
            String printed = GLFW.glfwGetKeyName(key, 0);
            if (printed != null && !printed.isBlank()) names.put(key, printed.toUpperCase(Locale.ROOT));
        }
        layout = Map.copyOf(names);
    }

    /**
     * The name to show a player for a key: what it prints on the current layout, or else a readable form of its
     * saved name ("Left Shift", "Numpad 4", "Page Up").
     */
    static String display(int key) {
        String printed = layout.get(key);
        if (printed != null) return printed;
        String symbol = SYMBOLS.get(key);
        if (symbol != null) return symbol;
        if (key == GLFW.GLFW_KEY_GRAVE_ACCENT) return "`";
        String keypad = KEYPAD.get(key);
        if (keypad != null) return "Numpad " + keypad;
        String name = NAMES.get(key);
        if (name == null) return "Key " + key;
        if (name.startsWith("KP_")) return "Numpad " + words(name.substring(3));
        boolean mac = Platform.getCurrent() == Platform.MACOS;
        name = name.replace("SUPER", mac ? "CMD" : Platform.getCurrent() == Platform.WINDOWS ? "WIN" : "SUPER");
        if (mac) name = name.replace("ALT", "OPTION");
        return words(name);
    }

    /**
     * The shown name of a modifier bit, as in "Ctrl+S".
     */
    static String modifier(int mod) {
        boolean mac = Platform.getCurrent() == Platform.MACOS;
        return switch (mod) {
            case GLFW.GLFW_MOD_SHIFT -> "Shift";
            case GLFW.GLFW_MOD_CONTROL -> mac ? "Control" : "Ctrl";
            case GLFW.GLFW_MOD_ALT -> mac ? "Option" : "Alt";
            case GLFW.GLFW_MOD_SUPER -> mac ? "Cmd" : Platform.getCurrent() == Platform.WINDOWS ? "Win" : "Super";
            default -> "?";
        };
    }

    /**
     * {@code PAGE_UP} to "Page Up".
     */
    private static String words(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        boolean start = true;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                sb.append(' ');
                start = true;
            } else {
                sb.append(start ? c : Character.toLowerCase(c));
                start = false;
            }
        }
        return sb.toString();
    }
}
