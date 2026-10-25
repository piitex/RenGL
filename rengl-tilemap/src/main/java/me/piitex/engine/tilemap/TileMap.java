package me.piitex.engine.tilemap;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.graphics.Texture;
import me.piitex.engine.math.Polygon;
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
 * <b>Orientation.</b> Tiled's four kinds of grid are read: {@link Orientation#ORTHOGONAL} (squares seen from above or the side),
 * {@link Orientation#ISOMETRIC} (diamonds), {@link Orientation#STAGGERED} (isometric with every other row or column
 * shifted, so the map is not a diamond) and {@link Orientation#HEXAGONAL}. Cells are not at {@code x * tileWidth} on the
 * three that are not orthogonal: ask the map where one is ({@link #getCellBounds}, {@link #getCellShape}) and which cell
 * a point is in ({@link #toCell}), for picking what the mouse is over.
 * <p>
 * Loading can run on any thread, such as in a {@link me.piitex.engine.scheduler.Loader} step; after that, use a map on the
 * render thread.
 */
public final class TileMap {
    /**
     * How the grid of cells is laid out.
     */
    public enum Orientation {
        /**
         * Rows and columns of rectangles.
         */
        ORTHOGONAL,
        /**
         * A diamond of cells: cell (0, 0) is at the top, x runs down and to the right, y down and to the left.
         */
        ISOMETRIC,
        /**
         * Diamond cells in rows (or columns) of a rectangular map, every other one shifted half a cell.
         */
        STAGGERED,
        /**
         * Hexagons in rows (or columns) of a rectangular map, every other one shifted.
         */
        HEXAGONAL
    }

    /**
     * Which direction the cells of a staggered or hexagonal map are shifted in.
     */
    public enum StaggerAxis {
        /**
         * Columns are shifted down: the hexagons have a flat top.
         */
        X,
        /**
         * Rows are shifted right: the hexagons have a pointed top.
         */
        Y
    }

    private final int tileWidth, tileHeight;
    private final Orientation orientation;
    private final StaggerAxis staggerAxis;
    private final boolean staggerOdd;
    private final int hexSideLength;
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
        this(width, height, tileWidth, tileHeight, Orientation.ORTHOGONAL, StaggerAxis.Y, true, 0);
    }

    private TileMap(int width, int height, int tileWidth, int tileHeight, Orientation orientation, StaggerAxis staggerAxis,
                    boolean staggerOdd, int hexSideLength) {
        if (width < 0 || height < 0) throw new IllegalArgumentException("size must not be negative: " + width + "x" + height);
        if (tileWidth <= 0 || tileHeight <= 0) throw new IllegalArgumentException("tiles must not be empty: " + tileWidth + "x" + tileHeight);
        if (orientation == Orientation.HEXAGONAL) {
            int across = staggerAxis == StaggerAxis.X ? tileWidth : tileHeight;
            if (hexSideLength <= 0 || hexSideLength >= across) {
                throw new IllegalArgumentException("a hexagon's side length must be more than 0 and less than the tile's "
                        + (staggerAxis == StaggerAxis.X ? "width" : "height") + " of " + across + ": " + hexSideLength);
            }
        }
        this.width = width;
        this.height = height;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.orientation = orientation;
        this.staggerAxis = staggerAxis == null ? StaggerAxis.Y : staggerAxis;
        this.staggerOdd = staggerOdd;
        this.hexSideLength = orientation == Orientation.HEXAGONAL ? hexSideLength : 0;
    }

    /**
     * An empty isometric map, a diamond of {@code width} by {@code height} cells each {@code tileWidth} wide and
     * {@code tileHeight} high (usually twice as wide as high: 64 by 32).
     */
    public static TileMap isometric(int width, int height, int tileWidth, int tileHeight) {
        return new TileMap(width, height, tileWidth, tileHeight, Orientation.ISOMETRIC, StaggerAxis.Y, true, 0);
    }

    /**
     * An empty staggered isometric map: diamond cells in {@code width} by {@code height} rows and columns, every other row
     * (axis {@code Y}) or column (axis {@code X}) shifted half a cell. {@code staggerOdd} says whether the odd ones are
     * shifted or the even ones.
     */
    public static TileMap staggered(int width, int height, int tileWidth, int tileHeight, StaggerAxis axis, boolean staggerOdd) {
        return new TileMap(width, height, tileWidth, tileHeight, Orientation.STAGGERED, axis, staggerOdd, 0);
    }

    /**
     * An empty hexagonal map. The hexagons are {@code tileWidth} by {@code tileHeight}, with a side of
     * {@code hexSideLength} along the axis they are shifted in (Tiled's "Tile Side Length"): for axis {@code Y} the left and
     * right sides are vertical, for {@code X} the top and bottom are horizontal.
     */
    public static TileMap hexagonal(int width, int height, int tileWidth, int tileHeight, int hexSideLength, StaggerAxis axis,
                                    boolean staggerOdd) {
        return new TileMap(width, height, tileWidth, tileHeight, Orientation.HEXAGONAL, axis, staggerOdd, hexSideLength);
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
        if (!infinite) {
            return switch (orientation) {
                case ORTHOGONAL -> new Rect(0f, 0f, width * tileWidth, height * tileHeight);
                case ISOMETRIC -> new Rect(0f, 0f, (width + height) * tileWidth / 2f, (width + height) * tileHeight / 2f);
                case STAGGERED -> staggerAxis == StaggerAxis.X
                        ? new Rect(0f, 0f, width * tileWidth / 2f + tileWidth / 2f, height * (float) tileHeight + tileHeight / 2f)
                        : new Rect(0f, 0f, width * (float) tileWidth + tileWidth / 2f, height * tileHeight / 2f + tileHeight / 2f);
                case HEXAGONAL -> hexBounds();
            };
        }
        Rect bounds = null;
        for (MapLayer layer : getAllLayers()) {
            if (!(layer instanceof TileLayer tiles) || tiles.getWidth() == 0 || tiles.getHeight() == 0) continue;
            Rect covered = new Rect(tiles.getStartX() * (float) tileWidth, tiles.getStartY() * (float) tileHeight,
                    tiles.getWidth() * (float) tileWidth, tiles.getHeight() * (float) tileHeight);
            bounds = bounds == null ? covered : bounds.union(covered);
        }
        return bounds == null ? new Rect(0f, 0f, 0f, 0f) : bounds;
    }

    public Orientation getOrientation() {
        return orientation;
    }

    /**
     * Which way a staggered or hexagonal map's cells are shifted; {@code Y} (rows) for the other orientations, where it means nothing.
     */
    public StaggerAxis getStaggerAxis() {
        return staggerAxis;
    }

    /**
     * Whether the odd rows or columns (counting from 0) of a staggered or hexagonal map are the shifted ones, rather than the even.
     */
    public boolean isStaggerOdd() {
        return staggerOdd;
    }

    /**
     * The length of a hexagon's side in the direction the map is staggered, in pixels, or 0 for the other orientations.
     */
    public int getHexSideLength() {
        return hexSideLength;
    }

    private Rect hexBounds() {
        float side = hexSideLength;
        if (staggerAxis == StaggerAxis.X) {
            float columnWidth = (tileWidth - side) / 2f + side, rowHeight = tileHeight / 2f;
            float h = height * (float) tileHeight;
            if (width > 1) h += rowHeight;
            return new Rect(0f, 0f, width * columnWidth + (tileWidth - side) / 2f, h);
        }
        float columnWidth = tileWidth / 2f, rowHeight = (tileHeight - side) / 2f + side;
        float w = width * (float) tileWidth;
        if (height > 1) w += columnWidth;
        return new Rect(0f, 0f, w, height * rowHeight + (tileHeight - side) / 2f);
    }

    private boolean shifted(int index) {
        return ((index & 1) == 1) == staggerOdd;
    }

    /**
     * Puts the top-left corner of cell ({@code x}, {@code y})'s box, which is {@code tileWidth} by {@code tileHeight} and
     * holds the diamond or hexagon, in {@code out[0]} and {@code out[1]}, ignoring layer offsets.
     */
    void cellOrigin(int x, int y, float[] out) {
        float tw = tileWidth, th = tileHeight;
        switch (orientation) {
            case ORTHOGONAL -> {
                out[0] = x * tw;
                out[1] = y * th;
            }
            case ISOMETRIC -> {
                out[0] = (x - y) * tw / 2f + height * tw / 2f - tw / 2f;
                out[1] = (x + y) * th / 2f;
            }
            case STAGGERED -> {
                if (staggerAxis == StaggerAxis.X) {
                    out[0] = x * tw / 2f;
                    out[1] = y * th + (shifted(x) ? th / 2f : 0f);
                } else {
                    out[0] = x * tw + (shifted(y) ? tw / 2f : 0f);
                    out[1] = y * th / 2f;
                }
            }
            case HEXAGONAL -> {
                float side = hexSideLength;
                if (staggerAxis == StaggerAxis.X) {
                    float columnWidth = (tw - side) / 2f + side, rowHeight = th / 2f;
                    out[0] = x * columnWidth;
                    out[1] = y * th + (shifted(x) ? rowHeight : 0f);
                } else {
                    float columnWidth = tw / 2f, rowHeight = (th - side) / 2f + side;
                    out[0] = x * tw + (shifted(y) ? columnWidth : 0f);
                    out[1] = y * rowHeight;
                }
            }
        }
    }

    /**
     * Where a point of an isometric map's object layer is drawn. Tiled saves such objects on a square grid whose unit is
     * {@code tileHeight} and draws them as the diamonds of the map: this is that change, as {x, y}.
     */
    float[] isometricToScreen(float x, float y) {
        float tileX = x / tileHeight, tileY = y / tileHeight;
        return new float[]{(tileX - tileY) * tileWidth / 2f + height * tileWidth / 2f, (tileX + tileY) * tileHeight / 2f};
    }

    /**
     * The column of cells a world x falls in, ignoring layer offsets. Only for an {@linkplain Orientation#ORTHOGONAL
     * orthogonal} map, where a column is a straight strip; elsewhere both numbers depend on both coordinates, so use
     * {@link #toCell}.
     *
     * @throws UnsupportedOperationException on any other orientation
     */
    public int toTileX(float worldX) {
        requireOrthogonal("toTileX");
        return (int) Math.floor(worldX / tileWidth);
    }

    public int toTileY(float worldY) {
        requireOrthogonal("toTileY");
        return (int) Math.floor(worldY / tileHeight);
    }

    private void requireOrthogonal(String what) {
        if (orientation != Orientation.ORTHOGONAL) {
            throw new UnsupportedOperationException(what + " is for orthogonal maps; this one is " + orientation + ", so use toCell(x, y)");
        }
    }

    /**
     * A cell of the grid.
     */
    public record Cell(int x, int y) {
    }

    /**
     * The cell the world point ({@code worldX}, {@code worldY}) is in, ignoring layer offsets: what a mouse is over. Works on every
     * orientation, and gives cells outside the map for points outside it, as the orthogonal arithmetic always has.
     */
    public Cell toCell(float worldX, float worldY) {
        switch (orientation) {
            case ORTHOGONAL -> {
                return new Cell((int) Math.floor(worldX / tileWidth), (int) Math.floor(worldY / tileHeight));
            }
            case ISOMETRIC -> {
                double u = (worldX - height * tileWidth / 2.0) / (tileWidth / 2.0), v = worldY / (tileHeight / 2.0);
                return new Cell((int) Math.floor((u + v) / 2), (int) Math.floor((v - u) / 2));
            }
            default -> {
                return findStaggered(worldX, worldY);
            }
        }
    }

    /**
     * A staggered or hexagonal cell holds no simple arithmetic, so look at the few cells around where a plain grid would put the
     * point and take the one whose outline holds it.
     */
    private Cell findStaggered(float wx, float wy) {
        float side = hexSideLength, colStep, rowStep;
        if (orientation == Orientation.STAGGERED) {
            colStep = staggerAxis == StaggerAxis.X ? tileWidth / 2f : tileWidth;
            rowStep = staggerAxis == StaggerAxis.X ? tileHeight : tileHeight / 2f;
        } else if (staggerAxis == StaggerAxis.X) {
            colStep = (tileWidth - side) / 2f + side;
            rowStep = tileHeight;
        } else {
            colStep = tileWidth;
            rowStep = (tileHeight - side) / 2f + side;
        }
        int cx = (int) Math.floor(wx / colStep), cy = (int) Math.floor(wy / rowStep);
        float[] origin = new float[2];
        float[] outline = new float[12];
        Cell nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int y = cy - 2; y <= cy + 2; y++) {
            for (int x = cx - 2; x <= cx + 2; x++) {
                cellOrigin(x, y, origin);
                int n = outline(origin[0], origin[1], outline);
                if (contains(outline, n, wx, wy)) return new Cell(x, y);
                double dx = origin[0] + tileWidth / 2.0 - wx, dy = origin[1] + tileHeight / 2.0 - wy;
                double distance = dx * dx + dy * dy;
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = new Cell(x, y);
                }
            }
        }
        return nearest; // only for a point on a seam that rounding left out
    }

    /**
     * Fills {@code out} with the corners of the cell whose box starts at ({@code left}, {@code top}) and returns how many points (x, y pairs)
     * it has.
     */
    private int outline(float left, float top, float[] out) {
        float tw = tileWidth, th = tileHeight;
        float[] p;
        switch (orientation) {
            case ORTHOGONAL -> p = new float[]{0, 0, tw, 0, tw, th, 0, th};
            case ISOMETRIC, STAGGERED -> p = new float[]{tw / 2f, 0, tw, th / 2f, tw / 2f, th, 0, th / 2f};
            default -> {
                float side = hexSideLength;
                if (staggerAxis == StaggerAxis.X) {
                    float o = (tw - side) / 2f;
                    p = new float[]{o, 0, o + side, 0, tw, th / 2f, o + side, th, o, th, 0, th / 2f};
                } else {
                    float o = (th - side) / 2f;
                    p = new float[]{tw / 2f, 0, tw, o, tw, o + side, tw / 2f, th, 0, o + side, 0, o};
                }
            }
        }
        for (int i = 0; i < p.length; i += 2) {
            out[i] = left + p[i];
            out[i + 1] = top + p[i + 1];
        }
        return p.length / 2;
    }

    /** Whether a point is inside (or on the edge of) a convex outline of {@code n} points. */
    private static boolean contains(float[] p, int n, float x, float y) {
        boolean positive = false, negative = false;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float cross = (p[j * 2] - p[i * 2]) * (y - p[i * 2 + 1]) - (p[j * 2 + 1] - p[i * 2 + 1]) * (x - p[i * 2]);
            if (cross > 1e-4f) positive = true;
            else if (cross < -1e-4f) negative = true;
            if (positive && negative) return false;
        }
        return true;
    }

    /**
     * The rectangle that holds cell ({@code x}, {@code y}) in pixels, ignoring layer offsets: the cell itself on an orthogonal
     * map, the box around the diamond or hexagon on the others. The tile {@code tileWidth} by {@code tileHeight} sits in it.
     */
    public Rect getCellBounds(int x, int y) {
        float[] origin = new float[2];
        cellOrigin(x, y, origin);
        return new Rect(origin[0], origin[1], tileWidth, tileHeight);
    }

    /**
     * The outline of cell ({@code x}, {@code y}) in pixels, ignoring layer offsets: a {@link Rect} on an orthogonal map, a
     * convex {@link Polygon} (a diamond or a hexagon) on the others. For drawing the cell the mouse is over, or as a
     * collision shape.
     */
    public Shape getCellShape(int x, int y) {
        float[] origin = new float[2];
        cellOrigin(x, y, origin);
        if (orientation == Orientation.ORTHOGONAL) return new Rect(origin[0], origin[1], tileWidth, tileHeight);
        float[] points = new float[12];
        int n = outline(origin[0], origin[1], points);
        return new Polygon(java.util.Arrays.copyOf(points, n * 2));
    }

    /**
     * The middle of cell ({@code x}, {@code y}) in pixels, ignoring layer offsets: where to put something that stands on it.
     */
    public float[] getCellCenter(int x, int y) {
        float[] origin = new float[2];
        cellOrigin(x, y, origin);
        return new float[]{origin[0] + tileWidth / 2f, origin[1] + tileHeight / 2f};
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
