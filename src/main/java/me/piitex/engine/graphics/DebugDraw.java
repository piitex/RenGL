package me.piitex.engine.graphics;

import me.piitex.engine.game.GameTime;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.math.Rect;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.jetbrains.skia.Font;
import org.jetbrains.skia.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * Lines, shapes and labels for seeing what the game is doing: hit boxes, rays, paths, velocities, the id above an
 * enemy. Queue them from anywhere in the game code, in the same coordinates as the thing they describe, and draw the
 * queue once a frame:
 * <pre>{@code
 * // fixedUpdate
 * debug.rect(enemy.getHitBox(), Color.RED);
 * debug.arrow(enemy.x, enemy.y, enemy.x + enemy.vx, enemy.y + enemy.vy, Color.YELLOW);
 *
 * // render, after the world
 * debug.draw(renderer, camera);
 * }</pre>
 * With a {@link Camera} the shapes are in world coordinates, but lines, points, arrow heads and text keep the same size
 * on screen at any zoom, and text stays upright when the camera turns. Without one they are in screen coordinates.
 * <p>
 * <b>How long a shape stays:</b>
 * <ul>
 *   <li>Queued in {@link me.piitex.engine.game.Scene#fixedUpdate}: until the next fixed step. It shows on every frame
 *       in between, and stays while the game is paused, so what a step found stays visible without flickering.</li>
 *   <li>Queued anywhere else, such as {@code update}, {@code render} or an input handler: for one frame. Every
 *       {@code draw} in that frame shows it, so a main view and a minimap can both draw one queue.</li>
 *   <li>Queued through {@link #hold(float)}: for at least that many seconds of game time, for events too short to
 *       see, such as a hit or a pick-up.</li>
 * </ul>
 * So queue shapes afresh each step or frame, rather than once.
 * <p>
 * Turn it off with {@link #setEnabled}, and every call returns at once, so debug calls can be left in release code.
 * Render thread only, which includes every {@code Scene} hook.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class DebugDraw {
    private static final int LINE = 0, ARROW = 1, RECT = 2, FILL_RECT = 3, CIRCLE = 4, FILL_CIRCLE = 5,
            POLYGON = 6, POINT = 7, TEXT = 8;
    private static final Color TEXT_SHADOW = new Color(0f, 0f, 0f, 0.75f);

    private final Queue queue;
    private final float seconds;

    /**
     * The shapes and settings, shared by a DebugDraw and the views {@link #hold} makes of it.
     */
    private static final class Queue {
        final List<Shape> shapes = new ArrayList<>();
        final List<Shape> free = new ArrayList<>();
        boolean enabled = true;
        float lineWidth = 1f, pointSize = 3f, textSize = 12f;
        Font font;
        long prunedFrame = -1;
    }

    private static final class Shape {
        int kind;
        float x0, y0, x1, y1;
        float[] points; // POLYGON: count * 2 floats, reused between shapes
        int pointCount;
        String text;
        Color color;
        boolean step;   // queued in a fixed step
        long tick;      // the step it was queued in
        long frame;     // the frame it was queued in
        long drawnFrame = -1;
        double until;   // game time it is held until, or -infinity
    }

    public DebugDraw() {
        this(new Queue(), 0f);
    }

    private DebugDraw(Queue queue, float seconds) {
        this.queue = queue;
        this.seconds = seconds;
    }

    /**
     * A view of this DebugDraw whose shapes stay for at least {@code seconds} of game time, and longer if they would
     * anyway (see the class docs). It shares this one's queue and settings, so draw either. Cheap enough to call
     * inline: {@code debug.hold(1f).circle(x, y, 20, Color.RED)}.
     */
    public DebugDraw hold(float seconds) {
        if (!(seconds >= 0f)) throw new IllegalArgumentException("seconds must be zero or positive: " + seconds);
        return new DebugDraw(queue, seconds);
    }

    // ---- shapes

    /**
     * A line from (x0, y0) to (x1, y1).
     */
    public void line(float x0, float y0, float x1, float y1, Color color) {
        Shape shape = add(LINE, color);
        if (shape != null) set(shape, x0, y0, x1, y1);
    }

    /**
     * A line from (x0, y0) to (x1, y1) with a head at (x1, y1): a direction, a velocity, a force.
     */
    public void arrow(float x0, float y0, float x1, float y1, Color color) {
        Shape shape = add(ARROW, color);
        if (shape != null) set(shape, x0, y0, x1, y1);
    }

    /**
     * The outline of a rectangle with its top-left corner at (x, y).
     */
    public void rect(float x, float y, float width, float height, Color color) {
        Shape shape = add(RECT, color);
        if (shape != null) set(shape, x, y, width, height);
    }

    public void rect(Rect rect, Color color) {
        rect(rect.x(), rect.y(), rect.width(), rect.height(), color);
    }

    /**
     * A filled rectangle. Give the color some transparency to see what is under it.
     */
    public void fillRect(float x, float y, float width, float height, Color color) {
        Shape shape = add(FILL_RECT, color);
        if (shape != null) set(shape, x, y, width, height);
    }

    public void fillRect(Rect rect, Color color) {
        fillRect(rect.x(), rect.y(), rect.width(), rect.height(), color);
    }

    /**
     * The outline of a circle around (x, y).
     */
    public void circle(float x, float y, float radius, Color color) {
        Shape shape = add(CIRCLE, color);
        if (shape != null) set(shape, x, y, radius, 0f);
    }

    public void fillCircle(float x, float y, float radius, Color color) {
        Shape shape = add(FILL_CIRCLE, color);
        if (shape != null) set(shape, x, y, radius, 0f);
    }

    /**
     * The closed outline through the first {@code pointCount} points of {@code points}, packed as
     * {@code x0, y0, x1, y1, ...}: a turned box, a collision polygon, a path. The points are copied.
     */
    public void polygon(float[] points, int pointCount, Color color) {
        if (pointCount < 2 || points.length < pointCount * 2) {
            if (pointCount > 0 && points.length < pointCount * 2) {
                throw new IllegalArgumentException(pointCount + " points need " + pointCount * 2 + " floats, not " + points.length);
            }
            return;
        }
        Shape shape = add(POLYGON, color);
        if (shape == null) return;
        int floats = (pointCount + 1) * 2;
        if (shape.points == null || shape.points.length < floats) shape.points = new float[floats];
        System.arraycopy(points, 0, shape.points, 0, pointCount * 2);
        shape.points[pointCount * 2] = points[0];
        shape.points[pointCount * 2 + 1] = points[1];
        shape.pointCount = pointCount + 1;
    }

    /**
     * A dot at (x, y), {@link #setPointSize point size} across on screen at any zoom.
     */
    public void point(float x, float y, Color color) {
        Shape shape = add(POINT, color);
        if (shape != null) set(shape, x, y, 0f, 0f);
    }

    /**
     * A label with its top-left corner at (x, y), upright and {@link #setTextSize text size} tall on screen at any
     * zoom, in the default font (see {@link me.piitex.engine.ui.text.Font#getDefault()}), with a dark shadow so it
     * reads over anything.
     */
    public void text(float x, float y, String text, Color color) {
        if (text == null || text.isEmpty()) return;
        Shape shape = add(TEXT, color);
        if (shape == null) return;
        set(shape, x, y, 0f, 0f);
        shape.text = text;
    }

    // ---- drawing

    /**
     * Draws the queue in screen coordinates, as they were given.
     */
    public void draw(Renderer renderer) {
        draw(renderer, null);
    }

    /**
     * Draws the queue in world coordinates, through {@code camera} and inside its viewport. Call it outside
     * {@link Camera#begin}/{@link Camera#end}: it does that itself. Null draws in screen coordinates.
     */
    public void draw(Renderer renderer, Camera camera) {
        Queue q = queue;
        if (!q.enabled) return;
        prune();
        if (q.shapes.isEmpty()) return;
        long frame = GameTime.getFrameCount();
        float unit = camera == null ? 1f : 1f / camera.getZoom(); // one screen point, in world units
        float line = q.lineWidth * unit;

        boolean hasText = false;
        if (camera != null) camera.begin(renderer);
        for (Shape s : q.shapes) {
            if (s.drawnFrame < 0) s.drawnFrame = frame;
            switch (s.kind) {
                case LINE -> renderer.drawLine(s.x0, s.y0, s.x1, s.y1, s.color, line);
                case ARROW -> drawArrow(renderer, s, line, unit);
                case RECT -> renderer.drawBorder(s.x0, s.y0, s.x1, s.y1, s.color, line, 0f);
                case FILL_RECT -> renderer.drawQuad(s.x0, s.y0, s.x1, s.y1, s.color, 0f);
                case CIRCLE -> renderer.drawBorder(s.x0 - s.x1, s.y0 - s.x1, s.x1 * 2, s.x1 * 2, s.color, line, s.x1);
                case FILL_CIRCLE -> renderer.drawCircle(s.x0, s.y0, s.x1, s.color);
                case POLYGON -> renderer.drawPolyline(s.points, s.pointCount, s.color, line);
                case POINT -> renderer.drawCircle(s.x0, s.y0, q.pointSize * unit / 2f, s.color);
                case TEXT -> hasText = true;
                default -> {
                }
            }
        }
        if (camera != null) camera.end(renderer);
        if (hasText) drawTexts(renderer, camera);
    }

    /**
     * Text is drawn after the rest, outside the camera's transform, so it stays upright and the same size.
     */
    private void drawTexts(Renderer renderer, Camera camera) {
        Font font = font();
        if (font == null) return;
        if (camera != null) {
            renderer.pushClip(camera.getViewportX(), camera.getViewportY(), camera.getViewportWidth(), camera.getViewportHeight());
        }
        for (Shape s : queue.shapes) {
            if (s.kind != TEXT) continue;
            float x = s.x0, y = s.y0;
            if (camera != null) {
                Vec2 screen = camera.worldToScreen(x, y);
                x = screen.x();
                y = screen.y();
            }
            renderer.drawText(s.text, x + 1f, y + 1f, font, TEXT_SHADOW);
            renderer.drawText(s.text, x, y, font, s.color);
        }
        if (camera != null) renderer.popClip();
    }

    private void drawArrow(Renderer renderer, Shape s, float line, float unit) {
        renderer.drawLine(s.x0, s.y0, s.x1, s.y1, s.color, line);
        float dx = s.x1 - s.x0, dy = s.y1 - s.y0;
        float length = (float) Math.hypot(dx, dy);
        if (length == 0f) return;
        float head = Math.min(8f * unit, length * 0.4f);
        float ux = dx / length, uy = dy / length;
        float backX = s.x1 - ux * head, backY = s.y1 - uy * head;
        float sideX = -uy * head * 0.5f, sideY = ux * head * 0.5f;
        renderer.drawLine(s.x1, s.y1, backX + sideX, backY + sideY, s.color, line);
        renderer.drawLine(s.x1, s.y1, backX - sideX, backY - sideY, s.color, line);
    }

    // ---- settings

    /**
     * Turns debug drawing on or off, on by default. While off, shapes aren't queued and {@link #draw} draws nothing.
     * Turning it off also empties the queue. Shared with every {@link #hold} view.
     */
    public void setEnabled(boolean enabled) {
        queue.enabled = enabled;
        if (!enabled) clear();
    }

    public boolean isEnabled() {
        return queue.enabled;
    }

    /**
     * How thick lines and outlines are, in screen points at any zoom. 1 by default.
     */
    public void setLineWidth(float width) {
        if (!(width > 0f)) throw new IllegalArgumentException("line width must be positive: " + width);
        queue.lineWidth = width;
    }

    public float getLineWidth() {
        return queue.lineWidth;
    }

    /**
     * How wide {@link #point}s are, in screen points at any zoom. 3 by default.
     */
    public void setPointSize(float size) {
        if (!(size > 0f)) throw new IllegalArgumentException("point size must be positive: " + size);
        queue.pointSize = size;
    }

    public float getPointSize() {
        return queue.pointSize;
    }

    /**
     * How tall {@link #text} is, in screen points at any zoom. 12 by default.
     */
    public void setTextSize(float size) {
        if (!(size > 0f)) throw new IllegalArgumentException("text size must be positive: " + size);
        if (size != queue.textSize && queue.font != null) {
            queue.font.close();
            queue.font = null;
        }
        queue.textSize = size;
    }

    public float getTextSize() {
        return queue.textSize;
    }

    /**
     * Removes every queued shape, held ones included.
     */
    public void clear() {
        for (Shape shape : queue.shapes) recycle(shape);
        queue.shapes.clear();
    }

    /**
     * How many shapes are queued, including ones that will go before the next draw.
     */
    public int size() {
        return queue.shapes.size();
    }

    // ---- the queue

    private Shape add(int kind, Color color) {
        Queue q = queue;
        if (!q.enabled || color == null) return null;
        long frame = GameTime.getFrameCount();
        if (frame != q.prunedFrame) prune(); // so a queue that is never drawn doesn't grow forever
        Shape shape = q.free.isEmpty() ? new Shape() : q.free.removeLast();
        shape.kind = kind;
        shape.color = color;
        shape.text = null;
        shape.step = GameTime.isInFixedStep();
        shape.tick = GameTime.getTicks();
        shape.frame = frame;
        shape.drawnFrame = -1;
        shape.until = seconds > 0f ? GameTime.getTime() + seconds : Double.NEGATIVE_INFINITY;
        q.shapes.add(shape);
        return shape;
    }

    private static void set(Shape shape, float x0, float y0, float x1, float y1) {
        shape.x0 = x0;
        shape.y0 = y0;
        shape.x1 = x1;
        shape.y1 = y1;
    }

    /**
     * Drops the shapes whose time is up, keeping the order of the rest.
     */
    private void prune() {
        Queue q = queue;
        long frame = GameTime.getFrameCount();
        long ticks = GameTime.getTicks();
        double time = GameTime.getTime();
        q.prunedFrame = frame;
        int kept = 0;
        for (int i = 0; i < q.shapes.size(); i++) {
            Shape s = q.shapes.get(i);
            if (alive(s, frame, ticks, time)) {
                q.shapes.set(kept++, s);
            } else {
                recycle(s);
            }
        }
        q.shapes.subList(kept, q.shapes.size()).clear();
    }

    private static boolean alive(Shape s, long frame, long ticks, double time) {
        if (time < s.until) return true;
        if (s.step) return ticks <= s.tick + 1; // until the step after the one that queued it has run
        // One frame: every draw of the frame it is first drawn in. Input arrives before the frame starts counting,
        // so a shape queued then is still waiting for its first draw one frame later.
        return s.drawnFrame < 0 ? s.frame >= frame - 1 : s.drawnFrame == frame;
    }

    private void recycle(Shape shape) {
        shape.color = null;
        shape.text = null;
        queue.free.add(shape);
    }

    private Font font() {
        Queue q = queue;
        if (q.font == null) {
            Typeface typeface = me.piitex.engine.ui.text.Font.getDefault();
            if (typeface == null) return null;
            q.font = new Font(typeface, q.textSize);
        }
        return q.font;
    }
}
