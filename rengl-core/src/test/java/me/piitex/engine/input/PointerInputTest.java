package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.PointerInput;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.Overlay;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What an element that implements {@link PointerInput} hears: hover, every button, capture, the wheel. */
class PointerInputTest {
    private final Window window = new Window(new WindowOptions("test").setDimensions(400, 300));
    private final InputProcessor processor = new InputProcessor();
    private final List<String> heard = new ArrayList<>();

    private class Probe extends Overlay implements PointerInput {
        @Override
        public void onPointerMove(double x, double y, int buttons, int mods) {
            heard.add("move " + (int) x + "," + (int) y + " b" + buttons);
        }

        @Override
        public void onPointerButton(double x, double y, int button, boolean pressed, int mods) {
            heard.add((pressed ? "down " : "up ") + button + " " + (int) x + "," + (int) y);
        }

        @Override
        public void onPointerScroll(double x, double y, double dx, double dy, int mods) {
            heard.add("scroll " + (int) x + "," + (int) y + " " + (int) dy);
        }

        @Override
        public void onPointerLeave() {
            heard.add("leave");
        }
    }

    private void send(Runnable... steps) {
        for (Runnable step : steps) step.run();
        processor.processEvents(window);
    }

    private Runnable move(double x, double y) {
        return () -> window.getInputManager().onCursorPos(0, x, y);
    }

    private Runnable button(int button, int action) {
        return () -> window.getInputManager().onMouseButton(0, button, action, 0);
    }

    private Runnable scroll(double dy) {
        return () -> window.getInputManager().onScroll(0, 0.0, dy);
    }

    /** A probe at 50,50 sized 100x80, inside a container at 20,20 (so it sits at 70,70 in the window). */
    private Probe probe(Container container) {
        Probe probe = new Probe();
        probe.setPosition(50, 50);
        probe.setWidth(100);
        probe.setHeight(80);
        container.addElement(probe);
        window.addContainer(container);
        return probe;
    }

    private Probe probe() {
        return probe(new Container(20, 20, 300, 200));
    }

    @Test
    void hoverReachesItWithNoButtonHeldInItsOwnCoordinates() {
        probe();
        send(move(100, 100));
        assertEquals(List.of("move 30,30 b0"), heard);
    }

    @Test
    void leavingEndsTheHover() {
        probe();
        send(move(100, 100));
        heard.clear();
        send(move(10, 10));
        assertEquals(List.of("leave"), heard);
    }

    @Test
    void everyButtonIsPassedOnIncludingRightAndMiddle() {
        probe();
        send(move(100, 100));
        heard.clear();
        send(button(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS), button(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_RELEASE),
                button(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, GLFW.GLFW_PRESS), button(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, GLFW.GLFW_RELEASE));
        assertEquals(List.of("down 1 30,30", "up 1 30,30", "down 2 30,30", "up 2 30,30"), heard);
    }

    @Test
    void aPressKeepsThePointerUntilEveryButtonIsReleased() {
        probe();
        send(move(100, 100), button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS));
        heard.clear();
        send(move(300, 250)); // far outside it
        assertEquals(List.of("move 230,180 b1"), heard);
        heard.clear();
        send(button(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS), button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE), move(310, 250));
        assertEquals(List.of("down 1 230,180", "up 0 230,180", "move 240,180 b2"), heard);
        heard.clear();
        send(button(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_RELEASE), move(350, 250));
        assertEquals(List.of("up 1 240,180", "leave"), heard, "the pointer is free again and is over nothing");
    }

    @Test
    void theWheelCarriesItsPosition() {
        probe();
        send(move(100, 100));
        heard.clear();
        send(scroll(-3));
        assertEquals(List.of("scroll 30,30 -3"), heard);
    }

    @Test
    void theWheelOutsideItDoesNotReachIt() {
        probe();
        send(move(10, 10));
        heard.clear();
        send(scroll(-3));
        assertEquals(List.of(), heard);
    }

    @Test
    void aPressOnItDoesNotDragTheDraggableItSitsIn() {
        Container panel = new Container(20, 20, 300, 200);
        panel.setDraggable(true);
        probe(panel);
        send(move(100, 100), button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS), move(160, 130));
        assertEquals(20, panel.getX(), 0.001);
        assertEquals(20, panel.getY(), 0.001);
    }

    @Test
    void aDisabledOneHearsNothing() {
        probe().setDisabled(true);
        send(move(100, 100), button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS), button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE));
        assertEquals(List.of(), heard);
    }
}
