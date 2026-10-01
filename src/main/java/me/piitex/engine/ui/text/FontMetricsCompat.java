package me.piitex.engine.ui.text;

import org.jetbrains.skia.Font;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * A font's vertical metrics, read the way Java can. Since Skiko 0.152 {@code FontMetrics} is a Kotlin value class, and
 * {@code Font.getMetrics()} comes out of the compiler under a mangled name ({@code getMetrics-l-OL_C8}) that Java source
 * can't call. This looks that method up once and reads the {@code float[]} it returns, which is laid out as top, ascent,
 * descent, bottom, leading, ...
 * <p>
 * Same values and signs as Skia's: ascent is negative (above the baseline), descent positive.
 */
public final class FontMetricsCompat {
    private static final int TOP = 0, ASCENT = 1, DESCENT = 2, BOTTOM = 3, LEADING = 4;
    private static final MethodHandle GET_METRICS = lookup();

    private final float[] values;

    private FontMetricsCompat(float[] values) {
        this.values = values;
    }

    public static FontMetricsCompat of(Font font) {
        try {
            return new FontMetricsCompat((float[]) GET_METRICS.invokeExact(font));
        } catch (Throwable t) {
            throw new IllegalStateException("Could not read the font's metrics", t);
        }
    }

    /**
     * Greatest extent above the origin of any glyph, typically negative.
     */
    public float getTop() {
        return values[TOP];
    }

    /**
     * Distance to reserve above the baseline, typically negative.
     */
    public float getAscent() {
        return values[ASCENT];
    }

    /**
     * Distance to reserve below the baseline, typically positive.
     */
    public float getDescent() {
        return values[DESCENT];
    }

    /**
     * Greatest extent below the origin of any glyph, typically positive.
     */
    public float getBottom() {
        return values[BOTTOM];
    }

    /**
     * Distance to add between lines, typically positive or zero.
     */
    public float getLeading() {
        return values[LEADING];
    }

    /**
     * Ascent to descent: the height of one line of text, without leading.
     */
    public float getHeight() {
        return values[DESCENT] - values[ASCENT];
    }

    private static MethodHandle lookup() {
        for (Method method : Font.class.getMethods()) {
            if (method.getName().startsWith("getMetrics") && method.getParameterCount() == 0
                    && method.getReturnType() == float[].class) {
                try {
                    return MethodHandles.publicLookup().unreflect(method)
                            .asType(java.lang.invoke.MethodType.methodType(float[].class, Font.class));
                } catch (IllegalAccessException e) {
                    throw new ExceptionInInitializerError(e);
                }
            }
        }
        throw new ExceptionInInitializerError("This Skiko has no Font metrics accessor returning float[]");
    }
}
