package me.piitex.engine.tilemap;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.graphics.Texture;
import me.piitex.engine.math.Rect;
import me.piitex.engine.math.Shape;
import me.piitex.engine.ui.color.Color;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A level made of tiles, usually drawn in the <a href="https://www.mapeditor.org">Tiled</a> map editor and loaded
 * from its {@code .tmx} or {@code .tmj} file: layers of tiles, layers of placed objects, pictures, and the custom
 * properties set on all of them.
 * <pre>{@code
 * TileMap map = TileMap.load("assets/levels/forest.tmx");
 * map.setSmooth(false);                                      // pixel art
 * List<Shape> walls = map.getCollisionShapes();              // from Tiled's tile collision editor
 * MapObject start = map.findObject("player_start");
 * camera.setBounds(map.getBounds());
 *
 * // Scene.update
 * map.update(delta);                                         // animated tiles
 *
 * // Scene.render
 * camera.begin(renderer);
 * map.draw(renderer, camera.getVisibleBounds());             // only what is in view
 * camera.end(renderer);
 * }</pre>
 * <b>Units</b> are the map's pixels: a tile covers {@link #getTileWidth()} by {@link #getTileHeight()} world units,
 * and objects are where Tiled shows them. Zoom a {@link me.piitex.engine.graphics.Camera} to show them bigger.
 * <p>
 * <b>Drawing</b> is layer by layer, bottom first, only what is in view, one draw call per layer for tiles from one
 * image. To draw the game's sprites between layers, such as characters above the ground but under tree tops,
 * draw the layers yourself: see {@link #getLayers()}.
 * <p>
 * Only orthogonal maps (square or rectangular grids seen from above or the side) are read. Loading can run on any
 * thread, such as in a {@link me.piitex.engine.scheduler.Loader} step; after that, use a map on the render thread.
 */
public final class TileMap {
    private final int tileWidth, tileHeight;
    private int width, height;
    private final List<MapLayer> layers = new ArrayList<>();
    private final List<TileSet> tileSets = new ArrayList<>();
    private final QuadBuffer quads = new QuadBuffer();
    private boolean infinite;
    private Color backgroundColor;
    private Properties properties = Properties.EMPTY;
    private String type = "";
    private float parallaxOriginX, parallaxOriginY;
    private boolean renderRight = true, renderDown = true;
    private double time;
    private Map<Integer, MapObject> objectsById;
    private Path source;

    /**
     * An empty map of {@code width} by {@code height} tiles, each {@code tileWidth} by {@code tileHeight} pixels,
     * for building a level in code: add {@link TileLayer}s and fill them with tiles from a {@link TileSet}.
     */
    public TileMap(int width, int height, int tileWidth, int tileHeight) {
        if (width < 0 || height < 0) throw new IllegalArgumentException("size must not be negative: " + width + "x" + height);
        if (tileWidth <= 0 || tileHeight <= 0) throw new IllegalArgumentException("tiles must not be empty: " + tileWidth + "x" + tileHeight);
        this.width = width;
        this.height = height;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
    }

    public static TileMap load(String path) {
        return load(Path.of(path));
    }

    public static TileMap load(File file) {
        return load(file.toPath());
    }

    /**
     * Reads a Tiled map, in either of Tiled's formats: TMX ({@code .tmx}, XML) or JSON ({@code .tmj} or
     * {@code .json}), along with the tile sets, images and templates it uses, which are looked for relative to
     * the file that names them. Works with paths inside a zip file system too, such as a mounted asset pack.
     *
     * @throws java.io.UncheckedIOException if a file can't be read
     * @throws IllegalArgumentException     if a file isn't one Tiled writes, or uses something not supported, naming
     *                                      the file and what is wrong
     */
    public static TileMap load(Path path) {
        return new TiledReader().readMap(path);
    }

    /**
     * As {@link #load(Path)}, with the {@link TiledProject} the map belongs to, whose custom class definitions supply the
     * members the map leaves at their default. Without it those members read as not set.
     */
    public static TileMap load(Path path, TiledProject project) {
        return new TiledReader(project).readMap(path);
    }

    public static TileMap load(String path, TiledProject project) {
        return load(Path.of(path), project);
    }

    public static TileMap load(File file, TiledProject project) {
        return load(file.toPath(), project);
    }

    // ---- Size

    /**
     * The map's width in tiles. For an infinite map, the width of the area its layers cover.
     */
    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * The width of a cell of the grid, in pixels.
     */
    public int getTileWidth() {
        return tileWidth;
    }

    public int getTileHeight() {
        return tileHeight;
    }

    /**
     * Whether the map was made as an infinite map in Tiled, whose layers can reach any way from the top-left
     * corner, including to negative cells.
     */
    public boolean isInfinite() {
        return infinite;
    }

    /**
     * The area the map covers, in pixels: from (0, 0) to its width and height in tiles times the tile size, or for
     * an infinite map, around every cell its tile layers cover. For {@link me.piitex.engine.graphics.Camera#setBounds}.
     */
    public Rect getBounds() {
        if (!infinite) return new Rect(0f, 0f, width * tileWidth, height * tileHeight);
        Rect bounds = null;
        for (MapLayer layer : getAllLayers()) {
            if (!(layer instanceof TileLayer tiles) || tiles.getWidth() == 0 || tiles.getHeight() == 0) continue;
            Rect covered = new Rect(tiles.getStartX() * (float) tileWidth, tiles.getStartY() * (float) tileHeight,
                    tiles.getWidth() * (float) tileWidth, tiles.getHeight() * (float) tileHeight);
            bounds = bounds == null ? covered : bounds.union(covered);
        }
        return bounds == null ? new Rect(0f, 0f, 0f, 0f) : bounds;
    }

    /**
     * The column of cells a world x falls in, ignoring layer offsets.
     */
    public int toTileX(float worldX) {
        return (int) Math.floor(worldX / tileWidth);
    }

    public int toTileY(float worldY) {
        return (int) Math.floor(worldY / tileHeight);
    }

    /**
     * The rectangle cell ({@code x}, {@code y}) covers, in pixels, ignoring layer offsets.
     */
    public Rect getCellBounds(int x, int y) {
        return new Rect(x * (float) tileWidth, y * (float) tileHeight, tileWidth, tileHeight);
    }

    // ---- Layers

    /**
     * The layers at the top of the map, bottom first, as they are drawn; groups hold more. To draw the game's own
     * sprites between layers, draw them one by one:
     * <pre>{@code
     * Rect view = camera.getVisibleBounds();
     * for (MapLayer layer : map.getLayers()) {
     *     layer.draw(renderer, view);
     *     if (layer.getName().equals("ground")) batch.draw(renderer, view); // characters stand on the ground
     * }
     * }</pre>
     */
    public List<MapLayer> getLayers() {
        return Collections.unmodifiableList(layers);
    }

    /**
     * Every layer, groups and what is in them included, in drawing order: each group comes just before its layers.
     */
    public List<MapLayer> getAllLayers() {
        List<MapLayer> all = new ArrayList<>();
        collect(layers, all);
        return all;
    }

    private static void collect(List<MapLayer> from, List<MapLayer> into) {
        for (MapLayer layer : from) {
            into.add(layer);
            if (layer instanceof GroupLayer group) collect(group.getLayers(), into);
        }
    }

    /**
     * The first layer named {@code name}, searching inside groups too. Throws, naming it, if there is none.
     */
    public MapLayer getLayer(String name) {
        MapLayer layer = findLayer(name);
        if (layer == null) throw new IllegalArgumentException("No layer named '" + name + "' in the map");
        return layer;
    }

    /**
     * As {@link #getLayer}, but null if there is no such layer.
     */
    public MapLayer findLayer(String name) {
        for (MapLayer layer : getAllLayers()) if (layer.getName().equals(name)) return layer;
        return null;
    }

    /**
     * The first tile layer named {@code name}. Throws if there is none, or if the layer of that name holds
     * something else.
     */
    public TileLayer getTileLayer(String name) {
        return typed(name, TileLayer.class, "a tile layer");
    }

    /**
     * The first object layer named {@code name}. Throws if there is none, or if the layer of that name holds
     * something else.
     */
    public ObjectLayer getObjectLayer(String name) {
        return typed(name, ObjectLayer.class, "an object layer");
    }

    private <T extends MapLayer> T typed(String name, Class<T> kind, String what) {
        MapLayer layer = getLayer(name);
        if (!kind.isInstance(layer)) {
            throw new IllegalArgumentException("Layer '" + name + "' is " + describe(layer) + ", not " + what);
        }
        return kind.cast(layer);
    }

    private static String describe(MapLayer layer) {
        return switch (layer) {
            case TileLayer ignored -> "a tile layer";
            case ObjectLayer ignored -> "an object layer";
            case ImageLayer ignored -> "an image layer";
            case GroupLayer ignored -> "a group";
        };
    }

    /**
     * Adds {@code layer} on top of the others.
     *
     * @throws IllegalArgumentException if the layer is already in a map or group
     */
    public void addLayer(MapLayer layer) {
        if (layer.map != null || layer.parent != null) throw new IllegalArgumentException(layer + " is already in a map or group");
        layers.add(layer);
        layer.setMap(this);
        layersChanged();
    }

    /**
     * Takes a layer at the top of the map out of it.
     */
    public boolean removeLayer(MapLayer layer) {
        if (!layers.remove(layer)) return false;
        layer.setMap(null);
        layersChanged();
        return true;
    }

    void layersChanged() {
        objectsById = null;
    }

    // ---- Tile sets

    /**
     * The tile sets the map uses, in the order Tiled lists them.
     */
    public List<TileSet> getTileSets() {
        return Collections.unmodifiableList(tileSets);
    }

    /**
     * The tile set named {@code name}. Throws, naming it, if there is none.
     */
    public TileSet getTileSet(String name) {
        for (TileSet set : tileSets) if (set.getName().equals(name)) return set;
        throw new IllegalArgumentException("No tile set named '" + name + "' in the map");
    }

    /**
     * Lists {@code set} with the map's tile sets, so {@link #setSmooth} reaches it. Tiles from any tile set can be
     * placed without it.
     */
    public void addTileSet(TileSet set) {
        if (!tileSets.contains(set)) tileSets.add(set);
    }

    // ---- Objects

    /**
     * Every object on every object layer, in drawing order.
     */
    public List<MapObject> getObjects() {
        List<MapObject> all = new ArrayList<>();
        for (MapLayer layer : getAllLayers()) if (layer instanceof ObjectLayer objects) all.addAll(objects.getObjects());
        return all;
    }

    /**
     * The object Tiled numbered {@code id}, or null if there is none.
     */
    public MapObject getObject(int id) {
        if (objectsById == null) {
            Map<Integer, MapObject> byId = new HashMap<>();
            for (MapObject object : getObjects()) byId.putIfAbsent(object.getId(), object);
            objectsById = byId;
        }
        return objectsById.get(id);
    }

    /**
     * The first object named {@code name} on any object layer, or null if there is none.
     */
    public MapObject findObject(String name) {
        for (MapObject object : getObjects()) if (object.getName().equals(name)) return object;
        return null;
    }

    /**
     * Every object of the class {@code type} on any object layer.
     */
    public List<MapObject> getObjectsOfType(String type) {
        List<MapObject> found = new ArrayList<>();
        for (MapObject object : getObjects()) if (object.getType().equals(type)) found.add(object);
        return found;
    }

    // ---- Collision

    /**
     * The collision shapes of every tile layer: the shapes drawn on tiles in Tiled's collision editor, placed
     * wherever those tiles are. See {@link TileLayer#getCollisionShapes()}. Object layers of walls aren't included,
     * since which layers those are is up to the game: add their {@link ObjectLayer#getShapes()}.
     */
    public List<Shape> getCollisionShapes() {
        List<Shape> shapes = new ArrayList<>();
        for (MapLayer layer : getAllLayers()) if (layer instanceof TileLayer tiles) shapes.addAll(tiles.getCollisionShapes());
        return shapes;
    }

    // ---- Time and drawing

    /**
     * Moves animated tiles on by {@code delta} seconds. Call it once a frame, or once a fixed step.
     */
    public void update(float delta) {
        time += delta;
    }

    /**
     * Seconds of animation so far, which picks the frame of every animated tile.
     */
    public double getTime() {
        return time;
    }

    public void setTime(double time) {
        this.time = time;
    }

    /**
     * Draws the map as Tiled shows it, in world coordinates: between a camera's {@code begin} and {@code end}.
     * Fills {@code view} with the {@link #getBackgroundColor() background color}, if the map has one, then draws the
     * visible layers bottom first, only what overlaps {@code view}. Pass the camera's {@code getVisibleBounds()}:
     * its middle is also where parallax is measured from.
     */
    public void draw(Renderer renderer, Rect view) {
        if (backgroundColor != null && view != null) {
            renderer.drawQuad(view.x(), view.y(), view.width(), view.height(), backgroundColor, 0f);
        }
        for (MapLayer layer : layers) layer.renderBlended(renderer, view, MapLayer.DrawState.ROOT);
    }

    /**
     * Draws the whole map, without a background or parallax: into an image for a minimap, say.
     */
    public void draw(Renderer renderer) {
        draw(renderer, null);
    }

    /**
     * Sets how every image the map uses is sampled when drawn scaled: false keeps hard pixel edges, as pixel art
     * wants; true, the default, blends neighbouring pixels. Images are shared, so this also changes other maps
     * and sprites drawn from the same textures.
     */
    public void setSmooth(boolean smooth) {
        Map<Texture, Boolean> seen = new IdentityHashMap<>();
        for (TileSet set : tileSets) {
            if (set.getTexture() != null) seen.put(set.getTexture(), true);
            for (Tile tile : set.getTiles()) {
                seen.put(tile.getRegion().getTexture(), true);
                if (tile.getAnimation() != null) {
                    tile.getAnimation().getFrames().forEach(frame -> seen.put(frame.region().getTexture(), true));
                }
            }
        }
        for (MapLayer layer : getAllLayers()) {
            if (layer instanceof ImageLayer image && image.getTexture() != null) seen.put(image.getTexture(), true);
            if (layer instanceof TileLayer tiles) {
                for (int y = tiles.getStartY(); y < tiles.getStartY() + tiles.getHeight(); y++) {
                    for (int x = tiles.getStartX(); x < tiles.getStartX() + tiles.getWidth(); x++) {
                        Tile tile = tiles.getTile(x, y);
                        if (tile != null) seen.put(tile.getRegion().getTexture(), true);
                    }
                }
            }
        }
        for (Texture texture : seen.keySet()) texture.setSmooth(smooth);
    }

    QuadBuffer quads() {
        return quads;
    }

    // ---- What Tiled set on the map

    /**
     * The background color set in Tiled's map properties, or null if none is set.
     */
    public Color getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(Color color) {
        this.backgroundColor = color;
    }

    /**
     * The map's class, or "" if none is set.
     */
    public String getType() {
        return type;
    }

    public Properties getProperties() {
        return properties;
    }

    /**
     * The point parallax layers are measured from: when the middle of the view is here, every layer sits where
     * Tiled shows it. (0, 0), the map's top-left corner, unless set in the map's properties.
     */
    public float getParallaxOriginX() {
        return parallaxOriginX;
    }

    public float getParallaxOriginY() {
        return parallaxOriginY;
    }

    public void setParallaxOrigin(float x, float y) {
        this.parallaxOriginX = x;
        this.parallaxOriginY = y;
    }

    /**
     * The file the map was loaded from, or null for a map built in code.
     */
    public Path getSource() {
        return source;
    }

    boolean isRenderRight() {
        return renderRight;
    }

    boolean isRenderDown() {
        return renderDown;
    }

    void setRenderOrder(boolean right, boolean down) {
        this.renderRight = right;
        this.renderDown = down;
    }

    void setSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    void setInfinite(boolean infinite) {
        this.infinite = infinite;
    }

    void setType(String type) {
        this.type = type == null ? "" : type;
    }

    void setProperties(Properties properties) {
        this.properties = properties;
    }

    void setSource(Path source) {
        this.source = source;
    }

    @Override
    public String toString() {
        return "TileMap[" + width + "x" + height + " tiles of " + tileWidth + "x" + tileHeight
                + (source == null ? "" : ", " + source.getFileName()) + "]";
    }
}
