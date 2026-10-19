package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * What a node of a scene file draws. A node with no shape is a group: a handle that moves what is under it together.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public enum Shape {
    /** Draws nothing. A group, or a place to attach things to. */
    NONE("Group"),
    /** {@link me.piitex.engine.render3d.scene.Mesh#cube}: a box with sides of equal length. */
    CUBE("Cube"),
    /** {@link me.piitex.engine.render3d.scene.Mesh#sphere}. */
    SPHERE("Sphere"),
    /** {@link me.piitex.engine.render3d.scene.Mesh#plane}: a flat sheet, facing up. */
    PLANE("Plane"),
    /** A model from a file, which comes with its own meshes, materials and nodes. */
    MODEL("Model");

    private final String label;

    Shape(String label) {
        this.label = label;
    }

    /** A name for people. */
    public String getLabel() {
        return label;
    }
}
