package me.piitex.engine.webview.linux;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.*;

class GdkKeysTest {
    @Test
    void printableKeysUseTheirAsciiCode() {
        assertEquals('a', GdkKeys.keyval(GLFW.GLFW_KEY_A), "letters are lower case, Shift is in the state");
        assertEquals('z', GdkKeys.keyval(GLFW.GLFW_KEY_Z));
        assertEquals('5', GdkKeys.keyval(GLFW.GLFW_KEY_5));
        assertEquals(' ', GdkKeys.keyval(GLFW.GLFW_KEY_SPACE));
        assertEquals('[', GdkKeys.keyval(GLFW.GLFW_KEY_LEFT_BRACKET));
    }

    @Test
    void specialKeysUseTheXKeySymbols() {
        assertEquals(0xFF0D, GdkKeys.keyval(GLFW.GLFW_KEY_ENTER));
        assertEquals(0xFF08, GdkKeys.keyval(GLFW.GLFW_KEY_BACKSPACE));
        assertEquals(0xFF09, GdkKeys.keyval(GLFW.GLFW_KEY_TAB));
        assertEquals(0xFF1B, GdkKeys.keyval(GLFW.GLFW_KEY_ESCAPE));
        assertEquals(0xFFFF, GdkKeys.keyval(GLFW.GLFW_KEY_DELETE));
        assertEquals(0xFF51, GdkKeys.keyval(GLFW.GLFW_KEY_LEFT));
        assertEquals(0xFF52, GdkKeys.keyval(GLFW.GLFW_KEY_UP));
        assertEquals(0xFF53, GdkKeys.keyval(GLFW.GLFW_KEY_RIGHT));
        assertEquals(0xFF54, GdkKeys.keyval(GLFW.GLFW_KEY_DOWN));
        assertEquals(0xFF50, GdkKeys.keyval(GLFW.GLFW_KEY_HOME));
        assertEquals(0xFF57, GdkKeys.keyval(GLFW.GLFW_KEY_END));
        assertEquals(0xFF55, GdkKeys.keyval(GLFW.GLFW_KEY_PAGE_UP));
        assertEquals(0xFF56, GdkKeys.keyval(GLFW.GLFW_KEY_PAGE_DOWN));
        assertEquals(0xFFBE, GdkKeys.keyval(GLFW.GLFW_KEY_F1));
        assertEquals(0xFFC9, GdkKeys.keyval(GLFW.GLFW_KEY_F12));
        assertEquals(0xFFB0, GdkKeys.keyval(GLFW.GLFW_KEY_KP_0));
        assertEquals(0xFF8D, GdkKeys.keyval(GLFW.GLFW_KEY_KP_ENTER));
    }

    @Test
    void unknownKeysHaveNone() {
        assertEquals(0, GdkKeys.keyval(GLFW.GLFW_KEY_UNKNOWN));
        assertEquals(0, GdkKeys.keyval(GLFW.GLFW_KEY_PRINT_SCREEN));
    }

    @Test
    void modifierMasksAreGdks() {
        assertEquals(0, GdkKeys.state(0));
        assertEquals(1, GdkKeys.state(GLFW.GLFW_MOD_SHIFT));
        assertEquals(4, GdkKeys.state(GLFW.GLFW_MOD_CONTROL));
        assertEquals(8, GdkKeys.state(GLFW.GLFW_MOD_ALT), "Alt is Mod1");
        assertEquals(1 << 26, GdkKeys.state(GLFW.GLFW_MOD_SUPER));
        assertEquals(5, GdkKeys.state(GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_CONTROL));
    }

    @Test
    void buttonNumbersAreGdksNotGlfws() {
        assertEquals(1, GdkKeys.button(0), "left");
        assertEquals(3, GdkKeys.button(1), "right");
        assertEquals(2, GdkKeys.button(2), "middle");
        assertEquals(256, GdkKeys.buttonMask(1));
        assertEquals(512, GdkKeys.buttonMask(2));
        assertEquals(1024, GdkKeys.buttonMask(3));
        assertEquals(0, GdkKeys.buttonMask(8));
    }

    @Test
    void heldButtonsBecomeMaskBits() {
        assertEquals(0, GdkKeys.heldMask(0));
        assertEquals(GdkKeys.BUTTON1_MASK, GdkKeys.heldMask(1));
        assertEquals(GdkKeys.BUTTON3_MASK, GdkKeys.heldMask(2));
        assertEquals(GdkKeys.BUTTON2_MASK, GdkKeys.heldMask(4));
        assertEquals(GdkKeys.BUTTON1_MASK | GdkKeys.BUTTON3_MASK, GdkKeys.heldMask(3));
    }

    @Test
    void theEditingCommandsHaveWebKitGtksNames() {
        assertEquals("SelectAll", GdkKeys.editingCommand("selectAll"));
        assertEquals("Copy", GdkKeys.editingCommand("copy"));
        assertEquals("Cut", GdkKeys.editingCommand("cut"));
        assertEquals("Paste", GdkKeys.editingCommand("paste"));
        assertEquals("PasteAsPlainText", GdkKeys.editingCommand("pasteAndMatchStyle"));
        assertEquals("Undo", GdkKeys.editingCommand("undo"));
        assertEquals("Redo", GdkKeys.editingCommand("redo"));
        assertNull(GdkKeys.editingCommand("explode"));
    }

    @Test
    void everyCommandKeysReturnsIsKnown() {
        for (int key : new int[]{GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_Y}) {
            for (int mods : new int[]{GLFW.GLFW_MOD_CONTROL, GLFW.GLFW_MOD_SUPER, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT}) {
                String command = me.piitex.engine.webview.internal.Keys.command(key, mods);
                if (command != null) assertNotNull(GdkKeys.editingCommand(command), command);
            }
        }
    }

    @Test
    void theLibrariesAreNotThereOffLinux() {
        if (System.getProperty("os.name").toLowerCase().contains("linux")) return;
        assertFalse(Gtk.available());
        assertNotNull(Gtk.failure());
        assertFalse(WebKitGtkBackend.isAvailable());
    }
}
