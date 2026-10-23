package me.piitex.engine.render3d.scene;

/**
 * One of the falloffs the scene shader already contains; {@code id} is what the shader switches on.
 */
record BuiltinLightFalloff(String name, int id) implements LightFalloff {
}
