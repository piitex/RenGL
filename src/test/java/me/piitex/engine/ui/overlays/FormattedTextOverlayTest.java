package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.text.BracketFormatPreset;
import me.piitex.engine.ui.text.MarkdownFormatPreset;
import me.piitex.engine.ui.text.TextStyle;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class FormattedTextOverlayTest {
    private static final Color RED = new Color(1f, 0f, 0f, 1f);

    @AfterEach
    void putTheThemeBack() {
        ThemeManager.setCurrent(null);
    }

    private static <T extends Element> List<T> of(FormattedTextOverlay overlay, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Element element : overlay.getElements().values()) if (type.isInstance(element)) found.add(type.cast(element));
        found.sort((a, b) -> Double.compare(a.getY(), b.getY()));
        return found;
    }

    private static FormattedTextOverlay make(String source) {
        MarkdownFormatPreset preset = new MarkdownFormatPreset();
        preset.setBodyStyle(new TextStyle(14f, false, false, RED));
        FormattedTextOverlay overlay = new FormattedTextOverlay(400, 300, preset);
        overlay.setSource(source);
        return overlay;
    }

    @Test
    void aStylesColorHoldsEvenWhenTheThemeIsAnother() {
        ThemeManager.setCurrent(new DarkTheme());
        FormattedTextOverlay overlay = make("Some text.");
        TextFlowOverlay text = of(overlay, TextFlowOverlay.class).getFirst();
        assertEquals(RED.getR(), ((Color) text.getTextColor()).getR());
        assertEquals(0f, ((Color) text.getTextColor()).getG());
        text.applyTheme(new DarkTheme());
        assertEquals(0f, ((Color) text.getTextColor()).getG(), "a theme applied later doesn't take it back");
    }

    @Test
    void aListItemHasItsMarkerBesideItsText() {
        FormattedTextOverlay overlay = make("- first\n- second");
        List<TextFlowOverlay> items = of(overlay, TextFlowOverlay.class);
        assertEquals(2, items.size());
        List<TextOverlay> markers = of(overlay, TextOverlay.class).stream().filter(t -> !(t instanceof TextFlowOverlay)).toList();
        assertEquals(2, markers.size());
        assertEquals("•", markers.getFirst().getText());
        assertTrue(items.getFirst().getX() > markers.getFirst().getX() + markers.getFirst().getWidth() - 1, "the text starts after the marker");
        assertTrue(items.getFirst().getWidth() < 400, "and is narrower for it");
        assertTrue(items.get(1).getY() > items.getFirst().getY());
    }

    @Test
    void aCodeBlockIsDrawnOnABackgroundWithPadding() {
        FormattedTextOverlay overlay = make("```\nline one\nline two\n```");
        TextFlowOverlay code = of(overlay, TextFlowOverlay.class).getFirst();
        assertNotNull(code.getStyling().getBackgroundColor());
        assertEquals(8f, code.getPadding());
        assertTrue(code.getHeight() > 2 * 12, "two lines and the padding");
    }

    @Test
    void buildingAgainTakesTheOldBlocksAway() {
        FormattedTextOverlay overlay = make("- a\n- b\n\nparagraph");
        int first = overlay.getElements().size();
        overlay.setSource("- a\n- b\n\nparagraph");
        assertEquals(first, overlay.getElements().size(), "markers go with their blocks");
        overlay.setSource("");
        assertTrue(of(overlay, TextFlowOverlay.class).isEmpty());
        assertTrue(of(overlay, TextOverlay.class).isEmpty());
    }

    @Test
    void changingTheWidthWrapsAgainWithoutParsing() {
        int[] parses = {0};
        MarkdownFormatPreset real = new MarkdownFormatPreset();
        FormattedTextOverlay overlay = new FormattedTextOverlay(400, 300, source -> {
            parses[0]++;
            return real.parse(source);
        });
        overlay.setSource("word ".repeat(80));
        assertEquals(1, parses[0]);
        TextFlowOverlay text = of(overlay, TextFlowOverlay.class).getFirst();
        double wide = text.getHeight();
        overlay.setWidth(120);
        assertEquals(1, parses[0], "no second parse");
        assertSame(text, of(overlay, TextFlowOverlay.class).getFirst(), "the same block, wrapped again");
        assertTrue(text.getHeight() > wide, "narrower is taller");
        overlay.setWidth(400);
        assertEquals(wide, text.getHeight(), 0.5);
    }

    @Test
    void thePresetCanBeSwitchedAfterwards() {
        FormattedTextOverlay overlay = make("# Title\n\ntext");
        assertEquals("Title", of(overlay, TextFlowOverlay.class).getFirst().getText());
        BracketFormatPreset bracket = new BracketFormatPreset();
        overlay.setPreset(bracket);
        assertSame(bracket, overlay.getPreset());
        assertEquals("# Title", of(overlay, TextFlowOverlay.class).getFirst().getText(), "the same source, read the new way");
        assertThrows(NullPointerException.class, () -> overlay.setPreset(null));
    }

    @Test
    void inlineMarksReachTheBlocksAsRuns() {
        FormattedTextOverlay overlay = make("a **bold** [link](https://example.com) word");
        TextFlowOverlay text = of(overlay, TextFlowOverlay.class).getFirst();
        assertEquals("a bold link word", text.getText());
        assertEquals(2, text.getRuns().size());
    }

    @Test
    void aClickOnALinkOpensItsAddressAndOneElsewhereDoesNot() {
        FormattedTextOverlay overlay = make("go [here](https://example.com/a) now");
        List<String> opened = new ArrayList<>();
        overlay.setLinkHandler(opened::add);
        TextFlowOverlay text = of(overlay, TextFlowOverlay.class).getFirst();
        double here = text.getOrBuildFont().measureTextWidth("go ") + text.getX();
        assertEquals("https://example.com/a", text.hrefAt(here + 3, text.getY() + 3));
        assertNull(text.hrefAt(text.getX() + 1, text.getY() + 3), "'go' is not part of it");
        assertNull(text.hrefAt(here + 3, text.getY() + 300), "nor is below the text");
    }

    @Test
    void aBlockWithNoLinkDoesNotTakeClicks() {
        FormattedTextOverlay overlay = make("a **bold** word");
        assertNull(of(overlay, TextFlowOverlay.class).getFirst().getMouseClickListenerConsumer());
    }
}
