package me.piitex.engine.ui.color;

import org.jetbrains.skia.Color4f;
import org.jetbrains.skia.ColorSpace;
import org.jetbrains.skia.FilterTileMode;
import org.jetbrains.skia.Gradient;
import org.jetbrains.skia.Shader;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

public class GradientUtil {
    private static final MethodHandle MAKE_LINEAR;

    static {
        MethodHandle handle = null;
        try {
            Method target = null;
            // Scan Shader.Companion for the mangled method name
            for (Method m : Shader.Companion.getClass().getDeclaredMethods()) {
                // Find the 6-parameter variant: (x0, y0, x1, y1, gradient, localMatrix)
                if (m.getName().startsWith("makeLinearGradient") && m.getParameterCount() == 6) {
                    target = m;
                    break;
                }
            }

            if (target == null) {
                throw new IllegalStateException("makeLinearGradient method not found on Shader.Companion");
            }

            target.setAccessible(true);
            handle = MethodHandles.lookup().unreflect(target);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize GradientUtil", e);
        }
        MAKE_LINEAR = handle;
    }

    public static Shader makeLinear(float x0, float y0, float x1, float y1, Gradient gradient) {
        try {
            // Invoke the mangled method, passing null for the nullable Matrix33
            return (Shader) MAKE_LINEAR.invoke(Shader.Companion, x0, y0, x1, y1, gradient, null);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke Skiko gradient shader", t);
        }
    }

    /**
     * Builds a Skia linear-gradient Shader from a LinearGradient (or ScrollingGradient)
     * spanning (x0,y0) to (x1,y1). Centralizing this means the scrolling logic below
     * only has to exist in one place instead of being duplicated across every draw
     * call site that might paint with a gradient.
     * <p>
     * For a plain LinearGradient, the shader spans exactly the given bounds and clamps
     * to the end colors beyond them.
     * <p>
     * For a ScrollingGradient, both endpoints are shifted along the gradient's own
     * axis by the current offset, and the tile mode switches to MIRROR instead of
     * CLAMP. MIRROR is used rather than REPEAT because this is a two-stop gradient:
     * REPEAT would produce a visible hard seam every time the ramp wraps, with the end
     * color snapping straight back to the start color, while MIRROR bounces smoothly
     * back and forth with no seam. Adding more color stops and wanting a hard repeating
     * cycle instead would mean changing that one line.
     * <p>
     * Seamless looping is handled entirely in here, not by the caller: MIRROR's true
     * visual period is 2x the span length (forward once, then reflected once), so the
     * offset is wrapped against that exact, freshly-computed period every frame before
     * it is applied. ScrollingGradient's own wrapDistance is unrelated: it only keeps
     * the raw offset from growing without limit over a long-running session, and does
     * not need to match the span length. A coarse value such as 100_000f is fine, since
     * this method re-derives the true wrap independently. Setting wrapDistance equal to
     * the span length does not produce a seamless loop; it is exactly half of MIRROR's
     * real period, and causes a hard visual jump once every wrapDistance/speed seconds.
     */
    public static Shader buildShader(LinearGradient gradient, float x0, float y0, float x1, float y1) {
        Color start = gradient.getStartColor();
        Color end = gradient.getEndColor();

        Color4f c1 = new Color4f(start.getR(), start.getG(), start.getB(), start.getA());
        Color4f c2 = new Color4f(end.getR(), end.getG(), end.getB(), end.getA());
        Color4f[] colorArray = new Color4f[]{c1, c2};

        float ox0 = x0, oy0 = y0, ox1 = x1, oy1 = y1;
        FilterTileMode tileMode = FilterTileMode.CLAMP;

        if (gradient instanceof ScrollingGradient scrolling) {
            float offset = scrolling.getOffset();
            float dx = x1 - x0;
            float dy = y1 - y0;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 0f) {
                // Shift both endpoints the same amount along the gradient's own
                // direction, so the ramp slides rather than stretching/shearing.
                float nx = dx / len;
                float ny = dy / len;

                // MIRROR tiling plays the ramp forward for one span, then backward
                // for the next, so its true visual period is 2*len, not len. Wrapping
                // the offset against anything else (including ScrollingGradient's own
                // wrapDistance, which only bounds float growth over time and is NOT
                // guaranteed to be a multiple of the live period below) causes a hard
                // jump once every wrap: the shader gets caught mid-reflection and
                // snaps back to the start of the forward ramp. Recomputing the exact
                // period here, from the actual endpoints in use this frame, and
                // wrapping against it every single frame keeps the visible phase
                // continuous regardless of what raw value offset has drifted to.
                float period = 2f * len;
                float effectiveOffset = offset % period;
                if (effectiveOffset < 0f) effectiveOffset += period;

                ox0 = x0 + nx * effectiveOffset;
                oy0 = y0 + ny * effectiveOffset;
                ox1 = x1 + nx * effectiveOffset;
                oy1 = y1 + ny * effectiveOffset;
            }
            tileMode = FilterTileMode.MIRROR;
        }

        Gradient.Colors colors = new Gradient.Colors(colorArray, null, tileMode, ColorSpace.Companion.getSRGB());
        Gradient.Interpolation interpolation = new Gradient.Interpolation(
                Gradient.Interpolation.InPremul.YES,
                Gradient.Interpolation.ColorSpace.SRGB,
                Gradient.Interpolation.HueMethod.SHORTER
        );
        Gradient skiaGradient = new Gradient(colors, interpolation);

        return makeLinear(ox0, oy0, ox1, oy1, skiaGradient);
    }
}