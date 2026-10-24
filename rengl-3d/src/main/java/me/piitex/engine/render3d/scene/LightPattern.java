package me.piitex.engine.render3d.scene;

import me.piitex.engine.math.MathUtil;

/**
 * How bright a blinking light is at a moment: 0 is off and 1 is full strength. A {@link LightAnimation} reads one of
 * these every frame, so a pattern is only a function of time. Ready-made ones are {@link #blink(float)},
 * {@link #pulse(float)}, {@link #flicker(float, float, long)}, {@link #faulty(float, long)} and
 * {@link #sequence(float, float...)}; write your own as a lambda:
 * <pre>{@code
 * LightPattern heartbeat = time -> (time % 1f) < 0.1f || (time % 1f) > 0.2f && (time % 1f) < 0.3f ? 1f : 0f;
 * }</pre>
 * Patterns that look random (flicker, faulty) are worked out from the time and a seed, so the same time always gives
 * the same brightness: a paused or restarted animation repeats itself exactly.
 */
@FunctionalInterface
public interface LightPattern {
    /**
     * @param time seconds since the animation started, never below 0 unless the animation was set back
     * @return the brightness, 0 for off to 1 for full; anything outside is clamped
     */
    float level(float time);

    /**
     * Always full strength.
     */
    static LightPattern steady() {
        return time -> 1f;
    }

    /**
     * Full strength for {@code onSeconds}, then off for {@code offSeconds}, over and over, starting on.
     *
     * @throws IllegalArgumentException if either time is negative or both are 0
     */
    static LightPattern blink(float onSeconds, float offSeconds) {
        if (!(onSeconds >= 0f) || !(offSeconds >= 0f) || onSeconds + offSeconds <= 0f) {
            throw new IllegalArgumentException("on and off times must not be negative, and not both 0: " + onSeconds + ", " + offSeconds);
        }
        float period = onSeconds + offSeconds;
        return time -> wrap(time, period) < onSeconds ? 1f : 0f;
    }

    /**
     * On for half of {@code periodSeconds} and off for the other half, starting on: the blink of a status LED.
     *
     * @throws IllegalArgumentException if the period is not above 0
     */
    static LightPattern blink(float periodSeconds) {
        if (!(periodSeconds > 0f)) throw new IllegalArgumentException("period must be above 0: " + periodSeconds);
        return blink(periodSeconds / 2f, periodSeconds / 2f);
    }

    /**
     * Rises smoothly to full and falls back to off every {@code periodSeconds}, like something breathing. It starts
     * off; see {@link MathUtil#pulse}.
     *
     * @throws IllegalArgumentException if the period is not above 0
     */
    static LightPattern pulse(float periodSeconds) {
        if (!(periodSeconds > 0f)) throw new IllegalArgumentException("period must be above 0: " + periodSeconds);
        return time -> MathUtil.pulse(time, periodSeconds);
    }

    /**
     * An unsteady light, like a tube on a bad supply: {@code rate} times a second it picks a new brightness between
     * {@code 1 - depth} and 1, at random but the same every run.
     *
     * @param rate  new brightnesses per second, above 0
     * @param depth how far down it can dip, 0 for none to 1 for all the way to off
     * @param seed  different seeds give lights that do not flicker together
     * @throws IllegalArgumentException if the rate is not above 0
     */
    static LightPattern flicker(float rate, float depth, long seed) {
        if (!(rate > 0f)) throw new IllegalArgumentException("rate must be above 0: " + rate);
        float dip = Math.max(0f, Math.min(1f, depth));
        return time -> 1f - dip * noise(seed, (long) Math.floor(time * rate));
    }

    /**
     * A dying neon sign or fluorescent tube: steady for most of the time, then now and then a burst of fast stutters.
     * Time is cut into slots of {@code periodSeconds}; about one slot in three is a burst (random, but the same every
     * run), 20 times a second each on or off, and the others stay on.
     *
     * @param seed different seeds give lights that fail at different times
     * @throws IllegalArgumentException if the period is not above 0
     */
    static LightPattern faulty(float periodSeconds, long seed) {
        if (!(periodSeconds > 0f)) throw new IllegalArgumentException("period must be above 0: " + periodSeconds);
        return time -> {
            long slot = (long) Math.floor(time / periodSeconds);
            if (noise(seed, slot) >= 0.33f) return 1f;
            long step = (long) Math.floor(wrap(time, periodSeconds) * 20f);
            return noise(seed ^ 0x5DEECE66DL, slot * 4096L + step) < 0.5f ? 0f : 1f;
        };
    }

    /**
     * Steps through the {@code levels} one after another, each held for {@code stepSeconds}, and starts again at the
     * first: {@code sequence(0.25f, 1, 0, 1, 0, 0, 0)} is two short flashes and a pause.
     *
     * @throws IllegalArgumentException if the step is not above 0 or there are no levels
     */
    static LightPattern sequence(float stepSeconds, float... levels) {
        if (!(stepSeconds > 0f)) throw new IllegalArgumentException("step must be above 0: " + stepSeconds);
        if (levels.length == 0) throw new IllegalArgumentException("a sequence needs at least one level");
        float[] steps = levels.clone();
        return time -> steps[(int) Math.floorMod((long) Math.floor(time / stepSeconds), (long) steps.length)];
    }

    private static float wrap(float time, float period) {
        float t = time % period;
        return t < 0f ? t + period : t;
    }

    /**
     * A repeatable number from 0 up to but not including 1 for each seed and step.
     */
    private static float noise(long seed, long step) {
        long z = seed + step * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 40) / (float) (1 << 24);
    }
}
