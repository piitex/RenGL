package me.piitex.engine.tilemap;

import me.piitex.engine.graphics.Animation;
import me.piitex.engine.graphics.TextureRegion;
import me.piitex.engine.math.Shape;

import java.util.ArrayList;
import java.util.List;

/**
 * One tile of a {@link TileSet}: its picture, and what was set on it in Tiled's tile set editor, namely its class,
 * custom properties, collision shapes and animation. The same tile is shared by every cell of every layer it is
 * placed in, so it describes the kind of tile, not one spot on the map.
 * <pre>{@code
 * Tile tile = ground.getTileAt(player.x, player.y + 1);
 * if (tile != null && tile.getType().equals("ice")) friction = 0.1f;
 * }</pre>
 */
public final class Tile {
    private final TileSet tileSet;
    private final int id;
    private final TextureRegion region;
    private String type = "";
    private Properties properties = Properties.EMPTY;
    private List<MapObject> collisionObjects = List.of();
    private List<Shape> collisionShapes;
    private Animation animation;

    Tile(TileSet tileSet, int id, TextureRegion region) {
        this.tileSet = tileSet;
        this.id = id;
        this.region = region;
    }

    public TileSet getTileSet() {
        return tileSet;
    }

    /**
     * The tile's number within its tile set, counting from 0 at the top left, row by row. For a collection of
     * images it is the number Tiled gave the image.
     */
    public int getId() {
        return id;
    }

    /**
     * The tile's picture. For an animated tile this is its own picture, which Tiled shows in the tile set; the map
     * draws {@link #getRegionAt the current frame} instead.
     */
    public TextureRegion getRegion() {
        return region;
    }

    /**
     * The picture to show {@code time} seconds into the animation: the tile's own picture if it isn't animated.
     */
    public TextureRegion getRegionAt(double time) {
        return animation == null ? region : animation.getRegionAt(time);
    }

    /**
     * Width of the picture, in pixels.
     */
    public int getWidth() {
        return region.getWidth();
    }

    public int getHeight() {
        return region.getHeight();
    }

    /**
     * The tile's class (called type before Tiled 1.9), or "" if none is set.
     */
    public String getType() {
        return type;
    }

    public Properties getProperties() {
        return properties;
    }

    /**
     * The shapes drawn on the tile in Tiled's collision editor, as objects with positions measured from the tile's
     * top-left corner. Empty if none were drawn.
     */
    public List<MapObject> getCollisionObjects() {
        return collisionObjects;
    }

    /**
     * The collision objects as shapes, measured from the tile's top-left corner. Points and polylines have no
     * area and are left out; a concave polygon becomes several convex ones. {@link TileLayer#getCollisionShapes()}
     * places them on the map.
     */
    public List<Shape> getCollisionShapes() {
        if (collisionShapes == null) {
            List<Shape> shapes = new ArrayList<>();
            for (MapObject object : collisionObjects) shapes.addAll(object.getShapes());
            collisionShapes = List.copyOf(shapes);
        }
        return collisionShapes;
    }

    /**
     * The animation set on the tile in Tiled, which loops, or null if it isn't animated.
     */
    public Animation getAnimation() {
        return animation;
    }

    public boolean isAnimated() {
        return animation != null;
    }

    void setType(String type) {
        this.type = type == null ? "" : type;
    }

    void setProperties(Properties properties) {
        this.properties = properties;
    }

    void setCollisionObjects(List<MapObject> objects) {
        this.collisionObjects = List.copyOf(objects);
        this.collisionShapes = null;
    }

    void setAnimation(Animation animation) {
        this.animation = animation;
    }

    @Override
    public String toString() {
        return "Tile[" + id + " of " + tileSet.getName() + (type.isEmpty() ? "" : ", " + type) + "]";
    }
}
