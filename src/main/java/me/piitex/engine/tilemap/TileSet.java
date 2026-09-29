package me.piitex.engine.tilemap;

import me.piitex.engine.graphics.Texture;
import me.piitex.engine.graphics.TextureRegion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The tiles a map is painted with: usually one image cut into a grid, or in Tiled's "collection of images" kind, a
 * separate image per tile.
 * <pre>{@code
 * TileSet terrain = map.getTileSet("terrain");
 * terrain.getTexture().setSmooth(false);
 * Tile grass = terrain.getTile(12);
 * }</pre>
 * A tile set for a map built in code comes from {@link #fromGrid}.
 */
public final class TileSet {
    private final String name;
    private final int tileWidth, tileHeight;
    private final Texture texture;
    private final int columns;
    private Tile[] tiles = new Tile[0];
    private int tileCount;
    private int maxTileWidth, maxTileHeight;
    private int tileOffsetX, tileOffsetY;
    private String type = "";
    private Properties properties = Properties.EMPTY;
    private float objectPivotX = 0f, objectPivotY = 1f;
    private boolean renderAtGridSize, keepAspect;

    TileSet(String name, int tileWidth, int tileHeight, Texture texture, int columns) {
        this.name = name == null ? "" : name;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.texture = texture;
        this.columns = columns;
    }

    /**
     * A tile set cut from {@code texture} in a grid of {@code tileWidth} by {@code tileHeight} tiles, numbered from
     * 0 at the top left, row by row, for building a map in code.
     */
    public static TileSet fromGrid(String name, Texture texture, int tileWidth, int tileHeight) {
        return fromGrid(name, texture, tileWidth, tileHeight, 0, 0);
    }

    /**
     * As {@link #fromGrid(String, Texture, int, int)}, for an image with {@code margin} pixels around its edge and
     * {@code spacing} pixels between tiles.
     */
    public static TileSet fromGrid(String name, Texture texture, int tileWidth, int tileHeight, int margin, int spacing) {
        if (texture == null) throw new IllegalArgumentException("texture must not be null");
        if (tileWidth <= 0 || tileHeight <= 0) throw new IllegalArgumentException("tiles must not be empty: " + tileWidth + "x" + tileHeight);
        if (margin < 0 || spacing < 0) throw new IllegalArgumentException("margin and spacing must not be negative");
        int columns = Math.max(0, (texture.getWidth() - 2 * margin + spacing) / (tileWidth + spacing));
        TileSet set = new TileSet(name, tileWidth, tileHeight, texture, columns);
        List<TextureRegion> cells = new TextureRegion(texture).split(tileWidth, tileHeight, margin, spacing);
        for (int i = 0; i < cells.size(); i++) set.put(new Tile(set, i, cells.get(i)));
        return set;
    }

    void put(Tile tile) {
        int id = tile.getId();
        if (id >= tiles.length) tiles = Arrays.copyOf(tiles, Math.max(id + 1, tiles.length * 2));
        if (tiles[id] == null) tileCount++;
        tiles[id] = tile;
        maxTileWidth = Math.max(maxTileWidth, tile.getWidth());
        maxTileHeight = Math.max(maxTileHeight, tile.getHeight());
    }

    public String getName() {
        return name;
    }

    /**
     * The tile numbered {@code id}. Throws, naming the tile set, if there is none.
     */
    public Tile getTile(int id) {
        Tile tile = findTile(id);
        if (tile == null) throw new IllegalArgumentException("No tile " + id + " in tile set '" + name + "'");
        return tile;
    }

    /**
     * As {@link #getTile}, but null if there is no such tile.
     */
    public Tile findTile(int id) {
        return id >= 0 && id < tiles.length ? tiles[id] : null;
    }

    /**
     * Every tile, by number.
     */
    public List<Tile> getTiles() {
        List<Tile> list = new ArrayList<>(tileCount);
        for (Tile tile : tiles) if (tile != null) list.add(tile);
        return Collections.unmodifiableList(list);
    }

    public int getTileCount() {
        return tileCount;
    }

    /**
     * The size of the tiles in the grid, in pixels. In a collection of images each tile has its own size; this is
     * the largest.
     */
    public int getTileWidth() {
        return tileWidth;
    }

    public int getTileHeight() {
        return tileHeight;
    }

    /**
     * The image the tiles are cut from, or null for a collection of images, where each {@link Tile#getRegion()}
     * has its own. {@link TileMap#setSmooth} sets the sampling of every image a map uses.
     */
    public Texture getTexture() {
        return texture;
    }

    /**
     * The number of tiles in a row of the image; 0 for a collection of images.
     */
    public int getColumns() {
        return columns;
    }

    /**
     * How far the tiles are drawn from where they would be, in pixels: Tiled's "Drawing Offset".
     */
    public int getTileOffsetX() {
        return tileOffsetX;
    }

    public int getTileOffsetY() {
        return tileOffsetY;
    }

    /**
     * The tile set's class, or "" if none is set.
     */
    public String getType() {
        return type;
    }

    public Properties getProperties() {
        return properties;
    }

    int getMaxTileWidth() {
        return maxTileWidth;
    }

    int getMaxTileHeight() {
        return maxTileHeight;
    }

    float getObjectPivotX() {
        return objectPivotX;
    }

    float getObjectPivotY() {
        return objectPivotY;
    }

    boolean isRenderAtGridSize() {
        return renderAtGridSize;
    }

    boolean isKeepAspect() {
        return keepAspect;
    }

    void setTileOffset(int x, int y) {
        this.tileOffsetX = x;
        this.tileOffsetY = y;
    }

    void setType(String type) {
        this.type = type == null ? "" : type;
    }

    void setProperties(Properties properties) {
        this.properties = properties;
    }

    void setObjectPivot(float x, float y) {
        this.objectPivotX = x;
        this.objectPivotY = y;
    }

    void setRenderSize(boolean atGridSize, boolean keepAspect) {
        this.renderAtGridSize = atGridSize;
        this.keepAspect = keepAspect;
    }

    @Override
    public String toString() {
        return "TileSet[" + name + ", " + tileCount + " tiles of " + tileWidth + "x" + tileHeight + "]";
    }
}
