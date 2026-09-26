package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.game.GameTime;
import me.piitex.engine.game.Scene;
import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.graphics.Animation;
import me.piitex.engine.graphics.AnimationPlayer;
import me.piitex.engine.graphics.Camera;
import me.piitex.engine.graphics.Sprite;
import me.piitex.engine.graphics.SpriteBatch;
import me.piitex.engine.graphics.Texture;
import me.piitex.engine.graphics.TextureAtlas;
import me.piitex.engine.graphics.TextureRegion;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.math.Rect;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Cameras, sprites, an atlas, frame animation and batching, in a small world of hopping slimes.
 * <ul>
 *   <li>The sprites come from {@code demo/sprites.json}, a sprite sheet in the JSON format texture packers and
 *       Aseprite export. Its tags become the hop, coin spin and water animations, and its coin frames are
 *       trimmed.</li>
 *   <li>The camera follows the player with a little lag. That is game code: the camera only holds the view.
 *       Z and X zoom, Q and E turn it, R resets.</li>
 *   <li>Slimes only move while they are in the air. Their hop animation reports "jump" and "land" events on the
 *       right frames, and the movement follows them.</li>
 *   <li>Everything in view goes through one {@link SpriteBatch}: tiles on the bottom layer, shadows above them,
 *       and slimes and coins sorted by y so the lower ones stand in front. The stats line shows how many sprites
 *       were drawn, how many were left out for being out of view, and how few draw calls it took.</li>
 *   <li>A second camera draws the minimap in the corner. The map there is drawn once into an image at startup.</li>
 *   <li>Clicking drops a new slime where you click, found with {@link Camera#screenToWorld}. The tile under the
 *       mouse is outlined.</li>
 * </ul>
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class SpriteDemo {

    static void main() {
        // To reduce console spam add these jvm flags
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Window window = new Window(new WindowOptions("Sprite Demo").setDimensions(1100, 700));
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        TextureAtlas atlas = TextureAtlas.load("src/main/resources/demo/sprites.json");
        atlas.get("grass_0").getTexture().setSmooth(false); // pixel art: keep the pixels square when zoomed in
        window.getSceneManager().set(new WorldScene(atlas));
        new Engine().start(window);
    }

    static class WorldScene extends Scene {
        private static final int TILE = 16, MAP = 96, MINIMAP = 180;
        private static final int GRASS = 0, FLOWERS = 1, PATH = 2, WATER = 3, STONE = 4;
        private static final Color BACKGROUND = new Color(0.05f, 0.06f, 0.08f, 1f);
        private static final Color HOVER = new Color(1f, 1f, 1f, 0.8f);
        private static final Color MINIMAP_VIEW = new Color(1f, 1f, 1f, 0.9f);
        private static final Color PLAYER_COLOR = new Color(0.45f, 0.75f, 1f, 1f);

        private final TextureAtlas atlas;
        private final int[][] map = new int[MAP][MAP];
        private final Random random = new Random(7);

        private final Camera camera = new Camera();
        private final Camera minimap = new Camera();
        private final SpriteBatch batch = new SpriteBatch();
        private final Sprite tile = new Sprite(); // one sprite, moved and added once per tile
        private Texture mapImage;
        private final float[] outline = new float[10];

        private final List<Slime> slimes = new ArrayList<>();
        private final List<Coin> coins = new ArrayList<>();
        private Slime player;
        private Animation hop, idle, spin, water;
        private boolean left, right, up, down;
        private int zoom = 3;
        private int collected;

        private final TextOverlay stats = new TextOverlay("", 14f, Color.WHITE);
        private final TextOverlay help = new TextOverlay(
                "WASD / arrows move   Z / X zoom   Q / E turn the camera   R reset   Click drops a slime", 14f, Color.GRAY);

        WorldScene(TextureAtlas atlas) {
            this.atlas = atlas;
        }

        @Override
        protected void onEnter() {
            hop = atlas.getAnimation("hop").withEvent(1, "jump").withEvent(3, "land");
            idle = Animation.of(0.6f, Animation.PlayMode.LOOP, atlas.get("slime_1"), atlas.get("slime_3"));
            spin = atlas.getAnimation("spin");
            water = atlas.getAnimation("water");
            tile.setLayer(0);

            generateMap();
            mapImage = drawMapImage();

            player = new Slime(MAP * TILE / 2f, MAP * TILE / 2f, PLAYER_COLOR);
            player.speed = 150f;
            slimes.add(player);
            for (int i = 0; i < 250; i++) spawnSlime(random.nextFloat() * MAP * TILE, random.nextFloat() * MAP * TILE);
            for (int i = 0; i < 120; i++) {
                coins.add(new Coin(random.nextFloat() * MAP * TILE, random.nextFloat() * MAP * TILE, random.nextFloat()));
            }

            camera.setPixelSnap(true);
            camera.setZoom(zoom);
            camera.setPosition(player.x, player.y);
            layout((int) getContainer().getWidth(), (int) getContainer().getHeight());

            stats.setPosition(16, 12);
            help.setPosition(16, 34);
            getContainer().addElement(stats);
            getContainer().addElement(help);
            getContainer().onMouseClick(event -> {
                Vec2 world = camera.screenToWorld((float) event.getMouseX(), (float) event.getMouseY());
                spawnSlime(world.x(), world.y());
            });
        }

        @Override
        protected void onResize(int width, int height) {
            layout(width, height);
        }

        private void layout(int width, int height) {
            camera.setViewport(0, 0, width, height);
            minimap.setViewport(width - MINIMAP - 16, height - MINIMAP - 16, MINIMAP, MINIMAP);
            minimap.setZoom(MINIMAP / (float) (MAP * TILE));
            minimap.setPosition(MAP * TILE / 2f, MAP * TILE / 2f);
        }

        private void spawnSlime(float x, float y) {
            Slime slime = new Slime(x, y, Color.fromHSV(random.nextFloat(), 0.45f, 1f, 1f));
            slime.wanderIn(random.nextFloat() * 3f);
            slimes.add(slime);
        }

        @Override
        protected void fixedUpdate(float step) {
            for (Slime slime : slimes) {
                slime.prevX = slime.x;
                slime.prevY = slime.y;
                if (slime == player) {
                    slime.dirX = (right ? 1 : 0) - (left ? 1 : 0);
                    slime.dirY = (down ? 1 : 0) - (up ? 1 : 0);
                } else if ((slime.wanderTimer -= step) <= 0f) {
                    slime.wanderIn(1f + random.nextFloat() * 3f);
                }
                if (slime.airborne && (slime.dirX != 0 || slime.dirY != 0)) {
                    float length = (float) Math.hypot(slime.dirX, slime.dirY);
                    slime.x = clamp(slime.x + slime.dirX / length * slime.speed * step, 8, MAP * TILE - 8);
                    slime.y = clamp(slime.y + slime.dirY / length * slime.speed * step, 8, MAP * TILE - 8);
                }
            }
            for (Iterator<Coin> it = coins.iterator(); it.hasNext(); ) {
                Coin coin = it.next();
                if (Math.abs(coin.x - player.x) < 10 && Math.abs(coin.y - player.y) < 8) {
                    it.remove();
                    collected++;
                }
            }
        }

        @Override
        protected void update(float delta) {
            for (Slime slime : slimes) {
                boolean moving = slime.dirX != 0 || slime.dirY != 0;
                slime.anim.play(moving ? hop : idle);
                if (!moving) slime.airborne = false;
                slime.anim.update(delta);
                if (slime.dirX != 0) slime.sprite.setFlipX(slime.dirX < 0);
            }

            // Follow the player, easing towards where it is drawn this frame. Frame-rate independent.
            float alpha = GameTime.getAlpha();
            float targetX = lerp(player.prevX, player.x, alpha), targetY = lerp(player.prevY, player.y, alpha);
            float follow = 1f - (float) Math.exp(-8f * delta);
            camera.setPosition(lerp(camera.getX(), targetX, follow), lerp(camera.getY(), targetY, follow));

            stats.setText(String.format(Locale.ROOT,
                    "Sprites drawn %d   Out of view %d   Draw calls %d   Slimes %d   Coins %d   Zoom %dx   FPS %.0f",
                    batch.getDrawnCount(), batch.getCulledCount(), batch.getDrawCallCount(), slimes.size(), collected,
                    zoom, getWindow().getFps()));
        }

        @Override
        protected void render(Renderer renderer, float alpha) {
            renderer.drawQuad(0, 0, (float) getContainer().getWidth(), (float) getContainer().getHeight(), BACKGROUND, 0f);

            camera.begin(renderer);
            Rect view = camera.getVisibleBounds();
            addTiles(view);
            addCharacters(alpha);
            batch.draw(renderer, view);
            drawHover(renderer);
            camera.end(renderer);

            drawMinimap(renderer, alpha);
        }

        /**
         * Only the tiles in view: a map knows where its tiles are, so it needn't hand all nine thousand to the batch.
         */
        private void addTiles(Rect view) {
            int x0 = Math.max(0, (int) Math.floor(view.x() / TILE)), x1 = Math.min(MAP - 1, (int) Math.floor(view.right() / TILE));
            int y0 = Math.max(0, (int) Math.floor(view.y() / TILE)), y1 = Math.min(MAP - 1, (int) Math.floor(view.bottom() / TILE));
            TextureRegion waterFrame = water.getRegionAt(GameTime.getTime());
            for (int ty = y0; ty <= y1; ty++) {
                for (int tx = x0; tx <= x1; tx++) {
                    tile.setRegion(map[ty][tx] == WATER ? waterFrame : tileRegion(tx, ty));
                    tile.setPosition(tx * TILE, ty * TILE);
                    batch.add(tile);
                }
            }
        }

        private void addCharacters(float alpha) {
            TextureRegion shadow = atlas.get("shadow");
            batch.setSortKey(SpriteBatch.BY_Y);
            for (Coin coin : coins) {
                coin.sprite.setRegion(spin.getRegionAt(GameTime.getTime() + coin.phase)); // every coin on its own beat
                batch.add(coin.sprite);
            }
            for (Slime slime : slimes) {
                float x = lerp(slime.prevX, slime.x, alpha), y = lerp(slime.prevY, slime.y, alpha);
                slime.shadow.setRegion(shadow);
                slime.shadow.setPosition(x, y);
                batch.add(slime.shadow);
                slime.sprite.setRegion(slime.anim.getFrame());
                slime.sprite.setPosition(x, y);
                batch.add(slime.sprite);
            }
            batch.setSortKey(null);
        }

        private void drawHover(Renderer renderer) {
            if (!MouseTracker.isInWindow()) return;
            Vec2 world = camera.screenToWorld((float) MouseTracker.getX(), (float) MouseTracker.getY());
            int tx = (int) Math.floor(world.x() / TILE), ty = (int) Math.floor(world.y() / TILE);
            if (tx < 0 || ty < 0 || tx >= MAP || ty >= MAP) return;
            renderer.drawBorder(tx * TILE, ty * TILE, TILE, TILE, HOVER, 1f / zoom, 0f);
        }

        private void drawMinimap(Renderer renderer, float alpha) {
            float mx = minimap.getViewportX(), my = minimap.getViewportY();
            renderer.drawQuad(mx - 3, my - 3, MINIMAP + 6, MINIMAP + 6, BACKGROUND, 4f);
            minimap.begin(renderer);
            float size = MAP * TILE;
            renderer.drawImageRegion(mapImage.getImage(), 0, 0, mapImage.getWidth(), mapImage.getHeight(), 0, 0, size, size, null, true);
            for (Slime slime : slimes) {
                renderer.drawCircle(lerp(slime.prevX, slime.x, alpha), lerp(slime.prevY, slime.y, alpha),
                        slime == player ? 28f : 14f, slime == player ? Color.WHITE : slime.sprite.getColor());
            }
            // The main camera's view: its viewport's corners, turned into world points.
            float vx = camera.getViewportX(), vy = camera.getViewportY();
            float vw = camera.getViewportWidth(), vh = camera.getViewportHeight();
            float[][] corners = {{vx, vy}, {vx + vw, vy}, {vx + vw, vy + vh}, {vx, vy + vh}, {vx, vy}};
            for (int i = 0; i < corners.length; i++) {
                Vec2 corner = camera.screenToWorld(corners[i][0], corners[i][1]);
                outline[i * 2] = corner.x();
                outline[i * 2 + 1] = corner.y();
            }
            renderer.drawPolyline(outline, 5, MINIMAP_VIEW, 1.5f / minimap.getZoom());
            minimap.end(renderer);
        }

        @Override
        protected boolean onKey(int key, int action, int mods) {
            boolean held = action != GLFW_RELEASE;
            switch (key) {
                case GLFW_KEY_LEFT, GLFW_KEY_A -> left = held;
                case GLFW_KEY_RIGHT, GLFW_KEY_D -> right = held;
                case GLFW_KEY_UP, GLFW_KEY_W -> up = held;
                case GLFW_KEY_DOWN, GLFW_KEY_S -> down = held;
                default -> {
                    if (action == GLFW_RELEASE) return false;
                    switch (key) {
                        case GLFW_KEY_Z -> zoom = Math.min(6, zoom + 1);
                        case GLFW_KEY_X -> zoom = Math.max(1, zoom - 1);
                        case GLFW_KEY_Q -> camera.setRotation(camera.getRotation() - 15f);
                        case GLFW_KEY_E -> camera.setRotation(camera.getRotation() + 15f);
                        case GLFW_KEY_R -> {
                            zoom = 3;
                            camera.setRotation(0f);
                        }
                        default -> {
                            return false;
                        }
                    }
                    camera.setZoom(zoom);
                }
            }
            return true;
        }

        // ---- the map

        private void generateMap() {
            for (int y = 0; y < MAP; y++) {
                for (int x = 0; x < MAP; x++) map[y][x] = random.nextInt(100) < 6 ? FLOWERS : GRASS;
            }
            for (int lake = 0; lake < 9; lake++) {
                int cx = random.nextInt(MAP), cy = random.nextInt(MAP), r = 3 + random.nextInt(6);
                for (int y = cy - r; y <= cy + r; y++) {
                    for (int x = cx - r; x <= cx + r; x++) {
                        if (x >= 0 && y >= 0 && x < MAP && y < MAP && (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) map[y][x] = WATER;
                    }
                }
            }
            for (int wall = 0; wall < 14; wall++) {
                int x = random.nextInt(MAP), y = random.nextInt(MAP), length = 4 + random.nextInt(10);
                boolean across = random.nextBoolean();
                for (int i = 0; i < length; i++) {
                    int wx = across ? x + i : x, wy = across ? y : y + i;
                    if (wx < MAP && wy < MAP) map[wy][wx] = STONE;
                }
            }
            for (int i = 0; i < MAP; i++) { // two paths crossing in the middle
                map[MAP / 2][i] = PATH;
                map[i][MAP / 2] = PATH;
            }
        }

        private TextureRegion tileRegion(int x, int y) {
            return switch (map[y][x]) {
                case FLOWERS -> atlas.get("flowers");
                case PATH -> atlas.get("path");
                case STONE -> atlas.get("stone");
                case WATER -> atlas.get("water_0");
                default -> atlas.get("grass_" + Math.floorMod(x * 7 + y * 13, 3));
            };
        }

        /**
         * Draws the whole map, small, into an image for the minimap: once, instead of thousands of tiles a frame.
         */
        private Texture drawMapImage() {
            int size = MAP * TILE;
            try (OffscreenRenderer offscreen = new OffscreenRenderer(size, size, MINIMAP * 2f / size)) {
                offscreen.begin();
                for (int y = 0; y < MAP; y++) {
                    for (int x = 0; x < MAP; x++) {
                        tile.setRegion(tileRegion(x, y));
                        tile.setPosition(x * TILE, y * TILE);
                        batch.add(tile);
                    }
                }
                batch.draw(offscreen);
                offscreen.end();
                return new Texture(offscreen.makeImage());
            }
        }

        private static float lerp(float from, float to, float alpha) {
            return from + (to - from) * alpha;
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private final class Slime {
            final Sprite sprite = new Sprite();
            final Sprite shadow = new Sprite();
            final AnimationPlayer anim = new AnimationPlayer();
            float x, y, prevX, prevY;
            float dirX, dirY;
            float speed = 60f;
            float wanderTimer;
            boolean airborne;

            Slime(float x, float y, Color color) {
                this.x = prevX = x;
                this.y = prevY = y;
                sprite.setPivot(0.5f, 1f); // stand on (x, y)
                sprite.setColor(color);    // the art is gray; the color tints it
                sprite.setLayer(2);
                shadow.setPivot(0.5f, 0.5f);
                shadow.setScale(1.25f, 1f);
                shadow.setLayer(1);
                anim.onEvent(event -> airborne = event.equals("jump"));
            }

            /**
             * Picks a new direction, or stands still, for {@code seconds}.
             */
            void wanderIn(float seconds) {
                wanderTimer = seconds;
                if (random.nextInt(3) == 0) {
                    dirX = dirY = 0;
                } else {
                    double angle = random.nextDouble() * Math.PI * 2;
                    dirX = (float) Math.cos(angle);
                    dirY = (float) Math.sin(angle);
                }
            }
        }

        private final class Coin {
            final Sprite sprite = new Sprite();
            final float x, y, phase;

            Coin(float x, float y, float phase) {
                this.x = x;
                this.y = y;
                this.phase = phase;
                sprite.setPivot(0.5f, 1f);
                sprite.setPosition(x, y);
                sprite.setLayer(2);
            }
        }
    }
}
