package me.piitex.engine.graphics;

/**
 * How a finished layer of drawing is combined with what is already underneath it, for
 * {@link me.piitex.engine.gl.renderer.Renderer#pushBlendLayer}. The modes are the ones image editors and Tiled's layer
 * "Mode" setting have: {@link #NORMAL} just covers what is below, the others mix their colors with it.
 */
public enum BlendMode {
    /** Covers what is below, as far as its opacity allows. */
    NORMAL,
    /** Adds the colors: glows, light beams and fire. Never darker than what is below. */
    ADD,
    /** Multiplies the colors: shadows and tints. Never lighter than what is below. */
    MULTIPLY,
    /** Inverts, multiplies and inverts back: a soft brightening. */
    SCREEN,
    /** Multiplies the dark parts of what is below and screens the light ones. */
    OVERLAY,
    /** Keeps the darker of each color. */
    DARKEN,
    /** Keeps the lighter of each color. */
    LIGHTEN,
    /** Brightens what is below by the layer's color. */
    COLOR_DODGE,
    /** Darkens what is below by the layer's color. */
    COLOR_BURN,
    /** Overlay decided by the layer's own colors instead of the ones below. */
    HARD_LIGHT,
    /** A gentler hard light. */
    SOFT_LIGHT,
    /** The distance between the colors. */
    DIFFERENCE,
    /** Like difference, with less contrast. */
    EXCLUSION;

    /**
     * The mode Tiled names with {@code text} ("multiply", "color-dodge", ...), or {@link #NORMAL} for an unknown or
     * missing name.
     */
    public static BlendMode fromName(String text) {
        if (text == null) return NORMAL;
        String name = text.trim().replace('-', '_').replace(' ', '_').toUpperCase(java.util.Locale.ROOT);
        for (BlendMode mode : values()) if (mode.name().equals(name)) return mode;
        return NORMAL;
    }
}
