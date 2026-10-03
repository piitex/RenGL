package me.piitex.engine.render3d.scene;

/**
 * One of the filters the scene shader already contains; {@code id} is what the shader switches on.
 */
record BuiltinShadowFilter(String name, int id) implements ShadowFilter {
}
