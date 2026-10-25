package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.RenderTarget;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.physics.PhysicsWorld;
import me.piitex.engine.ui.color.Color;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A 3D scene to show in a {@link Viewport3D}: a tree of {@link Node3D}s, a {@link Camera}, {@link Light}s and a
 * background. It is a {@link Viewport3D.Content}, so showing it is
 * <pre>
 * Scene3D scene = new Scene3D();
 * scene.add(new Node3D("box", Mesh.cube(1f), new Material(Color.CORNFLOWER_BLUE)));
 * Viewport3D view = new Viewport3D(640, 360);
 * view.setContent(scene);
 * view.setInputListener(new OrbitControls(scene.camera()));
 * container.addElement(view);
 * </pre>
 * and models come from {@link Model#load}. Everything is drawn with one forward renderer: classic (Lambert and
 * Blinn-Phong) or physically based shading with normal, metallic-roughness, occlusion and emissive maps, up to
 * {@link #MAX_LIGHTS} lights at once, {@link Light#setCastShadow shadows} from a directional or spot light, {@link #setFog fog}, a {@link Skybox},
 * mipmapped and anisotropically filtered textures, {@link Instances instanced} drawing, {@link Skin skinned} meshes
 * moved by {@link AnimationPlayer}s, alpha and additive blending, {@link #setEnvironmentLighting light and reflections from the
 * skybox}, depth testing, back-face culling and frustum culling; the viewport anti-aliases the edges. {@link #pick} finds
 * what is under a point of the picture.
 * <p>
 * A new scene has a soft ambient light and one directional light, so the first thing you add is visible; clear
 * {@link #lights()} for different lighting.
 * <p>
 * The scene's data is plain Java and can be built on any thread, but change it while it is drawn only from the render
 * thread, such as in an {@link #setUpdateHandler update handler}. One scene can be shown by several viewports; it is
 * then drawn once per viewport per frame, and its update handler runs once per frame, before the first of them.
 */
public class Scene3D implements Viewport3D.Content {
    /**
     * The most lights every pixel tests in one frame. A scene with more keeps drawing: with {@link LightMode#AUTO} the point and
     * spot lights without shadows go into a grid in front of the camera (up to {@link #MAX_CLUSTERED_LIGHTS} of them);
     * with {@link LightMode#DIRECT} each frame the lights that matter most are chosen, directional ones first and then the
     * point and spot lights whose reach is in the camera's view, nearest first.
     * Lights switched off (intensity 0) take no room when the choice has to be made.
     */
    public static final int MAX_LIGHTS = 64;

    /**
     * The most lights whose shadows are drawn in one frame: the ones nearest the camera among the {@link #MAX_LIGHTS}
     * that are shaded.
     */
    public static final int MAX_SHADOW_LIGHTS = 8;

    /**
     * Runs once per frame before the scene is drawn.
     */
    @FunctionalInterface
    public interface UpdateHandler {
        /**
         * @param delta seconds since the previous frame
         */
        void update(Scene3D scene, float delta);
    }

    private final Node3D root = new Node3D("root");
    private final Camera camera = new Camera();
    private final List<Light> lights = new ArrayList<>();
    private Color background = new Color(0.10f, 0.11f, 0.14f, 1f);
    private Color ambient = new Color(0.45f, 0.45f, 0.5f, 1f);
    private float ambientIntensity = 1f;
    private UpdateHandler updateHandler;
    private PhysicsWorld physics;
    private Skybox skybox;
    private int shadowMapSize = 2048;
    private int shadowAtlasSize = 4096;
    private float shadowDistance = 60f;
    private float shadowBias = 1f;
    private float shadowSoftness = 1f;
    private float environmentLighting;
    private Stats stats = new Stats(0, 0, 0, 0, 0, 0, 0);
    private Color fogColor = Color.WHITE;
    private float fogDensity;
    private float fogHeightFalloff;
    private ToneMapping toneMapping = ToneMapping.NONE;
    private float exposure = 1f;
    private float bloom, bloomThreshold = 0.8f, bloomRadius = 1f;
    private float saturation = 1f, contrast = 1f;
    private Color shadowTint = Color.WHITE, highlightTint = Color.WHITE;
    private float chromaticAberration, vignette;
    private float ambientOcclusion, ambientOcclusionRadius = 0.5f;
    private final List<AnimationPlayer> animators = new ArrayList<>();
    private final List<LightAnimation> lightAnimations = new CopyOnWriteArrayList<>();

    private final Map<GpuDevice, Shared> renderers = new IdentityHashMap<>();
    private final List<Preload> preloads = new CopyOnWriteArrayList<>();
    private float preloadBudget = 4f;

    private static final class Shared {
        final SceneRenderer renderer;
        int users;
        long updatedFrame = -1;

        Shared(SceneRenderer renderer) {
            this.renderer = renderer;
        }
    }

    public Scene3D() {
        lights.add(Light.directional(new Vec3(-0.4f, -1f, -0.6f), Color.WHITE, 0.9f));
    }

    /**
     * The top of the node tree. Everything under it is drawn.
     */
    public Node3D root() {
        return root;
    }

    /**
     * Shorthand for {@code root().add(node)}.
     */
    public Scene3D add(Node3D node) {
        root.add(node);
        return this;
    }

    public Camera camera() {
        return camera;
    }

    /**
     * The scene's lights; add and remove from it directly.
     */
    public List<Light> lights() {
        return lights;
    }

    public Color getBackground() {
        return background;
    }

    public Scene3D setBackground(Color background) {
        this.background = background;
        return this;
    }

    public Color getAmbient() {
        return ambient;
    }

    public float getAmbientIntensity() {
        return ambientIntensity;
    }

    /**
     * The light that reaches every surface from every side, so nothing is pitch black. {@code intensity} 0 turns it off.
     */
    public Scene3D setAmbient(Color color, float intensity) {
        this.ambient = color;
        this.ambientIntensity = intensity;
        return this;
    }

    public Skybox getSkybox() {
        return skybox;
    }

    /**
     * The sky drawn behind everything, in place of the plain {@link #setBackground background colour}; {@code null}
     * (the default) draws just the colour.
     */
    public Scene3D setSkybox(Skybox skybox) {
        this.skybox = skybox;
        return this;
    }

    public int getShadowMapSize() {
        return shadowMapSize;
    }

    /**
     * The width and height in pixels of a shadow picture for each light that doesn't set its own size (see
     * {@link Shadows}): more is sharper and costs more memory and time. 2048 by default; from 256 to 8192. A point light
     * without a size of its own uses 1024 whatever this says.
     */
    public Scene3D setShadowMapSize(int size) {
        this.shadowMapSize = Math.max(256, Math.min(8192, size));
        return this;
    }

    public int getShadowAtlasSize() {
        return shadowAtlasSize;
    }

    /**
     * The largest width and height in pixels of the picture all shadows share (4096 by default; 1024 to 8192). The
     * lights' shadow pictures are packed into it; if they don't fit, the biggest are drawn smaller until they do. A single
     * picture bigger than this still gets the room it needs.
     */
    public Scene3D setShadowAtlasSize(int size) {
        this.shadowAtlasSize = Math.max(1024, Math.min(8192, size));
        return this;
    }

    public float getShadowDistance() {
        return shadowDistance;
    }

    /**
     * How far from the camera shadows are drawn, in scene units (60 by default, and never beyond the camera's far
     * plane). Shorter gives sharper shadows near the camera.
     */
    public Scene3D setShadowDistance(float distance) {
        this.shadowDistance = Math.max(0.1f, distance);
        return this;
    }

    public float getShadowSoftness() {
        return shadowSoftness;
    }

    /**
     * How wide the soft edge of a shadow is, as a multiple of the default (1). Larger blurs the edge, from 0.25 to 4;
     * more than about 2 starts to show the sampling pattern unless the shadow map is large.
     */
    public Scene3D setShadowSoftness(float softness) {
        this.shadowSoftness = Math.max(0.25f, Math.min(4f, softness));
        return this;
    }

    public float getEnvironmentLighting() {
        return environmentLighting;
    }

    /**
     * Lights the scene from its {@link Skybox}, the way the real sky would: surfaces are lit softly by the colour of the
     * sky in front of them, and shiny and metallic materials ({@link Material#setPbr physically based}) reflect it, blurred
     * by their roughness. {@code intensity} multiplies it; 0 (the default) turns it off, 1 is the sky as drawn. Where it
     * is on, it takes the place of the flat {@link #setAmbient ambient light}. It needs a skybox ({@link Skybox#cube},
     * {@link Skybox#equirect} or {@link Skybox#gradient}), has no effect without one, and does not make shadows: the
     * sky is a soft light from every direction, while {@link #lights()} still give the sun and lamps.
     * <p>
     * Skybox pictures are ordinary 8-bit images, so a bright sun in one doesn't light the scene more than white does.
     */
    public Scene3D setEnvironmentLighting(float intensity) {
        this.environmentLighting = Math.max(0f, intensity);
        return this;
    }

    /**
     * What the last render of this scene did: nodes drawn, nodes skipped because they were outside the view, instanced
     * copies drawn and GPU draw calls (shadow pass not counted). Useful for checking that culling and batching work.
     *
     * @param nodes     nodes drawn, an instanced node counting once
     * @param culled    nodes left out because they were wholly outside the camera's view and cast no visible shadow
     * @param instances records drawn: a plain node is one, an instanced node one per instance
     * @param drawCalls GPU draw calls in the main pass
     * @param shadowViews shadow maps drawn this frame: a cascade, a spot light or one side of a point light each count one
     * @param shadowAtlasWidth width in pixels of the shadow atlas they were drawn into, 0 when nothing cast a shadow
     * @param shadowAtlasHeight height in pixels of the shadow atlas
     * @param clusteredLights point and spot lights sorted into the grid this frame, 0 when the scene shades its lights directly
     * @param occluded  nodes (or instances) left out because an occluder hid them; counted in {@code culled} too, not in {@code nodes}
     * @param glowLights lights made this frame from glowing surfaces ({@link Material#setGlow})
     */
    public record Stats(int nodes, int culled, int instances, int drawCalls, int shadowViews, int shadowAtlasWidth, int shadowAtlasHeight,
                        int clusteredLights, int occluded, int glowLights) {
        public Stats(int nodes, int culled, int instances, int drawCalls, int shadowViews, int shadowAtlasWidth, int shadowAtlasHeight) {
            this(nodes, culled, instances, drawCalls, shadowViews, shadowAtlasWidth, shadowAtlasHeight, 0, 0, 0);
        }
    }

    /**
     * How a scene's point and spot lights are shaded.
     */
    public enum LightMode {
        /** Clustered once the scene has more than {@link #MAX_LIGHTS} lights, direct below that. */
        AUTO,
        /** Every pixel tests every light that was chosen, up to {@link #MAX_LIGHTS}: the cheapest way to shade a handful. */
        DIRECT,
        /**
         * Directional lights and lights that cast shadows are tested by every pixel, as in {@link #DIRECT}; every other point
         * and spot light goes into a grid in front of the camera, and a pixel tests only the lights listed for its cell, up
         * to {@link #MAX_CLUSTERED_LIGHTS} of them.
         */
        CLUSTERED
    }

    /**
     * The most point and spot lights without shadows one frame shades when they are {@link LightMode#CLUSTERED clustered}.
     * They come on top of the {@link #MAX_LIGHTS} that every pixel tests.
     */
    public static final int MAX_CLUSTERED_LIGHTS = LightClusters.MAX_LIGHTS;

    /**
     * The most lights made in one frame from glowing surfaces ({@link Material#setGlow}). Surfaces past it glow without
     * lighting their surroundings.
     */
    public static final int MAX_GLOW_LIGHTS = 1024;

    private LightMode lightMode = LightMode.AUTO;
    private boolean occlusionCulling;

    boolean hasGlow() {
        return Material.anyGlow();
    }

    public LightMode getLightMode() {
        return lightMode;
    }

    /**
     * Chooses between testing every light at every pixel and sorting the lights into a grid first ({@link LightMode}).
     * {@link LightMode#AUTO} by default. The picture is the same either way; only the cost, and how many lights fit, differ.
     */
    public Scene3D setLightMode(LightMode mode) {
        this.lightMode = mode == null ? LightMode.AUTO : mode;
        return this;
    }

    public boolean isOcclusionCulling() {
        return occlusionCulling;
    }

    /**
     * Leaves out what is hidden behind the nodes marked with {@link Node3D#setOccluder}, before it is sent to the GPU (off by
     * default). Worth turning on for a built-up scene seen from inside, such as a city street or the rooms of a building, where
     * most of what is in front of the camera's frustum is behind a wall. A hidden node still casts its shadow.
     */
    public Scene3D setOcclusionCulling(boolean enabled) {
        this.occlusionCulling = enabled;
        return this;
    }

    public Stats getStats() {
        return stats;
    }

    void setStats(Stats stats) {
        this.stats = stats;
    }

    public Color getFogColor() {
        return fogColor;
    }

    public float getFogDensity() {
        return fogDensity;
    }

    public float getFogHeightFalloff() {
        return fogHeightFalloff;
    }

    /**
     * Fades distant things into {@code color}. The strength is {@code 1 - exp(-(distance * density)^2)}: nothing close
     * up, thick far away. {@code 0.02} hazes things 40 units away; {@code 0} switches fog off (the default). Match the
     * colour to the {@link Skybox} at the horizon so the world melts into the sky. Fog applies to everything but
     * the skybox.
     */
    public Scene3D setFog(Color color, float density) {
        return setFog(color, density, 0f);
    }

    /**
     * Fog that is thicker low down, like mist over a meadow. {@code heightFalloff} is how fast it thins with height:
     * 0 is the same at every height, {@code 0.3} halves it about every 2.3 units up.
     */
    public Scene3D setFog(Color color, float density, float heightFalloff) {
        this.fogColor = color;
        this.fogDensity = Math.max(0f, density);
        this.fogHeightFalloff = Math.max(0f, heightFalloff);
        return this;
    }

    public ToneMapping getToneMapping() {
        return toneMapping;
    }

    /**
     * Bends colours brighter than white into the range the screen shows ({@link ToneMapping#NONE} by default, which cuts
     * them off). Applies to everything lit, the fog included; the skybox is drawn as it is.
     */
    public Scene3D setToneMapping(ToneMapping toneMapping) {
        this.toneMapping = toneMapping == null ? ToneMapping.NONE : toneMapping;
        return this;
    }

    public float getExposure() {
        return exposure;
    }

    /**
     * Multiplies the light before tone mapping (1 by default): 2 is one stop brighter, 0.5 one darker. Works without a
     * tone mapper too, as a plain brightness.
     */
    public Scene3D setExposure(float exposure) {
        this.exposure = Math.max(0f, exposure);
        return this;
    }

    public float getBloom() {
        return bloom;
    }

    /**
     * How much light bleeds out of the brightest parts of the picture (0, off, by default): a glow round a lamp, a glint on
     * metal, the sun in a window. Try 0.3 to 1. Everything brighter than the {@link #setBloomThreshold threshold} glows,
     * judged in linear light after exposure and before tone mapping, so a lamp eight times brighter than white glows
     * more than one at white.
     * <p>
     * Bloom draws the scene in 16-bit floating point into a target of its own (multisampled like the viewport, so edges
     * stay smooth) and then through a few full-screen passes, which costs a few percent of the frame and a few textures.
     * The tone mapper and gamma are applied once, at the end, to the picture and its glow together; that includes
     * gradient and cube skies, which are tone mapped under bloom like everything else.
     */
    public Scene3D setBloom(float strength) {
        this.bloom = Math.max(0f, strength);
        return this;
    }

    public float getBloomThreshold() {
        return bloomThreshold;
    }

    /**
     * How bright, as a share of white in linear light before tone mapping, a pixel has to be before it glows (0.8 by
     * default; 1 is white, and only things brighter than that glow).
     */
    public Scene3D setBloomThreshold(float threshold) {
        this.bloomThreshold = Math.max(0f, threshold);
        return this;
    }

    public float getBloomRadius() {
        return bloomRadius;
    }

    /**
     * How far the glow spreads, 1 by default; 2 is about twice as wide, 0.5 half. It is a share of the picture's height, so
     * the glow looks the same at any resolution or display density (1 is a few pixels' blur on a 1080 pixel high picture).
     * A wide glow of more than about 3 shows its taps as faint copies of the light.
     */
    public Scene3D setBloomRadius(float radius) {
        this.bloomRadius = Math.clamp(radius, 0.1f, 8f);
        return this;
    }

    public float getSaturation() {
        return saturation;
    }

    /**
     * How vivid the picture is (1 by default): 0 is black and white, 1.3 pushes neon harder, 0.6 washes it out. Part of the
     * look applied to the finished picture, with {@link #setContrast contrast} and the {@link #setShadowTint tints}; see
     * {@link #setBloom} for what turning any of them on costs.
     */
    public Scene3D setSaturation(float saturation) {
        this.saturation = Math.clamp(saturation, 0f, 4f);
        return this;
    }

    public float getContrast() {
        return contrast;
    }

    /**
     * Pushes the picture away from middle grey (1 by default): above 1 darkens shadows and brightens highlights, below 1
     * flattens them.
     */
    public Scene3D setContrast(float contrast) {
        this.contrast = Math.clamp(contrast, 0f, 4f);
        return this;
    }

    public Color getShadowTint() {
        return shadowTint;
    }

    /**
     * Multiplies the dark parts of the picture by a colour (white, no change, by default): a teal or blue here is the
     * cold shadows of a neon street. The tint fades into the {@link #setHighlightTint highlight tint} with brightness.
     * Only the colour's red, green and blue count.
     */
    public Scene3D setShadowTint(Color tint) {
        this.shadowTint = tint == null ? Color.WHITE : tint;
        return this;
    }

    public Color getHighlightTint() {
        return highlightTint;
    }

    /**
     * Multiplies the bright parts of the picture by a colour (white, no change, by default): a warm magenta or orange
     * here, against teal shadows, is the cyberpunk split.
     */
    public Scene3D setHighlightTint(Color tint) {
        this.highlightTint = tint == null ? Color.WHITE : tint;
        return this;
    }

    public float getChromaticAberration() {
        return chromaticAberration;
    }

    /**
     * Splits red and blue apart toward the edges of the picture, like a cheap lens (0, off, by default). The value is how
     * far, as a share of the picture's width, the colours are pulled apart at a corner: 0.003 is subtle, 0.01 is loud.
     * The centre is untouched.
     */
    public Scene3D setChromaticAberration(float amount) {
        this.chromaticAberration = Math.clamp(amount, 0f, 0.05f);
        return this;
    }

    public float getVignette() {
        return vignette;
    }

    /**
     * Darkens the corners of the picture (0, off, by default; 0.4 is noticeable, 1 is heavy).
     */
    public Scene3D setVignette(float strength) {
        this.vignette = Math.clamp(strength, 0f, 2f);
        return this;
    }

    public float getAmbientOcclusion() {
        return ambientOcclusion;
    }

    /**
     * Darkens creases, corners and the gaps under things, where light would not reach (0, off, by default; try 0.5 to 1).
     * It is screen-space: it looks at the depth of what the camera sees, so it needs no setup but also can't see what is
     * behind an object or off screen, and a thin object in front of a far wall can leave a faint halo.
     * <p>
     * It is applied to the finished picture, so it darkens direct light as well as ambient light, and a moderate strength
     * looks better than a high one. It costs a second pass over the scene's solid objects and a few full-screen passes; see
     * {@link #setBloom} for what the offscreen picture means for anti-aliasing. Transparent surfaces and lines are not part
     * of it.
     */
    public Scene3D setAmbientOcclusion(float strength) {
        this.ambientOcclusion = Math.clamp(strength, 0f, 4f);
        return this;
    }

    public float getAmbientOcclusionRadius() {
        return ambientOcclusionRadius;
    }

    /**
     * How far, in world units, occlusion reaches from a surface (0.5 by default). Match it to the size of the detail you
     * want grounded: 0.2 for the gaps between props, 1 for walls and furniture.
     */
    public Scene3D setAmbientOcclusionRadius(float radius) {
        this.ambientOcclusionRadius = Math.clamp(radius, 0.01f, 20f);
        return this;
    }

    /**
     * True when the scene needs the offscreen path (bloom, the look, lens effects or occlusion).
     */
    boolean usesPostProcessing() {
        return bloom > 0f || ambientOcclusion > 0f || chromaticAberration > 0f || vignette > 0f
                || saturation != 1f || contrast != 1f || !isWhite(shadowTint) || !isWhite(highlightTint);
    }

    private static boolean isWhite(Color c) {
        return c.getR() == 1f && c.getG() == 1f && c.getB() == 1f;
    }

    public float getShadowBias() {
        return shadowBias;
    }

    /**
     * Scales the offset that stops a surface from shadowing itself (1 by default). Raise it if a lit surface shows
     * stripes, lower it if shadows float away from what casts them.
     */
    public Scene3D setShadowBias(float bias) {
        this.shadowBias = Math.max(0f, bias);
        return this;
    }

    /**
     * Registers a player, which then advances once per frame, before the update handler.
     */
    public Scene3D animate(AnimationPlayer player) {
        if (!animators.contains(player)) animators.add(player);
        return this;
    }

    public Scene3D stopAnimating(AnimationPlayer player) {
        animators.remove(player);
        return this;
    }

    /**
     * Registers a blinking-light animation, which then advances once per frame, after the animation players and before
     * the update handler. While one is playing the scene asks its viewport for frames, so the lights keep blinking even
     * in a viewport that is not {@linkplain Viewport3D#setContinuous continuous}.
     */
    public Scene3D animate(LightAnimation animation) {
        if (!lightAnimations.contains(animation)) lightAnimations.add(animation);
        return this;
    }

    public Scene3D stopAnimating(LightAnimation animation) {
        lightAnimations.remove(animation);
        return this;
    }

    /**
     * The nearest thing the ray hits, or null. Only visible nodes with a mesh are tested, and a triangle that faces away
     * from the ray is skipped unless its material is double-sided, so a click goes through what you can't see. A node
     * is skipped when {@link Node3D#setPickable} is off.
     * <p>
     * Call it from the render thread or while the scene isn't drawing, as with any change to the scene. Each mesh keeps
     * a hierarchy of boxes (built the first time it is picked), so a ray tests only the triangles near its path. A
     * skinned mesh has no such hierarchy, because its shape changes with the pose: it is posed on the CPU and searched
     * afresh, which is fine for a click and not for thousands of rays per frame.
     */
    public Hit pick(Ray ray) {
        List<Hit> hits = pickAll(ray);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /**
     * What is under a point of the picture, using the camera: {@code pick(camera().screenRay(...))}, limited to what the
     * camera can see. Pass a viewport's mouse position and size, in points from its top-left corner:
     * <pre>
     * public void clicked(double x, double y) {
     *     Hit hit = scene.pick(x, y, view.getWidth(), view.getHeight());
     * }
     * </pre>
     */
    public Hit pick(double x, double y, double width, double height) {
        List<Hit> hits = new ArrayList<>();
        Picking.cast(root, Mat4.identity(), camera.screenRay(x, y, width, height), camera.getFar(), hits);
        hits.sort((a, b) -> Float.compare(a.distance(), b.distance()));
        return hits.isEmpty() ? null : hits.get(0);
    }

    /**
     * Everything the ray hits, one {@link Hit} per node (its nearest), nearest first. For picking through glass or
     * listing what is stacked under the mouse.
     */
    public List<Hit> pickAll(Ray ray) {
        List<Hit> hits = new ArrayList<>();
        Picking.cast(root, Mat4.identity(), ray, Float.POSITIVE_INFINITY, hits);
        hits.sort((a, b) -> Float.compare(a.distance(), b.distance()));
        return hits;
    }

    public PhysicsWorld getPhysics() {
        return physics;
    }

    /**
     * A physics world to run once per frame, after the animations (so an animated node can push bodies) and before the
     * update handler (so it sees where the bodies ended up). Null, the default, runs none.
     */
    public Scene3D setPhysics(PhysicsWorld physics) {
        this.physics = physics;
        return this;
    }

    public Scene3D setUpdateHandler(UpdateHandler updateHandler) {
        this.updateHandler = updateHandler;
        return this;
    }

    /**
     * Uploads the node's meshes and textures, and its descendants', to the GPU over the next frames instead of all in the
     * frame that first draws them. Add the model hidden ({@link Node3D#setVisible}) or leave it out of the scene, call
     * this, and show it when {@link Preload#isDone} or {@link Preload#onDone} says it is ready. Hidden nodes are
     * included. Call it on the render thread; for a model loaded on a worker, from the result callback.
     */
    public Preload preload(Node3D root) {
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        List<Object> items = new ArrayList<>();
        root.traverse(node -> {
            if (node.getMesh() != null && seen.put(node.getMesh(), true) == null) items.add(node.getMesh());
            Material m = node.getMaterial();
            if (m == null) return;
            for (TextureData t : new TextureData[]{m.getTexture(), m.getNormalTexture(), m.getMetallicRoughnessTexture(),
                    m.getOcclusionTexture(), m.getEmissiveTexture()}) {
                if (t != null && seen.put(t, true) == null) items.add(t);
            }
        });
        return queue(items);
    }

    /**
     * Uploads a skybox's pictures over the next frames, like {@link #preload(Node3D)}. A gradient sky has none.
     */
    public Preload preload(Skybox skybox) {
        return queue(skybox == null || skybox.kind == Skybox.Kind.GRADIENT ? List.of() : List.of(skybox));
    }

    private Preload queue(List<Object> items) {
        Preload preload = new Preload(items);
        if (!items.isEmpty()) preloads.add(preload);
        return preload;
    }

    /**
     * The most time in milliseconds a frame spends on {@link #preload uploads}, 4 by default. A frame always does at
     * least one item, so one very large mesh or texture still takes its whole time in one frame.
     */
    public float getPreloadBudget() {
        return preloadBudget;
    }

    public Scene3D setPreloadBudget(float milliseconds) {
        this.preloadBudget = Math.max(0f, milliseconds);
        return this;
    }

    @Override
    public boolean needsFrames() {
        if (!preloads.isEmpty()) return true;
        for (LightAnimation animation : lightAnimations) {
            if (animation.isPlaying()) return true;
        }
        return false;
    }

    private void runPreloads(GpuDevice device, Shared shared) {
        long deadline = System.nanoTime() + (long) (preloadBudget * 1_000_000f);
        boolean any = false;
        for (Preload preload : preloads) {
            int at = preload.next(device);
            while (at < preload.getTotal() && (!any || System.nanoTime() < deadline)) {
                shared.renderer.preload(preload.item(at), getEnvironmentLighting() > 0f);
                at++;
                any = true;
            }
            preload.advance(device, at);
            if (at < preload.getTotal()) break; // the budget is spent; later ones wait their turn
        }
        for (Preload preload : preloads) {
            if (preload.complete(renderers.keySet())) preloads.remove(preload);
        }
    }

    @Override
    public void init(GpuDevice device) {
        Shared shared = renderers.get(device);
        if (shared == null) {
            shared = new Shared(new SceneRenderer(device));
            renderers.put(device, shared);
        }
        shared.users++;
    }

    @Override
    public void render(GpuDevice device, RenderTarget target, float delta) {
        Shared shared = renderers.get(device);
        if (shared == null) throw new IllegalStateException("The scene was drawn before it was initialised for this device");
        // Several viewports can show the scene in one frame; only the first runs the update.
        long frame = device.frameNumber();
        if (shared.updatedFrame != frame) {
            if (!preloads.isEmpty()) runPreloads(device, shared);
            for (AnimationPlayer player : new ArrayList<>(animators)) player.update(delta);
            for (LightAnimation animation : lightAnimations) animation.update(delta);
            if (physics != null) physics.update(delta);
            if (updateHandler != null) updateHandler.update(this, delta);
        }
        shared.updatedFrame = frame;
        shared.renderer.render(this, target);
    }

    @Override
    public void dispose(GpuDevice device) {
        Shared shared = renderers.get(device);
        if (shared != null && --shared.users <= 0) {
            renderers.remove(device);
            shared.renderer.close();
        }
    }
}
