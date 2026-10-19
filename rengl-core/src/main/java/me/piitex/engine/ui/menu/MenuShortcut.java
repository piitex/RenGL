package me.piitex.engine.ui.menu;

import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.Map;

/**
 * A key combination read from a {@link MenuItem#getShortcut() shortcut label} such as {@code "Ctrl+Shift+S"}, so the
 * label can bind the key too. {@code Ctrl}, {@code Cmd}, {@code Command}, {@code Meta} and {@code Super} all mean the
 * command key: Control, or Command on macOS, whichever the person presses. {@code Alt} and {@code Option} mean Alt.
 * The key is a letter, a digit, a punctuation mark, {@code F1} to {@code F12}, or a name such as {@code Enter},
 * {@code Esc}, {@code Del}, {@code Space}, {@code Tab}, {@code Home}, {@code End}, {@code PgUp}, {@code PgDn},
 * {@code Up}, {@code Down}, {@code Left} or {@code Right}.
 *
 * @param key     the GLFW key
 * @param command whether the command key is held
 * @param shift   whether Shift is held
 * @param alt     whether Alt is held
 */
record MenuShortcut(int key, boolean command, boolean shift, boolean alt) {
    private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("ENTER", GLFW.GLFW_KEY_ENTER), Map.entry("RETURN", GLFW.GLFW_KEY_ENTER),
            Map.entry("ESC", GLFW.GLFW_KEY_ESCAPE), Map.entry("ESCAPE", GLFW.GLFW_KEY_ESCAPE),
            Map.entry("DEL", GLFW.GLFW_KEY_DELETE), Map.entry("DELETE", GLFW.GLFW_KEY_DELETE),
            Map.entry("BACKSPACE", GLFW.GLFW_KEY_BACKSPACE), Map.entry("TAB", GLFW.GLFW_KEY_TAB),
            Map.entry("SPACE", GLFW.GLFW_KEY_SPACE), Map.entry("INS", GLFW.GLFW_KEY_INSERT),
            Map.entry("INSERT", GLFW.GLFW_KEY_INSERT), Map.entry("HOME", GLFW.GLFW_KEY_HOME),
            Map.entry("END", GLFW.GLFW_KEY_END), Map.entry("PGUP", GLFW.GLFW_KEY_PAGE_UP),
            Map.entry("PAGEUP", GLFW.GLFW_KEY_PAGE_UP), Map.entry("PGDN", GLFW.GLFW_KEY_PAGE_DOWN),
            Map.entry("PAGEDOWN", GLFW.GLFW_KEY_PAGE_DOWN), Map.entry("UP", GLFW.GLFW_KEY_UP),
            Map.entry("DOWN", GLFW.GLFW_KEY_DOWN), Map.entry("LEFT", GLFW.GLFW_KEY_LEFT),
            Map.entry("RIGHT", GLFW.GLFW_KEY_RIGHT));
    private static final Map<Character, Integer> PUNCTUATION = Map.ofEntries(
            Map.entry(',', GLFW.GLFW_KEY_COMMA), Map.entry('.', GLFW.GLFW_KEY_PERIOD),
            Map.entry('/', GLFW.GLFW_KEY_SLASH), Map.entry(';', GLFW.GLFW_KEY_SEMICOLON),
            Map.entry('\'', GLFW.GLFW_KEY_APOSTROPHE), Map.entry('[', GLFW.GLFW_KEY_LEFT_BRACKET),
            Map.entry(']', GLFW.GLFW_KEY_RIGHT_BRACKET), Map.entry('\\', GLFW.GLFW_KEY_BACKSLASH),
            Map.entry('-', GLFW.GLFW_KEY_MINUS), Map.entry('=', GLFW.GLFW_KEY_EQUAL),
            Map.entry('`', GLFW.GLFW_KEY_GRAVE_ACCENT));

    /**
     * The combination {@code text} names, or null if it is empty or names a key or modifier this does not know (the
     * label is then only shown).
     */
    static MenuShortcut parse(String text) {
        if (text == null || text.isBlank()) return null;
        String[] parts = text.trim().split("\\+", -1);
        String last = parts[parts.length - 1].trim();
        int count = parts.length - 1;
        if (last.isEmpty() && parts.length >= 2) { // "Ctrl++": the key is the plus sign, which has no key of its own
            return null;
        }
        boolean command = false, shift = false, alt = false;
        for (int i = 0; i < count; i++) {
            switch (parts[i].trim().toLowerCase(Locale.ROOT)) {
                case "ctrl", "control", "cmd", "command", "meta", "super" -> command = true;
                case "alt", "option", "opt" -> alt = true;
                case "shift" -> shift = true;
                default -> {
                    return null;
                }
            }
        }
        int key = keyOf(last);
        return key < 0 ? null : new MenuShortcut(key, command, shift, alt);
    }

    private static int keyOf(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.length() == 1) {
            char c = upper.charAt(0);
            if (c >= 'A' && c <= 'Z') return GLFW.GLFW_KEY_A + (c - 'A');
            if (c >= '0' && c <= '9') return GLFW.GLFW_KEY_0 + (c - '0');
            Integer punctuation = PUNCTUATION.get(c);
            return punctuation == null ? -1 : punctuation;
        }
        if (upper.length() >= 2 && upper.charAt(0) == 'F') {
            try {
                int n = Integer.parseInt(upper.substring(1));
                if (n >= 1 && n <= 12) return GLFW.GLFW_KEY_F1 + n - 1;
            } catch (NumberFormatException ignored) {
                // not a function key; falls through to the names
            }
        }
        Integer named = NAMED.get(upper);
        return named == null ? -1 : named;
    }

    /**
     * Whether pressing {@code pressed} with GLFW modifier bits {@code mods} is this combination. Shift and Alt must
     * match exactly; the command key is Control or Super.
     */
    boolean matches(int pressed, int mods) {
        if (pressed != key) return false;
        boolean heldCommand = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        return heldCommand == command
                && ((mods & GLFW.GLFW_MOD_SHIFT) != 0) == shift
                && ((mods & GLFW.GLFW_MOD_ALT) != 0) == alt;
    }
}
