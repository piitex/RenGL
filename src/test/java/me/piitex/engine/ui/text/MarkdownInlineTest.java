package me.piitex.engine.ui.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownInlineTest {
    private static MarkdownInline.Parsed parse(String source) {
        return MarkdownInline.parse(source);
    }

    private static String covered(MarkdownInline.Parsed parsed, InlineRun run) {
        return parsed.text().substring(run.start(), run.end());
    }

    @Test
    void boldItalicAndCodeBecomeRunsOverThePlainText() {
        var parsed = parse("a **bold** and *italic* and `code` word");
        assertEquals("a bold and italic and code word", parsed.text());
        assertEquals(3, parsed.runs().size());
        assertTrue(parsed.runs().get(0).bold());
        assertEquals("bold", covered(parsed, parsed.runs().get(0)));
        assertTrue(parsed.runs().get(1).italic());
        assertEquals("italic", covered(parsed, parsed.runs().get(1)));
        assertTrue(parsed.runs().get(2).code());
        assertEquals("code", covered(parsed, parsed.runs().get(2)));
    }

    @Test
    void aLinkKeepsItsTextAndItsAddress() {
        var parsed = parse("see [the guide](https://example.com/x) now");
        assertEquals("see the guide now", parsed.text());
        InlineRun link = parsed.runs().getFirst();
        assertEquals("https://example.com/x", link.href());
        assertEquals("the guide", covered(parsed, link));
    }

    @Test
    void emphasisInsideALinkCarriesTheLink() {
        var parsed = parse("[a **big** step](https://example.com)");
        assertEquals("a big step", parsed.text());
        assertEquals(3, parsed.runs().size());
        assertTrue(parsed.runs().stream().allMatch(r -> "https://example.com".equals(r.href())));
        assertTrue(parsed.runs().get(1).bold());
        assertEquals("big", covered(parsed, parsed.runs().get(1)));
    }

    @Test
    void threeMarksAreBoldAndItalic() {
        var parsed = parse("***both***");
        assertEquals("both", parsed.text());
        assertTrue(parsed.runs().getFirst().bold() && parsed.runs().getFirst().italic());
    }

    @Test
    void italicInsideBoldNests() {
        var parsed = parse("**bold *and italic* too**");
        assertEquals("bold and italic too", parsed.text());
        assertEquals(3, parsed.runs().size());
        assertTrue(parsed.runs().get(1).bold() && parsed.runs().get(1).italic());
        assertEquals("and italic", covered(parsed, parsed.runs().get(1)));
    }

    @Test
    void marksThatDoNotCloseOrHaveSpacesStayAsWritten() {
        assertEquals("2 * 3 * 4", parse("2 * 3 * 4").text());
        assertTrue(parse("2 * 3 * 4").runs().isEmpty());
        assertEquals("**open", parse("**open").text());
        assertEquals("snake_case_name", parse("snake_case_name").text());
        assertTrue(parse("snake_case_name").runs().isEmpty());
        assertEquals("[not a link]", parse("[not a link]").text());
    }

    @Test
    void marksInsideCodeAreLiteralAndABackslashEscapes() {
        var parsed = parse("`a **b** c` and \\*plain\\*");
        assertEquals("a **b** c and *plain*", parsed.text());
        assertEquals(1, parsed.runs().size());
        assertTrue(parsed.runs().getFirst().code());
    }

    @Test
    void underscoresWorkLikeAsterisks() {
        var parsed = parse("__strong__ and _soft_");
        assertEquals("strong and soft", parsed.text());
        assertTrue(parsed.runs().get(0).bold());
        assertTrue(parsed.runs().get(1).italic());
    }

    @Test
    void theMarkdownPresetPutTheRunsOnItsBlocks() {
        List<FormattedBlock> blocks = new MarkdownFormatPreset().parse("# A **big** title\n\n- an `item`\n\nplain *text*");
        assertEquals(3, blocks.size());
        assertEquals("A big title", blocks.get(0).text());
        assertTrue(blocks.get(0).runs().getFirst().bold());
        assertTrue(blocks.get(1).runs().getFirst().code());
        assertTrue(blocks.get(2).runs().getFirst().italic());
    }
}
