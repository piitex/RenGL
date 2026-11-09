package me.piitex.engine.webview.webkit;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UsLayoutTest {
    @Test
    void capitalsAndShiftedSymbolsNeedShift() {
        assertTrue(UsLayout.needsShift('A'));
        assertTrue(UsLayout.needsShift('!'));
        assertTrue(UsLayout.needsShift('"'));
        assertTrue(UsLayout.needsShift('?'));
        assertFalse(UsLayout.needsShift('a'));
        assertFalse(UsLayout.needsShift('1'));
        assertFalse(UsLayout.needsShift('/'));
        assertFalse(UsLayout.needsShift(' '));
        assertFalse(UsLayout.needsShift('é'));
    }

    @Test
    void theUnshiftedCharacterIsTheOneOnTheSameKey() {
        assertEquals('a', UsLayout.unshifted('A'));
        assertEquals('1', UsLayout.unshifted('!'));
        assertEquals('`', UsLayout.unshifted('~'));
        assertEquals('=', UsLayout.unshifted('+'));
        assertEquals('\\', UsLayout.unshifted('|'));
        assertEquals(';', UsLayout.unshifted(':'));
        assertEquals('\'', UsLayout.unshifted('"'));
        assertEquals('/', UsLayout.unshifted('?'));
        assertEquals('q', UsLayout.unshifted('q'));
        assertEquals('é', UsLayout.unshifted('é'));
    }

    @Test
    void everyShiftedSymbolMapsToADifferentKey() {
        String shifted = "~!@#$%^&*()_+{}|:\"<>?";
        Set<Integer> seen = new HashSet<>();
        for (char c : shifted.toCharArray()) {
            assertTrue(UsLayout.hasKey(c), "has a key: " + c);
            assertTrue(seen.add(UsLayout.unshifted(c)), "no two share a key: " + c);
        }
    }

    @Test
    void onlyAsciiTextHasAKey() {
        assertTrue(UsLayout.hasKey('x'));
        assertTrue(UsLayout.hasKey('X'));
        assertTrue(UsLayout.hasKey('5'));
        assertTrue(UsLayout.hasKey(' '));
        assertFalse(UsLayout.hasKey('é'));
        assertFalse(UsLayout.hasKey(0x4E2D));
        assertFalse(UsLayout.hasKey(0x1F600));
    }
}
