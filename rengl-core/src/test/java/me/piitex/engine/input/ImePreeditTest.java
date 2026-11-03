package me.piitex.engine.input;

import me.piitex.engine.ui.overlays.TextFieldOverlay;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.*;

class ImePreeditTest {
    private static ImePreedit deliver(String text, int[] blocks, int focusedBlock, int caret) {
        InputManager manager = new InputManager();
        int[] codepoints = text.codePoints().toArray();
        IntBuffer string = MemoryUtil.memAllocInt(Math.max(1, codepoints.length));
        IntBuffer sizes = MemoryUtil.memAllocInt(Math.max(1, blocks.length));
        try {
            string.put(codepoints).flip();
            sizes.put(blocks).flip();
            manager.onPreedit(0L, codepoints.length, codepoints.length == 0 ? 0L : MemoryUtil.memAddress(string),
                    blocks.length, MemoryUtil.memAddress(sizes), focusedBlock, caret);
        } finally {
            MemoryUtil.memFree(string);
            MemoryUtil.memFree(sizes);
        }
        InputEvent event = manager.getEventQueue().poll();
        assertNotNull(event);
        assertEquals(InputEvent.Type.PREEDIT, event.type);
        assertNull(manager.getEventQueue().poll());
        return event.preedit;
    }

    @Test
    void aCompositionArrivesWithItsClauseAndCaret() {
        // "きょうは" converted as two clauses of 2 codepoints, the second being converted, caret at its end.
        ImePreedit preedit = deliver("きょうは", new int[]{2, 2}, 1, 4);
        assertEquals("きょうは", preedit.text());
        assertEquals(2, preedit.focusStart());
        assertEquals(4, preedit.focusEnd());
        assertEquals(4, preedit.caret());
    }

    @Test
    void offsetsAreUtf16WhereCodepointsTakeTwoUnits() {
        // 𠮷 (U+20BB7) is two UTF-16 units but one codepoint.
        ImePreedit preedit = deliver("𠮷野家", new int[]{1, 2}, 1, 3);
        assertEquals(1 * 2, preedit.focusStart());
        assertEquals(2 + 2, preedit.focusEnd());
        assertEquals(4, preedit.caret());
    }

    @Test
    void anEmptyCompositionMeansItEnded() {
        ImePreedit preedit = deliver("", new int[0], -1, 0);
        assertTrue(preedit.isEmpty());
    }

    @Test
    void noClauseIsHighlightedWhenNoneIsFocused() {
        ImePreedit preedit = deliver("abc", new int[]{3}, -1, 3);
        assertEquals(0, preedit.focusStart());
        assertEquals(0, preedit.focusEnd());
    }

    @Test
    void onlyEditableFieldsAskForAnInputMethod() {
        TextFieldOverlay field = new TextFieldOverlay(0, 0, 100, 24);
        assertTrue(field.acceptsTextInput());
        field.setPassword(true);
        assertFalse(field.acceptsTextInput());
        field.setPassword(false);
        field.setReadOnly(true);
        assertFalse(field.acceptsTextInput());
    }
}
