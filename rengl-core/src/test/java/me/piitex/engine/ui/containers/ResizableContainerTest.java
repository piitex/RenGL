package me.piitex.engine.ui.containers;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.layout.GridLayout;
import me.piitex.engine.ui.layout.HorizontalLayout;
import me.piitex.engine.ui.layout.VerticalLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResizableContainerTest {
    @Test
    void layoutGapsScaleWithTheirContainer() {
        ResizableContainer panel = new ResizableContainer(0, 0, 400, 200);
        HorizontalLayout row = new HorizontalLayout(0, 0, 400, 50);
        row.setSpacing(10);
        row.setPadding(8);
        VerticalLayout column = new VerticalLayout(0, 60, 400, 100);
        column.setSpacing(10);
        GridLayout grid = new GridLayout(2, 100, 100);
        grid.setColumnSpacing(4);
        grid.setRowSpacing(6);
        panel.addElements(row, column, grid);

        panel.resizeTo(800, 300);

        assertEquals(20f, row.getSpacing(), 0.001f, "across");
        assertEquals(15f, column.getSpacing(), 0.001f, "down");
        assertEquals(8f * (float) Math.sqrt(2 * 1.5), row.getPadding(), 0.001f, "padding by both");
        assertEquals(8f, grid.getColumnSpacing(), 0.001f);
        assertEquals(9f, grid.getRowSpacing(), 0.001f);

        panel.resizeTo(400, 200);
        assertEquals(10f, row.getSpacing(), 0.001f, "and back");
        assertEquals(8f, row.getPadding(), 0.001f);
    }

    @Test
    void aClampedContainerStaysCenteredOnItsPlaceInTheWindow() {
        Window window = new Window(new WindowOptions("test").setDimensions(1000, 500));
        ResizableContainer panel = new ResizableContainer(100, 100, 200, 100);
        panel.setMaxSize(300, 150);
        window.addContainer(panel);
        panel.update(0f); // takes the window's size as the reference

        window.getWindowOptions().setDimensions(2000, 1000);
        panel.update(0f);

        assertEquals(300, panel.getWidth(), 0.001);
        assertEquals(150, panel.getHeight(), 0.001);
        // The unclamped panel's middle is at (200, 150) in a 1000 x 500 window, so (400, 300) in the doubled one.
        assertEquals(400, panel.getX() + panel.getWidth() / 2, 0.001);
        assertEquals(300, panel.getY() + panel.getHeight() / 2, 0.001);
    }

    @Test
    void oneInsideAPlainContainerFollowsTheWindowToo() {
        Window window = new Window(new WindowOptions("test").setDimensions(1000, 500));
        Container root = new Container(1000, 500);
        ResizableContainer panel = new ResizableContainer(100, 50, 200, 100);
        root.addElement(panel);
        window.addContainer(root);
        panel.update(0f);

        window.getWindowOptions().setDimensions(2000, 1000);
        panel.update(0f);

        assertEquals(400, panel.getWidth(), 0.001);
        assertEquals(200, panel.getHeight(), 0.001);
        assertEquals(200, panel.getX(), 0.001);
        assertEquals(100, panel.getY(), 0.001);
    }

    @Test
    void oneInsideAnotherIsOnlyScaledByIt() {
        Window window = new Window(new WindowOptions("test").setDimensions(1000, 500));
        ResizableContainer outer = new ResizableContainer(0, 0, 1000, 500);
        ResizableContainer inner = new ResizableContainer(100, 50, 200, 100);
        outer.addElement(inner);
        window.addContainer(outer);
        outer.update(0f);

        window.getWindowOptions().setDimensions(2000, 1000);
        outer.update(0f);

        assertEquals(400, inner.getWidth(), 0.001, "scaled once, by the outer one, not again by the window");
        assertEquals(200, inner.getX(), 0.001);
    }
}
