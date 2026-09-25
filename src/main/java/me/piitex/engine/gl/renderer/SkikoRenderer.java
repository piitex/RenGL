package me.piitex.engine.gl.renderer;

import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.GradientUtil;
import me.piitex.engine.ui.color.LinearGradient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.skia.*;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.opengl.GL11.*;

public class SkikoRenderer implements Renderer {
    private static final Logger log = LogManager.getLogger(SkikoRenderer.class);
    private DirectContext directContext;
    private BackendRenderTarget renderTarget;
    private Surface surface;
    private Canvas canvas;

    private int logicalWidth;
    private int logicalHeight;
    private float scaleX = 1.0f;
    private float scaleY = 1.0f;

    private float clearR = 1f, clearG = 1f, clearB = 1f, clearA = 1f;

    public SkikoRenderer(long windowHandle) {
        log.info("Using Skiko OpenGL backend...");
        directContext = DirectContext.Companion.makeGL();
        recreateSurface(windowHandle);
    }

    public void recreateSurface(long windowHandle) {
        if (surface != null) surface.close();
        if (renderTarget != null) renderTarget.close();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer fbw = stack.mallocInt(1);
            IntBuffer fbh = stack.mallocInt(1);

            glfwGetWindowSize(windowHandle, w, h);
            glfwGetFramebufferSize(windowHandle, fbw, fbh);

            this.logicalWidth = w.get(0);
            this.logicalHeight = h.get(0);
            int frameBufferWidth = fbw.get(0);
            int frameBufferHeight = fbh.get(0);

            this.scaleX = (float) frameBufferWidth / logicalWidth;
            this.scaleY = (float) frameBufferHeight / logicalHeight;

            // OpenGL targets physical pixels, not logical window size
            renderTarget = BackendRenderTarget.Companion.makeGL(
                    frameBufferWidth, frameBufferHeight, 0, 8, 0, FramebufferFormat.GR_GL_RGBA8
            );

            surface = Surface.Companion.makeFromBackendRenderTarget(
                    directContext,
                    renderTarget,
                    SurfaceOrigin.BOTTOM_LEFT,
                    SurfaceColorFormat.RGBA_8888,
                    ColorSpace.Companion.getSRGB(),
                    new SurfaceProps()
            );

            canvas = surface.getCanvas();
        }
    }

    @Override
    public void begin() {
        canvas.clear(argb(clearA, clearR, clearG, clearB));
        canvas.save();
        // Automatically scale Skia's grid to match the input coordinate space
        canvas.scale(scaleX, scaleY);
    }

    @Override
    public void end() {
        canvas.restore();
        directContext.flushAndSubmit(surface, false);
    }

    @Override
    public void clear(float r, float g, float b, float a) {
        // Records the color for begin()'s canvas.clear() to use next frame. An immediate
        // glClear() here would be redundant, since begin() unconditionally clears the
        // Skia canvas itself, to whatever color was last recorded, before anything else
        // in the frame draws.
        this.clearR = r;
        this.clearG = g;
        this.clearB = b;
        this.clearA = a;
    }

    private static int argb(float a, float r, float g, float b) {
        return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    @Override
    public void setViewport(int x, int y, int width, int height) {
        // Warning: This signature might need the window handle passed in the future to handle resize events properly
        glViewport(x, y, width, height);
    }

    @Override
    public void drawQuad(float x, float y, float width, float height, Color color, float cornerRadius) {
        if (color == null) return;

        org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint();
        paint.setColor(color.toARGB());
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
        paint.setColor(color.toARGB());
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
            paint.setColor(color.toARGB());
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
            paint.setColor(color.toARGB());
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
    public void drawCircle(float x, float y, float radius, Color color) {
        if (color == null || radius <= 0) return;

        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setColor(color.toARGB());
            paint.setAntiAlias(true);
            canvas.drawCircle(x, y, radius, paint);
        }
    }

    @Override
    public void drawText(String text, float x, float y, Font font, Paint color) {
        if (text == null || text.isEmpty() || color == null || font == null) return;

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
        if (image == null) return;

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
        if (gradient == null) return;

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
        try (org.jetbrains.skia.Paint paint = new org.jetbrains.skia.Paint()) {
            paint.setAlphaf(Math.max(0f, Math.min(1f, opacity)));
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

    public Canvas getCanvas() {
        return canvas;
    }
}