package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * What a scene file asks to enlarge the scene with when it is drawn smaller than it is shown. A file does not name a vendor's
 * upscaler: the one that is best on the machine the game runs on is used, so the same file works on an NVIDIA, an AMD and an
 * Intel GPU, and with or without the vendor libraries.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public enum UpscalerChoice {
    /** None: the scene is enlarged smoothly, which is soft. */
    NONE("None"),
    /** The temporal upscaler that comes with the 3D layer. It works on every GPU. */
    BUILT_IN("Built-in"),
    /** The best upscaler the GPU can run (DLSS, FSR) where the vendor module and libraries are present, and the built-in one anywhere else. */
    BEST("Best Available");

    private final String label;

    UpscalerChoice(String label) {
        this.label = label;
    }

    /** A name for people. */
    public String getLabel() {
        return label;
    }
}
