package me.piitex.engine.ui.overlays;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.input.Binding;
import me.piitex.engine.input.Gamepad;
import me.piitex.engine.input.Input;
import me.piitex.engine.input.InputContext;
import me.piitex.engine.ui.events.MouseClickEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The controller side of {@link HotkeyOverlay}: learning buttons and sticks, a box only for the pad, two boxes per action. */
class HotkeyOverlayGamepadTest {
    private final Window window = new Window(new WindowOptions("test").setDimensions(200, 200));
    private final Input input = window.getInput();
    private final List<Binding> changes = new ArrayList<>();
    private HotkeyOverlay box;

    @BeforeEach
    void setUp() {
        box = boxIn(input);
        box.onBindingChange(changes::add);
    }

    private static HotkeyOverlay boxIn(Input input) {
        HotkeyOverlay made = new HotkeyOverlay(160, 32) {
            @Override
            protected Input currentInput() {
                return input;
            }
        };
        made.setPosition(0, 0);
        return made;
    }

    private void click() {
        box.getMouseClickListenerConsumer().accept(new MouseClickEvent(10, 10, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS));
    }

    private static final Binding PAD_A = Binding.gamepad(Gamepad.Button.A);
    private static final Binding PAD_B = Binding.gamepad(Gamepad.Button.B);

    @Test
    void aBoxThatDoesNotAllowTheControllerDoesNotListenToIt() {
        click();
        assertTrue(box.isListening());
        assertFalse(input.isListeningForGamepad());
        assertEquals("Press a key...", box.getDisplayText());
    }

    @Test
    void listeningAsksTheInputForTheNextControllerPress() {
        box.setAllowGamepad(true);
        assertTrue(box.isAllowGamepad());
        click();
        assertTrue(input.isListeningForGamepad());
        assertEquals("Press a key or button...", box.getDisplayText());
    }

    @Test
    void theNextControllerPressBecomesTheBinding() {
        box.setAllowGamepad(true);
        click();
        box.learnGamepad(PAD_B);
        assertEquals(PAD_B, box.getBinding());
        assertEquals(List.of(PAD_B), changes);
        assertFalse(box.isListening());
        assertFalse(input.isListeningForGamepad());
        assertEquals("B", box.getDisplayText());
    }

    @Test
    void aKeyIsStillLearnedByABoxThatAlsoAllowsTheController() {
        box.setAllowGamepad(true);
        click();
        box.onKeyPressed(GLFW.GLFW_KEY_J, GLFW.GLFW_PRESS, 0);
        assertEquals(Binding.key(GLFW.GLFW_KEY_J), box.getBinding());
        assertFalse(input.isListeningForGamepad(), "the prompt waiting for a button is taken down");
    }

    @Test
    void cancellingTakesThePromptDownToo() {
        box.setAllowGamepad(true);
        click();
        box.onKeyPressed(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0);
        assertFalse(box.isListening());
        assertFalse(input.isListeningForGamepad());
        assertNull(box.getBinding());
        assertEquals(List.of(), changes);
    }

    @Test
    void losingFocusTakesThePromptDown() {
        box.setAllowGamepad(true);
        click();
        box.onFocusLost();
        assertFalse(input.isListeningForGamepad());
    }

    @Test
    void aBoxDoesNotCancelAPromptThatIsNotItsOwn() {
        box.setAllowGamepad(true);
        List<Binding> elsewhere = new ArrayList<>();
        input.listenForGamepad(elsewhere::add); // someone else is waiting
        box.stopListening();
        assertTrue(input.isListeningForGamepad());
    }

    @Test
    void aRefusedControllerInputKeepsListening() {
        box.setAllowGamepad(true);
        box.setConflictCheck(b -> b.equals(PAD_A) ? "Taken" : null);
        click();
        box.learnGamepad(PAD_A);
        assertTrue(box.isListening());
        assertEquals("Taken", box.getDisplayText());
        assertTrue(input.isListeningForGamepad(), "still waiting for another button");
        box.learnGamepad(PAD_B);
        assertEquals(PAD_B, box.getBinding());
    }

    @Test
    void aSticksDirectionIsLearnedToo() {
        box.setAllowGamepad(true);
        click();
        Binding left = Binding.gamepadAxis(Gamepad.Axis.LEFT_X, false);
        box.learnGamepad(left);
        assertEquals(left, box.getBinding());
        assertEquals("Left Stick Left", box.getDisplayText());
    }

    // ---------------------------------------------------------------- a box only for the pad

    @Test
    void aControllerBoxIgnoresKeysExceptEscapeAndBackspace() {
        box.setGamepadOnly(true);
        assertTrue(box.isAllowGamepad());
        box.setBinding(PAD_A);
        click();
        assertEquals("Press a button...", box.getDisplayText());
        box.onKeyPressed(GLFW.GLFW_KEY_J, GLFW.GLFW_PRESS, 0);
        box.onKeyPressed(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL);
        assertTrue(box.isListening());
        assertEquals("Press a button...", box.getDisplayText(), "no Ctrl+ preview either");
        assertEquals(PAD_A, box.getBinding());
        box.onKeyPressed(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_PRESS, 0);
        assertNull(box.getBinding());
        assertEquals(1, changes.size());
        assertNull(changes.getFirst());
        assertFalse(box.isListening());
    }

    @Test
    void aControllerBoxIgnoresMouseButtonsAndTheWheelEvenWhenMouseIsAllowed() {
        box.setGamepadOnly(true);
        box.setAllowMouse(true);
        click();
        box.getMouseClickListenerConsumer().accept(new MouseClickEvent(10, 10, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, GLFW.GLFW_PRESS));
        box.onScroll(0, 1);
        assertTrue(box.isListening());
        assertNull(box.getBinding());
        assertFalse(box.canScroll(0, 1));
    }

    @Test
    void aControllerBoxReturnsOnEnterOrSpaceLikeAnyOther() {
        box.setGamepadOnly(true);
        box.onKeyPressed(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0);
        assertTrue(box.isListening());
        assertTrue(input.isListeningForGamepad());
    }

    @Test
    void theListenTextCanBeSetAndPutBack() {
        box.setAllowGamepad(true);
        box.setListenText("Push something");
        click();
        assertEquals("Push something", box.getDisplayText());
        box.setListenText(null);
        assertEquals("Press a key or button...", box.getDisplayText());
    }

    // ---------------------------------------------------------------- naming

    @Test
    void buttonsAreNamedForTheLayoutSet() {
        box.setBinding(PAD_A);
        assertEquals("A", box.getDisplayText(), "an Xbox layout when no pad is plugged in");
        box.setLayout(Gamepad.Layout.PLAYSTATION);
        assertEquals("Cross", box.getDisplayText());
        box.setLayout(Gamepad.Layout.NINTENDO);
        assertEquals("B", box.getDisplayText());
        box.setBinding(Binding.key(GLFW.GLFW_KEY_J));
        assertEquals("J", box.getDisplayText());
    }

    // ---------------------------------------------------------------- an action with a key and a button

    private InputContext controls() {
        return new InputContext("test")
                .bind("jump", Binding.key(GLFW.GLFW_KEY_SPACE), PAD_A)
                .bind("fire", Binding.key(GLFW.GLFW_KEY_F));
    }

    @Test
    void theKeyboardBoxEditsTheFirstKeyAndLeavesTheButton() {
        InputContext controls = controls();
        box.bindTo(controls, "jump");
        assertEquals(Binding.key(GLFW.GLFW_KEY_SPACE), box.getBinding());
        click();
        box.onKeyPressed(GLFW.GLFW_KEY_E, GLFW.GLFW_PRESS, 0);
        assertEquals(List.of(Binding.key(GLFW.GLFW_KEY_E), PAD_A), controls.getBindings("jump"));
    }

    @Test
    void theKeyboardBoxShowsTheKeyEvenWhenTheButtonComesFirst() {
        InputContext controls = new InputContext("test").bind("jump", PAD_A, Binding.key(GLFW.GLFW_KEY_SPACE));
        box.bindTo(controls, "jump");
        assertEquals(Binding.key(GLFW.GLFW_KEY_SPACE), box.getBinding());
        click();
        box.onKeyPressed(GLFW.GLFW_KEY_E, GLFW.GLFW_PRESS, 0);
        assertEquals(List.of(PAD_A, Binding.key(GLFW.GLFW_KEY_E)), controls.getBindings("jump"));
    }

    @Test
    void theControllerBoxEditsTheFirstButtonAndLeavesTheKey() {
        InputContext controls = controls();
        box.bindToGamepad(controls, "jump");
        assertTrue(box.isGamepadOnly());
        assertEquals(PAD_A, box.getBinding());
        click();
        box.learnGamepad(PAD_B);
        assertEquals(List.of(Binding.key(GLFW.GLFW_KEY_SPACE), PAD_B), controls.getBindings("jump"));
    }

    @Test
    void anActionWithNoButtonGainsOneAtTheEnd() {
        InputContext controls = controls();
        box.bindToGamepad(controls, "fire");
        assertNull(box.getBinding());
        click();
        box.learnGamepad(PAD_B);
        assertEquals(List.of(Binding.key(GLFW.GLFW_KEY_F), PAD_B), controls.getBindings("fire"));
    }

    @Test
    void unbindingTheControllerBoxRemovesOnlyTheButton() {
        InputContext controls = controls();
        box.bindToGamepad(controls, "jump");
        click();
        box.onKeyPressed(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_PRESS, 0);
        assertEquals(List.of(Binding.key(GLFW.GLFW_KEY_SPACE)), controls.getBindings("jump"));
    }

    @Test
    void aButtonAnotherActionUsesIsRefusedAndSaysWhich() {
        InputContext controls = controls().bind("crouch", PAD_B);
        box.bindToGamepad(controls, "jump");
        click();
        box.learnGamepad(PAD_B);
        assertEquals("In use by crouch", box.getDisplayText());
        assertEquals(PAD_A, box.getBinding());
        box.learnGamepad(PAD_A); // its own is fine
        assertFalse(box.isListening());
    }

    @Test
    void anOverlayOutsideAWindowStillWorksWithoutAnInput() {
        HotkeyOverlay alone = new HotkeyOverlay(160, 32);
        alone.setAllowGamepad(true);
        alone.startListening();
        assertTrue(alone.isListening());
        alone.learnGamepad(PAD_A);
        assertEquals(PAD_A, alone.getBinding());
    }
}
