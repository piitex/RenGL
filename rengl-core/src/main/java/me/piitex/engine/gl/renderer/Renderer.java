package me.piitex.engine.gl.renderer;

import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.gl.renderer.metal.MetalSkikoRenderer;
import me.piitex.engine.graphics.ShaderEffect;
import me.piitex.engine.math.Polygon;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.text.TextAlign;
import me.piitex.engine.ui.text.TextLayout;
import me.piitex.engine.ui.text.TextShadow;
import me.piitex.engine.utils.flags.GeneratedFragments;
import org.jetbrains.skia.Font;
import org.jetbrains.skia.Image;

import java.util.Arrays;
import java.util.List;

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
 * {@link me.piitex.engine.ui.color.ScrollingGradient}. Gradient quads and outlines go
 * through {@link #drawGradient} and {@link #drawGradientBorder}. The
 * {@link #drawQuad(float, float, float, float, Paint, float)} and
 * {@link #drawBorder(float, float, float, float, Paint, float, float)} overloads take
 * any Paint and pick the right call, and so do
 * {@link #drawLine(float, float, float, float, Paint, float)} and
 * {@link #drawCircle(float, float, float, Paint)}. See {@link me.piitex.engine.ui.Element#render}.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
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
     * As {@link #drawPolyline(float[], int, Color, float)} in any {@link Paint}. A {@link LinearGradient} runs along the
     * line itself, its first color at the first point and its last at the end, whatever the line's shape; each segment
     * is drawn in the color of its middle. A null paint, a {@code thickness <= 0} or fewer than two points is a no-op.
     */
    default void drawPolyline(float[] points, int pointCount, Paint paint, float thickness) {
        paint = AnimatedPaint.resolve(paint);
        if (paint instanceof Color color) {
            drawPolyline(points, pointCount, color, thickness);
        } else if (paint instanceof LinearGradient gradient && thickness > 0 && pointCount >= 2) {
            float total = 0f;
            for (int i = 1; i < pointCount; i++) {
                total += (float) Math.hypot(points[i * 2] - points[i * 2 - 2], points[i * 2 + 1] - points[i * 2 - 1]);
            }
            if (total <= 0f) return;
            float done = 0f;
            for (int i = 1; i < pointCount; i++) {
                float length = (float) Math.hypot(points[i * 2] - points[i * 2 - 2], points[i * 2 + 1] - points[i * 2 - 1]);
                drawLine(points[i * 2 - 2], points[i * 2 - 1], points[i * 2], points[i * 2 + 1],
                        gradient.colorAt((done + length / 2f) / total), thickness);
                done += length;
            }
        }
    }

    /**
     * Fills the polygon through the first {@code pointCount} points of {@code points}, packed as
     * {@code x0, y0, x1, y1, ...} and closed back to the first. Any outline works: convex, concave (an arrow, a
     * shield) or crossing itself. Where it crosses itself, the non-zero rule decides what is inside, so a five-point
     * star drawn in one stroke is filled solid. A null {@code color} or fewer than three points is a no-op.
     * <p>
     * The default implementation fills it one row of points at a time with {@link #drawQuad}, with no anti-aliasing,
     * so a custom Renderer keeps working without overriding this.
     */
    default void drawPolygon(float[] points, int pointCount, Color color) {
        if (color == null || pointCount < 3) return;
        float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (int i = 0; i < pointCount; i++) {
            top = Math.min(top, points[i * 2 + 1]);
            bottom = Math.max(bottom, points[i * 2 + 1]);
        }
        float[] crossings = new float[pointCount];
        int[] directions = new int[pointCount];
        for (int row = (int) Math.floor(top); row < bottom; row++) {
            float y = row + 0.5f;
            int count = 0;
            for (int i = 0; i < pointCount; i++) {
                int j = (i + 1) % pointCount;
                float y0 = points[i * 2 + 1], y1 = points[j * 2 + 1];
                if ((y0 <= y) == (y1 <= y)) continue; // this edge doesn't cross the row
                float x0 = points[i * 2], x1 = points[j * 2];
                float x = x0 + (y - y0) / (y1 - y0) * (x1 - x0);
                int direction = y1 > y0 ? 1 : -1;
                // Insertion sort by x: few edges cross any one row.
                int k = count++;
                while (k > 0 && crossings[k - 1] > x) {
                    crossings[k] = crossings[k - 1];
                    directions[k] = directions[k - 1];
                    k--;
                }
                crossings[k] = x;
                directions[k] = direction;
            }
            int winding = 0;
            for (int k = 0; k < count - 1; k++) {
                winding += directions[k];
                if (winding != 0 && crossings[k + 1] > crossings[k]) {
                    drawQuad(crossings[k], row, crossings[k + 1] - crossings[k], 1f, color, 0f);
                }
            }
        }
    }

    /**
     * The closed outline of the polygon through the first {@code pointCount} points of {@code points}, packed as
     * {@code x0, y0, x1, y1, ...}, with sharp corners. The line is centered on the outline. A null {@code color}, a
     * {@code thickness <= 0} or fewer than two points is a no-op.
     * <p>
     * The default implementation draws it with {@link #drawPolyline}, back to the first point.
     */
    default void drawPolygonOutline(float[] points, int pointCount, Color color, float thickness) {
        if (color == null || thickness <= 0 || pointCount < 2) return;
        float[] closed = Arrays.copyOf(points, pointCount * 2 + 2);
        closed[pointCount * 2] = points[0];
        closed[pointCount * 2 + 1] = points[1];
        drawPolyline(closed, pointCount + 1, color, thickness);
    }

    /**
     * Fills a {@link Polygon}, such as a hit box. See {@link #drawPolygon(float[], int, Color)}.
     */
    default void drawPolygon(Polygon polygon, Color color) {
        if (polygon != null) drawPolygon(polygon.getPoints(), polygon.getPointCount(), color);
    }

    /**
     * The outline of a {@link Polygon}. See {@link #drawPolygonOutline(float[], int, Color, float)}.
     */
    default void drawPolygonOutline(Polygon polygon, Color color, float thickness) {
        if (polygon != null) drawPolygonOutline(polygon.getPoints(), polygon.getPointCount(), color, thickness);
    }

    /**
     * Fills a regular polygon: {@code sides} corners on a circle of {@code radius} around (centerX, centerY), the
     * first straight right of the center and turned clockwise by {@code degrees}, as in {@link Polygon#regular}. 3
     * sides is a triangle, 6 a hexagon. Fewer than 3 sides or a {@code radius <= 0} is a no-op.
     */
    default void drawRegularPolygon(float centerX, float centerY, float radius, int sides, float degrees, Color color) {
        if (sides < 3 || radius <= 0) return;
        drawPolygon(regularPolygon(centerX, centerY, radius, sides, degrees), sides, color);
    }

    /**
     * The outline of a regular polygon. See {@link #drawRegularPolygon} and {@link #drawPolygonOutline(float[], int, Color, float)}.
     */
    default void drawRegularPolygonOutline(float centerX, float centerY, float radius, int sides, float degrees, Color color, float thickness) {
        if (sides < 3 || radius <= 0) return;
        drawPolygonOutline(regularPolygon(centerX, centerY, radius, sides, degrees), sides, color, thickness);
    }

    private static float[] regularPolygon(float centerX, float centerY, float radius, int sides, float degrees) {
        float[] points = new float[sides * 2];
        double start = Math.toRadians(degrees);
        for (int i = 0; i < sides; i++) {
            double angle = start + Math.PI * 2 * i / sides;
            points[i * 2] = centerX + radius * (float) Math.cos(angle);
            points[i * 2 + 1] = centerY + radius * (float) Math.sin(angle);
        }
        return points;
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
     * As {@link #drawText(String, float, float, Font, Paint)}, with {@code x} as the text's left edge, its center or
     * its right edge, as {@code align} says. So {@code drawText(title, width / 2f, 40f, font, color, TextAlign.CENTER)}
     * centers a title across the window. A null {@code align} is {@link TextAlign#LEFT}. {@code y} is the top.
     */
    default void drawText(String text, float x, float y, Font font, Paint color, TextAlign align) {
        if (text == null || text.isEmpty() || font == null) return;
        float left = align == null || align == TextAlign.LEFT ? x : x - align.fraction() * me.piitex.engine.ui.text.TextShaping.width(font, text);
        drawText(text, left, y, font, color);
    }

    /**
     * As {@link #drawText(String, float, float, Font, Paint, TextAlign)}, over {@code shadow}: the same text in the
     * shadow's color, moved by its offset. A null {@code shadow} draws none.
     */
    default void drawText(String text, float x, float y, Font font, Paint color, TextAlign align, TextShadow shadow) {
        if (shadow != null && shadow.color() != null) {
            drawText(text, x + shadow.offsetX(), y + shadow.offsetY(), font, shadow.color(), align);
        }
        drawText(text, x, y, font, color, align);
    }

    /**
     * Draws {@code lines} one below the other, the first with its top at {@code y} and each next one
     * {@code lineHeight} lower, each placed at {@code x} as {@code align} says. For text broken into lines by
     * {@link TextLayout#wrap}; {@link TextLayout#lineHeight} is a natural {@code lineHeight}. Returns the y just below
     * the last line ({@code y + lines.size() * lineHeight}), where whatever follows can go.
     */
    default float drawTextLines(List<String> lines, float x, float y, float lineHeight, Font font, Paint color, TextAlign align) {
        for (String line : lines) {
            drawText(line, x, y, font, color, align);
            y += lineHeight;
        }
        return y;
    }

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
     * Draws a stroked outline like {@link #drawBorder}, colored by {@code gradient}
     * across the (x, y, width, height) box the same way {@link #drawGradient} fills it.
     * As with drawGradient, pass the original gradient object so a {@link
     * me.piitex.engine.ui.color.ScrollingGradient} scrolls. A null {@code gradient} or a
     * {@code thickness <= 0} is a no-op.
     * <p>
     * The default implementation draws a solid border in the gradient's start color, so
     * a custom Renderer keeps working without overriding this.
     */
    default void drawGradientBorder(float x, float y, float width, float height, LinearGradient gradient, float thickness, float cornerRadius) {
        if (gradient == null) return;
        drawBorder(x, y, width, height, gradient.getStartColor(), thickness, cornerRadius);
    }

    /**
     * Fills a rectangle with any {@link Paint}: a {@link Color} goes to {@link #drawQuad},
     * a {@link LinearGradient} to {@link #drawGradient}. A null paint is a no-op.
     */
    default void drawQuad(float x, float y, float width, float height, Paint paint, float cornerRadius) {
        paint = AnimatedPaint.resolve(paint);
        if (paint instanceof Color color) {
            drawQuad(x, y, width, height, color, cornerRadius);
        } else if (paint instanceof LinearGradient gradient) {
            drawGradient(x, y, width, height, gradient, cornerRadius);
        }
    }

    /**
     * Outlines a rectangle with any {@link Paint}: a {@link Color} goes to
     * {@link #drawBorder}, a {@link LinearGradient} to {@link #drawGradientBorder}. A
     * null paint is a no-op.
     */
    default void drawBorder(float x, float y, float width, float height, Paint paint, float thickness, float cornerRadius) {
        paint = AnimatedPaint.resolve(paint);
        if (paint instanceof Color color) {
            drawBorder(x, y, width, height, color, thickness, cornerRadius);
        } else if (paint instanceof LinearGradient gradient) {
            drawGradientBorder(x, y, width, height, gradient, thickness, cornerRadius);
        }
    }

    /**
     * Draws a line segment in any {@link Paint}: a {@link Color} goes to {@link #drawLine(float, float, float, float, Color, float)},
     * a {@link LinearGradient} is drawn along the segment, its first color at (x0, y0) and its last at (x1, y1), as a
     * flat-ended stroke. A null paint, a {@code thickness <= 0} or a zero-length segment is a no-op.
     */
    default void drawLine(float x0, float y0, float x1, float y1, Paint paint, float thickness) {
        paint = AnimatedPaint.resolve(paint);
        if (paint instanceof Color color) {
            drawLine(x0, y0, x1, y1, color, thickness);
        } else if (paint instanceof LinearGradient gradient && thickness > 0f) {
            float dx = x1 - x0, dy = y1 - y0;
            float length = (float) Math.hypot(dx, dy);
            if (length <= 0f) return;
            pushTransform(x0, y0, (float) Math.toDegrees(Math.atan2(dy, dx)), 1f, 1f, 0f, 0f);
            drawGradient(0f, -thickness / 2f, length, thickness, new LinearGradient(gradient.getColors(), 0f), 0f);
            popTransform();
        }
    }

    /**
     * Fills a circle with any {@link Paint}: a {@link Color} goes to {@link #drawCircle(float, float, float, Color)},
     * a {@link LinearGradient} fills the circle's bounding square, rounded all the way. A null paint or a
     * non-positive {@code radius} is a no-op.
     */
    default void drawCircle(float x, float y, float radius, Paint paint) {
        paint = AnimatedPaint.resolve(paint);
        if (paint instanceof Color color) {
            drawCircle(x, y, radius, color);
        } else if (paint instanceof LinearGradient gradient && radius > 0f) {
            drawGradient(x - radius, y - radius, radius * 2f, radius * 2f, gradient, radius);
        }
    }

    /**
     * Same as {@link #drawImage(Image, float, float, float, float, float, float, Color)} with no tint, so the image draws with its own colors unchanged.
     */
    default void drawImage(Image image, float x, float y, float width, float height, float cornerRadius, float opacity) {
        drawImage(image, x, y, width, height, cornerRadius, opacity, null);
    }

    /**
     * Draws {@code image} scaled to fill a (x, y, width, height) rectangle, blending
     * neighbouring pixels when scaled, using the same corner-radius rules as
     * {@link #drawQuad} for rounded corners. {@code
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
     * Draws the part of {@code image} inside the source rectangle ({@code srcX}, {@code srcY}, {@code srcWidth},
     * {@code srcHeight}), in image pixels, scaled to fill the destination rectangle ({@code x}, {@code y},
     * {@code width}, {@code height}). Nothing outside the source rectangle is sampled, so neighbouring pictures on a
     * sprite sheet never bleed in. The edges are not antialiased, so regions drawn side by side, such as tiles, leave
     * no seams. To mirror or rotate the picture, draw it inside {@link #pushTransform}.
     * <p>
     * {@code color} is multiplied with the image: white or null draws it unchanged, and its alpha fades it. This
     * differs from the recoloring {@code tint} of {@link #drawImage}. {@code smooth} picks the sampling when the image
     * is scaled: true blends neighbouring pixels, false keeps hard pixel edges, as pixel art wants. A null
     * {@code image} or an empty rectangle is a no-op.
     * <p>
     * The default implementation clips {@link #drawImage} to the destination, using only the alpha of {@code color}
     * and ignoring {@code smooth}, so pixels beside the source rectangle can show faintly along its edges when scaled.
     * It keeps a custom Renderer working without overriding this.
     */
    default void drawImageRegion(Image image, float srcX, float srcY, float srcWidth, float srcHeight,
                                 float x, float y, float width, float height, Color color, boolean smooth) {
        if (image == null || !(srcWidth > 0) || !(srcHeight > 0) || !(width > 0) || !(height > 0)) return;
        float scaleX = width / srcWidth, scaleY = height / srcHeight;
        float opacity = color == null ? 1f : color.getA();
        pushClip(x, y, width, height);
        drawImage(image, x - srcX * scaleX, y - srcY * scaleY, image.getWidth() * scaleX, image.getHeight() * scaleY, 0f, opacity);
        popClip();
    }

    /**
     * Draws {@code count} textured quads from one image in a single call, for drawing many sprites at once.
     * <ul>
     *   <li>{@code positions}: 8 floats per quad, the x and y of its four corners in the order top-left, top-right,
     *       bottom-right, bottom-left of the picture. A quad may be moved, rotated, scaled or mirrored, but not
     *       skewed.</li>
     *   <li>{@code texCoords}: 8 floats per quad, the same four corners in image pixels. They must form an
     *       upright rectangle: top-left, top-right, bottom-right, bottom-left.</li>
     *   <li>{@code colors}: one ARGB int per quad, multiplied with the image as in {@link #drawImageRegion}; null for
     *       white.</li>
     * </ul>
     * The arrays may be longer than {@code count} quads need; the rest is ignored. As with {@link #drawImageRegion},
     * edges are not antialiased, so quads side by side leave no seams, {@code smooth} picks the sampling, and nothing
     * outside a quad's rectangle of the image shows at its edges.
     * <p>
     * The default implementation draws each quad with {@link #drawImageRegion} inside {@link #pushTransform}, so a
     * custom Renderer keeps working without overriding this.
     */
    default void drawImageQuads(Image image, boolean smooth, float[] positions, float[] texCoords, int[] colors, int count) {
        if (image == null) return;
        for (int q = 0; q < count; q++) {
            int p = q * 8;
            float sx = texCoords[p], sy = texCoords[p + 1];
            float sw = texCoords[p + 2] - sx, sh = texCoords[p + 7] - sy;
            if (!(sw > 0) || !(sh > 0)) continue;
            float x0 = positions[p], y0 = positions[p + 1];
            float axisX = positions[p + 2] - x0, axisY = positions[p + 3] - y0; // along the picture's top edge
            float downX = positions[p + 6] - x0, downY = positions[p + 7] - y0; // along its left edge
            double angle = Math.atan2(axisY, axisX);
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            float scaleX = (float) Math.hypot(axisX, axisY) / sw;
            float scaleY = (downY * cos - downX * sin) / sh; // negative when mirrored top to bottom
            Color color = colors == null ? null : unpackColor(colors[q]);
            pushTransform(x0, y0, (float) Math.toDegrees(angle), scaleX, scaleY, 0f, 0f);
            drawImageRegion(image, sx, sy, sw, sh, 0f, 0f, sw, sh, color, smooth);
            popTransform();
        }
    }

    private static Color unpackColor(int argb) {
        return new Color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, (argb >>> 24) / 255f);
    }

    /**
     * Fills a rectangle with {@code effect}'s shader, with its top-left corner at (x, y) and the same corner-radius
     * rules as {@link #drawQuad}. The shader's {@code coord} runs from (0, 0) at that corner to ({@code width},
     * {@code height}), in points, whatever transform is in effect. A null or closed effect, or an empty rectangle, is
     * a no-op.
     * <p>
     * The default implementation draws nothing, so a custom Renderer keeps working without overriding this.
     */
    default void drawShader(ShaderEffect effect, float x, float y, float width, float height, float cornerRadius) {
    }

    /**
     * Passes everything drawn after this call, until the matching {@link #popEffect()}, through {@code effect}:
     * post-processing. The drawing is collected in an offscreen layer covering (x, y, width, height) and handed to the
     * shader as its {@code uniform shader content}. The shader's result is drawn in that rectangle, and nothing
     * outside it. Its {@code coord} is in the current coordinate space, the same one (x, y) is in, so
     * {@code content.eval(coord)} reads the drawing at the point being colored.
     * <p>
     * An effect that is null, closed or declares no {@code content} leaves the drawing unchanged. Nests, and must be
     * paired with exactly one {@link #popEffect()}. The default implementation leaves the drawing unchanged, so a
     * custom Renderer keeps working without overriding this.
     */
    default void pushEffect(ShaderEffect effect, float x, float y, float width, float height) {
    }

    /**
     * Pops the effect most recently pushed by {@link #pushEffect}, drawing what was drawn since through it.
     */
    default void popEffect() {
    }

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
     * Like {@link #pushOpacityLayer}, but the finished layer is combined with what is underneath it by {@code mode}
     * instead of covering it: {@link me.piitex.engine.graphics.BlendMode#ADD} for a glow, {@code MULTIPLY} for a
     * shadow. Pair it with {@link #popOpacityLayer()}. The default ignores the mode and draws a plain
     * {@link #pushOpacityLayer opacity layer}, so a custom Renderer keeps working.
     */
    default void pushBlendLayer(float x, float y, float width, float height, me.piitex.engine.graphics.BlendMode mode, float opacity) {
        pushOpacityLayer(x, y, width, height, opacity);
    }

    /**
     * Pops the layer most recently pushed by {@link #pushOpacityLayer}, compositing
     * everything drawn since then onto the canvas beneath it at that layer's opacity.
     */
    void popOpacityLayer();

    /**
     * Multiplies the opacity of everything drawn after this call by {@code alpha}, clamped to [0, 1], until the
     * matching {@link #popAlpha()}: fills, outlines, lines, text, images and shaders. Nested pushes multiply, so
     * {@code pushAlpha(0.5f)} inside {@code pushAlpha(0.5f)} draws at a quarter. For fading a panel in:
     * <pre>{@code
     * renderer.pushAlpha(fadeIn);
     * renderer.drawQuad(x, y, width, height, PANEL, 12f);
     * renderer.drawText("Paused", x + 16f, y + 16f, font, Color.WHITE);
     * renderer.popAlpha();
     * }</pre>
     * Unlike {@link #pushOpacityLayer}, this draws nothing offscreen, so it costs nothing, but each thing is faded on
     * its own: where two faded things overlap, the one below shows through the one above. Fade a group through an
     * opacity layer when that matters. What a {@link #pushEffect post effect} makes of the drawing is not faded
     * again. Must be paired with exactly one {@link #popAlpha()}; everything pushed is dropped at the next
     * {@link #begin()}.
     * <p>
     * The default implementation ignores it, so a custom Renderer keeps working without overriding this, drawing at
     * full opacity.
     */
    default void pushAlpha(float alpha) {
    }

    /**
     * Pops the alpha most recently pushed by {@link #pushAlpha}.
     */
    default void popAlpha() {
    }

    /**
     * The opacity {@link #pushAlpha} has set for what is drawn now, from 0 to 1: 1 when nothing is pushed.
     */
    default float getAlpha() {
        return 1f;
    }

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

    /**
     * Moves, rotates and scales everything drawn after this call, until the matching {@link #popTransform()}. A
     * point ({@code px}, {@code py}) drawn in between lands where the point ({@code originX}, {@code originY}) is
     * first moved to (0, 0), then scaled by {@code scaleX} and {@code scaleY}, rotated clockwise by
     * {@code rotation} degrees, and finally moved to ({@code x}, {@code y}). So the origin ends up at (x, y) and
     * everything turns and scales around it. A negative scale mirrors along that axis. This is how a
     * {@link me.piitex.engine.graphics.Camera} maps the world onto the screen, and how a rotated picture is drawn.
     * Nests, and must be paired with exactly one {@link #popTransform()}.
     * <p>
     * The default implementation ignores {@code rotation} and scales both axes by {@code scaleX}, through
     * {@link #pushTranslate} and {@link #pushScale}, so a custom Renderer keeps working without overriding this.
     */
    default void pushTransform(float x, float y, float rotation, float scaleX, float scaleY, float originX, float originY) {
        pushTranslate(x, y);
        pushScale(scaleX, 0f, 0f);
        pushTranslate(-originX, -originY);
    }

    /**
     * Pops the transform most recently pushed by {@link #pushTransform}.
     */
    default void popTransform() {
        popTranslate();
        popScale();
        popTranslate();
    }
}
