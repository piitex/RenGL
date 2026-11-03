package me.piitex.engine.gl.renderer;

import me.piitex.engine.ui.text.TextShaping;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.graphics.ShaderEffect;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.GradientUtil;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.utils.flags.GeneratedFragments;
import org.jetbrains.skia.*;

/**
 * A {@link Renderer} that draws into a CPU-backed Skia raster {@link Surface} instead of the window, so a UI
 * subtree can be captured as an image (see {@link Snapshot}). It needs no GL or Metal context and works the same
 * on every platform. Coordinates are logical points; {@code scale} multiplies them into pixels.
 * <p>
 * Not thread-safe. Use it on the render thread, like every other {@link Renderer}, and {@link #close()} it when done.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class OffscreenRenderer implements Renderer, AutoCloseable {
    private final Surface surface;
    private final Canvas canvas;
    private final AlphaStack alphas = new AlphaStack();
    private final float scale;

    private float clearR = 0f, clearG = 0f, clearB = 0f, clearA = 0f;

    /**
     * @param width logical width in points; @param scale pixels per point (2 for a retina-sharp capture)
     */
    public OffscreenRenderer(int width, int height, float scale) {
        this.scale = scale;
        int pw = Math.max(1, Math.round(width * scale));
        int ph = Math.max(1, Math.round(height * scale));
        this.surface = Surface.Companion.makeRasterN32Premul(pw, ph);
        this.canvas = surface.getCanvas();
    }

    @Override
    public void begin() {
        alphas.reset();
        canvas.clear(argb(clearA, clearR, clearG, clearB));
        canvas.save();
        canvas.scale(scale, scale);
    }

    @Override
    public void end() {
        canvas.restore();
    }

    /**
     * Colour {@link #begin()} clears to. Defaults to fully transparent, so rounded corners stay transparent in the capture.
     */
    @Override
    public void clear(float r, float g, float b, float a) {
        this.clearR = r;
        this.clearG = g;
        this.clearB = b;
        this.clearA = a;
    }

    private static int argb(float a, float r, float g, float b) {
        return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    /**
     * No-op: an offscreen surface has no viewport.
     */
    @Override
    public void setViewport(int x, int y, int width, int height) {
    }

    /**
     * Copies what has been drawn so far into an independent image. Call after {@link #end()}.
     */
    public Image makeImage() {
        return surface.makeImageSnapshot();
    }

    /**
     * What has been drawn so far as {@code width * height * 4} bytes of RGBA8 with the top row first, colours not
     * multiplied by their alpha (the layout of an image file). Call after {@link #end()}.
     */
    public byte[] readPixels() {
        int w = surface.getWidth();
        int h = surface.getHeight();
        ImageInfo info = new ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
        Bitmap bitmap = new Bitmap();
        try {
            if (!bitmap.allocPixels(info) || !surface.readPixels(bitmap, 0, 0)) {
                throw new IllegalStateException("Could not read back the " + w + "x" + h + " surface");
            }
            byte[] pixels = bitmap.readPixels(info, w * 4, 0, 0);
            if (pixels == null) throw new IllegalStateException("Could not read back the " + w + "x" + h + " surface");
            return pixels;
        } finally {
            bitmap.close();
        }
    }

    /**
     * Width of the surface in pixels: the logical width times the scale.
     */
    public int getPixelWidth() {
        return surface.getWidth();
    }

    public int getPixelHeight() {
        return surface.getHeight();
    }

    @Override
    public void close() {
        surface.close();
    }

    @Override
    public void drawQuad(float x, float y, float width, float height, Color color, float cornerRadius) {
        if (color == null) return;

        org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint();
        paint.setColor(alphas.argb(color));
        paint.setAntiAlias(true);

        if (cornerRadius > 0) {
            canvas.drawRRect(RRect.makeLTRB(x, y, x + width, y + height, cornerRadius), paint);
        } else {
            canvas.drawRect(Rect.makeXYWH(x, y, width, height), paint);
        }

        paint.close();
    }

    @Override
    public void drawBorder(float x, float y, float width, float height, Color color, float thickness, float cornerRadius) {
        if (color == null || thickness <= 0) return;

        org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint();
        paint.setColor(alphas.argb(color));
        paint.setMode(PaintMode.STROKE);
        paint.setStrokeWidth(thickness);
        paint.setAntiAlias(true);

        if (cornerRadius > 0) {
            canvas.drawRRect(RRect.makeLTRB(x, y, x + width, y + height, cornerRadius), paint);
        } else {
            canvas.drawRect(Rect.makeXYWH(x, y, width, height), paint);
        }

        paint.close();
    }

    @Override
    public void drawLine(float x0, float y0, float x1, float y1, Color color, float thickness) {
        if (color == null || thickness <= 0) return;

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
        if (color == null || thickness <= 0 || pointCount < 2) return;

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
            org.jetbrains.skia.Path path = builder.detach();
            org.jetbrains.skia.impl.Managed pathHandle = path;
            try {
                canvas.drawPath(path, paint);
            } finally {
                pathHandle.close();
            }
        }
    }

    @Override
    public void drawPolygon(float[] points, int pointCount, Color color) {
        if (color == null || pointCount < 3) return;
        SkiaShapes.drawPolygon(canvas, points, pointCount, alphas.argb(color), 0f);
    }

    @Override
    public void drawPolygonOutline(float[] points, int pointCount, Color color, float thickness) {
        if (color == null || thickness <= 0 || pointCount < 2) return;
        SkiaShapes.drawPolygon(canvas, points, pointCount, alphas.argb(color), thickness);
    }

    @Override
    public void drawCircle(float x, float y, float radius, Color color) {
        if (color == null || radius <= 0) return;

        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setColor(alphas.argb(color));
            paint.setAntiAlias(true);
            canvas.drawCircle(x, y, radius, paint);
        }
    }

    @Override
    public void drawText(String text, float x, float y, Font font, Paint color) {
        if (text == null || text.isEmpty() || color == null || font == null) return;

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
                float textWidth = TextShaping.width(font, text);
                float textHeight = -metrics.getAscent() + metrics.getDescent();

                try (Shader shader = GradientUtil.buildShader(grad, x, y, x + textWidth, y + textHeight)) {
                    paint.setShader(shader);
                    paint.setAlphaf(alphas.get());
                }
            }

            me.piitex.engine.ui.text.TextShaping.draw(canvas, text, x, baselineY, font, paint);
        }
    }

    @Override
    public void drawImage(Image image, float x, float y, float width, float height, float cornerRadius, float opacity, Color tint) {
        if (image == null) return;

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
        SkiaImages.drawImageRegion(canvas, image, srcX, srcY, srcWidth, srcHeight, x, y, width, height, color, smooth, alphas.get());
    }

    @Override
    public void drawImageQuads(Image image, boolean smooth, float[] positions, float[] texCoords, int[] colors, int count) {
        SkiaImages.drawImageQuads(canvas, image, smooth, positions, texCoords, colors, count, alphas.get());
    }

    @Override
    public void drawGradient(float x, float y, float width, float height, LinearGradient gradient, float cornerRadius) {
        if (gradient == null) return;

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
        if (gradient == null || thickness <= 0) return;

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
        SkiaEffects.drawShader(canvas, effect, x, y, width, height, cornerRadius, alphas.get());
    }

    @Override
    public void pushEffect(ShaderEffect effect, float x, float y, float width, float height) {
        SkiaEffects.pushEffect(canvas, effect, x, y, width, height);
    }

    @Override
    public void popEffect() {
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
        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setAlphaf(Math.max(0f, Math.min(1f, opacity)));
            canvas.saveLayer(Rect.makeXYWH(x, y, width, height), paint);
        }
    }

    @Override
    public void pushBlendLayer(float x, float y, float width, float height, me.piitex.engine.graphics.BlendMode mode, float opacity) {
        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setAlphaf(Math.max(0f, Math.min(1f, opacity)));
            paint.setBlendMode(SkiaBlend.of(mode));
            canvas.saveLayer(Rect.makeXYWH(x, y, width, height), paint);
        }
    }

    @Override
    public void popOpacityLayer() {
        canvas.restore();
    }

    @Override
    public void pushClip(float x, float y, float width, float height) {
        canvas.save();
        canvas.clipRect(Rect.makeXYWH(x, y, width, height), ClipMode.INTERSECT, true);
    }

    @Override
    public void pushClip(float x, float y, float width, float height, float cornerRadius) {
        canvas.save();
        canvas.clipRRect(RRect.makeLTRB(x, y, x + width, y + height, cornerRadius), ClipMode.INTERSECT, true);
    }

    @Override
    public void popClip() {
        canvas.restore();
    }

    @Override
    public void pushTranslate(float dx, float dy) {
        canvas.save();
        canvas.translate(dx, dy);
    }

    @Override
    public void popTranslate() {
        canvas.restore();
    }

    @Override
    public void pushScale(float scale, float pivotX, float pivotY) {
        canvas.save();
        canvas.translate(pivotX, pivotY);
        canvas.scale(scale, scale);
        canvas.translate(-pivotX, -pivotY);
    }

    @Override
    public void popScale() {
        canvas.restore();
    }

    @Override
    public void pushTransform(float x, float y, float rotation, float scaleX, float scaleY, float originX, float originY) {
        SkiaImages.pushTransform(canvas, x, y, rotation, scaleX, scaleY, originX, originY);
    }

    @Override
    public void popTransform() {
        canvas.restore();
    }
}
