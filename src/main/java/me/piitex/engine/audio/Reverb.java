package me.piitex.engine.audio;

/**
 * What a room does to sound: how long it rings, how bright the tail is and how loud it is. Hand one to
 * {@link Audio#setReverb} and every sound in the world gets it, scaled by each playback's own
 * {@link Playback#reverb reverb} amount. Built from a preset and adjusted with the setters, which return this so they
 * chain:
 * <pre>{@code
 * Audio.setReverb(Reverb.cave().decayTime(4f));
 * }</pre>
 * The values are those of OpenAL's standard reverb effect and are clamped to its ranges. A reverb is a plain value:
 * changing one after it is set does nothing until it is set again.
 */
public final class Reverb {
    float density = 1f, diffusion = 1f, gain = 0.32f, gainHF = 0.89f;
    float decayTime = 1.49f, decayHFRatio = 0.83f;
    float reflectionsGain = 0.05f, reflectionsDelay = 0.007f, lateGain = 1.26f, lateDelay = 0.011f;
    float airAbsorptionGainHF = 0.994f, roomRolloff = 0f;
    boolean decayHFLimit = true;

    /**
     * The default room of the effect: a medium room with a short, even decay.
     */
    public static Reverb room() {
        return new Reverb();
    }

    /**
     * A tiled, hard-walled room: bright and quickly ringing.
     */
    public static Reverb bathroom() {
        return new Reverb().density(0.17f).diffusion(0.8f).gain(0.32f).gainHF(0.25f).decayTime(1.5f).decayHFRatio(0.54f)
                .reflections(0.65f, 0.007f).late(3.28f, 0.011f);
    }

    /**
     * A large hall with a long, smooth tail.
     */
    public static Reverb hall() {
        return new Reverb().density(1f).diffusion(1f).gain(0.32f).gainHF(0.5f).decayTime(3.9f).decayHFRatio(0.7f)
                .reflections(0.24f, 0.02f).late(0.7f, 0.03f);
    }

    /**
     * Stone all round: a long, dark echo with a late onset.
     */
    public static Reverb cave() {
        return new Reverb().density(1f).diffusion(1f).gain(0.32f).gainHF(0.7f).decayTime(2.9f).decayHFRatio(1.3f)
                .reflections(0.5f, 0.015f).late(1.7f, 0.022f);
    }

    /**
     * A big open space with little to bounce off: a thin, quiet tail.
     */
    public static Reverb outdoors() {
        return new Reverb().density(0.36f).diffusion(0.42f).gain(0.2f).gainHF(0.5f).decayTime(1.5f).decayHFRatio(0.21f)
                .reflections(0.08f, 0.007f).late(0.2f, 0.011f);
    }

    /**
     * Padded walls: the tail dies in a fraction of a second.
     */
    public static Reverb padded() {
        return new Reverb().density(0.17f).diffusion(1f).gain(0.2f).gainHF(0.1f).decayTime(0.4f).decayHFRatio(0.5f)
                .reflections(0.25f, 0.002f).late(0.6f, 0.002f);
    }

    /** Echo density, 0 to 1: how many reflections per second. */
    public Reverb density(float v) {
        density = clamp(v, 0f, 1f);
        return this;
    }

    /** Smoothness of the tail, 0 to 1. */
    public Reverb diffusion(float v) {
        diffusion = clamp(v, 0f, 1f);
        return this;
    }

    /** Overall loudness of the effect, 0 to 1. */
    public Reverb gain(float v) {
        gain = clamp(v, 0f, 1f);
        return this;
    }

    /** How much of the high frequencies the whole effect keeps, 0 to 1. */
    public Reverb gainHF(float v) {
        gainHF = clamp(v, 0f, 1f);
        return this;
    }

    /** Seconds the tail takes to fade away, 0.1 to 20. */
    public Reverb decayTime(float seconds) {
        decayTime = clamp(seconds, 0.1f, 20f);
        return this;
    }

    /** How fast the highs die compared with the lows: below 1 the tail darkens, 0.1 to 2. */
    public Reverb decayHFRatio(float v) {
        decayHFRatio = clamp(v, 0.1f, 2f);
        return this;
    }

    /** The first echoes: how loud (0 to 3.16) and how long after the sound (seconds, 0 to 0.3). */
    public Reverb reflections(float gain, float delaySeconds) {
        reflectionsGain = clamp(gain, 0f, 3.16f);
        reflectionsDelay = clamp(delaySeconds, 0f, 0.3f);
        return this;
    }

    /** The dense tail: how loud (0 to 10) and how long after the first echoes (seconds, 0 to 0.1). */
    public Reverb late(float gain, float delaySeconds) {
        lateGain = clamp(gain, 0f, 10f);
        lateDelay = clamp(delaySeconds, 0f, 0.1f);
        return this;
    }

    /** How much distant sound loses its highs in the air, 0.892 (a lot) to 1 (none). */
    public Reverb airAbsorption(float gainHF) {
        airAbsorptionGainHF = clamp(gainHF, 0.892f, 1f);
        return this;
    }

    /** How much the effect fades with distance from the source: 0 keeps it constant, up to 10. */
    public Reverb roomRolloff(float v) {
        roomRolloff = clamp(v, 0f, 10f);
        return this;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
