package me.piitex.engine.ui.file;

import me.piitex.engine.ui.text.FormattedBlock;
import me.piitex.engine.ui.text.MarkdownFormatPreset;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class UiDocsTest {
    private static final Path DOCS = Path.of("src/main/resources/me/piitex/engine/ui/file/docs");

    @Test
    void everyKindIsDocumentedWithASummary() {
        for (UiKind kind : UiKinds.all()) {
            assertTrue(UiDocs.isWritten(kind), kind.getId() + " has no documentation in " + DOCS);
            String summary = UiDocs.summary(kind);
            assertTrue(summary.length() >= 10, kind.getId() + " needs a summary of what it is");
            assertTrue(summary.endsWith("."), kind.getId() + "'s summary should be a sentence: " + summary);
        }
    }

    @Test
    void everyPropertyOfEveryKindHasATooltip() {
        for (UiKind kind : UiKinds.all()) {
            for (UiProperty property : kind.getProperties()) {
                assertFalse(property.getTooltip().isBlank(), kind.getId() + "." + property.getName() + " has no tooltip");
            }
        }
    }

    @Test
    void everyDocumentFileIsForAKind() throws IOException {
        Set<String> ids = new HashSet<>();
        for (UiKind kind : UiKinds.all()) ids.add(kind.getId());
        try (Stream<Path> files = Files.list(DOCS)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                assertTrue(name.endsWith(".md"), name);
                String id = name.substring(0, name.length() - 3);
                assertTrue(id.equals("_common") || ids.contains(id), name + " is not for any kind, so it is never shown");
            }
        }
    }

    @Test
    void aPageHasItsNameItsWordsAndItsOwnProperties() {
        String page = UiDocs.page(UiKinds.get("button"));
        assertTrue(page.startsWith("# Button\n"));
        assertTrue(page.contains("## Settings"));
        assertTrue(page.contains("**Text On Hover** (`textHover`)"), "a property of its own");
        assertTrue(page.contains("**On Click** (`onAction`)"), "its event");
        assertFalse(page.contains("(`cornerRadius`)"), "what every element has is on its own page");
    }

    @Test
    void whatAPropertyCanBeIsInWords() {
        String slider = UiDocs.page(UiKinds.get("percent"));
        assertTrue(slider.contains("From 0 to 100."), slider);
        assertFalse(UiDocs.page(UiKinds.get("slider")).contains("1000000"), "a limit that is only out of the way is not said");
        String grid = UiDocs.page(UiKinds.get("vertical"));
        assertTrue(grid.contains("One of TOP_LEFT"), "an enum lists its choices");
    }

    @Test
    void theCommonPageHasWhatEveryElementHas() {
        String page = UiDocs.commonPage();
        assertTrue(page.startsWith("# Every Element"));
        for (String name : List.of("x", "width", "anchor", "opacity", "tooltip", "background", "border", "cornerRadius", "effect", "panel")) {
            assertTrue(page.contains("(`" + name + "`)"), name + " is on every element");
        }
        assertFalse(page.contains("(`text`)"), "a kind's own property doesn't belong");
    }

    @Test
    void aKindOfYourOwnHasAPageFromItsProperties() {
        UiKind own = new UiKind("docstest", "Docs Test", "Display", me.piitex.engine.ui.overlays.TextOverlay.class,
                () -> new me.piitex.engine.ui.overlays.TextOverlay("x"), List.of());
        assertFalse(UiDocs.isWritten(own));
        assertEquals("", UiDocs.summary(own));
        assertTrue(UiDocs.page(own).startsWith("# Docs Test"));
        UiDocs.register("docstest", "Shows a test.\n\nMore.");
        assertEquals("Shows a test.", UiDocs.summary(own));
    }

    @Test
    void everyPageReadsAsPlainText() {
        MarkdownFormatPreset preset = new MarkdownFormatPreset();
        List<String> pages = new java.util.ArrayList<>(UiKinds.all().stream().map(UiDocs::page).toList());
        pages.add(UiDocs.commonPage());
        for (String page : pages) {
            for (FormattedBlock block : preset.parse(page)) {
                String text = block.text();
                assertFalse(text.contains("**") || text.contains("`") || text.contains("]("),
                        "markup the preset doesn't take out is left in: " + text);
                if (block.background() == null) assertFalse(text.startsWith("|"), "tables are not supported: " + text);
            }
        }
    }
}
