package me.piitex.engine.entity;

import me.piitex.engine.math.Vec2;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A thing in an {@link EntityScene}: the player, a wall, a coin, a spawn point. On its own it is only a name and a
 * {@link Transform}; what it looks like and does comes from its {@link Component components}. Entities form a tree:
 * a child moves, turns and scales with its parent.
 * <pre>{@code
 * Entity player = scene.create("Player");
 * player.getTransform().setPosition(100, 200);
 * player.addComponent(new SpriteRenderer(TextureRef.of("assets/hero.png")));
 * player.addComponent(new BoxCollider(24, 32));
 *
 * Entity sword = new Entity("Sword");
 * sword.getTransform().setPosition(16, 0); // 16 units right of the player, turning with it
 * player.addChild(sword);
 * }</pre>
 * <b>Ids.</b> Every entity in a scene has an {@link #getId() id}, unique in that scene and kept when the scene is saved
 * and loaded, so saved references between entities survive. The scene gives one to an entity that has none, or whose
 * id another entity already has, when the entity is added.
 * <p>
 * An entity is in at most one scene and has at most one parent. Change entities on the render thread.
 */
public final class Entity {
    private static final Entity[] NO_ENTITIES = new Entity[0];
    private static final Component[] NO_COMPONENTS = new Component[0];

    long id;
    private String name;
    private boolean active = true;
    private final Transform transform = new Transform();
    private final List<Component> components = new ArrayList<>();
    private final List<Entity> children = new ArrayList<>();
    private Entity parent;
    EntityScene scene;
    private boolean destroyed;

    public Entity() {
        this("Entity");
    }

    public Entity(String name) {
        setName(name);
    }

    /**
     * The entity's id in its scene, or 0 while it has none.
     */
    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /**
     * Names need not be unique. {@link EntityScene#find} returns the first match.
     */
    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    /**
     * Whether this entity's own flag is on. It only runs when its parents are active too; see
     * {@link #isActiveInTree()}.
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Turns the entity on or off. An inactive entity and its children are not updated or drawn, but keep their
     * components and stay in the scene.
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Whether this entity and every parent above it are active.
     */
    public boolean isActiveInTree() {
        for (Entity e = this; e != null; e = e.parent) {
            if (!e.active) return false;
        }
        return true;
    }

    public Transform getTransform() {
        return transform;
    }

    /**
     * The scene the entity is in, or null.
     */
    public EntityScene getScene() {
        return scene;
    }

    /**
     * Whether {@link #destroy()} has been called.
     */
    public boolean isDestroyed() {
        return destroyed;
    }

    // --- Components -----------------------------------------------------------------------------------------------

    /**
     * Adds a component and returns it. If the entity is in a scene that is on the stack, the component's
     * {@link Component#onStart()} runs now.
     *
     * @throws IllegalArgumentException if the component is already on an entity
     */
    public <T extends Component> T addComponent(T component) {
        if (component == null) throw new IllegalArgumentException("component must not be null");
        if (component.entity != null) {
            throw new IllegalArgumentException(component.getClass().getSimpleName() + " is already on " + component.entity);
        }
        checkAlive();
        component.entity = this;
        components.add(component);
        if (scene != null && scene.isStarted()) scene.start(component);
        return component;
    }

    /**
     * Removes a component, running its {@link Component#onDestroy()} if it had started. Does nothing if the component
     * is not on this entity.
     */
    public void removeComponent(Component component) {
        if (component == null || component.entity != this) return;
        components.remove(component);
        EntityScene.stop(component);
        component.entity = null;
    }

    /**
     * The first component that is a {@code type} (a subclass or an interface it implements counts), or null.
     */
    public <T> T getComponent(Class<T> type) {
        for (Component c : components) {
            if (type.isInstance(c)) return type.cast(c);
        }
        return null;
    }

    /**
     * Every component that is a {@code type}, in the order they were added.
     */
    public <T> List<T> getComponents(Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component c : components) {
            if (type.isInstance(c)) found.add(type.cast(c));
        }
        return found;
    }

    /**
     * All components, in the order they were added. Read-only.
     */
    public List<Component> getComponents() {
        return Collections.unmodifiableList(components);
    }

    Component[] componentSnapshot() {
        return components.isEmpty() ? NO_COMPONENTS : components.toArray(NO_COMPONENTS);
    }

    /**
     * Moves a component to another place in the list, which decides the order its hooks run in.
     */
    public void moveComponent(Component component, int index) {
        if (!components.remove(component)) throw new IllegalArgumentException("component is not on " + this);
        components.add(Math.max(0, Math.min(index, components.size())), component);
    }

    // --- Hierarchy ------------------------------------------------------------------------------------------------

    /**
     * The parent entity, or null for a top-level entity of the scene (or one in no scene).
     */
    public Entity getParent() {
        return parent;
    }

    /**
     * The children, in drawing order: later ones are drawn on top. Read-only.
     */
    public List<Entity> getChildren() {
        return Collections.unmodifiableList(children);
    }

    Entity[] childSnapshot() {
        return children.isEmpty() ? NO_ENTITIES : children.toArray(NO_ENTITIES);
    }

    List<Entity> childList() {
        return children;
    }

    /**
     * Adds {@code child} as the last child. The child may be new, or already in this entity's scene, in which case it
     * moves here; its transform stays the same, so it keeps its place relative to its new parent, not in the world.
     *
     * @throws IllegalArgumentException if the child is this entity or one of its parents, is in another scene, or is
     *                                  destroyed
     */
    public void addChild(Entity child) {
        addChild(child, children.size());
    }

    /**
     * Adds {@code child} at {@code index} among the children. See {@link #addChild(Entity)}.
     */
    public void addChild(Entity child, int index) {
        if (child == null) throw new IllegalArgumentException("child must not be null");
        checkAlive();
        child.checkAlive();
        for (Entity e = this; e != null; e = e.parent) {
            if (e == child) throw new IllegalArgumentException("Can't make " + child + " a child of itself or of its own child");
        }
        if (child.scene != null && child.scene != scene) {
            throw new IllegalArgumentException(child + " is in another scene");
        }
        if (child.parent == this) {
            int from = children.indexOf(child);
            children.remove(from);
            if (from < index) index--;
        } else {
            child.detachFromParent();
        }
        children.add(Math.max(0, Math.min(index, children.size())), child);
        child.parent = this;
        if (scene != null && child.scene == null) scene.attach(child);
    }

    /**
     * Moves this entity to be a top-level entity of its scene, keeping its transform. Does nothing if it has no parent.
     */
    public void removeFromParent() {
        if (parent == null) return;
        EntityScene s = scene;
        detachFromParent();
        if (s != null) s.rootList().add(this);
    }

    void detachFromParent() {
        if (parent != null) {
            parent.children.remove(this);
            parent = null;
        } else if (scene != null) {
            scene.rootList().remove(this);
        }
    }

    void setParentDirect(Entity parent) {
        this.parent = parent;
    }

    /**
     * The first child, grandchild and so on with this name, searching each level before the next, or null.
     */
    public Entity find(String name) {
        return EntityScene.findIn(children, name);
    }

    /**
     * Removes the entity and its children from the scene. Their components get {@link Component#onDestroy()}, children
     * first. Destroyed entities can't be used again. Safe to call from a component's hooks, even on its own entity:
     * the rest of the frame skips the destroyed entity.
     */
    public void destroy() {
        if (destroyed) return;
        EntityScene s = scene;
        detachFromParent();
        destroyTree(s);
    }

    private void destroyTree(EntityScene s) {
        for (Entity child : childSnapshot()) child.destroyTree(s);
        for (Component c : componentSnapshot()) EntityScene.stop(c);
        if (s != null) s.forget(this);
        destroyed = true;
        scene = null;
    }

    private void checkAlive() {
        if (destroyed) throw new IllegalStateException(this + " has been destroyed");
    }

    // --- World transform ------------------------------------------------------------------------------------------

    /**
     * Where the entity is in the world, after its parents' positions, turns and scales. Each call walks up the parents.
     */
    public float getWorldX() {
        return worldPosition()[0];
    }

    public float getWorldY() {
        return worldPosition()[1];
    }

    /**
     * The entity's rotation plus its parents', in degrees clockwise.
     */
    public float getWorldRotation() {
        float r = 0f;
        for (Entity e = this; e != null; e = e.parent) r += e.transform.getRotation();
        return r;
    }

    /**
     * The entity's scale times its parents'. A child of a parent that is both turned and scaled unevenly would be
     * skewed; these entities are not, and take the scale along their own axes instead.
     */
    public float getWorldScaleX() {
        float s = 1f;
        for (Entity e = this; e != null; e = e.parent) s *= e.transform.getScaleX();
        return s;
    }

    public float getWorldScaleY() {
        float s = 1f;
        for (Entity e = this; e != null; e = e.parent) s *= e.transform.getScaleY();
        return s;
    }

    /**
     * Moves the entity so it ends up at this world position, whatever its parents are.
     */
    public void setWorldPosition(float worldX, float worldY) {
        if (parent == null) {
            transform.setPosition(worldX, worldY);
            return;
        }
        Vec2 local = parent.worldToLocal(worldX, worldY);
        transform.setPosition(local.x(), local.y());
    }

    /**
     * A world point in this entity's own space, where its children's positions are measured.
     */
    public Vec2 worldToLocal(float worldX, float worldY) {
        float[] origin = worldPosition();
        float dx = worldX - origin[0], dy = worldY - origin[1];
        double radians = Math.toRadians(-getWorldRotation());
        float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
        float rx = dx * cos - dy * sin, ry = dx * sin + dy * cos;
        float sx = getWorldScaleX(), sy = getWorldScaleY();
        return new Vec2(sx == 0f ? 0f : rx / sx, sy == 0f ? 0f : ry / sy);
    }

    /**
     * A point in this entity's own space in the world.
     */
    public Vec2 localToWorld(float localX, float localY) {
        float[] origin = worldPosition();
        float x = localX * getWorldScaleX(), y = localY * getWorldScaleY();
        double radians = Math.toRadians(getWorldRotation());
        float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
        return new Vec2(origin[0] + x * cos - y * sin, origin[1] + x * sin + y * cos);
    }

    private float[] worldPosition() {
        if (parent == null) return new float[]{transform.getX(), transform.getY()};
        Vec2 p = parent.localToWorld(transform.getX(), transform.getY());
        return new float[]{p.x(), p.y()};
    }

    @Override
    public String toString() {
        return "Entity[" + (id == 0 ? "" : "#" + id + " ") + "\"" + name + "\"]";
    }
}
