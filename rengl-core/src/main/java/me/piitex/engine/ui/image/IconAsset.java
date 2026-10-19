package me.piitex.engine.ui.image;

/**
 * A reference to an SVG icon bundled on the classpath (inside this engine's own jar, or
 * any jar or resources directory on the consuming application's classpath), as opposed
 * to an external file on disk, which {@link me.piitex.engine.ui.overlays.IconOverlay}'s
 * File and String constructors handle. {@link me.piitex.engine.ui.icons.Feather} is the
 * built-in set shipped with the engine, and an application can declare its own
 * {@code IconAsset} constants the same way for its own bundled icon resources.
 */
public record IconAsset(String resourcePath) {
}
