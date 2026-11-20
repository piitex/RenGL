package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.ListBox;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Controls in a list's rows: made only for the rows on screen, reused as it scrolls, and pressed through the window. */
class ListBoxRowWidgetTest {
    private static final class Mod {
        final String name;
        boolean enabled;

        Mod(String name, boolean enabled) {
            this.name = name;
            this.enabled = enabled;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final float ROW = 30, WIDTH = 300, HEIGHT = 200;

    private final Window window = new Window(new WindowOptions("test").setDimensions(600, 400));
    private final InputProcessor processor = new InputProcessor();
    private final List<Mod> mods = new ArrayList<>();
    private ListBox<Mod> list;
    private int made, binds;

    @BeforeEach
    void setUp() {
        for (int i = 0; i < 100; i++) mods.add(new Mod("mod " + i, i % 2 == 0));
        list = new ListBox<>(WIDTH, HEIGHT);
        list.setRowHeight(ROW);
        list.setRowWidget(() -> {
            made++;
            return new ToggleSwitchOverlay(40, 22);
        }, (toggle, mod) -> {
            binds++;
            toggle.setOn(mod.enabled);
            toggle.onToggle(on -> mod.enabled = on);
        });
        list.setItems(mods);
        window.addContainer(list);
        list.update(0);
    }

    private void send(Runnable... steps) {
        for (Runnable step : steps) step.run();
        processor.processEvents(window);
        list.update(0);
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

    private Runnable key(int key) {
        return () -> {
            window.getInputManager().onKey(0, key, 0, GLFW.GLFW_PRESS, 0);
            window.getInputManager().onKey(0, key, 0, GLFW.GLFW_RELEASE, 0);
        };
    }

    private ToggleSwitchOverlay toggle(int row) {
        return (ToggleSwitchOverlay) list.getRowWidget(mods.get(row));
    }

    private Runnable clickOn(Element widget) {
        double x = widget.getAbsoluteX() + widget.getWidth() / 2, y = widget.getAbsoluteY() + widget.getHeight() / 2;
        return () -> {
            move(x, y).run();
            processor.processEvents(window);
            press().run();
            processor.processEvents(window);
            release().run();
        };
    }

    @Test
    void onlyTheRowsOnScreenGetControls() {
        int onScreen = (int) Math.ceil(HEIGHT / ROW) + 1;
        assertTrue(made <= onScreen, "made " + made);
        assertNotNull(toggle(0));
        assertNull(toggle(50), "row 50 is far below the list");
    }

    @Test
    void aControlSitsAtTheRightEndOfItsRow() {
        Element t = toggle(2);
        assertEquals(2 * ROW + (ROW - 22) / 2, t.getAbsoluteY(), 1e-6);
        assertTrue(t.getAbsoluteX() + t.getWidth() <= WIDTH - 8 + 1e-6, "inside the padding");
        assertTrue(t.getAbsoluteX() > WIDTH / 2, "at the right");
        assertEquals(40, t.getWidth(), 1e-9, "keeps its own width");
    }

    @Test
    void fillStretchesTheControlOverTheRow() {
        list.setRowWidgetFill(true);
        list.update(0);
        Element t = toggle(0);
        assertEquals(ROW - 4, t.getHeight(), 1e-9);
        assertTrue(t.getWidth() > WIDTH / 2, "width " + t.getWidth());
    }

    @Test
    void scrollingHandsTheSameControlsToOtherRows() {
        int before = made;
        list.scrollTo(0, 51 * ROW);
        list.update(0);
        assertEquals(before, made, "nothing new is made, the old controls move down");
        assertNull(toggle(0));
        assertFalse(toggle(51).isOn(), "bound to mod 51, which is off");
        assertTrue(toggle(52).isOn());
    }

    @Test
    void refreshBindsTheControlsOnScreenAgain() {
        mods.get(1).enabled = true;
        int before = binds;
        list.refresh();
        list.update(0);
        assertTrue(binds > before);
        assertTrue(toggle(1).isOn());
    }

    @Test
    void aFilterMovesAControlWithItsItem() {
        Element third = toggle(4);
        list.setFilter(m -> m.enabled); // the even mods: mod 4 is the third row now
        list.update(0);
        assertSame(third, toggle(4));
        assertEquals(2 * ROW + (ROW - 22) / 2, third.getAbsoluteY(), 1e-6);
    }

    @Test
    void clickingASwitchFlipsItsItemAndSelectsTheRow() {
        send(clickOn(toggle(3)));
        assertTrue(mods.get(3).enabled);
        assertEquals(mods.get(3), list.getSelected());
    }

    @Test
    void doubleClickingAControlDoesNotActivateTheRow() {
        List<Mod> activated = new ArrayList<>();
        list.onActivate(activated::add);
        send(clickOn(toggle(2)));
        send(clickOn(toggle(2)));
        assertTrue(activated.isEmpty());
        assertTrue(mods.get(2).enabled, "flipped twice, back on");
    }

    @Test
    void clickingTheLabelStillSelectsAndActivates() {
        List<Mod> activated = new ArrayList<>();
        list.onActivate(activated::add);
        double y = 5 * ROW + ROW / 2;
        send(move(20, y), press(), release(), press(), release());
        assertEquals(mods.get(5), list.getSelected());
        assertEquals(List.of(mods.get(5)), activated);
        assertFalse(mods.get(5).enabled, "the switch was not touched");
    }

    @Test
    void aHeldControlKeepsItsItemWhenItsRowScrollsAway() {
        ToggleSwitchOverlay t = toggle(0);
        send(move(t.getAbsoluteX() + 5, t.getAbsoluteY() + 5), press());
        list.scrollTo(0, 40 * ROW);
        list.update(0);
        assertSame(t, toggle(0), "still held");
        send(release());
        assertNull(toggle(0), "let go, it goes back to the pool");
    }

    @Test
    void rowControlsAreNotTabStops() {
        assertFalse(toggle(0).isFocusTraversable());
    }

    @Test
    void f2MovesTheKeyboardIntoTheRowAndEscapeBringsItBack() {
        list.setSelected(mods.get(1));
        window.requestFocus(list);
        send(key(GLFW.GLFW_KEY_F2));
        assertSame(toggle(1), window.getFocusedElement());

        list.scrollTo(0, 60 * ROW);
        list.update(0);
        assertSame(window.getFocusedElement(), toggle(1), "a control with the keyboard is kept off screen");

        send(key(GLFW.GLFW_KEY_ESCAPE));
        assertSame(list, window.getFocusedElement());
        assertNull(toggle(1));
    }

    @Test
    void enterWithoutAnActivateHandlerFocusesTheControl() {
        list.setSelected(mods.get(0));
        window.requestFocus(list);
        send(key(GLFW.GLFW_KEY_ENTER));
        assertSame(toggle(0), window.getFocusedElement());
    }

    @Test
    void aControlWhoseItemLeavesIsTakenOutAndTheListGetsTheKeyboard() {
        list.setSelected(mods.get(0));
        window.requestFocus(list);
        send(key(GLFW.GLFW_KEY_F2));
        Element focused = window.getFocusedElement();
        assertSame(toggle(0), focused);
        mods.removeFirst();
        list.setItems(mods);
        list.update(0);
        assertSame(list, window.getFocusedElement());
    }

    @Test
    void aContainerRowWithAButtonWorks() {
        List<String> clicks = new ArrayList<>();
        list.setRowWidget(() -> {
            ButtonOverlay button = new ButtonOverlay("Go", 50, 22);
            button.setClickOnRelease(false);
            return button;
        }, (button, mod) -> button.onMouseClick(_ -> clicks.add(mod.name)));
        list.update(0);
        assertNotNull(list.getRowWidget(mods.get(0)));
        assertFalse(list.getRowWidget(mods.get(0)) instanceof ToggleSwitchOverlay, "the old switches are gone");
        send(clickOn(list.getRowWidget(mods.get(4))));
        assertEquals(List.of("mod 4"), clicks);
    }

    @Test
    void turningTheControlsOffTakesThemAway() {
        list.setRowWidget(null, null);
        list.update(0);
        assertNull(toggle(0));
        assertTrue(list.getElements().isEmpty());
    }
}
