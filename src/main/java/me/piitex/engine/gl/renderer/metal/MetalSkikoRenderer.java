package me.piitex.engine.gl.renderer.metal;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.GradientUtil;
import me.piitex.engine.ui.color.LinearGradient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.skia.*;
import org.jetbrains.skia.impl.Managed;

/**
 * Skiko renderer backed by Metal via a CAMetalLayer attached to a GLFW window.
 * <p>
 * Key difference from the GL path: the render target is the drawable's texture, and
 * a new drawable is handed to you every frame. So the BackendRenderTarget and Surface
 * are rebuilt in begin() and destroyed in end(); they cannot be cached across frames.
 * <p>
 * Threading: construct MetalContext on the main thread, then call begin()/end() on the
 * render thread only.
 */
public class MetalSkikoRenderer implements Renderer {
    private static final Logger log = LogManager.getLogger("Engine");

    private final MetalContext metal;
    private DirectContext directContext;

    private BackendRenderTarget renderTarget;
    private Surface surface;
    private Canvas canvas;

    /**
     * Logical (points) size the UI layer draws in.
     */
    private int logicalWidth;
    private int logicalHeight;
    private float scaleX = 1.0f;
    private float scaleY = 1.0f;

    private boolean frameValid;

    private float clearR = 1f, clearG = 1f, clearB = 1f, clearA = 1f;

    public MetalSkikoRenderer(MetalContext metal) {
        this.metal = metal;
    }

    public void init() {
        log.info("Using Skiko Metal backend...");
        directContext = DirectContext.Companion.makeMetal(metal.getDevice(), metal.getCommandQueue());
    }

    public void onResize(int logicalWidth, int logicalHeight, int framebufferWidth, int framebufferHeight) {
        this.logicalWidth = Math.max(1, logicalWidth);
        this.logicalHeight = Math.max(1, logicalHeight);
        this.scaleX = (float) framebufferWidth / this.logicalWidth;
        this.scaleY = (float) framebufferHeight / this.logicalHeight;
    }

    @Override
    public void begin() {
        frameValid = false;

        long drawable = metal.nextDrawable();
        if (drawable == 0L) {
            return;
        }

        long texture = metal.drawableTexture();
        int width = metal.textureWidth(texture);
        int height = metal.textureHeight(texture);

        renderTarget = BackendRenderTarget.Companion.makeMetal(width, height, texture);

        surface = Surface.Companion.makeFromBackendRenderTarget(
                directContext,
                renderTarget,
                SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.BGRA_8888,
                ColorSpace.Companion.getSRGB(),
                new SurfaceProps()
        );

        canvas = surface.getCanvas();
        frameValid = true;

        canvas.clear(argb(clearA, clearR, clearG, clearB));
        canvas.save();
        canvas.scale(scaleX, scaleY);
    }

    @Override
    public void end() {
        if (!frameValid) {
            metal.abandon();
            return;
        }

        canvas.restore();
        surface.flushAndSubmit();
        metal.present();
        surface.close();
        renderTarget.close();

        surface = null;
        renderTarget = null;
        canvas = null;
        frameValid = false;
    }

    @Override
    public void clear(float r, float g, float b, float a) {
        this.clearR = r;
        this.clearG = g;
        this.clearB = b;
        this.clearA = a;
    }

    @Override
    public void setViewport(int x, int y, int width, int height) {
        // Meaningless under Metal - the drawable defines the viewport.
        // Use onResize() instead.
    }

    @Override
    public void drawQuad(float x, float y, float width, float height, Color color, float cornerRadius) {
        if (color == null || !frameValid) return;

        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setColor(color.toARGB());
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
            paint.setColor(color.toARGB());
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
            paint.setColor(color.toARGB());
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
            paint.setColor(color.toARGB());
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
    public void drawCircle(float x, float y, float radius, Color color) {
        if (color == null || radius <= 0 || !frameValid) return;

        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setColor(color.toARGB());
            paint.setAntiAlias(true);
            canvas.drawCircle(x, y, radius, paint);
        }
    }

    @Override
    public void drawText(String text, float x, float y, Font font, Paint color) {
        if (text == null || text.isEmpty() || color == null || font == null || !frameValid) return;

        // drawString's y is the baseline, not the top, so shift down by the ascent.
        // Skia reports ascent as negative, so subtracting it moves down.
        FontMetrics metrics = font.getMetrics();
        float baselineY = y - metrics.getAscent();

        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setAntiAlias(true);

            if (color instanceof Color c) {
                paint.setColor(c.toARGB());
            } else if (color instanceof LinearGradient grad) {
                // Approximate text bounding box for the gradient shader coordinates
                float textWidth = font.measureTextWidth(text);
                float textHeight = -metrics.getAscent() + metrics.getDescent();

                try (Shader shader = GradientUtil.buildShader(grad, x, y, x + textWidth, y + textHeight)) {
                    paint.setShader(shader);
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
            paint.setAlphaf(Math.max(0f, Math.min(1f, opacity)));
            if (tint != null) {
                try (ColorFilter filter = ColorFilter.Companion.makeBlend(tint.toARGB(), BlendMode.SRC_IN)) {
                    paint.setColorFilter(filter);
                }
            }

            Rect dst = Rect.makeXYWH(x, y, width, height);
            if (cornerRadius > 0) {
                canvas.save();
                canvas.clipRRect(RRect.makeLTRB(x, y, x + width, y + height, cornerRadius), true);
                canvas.drawImageRect(image, dst, paint);
                canvas.restore();
            } else {
                canvas.drawImageRect(image, dst, paint);
            }
        }
    }

    @Override
    public void drawGradient(float x, float y, float width, float height, LinearGradient gradient, float cornerRadius) {
        if (gradient == null || !frameValid) return;

        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setAntiAlias(true);

            try (Shader shader = GradientUtil.buildShader(gradient, x, y, x + width, y + height)) {
                paint.setShader(shader);

                if (cornerRadius > 0) {
                    canvas.drawRRect(RRect.makeLTRB(x, y, x + width, y + height, cornerRadius), paint);
                } else {
                    canvas.drawRect(Rect.makeXYWH(x, y, width, height), paint);
                }
            }
        }
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

    public Canvas getCanvas() {
        return canvas;
    }

    public void dispose() {
        if (surface != null) surface.close();
        if (renderTarget != null) renderTarget.close();
        if (directContext != null) directContext.close();
    }

    private static int argb(float a, float r, float g, float b) {
        return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }
}