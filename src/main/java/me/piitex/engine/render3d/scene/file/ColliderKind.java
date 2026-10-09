package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * The shape a node of a scene file collides as.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public enum ColliderKind {
    /**
     * Fits what the node draws: a cube's box, a sphere's ball, a plane's sheet (as a thin box under it), and a model's
     * own triangles if it doesn't move or a hull around them if it does. A group gets a box of the collider size.
     */
    AUTO,
    /** A box of the collider size. */
    BOX,
    /** A ball of the collider radius. */
    SPHERE,
    /** A capsule of the collider radius and height, standing along Y. */
    CAPSULE,
    /** The smallest convex shape around what the node draws: a rock, a crate of odd shape. */
    HULL,
    /** What the node draws, triangle by triangle: a level or terrain. A body with it can't be dynamic. */
    MESH
}
