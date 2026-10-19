package me.piitex.engine.entity;

import me.piitex.engine.game.Scene;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.graphics.Camera;
import me.piitex.engine.graphics.DebugDraw;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A {@link Scene} made of {@link Entity entities} with {@link Component components}, which can be saved to a file and
 * loaded from one. This is what an editor makes: the game loads the file and the components bring it to life.
 * <pre>{@code
 * window.getSceneManager().set(EntityScene.load("scenes/level1.json"));
 * }</pre>
 * It is an ordinary scene, so {@link me.piitex.engine.game.SceneManager} transitions, pausing, timers and the
 * scene's {@link #getContainer() container} for UI all work as usual. Built in code, it looks like this:
 * <pre>{@code
 * EntityScene scene = new EntityScene();
 * Entity player = scene.create("Player");
 * player.addComponent(new SpriteRenderer(TextureRef.of("assets/hero.png")));
 * scene.save(Path.of("scenes/level1.json"));
 * }</pre>
 * <b>Camera.</b> The scene draws its entities through its {@link #getCamera() camera}, whose viewport follows the
 * window. The camera's position is the world point in the middle of the window, (0, 0) unless the scene file or
 * the game sets another.
 * <p>
 * <b>Extending it.</b> A game can extend this class to add scene-wide logic. Call {@code super} in any of
 * {@code onEnter}, {@code onExit}, {@code fixedUpdate}, {@code update}, {@code render} and {@code onResize} you
 * override, or the entities stop working. {@link SceneFile#read} loads a file into an instance of the subclass.
 * <p>
 * Every method is for the render thread, except that a scene not yet on a stack can be built and loaded on any one
 * thread, such as in a {@link me.piitex.engine.scheduler.Loader} step.
 */
public class EntityScene extends Scene {
    private static final Color COLLIDER_COLOR = new Color(0.2f, 1f, 0.4f, 0.9f);

    private final List<Entity> roots = new ArrayList<>();
    private final Map<Long, Entity> byId = new HashMap<>();
    private final Camera camera = new Camera();
    private final DebugDraw debugDraw = new DebugDraw();
    private DebugDraw editorDraw; // the colliders in renderInEditor, kept apart from the game's queue
    private long nextId = 1;
    private boolean started;
    private boolean showColliders;
    private Color background;

    /**
     * Loads a scene file saved by {@link #save} or an editor. Component classes are found through the thread's
     * context class loader.
     *
     * @throws UncheckedIOException     if the file can't be read
     * @throws IllegalArgumentException if it isn't a valid scene, saying where
     */
    public static EntityScene load(String path) {
        return load(Path.of(path));
    }

    public static EntityScene load(Path path) {
        EntityScene scene = new EntityScene();
        SceneFile.read(scene, path);
        return scene;
    }

    /**
     * Saves the scene: its camera, background and every entity with its components' properties.
     *
     * @throws UncheckedIOException if the file can't be written
     */
    public void save(Path path) {
        try {
            SceneFile.write(this, path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save scene to " + path, e);
        }
    }

    /**
     * Makes a new top-level entity and adds it to the scene.
     */
    public Entity create(String name) {
        Entity entity = new Entity(name);
        add(entity);
        return entity;
    }

    /**
     * Adds an entity, with its children, as the last top-level entity. Its components start if the scene is on the
     * stack. An entity already in this scene moves to the top level.
     *
     * @throws IllegalArgumentException if it is in another scene or destroyed
     */
    public void add(Entity entity) {
        add(entity, roots.size());
    }

    /**
     * Adds an entity at {@code index} among the top-level entities. See {@link #add(Entity)}.
     */
    public void add(Entity entity, int index) {
        if (entity == null) throw new IllegalArgumentException("entity must not be null");
        if (entity.isDestroyed()) throw new IllegalArgumentException(entity + " has been destroyed");
        if (entity.scene != null && entity.scene != this) throw new IllegalArgumentException(entity + " is in another scene");
        if (entity.scene == this && entity.getParent() == null) {
            int from = roots.indexOf(entity);
            roots.remove(from);
            if (from < index) index--;
        } else {
            entity.detachFromParent();
        }
        roots.add(Math.max(0, Math.min(index, roots.size())), entity);
        if (entity.scene == null) attach(entity);
    }

    /**
     * The top-level entities, in drawing order. Read-only.
     */
    public List<Entity> getEntities() {
        return Collections.unmodifiableList(roots);
    }

    List<Entity> rootList() {
        return roots;
    }

    /**
     * The entity with this id, anywhere in the tree, or null.
     */
    public Entity getEntity(long id) {
        return byId.get(id);
    }

    /**
     * How many entities the scene holds, children included.
     */
    public int getEntityCount() {
        return byId.size();
    }

    /**
     * The first entity with this name, searching the top level first, then their children, and so on; or null.
     */
    public Entity find(String name) {
        return findIn(roots, name);
    }

    static Entity findIn(List<Entity> level, String name) {
        ArrayDeque<Entity> queue = new ArrayDeque<>(level);
        while (!queue.isEmpty()) {
            Entity e = queue.poll();
            if (e.getName().equals(name)) return e;
            queue.addAll(e.getChildren());
        }
        return null;
    }

    /**
     * Calls {@code action} for every entity, parents before their children, in drawing order.
     */
    public void forEachEntity(Consumer<Entity> action) {
        for (Entity root : roots.toArray(new Entity[0])) forEach(root, action);
    }

    private static void forEach(Entity entity, Consumer<Entity> action) {
        action.accept(entity);
        for (Entity child : entity.childSnapshot()) forEach(child, action);
    }

    /**
     * Every component that is a {@code type} on any entity, active or not, in drawing order.
     */
    public <T> List<T> getComponents(Class<T> type) {
        List<T> found = new ArrayList<>();
        forEachEntity(e -> found.addAll(e.getComponents(type)));
        return found;
    }

    /**
     * Destroys every entity.
     */
    public void clear() {
        for (Entity root : roots.toArray(new Entity[0])) root.destroy();
    }

    void attach(Entity entity) {
        List<Entity> added = new ArrayList<>();
        register(entity, added);
        if (started) {
            for (Entity e : added) {
                for (Component c : e.componentSnapshot()) start(c);
            }
        }
    }

    // Gives the subtree its scene and ids first, so onStart can find any entity in it.
    private void register(Entity entity, List<Entity> added) {
        entity.scene = this;
        Entity holder = entity.id == 0 ? null : byId.get(entity.id);
        if (entity.id == 0 || (holder != null && holder != entity)) entity.id = nextId;
        byId.put(entity.id, entity);
        nextId = Math.max(nextId, entity.id + 1);
        added.add(entity);
        for (Entity child : entity.childList()) {
            child.setParentDirect(entity);
            register(child, added);
        }
    }

    void forget(Entity entity) {
        byId.remove(entity.id, entity);
    }

    void start(Component component) {
        if (component.started) return;
        component.started = true;
        component.onStart();
    }

    static void stop(Component component) {
        if (!component.started) return;
        component.started = false;
        component.onDestroy();
    }

    /**
     * Whether the scene is on a stack, between {@link #onEnter()} and {@link #onExit()}, so its components have
     * started.
     */
    public boolean isStarted() {
        return started;
    }

    // --- Settings -------------------------------------------------------------------------------------------------

    public Camera getCamera() {
        return camera;
    }

    /**
     * The color the scene fills the window with before drawing its entities, or null (the default) for none.
     */
    public Color getBackground() {
        return background;
    }

    public void setBackground(Color background) {
        this.background = background;
    }

    /**
     * Shapes queued here are drawn above the entities, through the scene's camera. See {@link DebugDraw}.
     */
    public DebugDraw getDebugDraw() {
        return debugDraw;
    }

    /**
     * Draws the outline of every {@link Collider} above the entities, for finding hit box mistakes.
     */
    public void setShowColliders(boolean show) {
        this.showColliders = show;
    }

    public boolean isShowColliders() {
        return showColliders;
    }

    /**
     * The next id the scene will give out. Ids are never reused within a scene, even after their entity is gone.
     */
    public long getNextId() {
        return nextId;
    }

    void setNextId(long nextId) {
        this.nextId = Math.max(this.nextId, nextId);
    }

    // --- Scene hooks ----------------------------------------------------------------------------------------------

    @Override
    protected void onEnter() {
        camera.setViewport(0, 0, (float) getContainer().getWidth(), (float) getContainer().getHeight());
        started = true;
        forEachEntity(e -> {
            for (Component c : e.componentSnapshot()) {
                if (c.entity == e) start(c);
            }
        });
    }

    @Override
    protected void onExit() {
        started = false;
        // Children before parents, the reverse of starting.
        List<Entity> order = new ArrayList<>();
        forEachEntity(order::add);
        for (int i = order.size() - 1; i >= 0; i--) {
            for (Component c : order.get(i).componentSnapshot()) stop(c);
        }
    }

    @Override
    protected void onResize(int width, int height) {
        camera.setViewport(0, 0, width, height);
    }

    @Override
    protected void fixedUpdate(float step) {
        for (Entity root : roots.toArray(new Entity[0])) fixedUpdate(root, step);
    }

    private void fixedUpdate(Entity entity, float step) {
        if (entity.isDestroyed() || !entity.isActive()) return;
        for (Component c : entity.componentSnapshot()) {
            if (c.started && c.entity == entity) c.fixedUpdate(step);
        }
        for (Entity child : entity.childSnapshot()) fixedUpdate(child, step);
    }

    @Override
    protected void update(float delta) {
        for (Entity root : roots.toArray(new Entity[0])) update(root, delta);
        camera.update(delta);
    }

    private void update(Entity entity, float delta) {
        if (entity.isDestroyed() || !entity.isActive()) return;
        for (Component c : entity.componentSnapshot()) {
            if (c.started && c.entity == entity) c.update(delta);
        }
        for (Entity child : entity.childSnapshot()) update(child, delta);
    }

    @Override
    protected void render(Renderer renderer, float alpha) {
        if (background != null) {
            renderer.drawQuad(camera.getViewportX(), camera.getViewportY(), camera.getViewportWidth(),
                    camera.getViewportHeight(), background, 0f);
        }
        camera.begin(renderer);
        for (Entity root : roots.toArray(new Entity[0])) render(root, renderer, alpha);
        camera.end(renderer);
        if (showColliders) {
            for (Collider collider : getComponents(Collider.class)) {
                Entity e = collider.getEntity();
                if (e != null && e.isActiveInTree()) debugDraw.shape(collider.getShape(), COLLIDER_COLOR);
            }
        }
        debugDraw.draw(renderer, camera);
    }

    private void render(Entity entity, Renderer renderer, float alpha) {
        if (entity.isDestroyed() || !entity.isActive()) return;
        for (Component c : entity.componentSnapshot()) {
            if (c.started && c.entity == entity) c.render(renderer, alpha);
        }
        for (Entity child : entity.childSnapshot()) render(child, renderer, alpha);
    }

    /**
     * Draws the scene the way an editor shows it while it is being edited: through {@code camera} rather than the
     * scene's own, without starting anything. Only components marked {@link DrawInEditor} draw, whether or not they
     * have started, along with the colliders if they are {@link #setShowColliders shown}. The {@link #getBackground()
     * background} is left to the editor, so it can draw a grid or guides between it and the scene. Nothing is updated,
     * and the {@link #getDebugDraw() debug draw} queue is left alone.
     */
    public void renderInEditor(Renderer renderer, Camera camera) {
        camera.begin(renderer);
        for (Entity root : roots.toArray(new Entity[0])) renderInEditor(root, renderer);
        camera.end(renderer);
        if (showColliders) {
            if (editorDraw == null) editorDraw = new DebugDraw();
            for (Collider collider : getComponents(Collider.class)) {
                Entity e = collider.getEntity();
                if (e != null && e.isActiveInTree()) editorDraw.shape(collider.getShape(), COLLIDER_COLOR);
            }
            editorDraw.draw(renderer, camera);
        }
    }

    private void renderInEditor(Entity entity, Renderer renderer) {
        if (entity.isDestroyed() || !entity.isActive()) return;
        for (Component c : entity.componentSnapshot()) {
            if (c.entity == entity && drawsInEditor(c.getClass())) c.render(renderer, 1f);
        }
        for (Entity child : entity.childSnapshot()) renderInEditor(child, renderer);
    }

    private static final ClassValue<Boolean> DRAWS_IN_EDITOR = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.isAnnotationPresent(DrawInEditor.class);
        }
    };

    private static boolean drawsInEditor(Class<?> type) {
        return DRAWS_IN_EDITOR.get(type);
    }
}
