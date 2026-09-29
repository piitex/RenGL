package me.piitex.engine.tilemap;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.math.Rect;
import me.piitex.engine.ui.color.Color;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A layer of a {@link TileMap}: a grid of tiles ({@link TileLayer}), free-placed objects ({@link ObjectLayer}), one
 * picture ({@link ImageLayer}) or a group of layers ({@link GroupLayer}).
 * <p>
 * Every layer has what Tiled's layer properties set: whether it is shown, its opacity and tint, how far it is
 * offset, and how fast it scrolls (parallax). A group's settings apply on top of its layers': a hidden group hides
 * them, its opacity and tint multiply theirs, its offset adds to theirs and its parallax multiplies theirs.
 * All of them can be changed while the game runs, for example to fade out a roof when the player walks inside.
 * <p>
 * <b>Parallax.</b> A layer with a parallax factor below 1 scrolls slower than the map, as something far away does,
 * and above 1 faster. It sits where Tiled shows it when the middle of the view is at the map's
 * {@link TileMap#getParallaxOriginX parallax origin}, and the distance from there moves it. Parallax only moves
 * what is drawn; it changes nothing in the layer's tile or object positions.
 */
public abstract sealed class MapLayer permits TileLayer, ObjectLayer, ImageLayer, GroupLayer {
    private final String name;
    private int id;
    private String type = "";
    private boolean visible = true;
    private float opacity = 1f;
    private Color tint;
    private float offsetX, offsetY;
    private float parallaxX = 1f, parallaxY = 1f;
    private Properties properties = Properties.EMPTY;
    TileMap map;
    GroupLayer parent;

    MapLayer(String name) {
        this.name = name == null ? "" : name;
    }

    /**
     * Draws the layer as it looks in Tiled, in world coordinates: between a camera's {@code begin} and {@code end}.
     * Only what overlaps {@code view} is drawn; pass the camera's {@code getVisibleBounds()}. Its middle is also
     * where parallax is measured from. Nothing is drawn if the layer or a group it is in is hidden.
     */
    public final void draw(Renderer renderer, Rect view) {
        Deque<MapLayer> chain = new ArrayDeque<>();
        for (GroupLayer group = parent; group != null; group = group.parent) chain.push(group);
        DrawState state = DrawState.ROOT;
        for (MapLayer group : chain) {
            state = state.with(group);
            if (state == null) return;
        }
        render(renderer, view, state);
    }

    /**
     * Draws the whole layer, without parallax.
     */
    public final void draw(Renderer renderer) {
        draw(renderer, null);
    }

    /**
     * Draws the layer, combining {@code parent}, the settings of the groups it is in, with its own.
     */
    abstract void render(Renderer renderer, Rect view, DrawState parent);

    void setMap(TileMap map) {
        this.map = map;
    }

    /**
     * The map the layer is in, or null before it is added to one.
     */
    public TileMap getMap() {
        return map;
    }

    /**
     * The group the layer is in, or null for a layer at the top of the map.
     */
    public GroupLayer getParent() {
        return parent;
    }

    public String getName() {
        return name;
    }

    /**
     * The number Tiled gave the layer, unique in its map, or 0 for a layer made in code.
     */
    public int getId() {
        return id;
    }

    /**
     * The layer's class, or "" if none is set.
     */
    public String getType() {
        return type;
    }

    public Properties getProperties() {
        return properties;
    }

    /**
     * Whether the layer is shown. A layer hidden in Tiled loads hidden: a layer of collision tiles, say, that is
     * there for the game to read and not to be seen. Hidden layers still count for collision.
     */
    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public float getOpacity() {
        return opacity;
    }

    /**
     * Opacity from 0, invisible, to 1, the default.
     */
    public void setOpacity(float opacity) {
        this.opacity = Math.clamp(opacity, 0f, 1f);
    }

    /**
     * The color the layer is multiplied with, or null for none.
     */
    public Color getTint() {
        return tint;
    }

    public void setTint(Color tint) {
        this.tint = tint;
    }

    /**
     * How far the layer is moved from its place, in pixels.
     */
    public float getOffsetX() {
        return offsetX;
    }

    public float getOffsetY() {
        return offsetY;
    }

    public void setOffset(float x, float y) {
        this.offsetX = x;
        this.offsetY = y;
    }

    /**
     * How fast the layer scrolls compared with the map: 1, the default, with it; 0.5 half as fast; 0 not at all.
     */
    public float getParallaxX() {
        return parallaxX;
    }

    public float getParallaxY() {
        return parallaxY;
    }

    public void setParallax(float x, float y) {
        this.parallaxX = x;
        this.parallaxY = y;
    }

    /**
     * The layer's offset plus that of every group it is in: where it really is, apart from parallax.
     */
    float totalOffsetX() {
        float total = offsetX;
        for (GroupLayer group = parent; group != null; group = group.parent) total += group.getOffsetX();
        return total;
    }

    float totalOffsetY() {
        float total = offsetY;
        for (GroupLayer group = parent; group != null; group = group.parent) total += group.getOffsetY();
        return total;
    }

    void setId(int id) {
        this.id = id;
    }

    void setType(String type) {
        this.type = type == null ? "" : type;
    }

    void setProperties(Properties properties) {
        this.properties = properties;
    }

    TileMap requireMap() {
        if (map == null) throw new IllegalStateException("Layer '" + name + "' isn't in a map: add it to one before drawing it");
        return map;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + "]";
    }

    /**
     * What the groups a layer is in add up to: offset, parallax, and the color everything is multiplied with.
     */
    static final class DrawState {
        static final DrawState ROOT = new DrawState(0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f);

        final float offsetX, offsetY, parallaxX, parallaxY;
        final float red, green, blue, alpha;

        private DrawState(float offsetX, float offsetY, float parallaxX, float parallaxY,
                          float red, float green, float blue, float alpha) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.parallaxX = parallaxX;
            this.parallaxY = parallaxY;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.alpha = alpha;
        }

        /**
         * This state with {@code layer}'s own settings on top, or null if the layer is hidden or fully transparent.
         */
        DrawState with(MapLayer layer) {
            if (!layer.visible) return null;
            float a = alpha * layer.opacity;
            float r = red, g = green, b = blue;
            if (layer.tint != null) {
                r *= layer.tint.getR();
                g *= layer.tint.getG();
                b *= layer.tint.getB();
                a *= layer.tint.getA();
            }
            if (!(a > 0f)) return null;
            return new DrawState(offsetX + layer.offsetX, offsetY + layer.offsetY,
                    parallaxX * layer.parallaxX, parallaxY * layer.parallaxY, r, g, b, a);
        }

        /**
         * How far parallax moves the layer across for {@code view}, measured from its middle.
         */
        float shiftX(TileMap map, Rect view) {
            return view == null ? 0f : (view.centerX() - map.getParallaxOriginX()) * (1f - parallaxX);
        }

        float shiftY(TileMap map, Rect view) {
            return view == null ? 0f : (view.centerY() - map.getParallaxOriginY()) * (1f - parallaxY);
        }

        /**
         * The color as one ARGB int, for {@code Renderer.drawImageQuads}.
         */
        int packed() {
            return packed(1f);
        }

        int packed(float opacity) {
            return (Math.round(Math.clamp(alpha * opacity, 0f, 1f) * 255f) << 24)
                    | (Math.round(Math.clamp(red, 0f, 1f) * 255f) << 16)
                    | (Math.round(Math.clamp(green, 0f, 1f) * 255f) << 8)
                    | Math.round(Math.clamp(blue, 0f, 1f) * 255f);
        }

        Color color(float opacity) {
            return new Color(red, green, blue, Math.clamp(alpha * opacity, 0f, 1f));
        }
    }
}
