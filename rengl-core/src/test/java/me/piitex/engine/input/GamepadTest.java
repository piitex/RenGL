package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.config.Config;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.HotkeyOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Controllers from a fake source through {@link Gamepads}, {@link Input} and {@link InputProcessor} to polled state and
 * {@link InputContext} actions. Nothing here touches GLFW or a real controller.
 */
class GamepadTest {
    /** A bank of sixteen slots whose state the test sets, read like GLFW's. */
    private static final class Fake implements Gamepads.Backend {
        final boolean[] present = new boolean[Gamepads.MAX];
        final boolean[] unreadable = new boolean[Gamepads.MAX];
        final float[][] axes = new float[Gamepads.MAX][Gamepad.AXIS_COUNT];
        final boolean[][] buttons = new boolean[Gamepads.MAX][Gamepad.BUTTON_COUNT];
        final String[] names = new String[Gamepads.MAX];

        void plug(int jid, String name) {
            present[jid] = true;
            names[jid] = name;
            axes[jid][GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER] = -1f; // GLFW: a trigger at rest is -1
            axes[jid][GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER] = -1f;
        }

        void unplug(int jid) {
            present[jid] = false;
            Arrays.fill(axes[jid], 0f);
            Arrays.fill(buttons[jid], false);
        }

        @Override
        public boolean present(int jid) {
            return present[jid];
        }

        @Override
        public boolean read(int jid, float[] outAxes, boolean[] outButtons) {
            if (unreadable[jid]) return false;
            System.arraycopy(axes[jid], 0, outAxes, 0, outAxes.length);
            System.arraycopy(buttons[jid], 0, outButtons, 0, outButtons.length);
            return true;
        }

        @Override
        public String name(int jid) {
            return names[jid];
        }

        @Override
        public String guid(int jid) {
            return "guid" + jid;
        }

        @Override
        public boolean addMappings(String text) {
            return true;
        }
    }

    private final Fake fake = new Fake();
    private final Window window = new Window(new WindowOptions("test").setDimensions(300, 300));
    private final InputProcessor processor = new InputProcessor();
    private final Input input = window.getInput();
    private long now = 1_000_000_000L;
    private boolean focused = true;

    @BeforeEach
    void setUp() {
        Gamepads.reset();
    }

    @AfterEach
    void tearDown() {
        Gamepads.reset();
    }

    /** One frame: the main thread reads the pads, then the render thread takes the reading. */
    private void frame() {
        Gamepads.read(fake);
        input.beginFrame();
        processor.handleGamepads(window, focused, now);
    }

    private void frameAfter(double seconds) {
        now += (long) (seconds * 1_000_000_000L);
        frame();
    }

    private Gamepad plugIn(String name) {
        fake.plug(0, name);
        frame();
        return input.getGamepad();
    }

    private void hold(int jid, Gamepad.Button button, boolean down) {
        fake.buttons[jid][button.code()] = down;
    }

    private void stick(int jid, Gamepad.Axis axis, float raw) {
        fake.axes[jid][axis.code()] = raw;
    }

    private static InputContext controls(Binding jump) {
        return new InputContext("test").bind("jump", jump);
    }

    // ---------------------------------------------------------------- connecting

    @Test
    void aSlotWithNothingInItReadsAsUntouched() {
        frame();
        assertFalse(input.isGamepadConnected());
        assertEquals(List.of(), input.getGamepads());
        Gamepad pad = input.getGamepad();
        assertEquals(0, pad.getIndex());
        assertFalse(pad.isConnected());
        assertEquals("", pad.getName());
        assertFalse(pad.isDown(Gamepad.Button.A));
        assertEquals(Vec2.ZERO, pad.getLeftStick());
        assertEquals(0f, pad.getLeftTrigger());
        assertFalse(input.getGamepad(7).isConnected());
        assertThrows(IllegalArgumentException.class, () -> input.getGamepad(16));
        assertThrows(IllegalArgumentException.class, () -> input.getGamepad(-1));
    }

    @Test
    void aPadPluggedInIsConnectedWithItsNameAndLayout() {
        Gamepad pad = plugIn("PS5 Controller");
        assertTrue(pad.isConnected());
        assertEquals("PS5 Controller", pad.getName());
        assertEquals("guid0", pad.getGuid());
        assertEquals(Gamepad.Layout.PLAYSTATION, pad.getLayout());
        assertEquals(List.of(pad), input.getGamepads());
        assertSame(pad, input.getGamepad(0));
    }

    @Test
    void getGamepadPrefersTheFirstOneThatIsPluggedIn() {
        fake.plug(2, "Xbox One Controller");
        frame();
        assertEquals(2, input.getGamepad().getIndex());
        fake.plug(1, "Xbox 360 Controller");
        frame();
        assertEquals(1, input.getGamepad().getIndex());
        assertEquals(2, input.getGamepads().size());
    }

    @Test
    void listenersHearPlugsAndUnplugs() {
        List<String> heard = new ArrayList<>();
        input.addGamepadListener(new Gamepad.Listener() {
            @Override
            public void connected(Gamepad pad) {
                heard.add("in " + pad.getIndex() + " " + pad.getName() + " " + pad.isConnected());
            }

            @Override
            public void disconnected(Gamepad pad) {
                heard.add("out " + pad.getIndex() + " " + pad.getName() + " " + pad.isConnected());
            }
        });
        frame();
        assertEquals(List.of(), heard);
        fake.plug(0, "Xbox 360 Controller");
        frame();
        frame();
        fake.unplug(0);
        frame();
        frame();
        assertEquals(List.of("in 0 Xbox 360 Controller true", "out 0 Xbox 360 Controller false"), heard);
        assertFalse(input.getGamepad().isConnected());
    }

    @Test
    void aPadAlreadyPluggedInWhenTheWindowOpensIsAnnouncedInTheFirstFrame() {
        fake.plug(0, "Xbox 360 Controller");
        List<Integer> heard = new ArrayList<>();
        input.addGamepadListener(new Gamepad.Listener() {
            @Override
            public void connected(Gamepad pad) {
                heard.add(pad.getIndex());
            }
        });
        frame();
        assertEquals(List.of(0), heard);
    }

    @Test
    void aListenerThatThrowsDoesNotBreakTheFrame() {
        input.addGamepadListener(new Gamepad.Listener() {
            @Override
            public void connected(Gamepad pad) {
                throw new IllegalStateException("boom");
            }
        });
        Gamepad pad = plugIn("Xbox 360 Controller");
        assertTrue(pad.isConnected());
    }

    @Test
    void aJoystickWithoutAMappingIsIgnored() {
        fake.plug(0, "Flight Stick");
        fake.unreadable[0] = true;
        frame();
        assertFalse(input.getGamepad().isConnected());
    }

    @Test
    void anUnchangedPadKeepsItsSnapshotAndAChangedOneKeepsItsName() {
        fake.plug(0, "Xbox 360 Controller");
        Gamepads.read(fake);
        Gamepads.Pad first = Gamepads.snapshot()[0];
        Gamepads.read(fake);
        assertSame(first, Gamepads.snapshot()[0]);
        hold(0, Gamepad.Button.A, true);
        Gamepads.read(fake);
        assertNotSame(first, Gamepads.snapshot()[0]);
        assertEquals("Xbox 360 Controller", Gamepads.snapshot()[0].name);
        assertTrue(Gamepads.snapshot()[0].buttons[Gamepad.Button.A.code()]);
    }

    // ---------------------------------------------------------------- buttons

    @Test
    void aButtonIsDownJustPressedAndJustReleased() {
        Gamepad pad = plugIn("Xbox 360 Controller");
        hold(0, Gamepad.Button.A, true);
        frame();
        assertTrue(pad.isDown(Gamepad.Button.A));
        assertTrue(pad.isJustPressed(Gamepad.Button.A));
        assertFalse(pad.isJustReleased(Gamepad.Button.A));
        assertTrue(input.isAnyJustPressed());
        assertFalse(pad.isDown(Gamepad.Button.B));
        frame();
        assertTrue(pad.isDown(Gamepad.Button.A));
        assertFalse(pad.isJustPressed(Gamepad.Button.A));
        hold(0, Gamepad.Button.A, false);
        frame();
        assertFalse(pad.isDown(Gamepad.Button.A));
        assertTrue(pad.isJustReleased(Gamepad.Button.A));
        frame();
        assertFalse(pad.isJustReleased(Gamepad.Button.A));
    }

    @Test
    void theDpadIsFourButtons() {
        Gamepad pad = plugIn("Xbox 360 Controller");
        hold(0, Gamepad.Button.DPAD_LEFT, true);
        frame();
        assertTrue(pad.isDown(Gamepad.Button.DPAD_LEFT));
        assertFalse(pad.isDown(Gamepad.Button.DPAD_RIGHT));
    }

    @Test
    void whatIsHeldWhenAPadIsPluggedInIsHeldNotPressed() {
        fake.plug(0, "Xbox 360 Controller");
        hold(0, Gamepad.Button.A, true);
        InputContext controls = controls(Binding.gamepad(Gamepad.Button.A));
        input.addContext(controls);
        frame();
        Gamepad pad = input.getGamepad();
        assertFalse(pad.isDown(Gamepad.Button.A));
        assertFalse(pad.isJustPressed(Gamepad.Button.A));
        assertFalse(controls.isDown("jump"));
        hold(0, Gamepad.Button.A, false);
        frame();
        assertFalse(pad.isJustReleased(Gamepad.Button.A));
        assertFalse(controls.isJustReleased("jump"));
        hold(0, Gamepad.Button.A, true);
        frame();
        assertTrue(controls.isJustPressed("jump")); // pressed anew
    }

    @Test
    void heldTimeCountsGameTime() {
        Gamepad pad = plugIn("Xbox 360 Controller");
        assertEquals(0f, pad.getHeldTime(Gamepad.Button.A));
        hold(0, Gamepad.Button.A, true);
        frame();
        assertTrue(pad.getHeldTime(Gamepad.Button.A) >= 0f);
        hold(0, Gamepad.Button.A, false);
        frame();
        assertEquals(0f, pad.getHeldTime(Gamepad.Button.A));
    }

    // ---------------------------------------------------------------- sticks and triggers

    @Test
    void aStickAtRestWithDriftReadsExactlyZero() {
        Gamepad pad = plugIn("Xbox 360 Controller");
        stick(0, Gamepad.Axis.LEFT_X, 0.1f);
        stick(0, Gamepad.Axis.LEFT_Y, -0.1f);
        frame();
        assertEquals(Vec2.ZERO, pad.getLeftStick());
        assertEquals(0f, pad.getAxis(Gamepad.Axis.LEFT_X));
        assertEquals(0.1f, pad.getRawAxis(Gamepad.Axis.LEFT_X), 1e-6);
    }

    @Test
    void thePushPastTheDeadZoneIsStretchedToReachOne() {
        Gamepad pad = plugIn("Xbox 360 Controller");
        stick(0, Gamepad.Axis.LEFT_X, 1f);
        frame();
        assertEquals(1f, pad.getLeftStick().x(), 1e-6);
        stick(0, Gamepad.Axis.LEFT_X, 0.6f); // 0.2 dead zone: (0.6 - 0.2) / 0.8
        frame();
        assertEquals(0.5f, pad.getLeftStick().x(), 1e-5);
    }

    @Test
    void upIsNegativeLikeTheScreen() {
        Gamepad pad = plugIn("Xbox 360 Controller");
        stick(0, Gamepad.Axis.RIGHT_Y, -1f);
        frame();
        assertEquals(-1f, pad.getRightStick().y(), 1e-6);
        assertEquals(-1f, pad.getAxis(Gamepad.Axis.RIGHT_Y), 1e-6);
        assertEquals(0f, pad.getLeftStick().y());
    }

    @Test
    void aDiagonalPushIsNeverLongerThanOne() {
        Gamepad pad = plugIn("Xbox 360 Controller");
        stick(0, Gamepad.Axis.LEFT_X, 1f);
        stick(0, Gamepad.Axis.LEFT_Y, 1f);
        frame();
        Vec2 v = pad.getLeftStick();
        assertEquals(1.0, Math.hypot(v.x(), v.y()), 1e-5);
        assertEquals(v.x(), v.y(), 1e-6);
    }

    @Test
    void theDeadZoneIsASetting() {
        Gamepad pad = plugIn("Xbox 360 Controller");
        Gamepads.setStickDeadZone(0.5f);
        stick(0, Gamepad.Axis.LEFT_X, 0.4f);
        frame();
        assertEquals(0f, pad.getLeftStick().x());
        Gamepads.setStickDeadZone(0f);
        frame();
        assertEquals(0.4f, pad.getLeftStick().x(), 1e-6);
        Gamepads.setStickDeadZone(5f);
        assertEquals(0.9f, Gamepads.getStickDeadZone(), 1e-6);
        Gamepads.setStickDeadZone(-1f);
        assertEquals(0f, Gamepads.getStickDeadZone());
    }

    @Test
    void triggersReadZeroToOne() {
        Gamepad pad = plugIn("Xbox 360 Controller");
        frame();
        assertEquals(0f, pad.getLeftTrigger()); // -1 on the wire is released
        stick(0, Gamepad.Axis.LEFT_TRIGGER, 1f);
        frame();
        assertEquals(1f, pad.getLeftTrigger(), 1e-6);
        stick(0, Gamepad.Axis.RIGHT_TRIGGER, 0f); // half way
        frame();
        assertEquals((0.5f - 0.05f) / 0.95f, pad.getRightTrigger(), 1e-5);
        assertEquals(0.5f, pad.getRawAxis(Gamepad.Axis.RIGHT_TRIGGER), 1e-6);
    }

    // ---------------------------------------------------------------- actions

    @Test
    void aButtonIsAnActionLikeAKey() {
        InputContext controls = controls(Binding.gamepad(Gamepad.Button.A));
        input.addContext(controls);
        plugIn("Xbox 360 Controller");
        assertFalse(controls.isDown("jump"));
        hold(0, Gamepad.Button.A, true);
        frame();
        assertTrue(controls.isDown("jump"));
        assertTrue(controls.isJustPressed("jump"));
        assertEquals(1f, controls.getValue("jump"));
        frame();
        assertTrue(controls.isDown("jump"));
        assertFalse(controls.isJustPressed("jump"));
        hold(0, Gamepad.Button.A, false);
        frame();
        assertFalse(controls.isDown("jump"));
        assertTrue(controls.isJustReleased("jump"));
        assertEquals(0f, controls.getValue("jump"));
    }

    @Test
    void aKeyAndAButtonOfTheSameActionBothHaveToLetGo() {
        InputContext controls = new InputContext("test")
                .bind("jump", Binding.key(GLFW.GLFW_KEY_SPACE), Binding.gamepad(Gamepad.Button.A));
        input.addContext(controls);
        plugIn("Xbox 360 Controller");
        input.keyPressed(GLFW.GLFW_KEY_SPACE, 0, false);
        hold(0, Gamepad.Button.A, true);
        frame();
        assertTrue(controls.isDown("jump"));
        input.keyReleased(GLFW.GLFW_KEY_SPACE);
        assertTrue(controls.isDown("jump"));
        hold(0, Gamepad.Button.A, false);
        frame();
        assertFalse(controls.isDown("jump"));
    }

    @Test
    void theSameButtonOnTwoPadsIsTwoPresses() {
        InputContext anyPad = controls(Binding.gamepad(Gamepad.Button.A));
        input.addContext(anyPad);
        fake.plug(0, "Xbox 360 Controller");
        fake.plug(1, "Xbox One Controller");
        frame();
        hold(0, Gamepad.Button.A, true);
        hold(1, Gamepad.Button.A, true);
        frame();
        assertTrue(anyPad.isDown("jump"));
        hold(0, Gamepad.Button.A, false);
        frame();
        assertTrue(anyPad.isDown("jump"), "the second pad still holds it");
        hold(1, Gamepad.Button.A, false);
        frame();
        assertFalse(anyPad.isDown("jump"));
    }

    @Test
    void aContextForOnePadIgnoresTheOthers() {
        InputContext one = controls(Binding.gamepad(Gamepad.Button.A)).setGamepad(0);
        InputContext two = new InputContext("two").bind("jump", Binding.gamepad(Gamepad.Button.A)).setGamepad(1);
        assertEquals(-1, new InputContext("any").getGamepad());
        assertEquals(1, two.getGamepad());
        input.addContext(one);
        input.addContext(two);
        fake.plug(0, "Xbox 360 Controller");
        fake.plug(1, "Xbox One Controller");
        frame();
        hold(1, Gamepad.Button.A, true);
        frame();
        assertFalse(one.isDown("jump"));
        assertTrue(two.isDown("jump"));
        hold(0, Gamepad.Button.A, true);
        frame();
        assertTrue(one.isDown("jump"));
        assertThrows(IllegalArgumentException.class, () -> one.setGamepad(16));
        assertThrows(IllegalArgumentException.class, () -> one.setGamepad(-2));
    }

    @Test
    void aPressGoesToTheTopContextThatBindsItAndNoOther() {
        InputContext below = controls(Binding.gamepad(Gamepad.Button.A));
        InputContext above = new InputContext("above").bind("jump", Binding.gamepad(Gamepad.Button.A));
        input.addContext(below);
        input.addContext(above);
        plugIn("Xbox 360 Controller");
        hold(0, Gamepad.Button.A, true);
        frame();
        assertTrue(above.isDown("jump"));
        assertFalse(below.isDown("jump"));
    }

    @Test
    void aStickIsAnalogAndPressedPastThePressPoint() {
        InputContext controls = new InputContext("test")
                .bind("left", Binding.gamepadAxis(Gamepad.Axis.LEFT_X, false))
                .bind("right", Binding.gamepadAxis(Gamepad.Axis.LEFT_X, true));
        input.addContext(controls);
        plugIn("Xbox 360 Controller");

        stick(0, Gamepad.Axis.LEFT_X, 0.4f); // (0.4 - 0.2) / 0.8 = 0.25: moving, not pressed
        frame();
        assertEquals(0.25f, controls.getValue("right"), 1e-5);
        assertFalse(controls.isDown("right"));
        assertEquals(0f, controls.getValue("left"));

        stick(0, Gamepad.Axis.LEFT_X, 0.8f); // 0.75
        frame();
        assertTrue(controls.isDown("right"));
        assertTrue(controls.isJustPressed("right"));
        assertEquals(0.75f, controls.getValue("right"), 1e-5);
        assertEquals(0.75f, controls.getAxis("left", "right"), 1e-5);

        stick(0, Gamepad.Axis.LEFT_X, 0.6f); // 0.5, below where it was pressed but above where it lets go
        frame();
        assertTrue(controls.isDown("right"));
        assertFalse(controls.isJustPressed("right"));
        assertEquals(0.5f, controls.getValue("right"), 1e-5);

        stick(0, Gamepad.Axis.LEFT_X, 0.4f); // 0.25: let go
        frame();
        assertFalse(controls.isDown("right"));
        assertTrue(controls.isJustReleased("right"));
        assertEquals(0.25f, controls.getValue("right"), 1e-5);

        stick(0, Gamepad.Axis.LEFT_X, -0.8f);
        frame();
        assertTrue(controls.isDown("left"));
        assertEquals(-0.75f, controls.getAxis("left", "right"), 1e-5);
        stick(0, Gamepad.Axis.LEFT_X, 0f);
        frame();
        assertEquals(0f, controls.getAxis("left", "right"));
        assertFalse(controls.isDown("left"));
    }

    @Test
    void aStickHoveringAtThePressPointDoesNotFlicker() {
        InputContext controls = new InputContext("test").bind("right", Binding.gamepadAxis(Gamepad.Axis.LEFT_X, true));
        input.addContext(controls);
        plugIn("Xbox 360 Controller");
        stick(0, Gamepad.Axis.LEFT_X, 0.9f);
        frame();
        assertTrue(controls.isJustPressed("right"));
        for (float raw : new float[]{0.62f, 0.58f, 0.66f, 0.6f, 0.64f}) { // 0.4 to 0.55 after the dead zone
            stick(0, Gamepad.Axis.LEFT_X, raw);
            frame();
            assertTrue(controls.isDown("right"), "raw " + raw);
            assertFalse(controls.isJustPressed("right"), "raw " + raw);
            assertFalse(controls.isJustReleased("right"), "raw " + raw);
        }
    }

    @Test
    void fourStickDirectionsGiveAMovementVector() {
        InputContext controls = new InputContext("test")
                .bind("left", Binding.gamepadAxis(Gamepad.Axis.LEFT_X, false))
                .bind("right", Binding.gamepadAxis(Gamepad.Axis.LEFT_X, true))
                .bind("up", Binding.gamepadAxis(Gamepad.Axis.LEFT_Y, false))
                .bind("down", Binding.gamepadAxis(Gamepad.Axis.LEFT_Y, true));
        input.addContext(controls);
        plugIn("Xbox 360 Controller");
        assertEquals(Vec2.ZERO, controls.getVector("left", "right", "up", "down"));
        stick(0, Gamepad.Axis.LEFT_X, 0.8f);
        stick(0, Gamepad.Axis.LEFT_Y, -0.8f); // up and to the right
        frame();
        Vec2 v = controls.getVector("left", "right", "up", "down");
        assertTrue(v.x() > 0.5f && v.y() < -0.5f, v.toString());
        assertEquals(1.0, Math.hypot(v.x(), v.y()), 1e-4);
    }

    @Test
    void aTriggerIsAnAnalogAction() {
        InputContext controls = new InputContext("test").bind("fire", Binding.gamepadTrigger(Gamepad.Axis.RIGHT_TRIGGER));
        input.addContext(controls);
        plugIn("Xbox 360 Controller");
        stick(0, Gamepad.Axis.RIGHT_TRIGGER, 0f); // half way
        frame();
        assertEquals((0.5f - 0.05f) / 0.95f, controls.getValue("fire"), 1e-5);
        assertFalse(controls.isDown("fire"));
        stick(0, Gamepad.Axis.RIGHT_TRIGGER, 1f);
        frame();
        assertEquals(1f, controls.getValue("fire"), 1e-6);
        assertTrue(controls.isDown("fire"));
        assertTrue(controls.isJustPressed("fire"));
    }

    @Test
    void aStickAndAKeyOfOneActionGiveTheStrongest() {
        InputContext controls = new InputContext("test")
                .bind("right", Binding.key(GLFW.GLFW_KEY_D), Binding.gamepadAxis(Gamepad.Axis.LEFT_X, true));
        input.addContext(controls);
        plugIn("Xbox 360 Controller");
        stick(0, Gamepad.Axis.LEFT_X, 0.6f);
        frame();
        assertEquals(0.5f, controls.getValue("right"), 1e-5);
        input.keyPressed(GLFW.GLFW_KEY_D, 0, false);
        assertEquals(1f, controls.getValue("right"));
        input.keyReleased(GLFW.GLFW_KEY_D);
        assertEquals(0.5f, controls.getValue("right"), 1e-5);
    }

    @Test
    void aContextForAnotherPadGetsNoAnalogValues() {
        InputContext two = new InputContext("two").bind("right", Binding.gamepadAxis(Gamepad.Axis.LEFT_X, true)).setGamepad(1);
        input.addContext(two);
        fake.plug(0, "Xbox 360 Controller");
        fake.plug(1, "Xbox One Controller");
        stick(0, Gamepad.Axis.LEFT_X, 1f);
        frame();
        assertEquals(0f, two.getValue("right"));
        stick(1, Gamepad.Axis.LEFT_X, 1f);
        frame();
        assertEquals(1f, two.getValue("right"), 1e-6);
    }

    @Test
    void anActionBoundOnlyInAContextBelowStillGetsItsAnalogValue() {
        InputContext below = new InputContext("below").bind("right", Binding.gamepadAxis(Gamepad.Axis.LEFT_X, true));
        InputContext above = new InputContext("above").bind("other", Binding.key(GLFW.GLFW_KEY_Q));
        input.addContext(below);
        input.addContext(above);
        plugIn("Xbox 360 Controller");
        stick(0, Gamepad.Axis.LEFT_X, 1f);
        frame();
        assertEquals(1f, below.getValue("right"), 1e-6);
    }

    @Test
    void aBlockingContextKeepsAnalogValuesFromTheOnesBelow() {
        InputContext below = new InputContext("below").bind("right", Binding.gamepadAxis(Gamepad.Axis.LEFT_X, true));
        InputContext menu = new InputContext("menu").bind("ok", Binding.gamepad(Gamepad.Button.A)).setBlocking(true);
        input.addContext(below);
        input.addContext(menu);
        plugIn("Xbox 360 Controller");
        stick(0, Gamepad.Axis.LEFT_X, 1f);
        frame();
        assertEquals(0f, below.getValue("right"));
        assertFalse(below.isDown("right"));
    }

    @Test
    void unpluggingAPadLetsGoOfEverythingItHeld() {
        InputContext controls = new InputContext("test")
                .bind("jump", Binding.gamepad(Gamepad.Button.A))
                .bind("right", Binding.gamepadAxis(Gamepad.Axis.LEFT_X, true));
        input.addContext(controls);
        Gamepad pad = plugIn("Xbox 360 Controller");
        hold(0, Gamepad.Button.A, true);
        stick(0, Gamepad.Axis.LEFT_X, 1f);
        frame();
        assertTrue(controls.isDown("jump"));
        assertTrue(controls.isDown("right"));
        fake.unplug(0);
        frame();
        assertFalse(controls.isDown("jump"));
        assertTrue(controls.isJustReleased("jump"));
        assertFalse(controls.isDown("right"));
        assertEquals(0f, controls.getValue("right"));
        assertFalse(pad.isDown(Gamepad.Button.A));
        assertEquals(Vec2.ZERO, pad.getLeftStick());
    }

    @Test
    void aWindowWithoutFocusSeesAnUntouchedPadAndTheHeldButtonIsNotAPressOnReturn() {
        InputContext controls = controls(Binding.gamepad(Gamepad.Button.A));
        input.addContext(controls);
        Gamepad pad = plugIn("Xbox 360 Controller");
        hold(0, Gamepad.Button.A, true);
        stick(0, Gamepad.Axis.LEFT_X, 1f);
        frame();
        assertTrue(controls.isDown("jump"));

        focused = false;
        frame();
        assertFalse(controls.isDown("jump"));
        assertFalse(pad.isDown(Gamepad.Button.A));
        assertEquals(Vec2.ZERO, pad.getLeftStick());
        assertTrue(pad.isConnected(), "an unfocused window still knows the pad is there");

        hold(0, Gamepad.Button.B, true); // pressed while away: nobody sees it
        frame();
        assertFalse(pad.isDown(Gamepad.Button.B));

        focused = true;
        frame();
        assertFalse(controls.isDown("jump"), "still held from before, not pressed");
        assertFalse(controls.isJustPressed("jump"));
        assertFalse(pad.isDown(Gamepad.Button.B));
        assertEquals(1f, pad.getLeftStick().x(), 1e-6);
        hold(0, Gamepad.Button.A, false);
        hold(0, Gamepad.Button.A, true);
        frame();
        assertFalse(controls.isJustPressed("jump"), "never released while the frame watched");
        hold(0, Gamepad.Button.A, false);
        frame();
        hold(0, Gamepad.Button.A, true);
        frame();
        assertTrue(controls.isJustPressed("jump"));
    }

    // ---------------------------------------------------------------- binding prompts

    @Test
    void aPromptForAGamepadBindingTakesTheNextButtonAndNothingElseDoes() {
        InputContext controls = controls(Binding.gamepad(Gamepad.Button.A));
        input.addContext(controls);
        plugIn("Xbox 360 Controller");
        List<Binding> bound = new ArrayList<>();
        input.listenForGamepad(bound::add);
        assertTrue(input.isListeningForGamepad());
        hold(0, Gamepad.Button.A, true);
        frame();
        assertEquals(List.of(Binding.gamepad(Gamepad.Button.A)), bound);
        assertFalse(input.isListeningForGamepad());
        assertFalse(controls.isDown("jump"), "the press that was bound goes nowhere else");
        hold(0, Gamepad.Button.A, false);
        frame();
        hold(0, Gamepad.Button.A, true);
        frame();
        assertTrue(controls.isDown("jump"));
        assertEquals(1, bound.size());
    }

    @Test
    void aPromptTakesAStickDirectionToo() {
        plugIn("Xbox 360 Controller");
        List<Binding> bound = new ArrayList<>();
        input.listenForGamepad(bound::add);
        stick(0, Gamepad.Axis.LEFT_X, -0.9f);
        frame();
        assertEquals(List.of(Binding.gamepadAxis(Gamepad.Axis.LEFT_X, false)), bound);
        input.listenForGamepad(bound::add);
        stick(0, Gamepad.Axis.RIGHT_TRIGGER, 1f);
        frame();
        assertEquals(Binding.gamepadTrigger(Gamepad.Axis.RIGHT_TRIGGER), bound.get(1));
    }

    @Test
    void aPromptIsAskedBeforeTheInterfaceSoBAndTheDpadCanBeBound() {
        List<String> keys = new ArrayList<>();
        TextFieldOverlay field = fieldRecording(keys);
        window.requestFocus(field);
        plugIn("Xbox 360 Controller");
        List<Binding> bound = new ArrayList<>();
        input.listenForGamepad(bound::add);
        hold(0, Gamepad.Button.B, true);
        frame();
        assertEquals(List.of(Binding.gamepad(Gamepad.Button.B)), bound);
        assertEquals(List.of(), keys, "B is not Escape while a prompt waits for it");
    }

    @Test
    void theGeneralPromptTakesAControllerButtonToo() {
        plugIn("Xbox 360 Controller");
        List<Binding> bound = new ArrayList<>();
        input.listenForBinding(bound::add);
        hold(0, Gamepad.Button.START, true);
        frame();
        assertEquals(List.of(Binding.gamepad(Gamepad.Button.START)), bound);
        assertFalse(input.isListening());
    }

    @Test
    void aPromptCanBeCancelled() {
        InputContext controls = controls(Binding.gamepad(Gamepad.Button.A));
        input.addContext(controls);
        plugIn("Xbox 360 Controller");
        List<Binding> bound = new ArrayList<>();
        input.listenForGamepad(bound::add);
        input.cancelGamepadListening();
        hold(0, Gamepad.Button.A, true);
        frame();
        assertEquals(List.of(), bound);
        assertTrue(controls.isDown("jump"));
    }

    // ---------------------------------------------------------------- the interface

    private static TextFieldOverlay fieldRecording(List<String> keys) {
        return new TextFieldOverlay(100, 20) {
            @Override
            public void onKeyPressed(int key, int action, int mods) {
                keys.add(key + "/" + action);
            }
        };
    }

    private static String key(int key, int action) {
        return key + "/" + action;
    }

    @Test
    void whileAnElementHasTheKeyboardTheButtonsAreKeysAndTheGameSeesNothing() {
        InputContext controls = controls(Binding.gamepad(Gamepad.Button.A));
        input.addContext(controls);
        List<String> keys = new ArrayList<>();
        window.requestFocus(fieldRecording(keys));
        Gamepad pad = plugIn("Xbox 360 Controller");

        hold(0, Gamepad.Button.A, true);
        frame();
        assertEquals(List.of(key(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS)), keys);
        assertFalse(controls.isDown("jump"));
        assertFalse(pad.isDown(Gamepad.Button.A));
        assertFalse(input.isAnyJustPressed());

        hold(0, Gamepad.Button.B, true);
        hold(0, Gamepad.Button.DPAD_UP, true);
        frame();
        assertEquals(List.of(key(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS), key(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS),
                key(GLFW.GLFW_KEY_UP, GLFW.GLFW_PRESS)), keys);
    }

    @Test
    void theLeftStickMovesTheInterfaceLikeTheArrowKeys() {
        List<String> keys = new ArrayList<>();
        window.requestFocus(fieldRecording(keys));
        plugIn("Xbox 360 Controller");
        stick(0, Gamepad.Axis.LEFT_X, -0.9f);
        frame();
        stick(0, Gamepad.Axis.LEFT_X, 0f);
        stick(0, Gamepad.Axis.LEFT_Y, 0.9f);
        frame();
        stick(0, Gamepad.Axis.LEFT_Y, 0f);
        stick(0, Gamepad.Axis.LEFT_X, 0.9f);
        frame();
        stick(0, Gamepad.Axis.LEFT_X, 0f);
        stick(0, Gamepad.Axis.LEFT_Y, -0.9f);
        frame();
        assertEquals(List.of(key(GLFW.GLFW_KEY_LEFT, GLFW.GLFW_PRESS), key(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS),
                key(GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_PRESS), key(GLFW.GLFW_KEY_UP, GLFW.GLFW_PRESS)), keys);
    }

    @Test
    void theRightStickAndTheTriggersLeaveTheInterfaceAlone() {
        List<String> keys = new ArrayList<>();
        window.requestFocus(fieldRecording(keys));
        plugIn("Xbox 360 Controller");
        stick(0, Gamepad.Axis.RIGHT_X, 1f);
        stick(0, Gamepad.Axis.RIGHT_TRIGGER, 1f);
        hold(0, Gamepad.Button.X, true);
        frame();
        assertEquals(List.of(), keys);
    }

    @Test
    void aHeldDirectionRepeatsAfterADelayAndStopsOnRelease() {
        List<String> keys = new ArrayList<>();
        window.requestFocus(fieldRecording(keys));
        plugIn("Xbox 360 Controller");
        hold(0, Gamepad.Button.DPAD_DOWN, true);
        frame();
        assertEquals(List.of(key(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS)), keys);
        frameAfter(0.3);
        assertEquals(1, keys.size(), "not yet");
        frameAfter(0.2); // 0.5 s in
        assertEquals(key(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_REPEAT), keys.get(1));
        frameAfter(0.05);
        assertEquals(2, keys.size());
        frameAfter(0.06);
        assertEquals(3, keys.size());
        hold(0, Gamepad.Button.DPAD_DOWN, false);
        frameAfter(0.1);
        frameAfter(0.5);
        assertEquals(3, keys.size());
    }

    @Test
    void aHeldAIsNotRepeated() {
        List<String> keys = new ArrayList<>();
        window.requestFocus(fieldRecording(keys));
        plugIn("Xbox 360 Controller");
        hold(0, Gamepad.Button.A, true);
        frame();
        frameAfter(1.0);
        assertEquals(1, keys.size());
    }

    @Test
    void theRepeatStopsWhenTheInterfaceLetsGoOfTheKeyboard() {
        List<String> keys = new ArrayList<>();
        window.requestFocus(fieldRecording(keys));
        plugIn("Xbox 360 Controller");
        hold(0, Gamepad.Button.DPAD_DOWN, true);
        frame();
        window.requestFocus(null);
        frameAfter(1.0);
        assertEquals(1, keys.size());
    }

    @Test
    void bClosesAnOpenPopup() {
        window.showPopup(new Container(50, 50), null);
        assertNotNull(window.getPopup());
        plugIn("Xbox 360 Controller");
        hold(0, Gamepad.Button.B, true);
        frame();
        assertNull(window.getPopup());
    }

    @Test
    void withNothingOpenThePadIsTheGames() {
        InputContext controls = controls(Binding.gamepad(Gamepad.Button.B));
        input.addContext(controls);
        plugIn("Xbox 360 Controller");
        hold(0, Gamepad.Button.B, true);
        frame();
        assertTrue(controls.isDown("jump"));
    }

    @Test
    void aReleaseAlwaysReachesTheGameEvenIfTheInterfaceTookTheControllerMeanwhile() {
        InputContext controls = controls(Binding.gamepad(Gamepad.Button.A));
        input.addContext(controls);
        Gamepad pad = plugIn("Xbox 360 Controller");
        hold(0, Gamepad.Button.A, true);
        stick(0, Gamepad.Axis.LEFT_X, 1f);
        frame();
        assertTrue(controls.isDown("jump"));
        assertEquals(1f, pad.getLeftStick().x(), 1e-6);

        window.requestFocus(fieldRecording(new ArrayList<>()));
        frame();
        assertEquals(Vec2.ZERO, pad.getLeftStick(), "the sticks read 0 while the interface has the pad");
        assertEquals(1f, pad.getRawAxis(Gamepad.Axis.LEFT_X), 1e-6, "the raw value is the hardware's");
        assertTrue(controls.isDown("jump"), "a button held from before stays held");

        hold(0, Gamepad.Button.A, false);
        frame();
        assertFalse(controls.isDown("jump"));
        assertFalse(pad.isDown(Gamepad.Button.A));
    }

    @Test
    void theSticksComeBackWhenTheInterfaceLetsGo() {
        Gamepad pad = plugIn("Xbox 360 Controller");
        window.requestFocus(fieldRecording(new ArrayList<>()));
        stick(0, Gamepad.Axis.LEFT_Y, -1f);
        frame();
        assertEquals(Vec2.ZERO, pad.getLeftStick());
        window.requestFocus(null);
        frame();
        assertEquals(-1f, pad.getLeftStick().y(), 1e-6);
    }

    // ---------------------------------------------------------------- rebinding and saving

    @Test
    void aHotkeyBoxLearnsTheNextControllerPressThroughTheRealPipeline() {
        HotkeyOverlay box = new HotkeyOverlay(160, 30) {
            @Override
            protected Input currentInput() {
                return input;
            }
        };
        box.setGamepadOnly(true);
        box.startListening();
        assertTrue(input.isListeningForGamepad());
        plugIn("PS5 Controller");
        hold(0, Gamepad.Button.X, true);
        frame();
        assertEquals(Binding.gamepad(Gamepad.Button.X), box.getBinding());
        assertFalse(box.isListening());
        assertEquals("Square", box.getDisplayText(), "named for the PlayStation pad that is plugged in");
    }

    @Test
    void controllerBindingsAreSavedAndLoadedWithTheKeyboardOnes() throws Exception {
        InputContext controls = new InputContext("test")
                .bind("jump", Binding.key(GLFW.GLFW_KEY_SPACE), Binding.gamepad(Gamepad.Button.A))
                .bind("right", Binding.gamepadAxis(Gamepad.Axis.LEFT_X, true))
                .bind("fire", Binding.gamepadTrigger(Gamepad.Axis.RIGHT_TRIGGER));
        controls.setBindings("jump", Binding.key(GLFW.GLFW_KEY_E), Binding.gamepad(Gamepad.Button.Y));
        controls.setBindings("right", Binding.gamepadAxis(Gamepad.Axis.RIGHT_X, true));
        Config config = new Config();
        controls.save(config, "controls");

        InputContext fresh = new InputContext("test")
                .bind("jump", Binding.key(GLFW.GLFW_KEY_SPACE), Binding.gamepad(Gamepad.Button.A))
                .bind("right", Binding.gamepadAxis(Gamepad.Axis.LEFT_X, true))
                .bind("fire", Binding.gamepadTrigger(Gamepad.Axis.RIGHT_TRIGGER));
        fresh.load(config, "controls");
        assertEquals(List.of(Binding.key(GLFW.GLFW_KEY_E), Binding.gamepad(Gamepad.Button.Y)), fresh.getBindings("jump"));
        assertEquals(List.of(Binding.gamepadAxis(Gamepad.Axis.RIGHT_X, true)), fresh.getBindings("right"));
        assertEquals(List.of(Binding.gamepadTrigger(Gamepad.Axis.RIGHT_TRIGGER)), fresh.getBindings("fire"));
    }

    // ---------------------------------------------------------------- settings

    @Test
    void thePressPointIsASettingAndLetGoIsBelowIt() {
        assertEquals(0.5f, Gamepads.getPressPoint());
        Gamepads.setPressPoint(0.8f);
        assertEquals(0.8f, Gamepads.getPressPoint());
        assertTrue(Gamepads.releasePoint() < 0.8f);
        Gamepads.setPressPoint(0f);
        assertEquals(0.1f, Gamepads.getPressPoint(), 1e-6);
        Gamepads.setPressPoint(5f);
        assertEquals(0.95f, Gamepads.getPressPoint(), 1e-6);
    }

    @Test
    void aHigherPressPointNeedsABiggerPush() {
        Gamepads.setPressPoint(0.9f);
        InputContext controls = new InputContext("test").bind("right", Binding.gamepadAxis(Gamepad.Axis.LEFT_X, true));
        input.addContext(controls);
        plugIn("Xbox 360 Controller");
        stick(0, Gamepad.Axis.LEFT_X, 0.8f); // 0.75
        frame();
        assertFalse(controls.isDown("right"));
        stick(0, Gamepad.Axis.LEFT_X, 1f);
        frame();
        assertTrue(controls.isDown("right"));
    }

    @Test
    void controllersCanBeSwitchedOff() {
        assertTrue(Gamepads.isEnabled());
        Gamepads.setEnabled(false);
        assertFalse(Gamepads.isEnabled());
        Gamepads.pump(); // does nothing, and in particular does not need GLFW
        assertEquals(null, Gamepads.snapshot()[0]);
    }
}
