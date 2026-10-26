package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.game.Scene;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.graphics.Camera;
import me.piitex.engine.graphics.Texture;
import me.piitex.engine.input.Binding;
import me.piitex.engine.input.Input;
import me.piitex.engine.input.InputContext;
import me.piitex.engine.math.Polygon;
import me.piitex.engine.math.Rect;
import me.piitex.engine.math.Shape;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.tilemap.Tile;
import me.piitex.engine.tilemap.TileLayer;
import me.piitex.engine.tilemap.TileMap;
import me.piitex.engine.tilemap.TileSet;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Canvas;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.Paint;
import org.jetbrains.skia.PaintMode;
import org.jetbrains.skia.Path;
import org.jetbrains.skia.PathBuilder;

import java.io.File;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;

/**
 * The five ways a {@link TileMap} can lay out its cells, each one built in code with no map file: orthogonal squares, an
 * isometric diamond, a staggered isometric rectangle, and hexagons with pointed and with flat tops. Press 1 to 5 to switch.
 * <ul>
 *   <li>The tiles are pictures drawn at startup with the graphics library: a diamond or hexagon of grass, water, sand, stone and
 *       forest, the size of a cell. The map draws them where Tiled would, in the order that lets a tall tile overlap the ones
 *       behind it, and only the cells in view.</li>
 *   <li>Point at the map: {@link TileMap#toCell} finds the cell under the mouse, whatever the layout, and
 *       {@link TileMap#getCellShape} gives its outline. A point near a corner belongs to the right cell, which a
 *       division by the tile size does not give once cells are diamonds or hexagons.</li>
 *   <li>Click to change the cell's tile to the next kind.</li>
 *   <li>G draws every cell's outline. C marks the cells that are solid (stone and forest) with
 *       {@link TileLayer#getSolidShapes}, which gives one diamond or hexagon for each cell on the other layouts, where an
 *       orthogonal map gets rectangles joined into as few as easily fit.</li>
 *   <li>Z and X, or the wheel, zoom. The arrow keys or WASD move the view.</li>
 * </ul>
 * {@code -Drengl.demo.layout=3}, {@code -Drengl.demo.grid=true} and {@code -Drengl.demo.solid=true} start it on a layout with the grid
 * or the solid cells showing.
 */
public class TileMapDemo {
    private static final String[] KINDS = {"Grass", "Water", "Sand", "Stone", "Forest"};
    private static final int[] COLORS = {0xFF5FA85A, 0xFF3F7FC4, 0xFFD9C37A, 0xFF8A8D93, 0xFF2F6B3E};

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Window window = new Window(new WindowOptions("Tile map layouts").setDimensions(1100, 700));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        window.getSceneManager().set(new MapScene());
        new Engine().start(window);
    }

    private record Layout(String name, TileMap map, TileSet tiles) {
    }

    static class MapScene extends Scene {
        private static final Color BACKGROUND = new Color(0.07f, 0.08f, 0.10f, 1f);
        private static final Color HOVER = new Color(1f, 1f, 1f, 0.95f);
        private static final Color GRID = new Color(0f, 0f, 0f, 0.35f);
        private static final Color SOLID = new Color(1f, 0.2f, 0.2f, 0.35f);

        private final Camera camera = new Camera();
        private final InputContext controls = new InputContext("map")
                .bind("zoom_in", Binding.key(GLFW_KEY_Z), Binding.wheel(Binding.WHEEL_UP))
                .bind("zoom_out", Binding.key(GLFW_KEY_X), Binding.wheel(Binding.WHEEL_DOWN))
                .bind("left", Binding.key(GLFW_KEY_A), Binding.key(GLFW_KEY_LEFT))
                .bind("right", Binding.key(GLFW_KEY_D), Binding.key(GLFW_KEY_RIGHT))
                .bind("up", Binding.key(GLFW_KEY_W), Binding.key(GLFW_KEY_UP))
                .bind("down", Binding.key(GLFW_KEY_S), Binding.key(GLFW_KEY_DOWN))
                .bind("paint", Binding.mouse(GLFW_MOUSE_BUTTON_LEFT));

        private final TextOverlay title = new TextOverlay("", 22f, Color.WHITE);
        private final TextOverlay info = new TextOverlay("", 14f, Color.WHITE);
        private final TextOverlay help = new TextOverlay(
                "1 - 5 layout   Click changes a tile   G grid   C solid cells   Z / X or wheel zoom   WASD / arrows move",
                14f, Color.GRAY);

        private List<Layout> layouts;
        private Layout current;
        private float zoom = 1f;
        private boolean grid, solid;

        MapScene() {
            addInputContext(controls);
        }

        @Override
        protected void onEnter() {
            layouts = List.of(
                    make("1  Orthogonal", new TileMap(14, 10, 48, 48), 48, 48, 0),
                    make("2  Isometric", TileMap.isometric(12, 12, 96, 48), 96, 48, 1),
                    make("3  Staggered, rows", TileMap.staggered(13, 18, 96, 48, TileMap.StaggerAxis.Y, true), 96, 48, 2),
                    make("4  Hexagons, pointed", TileMap.hexagonal(13, 11, 84, 96, 48, TileMap.StaggerAxis.Y, true), 84, 96, 3),
                    make("5  Hexagons, flat", TileMap.hexagonal(14, 11, 96, 84, 48, TileMap.StaggerAxis.X, true), 96, 84, 4));
            title.setPosition(16, 12);
            info.setPosition(16, 44);
            help.setPosition(16, 668);
            getContainer().addElement(title);
            getContainer().addElement(info);
            getContainer().addElement(help);
            camera.setViewport(0, 0, (int) getContainer().getWidth(), (int) getContainer().getHeight());
            grid = Boolean.getBoolean("rengl.demo.grid");
            solid = Boolean.getBoolean("rengl.demo.solid");
            show(Math.max(0, Math.min(layouts.size() - 1, Integer.getInteger("rengl.demo.layout", 1) - 1)));
        }

        @Override
        protected void onResize(int width, int height) {
            camera.setViewport(0, 0, width, height);
        }

        @Override
        protected boolean onKey(int key, int action, int mods) {
            if (action != GLFW_PRESS) return false;
            if (key >= GLFW_KEY_1 && key < GLFW_KEY_1 + layouts.size()) {
                show(key - GLFW_KEY_1);
                return true;
            }
            if (key == GLFW_KEY_G) {
                grid = !grid;
                return true;
            }
            if (key == GLFW_KEY_C) {
                solid = !solid;
                return true;
            }
            return false;
        }

        private void show(int index) {
            current = layouts.get(index);
            title.setText(current.name());
            Rect bounds = current.map().getBounds();
            camera.setPosition(bounds.x() + bounds.width() / 2f, bounds.y() + bounds.height() / 2f);
            float fitX = (camera.getViewportWidth() - 80f) / bounds.width(), fitY = (camera.getViewportHeight() - 140f) / bounds.height();
            zoom = Math.max(0.3f, Math.min(2.5f, Math.min(fitX, fitY)));
            camera.setZoom(zoom);
        }

        @Override
        protected void update(float delta) {
            if (controls.isJustPressed("zoom_in")) zoom = Math.min(4f, zoom * 1.15f);
            if (controls.isJustPressed("zoom_out")) zoom = Math.max(0.2f, zoom / 1.15f);
            camera.setZoom(zoom);
            float step = 500f * delta / zoom;
            if (controls.isDown("left")) camera.move(-step, 0);
            if (controls.isDown("right")) camera.move(step, 0);
            if (controls.isDown("up")) camera.move(0, -step);
            if (controls.isDown("down")) camera.move(0, step);

            Input input = getWindow().getInput();
            Vec2 world = camera.screenToWorld(input.getMouseX(), input.getMouseY());
            TileMap map = current.map();
            TileMap.Cell cell = map.toCell(world.x(), world.y());
            TileLayer ground = map.getTileLayer("ground");
            Tile tile = ground.getTile(cell.x(), cell.y());

            if (tile != null && controls.isJustPressed("paint")) { // only clicks no element took
                int next = (tile.getId() + 1) % KINDS.length;
                ground.setTile(cell.x(), cell.y(), current.tiles().getTile(next));
                tile = ground.getTile(cell.x(), cell.y());
            }
            info.setText(tile == null
                    ? String.format(Locale.ROOT, "Cell (%d, %d): outside the map   Mouse at %.0f, %.0f", cell.x(), cell.y(), world.x(), world.y())
                    : String.format(Locale.ROOT, "Cell (%d, %d): %s   Centre at %.0f, %.0f   %s, %dx%d tiles",
                    cell.x(), cell.y(), KINDS[tile.getId()], map.getCellCenter(cell.x(), cell.y())[0], map.getCellCenter(cell.x(), cell.y())[1],
                    map.getOrientation(), map.getWidth(), map.getHeight()));
        }

        @Override
        protected void render(Renderer renderer, float alpha) {
            float width = camera.getViewportWidth(), height = camera.getViewportHeight();
            renderer.drawQuad(0, 0, width, height, BACKGROUND, 0f);

            TileMap map = current.map();
            camera.begin(renderer);
            Rect view = camera.getVisibleBounds();
            map.draw(renderer, view);

            if (grid) {
                for (int y = 0; y < map.getHeight(); y++) {
                    for (int x = 0; x < map.getWidth(); x++) {
                        if (!map.getCellBounds(x, y).intersects(view)) continue;
                        outline(renderer, map.getCellShape(x, y), GRID, 1f / zoom);
                    }
                }
            }
            if (solid) {
                for (Shape shape : map.getTileLayer("ground").getSolidShapes(tile -> tile.getId() >= 3)) {
                    if (shape instanceof Polygon polygon) renderer.drawPolygon(polygon, SOLID);
                    else if (shape instanceof Rect rect) renderer.drawQuad(rect.x(), rect.y(), rect.width(), rect.height(), SOLID, 0f);
                }
            }

            Input input = getWindow().getInput();
            Vec2 world = camera.screenToWorld(input.getMouseX(), input.getMouseY());
            TileMap.Cell cell = map.toCell(world.x(), world.y());
            if (map.getTileLayer("ground").getTile(cell.x(), cell.y()) != null) {
                outline(renderer, map.getCellShape(cell.x(), cell.y()), HOVER, 2f / zoom);
            }
            camera.end(renderer);
        }

        private static void outline(Renderer renderer, Shape shape, Color color, float thickness) {
            if (shape instanceof Polygon polygon) {
                renderer.drawPolygonOutline(polygon, color, thickness);
            } else if (shape instanceof Rect rect) {
                renderer.drawBorder(rect.x(), rect.y(), rect.width(), rect.height(), color, thickness, 0f);
            }
        }

        /** A map of {@code kind} (0 orthogonal, 1 isometric, ...), filled with a lake, a beach, a few rocks and a forest. */
        private static Layout make(String name, TileMap map, int tileWidth, int tileHeight, int kind) {
            TileSet tiles = TileSet.fromGrid("terrain", picture(map, tileWidth, tileHeight), tileWidth, tileHeight);
            map.addTileSet(tiles);
            TileLayer ground = new TileLayer("ground", map.getWidth(), map.getHeight());
            for (int y = 0; y < map.getHeight(); y++) {
                for (int x = 0; x < map.getWidth(); x++) {
                    ground.setTile(x, y, tiles.getTile(terrain(x, y, map.getWidth(), map.getHeight())));
                }
            }
            map.addLayer(ground);
            map.setSmooth(true);
            return new Layout(name, map, tiles);
        }

        /** A lake in the middle with a beach around it, forest at one corner and rocks scattered about. */
        private static int terrain(int x, int y, int width, int height) {
            double dx = (x + 0.5) / width - 0.5, dy = (y + 0.5) / height - 0.5;
            double lake = Math.hypot(dx * 1.1, dy * 1.1);
            if (lake < 0.17) return 1;
            if (lake < 0.25) return 2;
            double corner = Math.hypot((x + 0.5) / width - 0.85, (y + 0.5) / height - 0.2);
            if (corner < 0.2 + 0.05 * Math.sin(x * 1.7 + y)) return 4;
            if ((x * 7 + y * 13) % 11 == 0) return 3;
            return 0;
        }

        /** One row of tiles, a diamond or hexagon (or square) of each kind the size of a cell, with a darker edge. */
        private static Texture picture(TileMap map, int tileWidth, int tileHeight) {
            try (Bitmap bitmap = new Bitmap()) {
                bitmap.allocN32Pixels(tileWidth * KINDS.length, tileHeight, false);
                Canvas canvas = new Canvas(bitmap, new org.jetbrains.skia.SurfaceProps());
                for (int i = 0; i < KINDS.length; i++) {
                    float x = i * tileWidth;
                    Path path = cellPath(map, x, tileWidth, tileHeight);
                    try (Paint fill = new Paint(); Paint edge = new Paint()) {
                        fill.setAntiAlias(true);
                        fill.setColor(COLORS[i]);
                        canvas.drawPath(path, fill);
                        edge.setAntiAlias(true);
                        edge.setMode(PaintMode.STROKE);
                        edge.setStrokeWidth(2f);
                        edge.setColor(darker(COLORS[i]));
                        canvas.drawPath(path, edge);
                        if (i == 3 || i == 4) { // a rock or a tree, so those two kinds read as something standing there
                            fill.setColor(darker(COLORS[i]));
                            canvas.drawCircle(x + tileWidth / 2f, tileHeight / 2f, Math.min(tileWidth, tileHeight) * 0.22f, fill);
                        }
                    }
                    path.close();
                }
                canvas.close();
                return new Texture(Image.Companion.makeFromBitmap(bitmap));
            }
        }

        private static Path cellPath(TileMap map, float x, int w, int h) {
            float[] p;
            switch (map.getOrientation()) {
                case ISOMETRIC, STAGGERED -> p = new float[]{w / 2f, 0, w, h / 2f, w / 2f, h, 0, h / 2f};
                case HEXAGONAL -> {
                    float side = map.getHexSideLength();
                    if (map.getStaggerAxis() == TileMap.StaggerAxis.Y) {
                        float rise = (h - side) / 2f;
                        p = new float[]{w / 2f, 0, w, rise, w, rise + side, w / 2f, h, 0, rise + side, 0, rise};
                    } else {
                        float run = (w - side) / 2f;
                        p = new float[]{run, 0, run + side, 0, w, h / 2f, run + side, h, run, h, 0, h / 2f};
                    }
                }
                default -> p = new float[]{0, 0, w, 0, w, h, 0, h};
            }
            PathBuilder path = new PathBuilder();
            for (int i = 0; i < p.length; i += 2) {
                if (i == 0) path.moveTo(x + p[i], p[i + 1]);
                else path.lineTo(x + p[i], p[i + 1]);
            }
            path.closePath();
            return path.detach();
        }

        private static int darker(int argb) {
            int r = (argb >> 16 & 0xFF) * 3 / 5, g = (argb >> 8 & 0xFF) * 3 / 5, b = (argb & 0xFF) * 3 / 5;
            return 0xFF000000 | r << 16 | g << 8 | b;
        }
    }
}
