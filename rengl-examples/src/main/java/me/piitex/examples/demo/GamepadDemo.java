package me.piitex.examples.demo;

import me.piitex.engine.ui.text.TextShaping;
import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.Binding;
import me.piitex.engine.input.Gamepad;
import me.piitex.engine.input.Gamepads;
import me.piitex.engine.input.Input;
import me.piitex.engine.input.InputContext;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.ConsoleLog;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.HotkeyOverlay;
import me.piitex.engine.ui.overlays.Overlay;
import me.piitex.engine.ui.overlays.PercentSliderOverlay;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.lwjgl.glfw.GLFW;

import java.io.File;

/**
 * A controller tester, for trying a gamepad with the engine:
 * <ul>
 *   <li>a diagram of the pad that lights up as buttons are pressed, with both sticks and both triggers moving;</li>
 *   <li>numbers for the sticks and triggers, with and without the dead zone, and a slider to change the dead zone;</li>
 *   <li>a log of controller events: plugging in, unplugging, every press and release;</li>
 *   <li>actions of an {@link InputContext} bound to both keyboard and pad, with bars that show the strength of each
 *       ({@code jump} on A or Space, {@code fire} on the right trigger or F, {@code steer} on the left stick or A and D);</li>
 *   <li>boxes that rebind those actions by pressing a button on the pad.</li>
 * </ul>
 * Plug a controller in before or after starting. The window must have focus to see the pad. While a rebind box has the
 * keyboard the pad belongs to the interface, as the keyboard does (the log stops reacting, and the D-pad and A act as arrow
 * keys and Enter); the demo lets go of it once a box has learned its binding.
 */
public class GamepadDemo {
    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Gamepad Demo").setDimensions(1000, 660));
        Input input = window.getInput();

        Container root = new Container(1000, 660);
        window.addContainer(root);

        InputContext controls = new InputContext("demo")
                .bind("jump", Binding.key(GLFW.GLFW_KEY_SPACE), Binding.gamepad(Gamepad.Button.A))
                .bind("fire", Binding.key(GLFW.GLFW_KEY_F), Binding.gamepadTrigger(Gamepad.Axis.RIGHT_TRIGGER))
                .bind("steer_left", Binding.key(GLFW.GLFW_KEY_A), Binding.gamepadAxis(Gamepad.Axis.LEFT_X, false))
                .bind("steer_right", Binding.key(GLFW.GLFW_KEY_D), Binding.gamepadAxis(Gamepad.Axis.LEFT_X, true));
        input.addContext(controls);

        // Status line and the pad itself
        TextOverlay status = text(root, "", 24, 16, 17f);
        PadDiagram diagram = new PadDiagram();
        diagram.setPosition(24, 50);
        root.addElement(diagram);

        // Numbers, a line each: a TextOverlay is one line
        TextOverlay[] readout = new TextOverlay[4];
        for (int i = 0; i < readout.length; i++) readout[i] = text(root, "", 24, 322 + i * 22, 14f);

        label(root, "Stick dead zone", 24, 424);
        PercentSliderOverlay deadZone = new PercentSliderOverlay(240, Gamepads.getStickDeadZone() * 100 / 0.9);
        deadZone.setPosition(24, 462);
        deadZone.onValueChange(percent -> Gamepads.setStickDeadZone((float) (percent / 100.0 * 0.9)));
        root.addElement(deadZone);
        label(root, "0 to 90% of the stick's travel. Raw numbers above ignore it.", 24, 486);

        // Log of everything the pad does
        ConsoleLog log = new ConsoleLog(450, 330);
        log.setPosition(520, 16);
        log.setInputVisible(false);
        log.setMinLevel(ConsoleLog.Level.DEBUG); // releases are logged at debug level
        log.info("Plug in a controller and press things. Press-and-release events appear here.");
        root.addElement(log);
        input.addGamepadListener(new Gamepad.Listener() {
            @Override
            public void connected(Gamepad pad) {
                log.info("Connected: " + pad.getName() + " (slot " + pad.getIndex() + ", " + pad.getLayout() + ")");
            }

            @Override
            public void disconnected(Gamepad pad) {
                log.warn("Disconnected: " + pad.getName() + " (slot " + pad.getIndex() + ")");
            }
        });

        // Actions
        label(root, "Actions: keyboard and pad on one action", 520, 366);
        ProgressBarOverlay jumpBar = actionRow(root, "jump", 520, 396);
        ProgressBarOverlay fireBar = actionRow(root, "fire", 520, 436);
        ProgressBarOverlay steerLeftBar = actionRow(root, "steer left", 520, 476);
        ProgressBarOverlay steerRightBar = actionRow(root, "steer right", 520, 516);

        label(root, "Rebind: click a box, then press a key or a pad button", 24, 520);
        String[] actions = {"jump", "fire", "steer_right"};
        HotkeyOverlay[] boxes = new HotkeyOverlay[actions.length * 2];
        for (int i = 0; i < actions.length; i++) {
            label(root, actions[i], 24, 556 + i * 34);
            HotkeyOverlay key = new HotkeyOverlay(150, 28);
            key.setPosition(150, 552 + i * 34);
            key.bindTo(controls, actions[i]);
            HotkeyOverlay pad = new HotkeyOverlay(190, 28);
            pad.setPosition(312, 552 + i * 34);
            pad.bindToGamepad(controls, actions[i]);
            pad.setEmptyText("No button");
            boxes[i * 2] = key;
            boxes[i * 2 + 1] = pad;
            for (HotkeyOverlay box : new HotkeyOverlay[]{key, pad}) {
                // Only one box listens at a time. (Not onBindingChange: bindTo already uses it to write the binding back.)
                box.onListenStart(() -> {
                    for (HotkeyOverlay other : boxes) if (other != null && other != box) other.stopListening();
                });
            }
            root.addElement(key);
            root.addElement(pad);
        }

        // One frame of everything: the numbers, the bars, and the press log
        boolean[] wasListening = {false};
        status.onUpdate(event -> {
            // A box that has just learned something still has the keyboard, and with it the pad: give both back to the game
            boolean listening = java.util.Arrays.stream(boxes).anyMatch(HotkeyOverlay::isListening);
            if (wasListening[0] && !listening) {
                window.requestFocus(null);
                for (String action : actions) log.info(action + " = " + controls.getBindings(action));
            }
            wasListening[0] = listening;
            Gamepad pad = input.getGamepad();
            status.setText(pad.isConnected()
                    ? "Controller: " + pad.getName() + "   (slot " + pad.getIndex() + ", " + pad.getLayout() + " layout)"
                    : "No controller found. Plug one in, and keep this window focused.");
            if (!pad.isConnected()) {
                for (TextOverlay line : readout) line.setText("");
                return;
            }
            Vec2 left = pad.getLeftStick(), right = pad.getRightStick();
            readout[0].setText(String.format("Left stick   x % .2f  y % .2f   (raw % .2f, % .2f)",
                    left.x(), left.y(), pad.getRawAxis(Gamepad.Axis.LEFT_X), pad.getRawAxis(Gamepad.Axis.LEFT_Y)));
            readout[1].setText(String.format("Right stick  x % .2f  y % .2f   (raw % .2f, % .2f)",
                    right.x(), right.y(), pad.getRawAxis(Gamepad.Axis.RIGHT_X), pad.getRawAxis(Gamepad.Axis.RIGHT_Y)));
            readout[2].setText(String.format("Triggers     L %.2f   R %.2f", pad.getLeftTrigger(), pad.getRightTrigger()));
            readout[3].setText("Held: " + held(pad));
            for (Gamepad.Button button : Gamepad.Button.values()) {
                if (pad.isJustPressed(button)) log.info(button.getDisplayName(pad.getLayout()) + " pressed");
                if (pad.isJustReleased(button)) log.debug(button.getDisplayName(pad.getLayout()) + " released");
            }
            jumpBar.setProgress(controls.getValue("jump"));
            fireBar.setProgress(controls.getValue("fire"));
            steerLeftBar.setProgress(controls.getValue("steer_left"));
            steerRightBar.setProgress(controls.getValue("steer_right"));
        });

        new Engine().start(window);
    }

    private static String held(Gamepad pad) {
        StringBuilder sb = new StringBuilder();
        for (Gamepad.Button button : Gamepad.Button.values()) {
            if (pad.isDown(button)) sb.append(sb.isEmpty() ? "" : ", ").append(button.getDisplayName(pad.getLayout()));
        }
        return sb.isEmpty() ? "nothing" : sb.toString();
    }

    private static ProgressBarOverlay actionRow(Container root, String name, double x, double y) {
        label(root, name, x, y - 2);
        ProgressBarOverlay bar = new ProgressBarOverlay(260, 14);
        bar.setPosition(x + 120, y + 2);
        root.addElement(bar);
        return bar;
    }

    private static TextOverlay text(Container root, String text, double x, double y, float size) {
        TextOverlay overlay = new TextOverlay(text, size, Color.WHITE);
        overlay.setPosition(x, y);
        root.addElement(overlay);
        return overlay;
    }

    private static void label(Container root, String text, double x, double y) {
        text(root, text, x, y, 14f);
    }

    /**
     * The first pad's current state drawn as an Xbox-style controller: every button lights up when held, the sticks move,
     * and the triggers fill. Read from {@link Input} each frame it is drawn.
     */
    private static final class PadDiagram extends Overlay {
        private static final Color BODY = new Color(0.16f, 0.17f, 0.2f, 1f);
        private static final Color UNLIT = new Color(0.30f, 0.32f, 0.36f, 1f);
        private static final Color LIT = new Color(0.95f, 0.95f, 0.98f, 1f);
        private static final Color OUTLINE = new Color(0.45f, 0.48f, 0.55f, 1f);
        private org.jetbrains.skia.Font font;

        PadDiagram() {
            setWidth(440);
            setHeight(256);
            getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
            getStyling().applyThemeHoverColor(Color.TRANSPARENT);
            getStyling().applyThemeBorderThickness(0f);
        }

        @Override
        public void render(Renderer renderer) {
            if (!isEnabled()) return;
            super.render(renderer);
            Window window = getWindow();
            Gamepad pad = window == null ? null : window.getInput().getGamepad();
            float x = (float) getX(), y = (float) getY();
            boolean on = pad != null && pad.isConnected();
            float alpha = on ? 1f : 0.45f;
            renderer.pushAlpha(alpha);

            renderer.drawQuad(x, y + 34, 440, 214, BODY, 70);
            renderer.drawBorder(x, y + 34, 440, 214, OUTLINE, 2f, 70);

            // Triggers fill upwards from the bumpers; bumpers light when held
            trigger(renderer, x + 40, y, on ? pad.getLeftTrigger() : 0f);
            trigger(renderer, x + 300, y, on ? pad.getRightTrigger() : 0f);
            bar(renderer, x + 34, y + 18, 106, 14, on && pad.isDown(Gamepad.Button.LEFT_BUMPER));
            bar(renderer, x + 300, y + 18, 106, 14, on && pad.isDown(Gamepad.Button.RIGHT_BUMPER));

            stick(renderer, x + 120, y + 112, on ? pad.getLeftStick() : Vec2.ZERO, on && pad.isDown(Gamepad.Button.LEFT_THUMB));
            stick(renderer, x + 290, y + 188, on ? pad.getRightStick() : Vec2.ZERO, on && pad.isDown(Gamepad.Button.RIGHT_THUMB));

            // D-pad
            float dx = x + 175, dy = y + 188;
            square(renderer, dx - 11, dy - 35, on && pad.isDown(Gamepad.Button.DPAD_UP));
            square(renderer, dx - 11, dy + 13, on && pad.isDown(Gamepad.Button.DPAD_DOWN));
            square(renderer, dx - 35, dy - 11, on && pad.isDown(Gamepad.Button.DPAD_LEFT));
            square(renderer, dx + 13, dy - 11, on && pad.isDown(Gamepad.Button.DPAD_RIGHT));

            // Face buttons, named for the pad that is plugged in
            Gamepad.Layout layout = on ? pad.getLayout() : Gamepad.Layout.XBOX;
            float fx = x + 350, fy = y + 112;
            face(renderer, fx, fy + 30, new Color(0.3f, 0.8f, 0.4f, 1f), Gamepad.Button.A, layout, on && pad.isDown(Gamepad.Button.A));
            face(renderer, fx + 30, fy, new Color(0.9f, 0.3f, 0.3f, 1f), Gamepad.Button.B, layout, on && pad.isDown(Gamepad.Button.B));
            face(renderer, fx - 30, fy, new Color(0.3f, 0.5f, 0.95f, 1f), Gamepad.Button.X, layout, on && pad.isDown(Gamepad.Button.X));
            face(renderer, fx, fy - 30, new Color(0.95f, 0.8f, 0.25f, 1f), Gamepad.Button.Y, layout, on && pad.isDown(Gamepad.Button.Y));

            // Back, Guide, Start
            small(renderer, x + 192, y + 100, 8, on && pad.isDown(Gamepad.Button.BACK));
            small(renderer, x + 248, y + 100, 8, on && pad.isDown(Gamepad.Button.START));
            small(renderer, x + 220, y + 78, 11, on && pad.isDown(Gamepad.Button.GUIDE));

            renderer.popAlpha();
        }

        private void trigger(Renderer renderer, float x, float y, float amount) {
            renderer.drawQuad(x, y, 106, 12, UNLIT, 4);
            if (amount > 0f) renderer.drawQuad(x, y, 106 * amount, 12, LIT, 4);
        }

        private void bar(Renderer renderer, float x, float y, float w, float h, boolean lit) {
            renderer.drawQuad(x, y, w, h, lit ? LIT : UNLIT, 6);
        }

        private void stick(Renderer renderer, float cx, float cy, Vec2 value, boolean pressed) {
            renderer.drawCircle(cx, cy, 36, new Color(0.08f, 0.09f, 0.1f, 1f));
            renderer.drawBorder(cx - 36, cy - 36, 72, 72, OUTLINE, 2f, 36);
            renderer.drawCircle(cx + value.x() * 20, cy + value.y() * 20, 18, pressed ? LIT : UNLIT);
        }

        private void square(Renderer renderer, float x, float y, boolean lit) {
            renderer.drawQuad(x, y, 22, 22, lit ? LIT : UNLIT, 4);
        }

        private void small(Renderer renderer, float cx, float cy, float r, boolean lit) {
            renderer.drawCircle(cx, cy, r, lit ? LIT : UNLIT);
        }

        private void face(Renderer renderer, float cx, float cy, Color color, Gamepad.Button button, Gamepad.Layout layout, boolean lit) {
            renderer.drawCircle(cx, cy, 15, lit ? color : color.withAlpha(0.35f));
            renderer.drawBorder(cx - 15, cy - 15, 30, 30, OUTLINE, 1.5f, 15);
            String name = button.getDisplayName(layout);
            org.jetbrains.skia.Font f = font();
            float w = TextShaping.width(f, name);
            FontMetricsCompat m = FontMetricsCompat.of(f);
            renderer.drawText(name, cx - w / 2f, cy - (-m.getAscent() + m.getDescent()) / 2f, f, lit ? Color.BLACK : Color.WHITE);
        }

        private org.jetbrains.skia.Font font() {
            if (font == null) font = new org.jetbrains.skia.Font(Font.getDefault(), 11f);
            return font;
        }
    }
}
