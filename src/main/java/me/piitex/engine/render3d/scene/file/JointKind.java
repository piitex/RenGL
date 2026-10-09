package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * What a {@link SceneJoint} joins its bodies with.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public enum JointKind {
    /** {@link me.piitex.engine.render3d.physics.BallJoint}: pinned at a point, free to swing. */
    BALL,
    /** {@link me.piitex.engine.render3d.physics.HingeJoint}: turning around an axis, as a door or a wheel. */
    HINGE,
    /** {@link me.piitex.engine.render3d.physics.SliderJoint}: sliding along an axis without turning, as a drawer. */
    SLIDER,
    /** {@link me.piitex.engine.render3d.physics.DistanceJoint}: a rod, a rope or a spring between two points. */
    DISTANCE,
    /** {@link me.piitex.engine.render3d.physics.FixedJoint}: welded together. */
    FIXED
}
