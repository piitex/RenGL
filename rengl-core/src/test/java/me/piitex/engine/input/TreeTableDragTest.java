package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.containers.TreeTableView;
import me.piitex.engine.ui.containers.TreeView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Dragging rows of a tree table to reorder them or move them under another item. */
class TreeTableDragTest {
    private static final class Node {
        final String name;
        final long size;
        final List<Node> kids = new ArrayList<>();
        Node parent;

        Node(String name, long size, Node... kids) {
            this.name = name;
            this.size = size;
            for (Node kid : kids) {
                this.kids.add(kid);
                kid.parent = this;
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final float HEADER = 28, ROW = 24, X = 100;

    private final Window window = new Window(new WindowOptions("test").setDimensions(600, 400));
    private final InputProcessor processor = new InputProcessor();
    private final Node readme = new Node("readme", 5);
    private final Node a = new Node("a.png", 30), b = new Node("b.png", 10);
    private final Node images = new Node("images", 40, a, b);
    private final Node main = new Node("Main.java", 20);
    private final Node src = new Node("src", 20, main);
    private final Node docs = new Node("docs", 45, images);
    private final List<Node> roots = new ArrayList<>(List.of(src, docs, readme));
    private final List<TreeView.Drop<Node>> drops = new ArrayList<>();
    private TreeTableView<Node> tree;

    @BeforeEach
    void setUp() {
        tree = new TreeTableView<>(400, 300, n -> n.kids);
        tree.addColumn("Name", n -> n.name).setWidth(160);
        tree.addColumn("Size", n -> String.valueOf(n.size)).setWidth(80).setComparator(Comparator.comparingLong(n -> n.size));
        tree.setRoots(roots);
        tree.setDragEnabled(true);
        tree.onDrop(drop -> {
            drops.add(drop);
            move(drop);
            tree.refresh();
        });
        window.addContainer(tree);
        tree.update(0);
    }

    /** Makes the move the way an app would: takes each item out, then puts them in at the drop's gap. */
    private void move(TreeView.Drop<Node> drop) {
        List<Node> into = drop.parent() == null ? roots : drop.parent().kids;
        int index = drop.index();
        for (Node item : drop.items()) {
            List<Node> from = item.parent == null ? roots : item.parent.kids;
            int old = from.indexOf(item);
            if (from == into && old < index) index--;
            from.remove(item);
        }
        for (Node item : drop.items()) {
            into.add(index++, item);
            item.parent = drop.parent();
        }
    }

    private static double rowY(int row, double within) {
        return HEADER + (row + within) * ROW;
    }

    private void step(Runnable event) {
        event.run();
        processor.processEvents(window);
        tree.update(0);
    }

    private void moveTo(double x, double y) {
        step(() -> window.getInputManager().onCursorPos(0, x, y));
    }

    private void press() {
        step(() -> window.getInputManager().onMouseButton(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0));
    }

    private void release() {
        step(() -> window.getInputManager().onMouseButton(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0));
    }

    private void key(int key) {
        step(() -> {
            window.getInputManager().onKey(0, key, 0, GLFW.GLFW_PRESS, 0);
            window.getInputManager().onKey(0, key, 0, GLFW.GLFW_RELEASE, 0);
        });
    }

    /** Presses on a row and moves to (X, toY) in a few steps, without letting go. */
    private void hold(int row, double toY) {
        double fromY = rowY(row, 0.5);
        moveTo(X, fromY);
        press();
        for (int i = 1; i <= 4; i++) moveTo(X, fromY + (toY - fromY) * i / 4);
    }

    private void drag(int row, double toY) {
        hold(row, toY);
        release();
    }

    @Test
    void droppingOnTheTopOfARowPutsItBefore() {
        drag(2, rowY(0, 0.1));
        assertEquals(List.of(new TreeView.Drop<>(readme, null, 0)), drops);
        assertEquals(List.of(readme, src, docs), tree.getItems());
    }

    @Test
    void droppingOnTheBottomOfARowPutsItAfter() {
        drag(0, rowY(1, 0.9));
        assertEquals(new TreeView.Drop<>(src, null, 2), drops.getFirst());
        assertEquals(List.of(docs, src, readme), tree.getItems());
    }

    @Test
    void droppingOnTheMiddleOfARowPutsItInsideAtTheEnd() {
        drag(2, rowY(1, 0.5));
        assertEquals(new TreeView.Drop<>(readme, docs, 1), drops.getFirst());
        assertEquals(List.of(images, readme), docs.kids);
    }

    @Test
    void belowTheLastRowIsTheEndOfTheTopLevel() {
        drag(0, rowY(6, 0.5));
        assertEquals(new TreeView.Drop<>(src, null, 3), drops.getFirst());
        assertEquals(List.of(docs, readme, src), tree.getItems());
    }

    @Test
    void aChildCanBeDraggedOutToTheTopLevel() {
        tree.expand(src); // src, Main.java, docs, readme
        drag(1, rowY(3, 0.9));
        assertEquals(new TreeView.Drop<>(main, null, 3), drops.getFirst());
        assertTrue(src.kids.isEmpty());
        assertEquals(List.of(src, docs, readme, main), tree.getItems());
        assertEquals(0, tree.depthOf(main));
    }

    @Test
    void anItemCannotGoIntoItsOwnChildren() {
        tree.expand(docs); // src, docs, images, readme
        drag(1, rowY(2, 0.5));
        assertTrue(drops.isEmpty());
    }

    @Test
    void theGapsEitherSideOfARowAreNoMove() {
        drag(1, rowY(1, 0.9));
        drag(1, rowY(2, 0.1));
        assertTrue(drops.isEmpty());
    }

    @Test
    void aShortMoveIsAClick() {
        moveTo(X, rowY(2, 0.5));
        press();
        moveTo(X + 1, rowY(2, 0.5) + 2);
        assertFalse(tree.isDraggingRow());
        release();
        assertTrue(drops.isEmpty());
        assertEquals(readme, tree.getSelected());
    }

    @Test
    void escapeDropsTheDrag() {
        hold(2, rowY(0, 0.1));
        assertTrue(tree.isDraggingRow());
        key(GLFW.GLFW_KEY_ESCAPE);
        assertFalse(tree.isDraggingRow());
        release();
        assertTrue(drops.isEmpty());
        assertEquals(List.of(src, docs, readme), tree.getItems());
    }

    @Test
    void canDropRefuses() {
        tree.setCanDrop((item, parent) -> parent == null);
        drag(2, rowY(1, 0.5));
        assertTrue(drops.isEmpty());
        drag(2, rowY(0, 0.1));
        assertEquals(1, drops.size());
    }

    @Test
    void holdingOverAClosedItemOpensIt() {
        hold(2, rowY(1, 0.5));
        assertFalse(tree.isExpanded(docs));
        tree.update(0.8f);
        assertTrue(tree.isExpanded(docs));
        release();
        assertEquals(new TreeView.Drop<>(readme, docs, 1), drops.getFirst());
    }

    @Test
    void sortedTheSameSiblingsAreNoMoveButAnotherParentIs() {
        tree.sortBy(tree.getColumns().get(1), true); // readme 5, src 20, docs 45
        assertEquals(List.of(readme, src, docs), tree.getItems());
        drag(0, rowY(2, 0.9));
        assertTrue(drops.isEmpty(), "the sort decides the order among siblings");
        drag(0, rowY(1, 0.5));
        assertEquals(new TreeView.Drop<>(readme, src, 1), drops.getFirst());
        assertEquals(List.of(main, readme), src.kids);
    }

    @Test
    void severalSelectedRowsGoTogether() {
        tree.setMultiSelect(true);
        tree.setSelectedItems(List.of(src, readme));
        drag(2, rowY(1, 0.5));
        TreeView.Drop<Node> drop = drops.getFirst();
        assertEquals(readme, drop.item());
        assertEquals(List.of(src, readme), drop.items());
        assertEquals(List.of(images, src, readme), docs.kids);
    }

    @Test
    void pressingOneOfSeveralSelectedRowsWithoutDraggingSelectsJustIt() {
        tree.setMultiSelect(true);
        tree.setSelectedItems(List.of(src, readme));
        moveTo(X, rowY(2, 0.5));
        press();
        assertEquals(2, tree.getSelectedItems().size(), "kept while it may become a drag");
        release();
        assertEquals(List.of(readme), tree.getSelectedItems());
    }

    @Test
    void withDragOffRowsStay() {
        tree.setDragEnabled(false);
        drag(2, rowY(0, 0.1));
        assertTrue(drops.isEmpty());
        assertFalse(tree.isDraggingRow());
    }

    /** A renderer that draws nothing but keeps the alpha stack, so a test can see how faded something is drawn. */
    private static Renderer alphaRenderer() {
        Deque<Float> alpha = new ArrayDeque<>(List.of(1f));
        return (Renderer) Proxy.newProxyInstance(Renderer.class.getClassLoader(), new Class<?>[]{Renderer.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "pushAlpha" -> alpha.push(alpha.peek() * (Float) args[0]);
                case "popAlpha" -> alpha.pop();
                case "getAlpha" -> {
                    return alpha.peek();
                }
                default -> {
                }
            }
            Class<?> type = method.getReturnType();
            if (type == boolean.class) return false;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        });
    }

    @Test
    void cellControlsFadeWithTheirDraggedRow() {
        Map<String, Float> drawnAt = new HashMap<>();
        tree.addColumn("Open", n -> "").setWidth(80).setCellWidget(() -> new ButtonOverlay("", 60, 20) {
            @Override
            public void render(Renderer renderer) {
                drawnAt.put(getText(), renderer.getAlpha());
            }
        }, (button, n) -> button.setText(n.name));
        tree.update(0);
        Renderer renderer = alphaRenderer();
        hold(2, rowY(0, 0.1));
        tree.render(renderer);
        assertEquals(0.4f, drawnAt.get("readme"), 1e-6f);
        assertEquals(1f, drawnAt.get("src"), 1e-6f);
        release();
        drawnAt.clear();
        tree.render(renderer);
        assertEquals(1f, drawnAt.get("readme"), 1e-6f);
    }
}
