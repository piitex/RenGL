package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.access.AccessibilityHost;
import me.piitex.engine.access.AccessibilityTree;
import me.piitex.engine.access.AccessibleNode;
import me.piitex.engine.access.AccessibleRole;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.RadioButtonOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Buttons, check boxes, radio buttons and sliders can be reached and used from the keyboard, without taking the
 * keyboard from a game, and tell an {@link AccessibilityHost} where the focus goes.
 */
class KeyboardControlsTest {
    private final Window window = new Window(new WindowOptions("test").setDimensions(400, 300));
    private final InputProcessor processor = new InputProcessor();
    private final List<String> events = new ArrayList<>();

    private final ButtonOverlay button = new ButtonOverlay("Save", 80, 30);
    private final CheckBoxOverlay box = new CheckBoxOverlay(20);
    private final SliderOverlay slider = new SliderOverlay(200, 0, 100, 50);
    private final TextFieldOverlay field = new TextFieldOverlay(120, 28);

    KeyboardControlsTest() {
        Container panel = new Container(0, 0, 400, 300);
        button.setPosition(10, 10);
        box.setPosition(10, 60);
        slider.setPosition(10, 100);
        field.setPosition(10, 160);
        panel.addElement(button);
        panel.addElement(box);
        panel.addElement(slider);
        panel.addElement(field);
        window.addContainer(panel);
        button.onAction(() -> events.add("save"));
        box.onToggle(on -> events.add("box " + on));
        slider.onValueChange(v -> events.add("slider " + v.intValue()));
    }

    private void key(int key, int mods) {
        window.getInputManager().onKey(0, key, 0, GLFW.GLFW_PRESS, mods);
        processor.processEvents(window);
        window.getInputManager().onKey(0, key, 0, GLFW.GLFW_RELEASE, mods);
        processor.processEvents(window);
    }

    private void key(int key) {
        key(key, 0);
    }

    private void click(double x, double y) {
        window.getInputManager().onCursorPos(0, x, y);
        window.getInputManager().onMouseButton(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
        window.getInputManager().onMouseButton(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
        processor.processEvents(window);
    }

    @Test
    void tabWalksTheControlsInOrderAndWraps() {
        key(GLFW.GLFW_KEY_TAB);
        assertSame(button, window.getFocusedElement());
        key(GLFW.GLFW_KEY_TAB);
        assertSame(box, window.getFocusedElement());
        key(GLFW.GLFW_KEY_TAB);
        assertSame(slider, window.getFocusedElement());
        key(GLFW.GLFW_KEY_TAB);
        assertSame(field, window.getFocusedElement());
        key(GLFW.GLFW_KEY_TAB);
        assertSame(button, window.getFocusedElement());
        key(GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_SHIFT);
        assertSame(field, window.getFocusedElement());
    }

    @Test
    void spaceAndEnterActivateTheFocusedControl() {
        key(GLFW.GLFW_KEY_TAB);
        key(GLFW.GLFW_KEY_SPACE);
        key(GLFW.GLFW_KEY_ENTER);
        key(GLFW.GLFW_KEY_TAB);
        key(GLFW.GLFW_KEY_SPACE);
        assertEquals(List.of("save", "save", "box true"), events);
        assertTrue(box.isChecked());
    }

    @Test
    void aRadioButtonIsSelectedFromTheKeyboard() {
        RadioButtonOverlay radio = new RadioButtonOverlay(18);
        radio.setPosition(10, 220);
        ((Container) window.getContainers().firstEntry().getValue()).addElement(radio);
        window.requestFocus(field);
        key(GLFW.GLFW_KEY_TAB);
        assertSame(radio, window.getFocusedElement());
        key(GLFW.GLFW_KEY_SPACE);
        assertTrue(radio.isSelected());
    }

    @Test
    void theArrowKeysMoveAFocusedSliderAndHomeAndEndJumpToTheEnds() {
        window.requestFocus(slider);
        key(GLFW.GLFW_KEY_RIGHT);
        assertEquals(51, slider.getValue(), 1e-9);
        key(GLFW.GLFW_KEY_LEFT, GLFW.GLFW_MOD_SHIFT);
        assertEquals(41, slider.getValue(), 1e-9);
        key(GLFW.GLFW_KEY_END);
        assertEquals(100, slider.getValue(), 1e-9);
        key(GLFW.GLFW_KEY_HOME);
        assertEquals(0, slider.getValue(), 1e-9);
        assertEquals(List.of("slider 51", "slider 41", "slider 100", "slider 0"), events);
    }

    @Test
    void aFocusedControlLeavesTheOtherKeysToTheGame() {
        key(GLFW.GLFW_KEY_TAB);
        assertSame(button, window.getFocusedElement());
        window.getInputManager().onKey(0, GLFW.GLFW_KEY_W, 0, GLFW.GLFW_PRESS, 0);
        processor.processEvents(window);
        assertTrue(window.getInput().isKeyDown(GLFW.GLFW_KEY_W), "W reaches the game while a button holds focus");
        assertTrue(events.isEmpty());
    }

    @Test
    void clickingAButtonDoesNotTakeTheKeyboardFromTheGame() {
        click(30, 20);
        assertEquals(List.of("save"), events);
        assertNull(window.getFocusedElement());
        window.getInputManager().onKey(0, GLFW.GLFW_KEY_W, 0, GLFW.GLFW_PRESS, 0);
        processor.processEvents(window);
        assertTrue(window.getInput().isKeyDown(GLFW.GLFW_KEY_W));
    }

    @Test
    void clickingATextFieldStillFocusesItAndKeepsTheKeysFromTheGame() {
        click(40, 170);
        assertSame(field, window.getFocusedElement());
        window.getInputManager().onKey(0, GLFW.GLFW_KEY_W, 0, GLFW.GLFW_PRESS, 0);
        processor.processEvents(window);
        assertFalse(window.getInput().isKeyDown(GLFW.GLFW_KEY_W));
    }

    @Test
    void theFocusRingShowsForTabButNotForAClick() {
        key(GLFW.GLFW_KEY_TAB);
        assertTrue(button.isFocusRingVisible());
        key(GLFW.GLFW_KEY_TAB);
        assertFalse(button.isFocusRingVisible());
        assertTrue(box.isFocusRingVisible());
        window.requestFocus(null);
        assertFalse(box.isFocusRingVisible());
        window.requestFocus(button); // code moved it, not a key
        assertFalse(button.isFocusRingVisible());
    }

    @Test
    void aDisabledControlIsSkipped() {
        box.setDisabled(true);
        key(GLFW.GLFW_KEY_TAB);
        key(GLFW.GLFW_KEY_TAB);
        assertSame(slider, window.getFocusedElement());
    }

    @Test
    void theHostHearsEveryFocusMoveAndAnnouncement() {
        List<String> heard = new ArrayList<>();
        window.setAccessibilityHost(new AccessibilityHost() {
            @Override
            public void focusChanged(Window w, Element previous, Element now, boolean byKeyboard) {
                heard.add((previous == null ? "-" : previous.getAccessibleRole()) + ">" + (now == null ? "-" : now.getAccessibleRole()) + (byKeyboard ? " key" : " code"));
            }

            @Override
            public void announce(Window w, String text, boolean urgent) {
                heard.add((urgent ? "!" : "") + text);
            }

            @Override
            public void close() {
                heard.add("closed");
            }
        });
        key(GLFW.GLFW_KEY_TAB);
        key(GLFW.GLFW_KEY_TAB);
        window.requestFocus(null);
        window.announce("Game saved");
        window.announce("Low health", true);
        window.announce("  ");
        window.setAccessibilityHost(null);
        assertEquals(List.of("->BUTTON key", "BUTTON>CHECK_BOX key", "CHECK_BOX>- code",
                "Game saved", "!Low health", "closed"), heard);
    }

    @Test
    void theHostHearsAToggleChangeFromTheKeyboardAndFromAClick() {
        me.piitex.engine.ui.overlays.ToggleSwitchOverlay toggle = new me.piitex.engine.ui.overlays.ToggleSwitchOverlay();
        toggle.setPosition(100, 60);
        ((Container) window.getContainers().firstEntry().getValue()).addElement(toggle);
        List<String> heard = new ArrayList<>();
        window.setAccessibilityHost(new AccessibilityHost() {
            @Override
            public void valueChanged(Window w, Element element) {
                heard.add(element.getAccessibleRole() + " " + element.getAccessibleChecked());
            }

            @Override
            public void focusChanged(Window w, Element previous, Element now, boolean byKeyboard) {
            }

            @Override
            public void announce(Window w, String text, boolean urgent) {
            }

            @Override
            public void close() {
            }
        });
        key(GLFW.GLFW_KEY_TAB);   // button
        key(GLFW.GLFW_KEY_TAB);   // box
        key(GLFW.GLFW_KEY_SPACE); // box on, by key
        click(15, 70);            // box off, by click
        click(110, 70);           // switch on, by click
        window.requestFocus(toggle);
        key(GLFW.GLFW_KEY_ENTER); // switch off, by key
        assertEquals(List.of("CHECK_BOX true", "CHECK_BOX false", "SWITCH true", "SWITCH false"), heard);
    }

    @Test
    void theTreeNamesWhatAPersonWouldMeet() {
        TextOverlay label = new TextOverlay("Music volume", 14f, me.piitex.engine.ui.color.Color.BLACK);
        slider.setLabelledBy(label);
        ((Container) window.getContainers().firstEntry().getValue()).addElement(label);
        box.setAccessibleName("Fullscreen");
        field.setAccessibleRole(AccessibleRole.TEXT_FIELD);
        window.requestFocus(box);

        List<AccessibleNode> nodes = AccessibilityTree.snapshot(window);
        assertEquals(List.of(AccessibleRole.BUTTON, AccessibleRole.CHECK_BOX, AccessibleRole.SLIDER,
                AccessibleRole.TEXT_FIELD, AccessibleRole.TEXT), nodes.stream().map(AccessibleNode::role).toList(),
                "the plain container is a layout box and is left out; its children are kept");
        assertEquals("Save", nodes.get(0).name());
        assertEquals("Fullscreen", nodes.get(1).name());
        assertEquals(Boolean.FALSE, nodes.get(1).checked());
        assertTrue(nodes.get(1).focused());
        assertEquals("Music volume", nodes.get(2).name());
        assertEquals("50", nodes.get(2).value());
    }

    @Test
    void hiddenAndDisabledAreHandledInTheTree() {
        box.setAccessibleHidden(true);
        button.setDisabled(true);
        List<AccessibleNode> nodes = AccessibilityTree.snapshot(window);
        assertEquals(3, nodes.size());
        assertTrue(nodes.get(0).disabled());
    }
}
