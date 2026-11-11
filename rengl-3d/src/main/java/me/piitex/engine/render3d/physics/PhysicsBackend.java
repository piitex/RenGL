package me.piitex.engine.render3d.physics;

import java.util.List;

/**
 * Another solver doing a {@link PhysicsWorld}'s steps in place of the built-in one, such as NVIDIA PhysX from the
 * {@code rengl-physx} module. The world, its {@link RigidBody bodies}, {@link Joint joints} and
 * {@link CharacterController characters} stay what a game talks to: the backend keeps a copy of them in its own
 * engine, moves that copy each step and writes the result back through {@link PhysicsBridge}.
 * <pre>
 * world.setBackend(new PhysXBackend());                 // one world
 * PhysicsWorld.setDefaultBackend(PhysXBackend::new);    // every world made from now on, loaded scenes included
 * </pre>
 * What the world still does itself with a backend: the fixed steps and node interpolation, the step handler, character
 * movement, raycasts and overlap queries (against where the backend left the bodies), and telling the
 * {@link ContactListener listeners} what began and ended. What the backend does: finding contacts, solving them and
 * the joints, sleeping, continuous collision and moving the bodies.
 * <p>
 * A backend belongs to one world and is used from that world's thread.
 */
public interface PhysicsBackend {

    /**
     * A short name for logs and overlays, such as "PhysX 5".
     */
    String name();

    /**
     * Called once, when the backend is given to {@code world}: copy the bodies and joints already in it.
     */
    void attach(PhysicsWorld world);

    /**
     * A body was added to the world.
     */
    void bodyAdded(RigidBody body);

    /**
     * A body is leaving the world. Its joints have been removed already.
     */
    void bodyRemoved(RigidBody body);

    /**
     * A joint was added to the world, with its anchors read against where its bodies are now.
     */
    void jointAdded(Joint joint);

    void jointRemoved(Joint joint);

    /**
     * Moves the world on by {@code dt} seconds, in {@link PhysicsWorld#getSubsteps()} pieces: reads whatever changed on
     * the bodies and joints since the last step (they may have been moved, pushed or reshaped), simulates, and puts each
     * body where it ended up with {@link PhysicsBridge#place}.
     */
    void step(PhysicsWorld world, float dt);

    /**
     * The pairs of shapes touching after the last step, made with {@link PhysicsBridge#contact}. The world works out
     * which began and which ended from this list.
     */
    List<Contact> contacts();

    /**
     * The joints the last step broke. The world takes them out and calls their break handlers.
     */
    List<Joint> brokenJoints();

    /**
     * Frees whatever the backend holds outside the Java heap. The world calls this from {@link PhysicsWorld#close()}
     * and when it is given another backend.
     */
    void close();
}
