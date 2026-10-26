package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.events.MouseClickEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InventoryGridOverlayTest {
    /** A grid whose held modifiers a test can set, since there is no window to ask. */
    private static final class TestGrid extends InventoryGridOverlay<String> {
        int mods;

        TestGrid(int columns, int rows, double slotSize) {
            super(columns, rows, slotSize);
        }

        @Override
        protected int heldModifiers() {
            return mods;
        }
    }

    // 4 by 2 slots of 40 points, gap 4, padding 4: slot i has its top-left at (4 + col * 44, 4 + row * 44).
    private TestGrid grid;
    private final List<Integer> changed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        grid = new TestGrid(4, 2, 40);
        grid.setPosition(0, 0);
        grid.setMaxStack(item -> item.equals("sword") || item.equals("shield") ? 1 : 10);
        grid.onSlotChange(changed::add);
    }

    private static double cx(int slot) {
        return 4 + (slot % 4) * 44 + 20;
    }

    private static double cy(int slot) {
        return 4 + (slot / 4) * 44 + 20;
    }

    private void drag(TestGrid from, int slot, double toX, double toY) {
        from.onDragStart(cx(slot), cy(slot));
        from.onDragUpdate(toX, toY);
        from.onDragEnd(toX, toY);
    }

    @Test
    void theGridIsSizedFromItsSlots() {
        assertEquals(8, grid.getSlotCount());
        assertEquals(4 * 2 + 4 * 40 + 3 * 4, grid.getWidth(), 0.001);
        assertEquals(4 * 2 + 2 * 40 + 4, grid.getHeight(), 0.001);
    }

    @Test
    void slotAtFindsSlotsAndIgnoresGapsAndPadding() {
        assertEquals(0, grid.slotAt(cx(0), cy(0)));
        assertEquals(5, grid.slotAt(cx(5), cy(5)));
        assertEquals(-1, grid.slotAt(2, 2), "the padding");
        assertEquals(-1, grid.slotAt(4 + 40 + 2, 20), "the gap between slots");
        assertEquals(-1, grid.slotAt(900, 20));
    }

    @Test
    void addingFillsExistingStacksThenEmptySlots() {
        assertEquals(0, grid.add("potion", 25));
        assertEquals(10, grid.getCount(0));
        assertEquals(10, grid.getCount(1));
        assertEquals(5, grid.getCount(2));
        assertEquals(0, grid.add("potion", 5)); // tops up slot 2 rather than starting slot 3
        assertEquals(10, grid.getCount(2));
        assertTrue(grid.isEmpty(3));
        assertEquals(30, grid.countOf("potion"));
    }

    @Test
    void addingReturnsWhatDidNotFit() {
        for (int i = 0; i < 8; i++) grid.add("sword", 1);
        assertEquals(3, grid.add("sword", 3));
        assertEquals(-1, grid.firstEmpty());
    }

    @Test
    void addingSkipsLockedSlotsAndSlotsThatRefuse() {
        grid.setLocked(0, true);
        grid.setSlotFilter(1, item -> item.equals("helmet"));
        grid.add("potion", 1);
        assertTrue(grid.isEmpty(0));
        assertTrue(grid.isEmpty(1));
        assertEquals(1, grid.getCount(2));
        grid.add("helmet", 1);
        assertEquals("helmet", grid.getItem(1));
    }

    @Test
    void removingTakesFromTheLastSlotsFirst() {
        grid.add("potion", 25);
        assertEquals(12, grid.remove("potion", 12));
        assertEquals(10, grid.getCount(0));
        assertEquals(3, grid.getCount(1));
        assertTrue(grid.isEmpty(2));
        assertEquals(13, grid.remove("potion", 99), "only 13 were there");
        assertEquals(0, grid.countOf("potion"));
    }

    @Test
    void everyChangeIsReported() {
        grid.add("potion", 12);
        assertEquals(List.of(0, 1), changed);
        changed.clear();
        grid.setSlot(5, "key", 1);
        grid.clearSlot(5);
        assertEquals(List.of(5, 5), changed);
    }

    @Test
    void movingToAnEmptySlotMovesTheStack() {
        grid.setSlot(0, "potion", 7);
        assertTrue(grid.move(0, grid, 5, 7));
        assertTrue(grid.isEmpty(0));
        assertEquals(7, grid.getCount(5));
    }

    @Test
    void movingPartOfAStackLeavesTheRest() {
        grid.setSlot(0, "potion", 7);
        assertTrue(grid.move(0, grid, 5, 3));
        assertEquals(4, grid.getCount(0));
        assertEquals(3, grid.getCount(5));
    }

    @Test
    void movingOntoTheSameItemMergesUpToTheLimit() {
        grid.setSlot(0, "potion", 6);
        grid.setSlot(1, "potion", 7);
        assertTrue(grid.move(0, grid, 1, 6));
        assertEquals(10, grid.getCount(1));
        assertEquals(3, grid.getCount(0));
        assertFalse(grid.move(0, grid, 1, 3), "the target is full");
        assertEquals(3, grid.getCount(0));
    }

    @Test
    void differentItemsSwapWhenTheWholeStackMoves() {
        grid.setSlot(0, "sword", 1);
        grid.setSlot(1, "shield", 1);
        assertTrue(grid.move(0, grid, 1, 1));
        assertEquals("shield", grid.getItem(0));
        assertEquals("sword", grid.getItem(1));
    }

    @Test
    void aPartialStackDoesNotSwap() {
        grid.setSlot(0, "potion", 5);
        grid.setSlot(1, "sword", 1);
        assertFalse(grid.move(0, grid, 1, 2));
        assertEquals(5, grid.getCount(0));
        assertEquals("sword", grid.getItem(1));
    }

    @Test
    void aSwapNeedsBothSlotsToAcceptTheOtherItem() {
        grid.setSlot(0, "sword", 1);
        grid.setSlot(1, "helmet", 1);
        grid.setSlotFilter(1, item -> item.equals("helmet"));
        assertFalse(grid.move(0, grid, 1, 1), "the helmet slot refuses a sword");
        grid.setSlotFilter(1, null);
        grid.setSlotFilter(0, item -> item.equals("sword"));
        assertFalse(grid.move(0, grid, 1, 1), "the sword slot refuses a helmet");
    }

    @Test
    void lockedSlotsAndGridFiltersRefuseMoves() {
        grid.setSlot(0, "potion", 1);
        grid.setLocked(5, true);
        assertFalse(grid.move(0, grid, 5, 1));
        grid.setLocked(5, false);
        grid.setFilter(item -> !item.equals("potion"));
        assertFalse(grid.move(0, grid, 5, 1));
        assertEquals(1, grid.getCount(0));
    }

    @Test
    void movingBetweenLinkedGridsWorksAndTheLimitIsTheTargets() {
        TestGrid chest = new TestGrid(2, 2, 40);
        chest.setMaxStack(item -> 4);
        grid.setSlot(0, "potion", 9);
        assertTrue(grid.move(0, chest, 0, 9));
        assertEquals(4, chest.getCount(0));
        assertEquals(5, grid.getCount(0));
    }

    @Test
    void draggingAStackToAnotherSlotMovesIt() {
        grid.setSlot(0, "potion", 7);
        drag(grid, 0, cx(6), cy(6));
        assertTrue(grid.isEmpty(0));
        assertEquals(7, grid.getCount(6));
        assertFalse(grid.isDragging());
    }

    @Test
    void ctrlDragCarriesHalfRoundedUp() {
        grid.setSlot(0, "potion", 7);
        grid.mods = GLFW.GLFW_MOD_CONTROL;
        grid.onDragStart(cx(0), cy(0));
        assertEquals(4, grid.getDragCount());
        assertEquals(0, grid.getDragSlot());
        grid.onDragUpdate(cx(3), cy(3));
        grid.onDragEnd(cx(3), cy(3));
        assertEquals(3, grid.getCount(0));
        assertEquals(4, grid.getCount(3));
    }

    @Test
    void droppingOnAGapOrOutsideWithNoHandlerCancels() {
        grid.setSlot(0, "potion", 7);
        drag(grid, 0, 4 + 40 + 2, 20);
        assertEquals(7, grid.getCount(0));
        drag(grid, 0, 900, 900);
        assertEquals(7, grid.getCount(0));
    }

    @Test
    void droppingOutsideCallsTheHandlerAndRemovesWhatItTakes() {
        grid.setSlot(0, "potion", 7);
        List<String> dropped = new ArrayList<>();
        grid.onDropOutside((slot, item, count) -> dropped.add(item + "x" + count));
        // A handler that takes nothing: the drag is cancelled.
        grid.onDropOutside((slot, item, count) -> false);
        drag(grid, 0, 900, 900);
        assertEquals(7, grid.getCount(0));
        grid.onDropOutside((slot, item, count) -> {
            dropped.add(item + "x" + count);
            return true;
        });
        drag(grid, 0, 900, 900);
        assertEquals(List.of("potionx7"), dropped);
        assertTrue(grid.isEmpty(0));
    }

    @Test
    void draggingBetweenLinkedGridsMovesTheItems() {
        TestGrid chest = new TestGrid(2, 2, 40);
        chest.setPosition(300, 0);
        grid.link(chest);
        grid.setSlot(0, "potion", 7);
        drag(grid, 0, 300 + 4 + 44 + 20, 20); // the chest's slot 1
        assertTrue(grid.isEmpty(0));
        assertEquals(7, chest.getCount(1));
    }

    @Test
    void anUnlinkedGridIsNotADropTarget() {
        TestGrid other = new TestGrid(2, 2, 40);
        other.setPosition(300, 0);
        grid.setSlot(0, "potion", 7);
        drag(grid, 0, 324, 24);
        assertEquals(7, grid.getCount(0));
        assertTrue(other.isEmpty(0));
        grid.link(other);
        grid.unlink(other);
        drag(grid, 0, 324, 24);
        assertEquals(7, grid.getCount(0));
    }

    @Test
    void shiftClickSendsTheStackToALinkedGrid() {
        TestGrid chest = new TestGrid(2, 2, 40);
        grid.link(chest);
        grid.setSlot(2, "potion", 7);
        grid.mods = GLFW.GLFW_MOD_SHIFT;
        grid.onDragStart(cx(2), cy(2));
        grid.onDragEnd(cx(2), cy(2));
        assertTrue(grid.isEmpty(2));
        assertEquals(7, chest.getCount(0));
    }

    @Test
    void quickMoveKeepsWhatDoesNotFit() {
        TestGrid chest = new TestGrid(1, 1, 40);
        chest.setMaxStack(item -> 4);
        grid.link(chest);
        grid.setSlot(0, "potion", 9);
        assertTrue(grid.quickMove(0));
        assertEquals(4, chest.getCount(0));
        assertEquals(5, grid.getCount(0));
        assertFalse(grid.quickMove(0), "the chest is full now");
    }

    @Test
    void aClickSelectsAndAnotherNearbyClickIsADoubleClickThatUsesTheItem() {
        List<Integer> selected = new ArrayList<>(), used = new ArrayList<>();
        grid.onSelect(selected::add);
        grid.onUse(used::add);
        grid.setSlot(1, "potion", 3);
        grid.onDragStart(cx(1), cy(1));
        grid.onDragEnd(cx(1), cy(1));
        assertEquals(List.of(1), selected);
        assertEquals(1, grid.getSelectedSlot());
        assertTrue(used.isEmpty());
        assertEquals("potion", grid.getItem(1), "a click moves nothing");
        grid.onDragStart(cx(1), cy(1));
        grid.onDragEnd(cx(1), cy(1));
        assertEquals(List.of(1), used);
    }

    @Test
    void rightClickUsesTheItemButNotAnEmptyOrLockedSlot() {
        List<Integer> used = new ArrayList<>();
        grid.onUse(used::add);
        grid.setSlot(1, "potion", 3);
        grid.setSlot(2, "potion", 3);
        grid.setLocked(2, true);
        for (int slot : new int[]{1, 2, 3}) {
            grid.getMouseClickListenerConsumer().accept(new MouseClickEvent(cx(slot), cy(slot), GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS));
        }
        assertEquals(List.of(1), used);
    }

    @Test
    void aLockedSlotCannotBePickedUp() {
        grid.setSlot(0, "potion", 3);
        grid.setLocked(0, true);
        grid.onDragStart(cx(0), cy(0));
        assertFalse(grid.isDragging());
        grid.onDragEnd(cx(4), cy(4));
        assertEquals(3, grid.getCount(0));
        assertTrue(grid.isEmpty(4));
    }

    @Test
    void theGameCanStillSetALockedSlot() {
        grid.setLocked(0, true);
        grid.setSlot(0, "key", 1);
        assertEquals("key", grid.getItem(0));
    }

    @Test
    void theKeysMoveTheSelectionAndEnterUsesIt() {
        List<Integer> used = new ArrayList<>();
        grid.onUse(used::add);
        grid.setSlot(5, "potion", 1);
        grid.onFocusGained();
        assertEquals(0, grid.getSelectedSlot());
        grid.onKeyPressed(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0);
        grid.onKeyPressed(GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_PRESS, 0);
        assertEquals(5, grid.getSelectedSlot());
        grid.onKeyPressed(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0);
        assertEquals(List.of(5), used);
        grid.onKeyPressed(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0); // already on the last row
        assertEquals(5, grid.getSelectedSlot());
        grid.onKeyPressed(GLFW.GLFW_KEY_END, GLFW.GLFW_PRESS, 0);
        assertEquals(7, grid.getSelectedSlot());
    }

    @Test
    void growingTheGridKeepsItemsAndShrinkingDropsTheOnesCutOff() {
        grid.setSlot(2, "a", 1);
        grid.setSlot(7, "b", 1);
        grid.setGrid(4, 3);
        assertEquals(12, grid.getSlotCount());
        assertEquals("a", grid.getItem(2));
        assertEquals("b", grid.getItem(7));
        grid.setGrid(4, 1);
        assertEquals(4, grid.getSlotCount());
        assertEquals("a", grid.getItem(2));
        assertEquals(4 * 2 + 4 * 40 + 3 * 4, grid.getWidth(), 0.001);
        assertEquals(48, grid.getHeight(), 0.001);
    }

    @Test
    void aBadSlotNumberIsAnError() {
        assertThrows(IndexOutOfBoundsException.class, () -> grid.getItem(8));
        assertThrows(IndexOutOfBoundsException.class, () -> grid.setSlot(-1, "x", 1));
    }

    @Test
    void itDrawsItemsCountsLocksAndADragWithoutAWindow() {
        grid.setSlot(0, "potion", 7);
        grid.setSlot(1, "sword", 1);
        grid.setLocked(7, true);
        grid.setSelectedSlot(1);
        grid.setBorderProvider(item -> item.equals("sword") ? me.piitex.engine.ui.color.Color.GOLD : null);
        grid.onDragStart(cx(0), cy(0));
        grid.onDragUpdate(cx(3), cy(3));
        try (OffscreenRenderer r = new OffscreenRenderer(200, 100, 1f)) {
            assertDoesNotThrow(() -> {
                r.begin();
                grid.render(r);
                r.end();
            });
        }
        grid.onDragEnd(cx(3), cy(3));
        grid.dispose();
    }
}
