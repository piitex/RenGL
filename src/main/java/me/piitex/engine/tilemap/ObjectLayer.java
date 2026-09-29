package me.piitex.engine.tilemap;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.graphics.Sprite;
import me.piitex.engine.math.Rect;
import me.piitex.engine.math.Shape;
import me.piitex.engine.ui.color.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A layer of {@link MapObject}s: things placed freely rather than on the grid, such as spawn points, triggers and
 * walls that don't follow the tiles.
 * <pre>{@code
 * ObjectLayer entities = map.getObjectLayer("entities");
 * MapObject start = entities.find("player_start");
 * for (MapObject spawn : entities.getObjectsOfType("EnemySpawn")) spawnEnemy(spawn.getX(), spawn.getY());
 * List<Shape> walls = map.getObjectLayer("collision").getShapes();
 * }</pre>
 * Drawing the layer draws its visible tile objects. Other kinds are markers for the game and aren't drawn.
 */
public final class ObjectLayer extends MapLayer {
    private static final Comparator<MapObject> TOP_DOWN = Comparator.comparingDouble(MapObject::getY);

    private final List<MapObject> objects;
    private final Color color;
    private final boolean topDown;
    private final List<MapObject> drawOrder = new ArrayList<>();
    private final Sprite sprite = new Sprite();

    ObjectLayer(String name, List<MapObject> objects, Color color, boolean topDown) {
        super(name);
        this.objects = Collections.unmodifiableList(new ArrayList<>(objects));
        this.color = color;
        this.topDown = topDown;
        for (MapObject object : objects) object.layer = this;
    }

    /**
     * Every object on the layer, in the order Tiled lists them.
     */
    public List<MapObject> getObjects() {
        return objects;
    }

    /**
     * The first object named {@code name}, or null if there is none.
     */
    public MapObject find(String name) {
        for (MapObject object : objects) if (object.getName().equals(name)) return object;
        return null;
    }

    /**
     * Every object of the class {@code type}, in the order Tiled lists them.
     */
    public List<MapObject> getObjectsOfType(String type) {
        List<MapObject> found = new ArrayList<>();
        for (MapObject object : objects) if (object.getType().equals(type)) found.add(object);
        return found;
    }

    /**
     * The shapes of every object on the layer, for a layer of walls drawn with Tiled's object tools. See
     * {@link MapObject#getShapes()}. Hidden objects count too, since collision layers are often hidden in the
     * editor.
     */
    public List<Shape> getShapes() {
        List<Shape> shapes = new ArrayList<>();
        for (MapObject object : objects) shapes.addAll(object.getShapes());
        return shapes;
    }

    /**
     * The color Tiled shows the layer's objects in, or null if it isn't set.
     */
    public Color getColor() {
        return color;
    }

    @Override
    void render(Renderer renderer, Rect view, DrawState parent) {
        DrawState state = parent.with(this);
        if (state == null) return;
        TileMap map = requireMap();
        drawOrder.clear();
        for (MapObject object : objects) {
            if (object.getTile() != null && object.isVisible() && object.getOpacity() > 0f) drawOrder.add(object);
        }
        if (drawOrder.isEmpty()) return;
        if (topDown) drawOrder.sort(TOP_DOWN);
        float shiftX = state.shiftX(map, view), shiftY = state.shiftY(map, view);
        double time = map.getTime();
        for (MapObject object : drawOrder) {
            object.configure(sprite, object.getTile().getRegionAt(time), shiftX, shiftY);
            if (view != null && !sprite.getBounds().intersects(view)) continue;
            sprite.setColor(state.color(object.getOpacity()));
            sprite.draw(renderer);
        }
    }
}
