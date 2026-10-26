package me.piitex.engine.ui.containers;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.events.MouseClickEvent;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ListBoxTest {
    /** A list whose modifier keys are set by the test. */
    private static final class Box extends ListBox<String> {
        int mods;

        Box() {
            super(200, 130);
        }

        @Override
        protected int heldModifiers() {
            return mods;
        }
    }

    private Box box;
    private final List<String> leads = new ArrayList<>();
    private final List<List<String>> changes = new ArrayList<>();
    private final List<String> activated = new ArrayList<>();

    @BeforeEach
    void setUp() {
        box = new Box();
        box.setItems(List.of("apple", "banana", "blueberry", "cherry", "date"));
        box.onSelect(leads::add);
        box.onSelectionChange(changes::add);
        box.onActivate(activated::add);
    }

    // Rows are 26 tall, with no header.
    private void click(int row) {
        double y = row * 26 + 13;
        box.onDragStart(50, y);
        box.onDragEnd(50, y);
    }

    private void key(int key) {
        box.onKeyPressed(key, GLFW.GLFW_PRESS, 0);
    }

    @Test
    void clickingARowSelectsItAndReportsIt() {
        click(1);
        assertEquals("banana", box.getSelected());
        assertEquals(1, box.getSelectedIndex());
        assertEquals(List.of("banana"), leads);
        assertEquals(List.of(List.of("banana")), changes);
    }

    @Test
    void clickingTheSameRowAgainDoesNotReportAgain() {
        click(2);
        click(2);
        assertEquals(List.of("blueberry"), leads);
    }

    @Test
    void clickingBelowTheLastRowClearsTheSelection() {
        click(0);
        box.onDragStart(50, 5 * 26 + 5);
        assertNull(box.getSelected());
        assertEquals(java.util.Arrays.asList("apple", null), leads);
    }

    @Test
    void theSettersAreSilent() {
        box.setSelected("cherry");
        box.setSelectedIndex(4);
        assertEquals("date", box.getSelected());
        assertTrue(leads.isEmpty());
        assertTrue(changes.isEmpty());
    }

    @Test
    void arrowKeysMoveTheSelection() {
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals("apple", box.getSelected());
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals("blueberry", box.getSelected());
        key(GLFW.GLFW_KEY_UP);
        assertEquals("banana", box.getSelected());
        key(GLFW.GLFW_KEY_END);
        assertEquals("date", box.getSelected());
        key(GLFW.GLFW_KEY_DOWN); // already last
        assertEquals("date", box.getSelected());
        key(GLFW.GLFW_KEY_HOME);
        assertEquals("apple", box.getSelected());
    }

    @Test
    void upWithNothingSelectedStartsFromTheEnd() {
        key(GLFW.GLFW_KEY_UP);
        assertEquals("date", box.getSelected());
    }

    @Test
    void enterAndDoubleClickActivate() {
        click(3);
        click(3); // the second press, at once, is the double-click
        assertEquals(List.of("cherry"), activated);
        key(GLFW.GLFW_KEY_ENTER);
        assertEquals(List.of("cherry", "cherry"), activated);
    }

    @Test
    void twoClicksOnDifferentRowsAreNotADoubleClick() {
        click(0);
        click(1);
        assertTrue(activated.isEmpty());
    }

    @Test
    void withoutMultiSelectModifiersDoNothingSpecial() {
        box.mods = GLFW.GLFW_MOD_CONTROL;
        click(0);
        click(2);
        assertEquals(List.of("blueberry"), box.getSelectedItems());
    }

    @Test
    void controlClickTogglesAndShiftClickSelectsARange() {
        box.setMultiSelect(true);
        click(0);
        box.mods = GLFW.GLFW_MOD_CONTROL;
        click(2);
        assertEquals(List.of("apple", "blueberry"), box.getSelectedItems());
        click(0); // already selected: toggles off
        assertEquals(List.of("blueberry"), box.getSelectedItems());
        box.mods = 0;
        click(2); // a plain click replaces the selection and sets the anchor
        box.mods = GLFW.GLFW_MOD_SHIFT;
        click(4);
        assertEquals(List.of("blueberry", "cherry", "date"), box.getSelectedItems());
        assertEquals("date", box.getSelected());
    }

    @Test
    void controlAAndShiftArrowSelectManyWhenAllowed() {
        box.setMultiSelect(true);
        box.onKeyPressed(GLFW.GLFW_KEY_A, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL);
        assertEquals(5, box.getSelectedItems().size());
        click(1);
        box.onKeyPressed(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
        box.onKeyPressed(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
        assertEquals(List.of("banana", "blueberry", "cherry"), box.getSelectedItems());
    }

    @Test
    void turningMultiSelectOffKeepsTheLeadRow() {
        box.setMultiSelect(true);
        click(0);
        box.mods = GLFW.GLFW_MOD_CONTROL;
        click(3);
        box.setMultiSelect(false);
        assertEquals(List.of("cherry"), box.getSelectedItems());
    }

    @Test
    void aFilterHidesRowsAndDropsTheirSelection() {
        box.setMultiSelect(true);
        click(0);
        box.mods = GLFW.GLFW_MOD_CONTROL;
        click(1);
        box.setFilter(s -> s.startsWith("b"));
        assertEquals(List.of("banana", "blueberry"), box.getItems());
        assertEquals(5, box.getAllItems().size());
        assertEquals(List.of("banana"), box.getSelectedItems());
        box.setFilter(null);
        assertEquals(5, box.size());
        assertEquals(List.of("banana"), box.getSelectedItems());
    }

    @Test
    void addAndRemoveKeepTheSelectionByEquals() {
        click(2);
        box.addItem("elderberry");
        assertEquals("blueberry", box.getSelected());
        assertTrue(box.removeItem("blueberry"));
        assertNull(box.getSelected());
        assertFalse(box.removeItem("blueberry"));
    }

    @Test
    void typingJumpsToTheMatchingRowAndRepeatingTheLetterCycles() {
        box.onCharTyped('b');
        assertEquals("banana", box.getSelected());
        box.onCharTyped('b'); // the same letter again: the next row starting with it
        assertEquals("blueberry", box.getSelected());
    }

    @Test
    void typingSeveralLettersNarrowsToThePrefix() {
        box.onCharTyped('b');
        box.onCharTyped('l');
        assertEquals("blueberry", box.getSelected());
        box.onCharTyped('x'); // nothing starts with "blx"
        assertEquals("blueberry", box.getSelected());
    }

    @Test
    void theListScrollsToKeepTheSelectedRowInView() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add("item " + i);
        box.setItems(many);
        assertEquals(100 * 26 - 130, box.getMaxScrollY(), 0.001);
        box.setSelectedIndex(50);
        assertEquals(50 * 26 + 26 - 130, box.getScrollOffsetY(), 0.001);
        box.setSelectedIndex(0);
        assertEquals(0, box.getScrollOffsetY(), 0.001);
        key(GLFW.GLFW_KEY_END);
        assertEquals(box.getMaxScrollY(), box.getScrollOffsetY(), 0.001);
        assertEquals("item 99", box.getSelected());
    }

    @Test
    void aScrolledListPicksTheRowUnderThePointer() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add("item " + i);
        box.setItems(many);
        box.scrollToIndex(40);
        double offset = box.getScrollOffsetY();
        click(0); // row 0 of the view, whichever item is there now
        assertEquals("item " + (int) Math.floor((13 + offset) / 26), box.getSelected());
    }

    @Test
    void rightClickSelectsTheRowUnderThePointerFirst() {
        box.setMultiSelect(true);
        click(0);
        box.mods = GLFW.GLFW_MOD_CONTROL;
        click(1);
        box.mods = 0;
        box.getMouseClickListenerConsumer().accept(new MouseClickEvent(50, 3 * 26 + 13, GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS));
        assertEquals(List.of("cherry"), box.getSelectedItems());
        // Right-clicking inside the selection keeps it.
        box.setMultiSelect(true);
        box.setSelectedItems(List.of("apple", "banana"));
        box.getMouseClickListenerConsumer().accept(new MouseClickEvent(50, 13, GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS));
        assertEquals(List.of("apple", "banana"), box.getSelectedItems());
    }

    @Test
    void aKeyListenerSeesKeysFirst() {
        List<Integer> seen = new ArrayList<>();
        box.onKey((key, mods) -> {
            seen.add(key);
            return key == GLFW.GLFW_KEY_DELETE;
        });
        click(1);
        key(GLFW.GLFW_KEY_DELETE);
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(List.of(GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_DOWN), seen);
        assertEquals("blueberry", box.getSelected());
    }

    @Test
    void rowsDrawTheSelectionAndTheTextWithoutFailing() {
        box.useTheme();
        box.setSelectionColor(Color.BLUE);
        box.setTextColor(Color.RED);
        box.setSelected("banana");
        try (OffscreenRenderer r = new OffscreenRenderer(200, 130, 1f)) {
            r.begin();
            box.render(r);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                int selected = bmp.getColor(180, 26 + 13), other = bmp.getColor(180, 13);
                assertNotEquals(selected, other, "the selected row is a different color from its neighbour");
                assertTrue((selected & 0xFF) > ((selected >> 16) & 0xFF), "and it is the blue one");
                bmp.close();
            }
        }
        box.dispose();
    }

    @Test
    void aCustomRowRendererIsGivenEachVisibleRow() {
        List<String> drawn = new ArrayList<>();
        box.setRowRenderer((renderer, item, x, y, w, h, selected, hovered) -> drawn.add(item));
        try (OffscreenRenderer r = new OffscreenRenderer(200, 130, 1f)) {
            r.begin();
            box.render(r);
            r.end();
        }
        assertEquals(List.of("apple", "banana", "blueberry", "cherry", "date"), drawn);
    }
}
