package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.config.Config;
import me.piitex.engine.game.Scene;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.Binding;
import me.piitex.engine.input.Input;
import me.piitex.engine.input.InputContext;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Game input: actions, contexts, the polled keyboard and mouse, and rebinding.
 * <ul>
 *   <li>The player moves, dashes, charges a shockwave and shoots through the actions of the "gameplay"
 *       {@link InputContext}, polled in {@link Scene#fixedUpdate}. Holding Space charges with
 *       {@link InputContext#getHeldTime}; the wheel resizes the player through wheel bindings.</li>
 *   <li>T opens a dialogue box over the game, which keeps running: its context is blocking, so the player stops, and
 *       Space moves the dialogue on instead of charging.</li>
 *   <li>Escape opens the controls menu. Click a binding and press a key, button or wheel direction to change it.
 *       The bindings are saved to {@code saves/input-demo.cfg} and loaded on the next start.</li>
 *   <li>Tab is bound to the grid, so it reaches the game while nothing has focus; click the name field and Tab moves
 *       focus instead. Typing in the field doesn't move the player, and clicking a button doesn't shoot.</li>
 *   <li>F1, in a context on the window rather than a scene, shows or hides the panel of raw input state, in the game
 *       and in the menu alike.</li>
 * </ul>
 */
public class InputDemo {
    private static final Logger log = LogManager.getLogger("InputDemo");
    private static final Path CONFIG = Path.of("saves", "input-demo.cfg");
    private static final String CONFIG_PATH = "controls.gameplay";

    /**
     * The game's controls, shared by the play scene and the controls menu.
     */
    static final InputContext GAMEPLAY = new InputContext("gameplay")
            .bind("move_left", Binding.key(GLFW_KEY_A), Binding.key(GLFW_KEY_LEFT))
            .bind("move_right", Binding.key(GLFW_KEY_D), Binding.key(GLFW_KEY_RIGHT))
            .bind("move_up", Binding.key(GLFW_KEY_W), Binding.key(GLFW_KEY_UP))
            .bind("move_down", Binding.key(GLFW_KEY_S), Binding.key(GLFW_KEY_DOWN))
            .bind("dash", Binding.key(GLFW_KEY_LEFT_SHIFT), Binding.mouse(GLFW_MOUSE_BUTTON_RIGHT))
            .bind("charge", Binding.key(GLFW_KEY_SPACE))
            .bind("shoot", Binding.mouse(GLFW_MOUSE_BUTTON_LEFT))
            .bind("grow", Binding.wheel(Binding.WHEEL_UP), Binding.key(GLFW_KEY_EQUAL))
            .bind("shrink", Binding.wheel(Binding.WHEEL_DOWN), Binding.key(GLFW_KEY_MINUS))
            .bind("grid", Binding.key(GLFW_KEY_TAB))
            .bind("talk", Binding.key(GLFW_KEY_T))
            .bind("controls", Binding.key(GLFW_KEY_ESCAPE));

    private static final String[][] LABELS = {
            {"move_left", "Move left"}, {"move_right", "Move right"}, {"move_up", "Move up"},
            {"move_down", "Move down"}, {"dash", "Dash"}, {"charge", "Charge (hold)"}, {"shoot", "Shoot"},
            {"grow", "Grow"}, {"shrink", "Shrink"}, {"grid", "Grid"}, {"talk", "Talk"}, {"controls", "Controls"}};

    private static final Config config = loadConfig();

    static void main() {
        // To reduce console spam add these jvm flags
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Window window = new Window(new WindowOptions("Input Demo").setDimensions(1000, 640));
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        GAMEPLAY.load(config, CONFIG_PATH);
        PlayScene play = new PlayScene();
        // A window context works in every scene: F1 shows the raw input panel in the game and in the menus.
        InputContext debug = new InputContext("debug").bind("panel", Binding.key(GLFW_KEY_F1));
        window.getInput().addContext(debug);
        play.debug = debug;

        window.getSceneManager().set(play);
        new Engine().start(window);
    }

    private static Config loadConfig() {
        try {
            return Config.loadOrEmpty(CONFIG);
        } catch (IOException e) {
            log.warn("Could not read {}; using the default controls", CONFIG, e);
            return new Config().setSourcePath(CONFIG);
        }
    }

    static void saveControls() {
        GAMEPLAY.save(config, CONFIG_PATH);
        try {
            config.save(CONFIG);
        } catch (IOException e) {
            log.warn("Could not save {}", CONFIG, e);
        }
    }

    static String bindingsText(String action) {
        List<Binding> bindings = GAMEPLAY.getBindings(action);
        if (bindings.isEmpty()) return "(none)";
        StringJoiner joiner = new StringJoiner(", ");
        for (Binding b : bindings) joiner.add(b.getDisplayName());
        return joiner.toString();
    }

    static class PlayScene extends Scene {
        private static final Color FIELD = new Color(0.07f, 0.08f, 0.11f, 1f);
        private static final Color GRID = new Color(1f, 1f, 1f, 0.06f);
        private static final Color PLAYER = new Color(0.35f, 0.75f, 1f, 1f);
        private static final Color DASHING = new Color(0.8f, 0.95f, 1f, 1f);
        private static final Color CHARGE = new Color(1f, 0.8f, 0.3f, 0.35f);
        private static final Color WAVE = new Color(1f, 0.8f, 0.3f, 1f);
        private static final Color BULLET = new Color(1f, 0.45f, 0.4f, 1f);
        private static final Color PANEL = new Color(0f, 0f, 0f, 0.55f);
        private static final float SPEED = 260f, DASH_SPEED = 900f, DASH_TIME = 0.12f, BULLET_SPEED = 700f;
        private static final float MAX_CHARGE = 1.5f;

        InputContext debug;
        private final TextOverlay help = new TextOverlay("", 14f, Color.GRAY);
        private final TextOverlay status = new TextOverlay("", 14f, Color.WHITE);
        private final TextOverlay[] panelLines = new TextOverlay[6];
        private final TextFieldOverlay name = new TextFieldOverlay(200, 30);
        private final ButtonOverlay controlsButton = new ButtonOverlay("Controls", 120, 30);
        private boolean showPanel = true, showGrid;

        private float x = 300, y = 300, prevX = x, prevY = y, size = 36;
        private float dashX, dashY, dashLeft;
        private float charge;
        private final List<float[]> bullets = new ArrayList<>(); // x, y, vx, vy, previous x, previous y
        private final List<float[]> waves = new ArrayList<>();   // x, y, radius, power

        PlayScene() {
            addInputContext(GAMEPLAY);
        }

        @Override
        protected void onEnter() {
            help.setPosition(16, 12);
            status.setPosition(16, 34);
            name.setPlaceholder("Your name (typing here doesn't move you)");
            controlsButton.onAction(() -> getSceneManager().push(new ControlsScene()));
            getContainer().addElements(help, status, name, controlsButton);
            for (int i = 0; i < panelLines.length; i++) {
                panelLines[i] = new TextOverlay("", 13f, Color.LIGHT_GRAY);
                getContainer().addElement(panelLines[i]);
            }
            layout();
        }

        @Override
        protected void onResume() {
            updateHelp(); // the bindings may have changed in the controls menu
        }

        @Override
        protected void onResize(int width, int height) {
            layout();
        }

        private void layout() {
            double w = getContainer().getWidth(), h = getContainer().getHeight();
            name.setPosition(16, 60);
            controlsButton.setPosition(228, 60);
            for (int i = 0; i < panelLines.length; i++) panelLines[i].setPosition(16, h - 24 - (panelLines.length - 1 - i) * 18);
            updateHelp();
        }

        private void updateHelp() {
            help.setText("Move " + bindingsText("move_up") + " / " + bindingsText("move_left") + " / " + bindingsText("move_down")
                    + " / " + bindingsText("move_right") + "   Dash " + bindingsText("dash") + "   Charge " + bindingsText("charge")
                    + "   Shoot " + bindingsText("shoot") + "   Talk " + bindingsText("talk") + "   Controls " + bindingsText("controls"));
        }

        @Override
        protected void fixedUpdate(float step) {
            float w = (float) getContainer().getWidth(), h = (float) getContainer().getHeight();
            prevX = x;
            prevY = y;

            Vec2 move = GAMEPLAY.getVector("move_left", "move_right", "move_up", "move_down");
            if (GAMEPLAY.isJustPressed("dash") && dashLeft <= 0f && (move.x() != 0f || move.y() != 0f)) {
                dashX = move.x();
                dashY = move.y();
                dashLeft = DASH_TIME;
            }
            if (dashLeft > 0f) {
                dashLeft -= step;
                x += dashX * DASH_SPEED * step;
                y += dashY * DASH_SPEED * step;
            } else {
                x += move.x() * SPEED * step;
                y += move.y() * SPEED * step;
            }
            x = Math.clamp(x, 0, w - size);
            y = Math.clamp(y, 0, h - size);

            // Holding charges; letting go fires a shockwave as strong as the charge was.
            if (GAMEPLAY.isDown("charge")) charge = Math.min(MAX_CHARGE, GAMEPLAY.getHeldTime("charge"));
            if (GAMEPLAY.isJustReleased("charge") && charge > 0f) {
                waves.add(new float[]{x + size / 2, y + size / 2, 0f, charge / MAX_CHARGE});
                charge = 0f;
            }

            if (GAMEPLAY.isJustPressed("shoot")) {
                Input input = getWindow().getInput();
                float cx = x + size / 2, cy = y + size / 2;
                float dx = input.getMouseX() - cx, dy = input.getMouseY() - cy;
                float length = (float) Math.sqrt(dx * dx + dy * dy);
                if (length > 1f) bullets.add(new float[]{cx, cy, dx / length * BULLET_SPEED, dy / length * BULLET_SPEED, cx, cy});
            }
            for (Iterator<float[]> it = bullets.iterator(); it.hasNext(); ) {
                float[] b = it.next();
                b[4] = b[0];
                b[5] = b[1];
                b[0] += b[2] * step;
                b[1] += b[3] * step;
                if (b[0] < -20 || b[1] < -20 || b[0] > w + 20 || b[1] > h + 20) it.remove();
            }
            for (Iterator<float[]> it = waves.iterator(); it.hasNext(); ) {
                float[] wave = it.next();
                wave[2] += (200f + 500f * wave[3]) * step;
                if (wave[2] > 60f + 400f * wave[3]) it.remove();
            }
        }

        @Override
        protected void update(float delta) {
            // One-off actions can be read in update too: "just pressed" then means during this frame.
            if (GAMEPLAY.isJustPressed("grow")) size = Math.min(90, size + 6);
            if (GAMEPLAY.isJustPressed("shrink")) size = Math.max(12, size - 6);
            if (GAMEPLAY.isJustPressed("grid")) showGrid = !showGrid;
            if (GAMEPLAY.isJustPressed("talk")) getSceneManager().push(new DialogueScene());
            if (GAMEPLAY.isJustPressed("controls")) getSceneManager().push(new ControlsScene());
            if (debug != null && debug.isJustPressed("panel")) showPanel = !showPanel;

            String who = name.getText().isBlank() ? "Player" : name.getText().strip();
            status.setText(String.format(Locale.ROOT, "%s   size %.0f   charge %.2f s   bullets %d   FPS %.0f",
                    who, size, charge, bullets.size(), getWindow().getFps()));
            updatePanel();
        }

        private void updatePanel() {
            Input input = getWindow().getInput();
            for (TextOverlay line : panelLines) line.setEnabled(showPanel);
            if (!showPanel) return;
            StringJoiner held = new StringJoiner(" ");
            for (int key = 0; key <= GLFW_KEY_LAST; key++) {
                if (input.isKeyDown(key)) held.add(Binding.key(key).getDisplayName());
            }
            StringJoiner buttons = new StringJoiner(" ");
            for (int b = 0; b <= GLFW_MOUSE_BUTTON_LAST; b++) {
                if (input.isMouseDown(b)) buttons.add(Binding.mouse(b).getDisplayName());
            }
            StringJoiner actions = new StringJoiner(" ");
            for (String action : GAMEPLAY.getActions()) {
                if (GAMEPLAY.isDown(action)) actions.add(action);
            }
            StringJoiner active = new StringJoiner(", ");
            for (InputContext c : input.getActiveContexts()) active.add(c.getName() + (c.isBlocking() ? " (blocking)" : ""));
            panelLines[0].setText("Raw input (F1 hides)");
            panelLines[1].setText("Keys held: " + held);
            panelLines[2].setText("Buttons held: " + buttons);
            panelLines[3].setText(String.format(Locale.ROOT, "Mouse %.0f, %.0f   moved %.0f, %.0f   wheel %.1f   %s",
                    input.getMouseX(), input.getMouseY(), input.getMouseDeltaX(), input.getMouseDeltaY(), input.getScrollY(),
                    input.isMouseInWindow() ? "in window" : "outside"));
            panelLines[4].setText("Actions held: " + actions);
            panelLines[5].setText("Active contexts: " + active);
        }

        @Override
        protected void render(Renderer renderer, float alpha) {
            float w = (float) getContainer().getWidth(), h = (float) getContainer().getHeight();
            renderer.drawQuad(0, 0, w, h, FIELD, 0f);
            if (showGrid) {
                for (float gx = 0; gx < w; gx += 40) renderer.drawLine(gx, 0, gx, h, GRID, 1f);
                for (float gy = 0; gy < h; gy += 40) renderer.drawLine(0, gy, w, gy, GRID, 1f);
            }
            float px = prevX + (x - prevX) * alpha, py = prevY + (y - prevY) * alpha;
            if (charge > 0f) renderer.drawCircle(px + size / 2, py + size / 2, size * 0.7f + 50f * charge, CHARGE);
            for (float[] wave : waves) renderer.drawBorder(wave[0] - wave[2], wave[1] - wave[2], wave[2] * 2, wave[2] * 2, WAVE, 3f, wave[2]);
            for (float[] b : bullets) renderer.drawCircle(b[4] + (b[0] - b[4]) * alpha, b[5] + (b[1] - b[5]) * alpha, 5f, BULLET);
            renderer.drawQuad(px, py, size, size, dashLeft > 0f ? DASHING : PLAYER, 6f);
            if (showPanel) renderer.drawQuad(8, h - 24 - panelLines.length * 18, w - 16, panelLines.length * 18 + 18, PANEL, 6f);
        }
    }

    /**
     * A dialogue box over a game that keeps running. Its context is blocking, so no key reaches the game while it is
     * open, bound here or not.
     */
    static class DialogueScene extends Scene {
        private static final String[] LINES = {
                "The game is still running under this box: the bullets keep flying.",
                "But its context is blocking, so the player can't move, charge or shoot.",
                "Space went to this box, not the charge. Press it again to close."};
        private final InputContext dialogue = new InputContext("dialogue")
                .bind("next", Binding.key(GLFW_KEY_SPACE), Binding.key(GLFW_KEY_ENTER), Binding.mouse(GLFW_MOUSE_BUTTON_LEFT))
                .setBlocking(true);
        private static final Color BOX = new Color(0.1f, 0.12f, 0.18f, 0.92f);
        private final TextOverlay text = new TextOverlay("", 16f, Color.WHITE);
        private final TextOverlay hint = new TextOverlay("", 13f, Color.GRAY);
        private int line;

        DialogueScene() {
            setOverlay(true);
            setPausesBelow(false);
            addInputContext(dialogue);
        }

        @Override
        protected void onEnter() {
            getContainer().addElements(text, hint);
            show();
        }

        private void show() {
            double h = getContainer().getHeight();
            text.setText(LINES[line]);
            hint.setText("Space, Enter or click to continue   (" + (line + 1) + "/" + LINES.length + ")");
            text.setPosition(60, h - 250);
            hint.setPosition(60, h - 220);
        }

        @Override
        protected void onResize(int width, int height) {
            show();
        }

        @Override
        protected void update(float delta) {
            if (!dialogue.isJustPressed("next")) return;
            if (++line >= LINES.length) {
                getSceneManager().remove(this);
            } else {
                show();
            }
        }

        @Override
        protected void render(Renderer renderer, float alpha) {
            float w = (float) getContainer().getWidth(), h = (float) getContainer().getHeight();
            renderer.drawQuad(40, h - 270, w - 80, 80, BOX, 10f);
        }
    }

    /**
     * Rebinding: each action gets a button showing its bindings. Clicking one waits for the next key, button or wheel
     * direction, which replaces the action's bindings and is taken off any other action that had it.
     */
    static class ControlsScene extends Scene {
        private final InputContext menu = new InputContext("menu").bind("close", Binding.key(GLFW_KEY_ESCAPE));
        private final TextOverlay title = new TextOverlay("Controls", 30f, Color.WHITE);
        private final TextOverlay hint = new TextOverlay("Click a binding, then press a key, mouse button or wheel. Escape cancels.", 14f, Color.GRAY);
        private final List<TextOverlay> labels = new ArrayList<>();
        private final List<ButtonOverlay> buttons = new ArrayList<>();
        private final ButtonOverlay reset = new ButtonOverlay("Reset all", 140, 34);
        private final ButtonOverlay back = new ButtonOverlay("Back", 140, 34);
        private String waiting; // the action waiting for a binding

        ControlsScene() {
            setOverlay(true);
            addInputContext(menu);
        }

        @Override
        protected void onEnter() {
            getContainer().getStyling().setBackgroundColor(new Color(0f, 0f, 0f, 0.75f));
            getContainer().addElements(title, hint, reset, back);
            for (String[] entry : LABELS) {
                String action = entry[0];
                TextOverlay label = new TextOverlay(entry[1], 15f, Color.WHITE);
                ButtonOverlay button = new ButtonOverlay("", 14f, Color.WHITE, 300, 30);
                button.onAction(() -> listen(action));
                labels.add(label);
                buttons.add(button);
                getContainer().addElements(label, button);
            }
            reset.onAction(() -> {
                getWindow().getInput().cancelListening();
                waiting = null;
                GAMEPLAY.resetBindings();
                saveControls();
                refresh();
            });
            back.onAction(this::close);
            layout();
            refresh();
        }

        private void listen(String action) {
            waiting = action;
            refresh();
            getWindow().getInput().listenForBinding(binding -> {
                waiting = null;
                if (!binding.equals(Binding.key(GLFW_KEY_ESCAPE))) {
                    for (String other : GAMEPLAY.getActionsBoundTo(binding)) GAMEPLAY.removeBinding(other, binding);
                    GAMEPLAY.setBindings(action, binding);
                    saveControls();
                }
                refresh();
            });
        }

        private void refresh() {
            for (int i = 0; i < LABELS.length; i++) {
                String action = LABELS[i][0];
                buttons.get(i).setText(action.equals(waiting) ? "Press a key..." : bindingsText(action));
            }
        }

        private void close() {
            getWindow().getInput().cancelListening();
            getSceneManager().remove(this);
        }

        @Override
        protected void update(float delta) {
            if (menu.isJustPressed("close")) close();
        }

        @Override
        protected void onResize(int width, int height) {
            layout();
        }

        private void layout() {
            double w = getContainer().getWidth();
            double left = w / 2 - 250, top = 90;
            title.setPosition(left, 30);
            hint.setPosition(left, 70);
            for (int i = 0; i < labels.size(); i++) {
                labels.get(i).setPosition(left, top + 26 + i * 38);
                buttons.get(i).setPosition(left + 200, top + 20 + i * 38);
            }
            double bottom = top + 30 + labels.size() * 38;
            reset.setPosition(left + 200, bottom);
            back.setPosition(left + 360, bottom);
        }
    }
}
