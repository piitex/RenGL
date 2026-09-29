package me.piitex.engine.gl.renderer;

import org.jetbrains.skia.*;

/**
 * The Skia side of {@link Renderer#drawPolygon(float[], int, me.piitex.engine.ui.color.Color)} and
 * {@link Renderer#drawPolygonOutline(float[], int, me.piitex.engine.ui.color.Color, float)}, shared by every renderer
 * that draws on a Skia {@link Canvas}. Not for use outside renderer implementations. Render thread only.
 */
public final class SkiaShapes {
    private SkiaShapes() {
    }

    /**
     * Draws the closed polygon through the first {@code pointCount} points: filled when {@code thickness <= 0},
     * otherwise its outline, {@code thickness} wide with sharp corners. The caller checks the arguments.
     */
    public static void drawPolygon(Canvas canvas, float[] points, int pointCount, int argb, float thickness) {
        try (PathBuilder builder = new PathBuilder();
             Paint paint = new Paint()) {
            builder.moveTo(points[0], points[1]);
            for (int i = 1; i < pointCount; i++) {
                builder.lineTo(points[i * 2], points[i * 2 + 1]);
            }
            builder.closePath();
            paint.setColor(argb);
            paint.setAntiAlias(true);
            if (thickness > 0) {
                paint.setMode(PaintMode.STROKE);
                paint.setStrokeWidth(thickness);
                paint.setStrokeJoin(PaintStrokeJoin.MITER);
            }
            // Closed through its Managed superclass rather than try-with-resources or path.close(): any member lookup
            // on Path makes javac load its Kotlin marker interface, and kotlin-stdlib is only on the runtime classpath.
            Path path = builder.detach();
            org.jetbrains.skia.impl.Managed pathHandle = path;
            try {
                canvas.drawPath(path, paint);
            } finally {
                pathHandle.close();
            }
        }
    }
}
