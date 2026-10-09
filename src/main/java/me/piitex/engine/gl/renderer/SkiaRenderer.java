package me.piitex.engine.gl.renderer;

import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.graphics.ShaderEffect;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.GradientUtil;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.utils.flags.GeneratedFragments;
import org.jetbrains.skia.*;
import org.jetbrains.skia.impl.Managed;

/**
 * Everything the Skia-backed renderers share: the {@link Renderer} drawing calls, written against one Skia
 * {@link Canvas}. A backend (OpenGL, Metal, Vulkan) only decides where that canvas draws to: it makes the canvas
 * valid in {@link #begin()}, flushes it in {@link #end()} and frees its GPU objects in {@code dispose()}.
 * <p>
 * Every drawing call is a no-op while {@link #frameValid} is false, which is how a backend skips a frame it couldn't
 * get a target for (a minimized window, a swapchain being recreated).
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public abstract class SkiaRenderer implements Renderer {
    protected Canvas canvas;
    protected final AlphaStack alphas = new AlphaStack();

    /**
     * True between a successful {@link #begin()} and its {@link #end()}.
     */
    protected boolean frameValid;

    /**
     * Frames begun so far. Each backend's {@link #begin()} increments it.
     */
    protected long frames;

    /**
     * The number of the frame being drawn, which goes up by one per frame. A GPU layer such as the 3D one uses it to
     * tell a new frame from a second draw in the same frame.
     */
    public long frameNumber() {
        return frames;
    }

    protected float clearR = 1f, clearG = 1f, clearB = 1f, clearA = 1f;

    @Override
    public void clear(float r, float g, float b, float a) {
        this.clearR = r;
        this.clearG = g;
        this.clearB = b;
        this.clearA = a;
    }

    @Override
    public void setViewport(int x, int y, int width, int height) {
        // Meaningless for a backend whose target defines the viewport (Metal, Vulkan); OpenGL overrides it.
    }

    @Override
    public void drawQuad(float x, float y, float width, float height, Color color, float cornerRadius) {
        if (color == null || !frameValid) return;

        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setColor(alphas.argb(color));
            paint.setAntiAlias(true);
            if (cornerRadius > 0) {
                canvas.drawRRect(RRect.makeLTRB(x, y, x + width, y + height, cornerRadius), paint);
            } else {
                canvas.drawRect(Rect.makeXYWH(x, y, width, height), paint);
            }
        }
    }

    @Override
    public void drawBorder(float x, float y, float width, float height, Color color, float thickness, float cornerRadius) {
        if (color == null || thickness <= 0 || !frameValid) return;

        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setColor(alphas.argb(color));
            paint.setMode(PaintMode.STROKE);
            paint.setStrokeWidth(thickness);
            paint.setAntiAlias(true);
            if (cornerRadius > 0) {
                canvas.drawRRect(RRect.makeLTRB(x, y, x + width, y + height, cornerRadius), paint);
            } else {
                canvas.drawRect(Rect.makeXYWH(x, y, width, height), paint);
            }
        }
    }

    @Override
    public void drawLine(float x0, float y0, float x1, float y1, Color color, float thickness) {
        if (color == null || thickness <= 0 || !frameValid) return;

        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setColor(alphas.argb(color));
            paint.setMode(PaintMode.STROKE);
            paint.setStrokeWidth(thickness);
            paint.setAntiAlias(true);
            canvas.drawLine(x0, y0, x1, y1, paint);
        }
    }

    @Override
    public void drawPolyline(float[] points, int pointCount, Color color, float thickness) {
        if (color == null || thickness <= 0 || pointCount < 2 || !frameValid) return;

        try (PathBuilder builder = new PathBuilder();
             org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            builder.moveTo(points[0], points[1]);
            for (int i = 1; i < pointCount; i++) {
                builder.lineTo(points[i * 2], points[i * 2 + 1]);
            }
            paint.setColor(alphas.argb(color));
            paint.setMode(PaintMode.STROKE);
            paint.setStrokeWidth(thickness);
            paint.setStrokeCap(PaintStrokeCap.ROUND);
            paint.setStrokeJoin(PaintStrokeJoin.ROUND);
            paint.setAntiAlias(true);
            // Closed through its Managed superclass rather than try-with-resources or path.close():
            // any member lookup on Path makes javac load its Kotlin marker interface, and
            // kotlin-stdlib is only on the runtime classpath.
            Path path = builder.detach();
            Managed pathHandle = path;
            try {
                canvas.drawPath(path, paint);
            } finally {
                pathHandle.close();
            }
        }
    }

    @Override
    public void drawPolygon(float[] points, int pointCount, Color color) {
        if (color == null || pointCount < 3 || !frameValid) return;
        SkiaShapes.drawPolygon(canvas, points, pointCount, alphas.argb(color), 0f);
    }

    @Override
    public void drawPolygonOutline(float[] points, int pointCount, Color color, float thickness) {
        if (color == null || thickness <= 0 || pointCount < 2 || !frameValid) return;
        SkiaShapes.drawPolygon(canvas, points, pointCount, alphas.argb(color), thickness);
    }

    @Override
    public void drawCircle(float x, float y, float radius, Color color) {
        if (color == null || radius <= 0 || !frameValid) return;

        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setColor(alphas.argb(color));
            paint.setAntiAlias(true);
            canvas.drawCircle(x, y, radius, paint);
        }
    }

    @Override
    public void drawText(String text, float x, float y, Font font, Paint color) {
        if (text == null || text.isEmpty() || color == null || font == null || !frameValid) return;

        // drawString's y is the baseline, not the top, so shift down by the ascent.
        // Skia reports ascent as negative, so subtracting it moves down.
        FontMetricsCompat metrics = FontMetricsCompat.of(font);
        float baselineY = y - metrics.getAscent();

        color = AnimatedPaint.resolve(color);
        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setAntiAlias(true);

            if (color instanceof Color c) {
                paint.setColor(alphas.argb(c));
            } else if (color instanceof LinearGradient grad) {
                // Approximate text bounding box for the gradient shader coordinates
                float textWidth = font.measureTextWidth(text);
                float textHeight = -metrics.getAscent() + metrics.getDescent();

                try (Shader shader = GradientUtil.buildShader(grad, x, y, x + textWidth, y + textHeight)) {
                    paint.setShader(shader);
                    paint.setAlphaf(alphas.get());
                }
            }

            canvas.drawString(text, x, baselineY, font, paint);
        }
    }

    @Override
    public void drawImage(Image image, float x, float y, float width, float height, float cornerRadius, float opacity, Color tint) {
        if (image == null || !frameValid) return;

        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setAntiAlias(true);
            paint.setAlphaf(Math.max(0f, Math.min(1f, opacity)) * alphas.get());
            if (tint != null) {
                try (ColorFilter filter = ColorFilter.Companion.makeBlend(tint.toARGB(), BlendMode.SRC_IN)) {
                    paint.setColorFilter(filter);
                }
            }

            Rect dst = Rect.makeXYWH(x, y, width, height);
            if (cornerRadius > 0) {
                canvas.save();
                canvas.clipRRect(RRect.makeLTRB(x, y, x + width, y + height, cornerRadius), true);
                canvas.drawImageRect(image, Rect.makeWH(image.getWidth(), image.getHeight()), dst, SkiaImages.SMOOTH, paint, true);
                canvas.restore();
            } else {
                canvas.drawImageRect(image, Rect.makeWH(image.getWidth(), image.getHeight()), dst, SkiaImages.SMOOTH, paint, true);
            }
        }
    }

    @Override
    public void drawImageRegion(Image image, float srcX, float srcY, float srcWidth, float srcHeight,
                                float x, float y, float width, float height, Color color, boolean smooth) {
        if (!frameValid) return;
        SkiaImages.drawImageRegion(canvas, image, srcX, srcY, srcWidth, srcHeight, x, y, width, height, color, smooth, alphas.get());
    }

    @Override
    public void drawImageQuads(Image image, boolean smooth, float[] positions, float[] texCoords, int[] colors, int count) {
        if (!frameValid) return;
        SkiaImages.drawImageQuads(canvas, image, smooth, positions, texCoords, colors, count, alphas.get());
    }

    @Override
    public void drawGradient(float x, float y, float width, float height, LinearGradient gradient, float cornerRadius) {
        if (gradient == null || !frameValid) return;

        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setAntiAlias(true);

            try (Shader shader = GradientUtil.buildShader(gradient, x, y, x + width, y + height)) {
                paint.setShader(shader);
                paint.setAlphaf(alphas.get());

                if (cornerRadius > 0) {
                    canvas.drawRRect(RRect.makeLTRB(x, y, x + width, y + height, cornerRadius), paint);
                } else {
                    canvas.drawRect(Rect.makeXYWH(x, y, width, height), paint);
                }
            }
        }
    }

    @Override
    public void drawGradientBorder(float x, float y, float width, float height, LinearGradient gradient, float thickness, float cornerRadius) {
        if (gradient == null || thickness <= 0 || !frameValid) return;

        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint();
             Shader shader = GradientUtil.buildShader(gradient, x, y, x + width, y + height)) {
            paint.setShader(shader);
            paint.setAlphaf(alphas.get());
            paint.setMode(PaintMode.STROKE);
            paint.setStrokeWidth(thickness);
            paint.setAntiAlias(true);
            if (cornerRadius > 0) {
                canvas.drawRRect(RRect.makeLTRB(x, y, x + width, y + height, cornerRadius), paint);
            } else {
                canvas.drawRect(Rect.makeXYWH(x, y, width, height), paint);
            }
        }
    }

    @Override
    public void drawShader(ShaderEffect effect, float x, float y, float width, float height, float cornerRadius) {
        if (!frameValid) return;
        SkiaEffects.drawShader(canvas, effect, x, y, width, height, cornerRadius, alphas.get());
    }

    @Override
    public void pushEffect(ShaderEffect effect, float x, float y, float width, float height) {
        if (!frameValid) return;
        SkiaEffects.pushEffect(canvas, effect, x, y, width, height);
    }

    @Override
    public void popEffect() {
        if (!frameValid) return;
        SkiaEffects.popEffect(canvas);
    }

    @Override
    public void pushAlpha(float alpha) {
        alphas.push(alpha);
    }

    @Override
    public void popAlpha() {
        alphas.pop();
    }

    @Override
    public float getAlpha() {
        return alphas.get();
    }

    @Override
    public void pushOpacityLayer(float x, float y, float width, float height, float opacity) {
        if (!frameValid) return;
        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setAlphaf(Math.max(0f, Math.min(1f, opacity)));
            canvas.saveLayer(Rect.makeXYWH(x, y, width, height), paint);
        }
    }

    @Override
    public void popOpacityLayer() {
        if (!frameValid) return;
        canvas.restore();
    }

    @Override
    public void pushClip(float x, float y, float width, float height) {
        if (!frameValid) return;
        canvas.save();
        canvas.clipRect(Rect.makeXYWH(x, y, width, height), ClipMode.INTERSECT, true);
    }

    @Override
    public void pushClip(float x, float y, float width, float height, float cornerRadius) {
        if (!frameValid) return;
        canvas.save();
        canvas.clipRRect(RRect.makeLTRB(x, y, x + width, y + height, cornerRadius), ClipMode.INTERSECT, true);
    }

    @Override
    public void popClip() {
        if (!frameValid) return;
        canvas.restore();
    }

    @Override
    public void pushTranslate(float dx, float dy) {
        if (!frameValid) return;
        canvas.save();
        canvas.translate(dx, dy);
    }

    @Override
    public void popTranslate() {
        if (!frameValid) return;
        canvas.restore();
    }

    @Override
    public void pushScale(float scale, float pivotX, float pivotY) {
        if (!frameValid) return;
        canvas.save();
        canvas.translate(pivotX, pivotY);
        canvas.scale(scale, scale);
        canvas.translate(-pivotX, -pivotY);
    }

    @Override
    public void popScale() {
        if (!frameValid) return;
        canvas.restore();
    }

    @Override
    public void pushTransform(float x, float y, float rotation, float scaleX, float scaleY, float originX, float originY) {
        if (!frameValid) return;
        SkiaImages.pushTransform(canvas, x, y, rotation, scaleX, scaleY, originX, originY);
    }

    @Override
    public void popTransform() {
        if (!frameValid) return;
        canvas.restore();
    }

    public Canvas getCanvas() {
        return canvas;
    }

    protected static int argb(float a, float r, float g, float b) {
        return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }
}
