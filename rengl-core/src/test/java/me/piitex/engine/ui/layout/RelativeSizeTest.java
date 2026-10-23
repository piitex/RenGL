package me.piitex.engine.ui.layout;

import me.piitex.engine.ui.Anchor;
import me.piitex.engine.ui.containers.Container;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RelativeSizeTest {
    @Test
    void aChildKeepsItsFractionOfTheContainerAsItResizes() {
        Container box = new Container(400, 200);
        Container child = new Container(10, 10);
        box.addElement(child);
        child.setRelativeSize(0.5, 1);
        assertEquals(200, child.getWidth(), 1e-9);
        assertEquals(200, child.getHeight(), 1e-9);
        box.setWidth(600);
        box.setHeight(100);
        assertEquals(300, child.getWidth(), 1e-9);
        assertEquals(100, child.getHeight(), 1e-9);
    }

    @Test
    void anAxisWithoutAFractionKeepsItsOwnSize() {
        Container box = new Container(400, 200);
        Container child = new Container(40, 30);
        box.addElement(child);
        child.setRelativeWidth(0.25);
        assertEquals(100, child.getWidth(), 1e-9);
        assertEquals(30, child.getHeight(), 1e-9);
        child.setRelativeWidth(-1);
        child.setWidth(55);
        box.setWidth(800);
        assertEquals(55, child.getWidth(), 1e-9);
    }

    @Test
    void fillCoversTheContainerLeavingTheMargin() {
        Container box = new Container(400, 200);
        Container child = new Container(10, 10);
        box.addElement(child);
        child.fill(8);
        assertEquals(384, child.getWidth(), 1e-9);
        assertEquals(184, child.getHeight(), 1e-9);
        assertEquals(8, child.getX(), 1e-9);
        assertEquals(8, child.getY(), 1e-9);
        box.setWidth(300);
        assertEquals(284, child.getWidth(), 1e-9);
        assertEquals(Anchor.CENTER, child.getAnchor());
    }

    @Test
    void inALayoutTheFractionIsOfTheAreaInsideThePadding() {
        VerticalLayout column = new VerticalLayout(200, 300);
        column.setPadding(10);
        Container row = new Container(5, 40);
        column.addElement(row);
        row.setRelativeWidth(1);
        column.relayout();
        assertEquals(180, row.getWidth(), 1e-9);
        assertEquals(10, row.getX(), 1e-9);
    }

    @Test
    void weightedChildrenShareWhatTheOthersLeaveInARow() {
        HorizontalLayout row = new HorizontalLayout(500, 50);
        row.setSpacing(10);
        row.setPadding(5);
        Container fixed = new Container(100, 20), a = new Container(1, 20), b = new Container(1, 20);
        row.addElements(fixed, a, b);
        a.setLayoutWeight(1);
        b.setLayoutWeight(2);
        row.relayout();
        // 500 - 10 padding - 20 spacing - 100 fixed = 370
        assertEquals(100, fixed.getWidth(), 1e-9);
        assertEquals(370.0 / 3, a.getWidth(), 1e-9);
        assertEquals(740.0 / 3, b.getWidth(), 1e-9);
        assertEquals(5, fixed.getX(), 1e-9);
        assertEquals(a.getX() + a.getWidth() + 10, b.getX(), 1e-9);
        assertEquals(495, b.getX() + b.getWidth(), 1e-9);
        row.setWidth(300);
        row.relayout();
        assertEquals(170.0 / 3, a.getWidth(), 1e-9);
    }

    @Test
    void weightedChildrenShareAColumnsHeightAndStretchAcross() {
        VerticalLayout column = new VerticalLayout(120, 400);
        Container header = new Container(10, 50), body = new Container(10, 10);
        column.addElements(header, body);
        body.setLayoutWeight(1);
        body.setRelativeWidth(1);
        column.relayout();
        assertEquals(350, body.getHeight(), 1e-9);
        assertEquals(120, body.getWidth(), 1e-9);
        assertEquals(50, body.getY(), 1e-9);
    }

    @Test
    void aLayoutThatSizesToItsContentIgnoresWeights() {
        HorizontalLayout row = new HorizontalLayout(500, 50);
        Container a = new Container(80, 20);
        row.setSizeToContent(true, false);
        row.addElement(a);
        a.setLayoutWeight(1);
        row.relayout();
        assertEquals(80, a.getWidth(), 1e-9);
        assertEquals(80, row.getWidth(), 1e-9);
    }
}
