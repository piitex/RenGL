package me.piitex.engine.ui;

import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.SplitContainer;
import me.piitex.engine.ui.containers.TabContainer;
import me.piitex.engine.ui.layout.HorizontalLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContainerInsertTest {
    private static Container box() {
        return new Container(1, 1);
    }

    @Test
    void insertingShiftsTheOthersUp() {
        Container root = new Container(100, 100);
        Container a = box(), b = box(), c = box(), menu = box();
        root.addElements(a, b, c);
        root.insertElement(menu, 0);
        assertEquals(List.of(0, 1, 2, 3), List.copyOf(root.getElements().keySet()));
        assertEquals(List.of(menu, a, b, c), List.copyOf(root.getElements().values()));
        assertSame(root, menu.getParent());
    }

    @Test
    void insertingInTheMiddleKeepsTheOrder() {
        Container root = new Container(100, 100);
        Container a = box(), b = box(), c = box(), x = box();
        root.addElements(a, b, c);
        root.insertElement(x, 2);
        assertEquals(List.of(a, b, x, c), List.copyOf(root.getElements().values()));
    }

    @Test
    void anIndexPastTheEndOrAnEmptyContainerAppends() {
        Container root = new Container(100, 100);
        Container a = box(), b = box(), c = box();
        root.insertElement(a, 5);
        root.addElement(b);
        root.insertElement(c, 99);
        assertEquals(List.of(a, b, c), List.copyOf(root.getElements().values()));
        assertEquals(List.of(0, 1, 2), List.copyOf(root.getElements().keySet()));
    }

    @Test
    void aNegativeIndexIsTheBack() {
        Container root = new Container(100, 100);
        Container a = box(), x = box();
        root.addElement(a);
        root.insertElement(x, -3);
        assertEquals(List.of(x, a), List.copyOf(root.getElements().values()));
    }

    @Test
    void addElementAtAnIndexStillReplaces() {
        Container root = new Container(100, 100);
        Container a = box(), b = box(), x = box();
        root.addElements(a, b);
        root.addElement(x, 0);
        assertEquals(List.of(x, b), List.copyOf(root.getElements().values()));
    }

    @Test
    void aLayoutPlacesTheInsertedChildInItsRow() {
        HorizontalLayout row = new HorizontalLayout(0, 0, 400, 40);
        Container a = new Container(50, 20), b = new Container(50, 20), x = new Container(50, 20);
        row.addElements(a, b);
        double bBefore = b.getX();
        row.insertElement(x, 1);
        assertTrue(x.getX() < b.getX(), "the new child sits before b");
        assertTrue(b.getX() > bBefore, "b was pushed along");
        assertEquals(a.getX() < x.getX(), true);
    }

    @Test
    void aSplitAndATabContainerRefuse() {
        SplitContainer split = new SplitContainer(SplitContainer.Orientation.HORIZONTAL, 200, 100);
        assertThrows(UnsupportedOperationException.class, () -> split.insertElement(box(), 0));
        TabContainer tabs = new TabContainer(200, 100);
        assertThrows(UnsupportedOperationException.class, () -> tabs.insertElement(box(), 0));
    }
}
