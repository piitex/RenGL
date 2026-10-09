package me.piitex.engine.input;

/**
 * Something that reacts to key combinations for the whole window, such as a menu bar's shortcuts. Register one with
 * {@link me.piitex.engine.Window#addShortcutHandler}. It is asked on a fresh key press (not a repeat), before the
 * focused element and the game see it, while no popup or modal is open.
 */
@FunctionalInterface
public interface ShortcutHandler {
    /**
     * @param key  the GLFW key pressed
     * @param mods the GLFW modifier bits held
     * @return true if the combination was used, which keeps it from the focused element and the game
     */
    boolean onShortcut(int key, int mods);
}
