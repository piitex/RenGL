package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.game.GameTime;
import me.piitex.engine.game.Scene;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Scenes and game time: a main menu, a small game, and a pause menu drawn over it.
 * <ul>
 *   <li>The balls and the player move in {@link Scene#fixedUpdate} at 60 ticks per second and are drawn between
 *       ticks with {@link GameTime#getAlpha()}, so they stay smooth at any frame rate.</li>
 *   <li>Arrow keys move the player, 1/2/3 set the time scale (slow motion, normal, double), Escape pauses.</li>
 *   <li>A new ball appears every two seconds of game time, from a scene timer: it slows down with the time scale
 *       and stops while the pause menu is open.</li>
 *   <li>The pause menu is an overlay scene, so the paused game stays visible behind it.</li>
 * </ul>
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class GameLoopDemo {

    static void main() {
        // To reduce console spam add these jvm flags
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Window window = new Window(new WindowOptions("Game Loop Demo").setDimensions(960, 600));
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        window.getSceneManager().set(new MenuScene());
        new Engine().start(window);
    }

    static class MenuScene extends Scene {
        private final TextOverlay title = new TextOverlay("Game Loop Demo", 40f, Color.WHITE);
        private final ButtonOverlay play = new ButtonOverlay("Play", 180, 40);

        @Override
        protected void onEnter() {
            play.onAction(() -> getSceneManager().set(new PlayScene()));
            getContainer().addElement(title);
            getContainer().addElement(play);
            layout();
        }

        @Override
        protected void onResize(int width, int height) {
            layout();
        }

        private void layout() {
            double w = getContainer().getWidth(), h = getContainer().getHeight();
            title.setPosition(w / 2 - 150, h / 2 - 90);
            play.setPosition(w / 2 - 90, h / 2);
        }
    }

    static class PlayScene extends Scene {
        private static final float PLAYER_SPEED = 320f, PLAYER_SIZE = 36f, BALL_RADIUS = 12f;
        private static final Color FIELD = new Color(0.08f, 0.09f, 0.12f, 1f);
        private static final Color PLAYER = new Color(0.35f, 0.75f, 1f, 1f);

        private final List<Ball> balls = new ArrayList<>();
        private final TextOverlay stats = new TextOverlay("", 14f, Color.WHITE);
        private final TextOverlay help = new TextOverlay("Arrows move   1 / 2 / 3 time scale   Esc pause", 14f, Color.GRAY);
        private boolean left, right, up, down;
        private float x = 100, y = 100, prevX = x, prevY = y;

        @Override
        protected void onEnter() {
            stats.setPosition(16, 12);
            help.setPosition(16, 34);
            getContainer().addElement(stats);
            getContainer().addElement(help);
            for (int i = 0; i < 3; i++) addBall();
            every(2f, () -> {
                if (balls.size() < 20) addBall();
            });
        }

        private void addBall() {
            double angle = Math.random() * Math.PI * 2;
            float speed = 150 + (float) Math.random() * 200;
            float bx = (float) getContainer().getWidth() / 2, by = (float) getContainer().getHeight() / 2;
            Color color = Color.fromHSV((float) Math.random() * 360f, 0.6f, 1f, 1f);
            balls.add(new Ball(bx, by, (float) Math.cos(angle) * speed, (float) Math.sin(angle) * speed, color));
        }

        @Override
        protected void fixedUpdate(float step) {
            float w = (float) getContainer().getWidth(), h = (float) getContainer().getHeight();

            prevX = x;
            prevY = y;
            float dx = (right ? 1 : 0) - (left ? 1 : 0), dy = (down ? 1 : 0) - (up ? 1 : 0);
            x = clamp(x + dx * PLAYER_SPEED * step, 0, w - PLAYER_SIZE);
            y = clamp(y + dy * PLAYER_SPEED * step, 0, h - PLAYER_SIZE);

            for (Ball ball : balls) {
                ball.prevX = ball.x;
                ball.prevY = ball.y;
                ball.x += ball.vx * step;
                ball.y += ball.vy * step;
                if (ball.x < BALL_RADIUS || ball.x > w - BALL_RADIUS) {
                    ball.vx = -ball.vx;
                    ball.x = clamp(ball.x, BALL_RADIUS, w - BALL_RADIUS);
                }
                if (ball.y < BALL_RADIUS || ball.y > h - BALL_RADIUS) {
                    ball.vy = -ball.vy;
                    ball.y = clamp(ball.y, BALL_RADIUS, h - BALL_RADIUS);
                }
            }
        }

        @Override
        protected void update(float delta) {
            stats.setText(String.format(Locale.ROOT, "Game time %.1f s   Real time %.1f s   Ticks %d   Scale %.2fx   Balls %d   FPS %.0f",
                    GameTime.getTime(), GameTime.getRealTime(), GameTime.getTicks(), GameTime.getTimeScale(),
                    balls.size(), getWindow().getFps()));
        }

        @Override
        protected void render(Renderer renderer, float alpha) {
            renderer.drawQuad(0, 0, (float) getContainer().getWidth(), (float) getContainer().getHeight(), FIELD, 0f);
            for (Ball ball : balls) {
                renderer.drawCircle(lerp(ball.prevX, ball.x, alpha), lerp(ball.prevY, ball.y, alpha), BALL_RADIUS, ball.color);
            }
            renderer.drawQuad(lerp(prevX, x, alpha), lerp(prevY, y, alpha), PLAYER_SIZE, PLAYER_SIZE, PLAYER, 6f);
        }

        @Override
        protected boolean onKey(int key, int action, int mods) {
            boolean held = action != GLFW_RELEASE;
            switch (key) {
                case GLFW_KEY_LEFT -> left = held;
                case GLFW_KEY_RIGHT -> right = held;
                case GLFW_KEY_UP -> up = held;
                case GLFW_KEY_DOWN -> down = held;
                default -> {
                    if (action != GLFW_PRESS) return false;
                    switch (key) {
                        case GLFW_KEY_1 -> GameTime.setTimeScale(0.25f);
                        case GLFW_KEY_2 -> GameTime.setTimeScale(1f);
                        case GLFW_KEY_3 -> GameTime.setTimeScale(2f);
                        case GLFW_KEY_ESCAPE -> getSceneManager().push(new PauseScene());
                        default -> {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        @Override
        protected void onExit() {
            GameTime.setTimeScale(1f); // don't leave the next game in slow motion
        }

        private static float lerp(float from, float to, float alpha) {
            return from + (to - from) * alpha;
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    static class PauseScene extends Scene {
        private final TextOverlay title = new TextOverlay("Paused", 36f, Color.WHITE);
        private final ButtonOverlay resume = new ButtonOverlay("Resume", 180, 40);
        private final ButtonOverlay menu = new ButtonOverlay("Main menu", 180, 40);

        PauseScene() {
            setOverlay(true); // the paused game stays visible behind the menu
        }

        @Override
        protected void onEnter() {
            getContainer().getStyling().setBackgroundColor(new Color(0f, 0f, 0f, 0.55f));
            resume.onAction(() -> getSceneManager().pop());
            menu.onAction(() -> getSceneManager().set(new MenuScene()));
            getContainer().addElement(title);
            getContainer().addElement(resume);
            getContainer().addElement(menu);
            layout();
        }

        @Override
        protected void onResize(int width, int height) {
            layout();
        }

        private void layout() {
            double w = getContainer().getWidth(), h = getContainer().getHeight();
            title.setPosition(w / 2 - 60, h / 2 - 110);
            resume.setPosition(w / 2 - 90, h / 2 - 30);
            menu.setPosition(w / 2 - 90, h / 2 + 24);
        }

        @Override
        protected boolean onKey(int key, int action, int mods) {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
                getSceneManager().pop();
                return true;
            }
            return false;
        }
    }

    private static final class Ball {
        float x, y, prevX, prevY, vx, vy;
        final Color color;

        Ball(float x, float y, float vx, float vy, Color color) {
            this.x = prevX = x;
            this.y = prevY = y;
            this.vx = vx;
            this.vy = vy;
            this.color = color;
        }
    }
}
