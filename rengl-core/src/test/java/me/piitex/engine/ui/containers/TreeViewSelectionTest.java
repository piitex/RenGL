package me.piitex.engine.ui.containers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import me.piitex.engine.ui.events.MouseClickEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TreeViewSelectionTest {
    /** A tree whose modifier keys are set by the test. */
    private static final class Tree extends TreeView<String> {
        int mods;

        Tree() {
            super(300, 240, item -> switch (item) {
                case "folder" -> List.of("a", "b", "c");
                case "b" -> List.of("b1", "b2");
                default -> List.of();
            });
        }

        @Override
        protected int heldModifiers() {
            return mods;
        }
    }

    private Tree tree;
    private final List<String> leads = new ArrayList<>();
    private final List<List<String>> changes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tree = new Tree();
        tree.setRoots(List.of("folder", "x", "y", "z"));
        tree.expand("folder");
        tree.onSelect(leads::add);
        tree.onSelectionChange(changes::add);
        // rows: folder, a, b, c, x, y, z
    }

    private void click(int row) {
        double y = row * 24 + 12;
        tree.onDragStart(100, y);
        tree.onDragEnd(100, y);
    }

    private void click(int row, int mods) {
        tree.mods = mods;
        click(row);
        tree.mods = 0;
    }

    private void rightClick(int row) {
        tree.getMouseClickListenerConsumer().accept(new MouseClickEvent(100, row * 24 + 12, GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS));
    }

    @Test
    void singleSelectIgnoresModifiers() {
        click(1);
        click(3, GLFW.GLFW_MOD_SHIFT);
        assertEquals(List.of("c"), tree.getSelectedItems());
        click(1, GLFW.GLFW_MOD_CONTROL);
        assertEquals(List.of("a"), tree.getSelectedItems());
    }

    @Test
    void clickingSelectsOneRow() {
        tree.setMultiSelect(true);
        click(1);
        click(4);
        assertEquals(List.of("x"), tree.getSelectedItems());
        assertEquals("x", tree.getSelected());
        assertEquals(List.of("a", "x"), leads);
    }

    @Test
    void commandClickAddsAndRemoves() {
        tree.setMultiSelect(true);
        click(1);
        click(4, GLFW.GLFW_MOD_SUPER);
        click(6, GLFW.GLFW_MOD_CONTROL);
        assertEquals(List.of("a", "x", "z"), tree.getSelectedItems());
        assertEquals("z", tree.getSelected());

        click(4, GLFW.GLFW_MOD_SUPER); // off again
        assertEquals(List.of("a", "z"), tree.getSelectedItems());
        assertEquals(List.of("a", "z"), changes.getLast());
    }

    @Test
    void shiftClickSelectsTheRangeFromTheAnchor() {
        tree.setMultiSelect(true);
        click(2);
        click(5, GLFW.GLFW_MOD_SHIFT);
        assertEquals(List.of("b", "c", "x", "y"), tree.getSelectedItems());
        click(1, GLFW.GLFW_MOD_SHIFT); // the anchor stays on b
        assertEquals(List.of("a", "b"), tree.getSelectedItems());
    }

    @Test
    void shiftCommandClickAddsARangeToWhatIsSelected() {
        tree.setMultiSelect(true);
        click(0);
        click(4);
        click(6, GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_CONTROL);
        assertEquals(List.of("x", "y", "z"), tree.getSelectedItems());
        click(1, GLFW.GLFW_MOD_CONTROL);
        click(3, GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_CONTROL);
        assertEquals(List.of("a", "b", "c", "x", "y", "z"), tree.getSelectedItems());
    }

    @Test
    void shiftArrowsExtendFromTheAnchor() {
        tree.setMultiSelect(true);
        click(1);
        tree.onKeyPressed(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
        tree.onKeyPressed(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
        assertEquals(List.of("a", "b", "c"), tree.getSelectedItems());
        tree.onKeyPressed(GLFW.GLFW_KEY_UP, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
        assertEquals(List.of("a", "b"), tree.getSelectedItems());
        tree.onKeyPressed(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0);
        assertEquals(List.of("c"), tree.getSelectedItems(), "a plain arrow moves one row from the lead");
    }

    @Test
    void commandAselectsEveryRowShown() {
        tree.setMultiSelect(true);
        click(2);
        tree.onKeyPressed(GLFW.GLFW_KEY_A, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SUPER);
        assertEquals(tree.getVisibleItems(), tree.getSelectedItems());
        assertEquals(7, changes.getLast().size());
    }

    @Test
    void pressingASelectedRowKeepsTheSelectionUntilReleased() {
        tree.setMultiSelect(true);
        tree.setDragEnabled(true);
        click(1);
        click(3, GLFW.GLFW_MOD_SHIFT);
        assertEquals(List.of("a", "b", "c"), tree.getSelectedItems());

        tree.onDragStart(100, 2 * 24 + 12); // press b
        assertEquals(List.of("a", "b", "c"), tree.getSelectedItems(), "still all three while the button is down");
        tree.onDragUpdate(100, 5 * 24 + 12);
        tree.onDragEnd(100, 5 * 24 + 12);
        assertEquals(List.of("a", "b", "c"), tree.getSelectedItems(), "a drag keeps them");

        click(2); // press and release without moving
        assertEquals(List.of("b"), tree.getSelectedItems());
    }

    @Test
    void closingAnItemWithSelectedChildrenSelectsIt() {
        tree.setMultiSelect(true);
        click(1);
        click(3, GLFW.GLFW_MOD_CONTROL);
        tree.collapse("folder");
        assertEquals(List.of("folder"), tree.getSelectedItems());
    }

    @Test
    void refreshForgetsRowsThatAreGone() {
        tree.setMultiSelect(true);
        click(4);
        click(5, GLFW.GLFW_MOD_CONTROL);
        tree.setRoots(List.of("folder", "y"));
        assertEquals(List.of("y"), tree.getSelectedItems());
        assertEquals("y", tree.getSelected());
    }

    @Test
    void settersAreSilentAndRevealTheItems() {
        tree.setMultiSelect(true);
        tree.setSelectedItems(List.of("b1", "x", "missing"));
        assertEquals(List.of("b1", "x"), tree.getSelectedItems());
        assertTrue(tree.isExpanded("b"), "b1 is shown");
        assertEquals("x", tree.getSelected());
        assertTrue(leads.isEmpty() && changes.isEmpty());

        tree.clearSelection();
        assertTrue(tree.getSelectedItems().isEmpty());
        assertNull(tree.getSelected());
    }

    @Test
    void turningMultiSelectOffKeepsTheLead() {
        tree.setMultiSelect(true);
        click(1);
        click(3, GLFW.GLFW_MOD_CONTROL);
        tree.setMultiSelect(false);
        assertEquals(List.of("c"), tree.getSelectedItems());
    }

    @Test
    void rightClickKeepsASelectionItIsInside() {
        tree.setMultiSelect(true);
        click(1);
        click(3, GLFW.GLFW_MOD_CONTROL);
        // right-click on a selected row keeps the selection; on b, which is not selected, it replaces it
        rightClick(3);
        assertEquals(List.of("a", "c"), tree.getSelectedItems());
        rightClick(2);
        assertEquals(List.of("b"), tree.getSelectedItems());
    }

    @Test
    void checkBoxesAreIndependentOfTheSelection() {
        tree.setCheckBoxes(true);
        List<String> ticked = new ArrayList<>();
        tree.onCheck((item, on) -> ticked.add(item + (on ? "+" : "-")));
        // the box sits just past the arrow: depth 1 -> x = 4 + 2 * 16 + 2
        double boxX = 4 + 2 * 16 + 2 + 5;
        tree.onDragStart(boxX, 1 * 24 + 12);
        tree.onDragEnd(boxX, 1 * 24 + 12);
        assertEquals(List.of("a"), tree.getCheckedItems());
        assertTrue(tree.getSelectedItems().isEmpty());
        tree.onDragStart(boxX, 1 * 24 + 12);
        tree.onDragEnd(boxX, 1 * 24 + 12);
        assertEquals(List.of("a+", "a-"), ticked);
        assertTrue(tree.getCheckedItems().isEmpty());
    }

    @Test
    void cascadeTicksEverythingUnder() {
        tree.setCheckBoxes(true);
        tree.setCascadeChecks(true);
        tree.expandAll();
        tree.setChecked("folder", true);
        assertEquals(List.of("folder", "a", "b", "b1", "b2", "c").stream().sorted().toList(),
                tree.getCheckedItems().stream().sorted().toList());
        tree.setChecked("b", false);
        assertFalse(tree.isChecked("b1"));
        assertTrue(tree.isChecked("c"));
        tree.clearChecked();
        assertTrue(tree.getCheckedItems().isEmpty());
    }

    @Test
    void spaceTicksTheLeadRow() {
        tree.setCheckBoxes(true);
        click(4);
        tree.onKeyPressed(GLFW.GLFW_KEY_SPACE, GLFW.GLFW_PRESS, 0);
        assertEquals(List.of("x"), tree.getCheckedItems());
    }
}
