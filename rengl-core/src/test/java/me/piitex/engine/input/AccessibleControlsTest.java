package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.access.AccessibilityHost;
import me.piitex.engine.access.AccessibilityTree;
import me.piitex.engine.access.AccessibleNode;
import me.piitex.engine.access.AccessibleRole;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.AccordionContainer;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ListBox;
import me.piitex.engine.ui.containers.TabContainer;
import me.piitex.engine.ui.containers.TableView;
import me.piitex.engine.ui.containers.TreeView;
import me.piitex.engine.ui.overlays.ColorPickerOverlay;
import me.piitex.engine.ui.overlays.GaugeOverlay;
import me.piitex.engine.ui.overlays.KnobOverlay;
import me.piitex.engine.ui.overlays.RangeSliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The composite controls (knob, range slider, list, tree, table, tabs, accordion) say what they are and what they hold,
 * and tell an {@link AccessibilityHost} when the keyboard changes it.
 */
class AccessibleControlsTest {
    private final Window window = new Window(new WindowOptions("test").setDimensions(600, 600));
    private final InputProcessor processor = new InputProcessor();
    private final Container panel = new Container(0, 0, 600, 600);
    private final List<String> heard = new ArrayList<>();

    AccessibleControlsTest() {
        window.addContainer(panel);
        window.setAccessibilityHost(new AccessibilityHost() {
            @Override
            public void valueChanged(Window w, Element element) {
                heard.add(element.getAccessibleRole() + " " + element.getAccessibleValue());
            }

            @Override
            public void focusChanged(Window w, Element previous, Element now, boolean byKeyboard) {
            }

            @Override
            public void announce(Window w, String text, boolean urgent) {
                heard.add("announce " + text);
            }

            @Override
            public void close() {
            }
        });
    }

    private <E extends Element> E put(E element, double x, double y) {
        element.setPosition(x, y);
        panel.addElement(element);
        return element;
    }

    private void key(int key) {
        key(key, 0);
    }

    private void key(int key, int mods) {
        window.getInputManager().onKey(0, key, 0, GLFW.GLFW_PRESS, mods);
        processor.processEvents(window);
        window.getInputManager().onKey(0, key, 0, GLFW.GLFW_RELEASE, mods);
        processor.processEvents(window);
    }

    @Test
    void aKnobIsASliderThatTheKeyboardMovesAndSpeaks() {
        KnobOverlay knob = put(new KnobOverlay(60, 0, 10), 10, 10);
        knob.setStep(1);
        window.requestFocus(knob);
        assertEquals(AccessibleRole.SLIDER, knob.getAccessibleRole());
        assertFalse(knob.grabsKeyboard(), "like a slider it leaves the other keys to the game");
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_END);
        key(GLFW.GLFW_KEY_END); // already there: nothing to say
        assertEquals(List.of("SLIDER 1", "SLIDER 2", "SLIDER 10"), heard);
        knob.setValueByUser(4);
        assertEquals("4", knob.getAccessibleValue());
        assertEquals("SLIDER 4", heard.getLast());
    }

    @Test
    void aRangeSliderReadsBothEndsAndSaysWhichThumbIsMoving() {
        RangeSliderOverlay range = put(new RangeSliderOverlay(200, 0, 100), 10, 10);
        range.setStep(10);
        range.setRange(20, 80);
        window.requestFocus(range);
        assertEquals("20 to 80", range.getAccessibleValue());
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_SPACE);
        key(GLFW.GLFW_KEY_LEFT);
        assertEquals(List.of("SLIDER 30 to 80", "announce Adjusting the upper value", "SLIDER 30 to 70"), heard);
    }

    @Test
    void aListSaysTheRowItIsOn() {
        ListBox<String> list = put(new ListBox<>(150, 100), 10, 10);
        list.setItems(List.of("Sword", "Shield", "Potion"));
        window.requestFocus(list);
        assertEquals(AccessibleRole.LIST, list.getAccessibleRole());
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(List.of("LIST Sword", "LIST Shield"), heard);
    }

    @Test
    void aTreeSaysTheItemAndWhetherItIsOpen() {
        TreeView<String> tree = put(new TreeView<String>(150, 100, item -> item.equals("Weapons") ? List.of("Sword") : List.of()), 10, 10);
        tree.setRoots(List.of("Weapons"));
        window.requestFocus(tree);
        assertEquals(AccessibleRole.TREE, tree.getAccessibleRole());
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_RIGHT); // opens it
        key(GLFW.GLFW_KEY_RIGHT); // into the first child
        assertEquals(List.of("TREE Weapons, collapsed", "TREE Weapons, expanded", "TREE Sword"), heard);
    }

    @Test
    void aTableReadsTheRowAfterItsColumnTitles() {
        TableView<String[]> table = put(new TableView<>(300, 100), 10, 10);
        table.addColumn("Name", row -> row[0]);
        table.addColumn("Price", row -> row[1]);
        table.setItems(List.of(new String[]{"Sword", "40"}, new String[]{"Shield", "25"}));
        window.requestFocus(table);
        assertEquals(AccessibleRole.TABLE, table.getAccessibleRole());
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(List.of("TABLE Name: Sword, Price: 40", "TABLE Name: Shield, Price: 25"), heard);
    }

    @Test
    void theTabStripSaysWhichTabAndWhere() {
        TabContainer tabs = put(new TabContainer(300, 100), 10, 10);
        tabs.addTab("Graphics", new TextOverlay("g", 12f, me.piitex.engine.ui.color.Color.BLACK));
        tabs.addTab("Audio", new TextOverlay("a", 12f, me.piitex.engine.ui.color.Color.BLACK));
        AccessibleNode strip = find(AccessibilityTree.snapshot(window), AccessibleRole.TAB_LIST);
        assertNotNull(strip, "the strip is in the tree");
        assertEquals("Graphics, tab 1 of 2", strip.value());
        window.requestFocus(strip.element());
        key(GLFW.GLFW_KEY_RIGHT);
        assertEquals(List.of("TAB_LIST Audio, tab 2 of 2"), heard);
    }

    @Test
    void anAccordionSaysTheSectionAndWhetherItIsOpen() {
        AccordionContainer accordion = put(new AccordionContainer(300), 10, 10);
        accordion.addSection("Graphics", new TextOverlay("g", 12f, me.piitex.engine.ui.color.Color.BLACK));
        accordion.addSection("Audio", new TextOverlay("a", 12f, me.piitex.engine.ui.color.Color.BLACK));
        window.requestFocus(accordion);
        key(GLFW.GLFW_KEY_ENTER);
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(List.of("GROUP Graphics, expanded, section 1 of 2", "GROUP Audio, collapsed, section 2 of 2"), heard);
    }

    @Test
    void aGaugeIsReadAsAMeter() {
        GaugeOverlay gauge = put(new GaugeOverlay(80, 0, 100), 10, 10);
        gauge.setValue(42);
        assertEquals(AccessibleRole.PROGRESS_BAR, gauge.getAccessibleRole());
        assertEquals("42", gauge.getAccessibleValue());
    }

    @Test
    void aColorPickerIsChangedChannelByChannelAndSaysTheColorsName() {
        ColorPickerOverlay picker = put(new ColorPickerOverlay(120, 30, me.piitex.engine.ui.color.Color.BLUE), 10, 10);
        window.requestFocus(picker);
        assertFalse(picker.grabsKeyboard(), "a game keeps its keys when the picker was only clicked");
        assertEquals("#0000FF, blue", picker.getAccessibleValue());
        assertNotNull(picker.getAccessibleDescription());
        key(GLFW.GLFW_KEY_LEFT); // hue 240 -> 230
        key(GLFW.GLFW_KEY_DOWN); // to saturation
        key(GLFW.GLFW_KEY_LEFT, GLFW.GLFW_MOD_SHIFT); // 100% -> 80%
        key(GLFW.GLFW_KEY_HOME); // no colour left: white
        key(GLFW.GLFW_KEY_HOME); // already there: nothing to say
        assertEquals(4, heard.size(), heard.toString());
        assertTrue(heard.get(0).startsWith("BUTTON #00") && heard.get(0).endsWith(", blue"), heard.get(0));
        assertEquals("announce Saturation, 100 percent", heard.get(1));
        assertTrue(heard.get(2).endsWith(", blue") || heard.get(2).endsWith(", pale blue"), heard.get(2));
        assertEquals("BUTTON #FFFFFF, white", heard.get(3));
    }

    @Test
    void aColorPickerIsReachedWithTabAndChangesWithTheArrowKeys() {
        ColorPickerOverlay picker = put(new ColorPickerOverlay(120, 30), 10, 10);
        key(GLFW.GLFW_KEY_TAB);
        assertSame(picker, window.getFocusedElement());
        key(GLFW.GLFW_KEY_RIGHT);
        assertEquals(1, heard.size(), heard.toString());
    }

    @Test
    void aColorPickerOpenedWithTheMouseSaysSo() {
        ColorPickerOverlay picker = put(new ColorPickerOverlay(120, 30, me.piitex.engine.ui.color.Color.BLUE), 10, 10);
        picker.toggleOpen();
        picker.toggleOpen();
        assertEquals(List.of("announce Colour panel open, #0000FF, blue", "announce Colour panel closed"), heard);
    }

    @Test
    void aColorPickerStepsThroughItsPresets() {
        ColorPickerOverlay picker = put(new ColorPickerOverlay(120, 30), 10, 10);
        window.requestFocus(picker);
        key(GLFW.GLFW_KEY_PAGE_DOWN);
        key(GLFW.GLFW_KEY_PAGE_DOWN);
        key(GLFW.GLFW_KEY_PAGE_UP);
        assertEquals(List.of("BUTTON #E53935, red", "BUTTON #FB8C00, orange", "BUTTON #E53935, red"), heard);
    }

    @Test
    void aColorPickersPanelTakesTheKeyboardForTheHexCodeAndGivesItBack() {
        ColorPickerOverlay picker = put(new ColorPickerOverlay(120, 30), 10, 10);
        window.requestFocus(picker);
        key(GLFW.GLFW_KEY_ENTER);
        assertTrue(picker.isOpen(), "Enter opens the panel");
        assertNotSame(picker, window.getFocusedElement(), "the cursor is in the hex field");
        assertNotNull(window.getFocusedElement());
        key(GLFW.GLFW_KEY_ESCAPE);
        assertFalse(picker.isOpen(), "Escape closes it");
        assertSame(picker, window.getFocusedElement(), "and the picker has the keyboard again");
    }

    private static AccessibleNode find(List<AccessibleNode> nodes, AccessibleRole role) {
        for (AccessibleNode node : nodes) {
            if (node.role() == role) return node;
            AccessibleNode inside = find(node.children(), role);
            if (inside != null) return inside;
        }
        return null;
    }
}
