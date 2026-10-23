package me.piitex.engine.ui.animation;

/**
 * Reshapes a transition's linear 0-1 time into a 0-1 progress curve, which is the
 * difference between a value that moves at constant speed and one that eases in and out.
 * It takes any lambda, so a custom curve is simply {@code t -> t * t * t}, and the
 * constants below cover the common ones. Implementations should map 0 to 0 and 1 to 1.
 */
@FunctionalInterface
public interface Easing {

    Easing LINEAR = t -> t;

    /**
     * Starts slow, ends fast.
     */
    Easing EASE_IN = t -> t * t;

    /**
     * Starts fast, settles slowly.
     */
    Easing EASE_OUT = t -> 1f - (1f - t) * (1f - t);

    /**
     * Slow at both ends.
     */
    Easing EASE_IN_OUT = t -> t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2) / 2f;

    /**
     * As {@link #EASE_IN}, more strongly.
     */
    Easing EASE_IN_CUBIC = t -> t * t * t;

    /**
     * As {@link #EASE_OUT}, more strongly.
     */
    Easing EASE_OUT_CUBIC = t -> 1f - (1f - t) * (1f - t) * (1f - t);

    /**
     * As {@link #EASE_IN_OUT}, more strongly.
     */
    Easing EASE_IN_OUT_CUBIC = t -> t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;

    /**
     * Shoots about 10% past the end, then settles back: a pop.
     */
    Easing EASE_OUT_BACK = t -> {
        float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    };

    /**
     * Overshoots and wobbles back and forth before it settles, like a spring.
     */
    Easing EASE_OUT_ELASTIC = t -> {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1);
    };

    /**
     * Lands and bounces a few times, each lower, like a dropped ball.
     */
    Easing EASE_OUT_BOUNCE = t -> {
        float n1 = 7.5625f, d1 = 2.75f;
        if (t < 1f / d1) return n1 * t * t;
        if (t < 2f / d1) return n1 * (t -= 1.5f / d1) * t + 0.75f;
        if (t < 2.5f / d1) return n1 * (t -= 2.25f / d1) * t + 0.9375f;
        return n1 * (t -= 2.625f / d1) * t + 0.984375f;
    };

    /**
     * @param t linear time in [0, 1] @return eased progress, normally in [0, 1]
     */
    float apply(float t);
}
