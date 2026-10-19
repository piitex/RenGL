package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * Whether a light of a scene file casts shadows, and how sharp they are.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public enum LightShadow {
    /** No shadows. */
    OFF,
    /** Shadows with the scene's own settings. */
    SCENE,
    LOW,
    MEDIUM,
    HIGH,
    ULTRA
}
