package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.TableView;
import me.piitex.engine.ui.containers.TreeTableView;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A table whose rows form a tree: opening and closing, sorting each level, keys, and controls in its cells. */
class TreeTableViewTest {
    private static final class Node {
        final String name;
        final long size;
        final List<Node> kids = new ArrayList<>();
        boolean visible = true;

        Node(String name, long size, Node... kids) {
            this.name = name;
            this.size = size;
            this.kids.addAll(List.of(kids));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final float HEADER = 28, ROW = 24;

    private final Window window = new Window(new WindowOptions("test").setDimensions(600, 400));
    private final InputProcessor processor = new InputProcessor();
    private final Node readme = new Node("readme", 5);
    private final Node a = new Node("a.png", 30), b = new Node("b.png", 10);
    private final Node images = new Node("images", 40, a, b);
    private final Node main = new Node("Main.java", 20);
    private final Node src = new Node("src", 20, main);
    private final Node docs = new Node("docs", 45, images);
    private final List<Node> roots = new ArrayList<>(List.of(src, docs, readme));
    private TreeTableView<Node> tree;
    private TableView.Column<Node> name, size, visible;

    @BeforeEach
    void setUp() {
        tree = new TreeTableView<>(400, 300, n -> n.kids);
        name = tree.addColumn("Name", n -> n.name).setWidth(160);
        size = tree.addColumn("Size", n -> String.valueOf(n.size)).setWidth(80)
                .setComparator(Comparator.comparingLong(n -> n.size));
        visible = tree.addColumn("Visible", n -> "").setWidth(80).setSortable(false)
                .setCellWidgetFill(false, false)
                .setCellWidget(() -> new ToggleSwitchOverlay(40, 20), (toggle, node) -> {
                    toggle.setOn(node.visible);
                    toggle.onToggle(on -> node.visible = on);
                });
        tree.setRoots(roots);
        window.addContainer(tree);
        tree.update(0);
    }

    private void send(Runnable... steps) {
        for (Runnable step : steps) step.run();
        processor.processEvents(window);
        tree.update(0);
    }

    private Runnable click(double x, double y) {
        return () -> {
            window.getInputManager().onCursorPos(0, x, y);
            window.getInputManager().onMouseButton(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
            window.getInputManager().onMouseButton(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
        };
    }

    private Runnable key(int key) {
        return () -> {
            window.getInputManager().onKey(0, key, 0, GLFW.GLFW_PRESS, 0);
            window.getInputManager().onKey(0, key, 0, GLFW.GLFW_RELEASE, 0);
        };
    }

    private static double rowMiddle(int row) {
        return HEADER + row * ROW + ROW / 2;
    }

    /** The middle of a row's arrow: the cell's half padding, the depth's indent, then half an arrow. */
    private static double arrowX(int depth) {
        return 4 + depth * 16 + 8;
    }

    @Test
    void onlyTheRootsShowUntilSomethingOpens() {
        assertEquals(List.of(src, docs, readme), tree.getItems());
        tree.expand(docs);
        assertEquals(List.of(src, docs, images, readme), tree.getItems());
        assertEquals(1, tree.depthOf(images));
        assertEquals(docs, tree.parentOf(images));
        assertEquals(-1, tree.depthOf(a), "inside a closed item");
    }

    @Test
    void clickingTheArrowOpensAndClosesWithoutSelecting() {
        send(click(arrowX(0), rowMiddle(1)));
        assertTrue(tree.isExpanded(docs));
        assertEquals(List.of(src, docs, images, readme), tree.getItems());
        assertNull(tree.getSelected(), "the arrow only opens");
        send(click(arrowX(1), rowMiddle(2)));
        assertEquals(List.of(src, docs, images, a, b, readme), tree.getItems());
        send(click(arrowX(0), rowMiddle(1)));
        assertEquals(List.of(src, docs, readme), tree.getItems());
        assertTrue(tree.isExpanded(images), "an inner item stays open while its parent is closed");
    }

    @Test
    void clickingTheNamePastTheArrowSelects() {
        send(click(80, rowMiddle(1)));
        assertEquals(docs, tree.getSelected());
        assertFalse(tree.isExpanded(docs));
    }

    @Test
    void sortingSortsEachLevelAndKeepsChildrenUnderTheirParent() {
        tree.expandAll();
        tree.sortBy(size, true);
        assertEquals(List.of(readme, src, main, docs, images, b, a), tree.getItems());
        tree.sortBy(size, false);
        assertEquals(List.of(docs, images, a, b, src, main, readme), tree.getItems());
    }

    @Test
    void closingTheParentOfTheSelectedRowSelectsTheParent() {
        tree.setSelected(a);
        assertTrue(tree.isExpanded(docs) && tree.isExpanded(images), "setSelected opens the way to it");
        List<Node> told = new ArrayList<>();
        tree.onSelect(told::add);
        send(click(arrowX(0), rowMiddle(1)));
        assertEquals(docs, tree.getSelected());
        assertEquals(List.of(docs), told, "the user closed it, so the move is reported");
    }

    @Test
    void leftAndRightOpenCloseAndWalkTheTree() {
        tree.setSelected(docs);
        window.requestFocus(tree);
        send(key(GLFW.GLFW_KEY_RIGHT));
        assertTrue(tree.isExpanded(docs));
        assertEquals(docs, tree.getSelected());
        send(key(GLFW.GLFW_KEY_RIGHT));
        assertEquals(images, tree.getSelected(), "on an open item Right goes to its first child");
        send(key(GLFW.GLFW_KEY_LEFT));
        assertEquals(docs, tree.getSelected(), "on a closed item Left goes to the parent");
        send(key(GLFW.GLFW_KEY_LEFT));
        assertFalse(tree.isExpanded(docs));
    }

    @Test
    void aDoubleClickOpensAnItemAndStillActivates() {
        List<Node> activated = new ArrayList<>();
        tree.onActivate(activated::add);
        send(click(80, rowMiddle(0)), click(80, rowMiddle(0)));
        assertTrue(tree.isExpanded(src));
        assertEquals(List.of(src), activated);
        tree.setToggleOnDoubleClick(false);
        send(click(80, rowMiddle(0)), click(80, rowMiddle(0)));
        assertTrue(tree.isExpanded(src));
        assertEquals(List.of(src, src), activated);
    }

    @Test
    void cellControlsFollowTheRowsAsItemsOpen() {
        Element readmeSwitch = tree.getCellWidget(readme, visible);
        assertNotNull(readmeSwitch);
        assertEquals(rowMiddle(2), readmeSwitch.getAbsoluteY() + readmeSwitch.getHeight() / 2, 1);
        tree.expand(docs);
        tree.update(0);
        assertSame(readmeSwitch, tree.getCellWidget(readme, visible), "the same control, moved down a row");
        assertEquals(rowMiddle(3), readmeSwitch.getAbsoluteY() + readmeSwitch.getHeight() / 2, 1);
        assertNotNull(tree.getCellWidget(images, visible));

        ToggleSwitchOverlay imagesSwitch = (ToggleSwitchOverlay) tree.getCellWidget(images, visible);
        send(click(imagesSwitch.getAbsoluteX() + imagesSwitch.getWidth() / 2, imagesSwitch.getAbsoluteY() + imagesSwitch.getHeight() / 2));
        assertFalse(images.visible);
        assertEquals(images, tree.getSelected());
    }

    @Test
    void aControlInTheTreeColumnIsIndented() {
        tree.setTreeColumn(visible);
        tree.expand(docs);
        tree.update(0);
        double top = tree.getCellWidget(docs, visible).getAbsoluteX();
        double inner = tree.getCellWidget(images, visible).getAbsoluteX();
        assertEquals(16, inner - top, 1e-9, "one level deeper, one indent right");
    }

    @Test
    void ticksInsideClosedItemsAreKept() {
        tree.setCheckBoxes(true);
        tree.setChecked(a, true);
        tree.refresh();
        assertTrue(tree.isChecked(a), "not shown, still ticked");
        assertEquals(List.of(a), tree.getCheckedItems());
        tree.setSelected(b);
        assertEquals(List.of(a), tree.getCheckedItems());
    }

    @Test
    void collapseAllMovesTheSelectionToItsRoot() {
        tree.setSelected(b);
        tree.collapseAll();
        assertEquals(List.of(src, docs, readme), tree.getItems());
        assertEquals(docs, tree.getSelected());
    }

    @Test
    void revealOpensTheWayAndRefusesStrangers() {
        assertTrue(tree.reveal(b));
        assertEquals(2, tree.depthOf(b));
        assertFalse(tree.reveal(new Node("elsewhere", 0)));
    }
}
