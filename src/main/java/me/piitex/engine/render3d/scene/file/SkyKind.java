package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * How a scene file draws the sky behind everything, in place of the plain background color.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public enum SkyKind {
    /** No sky: the background color. */
    NONE,
    /** Three colors blended from the top of the sky to the horizon and the ground. */
    GRADIENT,
    /** One panorama picture, twice as wide as it is tall, wrapped around the scene. */
    PANORAMA,
    /** Six square pictures of the same size, one for each side of a box around the scene. */
    CUBE
}
