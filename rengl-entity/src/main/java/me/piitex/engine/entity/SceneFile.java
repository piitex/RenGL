package me.piitex.engine.entity;

import me.piitex.engine.io.Json;
import me.piitex.engine.io.JsonWriter;
import me.piitex.engine.property.PropertyTypes;
import me.piitex.engine.property.TypeInfo;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes {@link EntityScene}s as JSON. {@link EntityScene#load} and {@link EntityScene#save} cover the usual
 * case; this class is for the rest: loading into a subclass of {@code EntityScene}, choosing the class loader
 * component classes come from, or copying single entities (for copy and paste, or undoing a delete).
 * <p>
 * A scene file looks like this:
 * <pre>{@code
 * {
 *   "format": 1,
 *   "camera": { "x": 0, "y": 0, "zoom": 1 },
 *   "background": [0.1, 0.1, 0.12, 1],
 *   "nextId": 3,
 *   "entities": [
 *     {
 *       "id": 1,
 *       "name": "Player",
 *       "active": true,
 *       "transform": { "x": 0, "y": 0, "rotation": 0, "scaleX": 1, "scaleY": 1 },
 *       "components": [
 *         { "type": "me.piitex.engine.entity.SpriteRenderer", "texture": "assets/hero.png", ... }
 *       ],
 *       "children": [ { "id": 2, "name": "Sword", ... } ]
 *     }
 *   ]
 * }
 * }</pre>
 * Each component is its class's full name under {@code "type"}, followed by its {@link me.piitex.engine.property.Property
 * properties}, so a component can't have a property named "type". Anything missing when reading takes its default:
 * an entity with no {@code "transform"} is at (0, 0), a component with a property missing keeps the value its
 * constructor gave. A component whose class can't be found or made loads as a {@link MissingComponent}, with a warning
 * in the log, and is written back as it was.
 */
public final class SceneFile {
    /**
     * The version of the format this class writes.
     */
    public static final int FORMAT = 1;

    private static final Logger log = LogManager.getLogger("SceneFile");

    private SceneFile() {
    }

    // --- Whole scenes ---------------------------------------------------------------------------------------------

    /**
     * Writes the scene to a file, creating parent folders as needed. It is written to a temporary file beside it first
     * and then moved into place, so a crash part way through leaves the old file whole.
     */
    public static void write(EntityScene scene, Path path) throws IOException {
        String json = JsonWriter.write(write(scene));
        Path target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, json + "\n");
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The scene as JSON, ready for {@link JsonWriter}.
     */
    public static Map<String, Object> write(EntityScene scene) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("format", FORMAT);
        Map<String, Object> camera = new LinkedHashMap<>();
        camera.put("x", scene.getCamera().getX());
        camera.put("y", scene.getCamera().getY());
        camera.put("zoom", scene.getCamera().getZoom());
        json.put("camera", camera);
        if (scene.getBackground() != null) {
            json.put("background", PropertyTypes.codecFor(Color.class).write(scene.getBackground()));
        }
        json.put("nextId", scene.getNextId());
        List<Object> entities = new ArrayList<>();
        for (Entity root : scene.getEntities()) entities.add(writeEntity(root));
        json.put("entities", entities);
        return json;
    }

    /**
     * Loads a scene file into {@code scene}, which is cleared first. Component classes come from the thread's context
     * class loader.
     *
     * @throws UncheckedIOException     if the file can't be read
     * @throws IllegalArgumentException if the file isn't a valid scene, naming the file and what is wrong
     */
    public static void read(EntityScene scene, Path path) {
        read(scene, path, defaultLoader());
    }

    /**
     * Loads a scene file into {@code scene}, finding component classes through {@code loader}. An editor passes the
     * class loader it loaded the game's classes with.
     */
    public static void read(EntityScene scene, Path path, ClassLoader loader) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read scene " + path, e);
        }
        try {
            if (!(Json.parse(text) instanceof Map<?, ?> json)) throw new IllegalArgumentException("a scene file must be a JSON object");
            read(scene, json, loader);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Bad scene " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Loads a scene from JSON as {@link #write(EntityScene)} produces it into {@code scene}, which is cleared first.
     */
    public static void read(EntityScene scene, Map<?, ?> json, ClassLoader loader) {
        Object format = json.get("format");
        if (format instanceof Number n && n.intValue() > FORMAT) {
            throw new IllegalArgumentException("the file is format " + n.intValue() + ", newer than this engine reads (" + FORMAT + ")");
        }
        scene.clear();
        if (json.get("camera") instanceof Map<?, ?> camera) {
            float x = number(camera, "x", scene.getCamera().getX());
            float y = number(camera, "y", scene.getCamera().getY());
            scene.getCamera().setPosition(x, y);
            scene.getCamera().setZoom(number(camera, "zoom", scene.getCamera().getZoom()));
        }
        Object background = json.get("background");
        scene.setBackground(background == null ? null : PropertyTypes.codecFor(Color.class).read(background));
        for (Object entity : list(json, "entities", "the scene")) {
            scene.add(readEntity(entity, loader, "entities"));
        }
        if (json.get("nextId") instanceof Number n) scene.setNextId(n.longValue());
    }

    // --- Single entities ------------------------------------------------------------------------------------------

    /**
     * One entity with its children as JSON, ids included.
     */
    public static Map<String, Object> writeEntity(Entity entity) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", entity.getId());
        json.put("name", entity.getName());
        json.put("active", entity.isActive());
        json.put("transform", TypeInfo.of(Transform.class).write(entity.getTransform()));
        List<Object> components = new ArrayList<>();
        for (Component c : entity.getComponents()) components.add(writeComponent(c));
        json.put("components", components);
        if (!entity.getChildren().isEmpty()) {
            List<Object> children = new ArrayList<>();
            for (Entity child : entity.getChildren()) children.add(writeEntity(child));
            json.put("children", children);
        }
        return json;
    }

    /**
     * A new entity, with its children, from JSON written by {@link #writeEntity}. It is in no scene yet. Its ids are
     * kept where they are free when it is added to a scene, so to paste a copy beside the original, the copy gets new
     * ids and the original keeps its own.
     *
     * @throws IllegalArgumentException if the JSON isn't a valid entity
     */
    public static Entity readEntity(Map<?, ?> json, ClassLoader loader) {
        return readEntity(json, loader, "entity");
    }

    private static Entity readEntity(Object value, ClassLoader loader, String where) {
        if (!(value instanceof Map<?, ?> json)) throw new IllegalArgumentException(where + " must be an object");
        Entity entity = new Entity(json.get("name") instanceof String s ? s : "Entity");
        where = where + " \"" + entity.getName() + "\"";
        if (json.get("id") instanceof Number n) entity.id = Math.max(0, n.longValue());
        if (json.get("active") instanceof Boolean b) entity.setActive(b);
        if (json.get("transform") instanceof Map<?, ?> transform) {
            try {
                TypeInfo.of(Transform.class).read(entity.getTransform(), transform);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
            }
        }
        for (Object component : list(json, "components", where)) {
            entity.addComponent(readComponent(component, loader, where));
        }
        for (Object child : list(json, "children", where)) {
            entity.addChild(readEntity(child, loader, where + " > children"));
        }
        return entity;
    }

    // --- Components -----------------------------------------------------------------------------------------------

    /**
     * A component as JSON: its type, then its properties.
     *
     * @throws IllegalStateException if the component has a property named "type"
     */
    public static Map<String, Object> writeComponent(Component component) {
        if (component instanceof MissingComponent missing) return new LinkedHashMap<>(missing.getData());
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("type", component.getClass().getName());
        Map<String, Object> properties = TypeInfo.of(component.getClass()).write(component);
        if (properties.containsKey("type")) {
            throw new IllegalStateException(component.getClass().getName() + " has a property named \"type\", which scene files use for the class name");
        }
        json.putAll(properties);
        return json;
    }

    /**
     * A new component from JSON written by {@link #writeComponent}, or a {@link MissingComponent} if its class can't
     * be found or made.
     *
     * @throws IllegalArgumentException if a property doesn't fit its field
     */
    public static Component readComponent(Map<?, ?> json, ClassLoader loader) {
        return readComponent(json, loader, "component");
    }

    private static Component readComponent(Object value, ClassLoader loader, String where) {
        if (!(value instanceof Map<?, ?> json)) throw new IllegalArgumentException(where + ": a component must be an object");
        if (!(json.get("type") instanceof String type)) throw new IllegalArgumentException(where + ": a component has no \"type\"");
        Component component;
        try {
            component = create(type, loader);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            String reason = e instanceof ClassNotFoundException ? "class not found" : e.toString();
            log.warn("{}: component {} can't be made ({}). It is kept as a MissingComponent.", where, type, reason);
            return new MissingComponent(type, json, reason);
        }
        try {
            TypeInfo.of(component.getClass()).read(component, json);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
        }
        return component;
    }

    private static Component create(String type, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> cls = Class.forName(type, true, loader);
        if (!Component.class.isAssignableFrom(cls)) throw new ClassCastException(type + " is not a Component");
        if (Modifier.isAbstract(cls.getModifiers())) throw new InstantiationException(type + " is abstract");
        Constructor<?> constructor = cls.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (Component) constructor.newInstance();
    }

    // --- Helpers --------------------------------------------------------------------------------------------------

    static ClassLoader defaultLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : SceneFile.class.getClassLoader();
    }

    private static List<?> list(Map<?, ?> json, String key, String where) {
        Object value = json.get(key);
        if (value == null) return List.of();
        if (value instanceof List<?> list) return list;
        throw new IllegalArgumentException(where + ": \"" + key + "\" must be an array");
    }

    private static float number(Map<?, ?> json, String key, float fallback) {
        return json.get(key) instanceof Number n ? n.floatValue() : fallback;
    }
}
