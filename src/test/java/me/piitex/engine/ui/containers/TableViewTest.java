package me.piitex.engine.ui.containers;

import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.text.TextAlign;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableViewTest {
    private record Entry(String name, long size) {
    }

    private static final class Table extends TableView<Entry> {
        int mods;

        Table() {
            super(400, 200);
        }

        @Override
        protected int heldModifiers() {
            return mods;
        }
    }

    private Table table;
    private TableView.Column<Entry> name, size;
    private final List<List<Entry>> changes = new ArrayList<>();
    private final Entry a = new Entry("file10.txt", 5), b = new Entry("File2.txt", 900), c = new Entry("alpha.txt", 70), d = new Entry("zeta.txt", 3);

    @BeforeEach
    void setUp() {
        table = new Table();
        name = table.addColumn("Name", Entry::name).setWidth(150);
        size = table.addColumn("Size", e -> e.size() + " B").setWidth(100).setAlignment(TextAlign.RIGHT)
                .setComparator(Comparator.comparingLong(Entry::size));
        table.setItems(List.of(a, b, c, d));
        table.onSelectionChange(changes::add);
    }

    // y of the middle of row i: the header takes 28 and rows are 24
    private static double rowY(int row) {
        return 28 + row * 24 + 12;
    }

    private void click(int row) {
        table.onDragStart(20, rowY(row));
        table.onDragEnd(20, rowY(row));
    }

    private void clickHeader(double x) {
        table.onDragStart(x, 14);
        table.onDragEnd(x, 14);
    }

    @Test
    void rowsAreInTheOrderGiven() {
        assertEquals(List.of(a, b, c, d), table.getItems());
    }

    @Test
    void clickingAHeaderSortsThenReverses() {
        clickHeader(40); // Name
        assertEquals(List.of(c, b, a, d), table.getItems(), "case-insensitive and file2 before file10");
        assertSame(name, table.getSortColumn());
        assertTrue(table.isSortAscending());

        clickHeader(40);
        assertEquals(List.of(d, a, b, c), table.getItems());
        assertFalse(table.isSortAscending());

        clickHeader(200); // Size, by its comparator
        assertEquals(List.of(d, a, c, b), table.getItems());
        assertTrue(table.isSortAscending());
    }

    @Test
    void sortListenerHearsUserSortsOnly() {
        List<String> heard = new ArrayList<>();
        table.onSort((column, ascending) -> heard.add(column.getTitle() + (ascending ? "^" : "v")));
        table.sortBy(size, false);
        assertEquals(List.of(), heard);
        assertEquals(List.of(b, c, a, d), table.getItems());
        clickHeader(200);
        assertEquals(List.of("Size^"), heard, "a click on the sorted column's header starts again from ascending only if it was another column");
    }

    @Test
    void sortSurvivesRefreshAndClearGoesBack() {
        table.sortBy(size, true);
        table.setItems(List.of(a, b, c, d, new Entry("new", 1)));
        assertEquals(1, table.getItems().getFirst().size());
        table.clearSort();
        assertEquals(List.of(a, b, c, d, new Entry("new", 1)), table.getItems());
    }

    @Test
    void unsortableColumnsIgnoreClicks() {
        name.setSortable(false);
        clickHeader(40);
        assertNull(table.getSortColumn());
    }

    @Test
    void draggingAnEdgeResizesAndASlightMoveDoesNotSort() {
        table.onDragStart(150, 14); // the Name/Size edge
        table.onDragUpdate(190, 14);
        table.onDragEnd(190, 14);
        assertEquals(190f, name.getWidth());
        assertNull(table.getSortColumn(), "a resize is not a click");

        table.onDragStart(150 + 40, 14);
        table.onDragUpdate(0, 14);
        table.onDragEnd(0, 14);
        assertEquals(name.getMinWidth(), name.getWidth(), "never narrower than the minimum");
    }

    @Test
    void aFixedWidthColumnCannotBeResized() {
        name.setResizable(false);
        table.onDragStart(150, 14);
        table.onDragUpdate(250, 14);
        table.onDragEnd(250, 14);
        assertEquals(150f, name.getWidth());
    }

    @Test
    void headerDoesNotSelectARow() {
        click(1);
        clickHeader(40);
        assertEquals(b, table.getSelected(), "the header is not a row");
    }

    @Test
    void clickingSelectsAndMultiSelectWorks() {
        click(1);
        assertEquals(List.of(b), table.getSelectedItems());
        table.setMultiSelect(true);
        table.mods = GLFW.GLFW_MOD_SUPER;
        click(3);
        table.mods = 0;
        assertEquals(List.of(b, d), table.getSelectedItems());
        table.mods = GLFW.GLFW_MOD_SHIFT;
        click(2);
        table.mods = 0;
        assertEquals(List.of(c, d), table.getSelectedItems(), "a range from the anchor (the last Cmd-clicked row) replaces the rest");
    }

    @Test
    void selectionFollowsItemsAcrossASort() {
        table.setMultiSelect(true);
        click(0);
        table.mods = GLFW.GLFW_MOD_CONTROL;
        click(2);
        table.mods = 0;
        table.sortBy(size, true);
        assertEquals(List.of(a, c), table.getSelectedItems());
    }

    @Test
    void shiftArrowsAndSelectAll() {
        table.setMultiSelect(true);
        click(0);
        table.onKeyPressed(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
        table.onKeyPressed(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
        assertEquals(List.of(a, b, c), table.getSelectedItems());
        table.onKeyPressed(GLFW.GLFW_KEY_A, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL);
        assertEquals(4, table.getSelectedItems().size());
    }

    @Test
    void enterAndDoubleClickActivate() {
        List<Entry> opened = new ArrayList<>();
        table.onActivate(opened::add);
        click(2);
        click(2);
        assertEquals(List.of(c), opened);
        table.onKeyPressed(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0);
        assertEquals(List.of(c, c), opened);
    }

    @Test
    void refreshDropsGoneItemsFromTheSelection() {
        click(1);
        table.setItems(List.of(a, c));
        assertNull(table.getSelected());
        assertTrue(table.getSelectedItems().isEmpty());
    }

    @Test
    void rightClickKeepsTheSelectionItIsInside() {
        table.setMultiSelect(true);
        click(0);
        table.mods = GLFW.GLFW_MOD_CONTROL;
        click(2);
        table.mods = 0;
        table.getMouseClickListenerConsumer().accept(new MouseClickEvent(20, rowY(2), GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS));
        assertEquals(List.of(a, c), table.getSelectedItems());
        table.getMouseClickListenerConsumer().accept(new MouseClickEvent(20, rowY(1), GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS));
        assertEquals(List.of(b), table.getSelectedItems());
    }

    @Test
    void aClickHandlerOfYoursRunsAfterTheTablesOwnAndDoesNotReplaceIt() {
        List<Integer> seen = new ArrayList<>();
        table.onMouseClick(event -> seen.add(table.getSelectedItems().size()));
        table.getMouseClickListenerConsumer().accept(new MouseClickEvent(20, rowY(1), GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS));
        assertEquals(List.of(b), table.getSelectedItems(), "the right-click still selects the row");
        assertEquals(List.of(1), seen, "and the handler saw the selection already made");
    }

    @Test
    void scrollRangeCoversHeaderRowsAndColumns() {
        assertEquals(0, table.getMaxScrollX(), "250 of columns fit in 400");
        size.setWidth(400);
        assertEquals(150 + 400 - 400, table.getMaxScrollX());
        assertEquals(0, table.getMaxScrollY());
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) many.add(new Entry("e" + i, i));
        table.setItems(many);
        assertEquals(28 + 20 * 24 - 200, table.getMaxScrollY());
    }

    @Test
    void selectingScrollsTheRowIntoView() {
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(new Entry("e" + i, i));
        table.setItems(many);
        table.setSelected(many.get(30));
        assertEquals(28 + 31 * 24 - 200, table.getScrollOffsetY());
        table.setSelected(many.get(0));
        assertEquals(0, table.getScrollOffsetY());
    }

    @Test
    void naturalOrder() {
        assertTrue(TableView.compareNatural("file2", "file10") < 0);
        assertTrue(TableView.compareNatural("File", "file") == 0);
        assertTrue(TableView.compareNatural("a007", "a7") == 0);
        assertTrue(TableView.compareNatural("a", "ab") < 0);
        assertTrue(TableView.compareNatural("b", "a1") > 0);
    }

    // --- sort group, scroll on setItems, header and icon colors ---

    @Test
    void aSortGroupStaysOnTopInBothDirections() {
        table.setSortGroup(e -> e.name().startsWith("zeta") || e.name().startsWith("alpha"));
        // no sort yet: the group is first, each part in the given order
        assertEquals(List.of(c, d, a, b), table.getItems());

        table.sortBy(name, true);
        assertEquals(List.of(c, d, b, a), table.getItems());
        table.sortBy(name, false);
        assertEquals(List.of(d, c, a, b), table.getItems(), "grouped rows still first when descending");

        table.setSortGroup(null);
        assertEquals(List.of(d, a, b, c), table.getItems());
    }

    @Test
    void setItemsScrollsToTheTopAndRefreshKeepsTheOffset() {
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) many.add(new Entry("f" + i, i));
        table.setItems(many);
        table.scrollTo(0, 300);
        assertEquals(300, table.getScrollOffsetY(), 0.001);

        table.refresh();
        assertEquals(300, table.getScrollOffsetY(), 0.001, "refresh keeps the position");

        table.setItems(many);
        assertEquals(0, table.getScrollOffsetY(), 0.001, "new items start at the top");
    }

    @Test
    void headerColorFollowsTheThemeUntilPinned() {
        assertSame(table.getTextColor(), table.getHeaderTextColor());
        var muted = new me.piitex.engine.ui.color.Color(0.5f, 0.5f, 0.5f, 1f);
        table.setHeaderTextColor(muted);
        assertSame(muted, table.getHeaderTextColor());
        table.setHeaderTextColor(null);
        assertSame(table.getTextColor(), table.getHeaderTextColor());
    }
}
