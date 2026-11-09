package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Instances;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Model;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.render3d.scene.TextureData;
import me.piitex.engine.render3d.scene.UiScreen;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.file.UiFile;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What a scene file knows about the parts of a scene that the engine's own classes don't hold: which shape a node's mesh
 * was made as, which file a texture came from, what a light is called. Kept beside the parts, weakly, so a part that is
 * gone takes its notes with it. A part not made by a scene file has none, and is then saved as what it can be told to
 * be: a node with a mesh it can't describe is saved as a group.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
final class SceneMeta {
    private static final Logger log = LogManager.getLogger("Engine");

    /** A node's own settings. */
    static final class NodeInfo {
        Shape shape = Shape.NONE;
        boolean folder; // a group that only holds nodes, with no place, turn or size of its own
        float size = 1f, radius = 0.5f, width = 10f, depth = 10f, height = 1f, peak = 5f;
        int segments = 32, rings = 16, resolution = 64, copies = 1;
        Vec3 spacing = new Vec3(2f, 0f, 0f);
        String model = "", animation = "", heightmap = "", prefab = "";
        Path base = Path.of(""); // where the height map is read from, for making the terrain again
        Node3D prefabRoot; // the child that holds the nodes of a prefab's file
        String prefabProblem; // why the prefab isn't there
        String heightmapProblem;
        Vec3 euler; // the angles last given, which a rotation can be read back as without drifting
        Node3D modelRoot; // the child that holds a model's own nodes
        Model loaded;
        String problem; // why the model isn't there
        final Map<String, Model> models = new HashMap<>(); // loaded before, so switching back is quick
    }

    /** A material's own settings. */
    static final class MaterialInfo {
        static final int BASE = 0, NORMAL = 1, METALLIC_ROUGHNESS = 2, OCCLUSION = 3, EMISSIVE = 4, HEIGHT = 5;
        final String[] textures = {"", "", "", "", "", ""};
        Color emissiveColor = Color.WHITE;
        float emissiveStrength;
        /** How the glow blinks while the game runs, with no light needed. Its glow node is not used. */
        final Motion blink = new Motion();
        /** The layout the material shows as a screen, as the file named it, and its texture pixels per point. */
        String screen = "";
        float screenScale = 1f;
        UiScreen uiScreen;
        String screenProblem;
    }

    /** The sky the scene was told to have, which a {@link Skybox} can't be asked about. */
    static final class SkyInfo {
        SkyKind kind = SkyKind.NONE;
        Color top = new Color(0.25f, 0.45f, 0.85f, 1f), horizon = new Color(0.75f, 0.85f, 0.95f, 1f), bottom = new Color(0.35f, 0.33f, 0.3f, 1f);
        String panorama = "";
        final String[] faces = {"", "", "", "", "", ""};
        float brightness = 1f;
        String problem;
    }

    private static final Map<Node3D, NodeInfo> NODES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Material, MaterialInfo> MATERIALS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Scene3D, SkyInfo> SKIES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Light, String> LIGHT_NAMES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Light, Motion> MOTIONS = Collections.synchronizedMap(new WeakHashMap<>());

    /** How a light blinks while the game runs, and the node whose glow blinks with it. */
    static final class Motion {
        LightPatternKind pattern = LightPatternKind.NONE;
        float period = 1f, minimum = 0f, start = 0f;
        String glow = "";
    }

    static Motion motion(Light light) {
        return MOTIONS.computeIfAbsent(light, l -> new Motion());
    }

    static Motion peekMotion(Light light) {
        return MOTIONS.get(light);
    }

    private SceneMeta() {
    }

    static NodeInfo node(Node3D node) {
        return NODES.computeIfAbsent(node, n -> new NodeInfo());
    }

    static NodeInfo peek(Node3D node) {
        return NODES.get(node);
    }

    /** Whether the node is a folder: a group that holds nodes without moving, turning or sizing them. */
    static boolean isFolder(Node3D node) {
        NodeInfo info = NODES.get(node);
        return info != null && info.folder && info.shape == Shape.NONE;
    }

    /** Makes the node a folder, or a group again. A folder has no place of its own, so it is put back at the origin, unturned and unscaled. */
    static void setFolder(Node3D node, boolean folder) {
        node(node).folder = folder;
        if (folder) {
            node.setPosition(Vec3.ZERO);
            node.setRotation(Quat.IDENTITY);
            node.setScale(Vec3.ONE);
            node(node).euler = null;
        }
    }

    static MaterialInfo material(Material material) {
        return MATERIALS.computeIfAbsent(material, m -> new MaterialInfo());
    }

    static MaterialInfo peekMaterial(Material material) {
        return MATERIALS.get(material);
    }

    static SkyInfo sky(Scene3D scene) {
        return SKIES.computeIfAbsent(scene, s -> new SkyInfo());
    }

    static String lightName(Light light) {
        String name = LIGHT_NAMES.get(light);
        return name == null ? defaultName(light) : name;
    }

    static void setLightName(Light light, String name) {
        LIGHT_NAMES.put(light, name);
    }

    static String defaultName(Light light) {
        return switch (light.getType()) {
            case DIRECTIONAL -> "Directional Light";
            case POINT -> "Point Light";
            case SPOT -> "Spot Light";
        };
    }

    // --- Rotation -------------------------------------------------------------------------------------------------

    static Vec3 euler(Node3D node) {
        NodeInfo info = node(node);
        if (info.euler == null || !Euler.same(Euler.toQuat(info.euler), node.getRotation())) info.euler = Euler.of(node.getRotation());
        return info.euler;
    }

    static void setEuler(Node3D node, Vec3 degrees) {
        node(node).euler = degrees;
        node.setRotation(Euler.toQuat(degrees));
    }

    // --- Shapes and models ----------------------------------------------------------------------------------------

    /** Whether a node of this shape draws a mesh of its own that the file describes, so its material is the file's too. */
    static boolean drawsMesh(Shape shape) {
        return shape.hasMesh();
    }

    static void setShape(Node3D node, Shape shape, Path base) {
        NodeInfo info = node(node);
        info.shape = shape;
        info.base = base;
        if (shape == Shape.MODEL) {
            removePrefab(node, info);
            node.setMesh(null);
            loadModel(node, info, base);
            return;
        }
        removeModel(node, info);
        if (shape == Shape.PREFAB) {
            node.setMesh(null);
            loadPrefab(node, info, base);
            return;
        }
        removePrefab(node, info);
        if (shape == Shape.NONE) {
            node.setMesh(null);
            node.setInstances(null);
            return;
        }
        node.setMesh(build(info));
        if (node.getMaterial() == null) node.setMaterial(new Material());
        repeat(node, info);
    }

    /** Draws the node's mesh {@code copies} times, each {@code spacing} further along, in one draw call. */
    static void repeat(Node3D node, NodeInfo info) {
        if (info.copies <= 1 || !drawsMesh(info.shape)) {
            node.setInstances(null);
            return;
        }
        Instances instances = new Instances();
        for (int i = 0; i < info.copies; i++) instances.add(info.spacing.mul(i));
        node.setInstances(instances);
    }

    /** Makes the mesh again from the node's size, radius and so on, after one of them changed. */
    static void rebuild(Node3D node) {
        NodeInfo info = node(node);
        if (drawsMesh(info.shape)) node.setMesh(build(info));
        repeat(node, info);
    }

    private static Mesh build(NodeInfo info) {
        return switch (info.shape) {
            case CUBE -> Mesh.cube(Math.max(0.001f, info.size));
            case SPHERE -> Mesh.sphere(Math.max(0.001f, info.radius), Math.max(3, info.segments), Math.max(2, info.rings));
            case PLANE -> Mesh.plane(Math.max(0.001f, info.width), Math.max(0.001f, info.depth));
            case CYLINDER -> Mesh.cylinder(Math.max(0.001f, info.radius), Math.max(0.001f, info.height), Math.max(3, info.segments));
            case CONE -> Mesh.cone(Math.max(0.001f, info.radius), Math.max(0.001f, info.height), Math.max(3, info.segments));
            case CAPSULE -> Mesh.capsule(Math.max(0.001f, info.radius), Math.max(0.002f, info.height), Math.max(3, info.segments), Math.max(1, info.rings / 2));
            case TERRAIN -> terrain(info);
            default -> null;
        };
    }

    private static Mesh terrain(NodeInfo info) {
        int points = Math.clamp(info.resolution, 2, 512);
        float[] heights = new float[points * points];
        info.heightmapProblem = null;
        if (!info.heightmap.isEmpty()) {
            TextureData picture = Textures.load(info.heightmap, info.base);
            if (picture == null) info.heightmapProblem = "The height map " + info.heightmap + " can't be read";
            else heights = picture.brightness(points, points);
        }
        return Mesh.terrain(Math.max(0.001f, info.width), Math.max(0.001f, info.depth), Math.max(0f, info.peak), heights, points, points);
    }

    static void setHeightmap(Node3D node, String path, Path base) {
        NodeInfo info = node(node);
        info.heightmap = path;
        info.base = base;
        rebuild(node);
    }

    // --- Prefabs --------------------------------------------------------------------------------------------------

    private static final ThreadLocal<java.util.Set<Path>> LOADING = ThreadLocal.withInitial(java.util.HashSet::new);

    static void setPrefab(Node3D node, String path, Path base) {
        NodeInfo info = node(node);
        info.prefab = path;
        info.base = base;
        if (info.shape == Shape.PREFAB) loadPrefab(node, info, base);
    }

    private static void removePrefab(Node3D node, NodeInfo info) {
        if (info.prefabRoot != null) node.remove(info.prefabRoot);
        info.prefabRoot = null;
        info.prefabProblem = null;
    }

    private static void loadPrefab(Node3D node, NodeInfo info, Path base) {
        removePrefab(node, info);
        if (info.prefab.isEmpty()) {
            info.prefabProblem = "No scene file chosen";
            return;
        }
        Path file = base.resolve(info.prefab).toAbsolutePath().normalize();
        if (!LOADING.get().add(file)) {
            info.prefabProblem = "The prefab " + info.prefab + " has itself inside it";
            return;
        }
        try {
            Scene3D loaded = Scene3DFile.load(file, base, false);
            Node3D root = new Node3D("Prefab");
            for (Node3D child : new ArrayList<>(loaded.root().getChildren())) {
                loaded.root().remove(child);
                root.add(child);
            }
            node.add(root);
            info.prefabRoot = root;
        } catch (RuntimeException e) {
            info.prefabProblem = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("Could not load the prefab {}: {}", file, info.prefabProblem);
        } finally {
            LOADING.get().remove(file);
        }
    }

    static void setModel(Node3D node, String path, Path base) {
        NodeInfo info = node(node);
        info.model = path;
        if (info.shape == Shape.MODEL) loadModel(node, info, base);
    }

    private static void removeModel(Node3D node, NodeInfo info) {
        if (info.modelRoot != null) node.remove(info.modelRoot);
        info.modelRoot = null;
        info.loaded = null;
        info.problem = null;
    }

    /** A model read ahead of time, on another thread, for the next node that asks for its file. Only the last is kept. */
    private static String preloadedKey;
    private static Model preloaded;

    /**
     * Reads the model at {@code path} now, so that the node that is given it next doesn't have to wait for the file. Safe
     * from any thread: it never touches the GPU or a scene.
     */
    static void preloadModel(String path, Path base) throws IOException {
        Path file = base.resolve(path).toAbsolutePath().normalize();
        String key = modelKey(file);
        Model model = Model.load(file);
        model.prepareForPicking();
        synchronized (SceneMeta.class) {
            preloadedKey = key;
            preloaded = model;
        }
    }

    private static synchronized Model takePreloaded(String key) {
        if (!key.equals(preloadedKey)) return null;
        Model model = preloaded;
        preloadedKey = null;
        preloaded = null;
        return model;
    }

    private static String modelKey(Path file) throws IOException {
        return file + "@" + Files.getLastModifiedTime(file).toMillis();
    }

    private static void loadModel(Node3D node, NodeInfo info, Path base) {
        removeModel(node, info);
        if (info.model.isEmpty()) {
            info.problem = "No model file chosen";
            return;
        }
        Path file = base.resolve(info.model).toAbsolutePath().normalize();
        try {
            String key = modelKey(file);
            Model model = info.models.get(key);
            if (model == null) {
                model = takePreloaded(key);
                if (model == null) model = Model.load(file);
                info.models.clear(); // only the last is worth keeping: a model can be big
                info.models.put(key, model);
            }
            node.add(model.root());
            info.modelRoot = model.root();
            info.loaded = model;
        } catch (IOException | RuntimeException e) {
            info.problem = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("Could not load the model {}: {}", file, info.problem);
        }
    }

    // --- Textures -------------------------------------------------------------------------------------------------

    static void setTexture(Material material, int slot, String path, Path base) {
        MaterialInfo info = material(material);
        info.textures[slot] = path;
        TextureData data = Textures.load(path, base);
        boolean pbr = material.isPbr();
        switch (slot) {
            case MaterialInfo.BASE -> material.setTexture(data);
            case MaterialInfo.NORMAL -> material.setNormalTexture(data);
            case MaterialInfo.METALLIC_ROUGHNESS -> material.setMetallicRoughnessTexture(data);
            case MaterialInfo.OCCLUSION -> material.setOcclusionTexture(data);
            case MaterialInfo.HEIGHT -> material.setHeightTexture(data);
            default -> {
                // A glow picture does nothing while there is no glow, so choosing one turns it on, as the engine does.
                if (data != null && info.emissiveStrength == 0f) info.emissiveStrength = 1f;
                material.setEmissiveTexture(data);
                applyEmissive(material, info);
            }
        }
        material.setPbr(pbr);
    }

    // --- Screens ---------------------------------------------------------------------------------------------------

    static void setScreen(Material material, String path, Path base) {
        MaterialInfo info = material(material);
        info.screen = path;
        buildScreen(material, info, base);
    }

    static void setScreenScale(Material material, float scale, Path base) {
        MaterialInfo info = material(material);
        info.screenScale = scale;
        if (!info.screen.isEmpty()) buildScreen(material, info, base);
    }

    /**
     * Draws the layout into a texture and puts it on the material's glow, which a screen takes over: the glow map the
     * file names is kept as it was, so clearing the screen gives it back. A layout that can't be read leaves the
     * material as it was, with the path kept so that saving doesn't lose it.
     */
    private static void buildScreen(Material material, MaterialInfo info, Path base) {
        if (info.uiScreen != null) {
            info.uiScreen.close();
            info.uiScreen = null;
        }
        info.screenProblem = null;
        boolean pbr = material.isPbr();
        if (info.screen.isEmpty()) {
            material.setEmissiveTexture(Textures.load(info.textures[MaterialInfo.EMISSIVE], base));
            material.setPbr(pbr);
            return;
        }
        Path file = base.resolve(info.screen).toAbsolutePath().normalize();
        try {
            UiScreen screen = new UiScreen(UiFile.load(file, base), info.screenScale);
            screen.refresh();
            info.uiScreen = screen;
            if (info.emissiveStrength == 0f) info.emissiveStrength = 1f;
            material.setEmissiveTexture(screen.texture());
            applyEmissive(material, info);
        } catch (RuntimeException e) {
            info.screenProblem = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("Could not load the screen {}: {}", file, info.screenProblem);
        }
        material.setPbr(pbr);
    }

    static Color emissiveColor(Material material) {
        MaterialInfo info = material(material);
        float strength = linearStrength(material);
        if (emissiveMatches(material, info) || strength <= 0f) return info.emissiveColor;
        // A glow set in linear light, as models are: its strongest channel is the strength and the rest the color.
        return new Color(channel(material.getEmissiveRed(), strength), channel(material.getEmissiveGreen(), strength),
                channel(material.getEmissiveBlue(), strength), 1f);
    }

    static float emissiveStrength(Material material) {
        MaterialInfo info = material(material);
        return emissiveMatches(material, info) ? info.emissiveStrength : linearStrength(material);
    }

    static void setEmissive(Material material, Color color, float strength) {
        MaterialInfo info = material(material);
        info.emissiveColor = color;
        info.emissiveStrength = Math.max(0f, strength);
        boolean pbr = material.isPbr();
        applyEmissive(material, info);
        material.setPbr(pbr);
    }

    private static void applyEmissive(Material material, MaterialInfo info) {
        material.setEmissive(info.emissiveColor, info.emissiveStrength);
    }

    // Whether the material still glows as the notes say, or something else changed it since.
    private static boolean emissiveMatches(Material material, MaterialInfo info) {
        float k = info.emissiveStrength;
        return near(material.getEmissiveRed(), (float) Math.pow(info.emissiveColor.getR(), 2.2) * k)
                && near(material.getEmissiveGreen(), (float) Math.pow(info.emissiveColor.getG(), 2.2) * k)
                && near(material.getEmissiveBlue(), (float) Math.pow(info.emissiveColor.getB(), 2.2) * k);
    }

    private static float linearStrength(Material material) {
        return Math.max(material.getEmissiveRed(), Math.max(material.getEmissiveGreen(), material.getEmissiveBlue()));
    }

    private static float channel(float linear, float strength) {
        return (float) Math.pow(linear / strength, 1 / 2.2);
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-4f;
    }

    // --- Sky ------------------------------------------------------------------------------------------------------

    /** Makes the scene's skybox again from what it was told, after any of it changed. */
    static void rebuildSky(Scene3D scene, Path base) {
        SkyInfo info = sky(scene);
        info.problem = null;
        Skybox box = null;
        switch (info.kind) {
            case NONE -> {
            }
            case GRADIENT -> box = Skybox.gradient(info.top, info.horizon, info.bottom);
            case PANORAMA -> {
                if (info.panorama.toLowerCase(java.util.Locale.ROOT).endsWith(".hdr")) {
                    // A picture of light with no upper limit: the sky of a sun that really lights the scene.
                    var hdr = Textures.loadHdr(info.panorama, base);
                    if (hdr != null) box = Skybox.equirectHdr(hdr);
                    else info.problem = "Can't read " + info.panorama;
                } else {
                    TextureData picture = Textures.load(info.panorama, base);
                    if (picture != null) box = Skybox.equirect(picture);
                    else info.problem = info.panorama.isEmpty() ? "No picture chosen" : "Can't read " + info.panorama;
                }
            }
            case CUBE -> {
                TextureData[] faces = new TextureData[6];
                for (int i = 0; i < 6 && info.problem == null; i++) {
                    faces[i] = Textures.load(info.faces[i], base);
                    if (faces[i] == null) info.problem = info.faces[i].isEmpty() ? "A side has no picture" : "Can't read " + info.faces[i];
                }
                if (info.problem == null) {
                    try {
                        box = Skybox.cube(faces[0], faces[1], faces[2], faces[3], faces[4], faces[5]);
                    } catch (IllegalArgumentException e) {
                        info.problem = e.getMessage();
                    }
                }
            }
        }
        if (box != null) box.setBrightness(info.brightness);
        scene.setSkybox(box);
    }
}
