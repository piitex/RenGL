package me.piitex.engine.math;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * Small number helpers that games reach for constantly: blending, clamping, moving towards a target, and smoothing
 * that behaves the same at any frame rate.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class MathUtil {
    private MathUtil() {
    }

    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * {@code a} at 0, {@code b} at 1, and in between for anything else. {@code t} is not clamped.
     */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * The opposite of {@link #lerp}: where {@code value} sits between {@code a} (0) and {@code b} (1). Not clamped;
     * 0 when {@code a} equals {@code b}.
     */
    public static float inverseLerp(float a, float b, float value) {
        return a == b ? 0f : (value - a) / (b - a);
    }

    /**
     * {@code current} moved towards {@code target} by at most {@code maxStep}, without overshooting: steady movement
     * such as {@code speed = approach(speed, topSpeed, acceleration * step)}.
     */
    public static float approach(float current, float target, float maxStep) {
        return current < target ? Math.min(current + maxStep, target) : Math.max(current - maxStep, target);
    }

    /**
     * {@code current} moved part of the way to {@code target}, so that repeated calls ease in on it: a camera
     * following the player, say. {@code rate} is how quickly: the gap shrinks by a factor of e every
     * {@code 1 / rate} seconds. Unlike {@code lerp(current, target, 0.1f)} each frame, it gives the same motion at any
     * frame rate.
     */
    public static float damp(float current, float target, float rate, float delta) {
        return lerp(current, target, 1f - (float) Math.exp(-rate * delta));
    }

    /**
     * {@code degrees} brought into the range -180 (exclusive) to 180 (inclusive), such as the difference between two
     * headings: {@code wrapAngle(target - current)} is the shortest turn from one to the other.
     */
    public static float wrapAngle(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped > 180f) wrapped -= 360f;
        else if (wrapped <= -180f) wrapped += 360f;
        return wrapped;
    }
}
