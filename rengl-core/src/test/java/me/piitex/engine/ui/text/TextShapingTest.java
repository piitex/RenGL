package me.piitex.engine.ui.text;

import org.jetbrains.skia.Font;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextShapingTest {
    private static Font font() {
        return new Font(me.piitex.engine.ui.text.Font.getDefault(), 20f);
    }

    @Test
    void latinTextKeepsThePlainFontPath() {
        try (Font font = font()) {
            assertFalse(TextShaping.needsShaping("Hello, world"));
            assertEquals(font.measureTextWidth("Hello, world"), TextShaping.width(font, "Hello, world"));
            assertEquals(font.measureTextWidth("Hel"), TextShaping.xAt(font, "Hello", 3));
        }
    }

    @Test
    void otherScriptsAreRecognised() {
        for (String text : new String[]{"日本語", "한국어", "中文", "مرحبا", "שלום", "สวัสดี", "नमस्ते", "😀"}) {
            assertTrue(TextShaping.needsShaping(text), text);
        }
    }

    @Test
    void shapedTextHasAWidthAndAClickedGapComesBackAsAnOffset() {
        try (Font font = font()) {
            String text = "日本語のテスト";
            float width = TextShaping.width(font, text);
            assertTrue(width > 0f);
            assertEquals(0, TextShaping.offsetAt(font, text, -5f));
            assertEquals(text.length(), TextShaping.offsetAt(font, text, width + 50f));
            // The middle of the text is somewhere in the middle of the string.
            int middle = TextShaping.offsetAt(font, text, width / 2f);
            assertTrue(middle > 0 && middle < text.length(), "offset " + middle);
            assertEquals(0f, TextShaping.xAt(font, text, 0));
            assertEquals(width, TextShaping.xAt(font, text, text.length()), 1.5f);
        }
    }

    @Test
    void theCaretStepsOverWholeCharacters() {
        String pair = "a😀b";              // the emoji is two UTF-16 units
        assertEquals(1, TextShaping.step(pair, 0, 1));
        assertEquals(3, TextShaping.step(pair, 1, 1));
        assertEquals(1, TextShaping.step(pair, 3, -1));

        String accent = "éx";        // e plus a combining acute is one letter
        assertEquals(2, TextShaping.step(accent, 0, 1));
        assertEquals(0, TextShaping.step(accent, 2, -1));

        assertEquals(0, TextShaping.step("", 0, 1));
        assertEquals(3, TextShaping.step("abc", 2, 1));
        assertEquals(0, TextShaping.step("abc", 0, -1));
    }

    @Test
    void rightToLeftTextIsTold() {
        assertTrue(TextShaping.isRightToLeft("مرحبا"));
        assertTrue(TextShaping.isRightToLeft("  שלום"));
        assertFalse(TextShaping.isRightToLeft("hello"));
        assertFalse(TextShaping.isRightToLeft("日本語"));
        assertFalse(TextShaping.isRightToLeft(""));
    }

    @Test
    void textWithoutSpacesWrapsBetweenCharacters() {
        try (Font font = font()) {
            String text = "これは日本語の長い文章です。スペースがありません。";
            float room = TextShaping.width(font, text) / 3f;
            List<String> lines = TextLayout.wrap(text, font, room);
            assertTrue(lines.size() >= 3, lines.toString());
            // Wrapping must not add or lose anything: no spaces are invented inside a language written without them.
            assertEquals(text, String.join("", lines));
        }
    }

    @Test
    void spacedTextStillWrapsAtSpaces() {
        try (Font font = font()) {
            List<String> lines = TextLayout.wrap("one two three four five six", font, font.measureTextWidth("one two three"));
            assertEquals("one two three", lines.get(0));
            assertEquals("four five six", String.join(" ", lines.subList(1, lines.size())));
        }
    }
}
