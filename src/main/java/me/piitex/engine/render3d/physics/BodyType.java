package me.piitex.engine.render3d.physics;

/**
 * How a {@link RigidBody} moves.
 */
public enum BodyType {
    /**
     * Moved by the simulation: gravity, forces, impulses and (once collisions are in) other bodies.
     */
    DYNAMIC,
    /**
     * Moved only by its velocity or by you, and never pushed back: lifts, doors, moving platforms. It pushes dynamic
     * bodies as if it had infinite mass.
     */
    KINEMATIC,
    /**
     * Never moves: ground, walls and other scenery.
     */
    STATIC
}
