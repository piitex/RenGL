package me.piitex.engine.ui.containers;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.input.FileDrag;
import me.piitex.engine.input.InputProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A tree row dragged out of the window becomes a drag of files for the operating system. */
class TreeViewFileDragTest {
    private static final class Tree extends TreeView<String> {
        Tree() {
            super(300, 240, item -> switch (item) {
                case "folder" -> List.of("a", "b", "c");
                default -> List.of();
            });
        }

        @Override
        protected int heldModifiers() {
            return 0;
        }
    }

    /** A window that records the OS drags it is asked to start, instead of starting them. */
    private static final class RecordingWindow extends Window {
        final List<FileDrag.Source> started = new ArrayList<>();

        RecordingWindow() {
            super(new WindowOptions("test").setDimensions(300, 240));
        }

        @Override
        public void startFileDrag(FileDrag.Source source, Consumer<FileDrag.Outcome> done) {
            started.add(source);
        }
    }

    private Tree tree;
    private final List<List<String>> asked = new ArrayList<>();
    private final List<TreeView.Drop<String>> drops = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tree = new Tree();
        tree.setRoots(List.of("folder", "x", "y"));
        tree.expand("folder");
        tree.setMultiSelect(true);
        tree.onDrop(drops::add);
        // rows: folder, a, b, c, x, y
    }

    private static double at(int row) {
        return row * 24 + 12;
    }

    private void useSource() {
        tree.setFileDragSource(items -> {
            asked.add(items);
            return () -> items.stream().map(Path::of).toList();
        });
    }

    @Test
    void withoutASourceThereIsNothingToDragOut() {
        tree.onDragStart(100, at(1));
        assertNull(tree.fileDragOut());
    }

    @Test
    void thePressedRowIsWhatLeavesTheWindow() throws Exception {
        useSource();
        tree.onDragStart(100, at(2));
        FileDrag.Source source = tree.fileDragOut();
        assertNotNull(source);
        assertEquals(List.of(List.of("b")), asked);
        assertEquals(List.of(Path.of("b")), source.files(), "the source is the one the callback made");
    }

    @Test
    void severalSelectedRowsLeaveTogether() {
        useSource();
        tree.setSelectedItems(List.of("a", "c"));
        tree.onDragStart(100, at(1));
        assertNotNull(tree.fileDragOut());
        assertEquals(List.of(List.of("a", "c")), asked);
    }

    @Test
    void aPressThatWasNotOnARowCarriesNothing() {
        useSource();
        tree.onDragStart(100, at(9)); // below the last row
        assertNull(tree.fileDragOut());
        assertTrue(asked.isEmpty());
    }

    @Test
    void theCallbackCanRefuse() {
        tree.setFileDragSource(items -> null);
        tree.onDragStart(100, at(1));
        assertNull(tree.fileDragOut());
    }

    @Test
    void cancellingTheTreesOwnDragDropsNothing() {
        useSource();
        tree.setDragEnabled(true);
        tree.onDragStart(100, at(1));
        tree.onDragUpdate(100, at(5) + 10); // a drop position inside the tree
        assertTrue(tree.isDraggingRow());

        tree.onDragCancel();
        assertFalse(tree.isDraggingRow());
        tree.onDragEnd(100, at(5) + 10);
        assertTrue(drops.isEmpty(), "the drag left the window; it is not a move inside the tree");
    }

    // ---- through the input processor ----------------------------------------------------------------------------

    private RecordingWindow window;
    private InputProcessor processor;

    private void inWindow() {
        assumeTrue(FileDrag.isSupported(), "dragging out is only done on Windows");
        window = new RecordingWindow();
        processor = new InputProcessor();
        window.addContainer(tree);
        useSource();
    }

    private void send(Runnable... steps) {
        for (Runnable step : steps) step.run();
        processor.processEvents(window);
    }

    private Runnable move(double x, double y) {
        return () -> window.getInputManager().onCursorPos(0, x, y);
    }

    private Runnable press() {
        return () -> window.getInputManager().onMouseButton(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
    }

    private Runnable release() {
        return () -> window.getInputManager().onMouseButton(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
    }

    @Test
    void draggingARowPastTheEdgeStartsADragOfItsFiles() {
        inWindow();
        send(move(100, at(2)), press(), move(100, at(3)));
        assertTrue(window.started.isEmpty(), "still inside the window: an ordinary drag");

        send(move(-30, at(3)));
        assertEquals(1, window.started.size());
        assertEquals(List.of(List.of("b")), asked);

        send(move(-60, at(3)), release());
        assertEquals(1, window.started.size(), "handed over once");
        assertTrue(drops.isEmpty());
    }

    @Test
    void theRowsOwnDragIsOverOnceItIsHandedOver() {
        inWindow();
        tree.setDragEnabled(true);
        send(move(100, at(1)), press(), move(100, at(4)));
        assertTrue(tree.isDraggingRow());
        send(move(400, at(4))); // past the right edge
        assertFalse(tree.isDraggingRow());
        send(release());
        assertTrue(drops.isEmpty());
    }

    @Test
    void aDragThatNeverLeavesIsNotHandedOver() {
        inWindow();
        tree.setDragEnabled(true);
        send(move(100, at(1)), press(), move(100, at(4)), move(250, at(5)), release());
        assertTrue(window.started.isEmpty());
        assertEquals(1, drops.size());
    }

    @Test
    void aTreeWithoutASourceKeepsItsDragWhenThePointerLeaves() {
        inWindow();
        tree.setFileDragSource(null);
        tree.setDragEnabled(true);
        send(move(100, at(1)), press(), move(100, at(4)), move(-30, at(4)));
        assertTrue(window.started.isEmpty());
        assertTrue(tree.isDraggingRow());
    }
}
