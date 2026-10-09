package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Model;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.render3d.scene.TextureData;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        float size = 1f, radius = 0.5f, width = 10f, depth = 10f;
        int segments = 32, rings = 16;
        String model = "", animation = "";
        Vec3 euler; // the angles last given, which a rotation can be read back as without drifting
        Node3D modelRoot; // the child that holds a model's own nodes
        Model loaded;
        String problem; // why the model isn't there
        final Map<String, Model> models = new HashMap<>(); // loaded before, so switching back is quick
    }

    /** A material's own settings. */
    static final class MaterialInfo {
        static final int BASE = 0, NORMAL = 1, METALLIC_ROUGHNESS = 2, OCCLUSION = 3, EMISSIVE = 4;
        final String[] textures = {"", "", "", "", ""};
        Color emissiveColor = Color.WHITE;
        float emissiveStrength;
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

    private SceneMeta() {
    }

    static NodeInfo node(Node3D node) {
        return NODES.computeIfAbsent(node, n -> new NodeInfo());
    }

    static NodeInfo peek(Node3D node) {
        return NODES.get(node);
    }

    static MaterialInfo material(Material material) {
        return MATERIALS.computeIfAbsent(material, m -> new MaterialInfo());
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

    static void setShape(Node3D node, Shape shape, Path base) {
        NodeInfo info = node(node);
        info.shape = shape;
        if (shape == Shape.MODEL) {
            node.setMesh(null);
            loadModel(node, info, base);
            return;
        }
        removeModel(node, info);
        if (shape == Shape.NONE) {
            node.setMesh(null);
            return;
        }
        node.setMesh(build(info));
        if (node.getMaterial() == null) node.setMaterial(new Material());
    }

    /** Makes the mesh again from the node's size, radius and so on, after one of them changed. */
    static void rebuild(Node3D node) {
        NodeInfo info = node(node);
        if (info.shape == Shape.CUBE || info.shape == Shape.SPHERE || info.shape == Shape.PLANE) node.setMesh(build(info));
    }

    private static Mesh build(NodeInfo info) {
        return switch (info.shape) {
            case CUBE -> Mesh.cube(Math.max(0.001f, info.size));
            case SPHERE -> Mesh.sphere(Math.max(0.001f, info.radius), Math.max(3, info.segments), Math.max(2, info.rings));
            case PLANE -> Mesh.plane(Math.max(0.001f, info.width), Math.max(0.001f, info.depth));
            default -> null;
        };
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

    private static void loadModel(Node3D node, NodeInfo info, Path base) {
        removeModel(node, info);
        if (info.model.isEmpty()) {
            info.problem = "No model file chosen";
            return;
        }
        Path file = base.resolve(info.model).toAbsolutePath().normalize();
        try {
            String key = file + "@" + Files.getLastModifiedTime(file).toMillis();
            Model model = info.models.get(key);
            if (model == null) {
                model = Model.load(file);
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
            default -> {
                // A glow picture does nothing while there is no glow, so choosing one turns it on, as the engine does.
                if (data != null && info.emissiveStrength == 0f) info.emissiveStrength = 1f;
                material.setEmissiveTexture(data);
                applyEmissive(material, info);
            }
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
