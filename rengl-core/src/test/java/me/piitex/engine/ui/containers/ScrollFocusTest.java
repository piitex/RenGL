package me.piitex.engine.ui.containers;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.input.InputProcessor;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tab never lands on a control that is scrolled out of the view. */
class ScrollFocusTest {
    private final Window window = new Window(new WindowOptions("test").setDimensions(300, 300));
    private final InputProcessor processor = new InputProcessor();
    private final ScrollContainer scroll = new ScrollContainer(0, 0, 200, 100);
    private final List<ButtonOverlay> rows = new ArrayList<>();

    ScrollFocusTest() {
        VerticalLayout layout = new VerticalLayout(200, 400);
        layout.setClipping(false);
        for (int i = 0; i < 10; i++) {
            ButtonOverlay row = new ButtonOverlay("Row " + i, 100, 30);
            rows.add(row);
            layout.addElement(row);
        }
        scroll.addElement(layout);
        window.addContainer(scroll);
    }

    private void tab(int mods) {
        window.getInputManager().onKey(0, GLFW.GLFW_KEY_TAB, 0, GLFW.GLFW_PRESS, mods);
        processor.processEvents(window);
    }

    private boolean inView(ButtonOverlay row) {
        double top = row.getAbsoluteY() - scroll.getAbsoluteY();
        return top >= 0 && top + row.getHeight() <= scroll.getHeight();
    }

    @Test
    void tabbingDownScrollsTheFocusedRowIntoView() {
        for (int i = 0; i < 10; i++) {
            tab(0);
            assertSame(rows.get(i), window.getFocusedElement());
            assertTrue(inView(rows.get(i)), "row " + i + " is in view");
        }
        assertTrue(scroll.getScrollOffsetY() > 0);
    }

    @Test
    void shiftTabFromTheTopWrapsToTheLastRowAndScrollsBack() {
        tab(0);
        tab(GLFW.GLFW_MOD_SHIFT); // wraps to the last
        assertSame(rows.get(9), window.getFocusedElement());
        assertTrue(inView(rows.get(9)));
        tab(0); // wraps to the first
        assertSame(rows.get(0), window.getFocusedElement());
        assertTrue(inView(rows.get(0)));
        assertEquals(0, scroll.getScrollOffsetY(), 1e-9);
    }

    @Test
    void aClickedRowDoesNotScroll() {
        window.requestFocus(rows.get(9));
        assertEquals(0, scroll.getScrollOffsetY(), 1e-9);
    }
}
