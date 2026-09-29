package me.piitex.engine.gl.renderer;

import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedFragments;
import org.jetbrains.skia.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The Skia side of {@link Renderer#drawImageRegion}, {@link Renderer#drawImageQuads} and
 * {@link Renderer#pushTransform}, shared by every renderer that draws on a Skia {@link Canvas}. Not for use outside
 * renderer implementations. Render thread only.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class SkiaImages {
    /**
     * Blends neighbouring pixels when an image is drawn scaled. Skia's own default picks the nearest pixel, which
     * leaves scaled images and icons jagged.
     */
    public static final SamplingMode SMOOTH = new FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE);
    private static final SamplingMode PIXELATED = new FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE);
    private static final int WHITE = 0xFFFFFFFF;
    /**
     * How far, in image pixels, pixelated quads pull their texture coordinates in. Far too little to see.
     */
    private static final float INSET = 1f / 64f;
    /**
     * Quads per drawVertices call: Skia's vertex indices are 16-bit, so at most 65536 vertices, 4 per quad.
     */
    private static final int MAX_QUADS = 16384;

    /**
     * Image.makeShader(tileX, tileY, sampling, localMatrix). Its JVM name is mangled because the matrix is a Kotlin
     * value class, so Java can't call it directly; see {@link me.piitex.engine.ui.color.GradientUtil}.
     */
    private static final MethodHandle MAKE_SHADER;

    static {
        try {
            Method target = null;
            for (Method m : Image.class.getDeclaredMethods()) {
                if (m.getName().startsWith("makeShader") && m.getParameterCount() == 4
                        && m.getParameterTypes()[2] == SamplingMode.class) {
                    target = m;
                    break;
                }
            }
            if (target == null) throw new IllegalStateException("Image.makeShader(tileX, tileY, sampling, matrix) not found");
            target.setAccessible(true);
            MAKE_SHADER = MethodHandles.lookup().unreflect(target);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Pixelated image shaders for drawVertices, by image. Weak, so an image that is no longer used goes.
    private static final Map<Image, Shader> shaders = new WeakHashMap<>();

    private static short[] indices = new short[0];

    private SkiaImages() {
    }

    public static void pushTransform(Canvas canvas, float x, float y, float rotation, float scaleX, float scaleY, float originX, float originY) {
        canvas.save();
        if (x != 0f || y != 0f) canvas.translate(x, y);
        if (rotation != 0f) canvas.rotate(rotation);
        if (scaleX != 1f || scaleY != 1f) canvas.scale(scaleX, scaleY);
        if (originX != 0f || originY != 0f) canvas.translate(-originX, -originY);
    }

    public static void drawImageRegion(Canvas canvas, Image image, float srcX, float srcY, float srcWidth, float srcHeight,
                                float x, float y, float width, float height, Color color, boolean smooth, float alpha) {
        if (image == null || !(srcWidth > 0) || !(srcHeight > 0) || !(width > 0) || !(height > 0)) return;
        int argb = color == null ? WHITE : color.toARGB();
        if (alpha < 1f) argb = (Math.round((argb >>> 24) * Math.max(0f, alpha)) << 24) | (argb & 0x00FFFFFF);
        if ((argb >>> 24) == 0) return;
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(false);
            if (argb != WHITE) {
                try (ColorFilter filter = ColorFilter.Companion.makeBlend(argb, BlendMode.MODULATE)) {
                    paint.setColorFilter(filter);
                }
            }
            canvas.drawImageRect(image, srcX, srcY, srcX + srcWidth, srcY + srcHeight, x, y, x + width, y + height,
                    smooth ? SMOOTH : PIXELATED, paint, true);
        }
    }

    public static void drawImageQuads(Canvas canvas, Image image, boolean smooth, float[] positions, float[] texCoords, int[] colors, int count, float alpha) {
        if (image == null || count <= 0 || !(alpha > 0f)) return;
        if (smooth) {
            drawSmoothQuads(canvas, image, positions, texCoords, colors, count, alpha);
            return;
        }
        Shader shader = pixelatedShader(image);
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(false);
            paint.setShader(shader);
            paint.setAlphaf(Math.min(1f, alpha));
            for (int first = 0; first < count; first += MAX_QUADS) {
                int quads = Math.min(MAX_QUADS, count - first);
                float[] pos = new float[quads * 8];
                float[] tex = new float[quads * 8];
                System.arraycopy(positions, first * 8, pos, 0, pos.length);
                for (int q = 0; q < quads; q++) insetTexCoords(texCoords, (first + q) * 8, tex, q * 8);
                int[] vertexColors = null;
                if (colors != null) {
                    vertexColors = new int[quads * 4];
                    for (int q = 0; q < quads; q++) {
                        int c = colors[first + q];
                        vertexColors[q * 4] = c;
                        vertexColors[q * 4 + 1] = c;
                        vertexColors[q * 4 + 2] = c;
                        vertexColors[q * 4 + 3] = c;
                    }
                }
                canvas.drawVertices(VertexMode.TRIANGLES, pos, vertexColors, tex, indices(quads), BlendMode.MODULATE, paint);
            }
        }
    }

    /**
     * Copies one quad's texture coordinates, pulled in by {@link #INSET} towards its middle. Nearest sampling then
     * never lands on the pixel just outside the region: not where a pixel's center falls exactly on the quad's edge,
     * which the CPU rasterizer gives to the quad on the left or top, nor through rounding when the quad is turned.
     */
    private static void insetTexCoords(float[] from, int offset, float[] to, int toOffset) {
        float left = from[offset], top = from[offset + 1], right = from[offset + 2], bottom = from[offset + 7];
        float dx = right > left ? INSET : -INSET, dy = bottom > top ? INSET : -INSET;
        to[toOffset] = left + dx;
        to[toOffset + 1] = top + dy;
        to[toOffset + 2] = right - dx;
        to[toOffset + 3] = top + dy;
        to[toOffset + 4] = right - dx;
        to[toOffset + 5] = bottom - dy;
        to[toOffset + 6] = left + dx;
        to[toOffset + 7] = bottom - dy;
    }

    /**
     * Smooth quads one by one, each clamped to its own region. Blending neighbouring pixels at a region's edge would
     * otherwise pull in whatever lies next to it on the sheet, and drawVertices can't clamp per quad. On the GPU,
     * Skia still merges consecutive draws from one image.
     */
    private static void drawSmoothQuads(Canvas canvas, Image image, float[] positions, float[] texCoords, int[] colors, int count, float alpha) {
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(false);
            paint.setAlphaf(Math.min(1f, alpha));
            int filterColor = WHITE;
            for (int q = 0; q < count; q++) {
                int color = colors == null ? WHITE : colors[q];
                if ((color >>> 24) == 0) continue;
                if (color != filterColor) {
                    if (color == WHITE) {
                        paint.setColorFilter(null);
                    } else {
                        try (ColorFilter filter = ColorFilter.Companion.makeBlend(color, BlendMode.MODULATE)) {
                            paint.setColorFilter(filter);
                        }
                    }
                    filterColor = color;
                }
                int p = q * 8;
                float srcLeft = texCoords[p], srcTop = texCoords[p + 1], srcRight = texCoords[p + 2], srcBottom = texCoords[p + 7];
                float srcWidth = srcRight - srcLeft, srcHeight = srcBottom - srcTop;
                if (!(srcWidth > 0) || !(srcHeight > 0)) continue;
                float x0 = positions[p], y0 = positions[p + 1];
                float x1 = positions[p + 2], y1 = positions[p + 3];
                float x3 = positions[p + 6], y3 = positions[p + 7];
                if (y1 == y0 && x3 == x0 && x1 > x0 && y3 > y0) { // upright and not mirrored: no transform needed
                    canvas.drawImageRect(image, srcLeft, srcTop, srcRight, srcBottom, x0, y0, x1, y3, SMOOTH, paint, true);
                    continue;
                }
                float axisX = x1 - x0, axisY = y1 - y0;
                double angle = Math.atan2(axisY, axisX);
                float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
                float scaleX = (float) Math.hypot(axisX, axisY) / srcWidth;
                float scaleY = ((y3 - y0) * cos - (x3 - x0) * sin) / srcHeight; // negative when mirrored top to bottom
                canvas.save();
                canvas.translate(x0, y0);
                canvas.rotate((float) Math.toDegrees(angle));
                canvas.scale(scaleX, scaleY);
                canvas.drawImageRect(image, srcLeft, srcTop, srcRight, srcBottom, 0f, 0f, srcWidth, srcHeight, SMOOTH, paint, true);
                canvas.restore();
            }
        }
    }

    private static Shader pixelatedShader(Image image) {
        return shaders.computeIfAbsent(image, i -> imageShader(i, false, FilterTileMode.CLAMP));
    }

    /**
     * A shader reading {@code image} in its own pixels, blending neighbouring pixels or not. The caller closes it.
     */
    public static Shader imageShader(Image image, boolean smooth, FilterTileMode tileMode) {
        try {
            return (Shader) MAKE_SHADER.invoke(image, tileMode, tileMode, smooth ? SMOOTH : PIXELATED, (float[]) null);
        } catch (Throwable t) {
            throw new IllegalStateException("Could not make an image shader", t);
        }
    }

    /**
     * Two triangles per quad, over its corners 0-1-2 and 0-2-3, sized to exactly {@code quads} quads since
     * drawVertices draws every index it is given.
     */
    private static short[] indices(int quads) {
        if (indices.length < quads * 6) {
            short[] grown = new short[Math.min(MAX_QUADS, Math.max(quads, indices.length / 6 * 2)) * 6];
            for (int q = 0; q < grown.length / 6; q++) {
                int v = q * 4;
                grown[q * 6] = (short) v;
                grown[q * 6 + 1] = (short) (v + 1);
                grown[q * 6 + 2] = (short) (v + 2);
                grown[q * 6 + 3] = (short) v;
                grown[q * 6 + 4] = (short) (v + 2);
                grown[q * 6 + 5] = (short) (v + 3);
            }
            indices = grown;
        }
        return indices.length == quads * 6 ? indices : java.util.Arrays.copyOf(indices, quads * 6);
    }
}
