package me.piitex.engine.ui.file;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.IconOverlay;
import me.piitex.engine.ui.overlays.ImageOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class UiFileTest {
    @TempDir
    Path temp;

    private static Container root() {
        Container root = (Container) UiKinds.get("container").create("Root");
        root.setWidth(400);
        root.setHeight(300);
        return root;
    }

    private static Element add(Container parent, String kind, String name) {
        Element element = UiKinds.get(kind).create(name);
        parent.addElement(element);
        return element;
    }

    @Test
    void everyKindMakesWritesAndReadsBackTheSame() {
        Container root = root();
        for (UiKind kind : UiKinds.all()) add(root, kind.getId(), kind.getId() + "1");
        String first = UiFile.toJson(root);
        Container again = UiFile.parse(first, Path.of(""));
        assertEquals(first, UiFile.toJson(again));
        assertEquals(UiKinds.all().size(), again.getElements().size());
    }

    @Test
    void everyElementKnowsItsKind() {
        for (UiKind kind : UiKinds.all()) {
            Element element = kind.create("n");
            assertSame(kind, UiKinds.of(element), kind.getId());
            assertEquals("n", UiFile.getName(element));
        }
    }

    @Test
    void valuesComeBack() {
        Container root = root();
        ButtonOverlay button = (ButtonOverlay) add(root, "button", "play");
        UiKinds.get("button").getProperty("text").set(button, "Play now");
        UiKinds.get("button").getProperty("fontSize").set(button, 22);
        UiKinds.get("button").getProperty("x").set(button, 30);
        UiKinds.get("button").getProperty("y").set(button, 40.5);
        SliderOverlay slider = (SliderOverlay) add(root, "slider", "volume");
        UiKinds.get("slider").getProperty("max").set(slider, 10);
        UiKinds.get("slider").getProperty("value").set(slider, 7);

        Container again = UiFile.parse(UiFile.toJson(root), Path.of(""));
        ButtonOverlay b = UiFile.find(again, "play", ButtonOverlay.class);
        assertEquals("Play now", b.getText());
        assertEquals(22f, b.getFontSize());
        assertEquals(30, b.getX());
        assertEquals(40.5, b.getY());
        SliderOverlay s = UiFile.find(again, "volume", SliderOverlay.class);
        assertEquals(10, s.getMax());
        assertEquals(7, s.getValue());
    }

    @Test
    void aThemedValueIsWrittenOnlyOnceSetAndGivenBackByReset() {
        Container root = root();
        Element button = add(root, "button", "b");
        UiKind kind = UiKinds.get("button");
        UiProperty background = kind.getProperty("background"), border = kind.getProperty("border");
        assertFalse(UiFile.write(button).containsKey("background"));

        background.set(button, new Color(1f, 0f, 0f, 1f));
        border.set(button, new Color(0f, 1f, 0f, 1f));
        Map<String, Object> json = UiFile.write(button);
        assertTrue(json.containsKey("background"));
        assertTrue(json.containsKey("border"));

        background.reset(button);
        json = UiFile.write(button);
        assertFalse(json.containsKey("background"));
        assertTrue(json.containsKey("border"), "the others stay set");
        assertEquals(List.of(0f, 1f, 0f, 1f), UiFile.write(button).get("border"));
    }

    @Test
    void anAnchoredElementKeepsItsAnchorAndOffsets() {
        Container root = root();
        Element button = add(root, "button", "corner");
        UiKind kind = UiKinds.get("button");
        kind.getProperty("anchor").set(button, UiAnchor.BOTTOM_RIGHT);
        kind.getProperty("offsetX").set(button, -16);
        kind.getProperty("offsetY").set(button, -10);
        assertEquals(400 - button.getWidth() - 16, button.getX(), 1e-6);
        assertEquals(300 - button.getHeight() - 10, button.getY(), 1e-6);

        Map<String, Object> json = UiFile.write(button);
        assertEquals("BOTTOM_RIGHT", json.get("anchor"));
        assertEquals(-16.0, ((Number) json.get("offsetX")).doubleValue(), 1e-6);
        assertFalse(json.containsKey("x"), "an anchored element's position is the anchor's");

        Container again = UiFile.parse(UiFile.toJson(root), Path.of(""));
        Element b = UiFile.find(again, "corner");
        assertEquals(UiAnchor.BOTTOM_RIGHT, UiAnchor.of(b.getAnchor()));
        assertEquals(button.getX(), b.getX(), 1e-6);
        assertEquals(button.getY(), b.getY(), 1e-6);

        // Resizing the container moves it, as an anchor should.
        again.setWidth(800);
        b.applyAnchor();
        assertEquals(800 - b.getWidth() - 16, b.getX(), 1e-6);

        kind.getProperty("anchor").set(button, UiAnchor.NONE);
        assertNull(button.getAnchor());
        assertTrue(UiFile.write(button).containsKey("x"));
    }

    @Test
    void anElementInALayoutHasNoPositionOfItsOwn() {
        VerticalLayout column = (VerticalLayout) add(root(), "vertical", "column");
        Element text = add(column, "text", "label");
        Map<String, Object> json = UiFile.write(text);
        assertFalse(json.containsKey("x"));
        assertFalse(json.containsKey("anchor"));
        assertFalse(json.containsKey("width"), "text works out its own size");
        assertTrue(UiKinds.get("text").getProperty("x") != null);
        assertFalse(UiKinds.get("text").getProperty("x").appliesTo(text));
    }

    @Test
    void layoutsKeepTheirSettingsAndChildrenInOrder() {
        Container root = root();
        VerticalLayout column = (VerticalLayout) add(root, "vertical", "column");
        UiKind kind = UiKinds.get("vertical");
        kind.getProperty("spacing").set(column, 20);
        kind.getProperty("alignment").set(column, Layout.Alignment.CENTER);
        add(column, "text", "first");
        add(column, "button", "second");
        add(column, "text", "third");

        Container again = UiFile.parse(UiFile.toJson(root), Path.of(""));
        VerticalLayout c = UiFile.find(again, "column", VerticalLayout.class);
        assertEquals(20f, c.getSpacing());
        assertEquals(Layout.Alignment.CENTER, c.getAlignment());
        List<String> names = c.getElements().values().stream().map(UiFile::getName).toList();
        assertEquals(List.of("first", "second", "third"), names);
    }

    @Test
    void anImagePathIsKeptEvenWhenTheFileIsntThere() {
        Container root = root();
        Element image = add(root, "image", "logo");
        UiKinds.get("image").getProperty("image").set(image, "assets/missing.png");
        assertEquals("assets/missing.png", UiFile.write(image).get("image"));
        Container again = UiFile.parse(UiFile.toJson(root), Path.of("nowhere"));
        assertEquals("assets/missing.png", UiFile.write(UiFile.find(again, "logo")).get("image"));
        assertInstanceOf(ImageOverlay.class, UiFile.find(again, "logo"));
    }

    @Test
    void anIconIsNamedAndCanBeChanged() {
        Container root = root();
        Element icon = add(root, "icon", "go");
        assertEquals(UiIcons.DEFAULT, UiFile.write(icon).get("icon"));
        assertTrue(UiIcons.names().contains("arrow-right"));
        UiKinds.get("icon").getProperty("icon").set(icon, "arrow-right");
        Container again = UiFile.parse(UiFile.toJson(root), Path.of(""));
        assertEquals("arrow-right", UiFile.write(UiFile.find(again, "go", IconOverlay.class)).get("icon"));
    }

    @Test
    void aTintIsWrittenOnlyWhenSetAndResetClearsIt() {
        Container root = root();
        Element icon = add(root, "icon", "go");
        UiProperty tint = UiKinds.get("icon").getProperty("tint");
        assertFalse(UiFile.write(icon).containsKey("tint"));
        tint.set(icon, new Color(1f, 0f, 0f, 1f));
        assertEquals(List.of(1f, 0f, 0f, 1f), UiFile.write(icon).get("tint"));
        Container again = UiFile.parse(UiFile.toJson(root), Path.of(""));
        assertNotNull(((IconOverlay) UiFile.find(again, "go")).getTint());
        tint.reset(icon);
        assertNull(((IconOverlay) icon).getTint());
        assertFalse(UiFile.write(icon).containsKey("tint"));
    }

    @Test
    void aDropdownKeepsItsItemsAndChoice() {
        Container root = root();
        DropdownOverlay<String> dropdown = (DropdownOverlay<String>) add(root, "dropdown", "pick");
        UiKind kind = UiKinds.get("dropdown");
        kind.getProperty("items").set(dropdown, List.of("a", "b", "c", "d"));
        kind.getProperty("selected").set(dropdown, 2);
        Container again = UiFile.parse(UiFile.toJson(root), Path.of(""));
        @SuppressWarnings("unchecked") DropdownOverlay<String> d = UiFile.find(again, "pick", DropdownOverlay.class);
        assertEquals(List.of("a", "b", "c", "d"), d.getItems());
        assertEquals(2, d.getSelectedIndex());
    }

    @Test
    void anUnknownKindIsWrittenBackAsItWas() {
        String json = """
                {"format": 1, "root": {"kind": "container", "name": "r", "width": 100, "height": 100, "children": [
                  {"kind": "hologram", "name": "future", "depth": 3, "children": [{"kind": "text", "text": "x"}]}
                ]}}""";
        Container root = UiFile.parse(json, Path.of(""));
        Element future = UiFile.find(root, "future");
        assertNotNull(future);
        Map<String, Object> written = UiFile.write(future);
        assertEquals("hologram", written.get("kind"));
        assertEquals(3.0, ((Number) written.get("depth")).doubleValue());
        assertTrue(written.get("children") instanceof List<?> l && l.size() == 1);
    }

    @Test
    void badFilesAreErrorsThatSayWhat() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> UiFile.parse("[1]", Path.of("")));
        assertThrows(IllegalArgumentException.class, () -> UiFile.parse("{\"format\": 1}", Path.of("")));
        IllegalArgumentException newer = assertThrows(IllegalArgumentException.class, () -> UiFile.parse("{\"format\": 99, \"root\": {}}", Path.of("")));
        assertTrue(newer.getMessage().contains("newer"));
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class, () -> UiFile.parse(
                "{\"format\": 1, \"root\": {\"kind\": \"container\", \"name\": \"r\", \"width\": \"wide\"}}", Path.of("")));
        assertTrue(wrong.getMessage().contains("width"), wrong.getMessage());
        assertThrows(IllegalArgumentException.class, () -> UiFile.parse(
                "{\"format\": 1, \"root\": {\"kind\": \"slider\", \"name\": \"s\"}}", Path.of("")), "the root must be a container");
    }

    @Test
    void savesAndLoadsAFile() throws IOException {
        Container root = root();
        add(root, "text", "title");
        Path file = temp.resolve("layouts/menu.json");
        UiFile.save(root, file);
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(temp.resolve("layouts/menu.json.tmp")));
        Container loaded = UiFile.load(file, temp);
        assertEquals("Root", UiFile.getName(loaded));
        assertInstanceOf(TextOverlay.class, UiFile.find(loaded, "title"));
    }

    @Test
    void findSaysWhatWentWrong() {
        Container root = root();
        add(root, "text", "title");
        assertNull(UiFile.find(root, "nope"));
        assertThrows(IllegalArgumentException.class, () -> UiFile.find(root, "nope", TextOverlay.class));
        assertThrows(IllegalArgumentException.class, () -> UiFile.find(root, "title", ButtonOverlay.class));
        assertSame(root, UiFile.find(root, "Root"));
    }

    @Test
    void aCopyIsSeparateButTheSame() {
        Container root = root();
        VerticalLayout column = (VerticalLayout) add(root, "vertical", "column");
        add(column, "button", "b");
        Element copy = UiFile.copy(column, Path.of(""));
        assertNotSame(column, copy);
        // The copy isn't in a container, so it has no position of its own to write.
        Map<String, Object> expected = UiFile.write(column);
        expected.remove("x");
        expected.remove("y");
        assertEquals(expected, UiFile.write(copy));
        assertNotSame(UiFile.find(column, "b"), UiFile.find(copy, "b"));
    }
}
