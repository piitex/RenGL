package me.piitex.engine.math;

import me.piitex.engine.ui.animation.Easing;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * Small number helpers that games reach for constantly: blending, clamping, moving towards a target, smoothing
 * that behaves the same at any frame rate, and the timing of simple animations.
 */
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

    /**
     * The distance from ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}). The helpers from here to
     * {@link #lerpAngle} take plain numbers, so hot loops over many objects needn't make a {@link Vec2} for each.
     */
    public static float distance(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * The distance squared, which is cheaper than {@link #distance} and enough to compare distances or test a range:
     * {@code distanceSquared(x, y, enemy.x, enemy.y) < range * range}.
     */
    public static float distanceSquared(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        return dx * dx + dy * dy;
    }

    /**
     * The length of the vector ({@code x}, {@code y}), such as a speed from a velocity. Faster than
     * {@link Math#hypot}, which guards against overflow that game numbers never reach.
     */
    public static float length(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    /**
     * The direction from ({@code fromX}, {@code fromY}) to ({@code toX}, {@code toY}) in degrees, clockwise from the
     * positive x axis like {@link Vec2#angle()}: 0 is right, 90 down, from -180 to 180. 0 when the points are the same.
     */
    public static float angleTo(float fromX, float fromY, float toX, float toY) {
        return (float) Math.toDegrees(Math.atan2(toY - fromY, toX - fromX));
    }

    /**
     * The shortest turn from heading {@code from} to heading {@code to}, in degrees from -180 to 180: positive turns
     * clockwise on screen.
     */
    public static float angleDifference(float from, float to) {
        return wrapAngle(to - from);
    }

    /**
     * Heading {@code current} turned towards {@code target} the shortest way round by at most {@code maxStep}
     * degrees, without overshooting: a turret that turns at a set speed,
     * {@code aim = rotateTowards(aim, angleTo(x, y, player.x, player.y), 180f * step)}. The result is brought into
     * -180 to 180 only when it reaches the target; otherwise it carries on from {@code current}.
     */
    public static float rotateTowards(float current, float target, float maxStep) {
        float difference = angleDifference(current, target);
        if (Math.abs(difference) <= maxStep) return wrapAngle(target);
        return current + Math.copySign(maxStep, difference);
    }

    /**
     * The heading {@code t} of the way from {@code from} to {@code to}, going the shortest way round: from 170 to
     * -170 passes 180 rather than 0. {@code t} is not clamped.
     */
    public static float lerpAngle(float from, float to, float t) {
        return from + angleDifference(from, to) * t;
    }

    /**
     * How far through an animation that starts {@code delay} seconds in and lasts {@code duration} seconds is at
     * {@code time}: 0 until it starts, rising evenly to 1, and 1 from then on. {@code time} is whatever clock the
     * animation runs on, such as {@link me.piitex.engine.game.Scene#getAge()}. A {@code duration} of 0 or less jumps
     * from 0 to 1 at {@code delay}.
     * <pre>{@code
     * float rise = MathUtil.progress(getAge(), 0.1f * index, 0.35f); // each card starts a little after the one before
     * }</pre>
     */
    public static float progress(float time, float delay, float duration) {
        if (duration <= 0f) return time >= delay ? 1f : 0f;
        return clamp((time - delay) / duration, 0f, 1f);
    }

    /**
     * {@link #progress(float, float, float)} shaped by {@code easing}: {@code progress(age, 0f, 0.4f,
     * Easing.EASE_OUT_CUBIC)} starts fast and settles gently at 1.
     */
    public static float progress(float time, float delay, float duration, Easing easing) {
        return easing.apply(progress(time, delay, duration));
    }

    /**
     * A value that rises smoothly from 0 to 1 and falls back to 0 every {@code period} seconds, for anything that
     * breathes, blinks or throbs: {@code color.withOpacity(0.5f + 0.5f * pulse(time, 2f))}. It is 0 when {@code time}
     * is 0 and 1 half a period later. Add to {@code time} to put several pulses out of step with each other.
     *
     * @throws IllegalArgumentException if {@code period} is not above 0
     */
    public static float pulse(float time, float period) {
        if (!(period > 0f)) throw new IllegalArgumentException("period must be above 0: " + period);
        return 0.5f - 0.5f * (float) Math.cos(time / period * 2.0 * Math.PI);
    }

    /**
     * The opacity of something that lives for {@code lifetime} seconds, at {@code time} seconds into its life: it
     * fades in over the first {@code fadeIn} seconds, stays at 1, and fades out over the last {@code fadeOut}
     * seconds. 0 before it starts and after it ends. When the fades overlap, as in a life shorter than both, they
     * multiply.
     * <pre>{@code
     * float opacity = MathUtil.fadeInOut(banner.age, 3f, 0.25f, 0.5f);
     * }</pre>
     */
    public static float fadeInOut(float time, float lifetime, float fadeIn, float fadeOut) {
        return progress(time, 0f, fadeIn) * (1f - progress(time, lifetime - fadeOut, fadeOut));
    }
}
