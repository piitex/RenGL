package me.piitex.engine.ui.containers;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.color.Color;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Dragging rows: everything selected goes along, and the dragged row is drawn at the pointer. */
class TreeViewDragTest {
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
    private final List<TreeView.Drop<String>> drops = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tree = new Tree();
        tree.setRoots(List.of("folder", "x", "y", "z"));
        tree.expand("folder");
        tree.setMultiSelect(true);
        tree.setDragEnabled(true);
        tree.onDrop(drops::add);
        // rows: folder, a, b, c, x, y, z
    }

    private static double at(int row) {
        return row * 24 + 12;
    }

    private void click(int row, int mods) {
        tree.mods = mods;
        tree.onDragStart(100, at(row));
        tree.onDragEnd(100, at(row));
        tree.mods = 0;
    }

    private void drag(int from, double toY) {
        tree.onDragStart(100, at(from));
        tree.onDragUpdate(100, toY);
        tree.onDragEnd(100, toY);
    }

    @Test
    void draggingOneOfSeveralSelectedRowsCarriesThemAll() {
        click(1, 0);
        click(3, GLFW.GLFW_MOD_CONTROL);
        drag(1, at(6) + 10); // below z: the end of the top level

        assertEquals(1, drops.size());
        TreeView.Drop<String> drop = drops.getFirst();
        assertEquals("a", drop.item(), "the pressed row");
        assertEquals(List.of("a", "c"), drop.items(), "top to bottom");
        assertNull(drop.parent());
        assertEquals(4, drop.index());
    }

    @Test
    void aRowInsideAnotherSelectedRowGoesWithIt() {
        tree.expand("b");
        click(2, 0);
        click(3, GLFW.GLFW_MOD_CONTROL); // b1, inside b
        click(5, GLFW.GLFW_MOD_CONTROL); // c
        drag(2, at(8) + 10);

        assertEquals(List.of("b", "c"), drops.getFirst().items());
    }

    @Test
    void pressingARowThatIsNotSelectedDragsOnlyIt() {
        click(1, 0);
        click(3, GLFW.GLFW_MOD_CONTROL);
        drag(4, at(6) + 10); // x was not selected

        assertEquals(List.of("x"), drops.getFirst().items());
        assertEquals(List.of("x"), tree.getSelectedItems());
    }

    @Test
    void nothingIsDroppedIntoARowThatIsBeingCarried() {
        click(1, 0);
        click(3, GLFW.GLFW_MOD_CONTROL);
        drag(1, at(3)); // the middle of c, which goes along

        assertTrue(drops.isEmpty());
    }

    @Test
    void everyCarriedRowMustBeAllowedToGoThere() {
        tree.setCanDrop((item, parent) -> !item.equals("c"));
        click(1, 0);
        click(3, GLFW.GLFW_MOD_CONTROL);
        drag(1, at(6) + 10);
        assertTrue(drops.isEmpty(), "c can't go, so the pair can't");

        click(5, 0);
        drag(4, at(6) + 10); // x, pressed a moment after another row was clicked
        assertEquals(List.of("x"), drops.getFirst().items());
    }

    @Test
    void aSingleRowDroppedWhereItIsStillReportsNothing() {
        click(2, 0);
        drag(2, at(2) + 20); // the bottom quarter of its own row
        assertTrue(drops.isEmpty());
    }

    @Test
    void theDraggedRowIsDrawnAtThePointer() {
        tree.setSelectionColor(Color.RED);
        click(5, 0); // a press on the row clicked a moment ago would be a double-click, not a drag
        int before = pixel(216, 172);

        tree.onDragStart(100, at(4));
        tree.onDragUpdate(200, 150);
        assertTrue(tree.isDraggingRow());
        int during = pixel(216, 172);
        tree.onDragEnd(200, 150);

        assertNotEquals(before, during, "something is drawn beside the pointer while the row is dragged");
        assertEquals(before, pixel(216, 172), "and it goes when the button is let go");
    }

    private int pixel(int x, int y) {
        try (OffscreenRenderer r = new OffscreenRenderer(300, 240, 1f)) {
            r.begin();
            tree.render(r);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                int color = bmp.getColor(x, y);
                bmp.close();
                return color;
            }
        }
    }
}
