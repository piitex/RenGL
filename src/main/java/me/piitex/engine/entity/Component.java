package me.piitex.engine.entity;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * A piece of behavior or data on an {@link Entity}: how it is drawn, what it collides as, how it moves. A game makes
 * its own by extending this class. Fields marked {@link me.piitex.engine.property.Property @Property} are saved
 * with the scene and shown in an editor; the rest are runtime state.
 * <pre>{@code
 * public class Spin extends Component {
 *     @Property public float degreesPerSecond = 90f;
 *
 *     @Override
 *     protected void update(float delta) {
 *         getTransform().rotate(degreesPerSecond * delta);
 *     }
 * }
 * }</pre>
 * A component class needs a public no-argument constructor so a scene file can create it. Every hook runs on the render
 * thread, and only while the component's entity and all its parents are {@link Entity#isActive() active}.
 * <table>
 *   <tr><th>Hook</th><th>When it runs</th></tr>
 *   <tr><td>{@link #onStart()}</td><td>Once, when the component is in a scene that is on the stack: when the scene
 *       enters, or when the component or its entity is added to a scene already on it. Other components of the scene
 *       exist by then, so it is the place to look them up.</td></tr>
 *   <tr><td>{@link #fixedUpdate(float)}</td><td>At the fixed tick rate while the scene is running.</td></tr>
 *   <tr><td>{@link #update(float)}</td><td>Once per frame while the scene is running.</td></tr>
 *   <tr><td>{@link #render(Renderer, float)}</td><td>Every frame the scene is visible, in world coordinates under
 *       the scene's camera, in hierarchy order: parents before children, earlier siblings before later ones, so later
 *       ones are drawn on top.</td></tr>
 *   <tr><td>{@link #onDestroy()}</td><td>Once, after {@code onStart}, when the component is removed, its entity is
 *       destroyed, or the scene leaves the stack.</td></tr>
 * </table>
 */
public abstract class Component {
    Entity entity;
    boolean started;

    protected void onStart() {
    }

    protected void onDestroy() {
    }

    protected void fixedUpdate(float step) {
    }

    protected void update(float delta) {
    }

    protected void render(Renderer renderer, float alpha) {
    }

    /**
     * The entity this component is on, or null before it is added to one.
     */
    public Entity getEntity() {
        return entity;
    }

    /**
     * The scene the component's entity is in, or null.
     */
    public EntityScene getScene() {
        return entity == null ? null : entity.getScene();
    }

    /**
     * The entity's transform.
     *
     * @throws IllegalStateException if the component is not on an entity
     */
    public Transform getTransform() {
        if (entity == null) throw new IllegalStateException(getClass().getSimpleName() + " is not on an entity");
        return entity.getTransform();
    }

    /**
     * Another component of the same entity, or null.
     */
    public <T> T getComponent(Class<T> type) {
        return entity == null ? null : entity.getComponent(type);
    }

    /**
     * Whether {@link #onStart()} has run and {@link #onDestroy()} has not.
     */
    public boolean isStarted() {
        return started;
    }
}
