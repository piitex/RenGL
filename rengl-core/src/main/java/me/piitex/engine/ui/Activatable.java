package me.piitex.engine.ui;

import org.lwjgl.glfw.GLFW;

/**
 * A {@link Focusable} that does one thing when asked, as a button, a check box or a link does: Tab reaches it, and
 * Space or Enter (a gamepad's A button arrives as Enter) activates it. It leaves the rest of the keyboard to the game,
 * and a mouse click does not focus it, so putting one on screen does not change how a game reads WASD.
 */
public interface Activatable extends Focusable {
    /**
     * Does what a click does.
     */
    void activate();

    @Override
    default void onFocusGained() {
    }

    @Override
    default void onFocusLost() {
    }

    @Override
    default void onCharTyped(int codepoint) {
    }

    @Override
    default boolean grabsKeyboard() {
        return false;
    }

    @Override
    default boolean handlesKey(int key, int mods) {
        return key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
    }

    @Override
    default void onKeyPressed(int key, int action, int mods) {
        if (action == GLFW.GLFW_PRESS && handlesKey(key, mods)) {
            activate();
            if (this instanceof Element element) element.notifyAccessibleChange();
        }
    }
}
