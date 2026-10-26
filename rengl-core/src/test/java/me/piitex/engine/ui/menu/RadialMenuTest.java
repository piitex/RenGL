package me.piitex.engine.ui.menu;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.PopupHost;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.events.MouseClickEvent;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RadialMenuTest {
    /** A popup host with no window: one popup at a time, an 800 by 600 area. */
    private static final class Host implements PopupHost {
        Container popup;
        Runnable onDismiss;

        @Override
        public void showPopup(Container popup, Element anchor, Runnable onDismiss) {
            closePopup();
            this.popup = popup;
            this.onDismiss = onDismiss;
        }

        @Override
        public void closePopup() {
            if (popup == null) return;
            Runnable dismiss = onDismiss;
            popup = null;
            onDismiss = null;
            if (dismiss != null) dismiss.run();
        }

        @Override
        public Container getPopup() {
            return popup;
        }

        @Override
        public double[] getPopupArea() {
            return new double[]{0, 0, 800, 600};
        }

        @Override
        public Element getFocusedElement() {
            return null;
        }

        @Override
        public void requestFocus(Element element) {
        }
    }

    // Four items centered on (400, 300): 0 up, 1 right, 2 down, 3 left. Ring from radius 36 to 110.
    private Host host;
    private RadialMenu menu;
    private final List<String> ran = new ArrayList<>();
    private int closed;

    @BeforeEach
    void setUp() {
        host = new Host();
        menu = new RadialMenu();
        menu.addItem("Up", () -> ran.add("Up"));
        menu.addItem("Right", () -> ran.add("Right"));
        menu.addItem("Down", () -> ran.add("Down"));
        menu.addItem("Left", () -> ran.add("Left"));
        menu.onClose(() -> closed++);
        menu.show(host, 400, 300);
    }

    private void click(double x, double y) {
        menu.getMouseClickListenerConsumer().accept(new MouseClickEvent(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS));
    }

    @Test
    void itOpensCenteredOnThePointAsThePopup() {
        assertTrue(menu.isOpen());
        assertSame(menu, host.getPopup());
        assertEquals(290, menu.getX(), 0.001);
        assertEquals(190, menu.getY(), 0.001);
        assertEquals(220, menu.getWidth(), 0.001);
    }

    @Test
    void itIsKeptInsideTheRoomTheHostHas() {
        menu.close();
        menu.show(host, 5, 5);
        assertEquals(0, menu.getX(), 0.001);
        assertEquals(0, menu.getY(), 0.001);
        menu.close();
        menu.show(host, 9999, 9999);
        assertEquals(800 - 220, menu.getX(), 0.001);
        assertEquals(600 - 220, menu.getY(), 0.001);
    }

    @Test
    void wedgesAreFoundByDirectionFromTheCenter() {
        assertEquals(0, menu.wedgeAt(400, 300 - 70));
        assertEquals(1, menu.wedgeAt(400 + 70, 300));
        assertEquals(2, menu.wedgeAt(400, 300 + 70));
        assertEquals(3, menu.wedgeAt(400 - 70, 300));
    }

    @Test
    void theWedgesMeetOnTheDiagonals() {
        assertEquals(0, menu.wedgeAt(400 + 50, 300 - 52)); // just less than 45 degrees from straight up
        assertEquals(1, menu.wedgeAt(400 + 52, 300 - 50));
        assertEquals(3, menu.wedgeAt(400 - 52, 300 - 50));
        assertEquals(0, menu.wedgeAt(400 - 50, 300 - 52));
    }

    @Test
    void theHubAndOutsideTheRingAreNoWedge() {
        assertEquals(-1, menu.wedgeAt(400, 300));
        assertEquals(-1, menu.wedgeAt(400, 300 - 20));
        assertEquals(-1, menu.wedgeAt(400, 300 - 120));
        assertEquals(-1, menu.wedgeAt(290 + 3, 190 + 3), "the corner of the square");
    }

    @Test
    void clickingAWedgeRunsItAndCloses() {
        click(400 + 70, 300);
        assertEquals(List.of("Right"), ran);
        assertFalse(menu.isOpen());
        assertEquals(1, closed);
    }

    @Test
    void clickingTheHubOrACornerClosesWithoutChoosing() {
        click(400, 300);
        assertTrue(ran.isEmpty());
        assertFalse(menu.isOpen());
        menu.show(host, 400, 300);
        click(290 + 3, 190 + 3);
        assertTrue(ran.isEmpty());
        assertFalse(menu.isOpen());
    }

    @Test
    void aDisabledItemCannotBePicked() {
        menu.getItems().get(1).setEnabled(false);
        assertEquals(-1, menu.wedgeAt(400 + 70, 300));
        click(400 + 70, 300);
        menu.pick(1);
        assertTrue(ran.isEmpty());
    }

    @Test
    void closingByAnyRouteReportsOnce() {
        host.closePopup();
        assertEquals(1, closed);
        assertFalse(menu.isOpen());
    }

    @Test
    void theArrowKeysWalkTheRingAndEnterPicks() {
        menu.onPopupKey(GLFW.GLFW_KEY_RIGHT, 0);
        assertEquals(0, menu.getLitIndex());
        menu.onPopupKey(GLFW.GLFW_KEY_RIGHT, 0);
        assertEquals(1, menu.getLitIndex());
        menu.onPopupKey(GLFW.GLFW_KEY_ENTER, 0);
        assertEquals(List.of("Right"), ran);
        assertFalse(menu.isOpen());
    }

    @Test
    void theKeysWrapAroundAndSkipDisabledItems() {
        menu.getItems().get(3).setEnabled(false);
        menu.onPopupKey(GLFW.GLFW_KEY_LEFT, 0); // from nothing, backward: the last enabled one
        assertEquals(2, menu.getLitIndex());
        menu.onPopupKey(GLFW.GLFW_KEY_RIGHT, 0); // 3 is disabled, so on around to 0
        assertEquals(0, menu.getLitIndex());
    }

    @Test
    void digitsPickByPositionAndEscapeCloses() {
        menu.onPopupKey(GLFW.GLFW_KEY_3, 0);
        assertEquals(List.of("Down"), ran);
        menu.show(host, 400, 300);
        menu.onPopupKey(GLFW.GLFW_KEY_9, 0); // there is no ninth item
        assertTrue(menu.isOpen());
        menu.onPopupKey(GLFW.GLFW_KEY_ESCAPE, 0);
        assertFalse(menu.isOpen());
        assertEquals(List.of("Down"), ran);
    }

    @Test
    void itCanBeShownAgainAfterClosing() {
        menu.close();
        menu.show(host, 200, 200);
        assertTrue(menu.isOpen());
        assertEquals(90, menu.getX(), 0.001);
        assertEquals(List.of(), ran);
    }

    @Test
    void aMenuWithNoItemsDoesNotOpen() {
        RadialMenu empty = new RadialMenu();
        empty.show(host, 100, 100);
        assertFalse(empty.isOpen());
    }

    @Test
    void theRadiiSetTheSizeOfThePopup() {
        menu.close();
        menu.setRadii(20, 80);
        menu.show(host, 400, 300);
        assertEquals(160, menu.getWidth(), 0.001);
        assertEquals(0, menu.wedgeAt(400, 300 - 50));
        assertEquals(-1, menu.wedgeAt(400, 300 - 90));
    }

    @Test
    void theRingIsDrawnAroundAClearHub() {
        menu.close();
        menu.setRadii(36, 110);
        menu.setWedgeColor(me.piitex.engine.ui.color.Color.RED);
        menu.setTextColor(me.piitex.engine.ui.color.Color.WHITE);
        RadialMenu drawn = menu;
        drawn.show(new Host(), 110, 110);
        // No pointer anywhere, whatever an earlier test left in the static tracker: a pointer over a wedge would light it.
        try (OffscreenRenderer r = new OffscreenRenderer(220, 220, 1f);
             var loan = me.piitex.engine.input.MouseTracker.lend(-1, -1, false)) {
            r.begin();
            drawn.render(r);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                int inWedge = bmp.getColor(110, 110 - 95), corner = bmp.getColor(3, 3);
                assertTrue(((inWedge >> 16) & 0xFF) > 150 && (inWedge & 0xFF) < 100, "the upper wedge is red");
                assertEquals(0, (corner >>> 24), "the square's corner stays clear");
                bmp.close();
            }
        }
        drawn.dispose();
    }
}
