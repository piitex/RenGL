package me.piitex.engine.ui.menu;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MenuShortcutTest {
    @Test
    void readsLettersWithModifiers() {
        MenuShortcut s = MenuShortcut.parse("Ctrl+Shift+S");
        assertEquals(new MenuShortcut(GLFW.GLFW_KEY_S, true, true, false), s);
        assertEquals(new MenuShortcut(GLFW.GLFW_KEY_S, true, false, false), MenuShortcut.parse("cmd+s"));
        assertEquals(new MenuShortcut(GLFW.GLFW_KEY_X, false, false, true), MenuShortcut.parse("Alt+X"));
        assertEquals(new MenuShortcut(GLFW.GLFW_KEY_1, true, false, false), MenuShortcut.parse("Ctrl+1"));
    }

    @Test
    void readsNamedAndFunctionKeys() {
        assertEquals(new MenuShortcut(GLFW.GLFW_KEY_F5, false, false, false), MenuShortcut.parse("F5"));
        assertEquals(new MenuShortcut(GLFW.GLFW_KEY_DELETE, false, false, false), MenuShortcut.parse("Del"));
        assertEquals(new MenuShortcut(GLFW.GLFW_KEY_PAGE_UP, true, false, false), MenuShortcut.parse("Ctrl+PgUp"));
        assertEquals(new MenuShortcut(GLFW.GLFW_KEY_COMMA, true, false, false), MenuShortcut.parse("Ctrl+,"));
    }

    @Test
    void textItCannotReadIsOnlyAnImage() {
        assertNull(MenuShortcut.parse(null));
        assertNull(MenuShortcut.parse(""));
        assertNull(MenuShortcut.parse("Ctrl+"));
        assertNull(MenuShortcut.parse("Hyper+S"));
        assertNull(MenuShortcut.parse("Ctrl+Banana"));
        assertNull(MenuShortcut.parse("F13"));
    }

    @Test
    void controlOrCommandBothCountAsTheCommandKey() {
        MenuShortcut s = MenuShortcut.parse("Ctrl+S");
        assertTrue(s.matches(GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_CONTROL));
        assertTrue(s.matches(GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_SUPER));
        assertFalse(s.matches(GLFW.GLFW_KEY_S, 0));
        assertFalse(s.matches(GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT), "shift must match exactly");
        assertFalse(s.matches(GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT));
        assertFalse(s.matches(GLFW.GLFW_KEY_D, GLFW.GLFW_MOD_CONTROL));
    }

    @Test
    void aPlainKeyDoesNotFireWithTheCommandKeyHeld() {
        MenuShortcut f5 = MenuShortcut.parse("F5");
        assertTrue(f5.matches(GLFW.GLFW_KEY_F5, 0));
        assertFalse(f5.matches(GLFW.GLFW_KEY_F5, GLFW.GLFW_MOD_CONTROL));
    }

    @Test
    void choosingARadioItemMovesTheTickAndReportsOnce() {
        List<Integer> chosen = new ArrayList<>();
        MenuItem[] group = MenuItem.radioGroup(1, chosen::add, "Day", "Dusk", "Night");
        assertTrue(group[0].isRadio() && group[0].isCheckable());
        assertFalse(group[0].isChecked());
        assertTrue(group[1].isChecked());

        group[2].fire();
        assertTrue(group[2].isChecked());
        assertFalse(group[1].isChecked());
        assertEquals(List.of(2), chosen);

        group[2].fire();
        assertEquals(List.of(2), chosen, "already ticked: nothing to report");
    }

    @Test
    void theMnemonicIsUnderlinedWhereItFirstAppears() {
        MenuItem item = new MenuItem("Save As", null);
        assertEquals(-1, item.mnemonicIndex());
        item.setMnemonic('a');
        assertEquals(1, item.mnemonicIndex());
        item.setMnemonic('x');
        assertEquals(-1, item.mnemonicIndex(), "a letter the label lacks underlines nothing");
    }
}
