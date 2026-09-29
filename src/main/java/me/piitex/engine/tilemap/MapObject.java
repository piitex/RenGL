package me.piitex.engine.tilemap;

import me.piitex.engine.graphics.Sprite;
import me.piitex.engine.graphics.TextureRegion;
import me.piitex.engine.math.Circle;
import me.piitex.engine.math.Polygon;
import me.piitex.engine.math.Rect;
import me.piitex.engine.math.Shape;

import java.util.ArrayList;
import java.util.List;

/**
 * Something placed on an {@link ObjectLayer} in Tiled: a rectangle, ellipse, point, polygon, polyline, text or tile,
 * with a name, a class and custom properties. Games use them for everything that isn't a tile: where the player
 * starts, where enemies spawn, doors, triggers, paths, and walls that don't follow the grid.
 * <pre>{@code
 * for (MapObject object : map.getObjectLayer("entities").getObjects()) {
 *     switch (object.getType()) {
 *         case "Coin" -> coins.add(new Coin(object.getX(), object.getY()));
 *         case "Enemy" -> enemies.add(new Enemy(object.getBounds(), object.getProperties().getFloat("speed", 60f)));
 *     }
 * }
 * }</pre>
 * <b>Position.</b> {@link #getX()} and {@link #getY()} are where Tiled puts the object, plus the offset of its layer
 * and groups. For most kinds that is the top-left corner, but for a tile it is the bottom-left corner, as Tiled
 * saves it (or the point the tile set's "Object Alignment" names). {@link #getBounds()} gives the box each kind
 * really covers. A turned object turns clockwise around ({@link #getX()}, {@link #getY()}).
 */
public final class MapObject {
    /**
     * The kind of object, from the tool it was drawn with in Tiled.
     */
    public enum Kind {
        RECTANGLE, ELLIPSE, CAPSULE, POINT, POLYGON, POLYLINE, TEXT, TILE
    }

    private static final int ELLIPSE_POINTS = 16;

    private final int id;
    private final String name;
    private final String type;
    private final Kind kind;
    private final float x, y, width, height, rotation;
    private final float[] points; // polygon and polyline points, from (x, y), before turning
    private final Tile tile;
    private final boolean flipX, flipY;
    private final String text;
    private final Properties properties;
    private boolean visible;
    private float opacity;
    ObjectLayer layer;

    MapObject(int id, String name, String type, Kind kind, float x, float y, float width, float height, float rotation,
              float[] points, Tile tile, boolean flipX, boolean flipY, String text, boolean visible, float opacity,
              Properties properties) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.type = type == null ? "" : type;
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.rotation = rotation;
        this.points = points;
        this.tile = tile;
        this.flipX = flipX;
        this.flipY = flipY;
        this.text = text;
        this.visible = visible;
        this.opacity = opacity;
        this.properties = properties;
    }

    /**
     * The number Tiled gave the object, unique in its map. Object properties point to objects by it.
     */
    public int getId() {
        return id;
    }

    /**
     * The object's name, or "" if it has none.
     */
    public String getName() {
        return name;
    }

    /**
     * The object's class (called type before Tiled 1.9), or "" if none is set.
     */
    public String getType() {
        return type;
    }

    public Kind getKind() {
        return kind;
    }

    public Properties getProperties() {
        return properties;
    }

    /**
     * The layer the object is on, or null for a shape drawn in Tiled's tile collision editor.
     */
    public ObjectLayer getLayer() {
        return layer;
    }

    /**
     * Where Tiled puts the object, plus the offset of its layer and groups: the top-left corner, or for a tile, its
     * bottom-left corner.
     */
    public float getX() {
        return layer == null ? x : x + layer.totalOffsetX();
    }

    public float getY() {
        return layer == null ? y : y + layer.totalOffsetY();
    }

    /**
     * The width set in Tiled: 0 for points, polygons and polylines.
     */
    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    /**
     * Degrees clockwise, around ({@link #getX()}, {@link #getY()}).
     */
    public float getRotation() {
        return rotation;
    }

    /**
     * Whether the object is shown. Only tile objects are drawn; this also marks the others for the game to use as
     * it likes.
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Shows or hides the object: hiding a tile object stops it being drawn, as for a coin that was picked up.
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public float getOpacity() {
        return opacity;
    }

    public void setOpacity(float opacity) {
        this.opacity = Math.clamp(opacity, 0f, 1f);
    }

    /**
     * The tile shown by a tile object, or null for other kinds.
     */
    public Tile getTile() {
        return tile;
    }

    /**
     * Whether a tile object is mirrored left to right.
     */
    public boolean isFlipX() {
        return flipX;
    }

    public boolean isFlipY() {
        return flipY;
    }

    /**
     * The text of a text object, or null for other kinds.
     */
    public String getText() {
        return text;
    }

    /**
     * The points of a polygon or polyline in world coordinates, turned with the object, packed as
     * {@code x0, y0, x1, y1, ...}: a path for an enemy to patrol, say. Empty for other kinds.
     */
    public float[] getPoints() {
        if (points == null) return new float[0];
        float[] world = new float[points.length];
        float ox = getX(), oy = getY();
        double radians = Math.toRadians(rotation);
        float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
        for (int i = 0; i < points.length; i += 2) {
            float px = points[i], py = points[i + 1];
            world[i] = rotation == 0f ? ox + px : ox + px * cos - py * sin;
            world[i + 1] = rotation == 0f ? oy + py : oy + px * sin + py * cos;
        }
        return world;
    }

    /**
     * The smallest upright rectangle around the object as Tiled shows it, turned. For a point, an empty rectangle
     * at the point.
     */
    public Rect getBounds() {
        switch (kind) {
            case POINT -> {
                return new Rect(getX(), getY(), 0f, 0f);
            }
            case POLYGON, POLYLINE -> {
                return boundsOf(getPoints());
            }
            case ELLIPSE, CAPSULE -> {
                if (rotation != 0f && width > 0f && height > 0f) {
                    Rect bounds = null;
                    for (Shape shape : getShapes()) bounds = bounds == null ? shape.bounds() : bounds.union(shape.bounds());
                    return bounds;
                }
                return boundsOf(corners());
            }
            default -> {
                return boundsOf(corners());
            }
        }
    }

    /**
     * The object as shapes for collision, in world coordinates: a {@link Rect} for a rectangle, text or tile (a
     * {@link Polygon} if turned), a {@link Circle} for a circle, a many-sided polygon for any other ellipse, a
     * rectangle and two circles for a capsule, and one or more convex polygons for a polygon. Points, polylines and
     * objects without area have no shapes, so the list is empty.
     *
     * @throws IllegalArgumentException if a polygon's outline crosses itself, naming the object
     */
    public List<Shape> getShapes() {
        List<Shape> shapes = new ArrayList<>(1);
        switch (kind) {
            case POINT, POLYLINE -> {
            }
            case POLYGON -> {
                try {
                    shapes.addAll(Polygon.decompose(getPoints()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(describe() + ": " + e.getMessage(), e);
                }
            }
            case ELLIPSE -> {
                if (!(width > 0f) || !(height > 0f)) break;
                if (width == height) {
                    shapes.add(circle(width / 2f, height / 2f, width / 2f));
                    break;
                }
                float[] outline = new float[ELLIPSE_POINTS * 2];
                for (int i = 0; i < ELLIPSE_POINTS; i++) {
                    double angle = Math.PI * 2 * i / ELLIPSE_POINTS;
                    outline[i * 2] = width / 2f + width / 2f * (float) Math.cos(angle);
                    outline[i * 2 + 1] = height / 2f + height / 2f * (float) Math.sin(angle);
                }
                shapes.add(new Polygon(turn(outline)));
            }
            case CAPSULE -> {
                if (!(width > 0f) || !(height > 0f)) break;
                float r = Math.min(width, height) / 2f;
                if (width == height) {
                    shapes.add(circle(r, r, r));
                } else if (width > height) {
                    shapes.add(box(r, 0f, width - 2 * r, height));
                    shapes.add(circle(r, r, r));
                    shapes.add(circle(width - r, r, r));
                } else {
                    shapes.add(box(0f, r, width, height - 2 * r));
                    shapes.add(circle(r, r, r));
                    shapes.add(circle(r, height - r, r));
                }
            }
            default -> {
                if (!(width > 0f) || !(height > 0f)) break;
                shapes.add(box(left(), top(), width, height));
            }
        }
        return shapes;
    }

    /**
     * A new sprite showing a tile object as it is drawn on the map: picture, size, turn and mirroring, positioned
     * by the same point as the object. For taking a tile object off the map to move it about, such as a crate
     * that can be pushed: hide the object and draw the sprite instead.
     *
     * @throws IllegalStateException if this isn't a tile object
     */
    public Sprite makeSprite() {
        if (tile == null) throw new IllegalStateException(describe() + " isn't a tile object");
        Sprite sprite = new Sprite();
        TileMap map = layer == null ? null : layer.getMap();
        configure(sprite, tile.getRegionAt(map == null ? 0.0 : map.getTime()), 0f, 0f);
        return sprite;
    }

    /**
     * Sets {@code sprite} up to draw this tile object with {@code region}, moved by ({@code shiftX}, {@code shiftY}).
     */
    void configure(Sprite sprite, TextureRegion region, float shiftX, float shiftY) {
        TileSet set = tile.getTileSet();
        sprite.setRegion(region);
        sprite.setPivot(set.getObjectPivotX(), set.getObjectPivotY());
        sprite.setPosition(getX() + set.getTileOffsetX() + shiftX, getY() + set.getTileOffsetY() + shiftY);
        sprite.setRotation(rotation);
        float scaleX = width > 0f ? width / region.getFrameWidth() : 1f;
        float scaleY = height > 0f ? height / region.getFrameHeight() : 1f;
        sprite.setScale(scaleX, scaleY);
        sprite.setFlipX(flipX);
        sprite.setFlipY(flipY);
    }

    /**
     * Where the box starts from ({@link #getX()}, {@link #getY()}): 0 except for a tile, whose position is the
     * point its tile set's object alignment names.
     */
    private float left() {
        return tile == null ? 0f : -tile.getTileSet().getObjectPivotX() * width;
    }

    private float top() {
        return tile == null ? 0f : -tile.getTileSet().getObjectPivotY() * height;
    }

    private float[] corners() {
        float l = left(), t = top();
        return turn(new float[]{l, t, l + width, t, l + width, t + height, l, t + height});
    }

    private Shape box(float left, float top, float w, float h) {
        if (rotation == 0f) return new Rect(getX() + left, getY() + top, w, h);
        return new Polygon(turn(new float[]{left, top, left + w, top, left + w, top + h, left, top + h}));
    }

    private Circle circle(float centerX, float centerY, float radius) {
        float[] center = turn(new float[]{centerX, centerY});
        return new Circle(center[0], center[1], radius);
    }

    /**
     * Points measured from ({@link #getX()}, {@link #getY()}) before turning, turned and moved into the world.
     */
    private float[] turn(float[] local) {
        float ox = getX(), oy = getY();
        double radians = Math.toRadians(rotation);
        float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
        float[] world = new float[local.length];
        for (int i = 0; i < local.length; i += 2) {
            float px = local[i], py = local[i + 1];
            world[i] = rotation == 0f ? ox + px : ox + px * cos - py * sin;
            world[i + 1] = rotation == 0f ? oy + py : oy + px * sin + py * cos;
        }
        return world;
    }

    private static Rect boundsOf(float[] p) {
        if (p.length == 0) return new Rect(0f, 0f, 0f, 0f);
        float minX = p[0], minY = p[1], maxX = p[0], maxY = p[1];
        for (int i = 2; i < p.length; i += 2) {
            minX = Math.min(minX, p[i]);
            maxX = Math.max(maxX, p[i]);
            minY = Math.min(minY, p[i + 1]);
            maxY = Math.max(maxY, p[i + 1]);
        }
        return new Rect(minX, minY, maxX - minX, maxY - minY);
    }

    String describe() {
        return "Object " + id + (name.isEmpty() ? "" : " '" + name + "'") + (layer == null ? "" : " in layer '" + layer.getName() + "'");
    }

    @Override
    public String toString() {
        return "MapObject[" + id + (name.isEmpty() ? "" : " " + name) + (type.isEmpty() ? "" : " (" + type + ")")
                + ", " + kind + " at " + getX() + ", " + getY() + "]";
    }
}
