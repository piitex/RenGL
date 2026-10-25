package me.piitex.engine.ui.containers;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.text.TextAlign;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Check boxes in a table, and editing a cell in place. Presses are fed to the table the way the window does. */
class TableEditingTest {
    private static final class Entry {
        String name;
        long size;

        Entry(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private TableView<Entry> table;
    private TableView.Column<Entry> name, size;
    private final Entry a = new Entry("alpha", 5), b = new Entry("bravo", 900), c = new Entry("charlie", 70), d = new Entry("delta", 3);
    private final List<String> checks = new ArrayList<>(), edits = new ArrayList<>(), activations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        table = new TableView<>(400, 200);
        name = table.addColumn("Name", e -> e.name).setWidth(150).setEditable((e, text) -> {
            edits.add(e.name + "->" + text);
            e.name = text;
        });
        size = table.addColumn("Size", e -> e.size + " B").setWidth(100).setAlignment(TextAlign.RIGHT)
                .setEditText(e -> String.valueOf(e.size));
        table.setItems(List.of(a, b, c, d));
        table.onCheck((e, on) -> checks.add(e.name + (on ? "+" : "-")));
        table.onActivate(e -> activations.add(e.name));
    }

    private static double rowY(int row) {
        return 28 + row * 24 + 12;
    }

    private void press(double x, double y) {
        table.onDragStart(x, y);
        table.onDragEnd(x, y);
    }

    private void key(int key) {
        table.onKeyPressed(key, GLFW.GLFW_PRESS, 0);
    }

    // ---- check boxes --------------------------------------------------------------------------------------------

    @Test
    void aTableHasNoCheckBoxesUntilAskedFor() {
        assertFalse(table.isCheckBoxes());
        press(15, rowY(1));
        assertEquals(b, table.getSelected(), "without boxes the first 30 points are just the Name column");
        assertTrue(checks.isEmpty());
    }

    @Test
    void aClickOnTheBoxTicksTheRowAndLeavesTheSelectionAlone() {
        table.setCheckBoxes(true);
        press(15, rowY(1));
        assertTrue(table.isChecked(b));
        assertNull(table.getSelected(), "ticking is not selecting");
        assertEquals(List.of("bravo+"), checks);
        press(15, rowY(1));
        assertFalse(table.isChecked(b));
        assertEquals(List.of("bravo+", "bravo-"), checks);
    }

    @Test
    void theBoxColumnPushesTheOthersRight() {
        table.setCheckBoxes(true);
        press(40, rowY(2)); // inside the Name column now, which starts at 30
        assertEquals(c, table.getSelected());
        assertTrue(checks.isEmpty());
        // sorting by the Name header still works from the new place
        table.onDragStart(60, 14);
        table.onDragEnd(60, 14);
        assertSame(name, table.getSortColumn());
    }

    @Test
    void theHeaderBoxTicksEverythingThenClearsIt() {
        table.setCheckBoxes(true);
        press(15, 14);
        assertEquals(List.of(a, b, c, d), table.getCheckedItems());
        assertEquals(List.of("alpha+", "bravo+", "charlie+", "delta+"), checks);
        assertNull(table.getSortColumn(), "the header's box does not sort");

        checks.clear();
        press(15, 14);
        assertTrue(table.getCheckedItems().isEmpty());
        assertEquals(4, checks.size());
    }

    @Test
    void aHeaderBoxWithSomeTickedTicksTheRest() {
        table.setCheckBoxes(true);
        table.setChecked(b, true);
        press(15, 14);
        assertEquals(List.of(a, b, c, d), table.getCheckedItems());
        assertEquals(List.of("alpha+", "charlie+", "delta+"), checks, "only the rows that changed are reported");
    }

    @Test
    void spaceTicksTheLeadRow() {
        table.setCheckBoxes(true);
        table.setSelected(c);
        key(GLFW.GLFW_KEY_SPACE);
        assertTrue(table.isChecked(c));
        key(GLFW.GLFW_KEY_SPACE);
        assertFalse(table.isChecked(c));
        assertEquals(List.of("charlie+", "charlie-"), checks);
    }

    @Test
    void spaceDoesNothingWithoutBoxes() {
        table.setSelected(c);
        key(GLFW.GLFW_KEY_SPACE);
        assertFalse(table.isChecked(c));
    }

    @Test
    void tickedItemsAreListedInRowOrderAndSurviveASort() {
        table.setCheckBoxes(true);
        table.setChecked(d, true);
        table.setChecked(a, true);
        assertEquals(List.of(a, d), table.getCheckedItems());
        table.sortBy(name, false);
        assertEquals(List.of(d, a), table.getCheckedItems());
    }

    @Test
    void refreshForgetsTicksOfRowsThatAreGone() {
        List<Entry> rows = new ArrayList<>(List.of(a, b, c));
        table.setItems(rows);
        table.setCheckBoxes(true);
        table.checkAll();
        assertEquals(3, table.getCheckedItems().size());
        rows.remove(b);
        table.refresh();
        assertEquals(List.of(a, c), table.getCheckedItems());
        table.setItems(List.of(d));
        assertTrue(table.getCheckedItems().isEmpty());
    }

    @Test
    void theSettersAreSilentAndSkipRowsThatAreNotInTheTable() {
        table.setCheckBoxes(true);
        table.setChecked(a, true);
        table.setChecked(new Entry("stranger", 1), true);
        assertEquals(List.of(a), table.getCheckedItems());
        table.setCheckedItems(List.of(c, d));
        assertEquals(List.of(c, d), table.getCheckedItems());
        table.clearChecked();
        assertTrue(table.getCheckedItems().isEmpty());
        assertTrue(checks.isEmpty());
    }

    @Test
    void theBoxesWidenWhatTheTableScrollsAcross() {
        name.setWidth(350); // 450 of columns in a table 400 wide
        double before = table.getMaxScrollX();
        assertEquals(50, before, 1e-6);
        table.setCheckBoxes(true);
        assertEquals(before + 30, table.getMaxScrollX(), 1e-6, "30 points more of columns");
    }

    private static int ink(Bitmap bmp, int x0, int y0, int x1, int y1) {
        int count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (bmp.getColor(x, y) != 0xFFFFFFFF) count++;
            }
        }
        return count;
    }

    @Test
    void aTickedBoxIsDrawnWithATickInIt() {
        table.setCheckBoxes(true);
        table.setPosition(0, 0);
        table.setChecked(b, true);
        int w = 400, h = 200;
        try (OffscreenRenderer r = new OffscreenRenderer(w, h, 1f)) {
            r.begin();
            r.drawQuad(0, 0, w, h, Color.WHITE, 0f);
            table.render(r);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                int empty = ink(bmp, 6, 28 + 24 + 4, 24, 28 + 48 - 4);       // row 1, ticked
                int plain = ink(bmp, 6, 28 + 24 * 2 + 4, 24, 28 + 24 * 3 - 4); // row 2, not
                assertTrue(plain > 20, "an empty box still has its outline: " + plain);
                assertTrue(empty > plain + 10, "the tick adds to it: " + empty + " against " + plain);
            }
        }
    }

    // ---- editing ------------------------------------------------------------------------------------------------

    @Test
    void aColumnIsReadOnlyUntilItIsEditable() {
        assertTrue(name.isEditable());
        assertFalse(size.isEditable());
        table.startEdit(a, size);
        assertFalse(table.isEditing());
        table.startEdit(new Entry("stranger", 1), name);
        assertFalse(table.isEditing(), "a row that is not in the table cannot be edited");
    }

    @Test
    void startingAnEditPutsAFieldOverTheCellWithItsTextSelected() {
        table.startEdit(b, name);
        assertTrue(table.isEditing());
        assertSame(b, table.getEditingItem());
        assertSame(b, table.getSelected(), "the row being edited is selected");
        TextFieldOverlay field = table.getEditor();
        assertEquals("bravo", field.getText());
        assertTrue(field.hasSelection());
        assertEquals(0, field.getX(), 1e-6);
        assertEquals(28 + 24, field.getY(), 1e-6);
        assertEquals(150, field.getWidth(), 1e-6);
        assertEquals(24, field.getHeight(), 1e-6);
    }

    @Test
    void enterEndsTheEditAndReportsTheNewText() {
        table.startEdit(b, name);
        table.getEditor().setText("brave");
        table.getEditor().onKeyPressed(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0);
        assertFalse(table.isEditing());
        assertEquals(List.of("bravo->brave"), edits);
        assertEquals("brave", b.name);
    }

    @Test
    void escapeThrowsTheEditAway() {
        table.startEdit(b, name);
        table.getEditor().setText("brave");
        table.getEditor().onKeyPressed(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0);
        assertFalse(table.isEditing());
        assertTrue(edits.isEmpty());
        assertEquals("bravo", b.name);
    }

    @Test
    void leavingTheFieldKeepsWhatWasTyped() {
        table.startEdit(b, name);
        table.getEditor().setText("brave");
        table.getEditor().onFocusLost();
        assertFalse(table.isEditing());
        assertEquals(List.of("bravo->brave"), edits);
    }

    @Test
    void anEditThatChangedNothingReportsNothing() {
        table.startEdit(b, name);
        table.getEditor().onKeyPressed(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0);
        assertTrue(edits.isEmpty());
    }

    @Test
    void theFieldStartsWithTheEditTextWhenThereIsOne() {
        size.setEditable((e, text) -> e.size = Long.parseLong(text));
        table.startEdit(b, size);
        assertEquals("900", table.getEditor().getText(), "not the '900 B' the cell shows");
        table.getEditor().setText("901");
        table.commitEdit();
        assertEquals(901, b.size);
    }

    @Test
    void aDoubleClickOnAnEditableCellEditsIt() {
        press(40, rowY(2));
        press(40, rowY(2));
        assertTrue(table.isEditing());
        assertSame(c, table.getEditingItem());
        assertTrue(activations.isEmpty(), "the double-click edited rather than opened the row");
    }

    @Test
    void aDoubleClickOnACellThatCannotBeEditedStillActivates() {
        press(200, rowY(2)); // the Size column
        press(200, rowY(2));
        assertFalse(table.isEditing());
        assertEquals(List.of("charlie"), activations);
    }

    @Test
    void f2EditsTheFirstEditableColumnOfTheSelectedRow() {
        key(GLFW.GLFW_KEY_F2);
        assertFalse(table.isEditing(), "nothing is selected");
        table.setSelected(d);
        key(GLFW.GLFW_KEY_F2);
        assertTrue(table.isEditing());
        assertSame(d, table.getEditingItem());
        table.cancelEdit();
        assertFalse(table.isEditing());
    }

    @Test
    void theFieldFollowsItsRowThroughASort() {
        table.startEdit(a, name); // alpha is first
        table.sortBy(name, false); // now last
        table.update(0.016f);
        assertEquals(28 + 3 * 24, table.getEditor().getY(), 1e-6);
    }

    @Test
    void anEditEndsWhenItsRowGoesAway() {
        List<Entry> rows = new ArrayList<>(List.of(a, b, c));
        table.setItems(rows);
        table.startEdit(b, name);
        rows.remove(b);
        table.refresh();
        table.update(0.016f);
        assertFalse(table.isEditing());
        assertTrue(edits.isEmpty(), "a row that is gone has nothing to report to");
    }

    @Test
    void newItemsEndAnEditWithoutReporting() {
        table.startEdit(b, name);
        table.getEditor().setText("brave");
        table.setItems(List.of(a));
        assertFalse(table.isEditing());
        assertTrue(edits.isEmpty());
    }

    @Test
    void startingASecondEditKeepsTheFirstOnesText() {
        table.startEdit(a, name);
        table.getEditor().setText("apple");
        table.startEdit(c, name);
        assertEquals(List.of("alpha->apple"), edits);
        assertSame(c, table.getEditingItem());
    }

    @Test
    void checkBoxesMoveTheFieldOverTheCell() {
        table.setCheckBoxes(true);
        table.startEdit(b, name);
        assertEquals(30, table.getEditor().getX(), 1e-6);
    }

    @Test
    void turningBoxesOnEndsAnEdit() {
        table.startEdit(b, name);
        table.setCheckBoxes(true);
        assertFalse(table.isEditing());
    }

    @Test
    void aRightAlignedColumnEditsRightAligned() {
        size.setEditable((e, text) -> { });
        table.startEdit(a, size);
        assertTrue(table.getEditor().isRightAligned());
        table.cancelEdit();
        table.startEdit(a, name);
        assertFalse(table.getEditor().isRightAligned());
    }

    @Test
    void removingTheColumnEndsTheEdit() {
        table.startEdit(a, name);
        table.removeColumn(name);
        assertFalse(table.isEditing());
    }
}
