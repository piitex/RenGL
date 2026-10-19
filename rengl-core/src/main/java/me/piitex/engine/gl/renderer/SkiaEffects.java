package me.piitex.engine.gl.renderer;

import me.piitex.engine.graphics.ShaderEffect;
import me.piitex.engine.utils.flags.GeneratedFragments;
import org.jetbrains.skia.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * The Skia side of {@link Renderer#drawShader}, {@link Renderer#pushEffect} and {@link Renderer#popEffect}, shared by
 * every renderer that draws on a Skia {@link Canvas}. Not for use outside renderer implementations. Render thread
 * only.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class SkiaEffects {
    /**
     * RuntimeShaderBuilder.makeShader(localMatrix). Its JVM name is mangled because the matrix is a Kotlin value
     * class, so Java can't call it directly; see {@link me.piitex.engine.ui.color.GradientUtil}.
     */
    private static final MethodHandle MAKE_SHADER;

    static {
        try {
            Method target = null;
            for (Method m : RuntimeShaderBuilder.class.getDeclaredMethods()) {
                if (m.getName().startsWith("makeShader") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == float[].class) {
                    target = m;
                    break;
                }
            }
            if (target == null) throw new IllegalStateException("RuntimeShaderBuilder.makeShader(matrix) not found");
            target.setAccessible(true);
            MAKE_SHADER = MethodHandles.lookup().unreflect(target);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private SkiaEffects() {
    }

    public static void drawShader(Canvas canvas, ShaderEffect effect, float x, float y, float width, float height, float cornerRadius, float alpha) {
        if (effect == null || effect.isClosed() || !(width > 0) || !(height > 0)) return;
        Shader shader;
        try {
            shader = (Shader) MAKE_SHADER.invoke(effect.getBuilder(), (float[]) null);
        } catch (Throwable t) {
            throw new IllegalStateException("Could not make the effect's shader", t);
        }
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            paint.setShader(shader);
            paint.setAlphaf(Math.clamp(alpha, 0f, 1f));
            canvas.save();
            canvas.translate(x, y); // so the shader's coordinates start at the rectangle's corner
            if (cornerRadius > 0) {
                canvas.drawRRect(RRect.makeLTRB(0, 0, width, height, cornerRadius), paint);
            } else {
                canvas.drawRect(Rect.makeWH(width, height), paint);
            }
            canvas.restore();
        } finally {
            shader.close();
        }
    }

    /**
     * Two saves: a clip to the rectangle, since the filtered layer may cover more than it, and the layer itself.
     * {@link #popEffect} restores both.
     */
    public static void pushEffect(Canvas canvas, ShaderEffect effect, float x, float y, float width, float height) {
        Rect bounds = Rect.makeXYWH(x, y, width, height);
        canvas.save();
        canvas.clipRect(bounds, ClipMode.INTERSECT, true);
        if (effect == null || effect.isClosed() || !effect.hasContent()) {
            canvas.save(); // draw unchanged, keeping the saves paired
            return;
        }
        try (Paint paint = new Paint();
             ImageFilter filter = ImageFilter.Companion.makeRuntimeShader(effect.getBuilder(), ShaderEffect.CONTENT, null)) {
            paint.setImageFilter(filter);
            canvas.saveLayer(bounds, paint);
        }
    }

    public static void popEffect(Canvas canvas) {
        canvas.restore();
        canvas.restore();
    }
}
