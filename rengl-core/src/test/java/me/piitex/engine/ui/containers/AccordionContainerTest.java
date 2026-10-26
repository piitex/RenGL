package me.piitex.engine.ui.containers;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.overlays.TextOverlay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccordionContainerTest {
    private AccordionContainer accordion;
    private Container first, second, third;
    private AccordionContainer.Section a, b, c;
    private final List<String> toggles = new ArrayList<>();

    @BeforeEach
    void setUp() {
        accordion = new AccordionContainer(300);
        accordion.setAnimationSeconds(0f); // jump straight to open or shut
        first = new Container(100, 60);
        second = new Container(100, 40);
        third = new Container(100, 20);
        a = accordion.addSection("One", first, true);
        b = accordion.addSection("Two", second);
        c = accordion.addSection("Three", third);
        accordion.onToggle((section, open) -> toggles.add(section.getTitle() + (open ? " open" : " shut")));
    }

    private void clickHeaderAt(double y) {
        accordion.getMouseClickListenerConsumer().accept(new MouseClickEvent(50, y, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS));
    }

    @Test
    void heightIsTheTitleBarsPlusTheOpenBodies() {
        // 3 bars of 34, and the open body: 60 + 2 * 10 padding
        assertEquals(3 * 34 + 80, accordion.getHeight(), 0.001);
        b.setExpanded(true);
        assertEquals(3 * 34 + 80 + 60, accordion.getHeight(), 0.001);
        accordion.collapseAll();
        assertEquals(3 * 34, accordion.getHeight(), 0.001);
    }

    @Test
    void bodiesSitUnderTheirBarsAtTheAccordionsWidth() {
        b.setExpanded(true);
        assertEquals(10, first.getX(), 0.001);
        assertEquals(34 + 10, first.getY(), 0.001);
        assertEquals(280, first.getWidth(), 0.001);
        // second bar starts after the first bar and its open body
        assertEquals(34 + 80 + 34 + 10, second.getY(), 0.001);
    }

    @Test
    void aFoldedBodyIsNotEnabled() {
        assertTrue(first.isEnabled());
        assertFalse(second.isEnabled());
        b.setExpanded(true);
        assertTrue(second.isEnabled());
        b.setExpanded(false);
        assertFalse(second.isEnabled());
    }

    @Test
    void clickingABarTogglesItAndReports() {
        clickHeaderAt(34 + 80 + 17); // the second bar
        assertTrue(b.isExpanded());
        assertEquals(List.of("Two open"), toggles);
        clickHeaderAt(17); // the first
        assertFalse(a.isExpanded());
        assertEquals(List.of("Two open", "One shut"), toggles);
    }

    @Test
    void clickingABodyDoesNothing() {
        clickHeaderAt(34 + 30);
        assertTrue(a.isExpanded());
        assertTrue(toggles.isEmpty());
    }

    @Test
    void rightClicksAreIgnored() {
        accordion.getMouseClickListenerConsumer().accept(new MouseClickEvent(50, 17, GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS));
        assertTrue(a.isExpanded());
    }

    @Test
    void theSettersAreSilent() {
        b.setExpanded(true);
        a.toggle();
        assertTrue(toggles.isEmpty());
    }

    @Test
    void exclusiveFoldsTheOthers() {
        accordion.setExclusive(true);
        b.setExpanded(true);
        assertFalse(a.isExpanded());
        assertTrue(b.isExpanded());
        clickHeaderAt(34 + 34 + 40 + 20 + 17); // third bar: after bar one, bar two with its open body
        assertTrue(c.isExpanded());
        assertFalse(b.isExpanded());
    }

    @Test
    void turningExclusiveOnKeepsOnlyTheFirstOpenSection() {
        b.setExpanded(true);
        c.setExpanded(true);
        accordion.setExclusive(true);
        assertTrue(a.isExpanded());
        assertFalse(b.isExpanded());
        assertFalse(c.isExpanded());
    }

    @Test
    void expandAllOpensEverythingExceptWhenExclusive() {
        accordion.expandAll();
        assertTrue(a.isExpanded() && b.isExpanded() && c.isExpanded());
        accordion.collapseAll();
        accordion.setExclusive(true);
        accordion.expandAll();
        assertFalse(b.isExpanded());
    }

    @Test
    void aBodyChangingHeightMovesTheBarsBelow() {
        b.setExpanded(true);
        double before = accordion.getHeight();
        second.setHeight(100);
        accordion.relayout();
        assertEquals(before + 60, accordion.getHeight(), 0.001);
    }

    @Test
    void theBodySlidesOpenOverTheAnimationTime() {
        AccordionContainer slow = new AccordionContainer(300);
        Container body = new Container(100, 100);
        AccordionContainer.Section section = slow.addSection("Slide", body);
        slow.setAnimationSeconds(1f);
        section.setExpanded(true);
        assertEquals(34, slow.getHeight(), 0.001, "nothing has moved yet");
        slow.update(0.5f);
        assertTrue(slow.getHeight() > 34 && slow.getHeight() < 34 + 120, "halfway: " + slow.getHeight());
        assertTrue(body.isEnabled());
        slow.update(0.6f);
        assertEquals(34 + 120, slow.getHeight(), 0.001);
        section.setExpanded(false);
        slow.update(2f);
        assertEquals(34, slow.getHeight(), 0.001);
        assertFalse(body.isEnabled());
    }

    @Test
    void keysMoveBetweenBarsAndToggle() {
        accordion.onKeyPressed(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0);
        accordion.onKeyPressed(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0);
        assertTrue(b.isExpanded());
        accordion.onKeyPressed(GLFW.GLFW_KEY_SPACE, GLFW.GLFW_PRESS, 0);
        assertFalse(b.isExpanded());
        assertEquals(List.of("Two open", "Two shut"), toggles);
        accordion.onKeyPressed(GLFW.GLFW_KEY_END, GLFW.GLFW_PRESS, 0);
        accordion.onKeyPressed(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0);
        assertTrue(c.isExpanded());
    }

    @Test
    void removingASectionRemovesItsBody() {
        accordion.removeSection(a);
        assertEquals(2, accordion.getSections().size());
        assertFalse(accordion.getElements().containsValue(first));
        assertEquals(2 * 34, accordion.getHeight(), 0.001);
    }

    @Test
    void anAccordionDrawsWithoutFailing() {
        accordion.addSection("Text", new TextOverlay("hello"), true);
        try (OffscreenRenderer r = new OffscreenRenderer(300, 300, 1f)) {
            r.begin();
            accordion.render(r);
            r.end();
        }
        accordion.dispose();
    }
}
