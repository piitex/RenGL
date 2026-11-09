package me.piitex.engine.webview.webkit;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static me.piitex.engine.webview.webkit.KeyRouting.Route.*;
import static org.junit.jupiter.api.Assertions.*;

class KeyRoutingTest {
    private static final int PRESS = GLFW.GLFW_PRESS, RELEASE = GLFW.GLFW_RELEASE, REPEAT = GLFW.GLFW_REPEAT;

    @Test
    void aKeyThatTypesACharacterIsLeftToTheTextPath() {
        assertEquals(IGNORE, KeyRouting.route(GLFW.GLFW_KEY_H, PRESS, 0));
        assertEquals(IGNORE, KeyRouting.route(GLFW.GLFW_KEY_H, PRESS, GLFW.GLFW_MOD_SHIFT), "shift only changes the character");
        assertEquals(IGNORE, KeyRouting.route(GLFW.GLFW_KEY_SPACE, REPEAT, 0));
        assertEquals(IGNORE, KeyRouting.route(GLFW.GLFW_KEY_5, PRESS, 0));
        assertEquals(IGNORE, KeyRouting.route(GLFW.GLFW_KEY_KP_ADD, PRESS, 0));
    }

    @Test
    void keysThatTypeNothingAreSentAsKeys() {
        for (int key : new int[]{GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_LEFT,
                GLFW.GLFW_KEY_F5, GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_PAGE_DOWN}) {
            assertEquals(KEY_EVENT, KeyRouting.route(key, PRESS, 0));
            assertEquals(KEY_EVENT, KeyRouting.route(key, RELEASE, 0), "the release goes too");
        }
    }

    @Test
    void anEditingShortcutRunsTheCommandOnPressOnly() {
        assertEquals(EDIT_COMMAND, KeyRouting.route(GLFW.GLFW_KEY_A, PRESS, GLFW.GLFW_MOD_CONTROL));
        assertEquals(EDIT_COMMAND, KeyRouting.route(GLFW.GLFW_KEY_C, PRESS, GLFW.GLFW_MOD_SUPER));
        assertEquals(EDIT_COMMAND, KeyRouting.route(GLFW.GLFW_KEY_V, REPEAT, GLFW.GLFW_MOD_CONTROL));
        assertEquals(IGNORE, KeyRouting.route(GLFW.GLFW_KEY_A, RELEASE, GLFW.GLFW_MOD_CONTROL));
    }

    @Test
    void otherChordsAreSentAsKeys() {
        assertEquals(KEY_EVENT, KeyRouting.route(GLFW.GLFW_KEY_B, PRESS, GLFW.GLFW_MOD_CONTROL));
        assertEquals(KEY_EVENT, KeyRouting.route(GLFW.GLFW_KEY_A, PRESS, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT), "ctrl+shift+a is not select all");
        assertEquals(KEY_EVENT, KeyRouting.route(GLFW.GLFW_KEY_X, PRESS, GLFW.GLFW_MOD_ALT));
    }

    @Test
    void modifierKeysAndUnknownKeysAreIgnored() {
        assertEquals(IGNORE, KeyRouting.route(GLFW.GLFW_KEY_LEFT_SHIFT, PRESS, GLFW.GLFW_MOD_SHIFT));
        assertEquals(IGNORE, KeyRouting.route(GLFW.GLFW_KEY_RIGHT_CONTROL, PRESS, GLFW.GLFW_MOD_CONTROL));
        assertEquals(IGNORE, KeyRouting.route(GLFW.GLFW_KEY_LEFT_SUPER, PRESS, GLFW.GLFW_MOD_SUPER));
        assertEquals(IGNORE, KeyRouting.route(GLFW.GLFW_KEY_UNKNOWN, PRESS, 0));
        assertEquals(IGNORE, KeyRouting.route(GLFW.GLFW_KEY_PRINT_SCREEN, PRESS, 0));
    }
}
