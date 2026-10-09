package me.piitex.engine.ui.text;

import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class TooltipWrapTest {
    // Every letter is 10 points wide, so 30 points holds three letters.
    private static double tenEach(String text) {
        return text.length() * 10.0;
    }

    @Test
    void aShortLineIsLeftAlone() {
        assertEquals(List.of("a b"), TooltipManager.wrap("a b", 100, TooltipWrapTest::tenEach));
    }

    @Test
    void aLongLineBreaksAtSpacesAndNoLineIsTooWide() {
        List<String> lines = TooltipManager.wrap("aa bb cc dd ee ff", 80, TooltipWrapTest::tenEach);
        assertEquals(List.of("aa bb cc", "dd ee ff"), lines);
        for (String line : lines) assertTrue(tenEach(line) <= 80, line);
        assertEquals("aa bb cc dd ee ff", String.join(" ", lines), "nothing is lost");
    }

    @Test
    void aLineBreakInTheTextIsKept() {
        assertEquals(List.of("one", "two"), TooltipManager.wrap("one\ntwo", 100, TooltipWrapTest::tenEach));
        assertEquals(List.of("", "x"), TooltipManager.wrap("\nx", 100, TooltipWrapTest::tenEach));
    }

    @Test
    void aWordWiderThanTheLimitStaysWhole() {
        assertEquals(List.of("abcdefghij", "k"), TooltipManager.wrap("abcdefghij k", 50, TooltipWrapTest::tenEach));
    }
}
