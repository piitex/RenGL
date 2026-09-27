package me.piitex.engine.scheduler;

import me.piitex.engine.math.Vec2;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * How a {@link Tween} blends between two values of a type: {@code from} at 0, {@code to} at 1. An {@link
 * me.piitex.engine.ui.animation.Easing Easing} that overshoots, such as {@code EASE_OUT_BACK}, passes values a little
 * outside 0-1.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
@FunctionalInterface
public interface Lerp<T> {

    /**
     * Straight-line blend between two points.
     */
    Lerp<Vec2> VEC2 = Vec2::lerp;

    /**
     * Blends each of red, green, blue and alpha, kept within 0-1.
     */
    Lerp<Color> COLOR = (from, to, t) -> new Color(
            channel(from.getR(), to.getR(), t), channel(from.getG(), to.getG(), t),
            channel(from.getB(), to.getB(), t), channel(from.getA(), to.getA(), t));

    T lerp(T from, T to, float t);

    private static float channel(float from, float to, float t) {
        return Math.max(0f, Math.min(1f, from + (to - from) * t));
    }
}
