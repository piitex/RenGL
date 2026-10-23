package me.piitex.engine.ui.containers;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReorderableGridTest {
    private static ReorderableGrid grid(boolean free, Container... items) {
        ReorderableGrid grid = new ReorderableGrid(300, 200, 100, 100, 0);
        grid.setFreePlacement(free);
        grid.setItems(Arrays.asList(items));
        return grid;
    }

    private static Container item() {
        return new Container(100, 100);
    }

    /** Presses the middle of slot {@code from}, carries it to the middle of slot {@code to} and lets go. */
    private static void drag(ReorderableGrid grid, int from, int to) {
        double ox = grid.getContentOriginX(), oy = grid.getContentOriginY();
        double fx = ox + (from % 3) * 100 + 50, fy = oy + (from / 3) * 100 + 50;
        double tx = ox + (to % 3) * 100 + 50, ty = oy + (to / 3) * 100 + 50;
        grid.onDragStart(fx, fy);
        grid.onDragUpdate(tx, ty);
        grid.update(0.5f);
        grid.onDragEnd(tx, ty);
    }

    @Test
    void freeGridPadsToTheVisibleArea() {
        ReorderableGrid grid = grid(true, item(), item());
        assertEquals(6, grid.getSlots().size());
        assertEquals(2, grid.getItems().size());
    }

    @Test
    void itemGoesIntoAnEmptySlotAndLeavesAGap() {
        Container a = item(), b = item();
        ReorderableGrid grid = grid(true, a, b);
        List<List<Container>> reported = new ArrayList<>();
        grid.onReorder(reported::add);

        drag(grid, 0, 4);

        assertNull(grid.getSlots().get(0));
        assertSame(b, grid.getSlots().get(1));
        assertSame(a, grid.getSlots().get(4));
        assertEquals(1, reported.size());
        assertSame(a, reported.get(0).get(4));
    }

    @Test
    void droppingOnAnOccupiedSlotSwaps() {
        Container a = item(), b = item(), c = item();
        ReorderableGrid grid = grid(true, a, b, c);

        drag(grid, 0, 2);

        assertEquals(List.of(c, b, a), grid.getSlots().subList(0, 3));
    }

    @Test
    void emptySlotsInTheGivenListAreKept() {
        Container a = item(), b = item();
        ReorderableGrid grid = grid(true, a, null, null, b);
        assertSame(a, grid.getSlots().get(0));
        assertNull(grid.getSlots().get(1));
        assertSame(b, grid.getSlots().get(3));
        assertEquals(List.of(a, b), grid.getItems());
    }

    @Test
    void plainGridStillPacks() {
        Container a = item(), b = item(), c = item();
        ReorderableGrid grid = grid(false, a, b, c);

        drag(grid, 0, 2);

        assertEquals(List.of(b, c, a), grid.getItems());
        assertEquals(3, grid.getSlots().size());
    }

    @Test
    void turningFreePlacementOffPacksTheItems() {
        Container a = item(), b = item();
        ReorderableGrid grid = grid(true, null, a, null, b);
        grid.setFreePlacement(false);
        assertEquals(List.of(a, b), grid.getSlots());
    }
}
