package me.piitex.engine.webview.macos;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MacKeysTest {
    @Test
    void lettersHaveTheirAnsiVirtualKeyCodes() {
        assertEquals(0x00, MacKeys.keyCode(GLFW.GLFW_KEY_A));
        assertEquals(0x06, MacKeys.keyCode(GLFW.GLFW_KEY_Z));
        assertEquals(0x0C, MacKeys.keyCode(GLFW.GLFW_KEY_Q));
        assertEquals(0x23, MacKeys.keyCode(GLFW.GLFW_KEY_P));
        assertEquals(0x2E, MacKeys.keyCode(GLFW.GLFW_KEY_M));
    }

    @Test
    void editingAndArrowKeysHaveTheirCodes() {
        assertEquals(0x24, MacKeys.keyCode(GLFW.GLFW_KEY_ENTER));
        assertEquals(0x33, MacKeys.keyCode(GLFW.GLFW_KEY_BACKSPACE));
        assertEquals(0x75, MacKeys.keyCode(GLFW.GLFW_KEY_DELETE));
        assertEquals(0x30, MacKeys.keyCode(GLFW.GLFW_KEY_TAB));
        assertEquals(0x35, MacKeys.keyCode(GLFW.GLFW_KEY_ESCAPE));
        assertEquals(0x7B, MacKeys.keyCode(GLFW.GLFW_KEY_LEFT));
        assertEquals(0x7C, MacKeys.keyCode(GLFW.GLFW_KEY_RIGHT));
        assertEquals(0x7D, MacKeys.keyCode(GLFW.GLFW_KEY_DOWN));
        assertEquals(0x7E, MacKeys.keyCode(GLFW.GLFW_KEY_UP));
        assertEquals(0x73, MacKeys.keyCode(GLFW.GLFW_KEY_HOME));
        assertEquals(0x77, MacKeys.keyCode(GLFW.GLFW_KEY_END));
    }

    @Test
    void noTwoKeysShareACode() {
        Set<Integer> seen = new HashSet<>();
        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
            int code = MacKeys.keyCode(key);
            if (code >= 0) assertTrue(seen.add(code), "duplicate code " + Integer.toHexString(code) + " for key " + key);
        }
        assertTrue(seen.size() > 80, "most keys have one: " + seen.size());
    }

    @Test
    void unknownAndModifierKeysHaveNone() {
        assertEquals(-1, MacKeys.keyCode(GLFW.GLFW_KEY_UNKNOWN));
        assertEquals(-1, MacKeys.keyCode(GLFW.GLFW_KEY_LEFT_SHIFT));
        assertEquals(-1, MacKeys.keyCode(GLFW.GLFW_KEY_PRINT_SCREEN));
    }

    @Test
    void aTypedCharacterFindsItsKeyWhetherOrNotShiftIsHeld() {
        assertEquals(MacKeys.keyCode(GLFW.GLFW_KEY_H), MacKeys.keyCodeForChar('h'));
        assertEquals(MacKeys.keyCode(GLFW.GLFW_KEY_H), MacKeys.keyCodeForChar('H'));
        assertEquals(MacKeys.keyCode(GLFW.GLFW_KEY_1), MacKeys.keyCodeForChar('!'));
        assertEquals(MacKeys.keyCode(GLFW.GLFW_KEY_SLASH), MacKeys.keyCodeForChar('?'));
        assertEquals(MacKeys.keyCode(GLFW.GLFW_KEY_SPACE), MacKeys.keyCodeForChar(' '));
        assertEquals(MacKeys.keyCode(GLFW.GLFW_KEY_APOSTROPHE), MacKeys.keyCodeForChar('"'));
        assertEquals(-1, MacKeys.keyCodeForChar('é'));
        assertEquals(-1, MacKeys.keyCodeForChar(0x4E2D));
    }

    @Test
    void everyAsciiCharacterOfAUsKeyboardFindsAKey() {
        for (int c = 0x20; c < 0x7F; c++) assertTrue(MacKeys.keyCodeForChar(c) >= 0, "no key for " + (char) c);
    }

    @Test
    void modifierFlagsAreAppKits() {
        assertEquals(0, MacKeys.modifierFlags(0));
        assertEquals(1L << 17, MacKeys.modifierFlags(GLFW.GLFW_MOD_SHIFT));
        assertEquals(1L << 18, MacKeys.modifierFlags(GLFW.GLFW_MOD_CONTROL));
        assertEquals(1L << 19, MacKeys.modifierFlags(GLFW.GLFW_MOD_ALT), "alt is Option");
        assertEquals(1L << 20, MacKeys.modifierFlags(GLFW.GLFW_MOD_SUPER), "super is Command");
        assertEquals((1L << 17) | (1L << 20), MacKeys.modifierFlags(GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_SUPER));
    }

    @Test
    void functionKeysCarryAppKitsPrivateCharacters() {
        assertEquals("", MacKeys.functionCharacters(GLFW.GLFW_KEY_UP));
        assertEquals("", MacKeys.functionCharacters(GLFW.GLFW_KEY_RIGHT));
        assertEquals("", MacKeys.functionCharacters(GLFW.GLFW_KEY_F1));
        assertEquals("", MacKeys.functionCharacters(GLFW.GLFW_KEY_F12));
        assertEquals("", MacKeys.functionCharacters(GLFW.GLFW_KEY_DELETE));
        assertEquals("\r", MacKeys.functionCharacters(GLFW.GLFW_KEY_ENTER));
        assertEquals("\u007f", MacKeys.functionCharacters(GLFW.GLFW_KEY_BACKSPACE));
        assertNull(MacKeys.functionCharacters(GLFW.GLFW_KEY_A));
    }

    @Test
    void arrowsAndFunctionKeysGetTheFunctionFlagButEnterDoesNot() {
        assertTrue((MacKeys.keyFlags(GLFW.GLFW_KEY_LEFT, 0) & MacKeys.FLAG_FUNCTION) != 0);
        assertTrue((MacKeys.keyFlags(GLFW.GLFW_KEY_LEFT, 0) & MacKeys.FLAG_NUMERIC_PAD) != 0, "arrows count as keypad keys to AppKit");
        assertTrue((MacKeys.keyFlags(GLFW.GLFW_KEY_F5, 0) & MacKeys.FLAG_FUNCTION) != 0);
        assertEquals(0, MacKeys.keyFlags(GLFW.GLFW_KEY_ENTER, 0));
        assertEquals(MacKeys.FLAG_SHIFT, MacKeys.keyFlags(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_MOD_SHIFT));
    }

    @Test
    void aKeyWithABaseCharacterGivesIt() {
        assertEquals("a", MacKeys.baseCharacter(GLFW.GLFW_KEY_A));
        assertEquals("5", MacKeys.baseCharacter(GLFW.GLFW_KEY_5));
        assertEquals("[", MacKeys.baseCharacter(GLFW.GLFW_KEY_LEFT_BRACKET));
        assertNull(MacKeys.baseCharacter(GLFW.GLFW_KEY_F1), "a name, not a character");
    }

    @Test
    void mouseButtonsAndMovesMapToEventTypes() {
        assertEquals(MacKeys.LEFT_MOUSE_DOWN, MacKeys.mouseButtonType(0, true));
        assertEquals(MacKeys.LEFT_MOUSE_UP, MacKeys.mouseButtonType(0, false));
        assertEquals(MacKeys.RIGHT_MOUSE_DOWN, MacKeys.mouseButtonType(1, true));
        assertEquals(MacKeys.OTHER_MOUSE_UP, MacKeys.mouseButtonType(2, false));
        assertEquals(MacKeys.MOUSE_MOVED, MacKeys.mouseMoveType(0));
        assertEquals(MacKeys.LEFT_MOUSE_DRAGGED, MacKeys.mouseMoveType(1));
        assertEquals(MacKeys.RIGHT_MOUSE_DRAGGED, MacKeys.mouseMoveType(2));
        assertEquals(MacKeys.LEFT_MOUSE_DRAGGED, MacKeys.mouseMoveType(3), "left wins");
        assertEquals(MacKeys.OTHER_MOUSE_DRAGGED, MacKeys.mouseMoveType(4));
    }

    @Test
    void everyEventTypeHasAResponderMethod() {
        assertEquals("mouseDown:", MacKeys.responderSelector(MacKeys.LEFT_MOUSE_DOWN));
        assertEquals("rightMouseUp:", MacKeys.responderSelector(MacKeys.RIGHT_MOUSE_UP));
        assertEquals("otherMouseDragged:", MacKeys.responderSelector(MacKeys.OTHER_MOUSE_DRAGGED));
        assertEquals("mouseMoved:", MacKeys.responderSelector(MacKeys.MOUSE_MOVED));
        assertEquals("keyDown:", MacKeys.responderSelector(MacKeys.KEY_DOWN));
        assertEquals("keyUp:", MacKeys.responderSelector(MacKeys.KEY_UP));
        assertEquals("mouseEntered:", MacKeys.responderSelector(MacKeys.MOUSE_ENTERED));
        assertThrows(IllegalArgumentException.class, () -> MacKeys.responderSelector(99));
    }

    @Test
    void theRuntimeIsNotThereOffMacOs() {
        if (System.getProperty("os.name").toLowerCase().contains("mac")) return;
        assertFalse(ObjC.available());
        assertNotNull(ObjC.failure());
        assertFalse(WKWebViewBackend.isAvailable());
        assertNotNull(WKWebViewBackend.unavailableReason());
    }
}
