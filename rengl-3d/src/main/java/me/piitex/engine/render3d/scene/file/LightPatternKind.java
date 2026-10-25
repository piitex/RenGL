package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * How a light in a scene file changes its brightness while the game runs: one of the {@link me.piitex.engine.render3d.scene.LightPattern}s
 * that need nothing more than a length of time.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public enum LightPatternKind {
    /** Always at full strength. */
    NONE("Steady"),
    /** On for half of the period and off for the other half. */
    BLINK("Blink"),
    /** Fades up and down smoothly. */
    PULSE("Pulse"),
    /** Wavers like a flame or a candle. The period is how long a flicker lasts. */
    FLICKER("Flicker"),
    /** Mostly on, with the odd stutter and dropout, like a broken neon sign. */
    FAULTY("Faulty");

    private final String label;

    LightPatternKind(String label) {
        this.label = label;
    }

    /** A name for people. */
    public String getLabel() {
        return label;
    }
}
