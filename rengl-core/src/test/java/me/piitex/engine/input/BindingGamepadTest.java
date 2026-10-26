package me.piitex.engine.input;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Controller inputs as bindings: how they are made, named, written out and read back. */
class BindingGamepadTest {

    private static List<Binding> everyControllerBinding() {
        List<Binding> all = new ArrayList<>();
        for (Gamepad.Button button : Gamepad.Button.values()) all.add(Binding.gamepad(button));
        for (Gamepad.Axis axis : Gamepad.Axis.values()) {
            if (!axis.isTrigger()) all.add(Binding.gamepadAxis(axis, false));
            all.add(Binding.gamepadAxis(axis, true));
        }
        return all;
    }

    @Test
    void buttonCodesAreGlfwsAndMatchTheEnumOrder() {
        for (Gamepad.Button button : Gamepad.Button.values()) {
            assertEquals(button.ordinal(), button.code());
            assertSame(button, Gamepad.Button.of(button.code()));
        }
        assertEquals(GLFW.GLFW_GAMEPAD_BUTTON_A, Gamepad.Button.A.code());
        assertEquals(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT, Gamepad.Button.DPAD_LEFT.code());
        assertEquals(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_THUMB, Gamepad.Button.LEFT_THUMB.code());
        assertEquals(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER, Gamepad.Axis.RIGHT_TRIGGER.code());
        assertNull(Gamepad.Button.of(15));
        assertNull(Gamepad.Button.of(-1));
    }

    @Test
    void everyControllerBindingSurvivesItsTextForm() {
        List<Binding> all = everyControllerBinding();
        assertEquals(15 + 10, all.size());
        for (Binding b : all) {
            Binding back = Binding.parse(b.toString());
            assertEquals(b, back, b.toString());
            assertTrue(b.isGamepad());
            assertTrue(b.toString().startsWith("PAD_"), b.toString());
        }
    }

    @Test
    void textFormsAreStable() {
        assertEquals("PAD_A", Binding.gamepad(Gamepad.Button.A).toString());
        assertEquals("PAD_DPAD_UP", Binding.gamepad(Gamepad.Button.DPAD_UP).toString());
        assertEquals("PAD_LEFT_THUMB", Binding.gamepad(Gamepad.Button.LEFT_THUMB).toString());
        assertEquals("PAD_LEFT_STICK_LEFT", Binding.gamepadAxis(Gamepad.Axis.LEFT_X, false).toString());
        assertEquals("PAD_LEFT_STICK_DOWN", Binding.gamepadAxis(Gamepad.Axis.LEFT_Y, true).toString());
        assertEquals("PAD_RIGHT_STICK_UP", Binding.gamepadAxis(Gamepad.Axis.RIGHT_Y, false).toString());
        assertEquals("PAD_RIGHT_TRIGGER", Binding.gamepadTrigger(Gamepad.Axis.RIGHT_TRIGGER).toString());
        assertEquals(Binding.gamepad(Gamepad.Button.START), Binding.parse(" pad_start "));
    }

    @Test
    void parsingAControllerInputWithAKeyboardModifierIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Binding.parse("CTRL+PAD_A"));
        assertThrows(IllegalArgumentException.class, () -> Binding.parse("PAD_NOPE"));
        assertThrows(IllegalArgumentException.class, () -> Binding.parse("PAD_LEFT_TRIGGER_LEFT"));
    }

    @Test
    void aTriggerHasOnlyThePulledDirection() {
        assertThrows(IllegalArgumentException.class, () -> Binding.gamepadAxis(Gamepad.Axis.LEFT_TRIGGER, false));
        assertThrows(IllegalArgumentException.class, () -> Binding.gamepadAxis(Gamepad.Axis.RIGHT_TRIGGER, false));
        assertThrows(IllegalArgumentException.class, () -> new Binding(Binding.Device.GAMEPAD_AXIS, 12, 0));
        assertThrows(IllegalArgumentException.class, () -> new Binding(Binding.Device.GAMEPAD_BUTTON, 15, 0));
        assertThrows(IllegalArgumentException.class, () -> new Binding(Binding.Device.GAMEPAD_BUTTON, -1, 0));
    }

    @Test
    void aControllerInputCannotHaveKeyboardModifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> new Binding(Binding.Device.GAMEPAD_BUTTON, 0, GLFW.GLFW_MOD_SHIFT));
        assertThrows(IllegalArgumentException.class, () -> Binding.gamepad(Gamepad.Button.A).withModifiers(GLFW.GLFW_MOD_CONTROL));
    }

    @Test
    void accessorsTellWhatABindingReads() {
        Binding a = Binding.gamepad(Gamepad.Button.B);
        assertEquals(Gamepad.Button.B, a.gamepadButton());
        assertNull(a.gamepadAxis());
        Binding stick = Binding.gamepadAxis(Gamepad.Axis.RIGHT_X, true);
        assertEquals(Gamepad.Axis.RIGHT_X, stick.gamepadAxis());
        assertTrue(stick.isPositiveDirection());
        assertFalse(Binding.gamepadAxis(Gamepad.Axis.RIGHT_X, false).isPositiveDirection());
        assertNull(stick.gamepadButton());
        assertFalse(Binding.key(GLFW.GLFW_KEY_A).isGamepad());
        assertFalse(Binding.mouse(0).isGamepad());
    }

    @Test
    void namesFollowTheLayoutOfThePad() {
        Binding a = Binding.gamepad(Gamepad.Button.A);
        assertEquals("A", a.getDisplayName());
        assertEquals("A", a.getDisplayName(Gamepad.Layout.XBOX));
        assertEquals("Cross", a.getDisplayName(Gamepad.Layout.PLAYSTATION));
        assertEquals("B", a.getDisplayName(Gamepad.Layout.NINTENDO)); // the bottom button of a Switch pad is labelled B

        Binding rt = Binding.gamepadTrigger(Gamepad.Axis.RIGHT_TRIGGER);
        assertEquals("RT", rt.getDisplayName(Gamepad.Layout.XBOX));
        assertEquals("R2", rt.getDisplayName(Gamepad.Layout.PLAYSTATION));
        assertEquals("ZR", rt.getDisplayName(Gamepad.Layout.NINTENDO));
        assertEquals("Right Trigger", rt.getDisplayName(Gamepad.Layout.GENERIC));

        assertEquals("Left Stick Up", Binding.gamepadAxis(Gamepad.Axis.LEFT_Y, false).getDisplayName());
        assertEquals("Right Stick Right", Binding.gamepadAxis(Gamepad.Axis.RIGHT_X, true).getDisplayName());
        assertEquals("Options", Binding.gamepad(Gamepad.Button.START).getDisplayName(Gamepad.Layout.PLAYSTATION));
        assertEquals("D-Pad Left", Binding.gamepad(Gamepad.Button.DPAD_LEFT).getDisplayName());
    }

    @Test
    void everyLayoutNamesEveryInputWithoutRepeats() {
        for (Gamepad.Layout layout : Gamepad.Layout.values()) {
            List<String> names = new ArrayList<>();
            for (Binding b : everyControllerBinding()) {
                String name = b.getDisplayName(layout);
                assertFalse(name.isBlank(), b + " in " + layout);
                names.add(name);
            }
            assertEquals(names.size(), names.stream().distinct().count(), layout + " names two inputs the same: " + names);
        }
    }

    @Test
    void theLayoutIsGuessedFromThePadsName() {
        assertEquals(Gamepad.Layout.XBOX, Gamepad.Layout.of("Xbox 360 Controller"));
        assertEquals(Gamepad.Layout.XBOX, Gamepad.Layout.of("Xbox Series Controller"));
        assertEquals(Gamepad.Layout.PLAYSTATION, Gamepad.Layout.of("PS5 Controller"));
        assertEquals(Gamepad.Layout.PLAYSTATION, Gamepad.Layout.of("PS4 Controller"));
        assertEquals(Gamepad.Layout.PLAYSTATION, Gamepad.Layout.of("Wireless Controller"));
        assertEquals(Gamepad.Layout.NINTENDO, Gamepad.Layout.of("Nintendo Switch Pro Controller"));
        assertEquals(Gamepad.Layout.GENERIC, Gamepad.Layout.of("Some Arcade Stick"));
        assertEquals(Gamepad.Layout.GENERIC, Gamepad.Layout.of(null));
        assertEquals(Gamepad.Layout.GENERIC, Gamepad.Layout.of(""));
    }

    @Test
    void keyboardAndControllerBindingsDoNotEqualEachOther() {
        assertNotEquals(Binding.key(GLFW.GLFW_KEY_A), Binding.gamepad(Gamepad.Button.A));
        assertNotEquals(Binding.mouse(0), Binding.gamepad(Gamepad.Button.A));
        assertEquals(Binding.gamepad(Gamepad.Button.A), Binding.gamepad(Gamepad.Button.A));
    }

    @Test
    void sourcesAreToldApartByPadAndKind() {
        int a0 = Binding.padSource(0, Binding.Device.GAMEPAD_BUTTON, 0);
        int a1 = Binding.padSource(1, Binding.Device.GAMEPAD_BUTTON, 0);
        int stick0 = Binding.padSource(0, Binding.Device.GAMEPAD_AXIS, 0);
        assertNotEquals(a0, a1);
        assertNotEquals(a0, stick0);
        assertFalse(Binding.isAxisSource(a0));
        assertFalse(Binding.isAxisSource(a1));
        assertTrue(Binding.isAxisSource(stick0));
        assertTrue(Binding.isAxisSource(Binding.padSource(15, Binding.Device.GAMEPAD_AXIS, 11)));
        assertFalse(Binding.isAxisSource(Binding.padSource(15, Binding.Device.GAMEPAD_BUTTON, 14)));
        // and none of them can be a key, mouse or wheel source
        assertFalse(Binding.isAxisSource(Binding.source(Binding.Device.KEY, GLFW.GLFW_KEY_LAST)));
        assertTrue(a0 > Binding.source(Binding.Device.WHEEL, Binding.WHEEL_RIGHT));
    }

    @Test
    void anActionsBindingsMayMixKeysAndButtons() {
        InputContext controls = new InputContext("test")
                .bind("jump", Binding.key(GLFW.GLFW_KEY_SPACE), Binding.gamepad(Gamepad.Button.A));
        assertEquals(List.of("jump"), controls.getActionsBoundTo(Binding.gamepad(Gamepad.Button.A)));
        assertEquals(List.of(), controls.getActionsBoundTo(Binding.gamepad(Gamepad.Button.B)));
    }
}
