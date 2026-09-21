package me.piitex.engine.audio;

/**
 * A volume group. Every {@link Playback} belongs to one channel, and each channel has its own volume that
 * scales everything playing on it (see {@link Audio#setVolume(AudioChannel, float)}). This is what a game's
 * "Music / Effects / Voice" sliders control.
 */
public enum AudioChannel {

    /**
     * Sound effects. The default channel for a {@link Sound}.
     */
    SFX,

    /**
     * Background music. Every {@link Music} track plays on this channel.
     */
    MUSIC,

    /**
     * Spoken dialogue.
     */
    VOICE,

    /**
     * Interface sounds such as clicks and hovers.
     */
    UI
}
