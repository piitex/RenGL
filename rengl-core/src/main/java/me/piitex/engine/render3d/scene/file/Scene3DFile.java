package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.io.Json;
import me.piitex.engine.io.JsonWriter;
import me.piitex.engine.render3d.physics.CharacterController;
import me.piitex.engine.render3d.physics.Joint;
import me.piitex.engine.render3d.physics.PhysicsWorld;
import me.piitex.engine.render3d.physics.RigidBody;
import me.piitex.engine.render3d.scene.AnimationClip;
import me.piitex.engine.render3d.scene.AnimationPlayer;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.file.SceneMeta.NodeInfo;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves and loads a {@link Scene3D} as a JSON file: its nodes with their shapes, models and materials, its lights, its
 * camera, its background, sky, fog and shadows, and its physics: which nodes are bodies or characters, and the joints
 * between them. The scene it loads is the real thing, ready to show in a
 * {@link me.piitex.engine.render3d.Viewport3D} or a {@link me.piitex.engine.render3d.Background3D}, and what an editor
 * saves is what the game loads:
 * <pre>
 * Scene3D scene = Scene3DFile.load("scenes/main.json");
 * Node3D player = Scene3DFile.find(scene, "player");   // a node, found by the name it has in the editor
 * RigidBody crate = Scene3DFile.findBody(scene, "crate");
 * </pre>
 * <p>
 * A file is a JSON object with a {@code format}, and {@code world}, {@code camera}, {@code lights}, {@code nodes} and
 * {@code joints}.
 * The values a file holds for each part are the {@link SceneProperty properties} {@link SceneKinds} lists, and a
 * property that has its default value is left out. A node's {@code material} and {@code children} are part of it.
 * <p>
 * Pictures and models are named by paths relative to the folder given when loading: the working folder, for
 * {@link #load(String)}, so a game runs from its project's folder and an editor reads from there too. One that
 * can't be read is left out of the scene and its name kept, so saving doesn't lose it; {@link #modelProblem} says why.
 * <p>
 * It saves what it made, and what an editor made with it: a node that was built in code is saved as a group, as the
 * file has no way to say what its mesh is.
 * <p>
 * Loading a scene to run it builds a {@link PhysicsWorld} with a body for each node that has one and the joints, and
 * gives it to the scene ({@link Scene3D#getPhysics()}), so it runs with the scene. An editor, which loads without
 * running, gets no world, and can build one with {@link #buildPhysics} to try the scene out.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class Scene3DFile {
    /** The version of the file layout this class reads and writes. A newer file is refused. */
    public static final int FORMAT = 1;

    private Scene3DFile() {
    }

    // --- Files -----------------------------------------------------------------------------------------------------

    /**
     * Reads a scene file, with pictures and models read from the working folder, and the animations its models are set
     * to play started.
     *
     * @throws IllegalArgumentException if it isn't a scene file, naming what is wrong
     * @throws UncheckedIOException     if it can't be read
     */
    public static Scene3D load(String path) {
        return load(Path.of(path), Path.of(""), true);
    }

    /**
     * Reads a scene file, with pictures and models read from {@code base}.
     */
    public static Scene3D load(Path path, Path base) {
        return load(path, base, true);
    }

    /**
     * Reads a scene file. {@code animate} starts the animations that models are set to play: an editor leaves them still.
     */
    public static Scene3D load(Path path, Path base, boolean animate) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read " + path, e);
        }
        try {
            return parse(text, base, animate);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Builds the scene {@code json} holds.
     */
    public static Scene3D parse(String json, Path base, boolean animate) {
        if (!(Json.parse(json) instanceof Map<?, ?> file)) throw new IllegalArgumentException("a scene file must be a JSON object");
        Object format = file.get("format");
        if (format instanceof Number n && n.intValue() > FORMAT) {
            throw new IllegalArgumentException("it was saved by a newer version (format " + n.intValue() + ")");
        }
        if (!file.containsKey("nodes") && !file.containsKey("lights") && !file.containsKey("world")) {
            throw new IllegalArgumentException("it has no \"nodes\", \"lights\" or \"world\"");
        }
        Scene3D scene = new Scene3D();
        readAll(SceneKinds.world(), scene, file.get("world"), base, "world");
        readAll(SceneKinds.camera(), scene.camera(), file.get("camera"), base, "camera");
        if (file.get("lights") instanceof List<?> lights) {
            scene.lights().clear();
            int index = 0;
            for (Object item : lights) {
                if (!(item instanceof Map<?, ?> light)) throw new IllegalArgumentException("\"lights\"[" + index + "] must be an object");
                Light.Type type;
                try {
                    type = Light.Type.valueOf(String.valueOf(light.get("type")));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("\"lights\"[" + index + "] has no type of DIRECTIONAL, POINT or SPOT");
                }
                Light made = SceneKinds.newLight(type);
                readAll(SceneKinds.light(type), made, light, base, "light " + index);
                scene.lights().add(made);
                index++;
            }
        }
        if (file.get("nodes") instanceof List<?> nodes) {
            int index = 0;
            for (Object item : nodes) {
                if (!(item instanceof Map<?, ?> node)) throw new IllegalArgumentException("\"nodes\"[" + index + "] must be an object");
                scene.add(readNode(node, base));
                index++;
            }
        }
        if (file.get("joints") instanceof List<?> joints) {
            int index = 0;
            for (Object item : joints) {
                if (!(item instanceof Map<?, ?> joint)) throw new IllegalArgumentException("\"joints\"[" + index + "] must be an object");
                JointKind kind;
                try {
                    kind = JointKind.valueOf(String.valueOf(joint.get("type")));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("\"joints\"[" + index + "] has no type of BALL, HINGE, SLIDER, DISTANCE or FIXED");
                }
                SceneJoint made = new SceneJoint(kind);
                readAll(SceneKinds.joint(kind), made, joint, base, "joint " + index);
                joints(scene).add(made);
                index++;
            }
        }
        if (animate) {
            startAnimations(scene);
            PhysicsWorld world = buildPhysics(scene);
            if (!world.bodies().isEmpty()) scene.setPhysics(world);
        }
        return scene;
    }

    /**
     * Writes the scene to a file, creating parent folders. It goes to a temporary file beside it first and is then
     * moved into place, so a crash part way through leaves the old file whole.
     */
    public static void save(Scene3D scene, Path path) throws IOException {
        String json = toJson(scene);
        Path target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, json);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The scene as the text {@link #save} writes.
     */
    public static String toJson(Scene3D scene) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("format", FORMAT);
        file.put("world", writeAll(SceneKinds.world(), scene));
        file.put("camera", writeAll(SceneKinds.camera(), scene.camera()));
        List<Object> lights = new ArrayList<>();
        for (Light light : scene.lights()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("type", light.getType().name());
            json.putAll(writeAll(SceneKinds.light(light.getType()), light));
            lights.add(json);
        }
        file.put("lights", lights);
        List<Object> nodes = new ArrayList<>();
        for (Node3D node : scene.root().getChildren()) nodes.add(write(node));
        file.put("nodes", nodes);
        List<Object> joints = new ArrayList<>();
        for (SceneJoint joint : joints(scene)) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("type", joint.getKind().name());
            json.putAll(writeAll(SceneKinds.joint(joint.getKind()), joint));
            joints.add(json);
        }
        if (!joints.isEmpty()) file.put("joints", joints);
        return JsonWriter.write(file) + "\n";
    }

    /**
     * A scene with nothing in it but the light a new {@link Scene3D} has.
     */
    public static Scene3D blank() {
        return new Scene3D();
    }

    // --- Nodes -----------------------------------------------------------------------------------------------------

    /**
     * A node and everything in it as JSON, ready for {@link JsonWriter}.
     */
    public static Map<String, Object> write(Node3D node) {
        Map<String, Object> json = writeAll(SceneKinds.node(), node);
        NodeInfo info = SceneMeta.peek(node);
        Material material = node.getMaterial();
        if (info != null && material != null && (info.shape == Shape.CUBE || info.shape == Shape.SPHERE || info.shape == Shape.PLANE)) {
            Map<String, Object> look = writeAll(SceneKinds.material(), material);
            if (!look.isEmpty()) json.put("material", look);
        }
        List<Object> children = new ArrayList<>();
        for (Node3D child : node.getChildren()) {
            if (info == null || child != info.modelRoot) children.add(write(child));
        }
        if (!children.isEmpty()) json.put("children", children);
        return json;
    }

    /**
     * Builds the node {@code json} holds, with the nodes in it.
     */
    public static Node3D read(Map<?, ?> json, Path base) {
        return readNode(json, base);
    }

    private static Node3D readNode(Map<?, ?> json, Path base) {
        Node3D node = new Node3D(json.get("name") instanceof String name ? name : "Node");
        try {
            readAll(SceneKinds.node(), node, json, base, null);
            if (json.get("material") instanceof Map<?, ?> look && node.getMaterial() != null) {
                readAll(SceneKinds.material(), node.getMaterial(), look, base, "material");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("node \"" + node.getName() + "\": " + e.getMessage(), e);
        }
        if (json.get("children") instanceof List<?> children) {
            int index = 0;
            for (Object child : children) {
                if (!(child instanceof Map<?, ?> childJson)) {
                    throw new IllegalArgumentException("node \"" + node.getName() + "\": \"children\"[" + index + "] must be an object");
                }
                node.add(readNode(childJson, base));
                index++;
            }
        }
        return node;
    }

    /**
     * A copy of {@code node} and what is in it, not yet under any parent. Unlike {@link Node3D#copy}, each node gets its
     * own material, so changing the copy's doesn't change the original's.
     */
    public static Node3D copy(Node3D node, Path base) {
        return readNode(write(node), base);
    }

    // --- Properties ------------------------------------------------------------------------------------------------

    private static <T> Map<String, Object> writeAll(List<SceneProperty<T>> properties, T target) {
        Map<String, Object> json = new LinkedHashMap<>();
        for (SceneProperty<T> property : properties) {
            if (!property.appliesTo(target)) continue;
            Object value = property.write(target);
            if (value != null) json.put(property.getName(), value);
        }
        return json;
    }

    private static <T> void readAll(List<SceneProperty<T>> properties, T target, Object json, Path base, String where) {
        if (json == null) return;
        if (!(json instanceof Map<?, ?> map)) throw new IllegalArgumentException("\"" + where + "\" must be an object");
        try {
            for (SceneProperty<T> property : properties) {
                if (map.containsKey(property.getName())) property.read(target, map.get(property.getName()), base);
            }
        } catch (IllegalArgumentException e) {
            throw where == null ? e : new IllegalArgumentException(where + ": " + e.getMessage(), e);
        }
    }

    // --- Looking things up -----------------------------------------------------------------------------------------

    /**
     * The first node named {@code name} in the scene, or null. The nodes inside a model are not looked in: they are the
     * model file's, and have names the editor didn't give.
     */
    public static Node3D find(Scene3D scene, String name) {
        return find(scene.root(), name);
    }

    private static Node3D find(Node3D node, String name) {
        if (name.equals(node.getName()) && node.getParent() != null) return node;
        NodeInfo info = SceneMeta.peek(node);
        for (Node3D child : node.getChildren()) {
            if (info != null && child == info.modelRoot) continue;
            Node3D found = find(child, name);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * The first light named {@code name} in the scene, or null.
     */
    public static Light findLight(Scene3D scene, String name) {
        for (Light light : scene.lights()) {
            if (name.equals(getName(light))) return light;
        }
        return null;
    }

    /** A light's name: what it was called in the editor, or "Directional Light", "Point Light" or "Spot Light". */
    public static String getName(Light light) {
        return SceneMeta.lightName(light);
    }

    public static void setName(Light light, String name) {
        SceneMeta.setLightName(light, name);
    }

    /** What a node draws, as the file says. A node that was built in code is {@link Shape#NONE}. */
    public static Shape getShape(Node3D node) {
        NodeInfo info = SceneMeta.peek(node);
        return info == null ? Shape.NONE : info.shape;
    }

    /**
     * Whether {@code node} is one of the nodes of a model's own file, which the scene doesn't save: they come from the
     * file each time.
     */
    public static boolean isInternal(Node3D node) {
        for (Node3D n = node; n.getParent() != null; n = n.getParent()) {
            NodeInfo info = SceneMeta.peek(n.getParent());
            if (info != null && info.modelRoot == n) return true;
        }
        return false;
    }

    /**
     * The node of the scene that {@code node} is part of: the model node it is inside of, or itself. A click on a
     * model's mesh finds the node that holds the model this way.
     */
    public static Node3D owner(Node3D node) {
        Node3D owner = node;
        for (Node3D n = node; n.getParent() != null; n = n.getParent()) {
            NodeInfo info = SceneMeta.peek(n.getParent());
            if (info != null && info.modelRoot == n) owner = n.getParent();
        }
        return owner;
    }

    /**
     * Why the model of a {@link Shape#MODEL} node is not in the scene, or null if it is, or the node is not a model.
     */
    public static String modelProblem(Node3D node) {
        NodeInfo info = SceneMeta.peek(node);
        return info == null || info.shape != Shape.MODEL ? null : info.problem;
    }

    /**
     * The names of the animations the node's model has, in order. Empty for a node that is not a model.
     */
    public static List<String> animations(Node3D node) {
        NodeInfo info = SceneMeta.peek(node);
        List<String> names = new ArrayList<>();
        if (info != null && info.loaded != null) {
            for (AnimationClip clip : info.loaded.animations()) names.add(clip.name());
        }
        return names;
    }

    /**
     * Why the scene's sky isn't drawn, such as a picture that can't be read, or null if it is, or there is none.
     */
    public static String skyProblem(Scene3D scene) {
        return SceneMeta.sky(scene).problem;
    }

    // --- Physics ---------------------------------------------------------------------------------------------------

    /**
     * The scene's joints, in order, to change, add to or take from; loading the scene to run it makes each into a real
     * joint.
     */
    public static List<SceneJoint> joints(Scene3D scene) {
        return ScenePhysics.joints(scene);
    }

    /**
     * A new world with a body for each node that has one, placed where the node is now, a character for each character
     * node, and the joints between them, with the scene's gravity. Nothing is given to the scene: this is how an editor
     * tries a scene out, and building again starts over. Bodies that can't be made, such as a model with no meshes,
     * and joints naming nodes that aren't there, are left out and logged.
     */
    public static PhysicsWorld buildPhysics(Scene3D scene) {
        return ScenePhysics.build(scene);
    }

    /**
     * The body of the first node named {@code name} in the scene's world, or null: a character's body for a character.
     */
    public static RigidBody findBody(Scene3D scene, String name) {
        ScenePhysics.Built built = ScenePhysics.built(scene.getPhysics());
        Node3D node = find(scene, name);
        if (built == null || node == null) return null;
        RigidBody body = built.bodies.get(node);
        if (body != null) return body;
        CharacterController character = built.characters.get(node);
        return character == null ? null : character.getBody();
    }

    /** The character the first node named {@code name} is, in the scene's world, or null. */
    public static CharacterController findCharacter(Scene3D scene, String name) {
        ScenePhysics.Built built = ScenePhysics.built(scene.getPhysics());
        Node3D node = find(scene, name);
        return built == null || node == null ? null : built.characters.get(node);
    }

    /** The first joint named {@code name} in the scene's world, or null. */
    public static Joint findJoint(Scene3D scene, String name) {
        ScenePhysics.Built built = ScenePhysics.built(scene.getPhysics());
        return built == null ? null : built.joints.get(name);
    }

    // --- Running ---------------------------------------------------------------------------------------------------

    private static void startAnimations(Scene3D scene) {
        scene.root().traverse(node -> {
            NodeInfo info = SceneMeta.peek(node);
            if (info == null || info.loaded == null || info.animation.isEmpty()) return;
            AnimationPlayer player = info.loaded.player();
            if (player.clip(info.animation) == null) return;
            player.play(info.animation);
            scene.animate(player);
        });
    }
}
