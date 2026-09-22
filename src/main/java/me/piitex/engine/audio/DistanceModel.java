package me.piitex.engine.audio;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.*;

/**
 * How a positioned {@link Playback} gets quieter as it moves away from the listener. Set with
 * {@link Audio#setDistanceModel}; tune each playback with {@link Playback#attenuation}.
 * <p>
 * Every model has a full-volume {@code reference distance}. The <em>clamped</em> variants never go louder than that
 * inside it, and stop getting quieter beyond the {@code max distance}.
 */
public enum DistanceModel {
    /**
     * No fading: distance does not change the volume (direction still does).
     */
    NONE(AL_NONE),
    /**
     * Realistic falloff, like sound in open air. Never quite reaches silence.
     */
    INVERSE(AL_INVERSE_DISTANCE),
    /**
     * {@link #INVERSE}, clamped. The default.
     */
    INVERSE_CLAMPED(AL_INVERSE_DISTANCE_CLAMPED),
    /**
     * Fades in a straight line to silence at the max distance. Easy to reason about in games.
     */
    LINEAR(AL_LINEAR_DISTANCE),
    /**
     * {@link #LINEAR}, clamped.
     */
    LINEAR_CLAMPED(AL_LINEAR_DISTANCE_CLAMPED),
    /**
     * Fades with a power curve controlled by the rolloff.
     */
    EXPONENT(AL_EXPONENT_DISTANCE),
    /**
     * {@link #EXPONENT}, clamped.
     */
    EXPONENT_CLAMPED(AL_EXPONENT_DISTANCE_CLAMPED);

    final int alValue;

    DistanceModel(int alValue) {
        this.alValue = alValue;
    }
}
