package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** A container that shows and does nothing where it is empty doesn't take the pointer from the one below. */
class EmptyContainerPassthroughTest {
    private final Window window = new Window(new WindowOptions("test").setDimensions(200, 200));
    private final InputProcessor processor = new InputProcessor();
    private final AtomicInteger clicks = new AtomicInteger();
    private final Container below = new Container(200, 200);

    EmptyContainerPassthroughTest() {
        ButtonOverlay button = new ButtonOverlay("Go", 80, 40);
        button.setClickOnRelease(false);
        button.onMouseClick(_ -> clicks.incrementAndGet());
        button.setPosition(20, 20);
        below.addElement(button);
        window.addContainer(below);
    }

    private void click(double x, double y) {
        InputManager manager = window.getInputManager();
        manager.onCursorPos(0, x, y);
        manager.onMouseButton(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
        manager.onMouseButton(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
        processor.processEvents(window);
    }

    @Test
    void aTransparentContainerAboveLetsAClickThrough() {
        window.addContainer(new Container(200, 200));
        click(40, 30);
        assertEquals(1, clicks.get());
    }

    @Test
    void aFilledContainerAboveStillTakesTheClick() {
        Container dim = new Container(200, 200);
        dim.getStyling().setBackgroundColor(new Color(0f, 0f, 0f, 0.5f));
        window.addContainer(dim);
        click(40, 30);
        assertEquals(0, clicks.get());
    }

    @Test
    void aContainerWithAClickListenerAboveStillTakesTheClick() {
        Container cover = new Container(200, 200);
        cover.onMouseClick(_ -> { });
        window.addContainer(cover);
        click(40, 30);
        assertEquals(0, clicks.get());
    }

    @Test
    void aTransparentContainerNestedAboveASiblingLetsAClickThrough() {
        Container panel = new Container(200, 200);
        ButtonOverlay button = new ButtonOverlay("Go", 80, 40);
        button.onMouseClick(_ -> clicks.incrementAndGet());
        button.setClickOnRelease(false);
        button.setPosition(20, 20);
        panel.addElement(button);
        panel.addElement(new Container(200, 200)); // drawn above the button, shows nothing
        window.addContainer(panel);
        clicks.set(0);
        click(40, 30);
        assertEquals(1, clicks.get());
    }

    @Test
    void thePassthroughRuleCoversTheKindsOfThingAContainerCanShow() {
        Container c = new Container(100, 100);
        assertTrue(c.isPointerPassthrough(1, 1));
        c.setDraggable(true);
        assertFalse(c.isPointerPassthrough(1, 1));
        c.setDraggable(false);
        c.getStyling().setBorderThickness(2f);
        assertTrue(c.isPointerPassthrough(1, 1), "a border with no color shows nothing");
        c.getStyling().setBorderColor(Color.WHITE);
        assertFalse(c.isPointerPassthrough(1, 1));
    }
}
