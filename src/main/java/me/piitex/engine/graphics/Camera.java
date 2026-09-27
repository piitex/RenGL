package me.piitex.engine.graphics;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.math.Rect;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * A view of a 2D world: which point of the world is in the middle of the screen, how far it is zoomed in, how it is
 * turned, and which part of the window it is shown in. Everything drawn between {@link #begin} and {@link #end} is in
 * world coordinates.
 * <pre>{@code
 * private final Camera camera = new Camera();
 *
 * protected void onEnter() {
 *     camera.setViewport(0, 0, (float) getContainer().getWidth(), (float) getContainer().getHeight());
 * }
 *
 * protected void onResize(int width, int height) {
 *     camera.setViewport(0, 0, width, height);
 * }
 *
 * protected void render(Renderer renderer, float alpha) {
 *     camera.setPosition(player.drawX(alpha), player.drawY(alpha)); // keep the player in the middle
 *     camera.begin(renderer);
 *     world.draw(renderer);                                        // world coordinates
 *     camera.end(renderer);
 * }
 * }</pre>
 * The camera only holds the view. How it moves, whether it follows the player smoothly, shakes, or stays inside the
 * level, is up to the game, which sets {@link #setPosition position}, {@link #setZoom zoom} and
 * {@link #setRotation rotation} whenever it likes.
 * <p>
 * <b>World units</b> are whatever the game uses. At a zoom of 1 one unit covers one point of the window; a game that
 * measures in tiles or metres can zoom by the number of points one of its units should cover.
 * <p>
 * <b>Several cameras</b> can draw in the same frame, each into its own {@link #setViewport viewport}: two halves of the
 * window for split screen, or a small corner for a minimap. Drawing is clipped to the viewport.
 * <p>
 * <b>Screen coordinates</b> here are window coordinates, the ones mouse positions and {@link me.piitex.engine.game.Scene#render}
 * use. {@link #screenToWorld} turns a mouse position into the world point under it.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class Camera {
    private float x, y;
    private float zoom = 1f;
    private float rotation;
    private float viewportX, viewportY, viewportWidth, viewportHeight;
    private boolean pixelSnap;

    /**
     * A camera with an empty viewport: set one with {@link #setViewport} before drawing.
     */
    public Camera() {
    }

    /**
     * A camera whose viewport covers {@code viewportWidth} by {@code viewportHeight} points from the window's top-left
     * corner.
     */
    public Camera(float viewportWidth, float viewportHeight) {
        setViewport(0f, 0f, viewportWidth, viewportHeight);
    }

    /**
     * Starts drawing through the camera: clips to the viewport and maps world coordinates onto it, until
     * {@link #end}. Pairs nest like the renderer's other pushes.
     */
    public void begin(Renderer renderer) {
        renderer.pushClip(viewportX, viewportY, viewportWidth, viewportHeight);
        renderer.pushTransform(offsetX(), offsetY(), -rotation, zoom, zoom, 0f, 0f);
    }

    /**
     * Ends what {@link #begin} started.
     */
    public void end(Renderer renderer) {
        renderer.popTransform();
        renderer.popClip();
    }

    /**
     * Where the world point ({@code worldX}, {@code worldY}) is drawn in the window.
     */
    public Vec2 worldToScreen(float worldX, float worldY) {
        double radians = Math.toRadians(-rotation);
        float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
        float scaledX = worldX * zoom, scaledY = worldY * zoom;
        return new Vec2(offsetX() + scaledX * cos - scaledY * sin, offsetY() + scaledX * sin + scaledY * cos);
    }

    /**
     * The world point drawn at ({@code screenX}, {@code screenY}) in the window, such as the one under the mouse.
     */
    public Vec2 screenToWorld(float screenX, float screenY) {
        double radians = Math.toRadians(rotation);
        float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
        float dx = screenX - offsetX(), dy = screenY - offsetY();
        return new Vec2((dx * cos - dy * sin) / zoom, (dx * sin + dy * cos) / zoom);
    }

    /**
     * The part of the world the viewport shows, as the smallest upright rectangle around it: exact when the camera
     * isn't rotated, a little larger when it is. Pass it to {@link SpriteBatch#draw(Renderer, Rect)} to skip sprites
     * out of view.
     */
    public Rect getVisibleBounds() {
        Vec2 a = screenToWorld(viewportX, viewportY);
        Vec2 b = screenToWorld(viewportX + viewportWidth, viewportY);
        Vec2 c = screenToWorld(viewportX + viewportWidth, viewportY + viewportHeight);
        Vec2 d = screenToWorld(viewportX, viewportY + viewportHeight);
        float minX = Math.min(Math.min(a.x(), b.x()), Math.min(c.x(), d.x()));
        float maxX = Math.max(Math.max(a.x(), b.x()), Math.max(c.x(), d.x()));
        float minY = Math.min(Math.min(a.y(), b.y()), Math.min(c.y(), d.y()));
        float maxY = Math.max(Math.max(a.y(), b.y()), Math.max(c.y(), d.y()));
        return new Rect(minX, minY, maxX - minX, maxY - minY);
    }

    // Where the world's (0, 0) is drawn: the viewport's center, less the camera position turned and zoomed.
    private float offsetX() {
        double radians = Math.toRadians(-rotation);
        float offset = viewportX + viewportWidth / 2f - (x * (float) Math.cos(radians) - y * (float) Math.sin(radians)) * zoom;
        return pixelSnap ? Math.round(offset) : offset;
    }

    private float offsetY() {
        double radians = Math.toRadians(-rotation);
        float offset = viewportY + viewportHeight / 2f - (x * (float) Math.sin(radians) + y * (float) Math.cos(radians)) * zoom;
        return pixelSnap ? Math.round(offset) : offset;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    /**
     * The world point shown in the middle of the viewport.
     */
    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    /**
     * Moves the camera by ({@code dx}, {@code dy}) world units.
     */
    public void move(float dx, float dy) {
        this.x += dx;
        this.y += dy;
    }

    public float getZoom() {
        return zoom;
    }

    /**
     * How many window points one world unit covers, 1 by default: 2 shows everything twice as big, 0.5 half as big.
     * The middle of the viewport stays on the same world point.
     */
    public void setZoom(float zoom) {
        if (!(zoom > 0f) || !Float.isFinite(zoom)) throw new IllegalArgumentException("zoom must be above 0: " + zoom);
        this.zoom = zoom;
    }

    public float getRotation() {
        return rotation;
    }

    /**
     * How far the camera is turned, in degrees clockwise, around the middle of the viewport. Turning the camera
     * clockwise turns the world the other way on screen.
     */
    public void setRotation(float rotation) {
        this.rotation = rotation;
    }

    /**
     * Shows the camera's view in the rectangle ({@code x}, {@code y}, {@code width}, {@code height}) of the window.
     * A camera that fills the window should update this when the window is resized, in
     * {@link me.piitex.engine.game.Scene#onResize}.
     */
    public void setViewport(float x, float y, float width, float height) {
        if (width < 0f || height < 0f) throw new IllegalArgumentException("viewport size must not be negative: " + width + "x" + height);
        this.viewportX = x;
        this.viewportY = y;
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    public float getViewportX() {
        return viewportX;
    }

    public float getViewportY() {
        return viewportY;
    }

    public float getViewportWidth() {
        return viewportWidth;
    }

    public float getViewportHeight() {
        return viewportHeight;
    }

    public boolean isPixelSnap() {
        return pixelSnap;
    }

    /**
     * Rounds the view's offset to whole window points, false by default. With pixel art at a whole-number zoom, this
     * keeps the art's pixels on the screen's pixel grid while the camera moves, instead of shimmering between them.
     */
    public void setPixelSnap(boolean pixelSnap) {
        this.pixelSnap = pixelSnap;
    }
}
