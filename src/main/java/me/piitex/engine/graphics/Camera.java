package me.piitex.engine.graphics;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.math.Rect;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.utils.flags.GeneratedClass;
import me.piitex.engine.utils.flags.GeneratedFragments;

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
 * The game moves the camera whenever it likes, with {@link #setPosition position}, {@link #setZoom zoom} and
 * {@link #setRotation rotation}. A few helpers cover the usual moves:
 * <ul>
 *   <li>{@link #follow} eases towards a target, such as the player, the same way at any frame rate, and
 *   {@link #setDeadzone a deadzone} lets the target move a little before the camera does;</li>
 *   <li>{@link #shake} knocks the view about for a moment, on top of wherever it is aimed. Call {@link #update} every
 *   frame so the shake moves and dies away;</li>
 *   <li>{@link #setBounds bounds} keep the view inside the level.</li>
 * </ul>
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
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class Camera {
    private float x, y;
    private float zoom = 1f;
    private float rotation;
    private float viewportX, viewportY, viewportWidth, viewportHeight;
    private boolean pixelSnap;
    private float deadzoneWidth, deadzoneHeight;
    private Rect bounds;
    // Shake: trauma from 0 to 1, how it shows, and where the shake is now (set by update).
    private float trauma;
    private float maxShakeOffset = 20f, maxShakeRotation = 2f, shakeRecovery = 1.5f;
    private double shakeTime; // a double, so the shake stays smooth however long the game runs
    private float shakeOffsetX, shakeOffsetY, shakeRotation;

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
        renderer.pushTransform(offsetX(), offsetY(), -viewRotation(), zoom, zoom, 0f, 0f);
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
        double radians = Math.toRadians(-viewRotation());
        float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
        float scaledX = worldX * zoom, scaledY = worldY * zoom;
        return new Vec2(offsetX() + scaledX * cos - scaledY * sin, offsetY() + scaledX * sin + scaledY * cos);
    }

    /**
     * The world point drawn at ({@code screenX}, {@code screenY}) in the window, such as the one under the mouse.
     */
    public Vec2 screenToWorld(float screenX, float screenY) {
        double radians = Math.toRadians(viewRotation());
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

    // Where the world's (0, 0) is drawn: the viewport's center, less the view's position turned and zoomed, plus the
    // shake.
    private float offsetX() {
        double radians = Math.toRadians(-viewRotation());
        float viewX = viewX(), viewY = viewY();
        float offset = viewportX + viewportWidth / 2f + shakeOffsetX
                - (viewX * (float) Math.cos(radians) - viewY * (float) Math.sin(radians)) * zoom;
        return pixelSnap ? Math.round(offset) : offset;
    }

    private float offsetY() {
        double radians = Math.toRadians(-viewRotation());
        float viewX = viewX(), viewY = viewY();
        float offset = viewportY + viewportHeight / 2f + shakeOffsetY
                - (viewX * (float) Math.sin(radians) + viewY * (float) Math.cos(radians)) * zoom;
        return pixelSnap ? Math.round(offset) : offset;
    }

    // The point shown in the middle, kept inside the bounds.
    private float viewX() {
        return bounds == null ? x : keepInside(x, bounds.x(), bounds.width(), viewportWidth / 2f / zoom);
    }

    private float viewY() {
        return bounds == null ? y : keepInside(y, bounds.y(), bounds.height(), viewportHeight / 2f / zoom);
    }

    private static float keepInside(float center, float start, float length, float halfView) {
        if (length <= halfView * 2f) return start + length / 2f; // the level is smaller than the view: center it
        return Math.clamp(center, start + halfView, start + length - halfView);
    }

    private float viewRotation() {
        return rotation + shakeRotation;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    /**
     * The world point shown in the middle of the viewport. With {@link #setBounds bounds} it is where the camera is
     * aimed, and the view stops at the edges of the bounds when this is too close to them.
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

    /**
     * Moves the camera part of the way towards ({@code targetX}, {@code targetY}), so that calling it every frame eases
     * in on the target: the gap shrinks by a factor of e every {@code 1 / rate} seconds, the same at any frame rate.
     * About 5 keeps up closely, 2 drifts behind. With a {@link #setDeadzone deadzone}, the camera stays put while the
     * target is inside it, and follows only the part that goes past its edges.
     * <pre>{@code
     * camera.follow(player.drawX(alpha), player.drawY(alpha), 6f, delta);
     * }</pre>
     * Follow where the target is drawn this frame rather than where the last fixed step left it, or the target
     * seems to shake. To jump straight to a point instead, use {@link #setPosition}.
     */
    public void follow(float targetX, float targetY, float rate, float delta) {
        float halfWidth = deadzoneWidth / 2f / zoom, halfHeight = deadzoneHeight / 2f / zoom;
        float goalX = Math.clamp(x, targetX - halfWidth, targetX + halfWidth);
        float goalY = Math.clamp(y, targetY - halfHeight, targetY + halfHeight);
        float share = 1f - (float) Math.exp(-rate * delta);
        x += (goalX - x) * share;
        y += (goalY - y) * share;
    }

    public float getDeadzoneWidth() {
        return deadzoneWidth;
    }

    public float getDeadzoneHeight() {
        return deadzoneHeight;
    }

    /**
     * A box around the middle of the view, {@code width} by {@code height} window points, that a {@link #follow
     * followed} target can move in without the camera moving. 0 by 0, the default, follows every move. A platformer
     * might use a box a little wider than the player, so small steps back and forth leave the view still.
     */
    public void setDeadzone(float width, float height) {
        if (width < 0f || height < 0f) throw new IllegalArgumentException("deadzone size must not be negative: " + width + "x" + height);
        this.deadzoneWidth = width;
        this.deadzoneHeight = height;
    }

    public Rect getBounds() {
        return bounds;
    }

    /**
     * Keeps the view inside {@code bounds}, in world units, such as the level's size: however the camera is aimed,
     * the view stops at the edges, and a level smaller than the view is shown in its middle. Null, the default,
     * lets the view go anywhere. The position is left as it is set, and only what is shown stops.
     * <p>
     * The bounds hold the upright view. A turned camera, or the {@link #shake}, can show a little past them, so draw
     * the level a few units larger if that must never show.
     */
    public void setBounds(Rect bounds) {
        this.bounds = bounds;
    }

    // ---- Shake

    /**
     * Knocks the view about: adds {@code trauma} to the camera's trauma, which is capped at 1 and dies away by itself
     * in {@link #update}. The view moves and turns by the square of the trauma, so small knocks stay small and big
     * ones add up to a real shake: 0.1 for a shot, 0.3 for a hit, 0.6 for an explosion nearby, 1 for the biggest.
     * The shake is on top of the position, which it leaves as it is.
     */
    public void shake(float trauma) {
        this.trauma = Math.clamp(this.trauma + trauma, 0f, 1f);
    }

    public float getTrauma() {
        return trauma;
    }

    /**
     * Sets the trauma outright, from 0 to 1: 0 stops a shake at once, such as when a level restarts.
     */
    public void setTrauma(float trauma) {
        this.trauma = Math.clamp(trauma, 0f, 1f);
        if (this.trauma == 0f) shakeOffsetX = shakeOffsetY = shakeRotation = 0f;
    }

    /**
     * Moves the shake on by {@code delta} seconds and lets the trauma die away. Call it once a frame. Pass real time,
     * {@link me.piitex.engine.game.GameTime#getRealDelta()}, for a shake that carries on in slow motion and pauses, or
     * the scene's delta for one that slows down with the game.
     */
    public void update(float delta) {
        trauma = Math.max(0f, trauma - shakeRecovery * delta);
        shakeTime += delta;
        float strength = trauma * trauma;
        if (strength == 0f) {
            shakeOffsetX = shakeOffsetY = shakeRotation = 0f;
            return;
        }
        // Sines at unrelated rates stand in for noise: smooth, and never visibly repeating.
        double time = shakeTime;
        shakeOffsetX = (float) (Math.sin(time * 37.1) + Math.sin(time * 23.3 + 1.7)) * 0.5f * strength * maxShakeOffset;
        shakeOffsetY = (float) (Math.sin(time * 31.7 + 0.5) + Math.sin(time * 19.9 + 2.9)) * 0.5f * strength * maxShakeOffset;
        shakeRotation = (float) Math.sin(time * 27.3 + 4.1) * strength * maxShakeRotation;
    }

    public float getMaxShakeOffset() {
        return maxShakeOffset;
    }

    /**
     * How far the view moves at full trauma, in window points: 20 by default. It doesn't change with the zoom.
     */
    public void setMaxShakeOffset(float points) {
        this.maxShakeOffset = points;
    }

    public float getMaxShakeRotation() {
        return maxShakeRotation;
    }

    /**
     * How far the view turns at full trauma, in degrees: 2 by default. 0 shakes without turning.
     */
    public void setMaxShakeRotation(float degrees) {
        this.maxShakeRotation = degrees;
    }

    public float getShakeRecovery() {
        return shakeRecovery;
    }

    /**
     * How much trauma dies away each second: 1.5 by default, so the biggest shake settles in two-thirds of a second.
     */
    public void setShakeRecovery(float perSecond) {
        this.shakeRecovery = perSecond;
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
     * clockwise turns the world the other way on screen. The {@link #shake} turns the view on top of this.
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
