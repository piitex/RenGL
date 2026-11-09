package me.piitex.engine.webview.internal;

import me.piitex.engine.ui.CursorMode;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.*;

class KeysTest {
    @Test
    void lettersAndDigitsKeepTheirCodes() {
        assertEquals('A', Keys.virtualKey(GLFW.GLFW_KEY_A));
        assertEquals('7', Keys.virtualKey(GLFW.GLFW_KEY_7));
        assertEquals("a", Keys.keyName(GLFW.GLFW_KEY_A));
        assertEquals("KeyA", Keys.codeName(GLFW.GLFW_KEY_A));
        assertEquals("Digit7", Keys.codeName(GLFW.GLFW_KEY_7));
    }

    @Test
    void controlKeysMapToWindowsCodesAndDomNames() {
        assertEquals(0x0D, Keys.virtualKey(GLFW.GLFW_KEY_ENTER));
        assertEquals(0x0D, Keys.virtualKey(GLFW.GLFW_KEY_KP_ENTER));
        assertEquals("Enter", Keys.keyName(GLFW.GLFW_KEY_KP_ENTER));
        assertEquals("NumpadEnter", Keys.codeName(GLFW.GLFW_KEY_KP_ENTER));
        assertEquals(0x25, Keys.virtualKey(GLFW.GLFW_KEY_LEFT));
        assertEquals("ArrowLeft", Keys.keyName(GLFW.GLFW_KEY_LEFT));
        assertEquals(0x71, Keys.virtualKey(GLFW.GLFW_KEY_F2));
        assertEquals("F2", Keys.keyName(GLFW.GLFW_KEY_F2));
        assertEquals(0x60, Keys.virtualKey(GLFW.GLFW_KEY_KP_0));
    }

    @Test
    void aKeyThePageHasNoUseForIsDropped() {
        assertEquals(0, Keys.virtualKey(GLFW.GLFW_KEY_UNKNOWN));
        assertEquals(0, Keys.virtualKey(GLFW.GLFW_KEY_PRINT_SCREEN));
    }

    @Test
    void modifiersAreRemappedToTheProtocolsBits() {
        assertEquals(0, Keys.modifiers(0));
        assertEquals(8, Keys.modifiers(GLFW.GLFW_MOD_SHIFT));
        assertEquals(2, Keys.modifiers(GLFW.GLFW_MOD_CONTROL));
        assertEquals(1, Keys.modifiers(GLFW.GLFW_MOD_ALT));
        assertEquals(4, Keys.modifiers(GLFW.GLFW_MOD_SUPER));
        assertEquals(10, Keys.modifiers(GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_CONTROL));
    }

    @Test
    void editingChordsBecomeCommandsOnlyWithControlOrCmd() {
        assertEquals("copy", Keys.command(GLFW.GLFW_KEY_C, GLFW.GLFW_MOD_CONTROL));
        assertEquals("paste", Keys.command(GLFW.GLFW_KEY_V, GLFW.GLFW_MOD_SUPER));
        assertEquals("selectAll", Keys.command(GLFW.GLFW_KEY_A, GLFW.GLFW_MOD_CONTROL));
        assertEquals("undo", Keys.command(GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL));
        assertEquals("redo", Keys.command(GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT));
        assertEquals("redo", Keys.command(GLFW.GLFW_KEY_Y, GLFW.GLFW_MOD_CONTROL));
        assertNull(Keys.command(GLFW.GLFW_KEY_C, 0), "a bare C is typing");
        assertNull(Keys.command(GLFW.GLFW_KEY_C, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT), "Ctrl+Alt is AltGr on some layouts");
        assertNull(Keys.command(GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_CONTROL), "the page's own shortcut, not an editing command");
    }

    @Test
    void cssCursorsMapToTheEnginesAndUnknownOnesToTheArrow() {
        assertEquals(CursorMode.POINTER, CdpInput.cursorFor("pointer"));
        assertEquals(CursorMode.TEXT, CdpInput.cursorFor("text"));
        assertEquals(CursorMode.RESIZE_HORIZONTAL, CdpInput.cursorFor("col-resize"));
        assertEquals(CursorMode.NOT_ALLOWED, CdpInput.cursorFor("not-allowed"));
        assertNull(CdpInput.cursorFor("default"));
        assertNull(CdpInput.cursorFor("url(foo.png), auto"));
    }
}
