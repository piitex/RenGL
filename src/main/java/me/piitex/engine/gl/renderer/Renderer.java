package me.piitex.engine.gl.renderer;

import me.piitex.engine.gl.renderer.metal.MetalSkikoRenderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import org.jetbrains.skia.Font;
import org.jetbrains.skia.Image;

/**
 * The engine's drawing surface for a single frame, backed by Skia through either the
 * OpenGL path ({@link SkikoRenderer}) or the Metal path ({@link MetalSkikoRenderer} on
 * macOS). Elements draw themselves by calling these methods once per frame,
 * between {@link #begin()} and {@link #end()}; they never touch Skia or the
 * underlying graphics API directly.
 * <p>
 * <b>Coordinate space:</b> every method here takes top-left-origin coordinates in
 * logical (point) space, the same space {@link me.piitex.engine.ui.Element}'s x, y,
 * width, and height live in. Retina and HiDPI scaling to physical pixels is handled
 * internally by the renderer implementation during {@link #begin()}; callers never
 * need to think about backing scale.
 * <p>
 * <b>Threading:</b> every method on this interface must only be called from the
 * render thread, between a matching {@link #begin()}/{@link #end()} pair for the
 * current frame. None of these methods are safe to call from the main thread or
 * from an input/update callback.
 * <p>
 * <b>Color vs. Paint:</b> {@link #drawQuad} and {@link #drawBorder} take a plain
 * {@link Color}, meaning solid fills only. {@link #drawText} takes the broader
 * {@link Paint} type so it can also accept a
 * {@link me.piitex.engine.ui.color.LinearGradient} or an animated
 * {@link me.piitex.engine.ui.color.ScrollingGradient}. Gradient quads go through the
 * separate {@link #drawGradient} method rather than an overload of drawQuad, because a
 * gradient fill needs two colors and the caller is what resolves a quad background to
 * either a Color or a LinearGradient. See {@link me.piitex.engine.ui.Element#render}.
 */
public interface Renderer {

    /**
     * Starts a new frame: acquires/clears the drawable (Metal) or clears the bound
     * framebuffer (GL), and prepares the canvas for drawing. Must be paired with a
     * matching {@link #end()} later in the same frame. No draw method below is valid
     * to call before this returns.
     */
    void begin();

    /**
     * Finishes the current frame: flushes and submits all drawing done since
     * {@link #begin()}, then presents it (Metal) or leaves the framebuffer ready for
     * {@code glfwSwapBuffers} (GL). After this call, the canvas from this frame is no
     * longer valid, and draw methods must not be called again until the next
     * {@link #begin()}.
     */
    void end();

    /**
     * Clears the current framebuffer to a solid color. On the GL backend this issues
     * a real {@code glClear}; on Metal this is a no-op; the clear color there is
     * applied automatically at the start of {@link #begin()} instead, since Metal has
     * no equivalent of clearing mid-frame on an already-acquired drawable.
     */
    void clear(float r, float g, float b, float a);

    /**
     * Sets the GL viewport in physical pixels. This is meaningful only on the OpenGL
     * backend. Metal has no concept of a viewport separate from the drawable's own size,
     * so {@link MetalSkikoRenderer}'s implementation is a no-op. Resize handling for
     * Metal goes through {@code MetalSkikoRenderer.onResize(...)} instead.
     */
    void setViewport(int x, int y, int width, int height);

    /**
     * Draws a solid-color filled rectangle with its top-left corner at (x, y).
     * Passing a {@code cornerRadius > 0} draws a rounded rectangle instead of a sharp
     * one; passing a null {@code color} is a no-op (nothing is drawn) rather than an
     * error, so callers don't need to null-check before calling.
     */
    void drawQuad(float x, float y, float width, float height, Color color, float cornerRadius);

    /**
     * Draws a solid-color stroked outline (not filled) around a rectangle with its
     * top-left corner at (x, y), using the same corner-radius rules as
     * {@link #drawQuad}. A null {@code color} or a {@code thickness <= 0} is a no-op.
     */
    void drawBorder(float x, float y, float width, float height, Color color, float thickness, float cornerRadius);

    /**
     * Draws a solid-color line segment from (x0, y0) to (x1, y1) at {@code thickness}
     * points wide. A null {@code color} or a {@code thickness <= 0} is a no-op, the
     * same convention {@link #drawBorder} uses.
     */
    void drawLine(float x0, float y0, float x1, float y1, Color color, float thickness);

    /**
     * Draws a connected, open line through the first {@code pointCount} points of
     * {@code points}, packed as {@code x0, y0, x1, y1, ...}, with round joins and caps.
     * Unlike a chain of {@link #drawLine} calls, a thick translucent polyline has no
     * notches or doubled alpha where segments meet, which matters for jagged shapes such
     * as {@link me.piitex.engine.ui.background.ElectricNodeBackground}'s lightning. A null
     * {@code color}, a {@code thickness <= 0}, or fewer than two points is a no-op.
     * <p>
     * The default implementation falls back to one {@link #drawLine} per segment, so a
     * custom Renderer keeps working without overriding this.
     */
    default void drawPolyline(float[] points, int pointCount, Color color, float thickness) {
        if (color == null || thickness <= 0 || pointCount < 2) return;
        for (int i = 1; i < pointCount; i++) {
            drawLine(points[i * 2 - 2], points[i * 2 - 1], points[i * 2], points[i * 2 + 1], color, thickness);
        }
    }

    /**
     * Draws a solid-color filled circle centered at (x, y) with the given {@code
     * radius}. A null {@code color} or a non-positive {@code radius} is a no-op, the
     * same convention {@link #drawQuad} uses for a null color.
     */
    void drawCircle(float x, float y, float radius, Color color);

    /**
     * Draws a single line of text with its top-left corner at (x, y).
     * Baseline positioning (Skia's native drawString origin) is handled internally
     * using the Font's metrics, so callers always work in top-left coordinates like
     * every other draw method here.
     * <p>
     * {@code color} accepts either a plain {@link Color} (solid fill) or a
     * {@link me.piitex.engine.ui.color.LinearGradient}/{@link me.piitex.engine.ui.color.ScrollingGradient},
     * in which case the gradient is stretched across the text's own measured
     * width/height rather than the element's full bounding box.
     */
    void drawText(String text, float x, float y, Font font, Paint color);

    /**
     * Draws a two-color linear-gradient filled rectangle with its top-left corner at
     * (x, y), spanning from {@code gradient}'s start color at (x, y) to its end color
     * at (x + width, y + height). Uses the same corner-radius rules as {@link #drawQuad}.
     * <p>
     * Takes the {@link me.piitex.engine.ui.color.LinearGradient} object itself, not
     * just its two colors, specifically so a {@link
     * me.piitex.engine.ui.color.ScrollingGradient} passed here scrolls. See
     * {@link me.piitex.engine.ui.color.GradientUtil#buildShader}, which every
     * implementation of this method should build its shader through. Passing a fresh
     * {@code new LinearGradient(start, end)} in place of the real, possibly animated
     * instance the caller holds renders a scrolling gradient as a static one, so always
     * pass the original object through.
     */
    void drawGradient(float x, float y, float width, float height, LinearGradient gradient, float cornerRadius);

    /**
     * Same as {@link #drawImage(Image, float, float, float, float, float, float, Color)} with no tint, so the image draws with its own colors unchanged.
     */
    default void drawImage(Image image, float x, float y, float width, float height, float cornerRadius, float opacity) {
        drawImage(image, x, y, width, height, cornerRadius, opacity, null);
    }

    /**
     * Draws {@code image} scaled to fill a (x, y, width, height) rectangle, using the
     * same corner-radius rules as {@link #drawQuad} for rounded corners. {@code
     * opacity} in [0, 1] fades the whole image uniformly (1 = fully opaque, 0 =
     * invisible); values outside that range are clamped. A null {@code image} is a
     * no-op, matching {@link #drawQuad}/{@link #drawBorder}'s null-{@link Color}
     * convention.
     * <p>
     * A non-null {@code tint} recolors the image's RGB to that color while keeping its
     * own alpha as a mask, using Skia's SRC_IN blend. This is the standard way to
     * recolor a monochrome asset, which most icon packs are, at draw time rather than
     * baking a fixed color into the file. Pass null to draw the image with its own
     * colors as they are.
     */
    void drawImage(Image image, float x, float y, float width, float height, float cornerRadius, float opacity, Color tint);

    /**
     * Pushes an offscreen layer onto the canvas's save stack. Everything drawn after
     * this call, until the matching {@link #popOpacityLayer()}, is composited as one
     * unit and blended onto whatever is beneath it at {@code opacity}, clamped to
     * [0, 1], when popped. This is what makes a true fade possible: drawing a
     * background, a border, and text each at a reduced alpha independently would
     * double-blend wherever they overlap and still show each one separately once fully
     * drawn, rather than fading the group together. Must be paired with exactly one
     * {@link #popOpacityLayer()}.
     */
    void pushOpacityLayer(float x, float y, float width, float height, float opacity);

    /**
     * Pops the layer most recently pushed by {@link #pushOpacityLayer}, compositing
     * everything drawn since then onto the canvas beneath it at that layer's opacity.
     */
    void popOpacityLayer();

    /**
     * Pushes a rectangular clip onto the canvas's save stack. Everything drawn after
     * this call, until the matching {@link #popClip()}, is clipped to this rectangle.
     * Must be paired with exactly one {@link #popClip()}; an unmatched pushClip leaves
     * the canvas's clip state wrong for the rest of the frame.
     */
    void pushClip(float x, float y, float width, float height);

    /**
     * As {@link #pushClip(float, float, float, float)} with rounded corners. Pair it with {@link #popClip()}.
     * Renderers that can't round the clip fall back to the plain rectangle.
     */
    default void pushClip(float x, float y, float width, float height, float cornerRadius) {
        pushClip(x, y, width, height);
    }

    /**
     * Pops the clip most recently pushed by {@link #pushClip}, restoring the canvas
     * to whatever clip (or lack of one) was in effect before it.
     */
    void popClip();

    /**
     * Moves the canvas's origin by (dx, dy) for everything drawn after this call, until the matching {@link
     * #popTranslate()}. This is how a container draws its children in its own coordinate space: children are
     * drawn at their position relative to the container, and this shifts that space to where the container is.
     * Nests, and must be paired with exactly one {@link #popTranslate()}.
     */
    void pushTranslate(float dx, float dy);

    /**
     * Pops the translation most recently pushed by {@link #pushTranslate}.
     */
    void popTranslate();

    /**
     * Scales everything drawn after this call by {@code scale} around the point ({@code pivotX}, {@code pivotY}), in the
     * current coordinate space, until the matching {@link #popScale()}. The pivot stays where it is on screen. Nests,
     * and must be paired with exactly one {@link #popScale()}.
     */
    void pushScale(float scale, float pivotX, float pivotY);

    /**
     * Pops the scale most recently pushed by {@link #pushScale}.
     */
    void popScale();
}
