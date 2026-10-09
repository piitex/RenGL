package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * How a point or spot light of a scene file fades with distance.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public enum Falloff {
    /** Like real light, smoothly reaching nothing at its range. */
    WINDOWED_INVERSE_SQUARE,
    /** Dims evenly from full to nothing at its range. */
    LINEAR,
    /** Full strength out to its range, then gone. */
    NONE
}
