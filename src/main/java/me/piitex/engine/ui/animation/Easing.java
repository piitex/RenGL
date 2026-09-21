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
     * @param t linear time in [0, 1] @return eased progress, normally in [0, 1]
     */
    float apply(float t);
}
