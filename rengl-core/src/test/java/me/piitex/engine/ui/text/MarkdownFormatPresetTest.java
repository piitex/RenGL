package me.piitex.engine.ui.text;

import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class MarkdownFormatPresetTest {
    private final MarkdownFormatPreset preset = new MarkdownFormatPreset();

    private List<FormattedBlock> parse(String source) {
        return preset.parse(source);
    }

    @Test
    void headingsAndParagraphsAreAsTheyWere() {
        List<FormattedBlock> blocks = parse("# Title\n\nFirst line\nsecond line\n\n#tag is not a heading");
        assertEquals(3, blocks.size());
        assertEquals("Title", blocks.get(0).text());
        assertTrue(blocks.get(0).style().bold());
        assertEquals("First line second line", blocks.get(1).text());
        assertEquals("#tag is not a heading", blocks.get(2).text());
        assertEquals("", blocks.get(1).marker());
        assertNull(blocks.get(1).background());
    }

    @Test
    void bulletsAndNumbersBecomeItemsWithMarkers() {
        List<FormattedBlock> blocks = parse("Intro\n\n- one\n* two\n  carries on\n\n1. first\n2. second");
        assertEquals(5, blocks.size());
        assertEquals("•", blocks.get(1).marker());
        assertEquals("one", blocks.get(1).text());
        assertEquals("two carries on", blocks.get(2).text());
        assertEquals("1.", blocks.get(3).marker());
        assertEquals("2.", blocks.get(4).marker());
        assertTrue(blocks.get(1).indent() > 0, "an item is in from the left");
        assertTrue(blocks.get(2).spacingBefore() < blocks.get(1).spacingBefore(), "items sit closer than a list from its paragraph");
    }

    @Test
    void anItemTwoSpacesInSitsOneLevelDeeper() {
        List<FormattedBlock> blocks = parse("- top\n  - inside");
        assertEquals(2, blocks.size());
        assertTrue(blocks.get(1).indent() > blocks.get(0).indent());
    }

    @Test
    void fencedCodeKeepsItsLinesAndGetsABackground() {
        List<FormattedBlock> blocks = parse("Before\n\n```json\n{\n  \"a\": 1\n}\n```\n\nAfter");
        assertEquals(3, blocks.size());
        FormattedBlock code = blocks.get(1);
        assertEquals("{\n  \"a\": 1\n}", code.text());
        assertNotNull(code.background());
        assertEquals("After", blocks.get(2).text());
    }

    @Test
    void headingsAndListsInsideCodeAreLeftAlone() {
        List<FormattedBlock> blocks = parse("```\n# not a heading\n- not an item\n```");
        assertEquals(1, blocks.size());
        assertEquals("# not a heading\n- not an item", blocks.getFirst().text());
    }

    @Test
    void aFenceThatIsNeverClosedRunsToTheEnd() {
        List<FormattedBlock> blocks = parse("```\nline");
        assertEquals(1, blocks.size());
        assertEquals("line", blocks.getFirst().text());
    }

    @Test
    void inlineMarksAreTakenOutAndWhatTheyWrapIsKept() {
        assertEquals("a bold word", MarkdownFormatPreset.inline("a **bold** word"));
        assertEquals("an italic word", MarkdownFormatPreset.inline("an *italic* word"));
        assertEquals("set size to 12", MarkdownFormatPreset.inline("set `size` to 12"));
        assertEquals("see the guide", MarkdownFormatPreset.inline("see [the guide](https://example.com/x)"));
        assertEquals("2 * 3 * 4", MarkdownFormatPreset.inline("2 * 3 * 4"), "spaced asterisks are not emphasis");
        assertEquals("snake_case_name", MarkdownFormatPreset.inline("snake_case_name"));
    }

    @Test
    void aRuleEndsTheParagraphAndDrawsNothing() {
        List<FormattedBlock> blocks = parse("above\n---\nbelow");
        assertEquals(2, blocks.size());
    }

    @Test
    void nothingGivesNothing() {
        assertTrue(parse("").isEmpty());
        assertTrue(parse(null).isEmpty());
        assertTrue(parse("```\n```").isEmpty());
    }
}
