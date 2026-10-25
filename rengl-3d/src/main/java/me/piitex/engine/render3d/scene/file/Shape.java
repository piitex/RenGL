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
    /** {@link me.piitex.engine.render3d.scene.Mesh#cylinder}: upright, flat at both ends. */
    CYLINDER("Cylinder"),
    /** {@link me.piitex.engine.render3d.scene.Mesh#capsule}: a cylinder with a half sphere on each end. */
    CAPSULE("Capsule"),
    /** {@link me.piitex.engine.render3d.scene.Mesh#cone}: its point up. */
    CONE("Cone"),
    /** {@link me.piitex.engine.render3d.scene.Mesh#terrain}: ground raised from a height map picture. */
    TERRAIN("Terrain"),
    /** A model from a file, which comes with its own meshes, materials and nodes. */
    MODEL("Model"),
    /** The nodes of another scene file, kept in one file and used again wherever it is wanted: a house, a tree, a lamp post. */
    PREFAB("Prefab");

    private final String label;

    Shape(String label) {
        this.label = label;
    }

    /** A name for people. */
    public String getLabel() {
        return label;
    }
}
