package me.piitex.engine.ui.containers;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.SeparatorOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolAndStatusBarTest {
    @Test
    void toolBarButtonsRunTheirActions() {
        List<String> ran = new ArrayList<>();
        ToolBar bar = new ToolBar(400);
        ButtonOverlay open = bar.addButton("Open", () -> ran.add("open"));
        ButtonOverlay save = bar.addButton("Save", () -> ran.add("save"));
        open.getMouseClickListenerConsumer().accept(new MouseClickEvent(1, 1, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS));
        save.getMouseClickListenerConsumer().accept(new MouseClickEvent(1, 1, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS));
        assertEquals(List.of("open", "save"), ran);
    }

    @Test
    void toolBarLaysItsButtonsOutInARowWithSpacing() {
        ToolBar bar = new ToolBar(400);
        ButtonOverlay first = bar.addButton("A", () -> {
        });
        ButtonOverlay second = bar.addButton("B", () -> {
        });
        assertEquals(4, first.getX(), 0.001, "the padding");
        assertEquals(first.getX() + first.getWidth() + 4, second.getX(), 0.001, "then the spacing");
        assertEquals((40 - first.getHeight()) / 2, first.getY(), 0.001, "centred in the bar");
    }

    @Test
    void toolBarButtonsAreFlat() {
        ToolBar bar = new ToolBar(400);
        ButtonOverlay button = bar.addButton("Flat", () -> {
        });
        assertEquals(0f, button.getStyling().getBorderThickness(), 0.001);
        assertEquals(0f, ((me.piitex.engine.ui.color.Color) button.getStyling().getBackgroundColor()).getA(), 0.001);
    }

    @Test
    void aSpacerPushesWhatFollowsToTheFarEnd() {
        ToolBar bar = new ToolBar(400);
        bar.addButton("Left", () -> {
        });
        bar.addSpacer();
        ButtonOverlay right = bar.addButton("Right", () -> {
        });
        assertEquals(400 - 4 - right.getWidth(), right.getX(), 0.001);
    }

    @Test
    void aSeparatorIsAVerticalDividerBetweenGroups() {
        ToolBar bar = new ToolBar(400);
        bar.addButton("A", () -> {
        });
        SeparatorOverlay separator = bar.addSeparator();
        assertEquals(SeparatorOverlay.Orientation.VERTICAL, separator.getOrientation());
        assertTrue(separator.getHeight() > separator.getWidth());
        assertTrue(bar.getElements().containsValue(separator));
    }

    @Test
    void anyWidgetCanSitInTheToolBar() {
        ToolBar bar = new ToolBar(400);
        TextOverlay label = new TextOverlay("Zoom");
        bar.addElement(label);
        assertTrue(bar.getElements().containsValue(label));
    }

    @Test
    void statusBarKeepsTheMessageLeftAndReadoutsRight() {
        StatusBar status = new StatusBar(600);
        status.setMessage("Ready");
        TextOverlay position = status.addRightText("Ln 1, Col 1");
        TextOverlay zoom = status.addRightText("100%");
        TextOverlay message = firstText(status);
        assertEquals("Ready", status.getMessage());
        assertEquals(6, message.getX(), 0.001);
        assertEquals(600 - 6, zoom.getX() + zoom.getWidth(), 0.5, "the last readout touches the right padding");
        assertTrue(position.getX() + position.getWidth() < zoom.getX(), "the first readout is before it");
        assertTrue(position.getX() > message.getX() + message.getWidth());
    }

    @Test
    void statusBarDrawsADividerBetweenNeighboursOnTheSameSide() {
        StatusBar status = new StatusBar(600);
        long before = dividers(status);
        status.addRightText("one");
        assertEquals(before, dividers(status), "the first one on a side needs none");
        status.addRightText("two");
        assertEquals(before + 1, dividers(status));
        status.setMessage("Ready");
        status.addLeftText("a");
        assertEquals(before + 2, dividers(status), "a left readout is divided from the message");
    }

    @Test
    void theMessageCanChangeAndBeCleared() {
        StatusBar status = new StatusBar(600);
        assertEquals("", status.getMessage());
        status.setMessage(null);
        assertEquals("", status.getMessage());
        status.setMessage("Saving");
        status.setMessage("Saved");
        assertEquals("Saved", status.getMessage());
        status.setMessage("");
        assertEquals("", status.getMessage());
    }

    @Test
    void readoutsKeepTheirOrderWhenALeftOneIsAddedLater() {
        StatusBar status = new StatusBar(600);
        TextOverlay right = status.addRightText("right");
        TextOverlay left = status.addLeftText("left");
        assertTrue(left.getX() < right.getX());
        status.setMessage("late message");
        assertTrue(firstText(status).getX() < left.getX(), "a message set after still goes first");
    }

    private static TextOverlay firstText(StatusBar status) {
        for (Element child : status.getElements().values()) {
            if (child instanceof TextOverlay text) return text;
        }
        throw new AssertionError("no text");
    }

    private static long dividers(StatusBar status) {
        return status.getElements().values().stream().filter(e -> e instanceof SeparatorOverlay).count();
    }
}
