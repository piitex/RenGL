package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Camera;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.LightFalloff;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.ShadowQuality;
import me.piitex.engine.render3d.scene.Shadows;
import me.piitex.engine.render3d.scene.file.SceneMeta.MaterialInfo;
import me.piitex.engine.render3d.scene.file.SceneMeta.NodeInfo;
import me.piitex.engine.render3d.scene.file.SceneMeta.SkyInfo;
import me.piitex.engine.render3d.scene.file.SceneProperty.Hint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What a scene file holds for each kind of part, as lists of {@link SceneProperty}: the one list is what is saved, what
 * is loaded and what an editor shows fields for.
 * <ul>
 *   <li>{@link #node()}: a {@link Node3D}, which is where it is, what it draws and how.</li>
 *   <li>{@link #material()}: the {@link Material} of a node that draws a shape.</li>
 *   <li>{@link #light(Light.Type)}: a {@link Light} of that type.</li>
 *   <li>{@link #camera()}: the scene's {@link Camera}.</li>
 *   <li>{@link #world()}: the scene itself: its background, sky, fog, shadows and gravity.</li>
 *   <li>{@link #joint(JointKind)}: a {@link SceneJoint} of that kind.</li>
 * </ul>
 * A node's physics (its body, collider and character settings) are node properties, in the "Physics", "Collider" and
 * "Character" groups.
 * Each list is in the order an editor should show them, and properties with a group share a section.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class SceneKinds {
    private static final List<SceneProperty<Node3D>> NODE = new ArrayList<>();
    private static final List<SceneProperty<Material>> MATERIAL = new ArrayList<>();
    private static final List<SceneProperty<Light>> DIRECTIONAL = new ArrayList<>();
    private static final List<SceneProperty<Light>> POINT = new ArrayList<>();
    private static final List<SceneProperty<Light>> SPOT = new ArrayList<>();
    private static final List<SceneProperty<Camera>> CAMERA = new ArrayList<>();
    private static final List<SceneProperty<Scene3D>> WORLD = new ArrayList<>();
    private static final Map<JointKind, List<SceneProperty<SceneJoint>>> JOINTS = new EnumMap<>(JointKind.class);

    private SceneKinds() {
    }

    /** The properties of a node, in the order to show them. */
    public static List<SceneProperty<Node3D>> node() {
        return NODE;
    }

    /** The properties of the material of a node that draws a shape. */
    public static List<SceneProperty<Material>> material() {
        return MATERIAL;
    }

    /** The properties of a light of {@code type}. */
    public static List<SceneProperty<Light>> light(Light.Type type) {
        return switch (type) {
            case DIRECTIONAL -> DIRECTIONAL;
            case POINT -> POINT;
            case SPOT -> SPOT;
        };
    }

    public static List<SceneProperty<Camera>> camera() {
        return CAMERA;
    }

    /** The properties of the scene as a whole. */
    public static List<SceneProperty<Scene3D>> world() {
        return WORLD;
    }

    /** The properties of a joint of {@code kind}. */
    public static List<SceneProperty<SceneJoint>> joint(JointKind kind) {
        return JOINTS.get(kind);
    }

    /** A new joint of {@code kind}, named after its kind; it still needs the nodes it joins. */
    public static SceneJoint newJoint(JointKind kind) {
        String name = kind.name().charAt(0) + kind.name().substring(1).toLowerCase();
        return new SceneJoint(kind).setName(name);
    }

    /** A node of {@code shape} named {@code name}, with a plain grey material if it draws anything. */
    public static Node3D newNode(Shape shape, String name) {
        Node3D node = new Node3D(name);
        SceneMeta.setShape(node, shape, java.nio.file.Path.of(""));
        if (node.getMaterial() != null) node.getMaterial().setColor(0.8f, 0.8f, 0.8f, 1f);
        return node;
    }

    /** A new light of {@code type} with settings that light something at the origin. */
    public static Light newLight(Light.Type type) {
        Light light = switch (type) {
            case DIRECTIONAL -> Light.directional(new Vec3(-0.4f, -1f, -0.6f), Color.WHITE, 0.9f);
            case POINT -> Light.point(new Vec3(0f, 3f, 0f), Color.WHITE, 1.2f, 10f);
            case SPOT -> Light.spot(new Vec3(0f, 4f, 0f), new Vec3(0f, -1f, 0f), Color.WHITE, 1.5f, 12f, 25f, 35f);
        };
        SceneMeta.setLightName(light, SceneMeta.defaultName(light));
        return light;
    }

    // --- Building --------------------------------------------------------------------------------------------------

    private static <T> SceneProperty<T> prop(List<SceneProperty<T>> into, String name, String label, Class<?> type, String group,
                                             Function<T, Object> get, SceneProperty.Setter<T> set) {
        SceneProperty<T> property = new SceneProperty<>(name, label, type, get, set).group(group);
        into.add(property);
        return property;
    }

    private static float f(Object value) {
        return ((Number) value).floatValue();
    }

    private static Vec3 v(Object value) {
        return (Vec3) value;
    }

    private static Color c(Object value) {
        return (Color) value;
    }

    private static String s(Object value) {
        return value == null ? "" : (String) value;
    }

    // --- Nodes -----------------------------------------------------------------------------------------------------

    private static NodeInfo info(Node3D node) {
        return SceneMeta.node(node);
    }

    private static boolean shapeIs(Node3D node, Shape shape) {
        NodeInfo info = SceneMeta.peek(node);
        return (info == null ? Shape.NONE : info.shape) == shape;
    }

    static {
        String node = "Node", transform = "Transform", shape = "Shape", rendering = "Rendering";
        prop(NODE, "name", "Name", String.class, node, Node3D::getName, (n, x, b) -> n.setName(s(x)))
                .tip("What the game finds it by");
        prop(NODE, "position", "Position", Vec3.class, transform, Node3D::getPosition, (n, x, b) -> n.setPosition(v(x)))
                .omitWhen(Vec3.ZERO).step(0.1).tip("Where it is, relative to what it is in");
        prop(NODE, "rotation", "Rotation", Vec3.class, transform, SceneMeta::euler, (n, x, b) -> SceneMeta.setEuler(n, v(x)))
                .omitWhen(Vec3.ZERO).step(1).range(-360, 360).tip("Turn in degrees around X (nod), Y (turn) and Z (tilt)");
        prop(NODE, "scale", "Scale", Vec3.class, transform, Node3D::getScale, (n, x, b) -> n.setScale(v(x)))
                .omitWhen(Vec3.ONE).step(0.1).tip("How much bigger or smaller, along each axis");

        prop(NODE, "shape", "Shape", Shape.class, shape, n -> info(n).shape, (n, x, b) -> SceneMeta.setShape(n, (Shape) x, b))
                .omitWhen(Shape.NONE).tip("What it draws. A group draws nothing and moves what is in it together");
        prop(NODE, "size", "Size", Float.class, shape, n -> info(n).size, (n, x, b) -> {
            info(n).size = f(x);
            SceneMeta.rebuild(n);
        }).omitWhen(1f).range(0.01, 1000).step(0.1).when(n -> shapeIs(n, Shape.CUBE));
        prop(NODE, "radius", "Radius", Float.class, shape, n -> info(n).radius, (n, x, b) -> {
            info(n).radius = f(x);
            SceneMeta.rebuild(n);
        }).omitWhen(0.5f).range(0.01, 1000).step(0.1).when(n -> shapeIs(n, Shape.SPHERE));
        prop(NODE, "segments", "Segments", Integer.class, shape, n -> info(n).segments, (n, x, b) -> {
            info(n).segments = ((Number) x).intValue();
            SceneMeta.rebuild(n);
        }).omitWhen(32).range(3, 128).when(n -> shapeIs(n, Shape.SPHERE)).tip("How many flat sides it has around");
        prop(NODE, "rings", "Rings", Integer.class, shape, n -> info(n).rings, (n, x, b) -> {
            info(n).rings = ((Number) x).intValue();
            SceneMeta.rebuild(n);
        }).omitWhen(16).range(2, 64).when(n -> shapeIs(n, Shape.SPHERE)).tip("How many flat bands it has from pole to pole");
        prop(NODE, "width", "Width", Float.class, shape, n -> info(n).width, (n, x, b) -> {
            info(n).width = f(x);
            SceneMeta.rebuild(n);
        }).omitWhen(10f).range(0.01, 10000).step(0.5).when(n -> shapeIs(n, Shape.PLANE)).tip("Along X");
        prop(NODE, "depth", "Depth", Float.class, shape, n -> info(n).depth, (n, x, b) -> {
            info(n).depth = f(x);
            SceneMeta.rebuild(n);
        }).omitWhen(10f).range(0.01, 10000).step(0.5).when(n -> shapeIs(n, Shape.PLANE)).tip("Along Z");
        prop(NODE, "model", "Model", String.class, shape, n -> info(n).model, (n, x, b) -> SceneMeta.setModel(n, s(x), b))
                .omitWhen("").hint(Hint.MODEL).when(n -> shapeIs(n, Shape.MODEL))
                .tip("A glTF, GLB, OBJ, FBX or Collada file in the project");
        prop(NODE, "animation", "Animation", String.class, shape, n -> info(n).animation, (n, x, b) -> info(n).animation = s(x))
                .omitWhen("").hint(Hint.ANIMATION).when(n -> shapeIs(n, Shape.MODEL) && !Scene3DFile.animations(n).isEmpty())
                .tip("An animation of the model that plays, over and over, when the game starts");

        prop(NODE, "visible", "Visible", Boolean.class, rendering, Node3D::isVisible, (n, x, b) -> n.setVisible((Boolean) x))
                .omitWhen(true).tip("A node that isn't visible hides everything in it too");
        prop(NODE, "pickable", "Pickable", Boolean.class, rendering, Node3D::isPickable, (n, x, b) -> n.setPickable((Boolean) x))
                .omitWhen(true).tip("Whether a click, or a ray, can hit it");
        prop(NODE, "castShadow", "Cast Shadow", Boolean.class, rendering, Node3D::isCastShadow, (n, x, b) -> n.setCastShadow((Boolean) x))
                .omitWhen(true);
        prop(NODE, "receiveShadow", "Receive Shadow", Boolean.class, rendering, Node3D::isReceiveShadow, (n, x, b) -> n.setReceiveShadow((Boolean) x))
                .omitWhen(true);
    }

    // --- Materials -------------------------------------------------------------------------------------------------

    private static boolean pbr(Material m) {
        return m.isPbr();
    }

    // The setters of a physically based material turn it on, which a file that says it is off means to override.
    private static void keepPbr(Material m, Runnable change) {
        boolean pbr = m.isPbr();
        change.run();
        m.setPbr(pbr);
    }

    static {
        String surface = "Material", shading = "Shading", maps = "Maps", glow = "Glow";
        prop(MATERIAL, "color", "Color", Color.class, surface, m -> new Color(m.getRed(), m.getGreen(), m.getBlue(), m.getAlpha()),
                (m, x, b) -> m.setColor(c(x))).omitWhen(Color.WHITE).tip("Times the picture, if it has one");
        prop(MATERIAL, "texture", "Picture", String.class, surface, m -> SceneMeta.material(m).textures[MaterialInfo.BASE],
                (m, x, b) -> SceneMeta.setTexture(m, MaterialInfo.BASE, s(x), b)).omitWhen("").hint(Hint.IMAGE);
        prop(MATERIAL, "unlit", "Unlit", Boolean.class, surface, Material::isUnlit, (m, x, b) -> m.setUnlit((Boolean) x))
                .omitWhen(false).tip("Draw it in its plain color, ignoring the lights");
        prop(MATERIAL, "doubleSided", "Double Sided", Boolean.class, surface, Material::isDoubleSided, (m, x, b) -> m.setDoubleSided((Boolean) x))
                .omitWhen(false).tip("Draw the back of its triangles too");
        prop(MATERIAL, "blend", "Blend", Boolean.class, surface, Material::isBlend, (m, x, b) -> m.setBlend((Boolean) x))
                .omitWhen(false).tip("See-through where its color has alpha below 1");
        prop(MATERIAL, "additive", "Additive", Boolean.class, surface, Material::isAdditive, (m, x, b) -> m.setAdditive((Boolean) x))
                .omitWhen(false).tip("Add its color to what is behind it, for glows and flames");
        prop(MATERIAL, "alphaCutoff", "Alpha Cutoff", Float.class, surface, Material::getAlphaCutoff, (m, x, b) -> m.setAlphaCutoff(f(x)))
                .omitWhen(0f).range(0, 1).step(0.05).tip("Leaves out the pixels whose alpha is below this, for leaves and fences");

        prop(MATERIAL, "pbr", "Physically Based", Boolean.class, shading, Material::isPbr, (m, x, b) -> m.setPbr((Boolean) x))
                .omitWhen(false).tip("Metal and roughness, as in a glTF model, instead of a plain highlight");
        prop(MATERIAL, "specular", "Specular", Float.class, shading, Material::getSpecular, (m, x, b) -> m.setSpecular(f(x)))
                .omitWhen(0.3f).range(0, 1).step(0.05).when(m -> !pbr(m)).tip("How strong its highlight is");
        prop(MATERIAL, "shininess", "Shininess", Float.class, shading, Material::getShininess, (m, x, b) -> m.setShininess(f(x)))
                .omitWhen(32f).range(1, 512).step(1).when(m -> !pbr(m)).tip("How tight its highlight is");
        prop(MATERIAL, "metallic", "Metallic", Float.class, shading, Material::getMetallic, (m, x, b) -> keepPbr(m, () -> m.setMetallic(f(x))))
                .omitWhen(0f).range(0, 1).step(0.05).when(Material::isPbr).tip("0 for plastic, wood and stone, 1 for bare metal");
        prop(MATERIAL, "roughness", "Roughness", Float.class, shading, Material::getRoughness, (m, x, b) -> keepPbr(m, () -> m.setRoughness(f(x))))
                .omitWhen(0.5f).range(0, 1).step(0.05).when(Material::isPbr).tip("0 for a mirror-sharp highlight, 1 for matte");

        prop(MATERIAL, "normalTexture", "Normal Map", String.class, maps, m -> SceneMeta.material(m).textures[MaterialInfo.NORMAL],
                (m, x, b) -> SceneMeta.setTexture(m, MaterialInfo.NORMAL, s(x), b)).omitWhen("").hint(Hint.IMAGE).when(Material::isPbr)
                .tip("Bumps and dents on a flat surface");
        prop(MATERIAL, "normalScale", "Normal Strength", Float.class, maps, Material::getNormalScale, (m, x, b) -> m.setNormalScale(f(x)))
                .omitWhen(1f).range(0, 4).step(0.1).when(m -> pbr(m) && !SceneMeta.material(m).textures[MaterialInfo.NORMAL].isEmpty());
        prop(MATERIAL, "metallicRoughnessTexture", "Metal/Rough Map", String.class, maps,
                m -> SceneMeta.material(m).textures[MaterialInfo.METALLIC_ROUGHNESS],
                (m, x, b) -> SceneMeta.setTexture(m, MaterialInfo.METALLIC_ROUGHNESS, s(x), b)).omitWhen("").hint(Hint.IMAGE).when(Material::isPbr)
                .tip("Roughness in its green channel and metal in its blue");
        prop(MATERIAL, "occlusionTexture", "Occlusion Map", String.class, maps, m -> SceneMeta.material(m).textures[MaterialInfo.OCCLUSION],
                (m, x, b) -> SceneMeta.setTexture(m, MaterialInfo.OCCLUSION, s(x), b)).omitWhen("").hint(Hint.IMAGE).when(Material::isPbr)
                .tip("Baked shadow in creases, in its red channel");
        prop(MATERIAL, "emissiveTexture", "Glow Map", String.class, maps, m -> SceneMeta.material(m).textures[MaterialInfo.EMISSIVE],
                (m, x, b) -> SceneMeta.setTexture(m, MaterialInfo.EMISSIVE, s(x), b)).omitWhen("").hint(Hint.IMAGE).when(Material::isPbr);

        String placing = "Texture Placement";
        prop(MATERIAL, "uvOffsetU", "Shift Across", Float.class, placing, Material::getUvOffsetU,
                (m, x, b) -> m.setUvTransform(f(x), m.getUvOffsetV(), m.getUvScaleU(), m.getUvScaleV(), m.getUvRotation())).omitWhen(0f).range(-100, 100).step(0.05)
                .tip("Slides every texture of the material along the surface, across. 1 is a whole texture");
        prop(MATERIAL, "uvOffsetV", "Shift Down", Float.class, placing, Material::getUvOffsetV,
                (m, x, b) -> m.setUvTransform(m.getUvOffsetU(), f(x), m.getUvScaleU(), m.getUvScaleV(), m.getUvRotation())).omitWhen(0f).range(-100, 100).step(0.05)
                .tip("Slides every texture of the material along the surface, down");
        prop(MATERIAL, "uvScaleU", "Repeat Across", Float.class, placing, Material::getUvScaleU,
                (m, x, b) -> m.setUvTransform(m.getUvOffsetU(), m.getUvOffsetV(), f(x), m.getUvScaleV(), m.getUvRotation())).omitWhen(1f).range(-100, 100).step(0.1)
                .tip("How many times the texture repeats across the surface");
        prop(MATERIAL, "uvScaleV", "Repeat Down", Float.class, placing, Material::getUvScaleV,
                (m, x, b) -> m.setUvTransform(m.getUvOffsetU(), m.getUvOffsetV(), m.getUvScaleU(), f(x), m.getUvRotation())).omitWhen(1f).range(-100, 100).step(0.1)
                .tip("How many times the texture repeats down the surface");
        prop(MATERIAL, "uvRotation", "Turn", Float.class, placing, m -> (float) Math.toDegrees(m.getUvRotation()),
                (m, x, b) -> m.setUvTransform(m.getUvOffsetU(), m.getUvOffsetV(), m.getUvScaleU(), m.getUvScaleV(), (float) Math.toRadians(f(x)))).omitWhen(0f)
                .range(-360, 360).step(1).tip("Turns the textures on the surface, in degrees");
        prop(MATERIAL, "emissiveColor", "Glow Color", Color.class, glow, SceneMeta::emissiveColor,
                (m, x, b) -> SceneMeta.setEmissive(m, c(x), SceneMeta.emissiveStrength(m))).omitWhen(Color.WHITE)
                .tip("Light it gives off whatever the lights do. It doesn't light its surroundings");
        prop(MATERIAL, "emissiveStrength", "Glow Strength", Float.class, glow, SceneMeta::emissiveStrength,
                (m, x, b) -> SceneMeta.setEmissive(m, SceneMeta.emissiveColor(m), f(x))).omitWhen(0f).range(0, 20).step(0.1);
    }

    // --- Lights ----------------------------------------------------------------------------------------------------

    private static LightShadow shadowOf(Light light) {
        Shadows shadows = light.getShadows();
        if (shadows == null) return LightShadow.OFF;
        if (shadows == Shadows.SCENE || shadows.size() == 0) return LightShadow.SCENE;
        ShadowQuality best = ShadowQuality.LOW;
        for (ShadowQuality q : ShadowQuality.values()) {
            if (Math.abs(q.size() - shadows.size()) < Math.abs(best.size() - shadows.size())) best = q;
        }
        return LightShadow.valueOf(best.name());
    }

    private static void setShadow(Light light, LightShadow shadow) {
        switch (shadow) {
            case OFF -> light.setCastShadow(false);
            case SCENE -> light.setShadows(Shadows.SCENE);
            default -> light.setShadows(ShadowQuality.valueOf(shadow.name()));
        }
    }

    private static Falloff falloffOf(Light light) {
        LightFalloff falloff = light.getFalloff();
        if (falloff == LightFalloff.LINEAR) return Falloff.LINEAR;
        if (falloff == LightFalloff.NONE) return Falloff.NONE;
        return Falloff.WINDOWED_INVERSE_SQUARE;
    }

    private static void setFalloff(Light light, Falloff falloff) {
        light.setFalloff(switch (falloff) {
            case LINEAR -> LightFalloff.LINEAR;
            case NONE -> LightFalloff.NONE;
            default -> LightFalloff.WINDOWED_INVERSE_SQUARE;
        });
    }

    static {
        for (Light.Type type : Light.Type.values()) {
            List<SceneProperty<Light>> list = light(type);
            String group = "Light", shadow = "Shadow";
            prop(list, "name", "Name", String.class, group, SceneMeta::lightName, (l, x, b) -> SceneMeta.setLightName(l, s(x)))
                    .tip("What the game finds it by");
            prop(list, "color", "Color", Color.class, group, Light::getColor, (l, x, b) -> l.setColor(c(x))).omitWhen(Color.WHITE);
            prop(list, "intensity", "Intensity", Float.class, group, Light::getIntensity, (l, x, b) -> l.setIntensity(f(x)))
                    .range(0, 20).step(0.1);
            if (type == Light.Type.DIRECTIONAL) {
                prop(list, "direction", "Direction", Vec3.class, group, Light::getVector, (l, x, b) -> l.setVector(v(x)))
                        .step(0.1).tip("The way the light travels: (0, -1, 0) shines straight down");
            } else {
                prop(list, "position", "Position", Vec3.class, group, Light::getVector, (l, x, b) -> l.setVector(v(x))).step(0.1);
                prop(list, "range", "Range", Float.class, group, Light::getRange, (l, x, b) -> l.setRange(f(x)))
                        .range(0.1, 1000).step(0.5).tip("How far it reaches. It is gone there");
                prop(list, "falloff", "Falloff", Falloff.class, group, SceneKinds::falloffOf, (l, x, b) -> setFalloff(l, (Falloff) x))
                        .omitWhen(Falloff.WINDOWED_INVERSE_SQUARE);
            }
            if (type == Light.Type.SPOT) {
                prop(list, "direction", "Direction", Vec3.class, group, Light::getDirection, (l, x, b) -> l.setDirection(v(x)))
                        .step(0.1).tip("The way the cone points");
                prop(list, "innerAngle", "Inner Angle", Float.class, group, Light::getInnerAngle,
                        (l, x, b) -> l.setCone(f(x), l.getOuterAngle())).range(0, 84).step(1).tip("Degrees from the middle where it is at full strength");
                prop(list, "outerAngle", "Outer Angle", Float.class, group, Light::getOuterAngle,
                        (l, x, b) -> l.setCone(l.getInnerAngle(), f(x))).range(1, 85).step(1).tip("Degrees from the middle where it is gone");
            }
            prop(list, "shadows", "Shadows", LightShadow.class, shadow, SceneKinds::shadowOf, (l, x, b) -> setShadow(l, (LightShadow) x))
                    .omitWhen(LightShadow.OFF).tip("At most four lights cast shadows at a time");
        }
    }

    // --- Camera ----------------------------------------------------------------------------------------------------

    static {
        String view = "View";
        prop(CAMERA, "position", "Position", Vec3.class, view, Camera::getPosition, (c, x, b) -> c.setPosition(v(x))).step(0.1);
        prop(CAMERA, "target", "Looks At", Vec3.class, view, Camera::getTarget, (c, x, b) -> c.setTarget(v(x))).step(0.1)
                .tip("The point it looks at");
        prop(CAMERA, "fov", "Field of View", Float.class, view, Camera::getFovYDegrees, (c, x, b) -> c.setFovYDegrees(f(x)))
                .omitWhen(60f).range(1, 170).step(1).tip("Degrees from the top of the picture to the bottom");
        prop(CAMERA, "near", "Near", Float.class, view, Camera::getNear, (c, x, b) -> {
            float near = Math.max(0.001f, f(x));
            c.setClip(near, Math.max(c.getFar(), near * 2f));
        }).omitWhen(0.1f).range(0.001, 1000).step(0.05).tip("Closer than this is not seen");
        prop(CAMERA, "far", "Far", Float.class, view, Camera::getFar, (c, x, b) -> c.setClip(c.getNear(), Math.max(f(x), c.getNear() * 2f)))
                .omitWhen(200f).range(0.01, 100000).step(10).tip("Farther than this is not seen");
    }

    // --- The scene -------------------------------------------------------------------------------------------------

    private static final Color DEFAULT_BACKGROUND = new Color(0.10f, 0.11f, 0.14f, 1f);
    private static final Color DEFAULT_AMBIENT = new Color(0.45f, 0.45f, 0.5f, 1f);

    private static boolean sky(Scene3D scene, SkyKind kind) {
        return SceneMeta.sky(scene).kind == kind;
    }

    private static String[] faces(Scene3D scene) {
        return SceneMeta.sky(scene).faces;
    }

    private static void sides(String name, String label, int index) {
        prop(WORLD, name, label, String.class, "Sky", s -> faces(s)[index], (s, x, b) -> {
            faces(s)[index] = s(x);
            SceneMeta.rebuildSky(s, b);
        }).omitWhen("").hint(Hint.IMAGE).when(s -> sky(s, SkyKind.CUBE));
    }

    static {
        String gBackground = "Background", gSky = "Sky", gLight = "Light", gFog = "Fog", gShadow = "Shadows";
        prop(WORLD, "background", "Color", Color.class, gBackground, Scene3D::getBackground, (s, x, b) -> s.setBackground(c(x)))
                .omitWhen(DEFAULT_BACKGROUND).tip("What the picture is cleared to, behind the sky");
        prop(WORLD, "skyKind", "Sky", SkyKind.class, gSky, s -> SceneMeta.sky(s).kind, (s, x, b) -> {
            SceneMeta.sky(s).kind = (SkyKind) x;
            SceneMeta.rebuildSky(s, b);
        }).omitWhen(SkyKind.NONE);
        prop(WORLD, "skyTop", "Top", Color.class, gSky, s -> SceneMeta.sky(s).top, (s, x, b) -> {
            SceneMeta.sky(s).top = c(x);
            SceneMeta.rebuildSky(s, b);
        }).when(s -> sky(s, SkyKind.GRADIENT)).omitWhen(new Color(0.25f, 0.45f, 0.85f, 1f));
        prop(WORLD, "skyHorizon", "Horizon", Color.class, gSky, s -> SceneMeta.sky(s).horizon, (s, x, b) -> {
            SceneMeta.sky(s).horizon = c(x);
            SceneMeta.rebuildSky(s, b);
        }).when(s -> sky(s, SkyKind.GRADIENT)).omitWhen(new Color(0.75f, 0.85f, 0.95f, 1f));
        prop(WORLD, "skyBottom", "Ground", Color.class, gSky, s -> SceneMeta.sky(s).bottom, (s, x, b) -> {
            SceneMeta.sky(s).bottom = c(x);
            SceneMeta.rebuildSky(s, b);
        }).when(s -> sky(s, SkyKind.GRADIENT)).omitWhen(new Color(0.35f, 0.33f, 0.3f, 1f));
        prop(WORLD, "skyPanorama", "Picture", String.class, gSky, s -> SceneMeta.sky(s).panorama, (s, x, b) -> {
            SceneMeta.sky(s).panorama = s(x);
            SceneMeta.rebuildSky(s, b);
        }).omitWhen("").hint(Hint.IMAGE).when(s -> sky(s, SkyKind.PANORAMA)).tip("Twice as wide as it is tall");
        sides("skyRight", "Right (+X)", 0);
        sides("skyLeft", "Left (-X)", 1);
        sides("skyUp", "Up (+Y)", 2);
        sides("skyDown", "Down (-Y)", 3);
        sides("skyFront", "Front (+Z)", 4);
        sides("skyBack", "Back (-Z)", 5);
        prop(WORLD, "skyBrightness", "Brightness", Float.class, gSky, s -> SceneMeta.sky(s).brightness, (s, x, b) -> {
            SceneMeta.sky(s).brightness = Math.max(0f, f(x));
            SceneMeta.rebuildSky(s, b);
        }).omitWhen(1f).range(0, 4).step(0.05).when(s -> !sky(s, SkyKind.NONE));

        prop(WORLD, "ambientColor", "Ambient Color", Color.class, gLight, Scene3D::getAmbient,
                (s, x, b) -> s.setAmbient(c(x), s.getAmbientIntensity())).omitWhen(DEFAULT_AMBIENT);
        prop(WORLD, "ambientIntensity", "Ambient", Float.class, gLight, Scene3D::getAmbientIntensity,
                (s, x, b) -> s.setAmbient(s.getAmbient(), f(x))).omitWhen(1f).range(0, 4).step(0.05)
                .tip("Light that reaches every surface from every side, so nothing is pitch black");
        prop(WORLD, "environmentLighting", "Sky Lighting", Float.class, gLight, Scene3D::getEnvironmentLighting,
                (s, x, b) -> s.setEnvironmentLighting(f(x))).omitWhen(0f).range(0, 4).step(0.05).when(s -> !sky(s, SkyKind.NONE))
                .tip("Light and reflections from the sky, in place of ambient light. 0 turns it off");

        prop(WORLD, "toneMapping", "Tone Mapping", me.piitex.engine.render3d.scene.ToneMapping.class, gLight, Scene3D::getToneMapping,
                (s, x, b) -> s.setToneMapping((me.piitex.engine.render3d.scene.ToneMapping) x)).omitWhen(me.piitex.engine.render3d.scene.ToneMapping.NONE)
                .tip("How light brighter than white is bent into the range of the screen. None cuts it off; ACES is the film look");
        prop(WORLD, "exposure", "Exposure", Float.class, gLight, Scene3D::getExposure, (s, x, b) -> s.setExposure(f(x))).omitWhen(1f)
                .range(0, 8).step(0.05).tip("Scales the light before tone mapping: 2 is one stop brighter");

        prop(WORLD, "fogColor", "Color", Color.class, gFog, Scene3D::getFogColor, (s, x, b) -> s.setFog(c(x), s.getFogDensity(), s.getFogHeightFalloff()))
                .omitWhen(Color.WHITE).tip("What distant things fade into. Match it to the horizon");
        prop(WORLD, "fogDensity", "Density", Float.class, gFog, Scene3D::getFogDensity,
                (s, x, b) -> s.setFog(s.getFogColor(), f(x), s.getFogHeightFalloff())).omitWhen(0f).range(0, 1).step(0.005)
                .tip("0.02 hazes things 40 units away. 0 turns fog off");
        prop(WORLD, "fogHeightFalloff", "Height Falloff", Float.class, gFog, Scene3D::getFogHeightFalloff,
                (s, x, b) -> s.setFog(s.getFogColor(), s.getFogDensity(), f(x))).omitWhen(0f).range(0, 2).step(0.05)
                .tip("Thicker low down, like mist over a meadow. 0 is the same at every height");

        prop(WORLD, "shadowMapSize", "Map Size", Integer.class, gShadow, Scene3D::getShadowMapSize, (s, x, b) -> s.setShadowMapSize(((Number) x).intValue()))
                .omitWhen(2048).range(256, 8192).tip("The sharpness of shadows, for lights that don't choose their own");
        prop(WORLD, "shadowDistance", "Distance", Float.class, gShadow, Scene3D::getShadowDistance, (s, x, b) -> s.setShadowDistance(f(x)))
                .omitWhen(60f).range(0.1, 1000).step(1).tip("How far from the camera shadows are drawn");
        prop(WORLD, "shadowBias", "Bias", Float.class, gShadow, Scene3D::getShadowBias, (s, x, b) -> s.setShadowBias(f(x)))
                .omitWhen(1f).range(0, 10).step(0.1).tip("Raise it if a lit surface shows stripes, lower it if shadows float away");
        prop(WORLD, "shadowSoftness", "Softness", Float.class, gShadow, Scene3D::getShadowSoftness, (s, x, b) -> s.setShadowSoftness(f(x)))
                .omitWhen(1f).range(0.25, 4).step(0.05).tip("The width of a shadow's soft edge");
    }

    // --- Physics ---------------------------------------------------------------------------------------------------

    private static final ScenePhysics.BodyInfo NO_BODY = new ScenePhysics.BodyInfo();

    /** A node's body settings to read: the defaults for a node that has none, without giving it any. */
    private static ScenePhysics.BodyInfo seen(Node3D node) {
        ScenePhysics.BodyInfo info = ScenePhysics.peek(node);
        return info == null ? NO_BODY : info;
    }

    private static ScenePhysics.BodyInfo body(Node3D node) {
        return ScenePhysics.body(node);
    }

    private static boolean bodyIs(Node3D node, BodyKind kind) {
        return seen(node).kind == kind;
    }

    private static boolean colliderIs(Node3D node, ColliderKind... kinds) {
        ScenePhysics.BodyInfo info = seen(node);
        if (!info.rigid()) return false;
        for (ColliderKind kind : kinds) if (info.collider == kind) return true;
        return false;
    }

    static {
        String physics = "Physics", collider = "Collider", character = "Character";
        prop(NODE, "body", "Body", BodyKind.class, physics, n -> seen(n).kind, (n, x, b) -> body(n).kind = (BodyKind) x)
                .omitWhen(BodyKind.NONE).tip("How physics moves it: Static never moves, Dynamic falls and is pushed, Kinematic is moved "
                        + "by the game and pushes what is in its way, and Character is walked by the game");
        prop(NODE, "collider", "Shape", ColliderKind.class, collider, n -> seen(n).collider, (n, x, b) -> body(n).collider = (ColliderKind) x)
                .omitWhen(ColliderKind.AUTO).when(n -> seen(n).rigid())
                .tip("What it collides as. Auto fits what it draws: a model's own triangles if it doesn't move, a hull around them if it does");
        prop(NODE, "colliderSize", "Size", Vec3.class, collider, n -> seen(n).size, (n, x, b) -> body(n).size = v(x))
                .omitWhen(Vec3.ONE).step(0.1).when(n -> colliderIs(n, ColliderKind.BOX)).tip("The box's length along X, Y and Z, before the node's scale");
        prop(NODE, "colliderRadius", "Radius", Float.class, collider, n -> seen(n).radius, (n, x, b) -> body(n).radius = f(x))
                .omitWhen(0.5f).range(0.001, 1000).step(0.05).when(n -> colliderIs(n, ColliderKind.SPHERE, ColliderKind.CAPSULE))
                .tip("The distance from its middle to its surface");
        prop(NODE, "colliderHeight", "Height", Float.class, collider, n -> seen(n).height, (n, x, b) -> body(n).height = f(x))
                .omitWhen(2f).range(0.002, 1000).step(0.1).when(n -> colliderIs(n, ColliderKind.CAPSULE))
                .tip("From the bottom of the capsule to its top, along Y");
        prop(NODE, "colliderWeld", "Weld", Float.class, collider, n -> seen(n).weld, (n, x, b) -> body(n).weld = f(x))
                .omitWhen(0f).range(0, 0.5).step(0.001).when(n -> colliderIs(n, ColliderKind.MESH, ColliderKind.AUTO))
                .tip("Joins corners of its triangles this close together, in metres, so pieces that only nearly meet have no gap to catch on. "
                        + "0 joins only corners at exactly the same place");
        prop(NODE, "colliderOffset", "Offset", Vec3.class, collider, n -> seen(n).offset, (n, x, b) -> body(n).offset = v(x))
                .omitWhen(Vec3.ZERO).step(0.1).when(n -> colliderIs(n, ColliderKind.BOX, ColliderKind.SPHERE, ColliderKind.CAPSULE))
                .tip("How far the shape's middle is from the node's origin, before the node's scale");
        prop(NODE, "mass", "Mass", Float.class, physics, n -> seen(n).mass, (n, x, b) -> body(n).mass = f(x))
                .omitWhen(1f).range(0.001, 1_000_000).step(1).when(n -> bodyIs(n, BodyKind.DYNAMIC)).tip("How heavy it is, in kilograms");
        prop(NODE, "friction", "Friction", Float.class, physics, n -> seen(n).friction, (n, x, b) -> body(n).friction = f(x))
                .omitWhen(0.5f).range(0, 2).step(0.05).when(n -> seen(n).rigid()).tip("How much it resists sliding: 0 is ice, 1 is rubber");
        prop(NODE, "bounce", "Bounce", Float.class, physics, n -> seen(n).bounce, (n, x, b) -> body(n).bounce = f(x))
                .omitWhen(0f).range(0, 1).step(0.05).when(n -> seen(n).rigid())
                .tip("How much of its speed it keeps when it hits something: 0 thuds, 1 bounces back as high");
        prop(NODE, "linearDamping", "Drag", Float.class, physics, n -> seen(n).linearDamping, (n, x, b) -> body(n).linearDamping = f(x))
                .omitWhen(0f).range(0, 10).step(0.05).when(n -> bodyIs(n, BodyKind.DYNAMIC)).tip("How quickly it slows down by itself, as in air or water");
        prop(NODE, "angularDamping", "Spin Drag", Float.class, physics, n -> seen(n).angularDamping, (n, x, b) -> body(n).angularDamping = f(x))
                .omitWhen(0.05f).range(0, 10).step(0.05).when(n -> bodyIs(n, BodyKind.DYNAMIC)).tip("How quickly its spin dies away by itself");
        prop(NODE, "gravityScale", "Gravity Scale", Float.class, physics, n -> seen(n).gravityScale, (n, x, b) -> body(n).gravityScale = f(x))
                .omitWhen(1f).range(-10, 10).step(0.1).when(n -> bodyIs(n, BodyKind.DYNAMIC) || bodyIs(n, BodyKind.CHARACTER))
                .tip("How much gravity pulls it: 0 floats, 1 falls as everything else does");
        prop(NODE, "canSleep", "Can Sleep", Boolean.class, physics, n -> seen(n).canSleep, (n, x, b) -> body(n).canSleep = (Boolean) x)
                .omitWhen(true).when(n -> bodyIs(n, BodyKind.DYNAMIC)).tip("Whether it may stop being simulated once it comes to rest, until something touches it");
        prop(NODE, "trigger", "Trigger", Boolean.class, physics, n -> seen(n).trigger, (n, x, b) -> body(n).trigger = (Boolean) x)
                .omitWhen(false).when(n -> seen(n).rigid()).tip("Lets things pass through it and tells the game when they come in and go out");
        prop(NODE, "layer", "Layer", Integer.class, physics, n -> seen(n).layer, (n, x, b) -> body(n).layer = ((Number) x).intValue())
                .omitWhen(1).when(n -> !bodyIs(n, BodyKind.NONE)).tip("The collision layers it is on, as bits: 1, 2, 4, 8 and so on, added together");
        prop(NODE, "mask", "Collides With", Integer.class, physics, n -> seen(n).mask, (n, x, b) -> body(n).mask = ((Number) x).intValue())
                .omitWhen(-1).when(n -> !bodyIs(n, BodyKind.NONE)).tip("The layers it collides with, as bits added together. -1 is every layer");
        prop(NODE, "characterRadius", "Radius", Float.class, character, n -> seen(n).characterRadius, (n, x, b) -> body(n).characterRadius = f(x))
                .omitWhen(0.3f).range(0.01, 10).step(0.05).when(n -> bodyIs(n, BodyKind.CHARACTER)).tip("How wide the character is, from its middle");
        prop(NODE, "characterHeight", "Height", Float.class, character, n -> seen(n).characterHeight, (n, x, b) -> body(n).characterHeight = f(x))
                .omitWhen(1.8f).range(0.02, 20).step(0.05).when(n -> bodyIs(n, BodyKind.CHARACTER))
                .tip("How tall the character is, from its feet at the node's position");
        prop(NODE, "characterMass", "Mass", Float.class, character, n -> seen(n).characterMass, (n, x, b) -> body(n).characterMass = f(x))
                .omitWhen(80f).range(0, 100_000).step(1).when(n -> bodyIs(n, BodyKind.CHARACTER))
                .tip("How heavy the character is, in kilograms: how hard it pushes crates and how much it tips what it stands on");
        prop(NODE, "slopeLimit", "Slope Limit", Float.class, character, n -> seen(n).slopeLimit, (n, x, b) -> body(n).slopeLimit = f(x))
                .omitWhen(45f).range(0, 89).step(1).when(n -> bodyIs(n, BodyKind.CHARACTER)).tip("The steepest slope it can walk up, in degrees");
        prop(NODE, "stepHeight", "Step Height", Float.class, character, n -> seen(n).stepHeight, (n, x, b) -> body(n).stepHeight = f(x))
                .omitWhen(0.3f).range(0, 5).step(0.05).when(n -> bodyIs(n, BodyKind.CHARACTER)).tip("The highest step it walks up without jumping, in metres");

        prop(WORLD, "gravity", "Gravity", Vec3.class, physics, s -> ScenePhysics.world(s).gravity, (s, x, b) -> ScenePhysics.world(s).gravity = v(x))
                .omitWhen(ScenePhysics.DEFAULT_GRAVITY).step(0.1)
                .tip("Which way things fall and how quickly they speed up, in metres per second each second. Earth's is 9.81 down");
        prop(WORLD, "velocityIterations", "Solver Passes", Integer.class, physics, s -> ScenePhysics.world(s).velocityIterations,
                (s, x, b) -> ScenePhysics.world(s).velocityIterations = ((Number) x).intValue()).omitWhen(10).range(1, 100)
                .tip("How many passes the solver makes each step: more holds tall stacks and long chains better, and costs more");
        prop(WORLD, "substeps", "Substeps", Integer.class, physics, s -> ScenePhysics.world(s).substeps,
                (s, x, b) -> ScenePhysics.world(s).substeps = ((Number) x).intValue()).omitWhen(1).range(1, 32)
                .tip("Cuts each step into this many smaller ones. 4 holds a tall stack or a heavy load on a light chain; it costs about 4 times as much");

        for (JointKind kind : JointKind.values()) {
            List<SceneProperty<SceneJoint>> list = new ArrayList<>();
            JOINTS.put(kind, list);
            String joint = "Joint", where = "Where", limits = "Limits", motor = "Motor", link = "Link", strength = "Strength";
            boolean hinge = kind == JointKind.HINGE, slider = kind == JointKind.SLIDER;
            prop(list, "name", "Name", String.class, joint, SceneJoint::getName, (j, x, b) -> j.setName(s(x))).tip("What the game finds it by");
            prop(list, "bodyA", "Body", String.class, joint, SceneJoint::getBodyA, (j, x, b) -> j.setBodyA(s(x)))
                    .tip("The name of the node whose body it holds");
            prop(list, "bodyB", "Other Body", String.class, joint, SceneJoint::getBodyB, (j, x, b) -> j.setBodyB(s(x))).omitWhen("")
                    .tip("The name of the node whose body it joins the first to. Empty holds the first to the world");
            if (kind == JointKind.DISTANCE) {
                prop(list, "anchor", "Point", Vec3.class, where, j -> j.anchor, (j, x, b) -> j.anchor = v(x)).step(0.1)
                        .tip("Where it is fixed to the first body, in the world");
                prop(list, "anchorB", "Other Point", Vec3.class, where, j -> j.anchorB, (j, x, b) -> j.anchorB = v(x)).step(0.1)
                        .tip("Where it is fixed to the other body, or to the world, in the world");
                prop(list, "length", "Length", Float.class, link, j -> j.length, (j, x, b) -> j.length = f(x)).omitWhen(0f).range(0, 1000).step(0.1)
                        .tip("How far apart it keeps the points, in metres. 0 keeps them as far apart as they start");
                prop(list, "rope", "Rope", Boolean.class, link, j -> j.rope, (j, x, b) -> j.rope = (Boolean) x).omitWhen(false)
                        .tip("Lets the points come closer, like a rope going slack, but never farther apart");
                prop(list, "springFrequency", "Spring", Float.class, link, j -> j.springFrequency, (j, x, b) -> j.springFrequency = f(x))
                        .omitWhen(0f).range(0, 30).step(0.1).tip("Makes it springy: how many times a second it bounces. 0 is rigid");
                prop(list, "springDamping", "Spring Damping", Float.class, link, j -> j.springDamping, (j, x, b) -> j.springDamping = f(x))
                        .omitWhen(0.5f).range(0, 2).step(0.05).when(j -> j.springFrequency > 0f)
                        .tip("How quickly the bouncing dies away: 1 settles without overshooting");
            } else if (kind != JointKind.FIXED) {
                prop(list, "anchor", "Point", Vec3.class, where, j -> j.anchor, (j, x, b) -> j.anchor = v(x)).step(0.1)
                        .tip(hinge ? "The point it turns around, in the world" : slider ? "The point its slide is measured from, in the world"
                                : "The point it is pinned at, in the world");
            }
            if (kind == JointKind.BALL) {
                prop(list, "axis", "Axis", Vec3.class, where, j -> j.axis, (j, x, b) -> j.axis = v(x)).omitWhen(Vec3.UP).step(0.1)
                        .tip("The direction of the limb that hangs from the joint, in the world: what the cone and the twist are measured around");
                prop(list, "swingLimited", "Limit Swing", Boolean.class, limits, j -> j.swingLimited, (j, x, b) -> j.swingLimited = (Boolean) x)
                        .omitWhen(false).tip("Keeps the limb inside a cone around its axis, as a shoulder or a hip does");
                prop(list, "swing", "Swing", Float.class, limits, j -> j.swing, (j, x, b) -> j.swing = f(x)).omitWhen(45f)
                        .range(0, 180).step(1).when(j -> j.swingLimited).tip("How far the limb leans from its axis, in degrees");
                prop(list, "limited", "Limit Twist", Boolean.class, limits, j -> j.limited, (j, x, b) -> j.limited = (Boolean) x).omitWhen(false)
                        .tip("Stops the limb turning around its own length past two angles");
                prop(list, "lower", "Twist Lower", Float.class, limits, j -> j.lower, (j, x, b) -> j.lower = f(x)).omitWhen(-45f)
                        .range(-180, 180).step(1).when(j -> j.limited).tip("The furthest it twists one way, in degrees from where it starts");
                prop(list, "upper", "Twist Upper", Float.class, limits, j -> j.upper, (j, x, b) -> j.upper = f(x)).omitWhen(45f)
                        .range(-180, 180).step(1).when(j -> j.limited).tip("The furthest it twists the other way, in degrees from where it starts");
            }
            if (hinge || slider) {
                String unit = hinge ? "degrees" : "metres", speed = hinge ? "degrees a second" : "metres a second";
                prop(list, "axis", "Axis", Vec3.class, where, j -> j.axis, (j, x, b) -> j.axis = v(x)).omitWhen(Vec3.UP).step(0.1)
                        .tip(hinge ? "The line it turns around, in the world: (0, 1, 0) for a door" : "The line it slides along, in the world");
                prop(list, "limited", "Limited", Boolean.class, limits, j -> j.limited, (j, x, b) -> j.limited = (Boolean) x).omitWhen(false)
                        .tip(hinge ? "Stops it turning past two angles, as a door stops at the wall" : "Stops it sliding past two distances");
                prop(list, "lower", "Lower", Float.class, limits, j -> j.lower, (j, x, b) -> j.lower = f(x)).omitWhen(hinge ? -90f : -1f)
                        .range(hinge ? -180 : -1000, hinge ? 180 : 1000).step(hinge ? 1 : 0.1).when(j -> j.limited)
                        .tip("The lowest it goes, in " + unit + " from where it starts");
                prop(list, "upper", "Upper", Float.class, limits, j -> j.upper, (j, x, b) -> j.upper = f(x)).omitWhen(hinge ? 90f : 1f)
                        .range(hinge ? -180 : -1000, hinge ? 180 : 1000).step(hinge ? 1 : 0.1).when(j -> j.limited)
                        .tip("The highest it goes, in " + unit + " from where it starts");
                prop(list, "motor", "Motor", Boolean.class, motor, j -> j.motor, (j, x, b) -> j.motor = (Boolean) x).omitWhen(false)
                        .tip(hinge ? "Turns it at a set speed, as far as its strength allows" : "Slides it at a set speed, as far as its strength allows");
                prop(list, "motorSpeed", "Speed", Float.class, motor, j -> j.motorSpeed, (j, x, b) -> j.motorSpeed = f(x)).omitWhen(0f)
                        .step(hinge ? 5 : 0.1).when(j -> j.motor).tip("How fast, in " + speed + ". 0 holds it still, like a brake");
                prop(list, "motorStrength", "Strength", Float.class, motor, j -> j.motorStrength, (j, x, b) -> j.motorStrength = f(x))
                        .omitWhen(100f).range(0, 1_000_000).step(10).when(j -> j.motor)
                        .tip(hinge ? "The most torque it turns with, in newton metres" : "The most force it pushes with, in newtons. Lifting 100 kg takes more than 981");
            }
            prop(list, "collideConnected", "Collide Connected", Boolean.class, strength, j -> j.collideConnected,
                    (j, x, b) -> j.collideConnected = (Boolean) x).omitWhen(false).tip("Whether the two bodies it joins still collide with each other");
            prop(list, "breakForce", "Break Force", Float.class, strength, j -> j.breakForce, (j, x, b) -> j.breakForce = f(x))
                    .omitWhen(0f).range(0, 10_000_000).step(100).tip("How hard it can be pulled, in newtons, before it breaks. 0 never breaks");
        }
    }

    // --- Tooltips for what the declarations above don't explain ---------------------------------------------------------

    static {
        tips(NODE, "size", "How long each edge of the cube is",
                "radius", "The distance from the middle of the sphere to its surface",
                "castShadow", "Whether it blocks light, so that it throws a shadow",
                "receiveShadow", "Whether shadows from other nodes fall on it");
        tips(MATERIAL, "texture", "A picture wrapped over the surface, as a path in the project",
                "normalScale", "How strong the bumps of the normal map are",
                "emissiveTexture", "A picture of which parts of the surface glow, and in what color",
                "emissiveStrength", "How bright the glow is");
        for (Light.Type type : Light.Type.values()) {
            tips(light(type), "color", "The color of the light",
                    "intensity", "How bright it is. 1 is the usual brightness",
                    "position", "Where it is in the scene",
                    "falloff", "How its strength dies away with distance: Windowed Inverse Square is how real light does it, "
                            + "Linear fades evenly to nothing at its range, and None stays the same until its range");
        }
        tips(CAMERA, "position", "Where the camera is in the scene");
        tips(WORLD, "skyKind", "What is drawn behind everything: nothing, three colors blended, one panorama picture, or six pictures round a box",
                "skyTop", "The color of the sky straight up",
                "skyHorizon", "The color of the sky where it meets the ground",
                "skyBottom", "The color below the horizon",
                "skyRight", "The picture for the side of the box on the +X side",
                "skyLeft", "The picture for the side of the box on the -X side",
                "skyUp", "The picture for the top of the box",
                "skyDown", "The picture for the bottom of the box",
                "skyFront", "The picture for the side of the box on the +Z side",
                "skyBack", "The picture for the side of the box on the -Z side",
                "skyBrightness", "How bright the sky is drawn, and how much light it gives. 1 is as the picture is",
                "ambientColor", "The color of the ambient light");
    }

    /** Gives each property named, in pairs of name and tip, that tooltip, unless it has one. */
    private static <T> void tips(List<SceneProperty<T>> list, String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            for (SceneProperty<T> property : list) if (property.getName().equals(pairs[i])) property.defaultTip(pairs[i + 1]);
        }
    }
}
