package me.piitex.engine.ui;

import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.SplitContainer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContainerRemovalTest {
    @Test
    void removingAChildClosesTheIndicesUp() {
        Container box = new Container(100, 100);
        Container a = new Container(1, 1), b = new Container(1, 1), c = new Container(1, 1);
        box.addElements(a, b, c);
        assertTrue(box.removeElement(a));
        assertEquals(List.of(0, 1), List.copyOf(box.getElements().keySet()));
        assertSame(b, box.getElements().get(0));
        assertSame(c, box.getElements().get(1));
        assertSame(c, box.removeElement(1));
        box.addElement(a);
        assertSame(a, box.getElements().get(1));
    }

    @Test
    void bringingAChildToTheFrontLeavesNoGap() {
        Container box = new Container(100, 100);
        Container a = new Container(1, 1), b = new Container(1, 1);
        box.addElements(a, b);
        a.bringToFront();
        assertEquals(List.of(0, 1), List.copyOf(box.getElements().keySet()));
        assertSame(b, box.getElements().get(0));
        assertSame(a, box.getElements().get(1));
    }

    @Test
    void aSplitKeepsItsSlots() {
        SplitContainer split = new SplitContainer(SplitContainer.Orientation.HORIZONTAL, 200, 100);
        Container left = new Container(1, 1), right = new Container(1, 1);
        split.setFirst(left);
        split.setSecond(right);
        split.removeElement(left);
        assertNull(split.getFirst());
        assertSame(right, split.getSecond());
    }
}
