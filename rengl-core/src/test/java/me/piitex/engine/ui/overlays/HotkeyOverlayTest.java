package me.piitex.engine.ui.overlays;

import me.piitex.engine.input.Binding;
import me.piitex.engine.input.InputContext;
import me.piitex.engine.ui.events.MouseClickEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HotkeyOverlayTest {
    private HotkeyOverlay hotkey;
    private final List<Binding> changes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        hotkey = new HotkeyOverlay(160, 32);
        hotkey.setPosition(0, 0);
        hotkey.onBindingChange(changes::add);
    }

    private void click(int button) {
        hotkey.getMouseClickListenerConsumer().accept(new MouseClickEvent(10, 10, button, GLFW.GLFW_PRESS));
    }

    private void press(int key, int mods) {
        hotkey.onKeyPressed(key, GLFW.GLFW_PRESS, mods);
    }

    private void listen() {
        click(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertTrue(hotkey.isListening());
    }

    @Test
    void itStartsUnboundAndShowsThat() {
        assertNull(hotkey.getBinding());
        assertEquals("Unbound", hotkey.getDisplayText());
        assertFalse(hotkey.isListening());
    }

    @Test
    void aClickStartsListeningAndShowsThePrompt() {
        listen();
        assertEquals("Press a key...", hotkey.getDisplayText());
    }

    @Test
    void theNextKeyBecomesTheBinding() {
        listen();
        press(GLFW.GLFW_KEY_J, 0);
        assertEquals(Binding.key(GLFW.GLFW_KEY_J), hotkey.getBinding());
        assertFalse(hotkey.isListening());
        assertEquals(List.of(Binding.key(GLFW.GLFW_KEY_J)), changes);
        assertEquals("J", hotkey.getDisplayText());
    }

    @Test
    void modifiersHeldWithTheKeyAreKept() {
        listen();
        press(GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT);
        assertEquals("SHIFT+CTRL+S", hotkey.getBinding().toString());
        assertEquals(GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT, hotkey.getBinding().modifiers());
    }

    @Test
    void withModifiersTurnedOffOnlyTheKeyIsKept() {
        hotkey.setAllowModifiers(false);
        listen();
        press(GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_CONTROL);
        assertEquals(Binding.key(GLFW.GLFW_KEY_S), hotkey.getBinding());
    }

    @Test
    void aModifierAloneKeepsListeningAndShowsTheCombinationSoFar() {
        listen();
        press(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_MOD_CONTROL);
        assertTrue(hotkey.isListening());
        assertTrue(changes.isEmpty());
        assertTrue(hotkey.getDisplayText().endsWith("+..."), hotkey.getDisplayText());
        hotkey.onKeyPressed(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_RELEASE, 0); // let go: back to the plain prompt
        assertEquals("Press a key...", hotkey.getDisplayText());
    }

    @Test
    void escapeCancelsAndKeepsTheOldBinding() {
        hotkey.setBinding(Binding.key(GLFW.GLFW_KEY_F));
        listen();
        press(GLFW.GLFW_KEY_ESCAPE, 0);
        assertFalse(hotkey.isListening());
        assertEquals(Binding.key(GLFW.GLFW_KEY_F), hotkey.getBinding());
        assertTrue(changes.isEmpty());
    }

    @Test
    void backspaceUnbinds() {
        hotkey.setBinding(Binding.key(GLFW.GLFW_KEY_F));
        listen();
        press(GLFW.GLFW_KEY_BACKSPACE, 0);
        assertNull(hotkey.getBinding());
        assertEquals(1, changes.size());
        assertNull(changes.getFirst());
    }

    @Test
    void whenClearingIsOffBackspaceIsJustAKey() {
        hotkey.setAllowClear(false);
        listen();
        press(GLFW.GLFW_KEY_BACKSPACE, 0);
        assertEquals(Binding.key(GLFW.GLFW_KEY_BACKSPACE), hotkey.getBinding());
    }

    @Test
    void tabCanBeBoundSoItIsWantedWhileListening() {
        assertFalse(hotkey.wantsTab());
        listen();
        assertTrue(hotkey.wantsTab());
        press(GLFW.GLFW_KEY_TAB, 0);
        assertEquals(Binding.key(GLFW.GLFW_KEY_TAB), hotkey.getBinding());
    }

    @Test
    void aLeftClickWhileListeningCancels() {
        listen();
        click(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertFalse(hotkey.isListening());
        assertTrue(changes.isEmpty());
    }

    @Test
    void losingFocusStopsListening() {
        listen();
        hotkey.onFocusLost();
        assertFalse(hotkey.isListening());
    }

    @Test
    void enterOrSpaceStartsListeningFromTheKeyboard() {
        press(GLFW.GLFW_KEY_ENTER, 0);
        assertTrue(hotkey.isListening());
    }

    @Test
    void settingTheBindingByCodeIsSilent() {
        listen();
        hotkey.setBinding(Binding.key(GLFW.GLFW_KEY_Q));
        assertFalse(hotkey.isListening());
        assertTrue(changes.isEmpty());
    }

    @Test
    void mouseButtonsAreIgnoredUnlessAllowed() {
        listen();
        click(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
        assertTrue(hotkey.isListening());
        hotkey.setAllowMouse(true);
        click(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
        assertEquals(Binding.mouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE), hotkey.getBinding());
    }

    @Test
    void theWheelCanBeLearnedWhenMouseIsAllowed() {
        hotkey.setAllowMouse(true);
        assertFalse(hotkey.canScroll(0, 1), "not listening: the wheel is not ours");
        listen();
        assertTrue(hotkey.canScroll(0, 1));
        hotkey.onScroll(0, -1);
        assertEquals(Binding.wheel(Binding.WHEEL_DOWN), hotkey.getBinding());
    }

    @Test
    void aConflictIsRefusedWithAReasonAndListeningContinues() {
        hotkey.setBinding(Binding.key(GLFW.GLFW_KEY_F));
        hotkey.setConflictCheck(b -> b.equals(Binding.key(GLFW.GLFW_KEY_S)) ? "Taken by Save" : null);
        listen();
        press(GLFW.GLFW_KEY_S, 0);
        assertTrue(hotkey.isListening());
        assertEquals("Taken by Save", hotkey.getDisplayText());
        assertEquals(Binding.key(GLFW.GLFW_KEY_F), hotkey.getBinding());
        assertTrue(changes.isEmpty());
        press(GLFW.GLFW_KEY_G, 0); // a free key still works
        assertEquals(Binding.key(GLFW.GLFW_KEY_G), hotkey.getBinding());
    }

    @Test
    void theRefusalFadesAfterAMoment() {
        hotkey.setConflictCheck(b -> "No");
        listen();
        press(GLFW.GLFW_KEY_S, 0);
        assertEquals("No", hotkey.getDisplayText());
        hotkey.update(3f);
        assertEquals("Press a key...", hotkey.getDisplayText());
    }

    @Test
    void bindingToAnInputContextEditsTheActionAndWarnsOfConflicts() {
        InputContext context = new InputContext("test")
                .bind("jump", Binding.key(GLFW.GLFW_KEY_SPACE))
                .bind("fire", Binding.key(GLFW.GLFW_KEY_F), Binding.mouse(GLFW.GLFW_MOUSE_BUTTON_LEFT));
        hotkey.bindTo(context, "fire");
        assertEquals(Binding.key(GLFW.GLFW_KEY_F), hotkey.getBinding());

        listen();
        press(GLFW.GLFW_KEY_SPACE, 0); // the jump key
        assertTrue(hotkey.isListening());
        assertEquals("In use by jump", hotkey.getDisplayText());

        press(GLFW.GLFW_KEY_G, 0);
        // The first binding is replaced and the rest left alone.
        assertEquals(List.of(Binding.key(GLFW.GLFW_KEY_G), Binding.mouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)), context.getBindings("fire"));

        listen();
        press(GLFW.GLFW_KEY_G, 0); // its own key is not a conflict
        assertEquals(Binding.key(GLFW.GLFW_KEY_G), hotkey.getBinding());

        listen();
        press(GLFW.GLFW_KEY_DELETE, 0);
        assertEquals(List.of(Binding.mouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)), context.getBindings("fire"));
    }

    @Test
    void bindingToAnActionWithNoKeyStartsUnbound() {
        InputContext context = new InputContext("test").bind("fire");
        hotkey.bindTo(context, "fire");
        assertNull(hotkey.getBinding());
        listen();
        press(GLFW.GLFW_KEY_H, 0);
        assertEquals(List.of(Binding.key(GLFW.GLFW_KEY_H)), context.getBindings("fire"));
    }

    @Test
    void onListenStartLetsAScreenStopTheOtherBoxes() {
        int[] started = {0};
        hotkey.onListenStart(() -> started[0]++);
        listen();
        assertEquals(1, started[0]);
    }
}
