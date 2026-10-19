package me.piitex.engine.tilemap;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.graphics.Texture;
import me.piitex.engine.graphics.TextureRegion;
import me.piitex.engine.math.Circle;
import me.piitex.engine.math.Polygon;
import me.piitex.engine.math.Rect;
import me.piitex.engine.math.Shape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A grid of tiles, one {@link Tile} or none in each cell, each cell possibly mirrored or turned.
 * <pre>{@code
 * TileLayer ground = map.getTileLayer("ground");
 * Tile under = ground.getTileAt(player.x, player.y + 1);    // the tile below the player's feet
 * ground.setTile(tx, ty, null);                             // dig a hole
 * List<Rect> walls = ground.getSolidRects(tile -> tile.getProperties().getBoolean("solid", false));
 * }</pre>
 * <b>Cells</b> are numbered in tiles from the map's top-left corner: cell (x, y) covers
 * {@code x * tileWidth} to {@code (x + 1) * tileWidth} across, plus the layer's offset. In an infinite map a layer
 * can start left of or above that corner, at negative cells; {@link #getStartX()} and {@link #getStartY()} say
 * where it starts. Cells outside the layer read as empty.
 * <p>
 * <b>Tiles bigger than the grid</b>, such as a tree two cells tall, sit on the bottom-left corner of their cell and
 * reach up and to the right, as in Tiled.
 * <p>
 * <b>Flags</b> mirror or turn a cell's tile: {@link #FLIP_X}, {@link #FLIP_Y} and {@link #FLIP_DIAGONAL}, or for
 * turning, {@link #ROTATE_90}, {@link #ROTATE_180} and {@link #ROTATE_270}. They come from Tiled's flip and rotate
 * buttons, and can be set with {@link #setTile(int, int, Tile, int)}.
 */
public final class TileLayer extends MapLayer {
    /**
     * Mirrored left to right.
     */
    public static final int FLIP_X = 1;
    /**
     * Mirrored top to bottom.
     */
    public static final int FLIP_Y = 2;
    /**
     * Mirrored across the line from the top-left corner to the bottom-right one, done before the other two.
     */
    public static final int FLIP_DIAGONAL = 4;
    /**
     * Turned a quarter turn clockwise.
     */
    public static final int ROTATE_90 = FLIP_DIAGONAL | FLIP_X;
    public static final int ROTATE_180 = FLIP_X | FLIP_Y;
    public static final int ROTATE_270 = FLIP_DIAGONAL | FLIP_Y;

    private static final float[] CORNER_U = {0f, 1f, 1f, 0f}, CORNER_V = {0f, 0f, 1f, 1f};

    private final int startX, startY, width, height;
    private final Tile[] tiles;
    private final byte[] flags;
    private final List<TileSet> usedSets = new ArrayList<>(2);
    private final float[] rect = new float[4];

    /**
     * An empty layer of {@code width} by {@code height} cells, for building a map in code.
     */
    public TileLayer(String name, int width, int height) {
        this(name, 0, 0, width, height);
    }

    /**
     * An empty layer of {@code width} by {@code height} cells, whose first cell is ({@code startX}, {@code startY}).
     */
    public TileLayer(String name, int startX, int startY, int width, int height) {
        super(name);
        if (width < 0 || height < 0) throw new IllegalArgumentException("size must not be negative: " + width + "x" + height);
        if ((long) width * height > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("layer is too big: " + width + "x" + height);
        this.startX = startX;
        this.startY = startY;
        this.width = width;
        this.height = height;
        this.tiles = new Tile[width * height];
        this.flags = new byte[width * height];
    }

    /**
     * The layer's first column, in cells: 0 except in an infinite map.
     */
    public int getStartX() {
        return startX;
    }

    public int getStartY() {
        return startY;
    }

    /**
     * The number of columns the layer has.
     */
    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Whether cell ({@code x}, {@code y}) is inside the layer.
     */
    public boolean contains(int x, int y) {
        return x >= startX && y >= startY && x - startX < width && y - startY < height;
    }

    /**
     * The tile in cell ({@code x}, {@code y}), or null if it is empty or outside the layer.
     */
    public Tile getTile(int x, int y) {
        return contains(x, y) ? tiles[(y - startY) * width + (x - startX)] : null;
    }

    /**
     * How the tile in cell ({@code x}, {@code y}) is mirrored or turned: {@link #FLIP_X}, {@link #FLIP_Y} and
     * {@link #FLIP_DIAGONAL} or'ed together, 0 for neither.
     */
    public int getFlags(int x, int y) {
        return contains(x, y) ? flags[(y - startY) * width + (x - startX)] : 0;
    }

    /**
     * Puts {@code tile} in cell ({@code x}, {@code y}), neither mirrored nor turned; null empties the cell.
     *
     * @throws IndexOutOfBoundsException if the cell is outside the layer
     */
    public void setTile(int x, int y, Tile tile) {
        setTile(x, y, tile, 0);
    }

    /**
     * Puts {@code tile} in cell ({@code x}, {@code y}), mirrored or turned by {@code flags}, such as
     * {@link #ROTATE_90}; null empties the cell.
     *
     * @throws IndexOutOfBoundsException if the cell is outside the layer
     */
    public void setTile(int x, int y, Tile tile, int flags) {
        if (!contains(x, y)) {
            throw new IndexOutOfBoundsException("Cell " + x + ", " + y + " is outside layer '" + getName() + "', which covers "
                    + startX + ", " + startY + " to " + (startX + width - 1) + ", " + (startY + height - 1));
        }
        int index = (y - startY) * width + (x - startX);
        tiles[index] = tile;
        this.flags[index] = tile == null ? 0 : (byte) (flags & 7);
        if (tile != null) noteSet(tile.getTileSet());
    }

    private void noteSet(TileSet set) {
        for (int i = 0; i < usedSets.size(); i++) if (usedSets.get(i) == set) return;
        usedSets.add(set);
    }

    /**
     * The tile under the world point ({@code worldX}, {@code worldY}), allowing for the layer's offset, or null.
     */
    public Tile getTileAt(float worldX, float worldY) {
        TileMap map = requireMap();
        int x = (int) Math.floor((worldX - totalOffsetX()) / map.getTileWidth());
        int y = (int) Math.floor((worldY - totalOffsetY()) / map.getTileHeight());
        return getTile(x, y);
    }

    /**
     * The number of cells with a tile in them.
     */
    public int getTileCount() {
        int count = 0;
        for (Tile tile : tiles) if (tile != null) count++;
        return count;
    }

    // ---- Collision

    /**
     * The collision shapes drawn on the layer's tiles in Tiled's collision editor, placed on the map, mirrored and
     * turned with their cells. Cells whose tile's shape is one rectangle covering the whole cell are joined into
     * as few large rectangles as easily fit, so a wall of a hundred tiles is a handful of shapes. Ready for
     * {@link me.piitex.engine.math.Slide#move} or a {@link me.piitex.engine.math.SpatialGrid}. Includes the layer's
     * offset; hidden layers count too.
     */
    public List<Shape> getCollisionShapes() {
        TileMap map = requireMap();
        float ox = totalOffsetX(), oy = totalOffsetY();
        float gw = map.getTileWidth(), gh = map.getTileHeight();
        List<Shape> shapes = new ArrayList<>();
        boolean[] full = new boolean[tiles.length];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int index = row * width + column;
                Tile tile = tiles[index];
                if (tile == null) continue;
                List<Shape> local = tile.getCollisionShapes();
                if (local.isEmpty()) continue;
                int cellFlags = flags[index];
                placeTile(map, tile, cellFlags, startX + column, startY + row, ox, oy, rect);
                float x = rect[0], y = rect[1], w = rect[2], h = rect[3];
                if (local.size() == 1 && local.getFirst() instanceof Rect r && r.x() == 0f && r.y() == 0f
                        && r.width() == tile.getWidth() && r.height() == tile.getHeight()
                        && x == ox + (startX + column) * gw && y == oy + (startY + row) * gh && w == gw && h == gh) {
                    full[index] = true;
                    continue;
                }
                for (Shape shape : local) shapes.add(place(shape, tile.getWidth(), tile.getHeight(), cellFlags, x, y, w, h));
            }
        }
        List<Rect> joined = new ArrayList<>();
        joinCells(full, ox, oy, gw, gh, joined);
        shapes.addAll(0, joined);
        return shapes;
    }

    /**
     * Rectangles covering every cell whose tile passes {@code isSolid}, joined into as few large rectangles as
     * easily fit: {@code t -> true} for a layer that is solid wherever it has a tile, or a test of the tile's class
     * or properties. Each covers whole cells of the grid, whatever the tile's picture or collision shapes. Includes
     * the layer's offset.
     */
    public List<Rect> getSolidRects(Predicate<? super Tile> isSolid) {
        TileMap map = requireMap();
        boolean[] full = new boolean[tiles.length];
        for (int i = 0; i < tiles.length; i++) full[i] = tiles[i] != null && isSolid.test(tiles[i]);
        List<Rect> rects = new ArrayList<>();
        joinCells(full, totalOffsetX(), totalOffsetY(), map.getTileWidth(), map.getTileHeight(), rects);
        return rects;
    }

    /**
     * Greedy rectangles over the marked cells: each takes the longest run right from its first cell, then as many
     * rows down as are marked the whole way across.
     */
    private void joinCells(boolean[] full, float ox, float oy, float gw, float gh, List<Rect> out) {
        boolean[] used = new boolean[full.length];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int index = row * width + column;
                if (!full[index] || used[index]) continue;
                int across = 1;
                while (column + across < width && full[index + across] && !used[index + across]) across++;
                int down = 1;
                grow:
                while (row + down < height) {
                    int first = (row + down) * width + column;
                    for (int k = 0; k < across; k++) if (!full[first + k] || used[first + k]) break grow;
                    down++;
                }
                for (int r = 0; r < down; r++) {
                    for (int k = 0; k < across; k++) used[(row + r) * width + column + k] = true;
                }
                out.add(new Rect(ox + (startX + column) * gw, oy + (startY + row) * gh, across * gw, down * gh));
            }
        }
    }

    /**
     * A shape measured on a tile's picture of {@code tileWidth} by {@code tileHeight}, moved onto the rectangle the
     * tile is drawn in, mirrored and turned by {@code cellFlags}.
     */
    static Shape place(Shape shape, float tileWidth, float tileHeight, int cellFlags, float x, float y, float w, float h) {
        boolean diagonal = (cellFlags & FLIP_DIAGONAL) != 0;
        float scaleU = w / (diagonal ? tileHeight : tileWidth), scaleV = h / (diagonal ? tileWidth : tileHeight);
        float[] point = new float[2];
        switch (shape) {
            case Rect r -> {
                mapPoint(r.x(), r.y(), tileWidth, tileHeight, cellFlags, x, y, w, h, point);
                float x0 = point[0], y0 = point[1];
                mapPoint(r.right(), r.bottom(), tileWidth, tileHeight, cellFlags, x, y, w, h, point);
                return Rect.fromCorners(x0, y0, point[0], point[1]);
            }
            case Circle c -> {
                mapPoint(c.x(), c.y(), tileWidth, tileHeight, cellFlags, x, y, w, h, point);
                return new Circle(point[0], point[1], c.radius() * (scaleU + scaleV) / 2f);
            }
            case Polygon p -> {
                float[] points = p.getPoints();
                for (int i = 0; i < points.length; i += 2) {
                    mapPoint(points[i], points[i + 1], tileWidth, tileHeight, cellFlags, x, y, w, h, point);
                    points[i] = point[0];
                    points[i + 1] = point[1];
                }
                return new Polygon(points);
            }
        }
    }

    private static void mapPoint(float px, float py, float tileWidth, float tileHeight, int cellFlags,
                                 float x, float y, float w, float h, float[] out) {
        float u = px / tileWidth, v = py / tileHeight;
        if ((cellFlags & FLIP_DIAGONAL) != 0) {
            float swap = u;
            u = v;
            v = swap;
        }
        if ((cellFlags & FLIP_X) != 0) u = 1f - u;
        if ((cellFlags & FLIP_Y) != 0) v = 1f - v;
        out[0] = x + u * w;
        out[1] = y + v * h;
    }

    /**
     * Where the tile in cell ({@code cellX}, {@code cellY}) is drawn, as x, y, width, height: on the bottom-left
     * corner of the cell, moved by the tile set's drawing offset, and sized as the tile set says.
     */
    static void placeTile(TileMap map, Tile tile, int cellFlags, int cellX, int cellY, float ox, float oy, float[] out) {
        TileSet set = tile.getTileSet();
        float gw = map.getTileWidth(), gh = map.getTileHeight();
        float w = tile.getWidth(), h = tile.getHeight();
        float shiftX = 0f, shiftY = 0f;
        if (set.isRenderAtGridSize()) {
            if (set.isKeepAspect()) {
                float scale = Math.min(gw / w, gh / h);
                w *= scale;
                h *= scale;
                shiftX = (gw - w) / 2f;
                shiftY = -(gh - h) / 2f;
            } else {
                w = gw;
                h = gh;
            }
        }
        if ((cellFlags & FLIP_DIAGONAL) != 0) {
            float swap = w;
            w = h;
            h = swap;
        }
        out[0] = ox + cellX * gw + set.getTileOffsetX() + shiftX;
        out[1] = oy + (cellY + 1) * gh + set.getTileOffsetY() - h + shiftY;
        out[2] = w;
        out[3] = h;
    }

    // ---- Drawing

    @Override
    void render(Renderer renderer, Rect view, DrawState parent) {
        DrawState state = parent.with(this);
        if (state == null || tiles.length == 0) return;
        TileMap map = requireMap();
        float gw = map.getTileWidth(), gh = map.getTileHeight();
        float ox = state.offsetX + state.shiftX(map, view), oy = state.offsetY + state.shiftY(map, view);

        int x0 = startX, x1 = startX + width - 1, y0 = startY, y1 = startY + height - 1;
        if (view != null) {
            // How far a tile can reach out of its cell: big tiles reach up and right, drawing offsets any way.
            float reachLeft = 0f, reachRight = 0f, reachUp = 0f, reachDown = 0f;
            for (TileSet set : usedSets) {
                float size = set.isRenderAtGridSize() ? Math.max(gw, gh) : Math.max(set.getMaxTileWidth(), set.getMaxTileHeight());
                reachLeft = Math.max(reachLeft, -set.getTileOffsetX());
                reachRight = Math.max(reachRight, size + set.getTileOffsetX() - gw);
                reachUp = Math.max(reachUp, size - gh - set.getTileOffsetY());
                reachDown = Math.max(reachDown, set.getTileOffsetY());
            }
            x0 = (int) Math.max(x0, Math.floor((view.x() - ox - reachRight) / gw));
            x1 = (int) Math.min(x1, Math.floor((view.right() - ox + reachLeft) / gw));
            y0 = (int) Math.max(y0, Math.floor((view.y() - oy - reachDown) / gh));
            y1 = (int) Math.min(y1, Math.floor((view.bottom() - oy + reachUp) / gh));
            if (x0 > x1 || y0 > y1) return;
        }

        QuadBuffer quads = map.quads();
        int color = state.packed();
        double time = map.getTime();
        boolean rightwards = map.isRenderRight(), downwards = map.isRenderDown();
        for (int j = 0, rows = y1 - y0 + 1; j < rows; j++) {
            int cellY = downwards ? y0 + j : y1 - j;
            int rowStart = (cellY - startY) * width - startX;
            for (int i = 0, columns = x1 - x0 + 1; i < columns; i++) {
                int cellX = rightwards ? x0 + i : x1 - i;
                Tile tile = tiles[rowStart + cellX];
                if (tile == null) continue;
                int cellFlags = flags[rowStart + cellX];
                placeTile(map, tile, cellFlags, cellX, cellY, ox, oy, rect);
                addQuad(renderer, quads, tile.getRegionAt(time), cellFlags, rect[0], rect[1], rect[2], rect[3], color);
            }
        }
        quads.flush(renderer);
    }

    /**
     * Adds one tile's picture, mirrored and turned by {@code cellFlags}, filling the rectangle at ({@code x},
     * {@code y}).
     */
    static void addQuad(Renderer renderer, QuadBuffer quads, TextureRegion region, int cellFlags,
                        float x, float y, float w, float h, int color) {
        Texture texture = region.getTexture();
        int at = quads.start(renderer, texture.getImage(), texture.isSmooth());
        float[] positions = quads.positions, texCoords = quads.texCoords;
        boolean diagonal = (cellFlags & FLIP_DIAGONAL) != 0, flipX = (cellFlags & FLIP_X) != 0, flipY = (cellFlags & FLIP_Y) != 0;
        for (int corner = 0; corner < 4; corner++) {
            float u = CORNER_U[corner], v = CORNER_V[corner];
            if (diagonal) {
                float swap = u;
                u = v;
                v = swap;
            }
            if (flipX) u = 1f - u;
            if (flipY) v = 1f - v;
            positions[at * 8 + corner * 2] = x + u * w;
            positions[at * 8 + corner * 2 + 1] = y + v * h;
        }
        float left = region.getX(), top = region.getY(), right = left + region.getWidth(), bottom = top + region.getHeight();
        int t = at * 8;
        texCoords[t] = left;
        texCoords[t + 1] = top;
        texCoords[t + 2] = right;
        texCoords[t + 3] = top;
        texCoords[t + 4] = right;
        texCoords[t + 5] = bottom;
        texCoords[t + 6] = left;
        texCoords[t + 7] = bottom;
        quads.colors[at] = color;
    }
}
