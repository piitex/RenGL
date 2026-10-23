package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * How physics moves a node of a scene file: the kind of {@link me.piitex.engine.render3d.physics.RigidBody} it gets
 * when the scene loads, or a {@link me.piitex.engine.render3d.physics.CharacterController}.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public enum BodyKind {
    /** No physics: nothing collides with it. */
    NONE,
    /** Never moves: the ground, walls, a level. */
    STATIC,
    /** Falls, tumbles and is pushed: crates, balls, debris. */
    DYNAMIC,
    /** Moved by the game, pushing what is in its way and carrying what stands on it: a lift, a door, a platform. */
    KINEMATIC,
    /** A character the game walks around, standing on its feet at the node's position. */
    CHARACTER
}
