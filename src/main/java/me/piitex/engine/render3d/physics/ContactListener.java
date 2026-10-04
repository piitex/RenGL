package me.piitex.engine.render3d.physics;

/**
 * Hears when bodies start and stop touching. Give one to the world for every body, or to a body for its own contacts.
 * Events come after each step, once per pair of bodies (not per shape), and only for shapes actually touching: a
 * contact that is only about to touch doesn't count.
 * <p>
 * Each method has an empty default, so implement just the ones you need. It is safe to add, remove or change bodies
 * from inside them.
 */
public interface ContactListener {

    /**
     * Two bodies started touching. {@link Contact#getImpulse()} says how hard they hit; {@link Contact#getOther} finds
     * the other body.
     */
    default void contactBegin(Contact contact) {
    }

    /**
     * Two bodies are still touching, called every step after the first.
     */
    default void contactStay(Contact contact) {
    }

    /**
     * Two bodies stopped touching, or one of them left the world.
     */
    default void contactEnd(RigidBody a, RigidBody b) {
    }

    /**
     * A body entered a trigger.
     */
    default void triggerEnter(RigidBody trigger, RigidBody other) {
    }

    /**
     * A body left a trigger, or one of them left the world.
     */
    default void triggerExit(RigidBody trigger, RigidBody other) {
    }
}
