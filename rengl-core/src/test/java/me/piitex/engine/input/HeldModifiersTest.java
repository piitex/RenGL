package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.containers.TreeView;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A held Ctrl or Shift is known to the Elements that read it (a tree's Ctrl-click and Shift-click) even while an Element
 * has the keyboard, which takes the key press away from the game's own input.
 */
class HeldModifiersTest {
    private final Window window = new Window(new WindowOptions("test").setDimensions(300, 300));
    private final InputProcessor processor = new InputProcessor();
    private final TreeView<String> tree = new TreeView<>(200, 200, item -> List.of());

    private void send(Runnable step) {
        step.run();
        processor.processEvents(window);
    }

    private void key(int key, int action) {
        send(() -> window.getInputManager().onKey(0, key, 0, action, 0));
    }

    private void click(int row) {
        double y = row * 24 + 12;
        send(() -> window.getInputManager().onCursorPos(0, 50, y));
        send(() -> window.getInputManager().onMouseButton(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0));
        send(() -> window.getInputManager().onMouseButton(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0));
    }

    private void showTree() {
        tree.setRoots(List.of("a", "b", "c", "d", "e"));
        tree.setMultiSelect(true);
        window.addContainer(tree);
    }

    @Test
    void controlAndShiftAreHeldEvenWhenTheTreeHasTheKeyboard() {
        showTree();
        click(0); // the tree takes focus, so key presses go to it and not to the game
        assertSame(tree, window.getFocusedElement());

        key(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_PRESS);
        assertEquals(GLFW.GLFW_MOD_CONTROL, window.getInput().getModifiers());
        key(GLFW.GLFW_KEY_RIGHT_SHIFT, GLFW.GLFW_PRESS);
        assertEquals(GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT, window.getInput().getModifiers());
        key(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_RELEASE);
        assertEquals(GLFW.GLFW_MOD_SHIFT, window.getInput().getModifiers());
        key(GLFW.GLFW_KEY_RIGHT_SHIFT, GLFW.GLFW_RELEASE);
        assertEquals(0, window.getInput().getModifiers());
    }

    @Test
    void oneControlKeyLetGoWhileTheOtherIsHeldStillCountsAsControl() {
        showTree();
        click(0);
        key(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_PRESS);
        key(GLFW.GLFW_KEY_RIGHT_CONTROL, GLFW.GLFW_PRESS);
        key(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_RELEASE);
        assertEquals(GLFW.GLFW_MOD_CONTROL, window.getInput().getModifiers());
    }

    @Test
    void controlClickAddsRowsToTheSelectionOfAFocusedTree() {
        showTree();
        click(0);
        key(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_PRESS);
        click(2);
        click(4);
        key(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_RELEASE);

        assertEquals(List.of("a", "c", "e"), tree.getSelectedItems());
    }

    @Test
    void shiftClickSelectsTheRowsBetweenInAFocusedTree() {
        showTree();
        click(1);
        key(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_PRESS);
        click(3);
        key(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_RELEASE);

        assertEquals(List.of("b", "c", "d"), tree.getSelectedItems());
    }

    @Test
    void aClickAfterTheKeysAreLetGoSelectsOneRowAgain() {
        showTree();
        click(0);
        key(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_PRESS);
        click(2);
        key(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_RELEASE);
        click(3);

        assertEquals(List.of("d"), tree.getSelectedItems());
    }
}
