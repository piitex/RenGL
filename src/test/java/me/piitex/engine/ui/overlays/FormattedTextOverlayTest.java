package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
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
}
