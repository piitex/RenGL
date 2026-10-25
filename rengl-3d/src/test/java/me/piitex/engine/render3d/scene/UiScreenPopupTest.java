package me.piitex.engine.render3d.scene;

import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.menu.MenuItem;
import me.piitex.engine.ui.menu.PopupMenu;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.ColorPickerOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dragging and popups on a screen in the 3D world. Everything is aimed with spots on the texture, the way a pick would do
 * it, and drawn into the texture on the CPU, so no GPU or window is involved.
 */
class UiScreenPopupTest {
    private static final double W = 240, H = 160;

    private static Vec2 at(double x, double y) {
        return new Vec2((float) (x / W), (float) (y / H));
    }

    /** Points at (x, y) in container points, and presses and lets go of the left button there. */
    private static boolean click(UiScreen screen, double x, double y) {
        screen.pointAt(at(x, y));
        boolean taken = screen.press();
        screen.release();
        return taken;
    }

    private static Container root() {
        Container root = new Container(W, H);
        root.getStyling().setBackgroundColor(new Color(0.1f, 0.1f, 0.12f, 1f));
        return root;
    }

    // ---- dragging -----------------------------------------------------------------------------------------------

    private static final class Card extends Container {
        int starts, updates, ends;
        double lastEndX = Double.NaN;

        Card(double x, double y, double w, double h) {
            super(x, y, w, h);
            setDraggable(true);
        }

        @Override
        public void onDragStart(double x, double y) {
            starts++;
            super.onDragStart(x, y);
        }

        @Override
        public void onDragUpdate(double x, double y) {
            updates++;
            super.onDragUpdate(x, y);
        }

        @Override
        public void onDragEnd(double x, double y) {
            ends++;
            lastEndX = x;
            super.onDragEnd(x, y);
        }
    }

    @Test
    void aDraggableContainerFollowsThePointerUntilTheRelease() {
        Container root = root();
        Card card = new Card(20, 20, 60, 40);
        card.getStyling().setBackgroundColor(Color.RED);
        root.addElement(card);
        try (UiScreen screen = new UiScreen(root)) {
            screen.pointAt(at(40, 30));
            assertTrue(screen.press(), "the press is the card's");
            assertEquals(1, card.starts);
            screen.pointAt(at(100, 70));
            assertEquals(80, card.getX(), 1.0, "moved 60 across");
            assertEquals(60, card.getY(), 1.0, "and 40 down");
            screen.pointAt(at(110, 70));
            assertEquals(90, card.getX(), 1.0);
            screen.release();
            assertEquals(1, card.ends);
            screen.pointAt(at(10, 10));
            assertEquals(90, card.getX(), 1.0, "after the release it stays where it was dropped");
        }
    }

    @Test
    void aSliderThumbCanBeDraggedAlongTheTrack() {
        Container root = root();
        SliderOverlay slider = new SliderOverlay(160, 0, 100, 0);
        slider.setPosition(20, 60);
        List<Double> values = new ArrayList<>();
        slider.onValueChange(values::add);
        root.addElement(slider);
        try (UiScreen screen = new UiScreen(root)) {
            double y = 60 + slider.getHeight() / 2;
            screen.pointAt(at(22, y));
            screen.press();
            screen.pointAt(at(100, y));
            double mid = slider.getValue();
            screen.pointAt(at(170, y));
            double far = slider.getValue();
            screen.release();
            assertTrue(mid > 30 && mid < 70, "about half way along: " + mid);
            assertTrue(far > mid, "and further on: " + far);
            assertFalse(values.isEmpty());
            double settled = slider.getValue();
            screen.pointAt(at(30, y));
            assertEquals(settled, slider.getValue(), 1e-9, "after the release the pointer no longer moves it");
        }
    }

    @Test
    void aDragThatLeavesTheScreenHoldsAndEndsAtTheRelease() {
        Container root = root();
        Card card = new Card(20, 20, 60, 40);
        root.addElement(card);
        try (UiScreen screen = new UiScreen(root)) {
            screen.pointAt(at(40, 30));
            screen.press();
            screen.pointAt(at(100, 30));
            screen.pointAway();
            assertFalse(screen.isPointing());
            assertEquals(0, card.ends, "the drag is not over just because the pointer left");
            double x = card.getX();
            screen.pointAt(at(130, 30)); // it comes back
            assertTrue(card.getX() > x, "and carries on");
            screen.pointAway();
            screen.release();
            assertEquals(1, card.ends);
        }
    }

    @Test
    void aPressOnAButtonIsNotADrag() {
        Container root = root();
        Card card = new Card(20, 20, 120, 80);
        ButtonOverlay button = new ButtonOverlay("Go", 60, 30);
        button.setPosition(30, 30);
        AtomicInteger clicks = new AtomicInteger();
        button.onMouseClick(e -> clicks.incrementAndGet());
        card.addElement(button);
        root.addElement(card);
        try (UiScreen screen = new UiScreen(root)) {
            // the button is at (30, 30) in the card, which is at (20, 20): it covers 50 to 110 across and 50 to 80 down
            screen.pointAt(at(70, 65));
            screen.press();
            screen.pointAt(at(130, 100));
            screen.release();
            assertEquals(0, card.starts, "a control keeps its press: nothing above it is dragged");
            assertEquals(0, clicks.get(), "and released elsewhere it does not click");
            click(screen, 70, 65);
            assertEquals(1, clicks.get());
        }
    }

    @Test
    void aClickableElementThatDragsItsContainerOnlyDoesSoOnceThePointerMoves() {
        Container root = root();
        Card card = new Card(20, 20, 120, 80);
        Container grip = new Container(10, 10, 60, 20);
        grip.setDragsContainer(true);
        AtomicInteger clicks = new AtomicInteger();
        grip.onMouseClick(e -> clicks.incrementAndGet());
        card.addElement(grip);
        root.addElement(card);
        try (UiScreen screen = new UiScreen(root)) {
            screen.pointAt(at(45, 40));
            screen.press();
            screen.pointAt(at(47, 41)); // under four points
            screen.release();
            assertEquals(0, card.starts);
            assertEquals(1, clicks.get(), "a click, since it did not move");

            double x = card.getX();
            screen.pointAt(at(45, 40));
            screen.press();
            screen.pointAt(at(75, 40));
            screen.release();
            assertEquals(1, card.starts, "past the threshold the press became a drag");
            assertEquals(1, clicks.get(), "and no longer a click");
            assertEquals(x + 30, card.getX(), 1.5);
        }
    }

    // ---- dropdown lists -----------------------------------------------------------------------------------------

    private static DropdownOverlay<String> dropdown(Container root, double x, double y, String... items) {
        DropdownOverlay<String> box = new DropdownOverlay<>(100, 24);
        box.setPosition(x, y);
        for (String item : items) box.addItem(item);
        root.addElement(box);
        return box;
    }

    @Test
    void pressingADropdownOpensItsListOnTheScreen() {
        Container root = root();
        DropdownOverlay<String> box = dropdown(root, 20, 20, "red", "green", "blue");
        try (UiScreen screen = new UiScreen(root)) {
            screen.refresh();
            int[] before = pixel(screen, 40, 20 + 24 + 12);
            assertFalse(box.isOpen());
            click(screen, 40, 32);
            assertTrue(box.isOpen());
            assertNotNull(screen.getPopup());
            assertEquals(20, screen.getPopup().getX(), 1e-6);
            assertEquals(44, screen.getPopup().getY(), 1e-6, "just under the box");
            screen.refresh();
            assertFalse(java.util.Arrays.equals(before, pixel(screen, 40, 44 + 12)), "the list is drawn over what was there");
        }
    }

    private static int[] pixel(UiScreen screen, int x, int y) {
        TextureData t = screen.texture();
        int i = (y * t.width() + x) * 4;
        byte[] p = t.pixels();
        return new int[]{p[i] & 255, p[i + 1] & 255, p[i + 2] & 255};
    }

    @Test
    void theRowUnderThePointerIsHighlightedInTheList() {
        Container root = root();
        dropdown(root, 20, 20, "red", "green", "blue").getStyling().setHoverColor(Color.RED);
        try (UiScreen screen = new UiScreen(root)) {
            click(screen, 40, 32);
            // the right end of the second row, clear of its text
            screen.pointAt(at(40, 44 + 24 + 12));
            screen.refresh();
            int[] hovered = pixel(screen, 110, 44 + 24 + 12);
            screen.pointAt(at(40, 44 + 48 + 12)); // now the third row
            screen.refresh();
            int[] plain = pixel(screen, 110, 44 + 24 + 12);
            assertFalse(java.util.Arrays.equals(hovered, plain), "the hover colour follows the pointer on the screen, not the mouse on the desktop");
        }
    }

    @Test
    void choosingARowSelectsItAndClosesTheList() {
        Container root = root();
        DropdownOverlay<String> box = dropdown(root, 20, 20, "red", "green", "blue");
        List<String> chosen = new ArrayList<>();
        box.onSelect(chosen::add);
        try (UiScreen screen = new UiScreen(root)) {
            click(screen, 40, 32);
            assertTrue(box.isOpen());
            click(screen, 40, 44 + 24 + 12); // the second row
            assertEquals(List.of("green"), chosen);
            assertFalse(box.isOpen());
            assertNull(screen.getPopup());
            assertEquals("green", box.getSelected());
        }
    }

    @Test
    void aPressOutsideTheListClosesItAndDoesNothingElse() {
        Container root = root();
        dropdown(root, 20, 20, "red", "green", "blue");
        ButtonOverlay button = new ButtonOverlay("Go", 60, 30);
        button.setPosition(140, 100);
        AtomicInteger clicks = new AtomicInteger();
        button.onMouseClick(e -> clicks.incrementAndGet());
        root.addElement(button);
        try (UiScreen screen = new UiScreen(root)) {
            click(screen, 40, 32);
            assertNotNull(screen.getPopup());
            assertTrue(click(screen, 160, 115), "the press is used up");
            assertNull(screen.getPopup());
            assertEquals(0, clicks.get(), "it did not also press the button underneath");
            click(screen, 160, 115);
            assertEquals(1, clicks.get(), "the next one does");
        }
    }

    @Test
    void escapeClosesAnOpenList() {
        Container root = root();
        DropdownOverlay<String> box = dropdown(root, 20, 20, "red", "green");
        try (UiScreen screen = new UiScreen(root)) {
            click(screen, 40, 32);
            assertTrue(screen.key(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0));
            assertFalse(box.isOpen());
            assertNull(screen.getPopup());
        }
    }

    @Test
    void aListNearTheBottomOpensAboveTheBox() {
        Container root = root();
        dropdown(root, 20, 130, "a", "b", "c", "d");
        try (UiScreen screen = new UiScreen(root)) {
            click(screen, 40, 142);
            Container popup = screen.getPopup();
            assertNotNull(popup);
            assertTrue(popup.getY() + popup.getHeight() <= 130 + 1e-6, "above the box, not off the bottom of the screen");
            assertTrue(popup.getY() >= 0);
        }
    }

    @Test
    void theWheelScrollsALongList() {
        Container root = root();
        DropdownOverlay<String> box = dropdown(root, 20, 20, "1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        box.setMaxVisibleRows(3);
        List<String> chosen = new ArrayList<>();
        box.onSelect(chosen::add);
        try (UiScreen screen = new UiScreen(root)) {
            click(screen, 40, 32);
            screen.pointAt(at(40, 44 + 12));
            assertTrue(screen.scroll(0, -3), "the list took the wheel");
            screen.pointAt(at(40, 44 + 12));
            screen.press();
            screen.release();
            assertEquals(1, chosen.size());
            assertNotEquals("1", chosen.getFirst(), "after scrolling the first row is not the top one");
        }
    }

    @Test
    void theContainerBehindAListStillHoversWhereThePointerIsOutsideIt() {
        Container root = root();
        dropdown(root, 20, 20, "red", "green");
        ButtonOverlay button = new ButtonOverlay("Go", 60, 30);
        button.setPosition(140, 100);
        root.addElement(button);
        try (UiScreen screen = new UiScreen(root)) {
            click(screen, 40, 32);
            screen.pointAt(at(160, 115));
            assertSame(button, screen.hovered());
            screen.pointAt(at(40, 56));
            assertNotSame(button, screen.hovered());
            assertFalse(button.isHovered());
        }
    }

    // ---- context menus ------------------------------------------------------------------------------------------

    @Test
    void aRightPressOpensTheContextMenuAtThePointer() {
        Container root = root();
        Container panel = new Container(20, 20, 200, 120);
        List<String> ran = new ArrayList<>();
        PopupMenu menu = new PopupMenu(new MenuItem("Open", () -> ran.add("open")), new MenuItem("Delete", () -> ran.add("delete")));
        panel.setContextMenu(menu);
        root.addElement(panel);
        try (UiScreen screen = new UiScreen(root)) {
            screen.pointAt(at(60, 50));
            assertTrue(screen.press(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
            screen.release(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            assertNotNull(screen.getPopup());
            assertTrue(screen.hasFocus(), "an open menu wants the keys");
            assertEquals(60, screen.getPopup().getX(), 1.0);
            assertEquals(50, screen.getPopup().getY(), 1.0);

            double row = 50 + 4 + menu.getItemHeight() * 1.5; // the middle of the second row
            click(screen, 70, row);
            assertEquals(List.of("delete"), ran);
            assertNull(screen.getPopup());
            assertFalse(screen.hasFocus());
        }
    }

    @Test
    void aMenuTakesTheArrowKeysAndEnter() {
        Container root = root();
        Container panel = new Container(20, 20, 200, 120);
        List<String> ran = new ArrayList<>();
        panel.setContextMenu(new PopupMenu(new MenuItem("One", () -> ran.add("one")), new MenuItem("Two", () -> ran.add("two"))));
        root.addElement(panel);
        try (UiScreen screen = new UiScreen(root)) {
            screen.pointAt(at(60, 50));
            screen.press(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            screen.release(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            assertTrue(screen.key(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0));
            assertTrue(screen.key(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0));
            assertTrue(screen.key(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0));
            assertFalse(ran.isEmpty(), "Enter ran the highlighted row: " + ran);
            assertNull(screen.getPopup());
        }
    }

    @Test
    void aMenuAtTheEdgeIsMovedBackInsideTheScreen() {
        Container root = root();
        Container panel = new Container(0, 0, W, H);
        panel.setContextMenu(new PopupMenu(new MenuItem("A long enough menu entry", () -> { }), new MenuItem("Second", () -> { })));
        root.addElement(panel);
        try (UiScreen screen = new UiScreen(root)) {
            screen.pointAt(at(W - 4, H - 4));
            screen.press(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            screen.release(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            Container popup = screen.getPopup();
            assertNotNull(popup);
            assertTrue(popup.getX() + popup.getWidth() <= W + 1e-6, "inside on the right");
            assertTrue(popup.getY() + popup.getHeight() <= H + 1e-6, "and at the bottom");
        }
    }

    @Test
    void keysAreTheGamesAgainOnceTheMenuIsGone() {
        Container root = root();
        Container panel = new Container(20, 20, 200, 120);
        panel.setContextMenu(new PopupMenu(new MenuItem("One", () -> { })));
        root.addElement(panel);
        try (UiScreen screen = new UiScreen(root)) {
            assertFalse(screen.key(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0), "no menu, no field: the game's");
            screen.pointAt(at(60, 50));
            screen.press(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            screen.release(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            screen.key(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0);
            assertNull(screen.getPopup());
            assertFalse(screen.key(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0));
        }
    }

    // ---- pickers ------------------------------------------------------------------------------------------------

    @Test
    void aColorPickerOpensItsPanelInsideTheScreen() {
        Container big = new Container(500, 500); // the panel is nearly as big as the small screens of the other tests
        big.getStyling().setBackgroundColor(new Color(0.1f, 0.1f, 0.12f, 1f));
        ColorPickerOverlay picker = new ColorPickerOverlay(40, 24, Color.RED);
        picker.setPosition(450, 470);
        big.addElement(picker);
        try (UiScreen screen = new UiScreen(big)) {
            screen.pointAt(new Vec2(460f / 500f, 480f / 500f));
            screen.press();
            screen.release();
            assertTrue(picker.isOpen());
            Container popup = screen.getPopup();
            assertNotNull(popup);
            assertTrue(popup.getX() >= 0 && popup.getX() + popup.getWidth() <= 500 + 1e-6, "pulled back inside across");
            assertTrue(popup.getY() >= 0 && popup.getY() + popup.getHeight() <= 500 + 1e-6, "and up or down");
            screen.refresh();
            screen.pointAt(new Vec2(5f / 500f, 5f / 500f)); // outside the panel, which hangs off the picker at the right
            assertTrue(popup.getX() > 10, "the corner is clear of the panel");
            screen.press();
            screen.release();
            assertFalse(picker.isOpen(), "a press outside closes it");
        }
    }

    // ---- the pointer while drawing -----------------------------------------------------------------------------

    @Test
    void whatDrawsOnTheScreenIsToldWhereThePointerIsOnIt() {
        Container root = root();
        double[] seen = {Double.NaN, Double.NaN, 0};
        Container probe = new Container(0, 0, 50, 50) {
            @Override
            public void render(me.piitex.engine.gl.renderer.Renderer renderer) {
                seen[0] = MouseTracker.getX();
                seen[1] = MouseTracker.getY();
                seen[2] = MouseTracker.isInWindow() ? 1 : 0;
                super.render(renderer);
            }
        };
        root.addElement(probe);
        double windowX = MouseTracker.getX();
        try (UiScreen screen = new UiScreen(root)) {
            screen.pointAt(at(120, 80));
            screen.refresh();
            assertEquals(120, seen[0], 0.01);
            assertEquals(80, seen[1], 0.01);
            assertEquals(1, seen[2]);
            screen.pointAway();
            screen.refresh();
            assertEquals(0, seen[2], "the pointer is not on the screen, so it is not 'in the window'");
        }
        assertEquals(windowX, MouseTracker.getX(), "the window's own pointer is back afterwards");
    }

    @Test
    void closingTheScreenLetsGoOfItsContainer() {
        Container root = root();
        DropdownOverlay<String> box = dropdown(root, 20, 20, "a", "b");
        assertNull(box.getPopupHost());
        UiScreen screen = new UiScreen(root);
        assertSame(screen, box.getPopupHost());
        click(screen, 40, 32);
        assertTrue(box.isOpen());
        screen.close();
        assertFalse(box.isOpen(), "an open list is closed with the screen");
        assertNull(box.getPopupHost());
    }

    @Test
    void anElementOnNeitherAWindowNorAScreenHasNowhereToOpenAList() {
        DropdownOverlay<String> box = new DropdownOverlay<>(100, 24);
        box.addItem("a");
        box.open();
        assertFalse(box.isOpen());
    }
}
