package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Where a press goes when clickable Elements are nested, and when a clickable handle sits on a draggable panel. */
class ClickRoutingTest {
    private final Window window = new Window(new WindowOptions("test").setDimensions(300, 300));
    private final InputProcessor processor = new InputProcessor();
    private final List<String> heard = new ArrayList<>();

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

    private Container card() {
        Container card = new Container(10, 10, 200, 100);
        card.onMouseClick(_ -> heard.add("card"));
        ButtonOverlay button = new ButtonOverlay("Go", 80, 30);
        button.setPosition(10, 10);
        button.setClickOnRelease(false);
        button.onMouseClick(_ -> heard.add("button"));
        card.addElement(button);
        window.addContainer(card);
        return card;
    }

    @Test
    void aPressUsuallyReachesOnlyTheButtonUnderIt() {
        card();
        send(move(30, 30), press(), release());
        assertEquals(List.of("button"), heard);
    }

    @Test
    void aButtonThatPropagatesLetsItsCardHearTheClickToo() {
        Container card = card();
        card.getElements().values().iterator().next().setPropagateClicks(true);
        send(move(30, 30), press(), release());
        assertEquals(List.of("button", "card"), heard);
    }

    @Test
    void aPropagatingPressStopsAtTheFirstAncestorThatDoesNot() {
        Container card = card();
        card.getElements().values().iterator().next().setPropagateClicks(true);
        send(move(180, 90), press(), release()); // on the card, off the button
        assertEquals(List.of("card"), heard);
    }

    private Container panelWithClickableTitleBar(boolean dragsPanel) {
        Container panel = new Container(20, 20, 150, 100);
        panel.setDraggable(true);
        ButtonOverlay title = new ButtonOverlay("Title", 150, 20);
        title.setClickOnRelease(false);
        title.setDragsContainer(dragsPanel);
        title.onMouseClick(_ -> heard.add("title"));
        panel.addElement(title);
        window.addContainer(panel);
        return panel;
    }

    @Test
    void aClickableTitleBarKeepsItsPressByDefault() {
        Container panel = panelWithClickableTitleBar(false);
        send(move(50, 30), press(), move(90, 30), release());
        assertEquals(20, panel.getX(), 0.001);
        assertEquals(List.of("title"), heard);
    }

    @Test
    void aTitleBarThatDragsItsPanelStillClicksWhenTheMouseDoesNotMove() {
        Container panel = panelWithClickableTitleBar(true);
        send(move(50, 30), press(), release());
        assertEquals(20, panel.getX(), 0.001);
        assertEquals(List.of("title"), heard);
    }

    @Test
    void movingTheMouseOnItDragsThePanelInsteadOfClicking() {
        Container panel = panelWithClickableTitleBar(true);
        send(move(50, 30), press(), move(52, 30));
        assertEquals(20, panel.getX(), 0.001, "a couple of points is still a click");
        send(move(90, 40), release());
        assertEquals(60, panel.getX(), 0.001);
        assertEquals(30, panel.getY(), 0.001);
        assertEquals(List.of(), heard);
    }
}
