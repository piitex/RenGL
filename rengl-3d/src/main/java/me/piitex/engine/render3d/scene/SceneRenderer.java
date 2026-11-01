package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.*;
import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static me.piitex.engine.render3d.scene.SceneShaders.*;

/**
 * Draws a {@link Scene3D} with one {@link GpuDevice}: the shaders, the uploaded meshes and textures, and the buffers.
 * Render thread only.
 * <p>
 * Every node is one record in an <em>instance stream</em>, a vertex buffer read once per instance, holding its
 * transform and material parameters; a node with {@link Instances} contributes one record per instance. Draws that share
 * a mesh and material are sorted next to each other and become one instanced call. Nothing is bound per node but the
 * mesh and the material's textures, so the number of nodes is limited by the GPU's speed and not by descriptor sets.
 * <p>
 * Buffers that change every frame are kept per <em>render call within a frame</em>, so a scene shown by two viewports gets
 * two sets and the second viewport's data never overwrites what the first one's pass is about to read. Meshes and
 * textures are uploaded when first drawn and freed some time after the scene stops drawing them.
 */
final class SceneRenderer {
    private static final Logger log = LogManager.getLogger("Engine");

    private static final int FRAME_BLOCK_SIZE = 64 + 16 * 4 + 16 + 16 * Scene3D.MAX_LIGHTS * 6 + SHADOW_VIEW_BYTES * MAX_SHADOW_VIEWS + 32 + 16 * 13;
    private static final int SHADOW_BLOCK_SIZE = 64 + 16;
    private static final int INSTANCE_BYTES = INSTANCE_FLOATS * 4;
    /** A model matrix, which is what the previous-frame stream holds for each record. */
    private static final int PREV_BYTES = 64;
    private static final int JOINT_BLOCK_SIZE = Skin.MAX_JOINTS * 64;
    private static final int SKY_BLOCK_SIZE = 16 * 6;
    /**
     * Frames an uploaded mesh or texture may go undrawn before it is freed, and how often to look.
     */
    private static final int KEEP_FRAMES = 600;
    private static final int SWEEP_EVERY = 120;
    private static final Material DEFAULT_MATERIAL = new Material();

    private static final int KEY_CULL = 1, KEY_BLEND = 2, KEY_MIRRORED = 4, KEY_SKINNED = 8, KEY_SHADOW = 16, KEY_ADDITIVE = 32, KEY_ENV_SHIFT = 6;
    /** The mesh has a second set of texture coordinates, read from a vertex buffer of its own. */
    private static final int KEY_UV2 = 1 << 9;
    /** The mesh is line segments, not triangles. */
    private static final int KEY_LINES = 1 << 10;
    /** The program blends the mesh's blend shapes on the GPU, from a texture of their offsets. */
    private static final int KEY_MORPH = 1 << 11;
    /** The environment kind takes three bits, from {@link #KEY_ENV_SHIFT}. */
    private static final int KEY_ENV_MASK = 7;
    /** The custom filters and falloffs a program holds ({@link #variants} index) sit above the environment bits and the UV2 and line bits. */
    private static final int KEY_VARIANT_SHIFT = 17;
    /** The program also shades the lights listed in the cluster grid for the pixel's cell. */
    private static final int KEY_CLUSTER = 1 << 12;
    /** The program shadows the lights marked as ray traced with a ray query through the scene's acceleration structure. */
    private static final int KEY_RAY = 1 << 13;
    /** The program reflects what a ray from a smooth surface hits, shaded where it landed, in place of the sky alone. */
    private static final int KEY_REFLECT = 1 << 14;
    /** The program lights the diffuse part of a surface with the indirect light picture in place of the flat or sky ambient light. */
    private static final int KEY_GI = 1 << 15;
    /** The program works out the indirect light a surface gets (into the picture {@link #KEY_GI} reads) in place of shading it. */
    private static final int KEY_GI_TRACE = 1 << 16;

    /**
     * The custom shadow filters and light falloffs one scene shader is built with; a custom piece's id in the shader is 3
     * plus its place here. Sorted, so the same set always makes the same program whatever order the lights are in.
     */
    private record Variant(List<CustomShadowFilter> filters, List<CustomLightFalloff> falloffs) {
        static final Variant NONE = new Variant(List.of(), List.of());
    }

    private static final class GpuMesh {
        final GpuBuffer vertices;
        final GpuBuffer indices;
        final GpuBuffer skin;
        GpuBuffer uv2;
        final int indexCount;
        long lastUsed;

        GpuMesh(GpuBuffer vertices, GpuBuffer indices, GpuBuffer skin, GpuBuffer uv2, int indexCount) {
            this.vertices = vertices;
            this.indices = indices;
            this.skin = skin;
            this.uv2 = uv2;
            this.indexCount = indexCount;
        }

        void close() {
            vertices.close();
            indices.close();
            if (skin != null) skin.close();
            if (uv2 != null) uv2.close();
        }
    }

    /**
     * A node's own copy of its mesh's vertices with its blend shapes mixed in, refilled when the weights change.
     */
    private static final class MorphedVertices {
        final Mesh mesh;
        final GpuBuffer buffer;
        float[] weights;
        long lastUsed;

        MorphedVertices(Mesh mesh, GpuBuffer buffer) {
            this.mesh = mesh;
            this.buffer = buffer;
        }
    }

    /**
     * A mesh's blend shapes as a texture the vertex shader reads, so a node's weights are the only thing that changes from
     * frame to frame: for each shape and vertex two texels, the position offset and the normal change, a shape's vertices
     * one after the other in rows {@link #MORPH_ROW} texels wide. Half precision, which is plenty for an offset.
     * {@code texture} is null for a mesh with too many shape-vertices for one (blended on the CPU instead).
     */
    private static final class GpuMorph {
        final List<Mesh.MorphTarget> targets;
        final GpuTexture texture;
        final boolean[] normals;
        final int vertexCount;
        long lastUsed;

        GpuMorph(GpuDevice device, Mesh mesh) {
            targets = mesh.morphTargets();
            vertexCount = mesh.vertexCount();
            normals = new boolean[targets.size()];
            long texels = 2L * vertexCount * targets.size();
            long rows = (texels + MORPH_ROW - 1) / MORPH_ROW;
            if (texels == 0 || rows > MORPH_MAX_ROWS) {
                texture = null;
                return;
            }
            int floats = (int) (rows * MORPH_ROW * 4);
            java.nio.FloatBuffer data = MemoryUtil.memAllocFloat(floats);
            try {
                MemoryUtil.memSet(data, 0);
                for (int t = 0; t < targets.size(); t++) {
                    float[] p = targets.get(t).positions(), n = targets.get(t).normals();
                    normals[t] = n != null;
                    for (int i = 0; i < vertexCount; i++) {
                        int at = (t * vertexCount + i) * 8;
                        data.put(at, p[i * 3]).put(at + 1, p[i * 3 + 1]).put(at + 2, p[i * 3 + 2]);
                        if (n != null) data.put(at + 4, n[i * 3]).put(at + 5, n[i * 3 + 1]).put(at + 6, n[i * 3 + 2]);
                    }
                }
                texture = device.createHdrTexture(MORPH_ROW, (int) rows, data, false);
            } finally {
                MemoryUtil.memFree(data);
            }
        }

        void close() {
            if (texture != null) texture.close();
        }
    }

    /** How many blend-shape draws were mixed on the GPU and how many vertex buffers were mixed on the CPU, for tests. */
    static final java.util.concurrent.atomic.AtomicInteger GPU_MORPHS = new java.util.concurrent.atomic.AtomicInteger(),
            CPU_MORPHS = new java.util.concurrent.atomic.AtomicInteger();

    /** Texels in a row of a morph texture, and the most rows one may have (with 2048 wide, 4 million texels: 64 MB). */
    private static final int MORPH_ROW = 2048, MORPH_MAX_ROWS = 2048;

    private static final class GpuImage {
        final GpuTexture texture;
        long lastUsed;
        /** The {@link TextureData#version} the GPU copy holds. */
        long version;

        GpuImage(GpuTexture texture) {
            this.texture = texture;
        }
    }

    /**
     * The buffers one render call fills.
     */
    private static final class Resources {
        final GpuBuffer frame, sky;
        final List<GpuBuffer> shadowViews = new ArrayList<>();
        GpuBuffer instances;
        long instanceCapacity;
        /** The previous frame's model matrix of every record in {@link #instances}, for the velocity pass. */
        GpuBuffer prevInstances;
        long prevCapacity;
        GpuBuffer velocityBlock, skyMotionBlock;
        final List<GpuBuffer> joints = new ArrayList<>();
        final List<GpuBuffer> morphBlocks = new ArrayList<>();
        final List<GpuBuffer> sortedIndices = new ArrayList<>();
        PostPass post;
        GpuBuffer depthView;
        ClusterTextures cluster;
        /** The scene's meshes placed for ray queries, rebuilt every frame, or null until a scene with ray traced shadows is drawn. */
        RayScene rayScene;
        /** Where a reflection ray finds what it hit: the scene's table of instances, and the reflection settings. */
        GpuBuffer rayBlock;

        Resources(GpuDevice device) {
            frame = device.createDynamicBuffer(BufferType.UNIFORM, FRAME_BLOCK_SIZE);
            sky = device.createDynamicBuffer(BufferType.UNIFORM, SKY_BLOCK_SIZE);
        }

        void close() {
            frame.close();
            for (GpuBuffer b : shadowViews) b.close();
            sky.close();
            if (instances != null) instances.close();
            if (prevInstances != null) prevInstances.close();
            if (velocityBlock != null) velocityBlock.close();
            if (skyMotionBlock != null) skyMotionBlock.close();
            for (GpuBuffer b : joints) b.close();
            for (GpuBuffer b : morphBlocks) b.close();
            for (GpuBuffer b : sortedIndices) if (b != null) b.close();
            if (post != null) post.close();
            if (depthView != null) depthView.close();
            if (cluster != null) cluster.close();
            if (rayScene != null) rayScene.close();
            if (rayBlock != null) rayBlock.close();
        }
    }

    /**
     * The three textures of one render call's light grid ({@link LightClusters}), grown when a frame needs more room.
     */
    private static final class ClusterTextures {
        GpuTexture lights, headers, indices;
        int lightRows, indexRows;

        void close() {
            if (lights != null) lights.close();
            if (headers != null) headers.close();
            if (indices != null) indices.close();
        }
    }

    /**
     * A node (or all of a node's instances) to draw, before it is given a place in the instance stream.
     */
    private static final class Draw {
        Node3D node;
        Mesh mesh;
        Material material;
        Mat4 world;
        boolean mirrored;
        float distance;
        boolean cast;
        boolean receive;
        boolean inView = true;
        float[] sphere; // world sphere {x, y, z, radius} around a shadow caster
        boolean unbounded; // a caster whose shape the sphere can't promise (skinned, morphed, or not culled): always drawn
        float[] morph; // blend-shape weights to draw with, or null
        int key;
        /**
         * The instances to write, in order, when only some of a node's are wanted: those in view first, then (for a shadow
         * caster) those out of view, which only the shadow pass draws. Null for all of them, in their own order.
         */
        int[] order;
        int visible; // how many of the first of order are in view
        boolean sortTriangles; // draw the mesh's triangles far to near, from the node's own index buffer
    }

    /**
     * One instanced call: {@code count} consecutive records starting at {@code first}.
     */
    private static final class Group {
        Mesh mesh;
        Material material;
        int key;
        boolean cast;
        float[] sphere; // as for Draw, for the casters in the group
        boolean unbounded;
        boolean inView = true; // false: only drawn into the shadow map
        GpuBuffer vertices; // a morphed node's own vertices, or null for the mesh's
        int first, count;
        int viewCount; // of the first records, how many are in view; the shadow pass draws all of count
        boolean isolated; // holds records out of view after those in view, so nothing may be merged in after it
        GpuBuffer indices; // a see-through node's triangles in this frame's order, or null for the mesh's own
        int joints = -1; // index into the render call's joint buffers, for a skinned group
        int morph = -1; // index into the render call's morph blocks, for a group blended on the GPU
        GpuTexture morphTexture;
    }

    private final GpuDevice device;
    private final VertexLayout meshLayout = VertexLayout.of(VertexFormat.FLOAT3, VertexFormat.FLOAT3, VertexFormat.FLOAT2);
    private final VertexLayout instanceLayout = VertexLayout.of(3, VertexLayout.Rate.INSTANCE,
            VertexFormat.FLOAT4, VertexFormat.FLOAT4, VertexFormat.FLOAT4, VertexFormat.FLOAT4,
            VertexFormat.FLOAT4, VertexFormat.FLOAT4, VertexFormat.FLOAT4, VertexFormat.FLOAT4, VertexFormat.FLOAT4);
    private final VertexLayout skinLayout = VertexLayout.of(14, VertexLayout.Rate.VERTEX, VertexFormat.FLOAT4, VertexFormat.FLOAT4);
    private final VertexLayout uv2Layout = VertexLayout.of(12, VertexLayout.Rate.VERTEX, VertexFormat.FLOAT2);
    private final Map<Integer, Pipeline> pipelines = new HashMap<>();
    private final List<Variant> variants = new ArrayList<>(List.of(Variant.NONE));
    private final java.util.Set<Integer> brokenVariants = new java.util.HashSet<>();
    private final Map<Skybox.Kind, Pipeline> skyPipelines = new HashMap<>();
    private final Map<Integer, Pipeline> velocityPipelines = new HashMap<>();
    private Pipeline skyMotionPipeline;
    /** The previous frame's model matrices of the records, in step with {@link #instanceScratch}; written only while a temporal upscaler is on. */
    private ByteBuffer prevScratch = MemoryUtil.memAlloc(PREV_BYTES * 256).order(ByteOrder.nativeOrder());
    /** The history of the render call being built, or null when it has no temporal upscaler. */
    private TemporalState writingTemporal;
    private final VertexLayout prevLayout = VertexLayout.of(12, VertexLayout.Rate.INSTANCE,
            VertexFormat.FLOAT4, VertexFormat.FLOAT4, VertexFormat.FLOAT4, VertexFormat.FLOAT4);
    private final Map<Mesh, GpuMesh> meshes = new HashMap<>();
    private final Map<TextureData, GpuImage> images = new IdentityHashMap<>();
    private final Map<Skybox, GpuImage> skies = new IdentityHashMap<>();
    private final Map<Skybox, float[]> skyLight = new IdentityHashMap<>();
    private final Map<Node3D, MorphedVertices> morphed = new IdentityHashMap<>();
    private final Map<Mesh, GpuMorph> morphTextures = new IdentityHashMap<>();
    private final Map<Resources, Integer> morphCounter = new IdentityHashMap<>();
    private final ByteBuffer morphBlock = MemoryUtil.memAlloc(MORPH_BLOCK_SIZE).order(ByteOrder.nativeOrder());
    private final Map<Node3D, SkinReach> skinReach = new java.util.WeakHashMap<>();
    private final Map<Mesh, float[]> centroids = new IdentityHashMap<>();
    private final Map<Resources, Integer> sortedCounter = new IdentityHashMap<>();
    private Vec3 eye = Vec3.ZERO;
    private final OcclusionBuffer occlusionBuffer = new OcclusionBuffer();
    /** The depth picture of this frame's occluders, or null when the scene has none or does not use occlusion culling. */
    private OcclusionBuffer occlusion;
    private ByteBuffer morphScratch = MemoryUtil.memAlloc(1024).order(ByteOrder.nativeOrder());
    private final List<Resources> resourcePool = new ArrayList<>();
    private final GpuTexture whiteTexture, flatNormalTexture;
    private final GpuBuffer skyTriangle;
    private ByteBuffer instanceScratch = MemoryUtil.memAlloc(INSTANCE_BYTES * 256).order(ByteOrder.nativeOrder());
    private final ByteBuffer scratch = MemoryUtil.memAlloc(Math.max(FRAME_BLOCK_SIZE, JOINT_BLOCK_SIZE)).order(ByteOrder.nativeOrder());
    private final ShadowAtlas atlas;
    private long atlasUsed;
    private long frame;
    private long lastDeviceFrame = -1;
    private int resourcesUsed;
    private boolean warnedLights, warnedShadowLights, warnedShadowShrink, warnedLocalLights, warnedCellFull;
    /** The light grid's shader did not compile, so every scene shades its lights directly. */
    private boolean clusterBroken;
    /** The meshes built for ray queries, made the first time a scene asks for ray traced shadows. */
    private RayWorld rayWorld;
    /** The ray traced shadow shader did not compile, so every scene draws shadow maps. */
    private boolean rayBroken;
    private boolean warnedRay;
    /** The reflection shader did not compile, so every scene reflects the sky alone. */
    private boolean reflectBroken;
    private boolean warnedReflect;
    /** The global illumination shaders did not compile, so every scene keeps its flat or sky ambient light. */
    private boolean giBroken;
    /** The base colour textures this frame's reflections can show, by place in the array the shader reads. */
    private final java.util.IdentityHashMap<GpuTexture, Integer> raySlots = new java.util.IdentityHashMap<>();
    private final List<GpuTexture> rayTextureList = new ArrayList<>();

    SceneRenderer(GpuDevice device) {
        this.device = device;
        atlas = new ShadowAtlas(device);
        whiteTexture = pixel(255, 255, 255, 255);
        flatNormalTexture = pixel(128, 128, 255, 255);
        ByteBuffer triangle = MemoryUtil.memAlloc(6 * 4).order(ByteOrder.nativeOrder());
        triangle.asFloatBuffer().put(new float[]{-1, -1, 3, -1, -1, 3});
        skyTriangle = device.createBuffer(BufferType.VERTEX, triangle);
        MemoryUtil.memFree(triangle);
        pipeline(0); // the common one, so a broken shader shows up at init instead of mid-frame
    }

    private GpuTexture pixel(int r, int g, int b, int a) {
        ByteBuffer px = MemoryUtil.memAlloc(4);
        px.put((byte) r).put((byte) g).put((byte) b).put((byte) a).flip();
        GpuTexture texture = device.createTexture(1, 1, px);
        MemoryUtil.memFree(px);
        return texture;
    }

    // ---- pipelines ---------------------------------------------------------------------------------------------

    private Pipeline pipeline(int key) {
        Pipeline existing = pipelines.get(key);
        if (existing != null) return existing;
        boolean skinned = (key & KEY_SKINNED) != 0, uv2 = (key & KEY_UV2) != 0, morph = (key & KEY_MORPH) != 0;
        // The vertex buffers after the mesh's own, at bindings 1 and up: the instance stream, then the skin, then the
        // second texture coordinates, whichever of those the geometry has.
        List<VertexLayout> extra = new ArrayList<>(List.of(instanceLayout));
        if (skinned) extra.add(skinLayout);
        PipelineDesc desc;
        if ((key & KEY_SHADOW) != 0) {
            desc = new PipelineDesc(shadowVertex(skinned, morph), SHADOW_FRAGMENT, meshLayout, true, false, false, false, extra);
        } else {
            boolean blend = (key & KEY_BLEND) != 0;
            if (uv2) extra.add(uv2Layout);
            // Blended surfaces test against the depth buffer but don't write to it, so a see-through pixel never hides
            // something drawn after it.
            desc = new PipelineDesc(vertex(skinned, uv2, morph), fragment((key >> KEY_ENV_SHIFT) & KEY_ENV_MASK, variants.get(key >> KEY_VARIANT_SHIFT).filters(),
                    variants.get(key >> KEY_VARIANT_SHIFT).falloffs(), (key & KEY_CLUSTER) != 0, (key & KEY_RAY) != 0, (key & KEY_REFLECT) != 0,
                    (key & KEY_GI_TRACE) != 0 ? GI_TRACE : (key & KEY_GI) != 0 ? GI_APPLY : GI_NONE), meshLayout, true, (key & KEY_CULL) != 0, blend,
                    (key & KEY_MIRRORED) != 0, extra, (key & KEY_ADDITIVE) != 0, !blend)
                    .withTopology((key & KEY_LINES) != 0 ? PipelineDesc.Topology.LINES : PipelineDesc.Topology.TRIANGLES);
        }
        Pipeline created = device.createPipeline(desc);
        pipelines.put(key, created);
        return created;
    }

    private Pipeline skyPipeline(Skybox.Kind kind) {
        return skyPipelines.computeIfAbsent(kind, k -> device.createPipeline(new PipelineDesc(SKY_VERTEX, switch (k) {
            case GRADIENT -> SKY_GRADIENT_FRAGMENT;
            case CUBE -> SKY_CUBE_FRAGMENT;
            case EQUIRECT -> SKY_EQUIRECT_FRAGMENT;
            case EQUIRECT_HDR -> SKY_EQUIRECT_HDR_FRAGMENT;
        }, VertexLayout.of(VertexFormat.FLOAT2), false, false, false)));
    }

    // ---- one render --------------------------------------------------------------------------------------------

    void render(Scene3D scene, RenderTarget target, float delta) {
        frame++;
        long deviceFrame = device.frameNumber();
        if (deviceFrame != lastDeviceFrame) {
            lastDeviceFrame = deviceFrame;
            resourcesUsed = 0;
        }
        while (resourcePool.size() <= resourcesUsed) resourcePool.add(new Resources(device));
        Resources res = resourcePool.get(resourcesUsed++);
        jointCounter.remove(res);
        morphCounter.remove(res);
        sortedCounter.remove(res);

        float aspect = (float) target.width() / Math.max(1, target.height());
        Camera camera = scene.camera();
        boolean postProcessed = scene.usesPostProcessing();
        // How big to draw, and with which upscaler: decided first, since a temporal upscaler moves the camera.
        PostPass.Frame upFrame = null;
        if (postProcessed) {
            if (res.post == null) res.post = new PostPass(device, skyTriangle);
            upFrame = res.post.beginFrame(scene, target.width(), target.height());
        }
        boolean temporal = upFrame != null && upFrame.temporal();
        writingTemporal = temporal ? res.post.temporal() : null;
        Mat4 view = camera.view();
        // Culling, shadows and the light grid use the camera as it is; only the picture is drawn with the jitter.
        Mat4 viewProj = camera.projection(aspect).mul(view);
        Mat4 drawViewProj = temporal
                ? camera.projection(aspect, 2f * upFrame.jitterX() / upFrame.renderWidth(), -2f * upFrame.jitterY() / upFrame.renderHeight()).mul(view)
                : viewProj;
        // Textures are sampled as sharply as the picture will be shown, not as the smaller one is drawn.
        float lodBias = 0f;
        if (upFrame != null && upFrame.upscaler() != null) {
            lodBias = (float) (Math.log((double) upFrame.renderWidth() / upFrame.displayWidth()) / Math.log(2)) - (temporal ? 1f : 0f);
        }

        List<Draw> opaque = new ArrayList<>();
        List<Draw> blended = new ArrayList<>();
        float[] planes = frustumPlanes(viewProj);
        int[] counts = {0, 0, 0};
        eye = camera.getPosition();
        occlusion = null;
        if (scene.isOcclusionCulling()) {
            occlusionBuffer.begin(viewProj.toArray());
            drawOccluders(scene.root(), Mat4.identity());
            if (!occlusionBuffer.isEmpty()) occlusion = occlusionBuffer;
        }
        collect(scene.root(), Mat4.identity(), camera.getPosition(), planes, counts, opaque, blended);
        opaque.sort(Comparator.<Draw>comparingInt(d -> d.key).thenComparingInt(d -> System.identityHashCode(d.material))
                .thenComparingInt(d -> System.identityHashCode(d.mesh)));
        blended.sort((a, b) -> Float.compare(b.distance, a.distance));

        int[] cursor = {0};
        instanceScratch.clear();
        prevScratch.clear();
        List<Group> opaqueGroups = build(opaque, res, cursor);
        List<Group> blendedGroups = build(blended, res, cursor);
        uploadInstances(res, cursor[0]);

        Skybox sky = scene.getSkybox();
        int env = environmentKind(scene, sky);
        // Glowing surfaces make lights of their own for this frame, after the scene's.
        List<Light> sceneLights = scene.lights();
        int glowCount = 0;
        List<Light> allLights = sceneLights;
        if (scene.hasGlow()) {
            allLights = new ArrayList<>(sceneLights);
            glowCount = GlowLights.collect(scene.root(), Mat4.identity(), allLights, sceneLights.size() + Scene3D.MAX_GLOW_LIGHTS);
        }
        LightBudget.Split split = LightBudget.split(clusterBroken ? Scene3D.LightMode.DIRECT : scene.getLightMode(), allLights, camera.getPosition(), planes);
        LightClusters clusters = null;
        if (!split.local().isEmpty()) {
            Vec3 forward = camera.getTarget().sub(camera.getPosition()).normalized();
            clusters = LightClusters.build(split.local(), viewProj.toArray(), camera.getPosition(), forward, camera.getNear(), camera.getFar());
            if (!clusterShaderWorks(env)) {
                clusters = null;
                split = LightBudget.split(Scene3D.LightMode.DIRECT, allLights, camera.getPosition(), planes);
            } else if (clusters.dropped > 0 && !warnedCellFull) {
                warnedCellFull = true;
                log.warn("A cell of the light grid holds at most {} lights, and this scene crowds more than that into one; the others add no light there.", LightClusters.MAX_PER_CELL);
            }
        }
        List<Light> lights = split.direct();
        if (split.directCut() && !warnedLights) {
            warnedLights = true;
            log.warn("A Scene3D shades at most {} lights at once; the ones farthest from the camera are left out of the frame.", Scene3D.MAX_LIGHTS);
        }
        if (split.localCut() && !warnedLocalLights) {
            warnedLocalLights = true;
            log.warn("A Scene3D sorts at most {} lights without shadows into the light grid; the ones farthest from the camera are left out of the frame.", LightClusters.MAX_LIGHTS);
        }
        RayFrame rays = prepareRays(scene, res, lights, env, clusters != null, postProcessed);
        boolean rayShadows = rays.shadows();
        ShadowFrame shadowFrame = rayShadows ? ShadowFrame.NONE : planShadows(scene, lights, camera, aspect, planes, opaque);
        if (!shadowFrame.plans().isEmpty()) shadowPass(res, opaqueGroups, shadowFrame);
        int variant = variantFor(lights, split.local(), shadowFrame, env, clusters != null);
        writeFrameBlock(res.frame, scene, lights, camera, drawViewProj, shadowFrame, env, variants.get(variant), postProcessed, clusters, lodBias, rayShadows);
        if (clusters != null) uploadClusters(res, clusters, split.local(), variants.get(variant));
        int fragmentBits = (env << KEY_ENV_SHIFT) | (variant << KEY_VARIANT_SHIFT) | (clusters != null ? KEY_CLUSTER : 0) | (rayShadows ? KEY_RAY : 0) | (rays.reflections() ? KEY_REFLECT : 0);
        if (sky != null) writeSky(res.sky, scene, camera, aspect, sky, postProcessed);

        Color bg = scene.getBackground();
        int drawCalls = 0, drawnRecords = 0;
        // With bloom, occlusion or the look the scene goes into a target of its own first, in linear light, and the
        // viewport's target gets it tone mapped, with the effects.
        RenderTarget drawTarget = target;
        boolean hasDepth = false;
        if (postProcessed) {
            // A temporal upscaler anti-aliases by itself, from the jitter.
            drawTarget = res.post.sceneTarget(upFrame, temporal ? 1 : target.samples());
            if (scene.getAmbientOcclusion() > 0f || rays.gi()) {
                depthPass(res, opaqueGroups, viewProj, camera);
                hasDepth = true;
            }
        }
        if (rays.reflections() || rays.gi()) writeRayBlock(res, scene, temporal);
        // The light that bounces: traced and filtered before the scene is drawn, which then reads it as its diffuse ambient light.
        GpuTexture indirect = null;
        if (rays.gi()) {
            int traceBits = (env << KEY_ENV_SHIFT) | (variant << KEY_VARIANT_SHIFT) | (rayShadows ? KEY_RAY : 0) | KEY_GI_TRACE;
            indirect = indirectLight(res, scene, camera, opaqueGroups, traceBits, env, sky, aspect, target.isBottomLeftOrigin());
        } else if (postProcessed) {
            res.post.dropGiHistory();
        }
        try (RenderPass pass = device.beginPass(drawTarget, bg.getR(), bg.getG(), bg.getB(), 1f)) {
            if (sky != null) drawSky(pass, res, sky);
            pass.bindTexture(7, shadowFrame.plans().isEmpty() ? whiteTexture : atlas.texture());
            if (rayShadows || rays.reflections()) pass.bindAccelerationStructure(RAY_BINDING, res.rayScene);
            if (rays.reflections()) {
                pass.bindUniformBuffer(RAY_BLOCK_BINDING, res.rayBlock);
                pass.bindTextures(RAY_TEXTURES_BINDING, rayTextureArray());
            }
            if (indirect != null) pass.bindTexture(GI_BINDING, indirect);
            if (env >= ENV_CUBE) pass.bindTexture(8, gpuSky(sky));
            if (clusters != null) {
                pass.bindTexture(11, res.cluster.lights);
                pass.bindTexture(12, res.cluster.headers);
                pass.bindTexture(13, res.cluster.indices);
            }
            int[] state = {-1, 0};
            Material[] lastMaterial = {null};
            for (List<Group> list : List.of(opaqueGroups, blendedGroups)) {
                for (Group group : list) {
                    if (!group.inView || group.viewCount == 0) continue;
                    // What is see-through is lit by the sky as ever: the picture holds the surface behind it.
                    drawGroup(pass, res, group, state, lastMaterial, false, indirect != null && list == opaqueGroups ? fragmentBits | KEY_GI : fragmentBits, null);
                    drawCalls++;
                    drawnRecords += group.viewCount;
                }
            }
        }
        if (temporal) velocityPass(res, opaqueGroups, drawViewProj, viewProj, camera, upFrame);
        if (postProcessed) {
            boolean traced = res.post.apply(target, PostPass.Settings.of(scene, aspect), hasDepth, upFrame, temporal, camera, delta,
                    rays.occlusion() ? res.rayScene : null, temporal ? (int) (frame & 7) : 0);
            scene.setUsingRayTracedAmbientOcclusion(traced);
        } else {
            scene.setUsingRayTracedAmbientOcclusion(false);
        }
        scene.setUsingRayTracedGlobalIllumination(indirect != null);
        if (temporal) res.post.temporal().endFrame(viewProj.toArray());
        writingTemporal = null;
        scene.setStats(new Scene3D.Stats(counts[0], counts[1], drawnRecords, drawCalls,
                shadowFrame.viewCount(), shadowFrame.atlasWidth(), shadowFrame.atlasHeight(), clusters == null ? 0 : split.local().size(), counts[2], glowCount));
        if (frame % SWEEP_EVERY == 0) sweep();
    }

    /**
     * What lights the scene from the sky this frame: {@code ENV_NONE} when the scene doesn't ask for it or has no sky.
     */
    private static int environmentKind(Scene3D scene, Skybox sky) {
        if (sky == null || scene.getEnvironmentLighting() <= 0f) return ENV_NONE;
        return switch (sky.kind) {
            case GRADIENT -> ENV_GRADIENT;
            case CUBE -> ENV_CUBE;
            case EQUIRECT -> ENV_EQUIRECT;
            case EQUIRECT_HDR -> ENV_EQUIRECT_HDR;
        };
    }

    private void drawSky(RenderPass pass, Resources res, Skybox sky) {
        pass.bindPipeline(skyPipeline(sky.kind));
        pass.bindUniformBuffer(0, res.sky);
        if (sky.kind != Skybox.Kind.GRADIENT) pass.bindTexture(1, gpuSky(sky));
        pass.bindVertexBuffer(0, skyTriangle);
        pass.draw(3);
    }

    /**
     * @param state        [0] the pipeline key in use, -1 for none
     */
    private void drawGroup(RenderPass pass, Resources res, Group group, int[] state, Material[] lastMaterial, boolean shadowPass, int fragmentBits, GpuBuffer shadowView) {
        drawGroup(pass, res, group, state, lastMaterial, shadowPass, fragmentBits, shadowView, false);
    }

    /**
     * @param viewOnly with {@code shadowPass}, draw only the records that are in view, not all of them: for a picture from the camera
     */
    private void drawGroup(RenderPass pass, Resources res, Group group, int[] state, Material[] lastMaterial, boolean shadowPass, int fragmentBits, GpuBuffer shadowView, boolean viewOnly) {
        int key = shadowPass ? (group.key & (KEY_SKINNED | KEY_MORPH)) | KEY_SHADOW : group.key | fragmentBits;
        GpuMesh mesh = gpuMesh(group.mesh);
        if (key != state[0]) {
            pass.bindPipeline(pipeline(key));
            pass.bindUniformBuffer(0, shadowPass ? shadowView : res.frame);
            state[0] = key;
            lastMaterial[0] = null;
        }
        if (group.joints >= 0) pass.bindUniformBuffer(1, res.joints.get(group.joints));
        if (group.morph >= 0) {
            pass.bindUniformBuffer(10, res.morphBlocks.get(group.morph));
            pass.bindTexture(9, group.morphTexture);
        }
        Material material = group.material;
        if (material != lastMaterial[0]) {
            pass.bindTexture(2, material.getTexture() == null ? whiteTexture : gpuImage(material.getTexture()));
            if (!shadowPass) {
                pass.bindTexture(3, material.getNormalTexture() == null ? flatNormalTexture : gpuImage(material.getNormalTexture()));
                pass.bindTexture(4, material.getMetallicRoughnessTexture() == null ? whiteTexture : gpuImage(material.getMetallicRoughnessTexture()));
                pass.bindTexture(5, material.getOcclusionTexture() == null ? whiteTexture : gpuImage(material.getOcclusionTexture()));
                pass.bindTexture(6, material.getEmissiveTexture() == null ? whiteTexture : gpuImage(material.getEmissiveTexture()));
            }
            lastMaterial[0] = material;
        }
        pass.bindVertexBuffer(0, group.vertices != null ? group.vertices : mesh.vertices);
        pass.bindVertexBuffer(1, res.instances);
        int next = 2;
        if (mesh.skin != null && (key & KEY_SKINNED) != 0) pass.bindVertexBuffer(next++, mesh.skin);
        if (mesh.uv2 != null && (key & KEY_UV2) != 0 && !shadowPass) pass.bindVertexBuffer(next, mesh.uv2);
        pass.bindIndexBuffer(group.indices != null ? group.indices : mesh.indices);
        pass.drawIndexed(mesh.indexCount, shadowPass && !viewOnly ? group.count : group.viewCount, group.first);
    }

    // ---- collecting and building the instance stream -----------------------------------------------------------

    /** The most triangles drawn into the occlusion picture in a frame; occluders past it hide nothing. */
    private static final int MAX_OCCLUDER_TRIANGLES = 50_000;

    /**
     * Draws the triangles of the nodes marked {@link Node3D#setOccluder} into the occlusion buffer.
     */
    private void drawOccluders(Node3D node, Mat4 parentWorld) {
        if (!node.isVisible() || occlusionBuffer.triangles >= MAX_OCCLUDER_TRIANGLES) return;
        Mat4 world = parentWorld.mul(node.localMatrix());
        Mesh mesh = node.getMesh();
        if (node.isOccluder() && mesh != null && !mesh.isLines() && node.getSkin() == null && node.activeMorph() == null) {
            Material material = node.getMaterial() != null ? node.getMaterial() : DEFAULT_MATERIAL;
            if (!material.isBlend() && material.getAlphaCutoff() <= 0f) {
                Instances instances = node.getInstances();
                if (instances == null) {
                    drawOccluder(mesh, world.toArray());
                } else {
                    float[] data = instances.matrixData();
                    float[] w = world.toArray();
                    float[] model = new float[16];
                    for (int i = 0; i < instances.size() && occlusionBuffer.triangles < MAX_OCCLUDER_TRIANGLES; i++) {
                        multiply(w, data, i * 16, model);
                        drawOccluder(mesh, model);
                    }
                }
            }
        }
        for (Node3D child : node.getChildren()) drawOccluders(child, world);
    }

    private void drawOccluder(Mesh mesh, float[] w) {
        float[] v = mesh.vertices();
        int[] idx = mesh.indices();
        int count = mesh.vertexCount();
        double[] p = new double[count * 3];
        for (int i = 0; i < count; i++) {
            double x = v[i * Mesh.FLOATS_PER_VERTEX], y = v[i * Mesh.FLOATS_PER_VERTEX + 1], z = v[i * Mesh.FLOATS_PER_VERTEX + 2];
            p[i * 3] = w[0] * x + w[4] * y + w[8] * z + w[12];
            p[i * 3 + 1] = w[1] * x + w[5] * y + w[9] * z + w[13];
            p[i * 3 + 2] = w[2] * x + w[6] * y + w[10] * z + w[14];
        }
        for (int t = 0; t + 2 < idx.length && occlusionBuffer.triangles < MAX_OCCLUDER_TRIANGLES; t += 3) {
            int a = idx[t] * 3, b = idx[t + 1] * 3, c = idx[t + 2] * 3;
            occlusionBuffer.addTriangle(p[a], p[a + 1], p[a + 2], p[b], p[b + 1], p[b + 2], p[c], p[c + 1], p[c + 2]);
        }
    }

    private void collect(Node3D node, Mat4 parentWorld, Vec3 eye, float[] planes, int[] counts, List<Draw> opaque, List<Draw> blended) {
        if (!node.isVisible()) return;
        Mat4 world = parentWorld.mul(node.localMatrix());
        Mesh mesh = node.getMesh();
        if (mesh != null) {
            Draw draw = new Draw();
            draw.node = node;
            draw.mesh = mesh;
            draw.material = node.getMaterial() != null ? node.getMaterial() : DEFAULT_MATERIAL;
            draw.world = world;
            // Blended draws are sorted far to near by the middle of their mesh, which is not where the node's origin is
            // for a model whose origin sits at its feet.
            Vec3 origin = world.transformPoint(mesh.bounds().center());
            draw.distance = origin.sub(eye).length();
            // A mirrored node (negative scale) turns its triangles around, so what was the front is clockwise on screen.
            draw.mirrored = world.transformDirection(Vec3.X).cross(world.transformDirection(Vec3.UP))
                    .dot(world.transformDirection(Vec3.Z)) < 0f;
            boolean lines = mesh.isLines();
            draw.cast = node.isCastShadow() && !draw.material.isBlend() && !lines;
            draw.receive = node.isReceiveShadow();
            draw.morph = node.activeMorph();
            boolean gpuMorph = draw.morph != null && gpuMorph(mesh, draw.morph) != null;
            boolean skinned = node.getSkin() != null && mesh.isSkinned();
            if (node.getSkin() != null && !mesh.isSkinned()) {
                log.warn("The node {} has a skin but its mesh has no skinning data; drawing it unskinned.", node.getName());
            }
            draw.key = (draw.material.isDoubleSided() ? 0 : KEY_CULL) | (draw.material.isBlend() ? KEY_BLEND : 0) | (draw.material.isAdditive() ? KEY_ADDITIVE : 0)
                    | (draw.mirrored && !skinned ? KEY_MIRRORED : 0) | (skinned ? KEY_SKINNED : 0) | (mesh.hasUvs2() && usesSecondUvs(draw.material) ? KEY_UV2 : 0) | (lines ? KEY_LINES : 0) | (gpuMorph ? KEY_MORPH : 0);
            // A skinned mesh is placed by its joints, so it is bounded by them, not by its node.
            float[] skinSphere = skinned ? skinSphere(node, mesh) : null;
            Instances instances = skinned ? null : node.getInstances();
            draw.sortTriangles = node.isSortTriangles() && draw.material.isBlend() && !skinned && instances == null && draw.morph == null;
            if (draw.cast) {
                draw.sphere = skinned ? skinSphere : worldSphere(world, mesh, instances);
                draw.unbounded = draw.sphere == null || draw.morph != null || !node.isFrustumCulled();
            }
            boolean culls = node.isFrustumCulled();
            boolean outOfView = culls && (skinned
                    ? skinSphere != null && !ShadowPlanner.intersects(planes, skinSphere[0], skinSphere[1], skinSphere[2], skinSphere[3])
                    : !inFrustum(planes, world, mesh, instances));
            // Behind an occluder counts the same, apart from the tally: the node is not drawn but may still cast its shadow.
            boolean occluded = !outOfView && culls && occlusion != null && !skinned && instances == null && draw.morph == null
                    && occlusion.hides(OcclusionBuffer.corners(mesh.bounds(), world.toArray()));
            if (occluded) {
                outOfView = true;
                counts[2]++;
            }
            if (instances != null && instances.size() == 0) {
                // nothing to draw
            } else if (outOfView) {
                counts[1]++;
                if (draw.cast) {
                    // Out of view, but its shadow may fall into it.
                    draw.inView = false;
                    opaque.add(draw);
                }
            } else if (instances != null && culls && instances.size() > 1) {
                addInstanced(draw, world, mesh, instances, planes, eye, counts, opaque, blended);
            } else {
                counts[0]++;
                (draw.material.isBlend() ? blended : opaque).add(draw);
            }
        }
        for (Node3D child : node.getChildren()) collect(child, world, eye, planes, counts, opaque, blended);
    }

    /**
     * Whether any of the material's textures reads the second set of texture coordinates, so a mesh that has one needs it
     * sent to the shader.
     */
    private static boolean usesSecondUvs(Material material) {
        return material.texCoordBits() != 0;
    }

    /**
     * A sphere around a posed skinned mesh, or null if it has no bound (a joint list that moves no vertex).
     */
    private float[] skinSphere(Node3D node, Mesh mesh) {
        Skin skin = node.getSkin();
        SkinReach cached = skinReach.get(node);
        if (cached == null || cached.mesh != mesh || cached.skin != skin || cached.shapes != mesh.morphTargets()) {
            cached = new SkinReach(mesh, skin, mesh.morphTargets(), SkinBounds.reach(mesh, skin));
            skinReach.put(node, cached);
        }
        return SkinBounds.sphere(skin, cached.reach);
    }

    private record SkinReach(Mesh mesh, Skin skin, List<Mesh.MorphTarget> shapes, float[] reach) {
    }

    /**
     * An instanced node, one instance at a time: those out of view are left out of the main pass (a shadow caster keeps
     * them after the others, for the shadow views), and see-through ones become a draw each so they sort among
     * themselves, far to near, instead of the whole node sorting by one point.
     */
    private void addInstanced(Draw draw, Mat4 world, Mesh mesh, Instances instances, float[] planes, Vec3 eye, int[] counts,
                              List<Draw> opaque, List<Draw> blended) {
        int n = instances.size();
        float[] data = instances.matrixData();
        float[] w = world.toArray();
        Vec3 c = mesh.bounds().center();
        float radius = mesh.bounds().radius();
        float[] model = new float[16];
        int[] seen = new int[n];
        int visible = 0;
        float[] distance = draw.material.isBlend() ? new float[n] : null;
        for (int i = 0; i < n; i++) {
            multiply(w, data, i * 16, model);
            float x = model[0] * c.x() + model[4] * c.y() + model[8] * c.z() + model[12];
            float y = model[1] * c.x() + model[5] * c.y() + model[9] * c.z() + model[13];
            float z = model[2] * c.x() + model[6] * c.y() + model[10] * c.z() + model[14];
            if (ShadowPlanner.intersects(planes, x, y, z, radius * maxColumn(model, 0))) {
                if (occlusion != null && occlusion.hides(OcclusionBuffer.corners(mesh.bounds(), model))) {
                    counts[2]++;
                    continue;
                }
                seen[visible++] = i;
                if (distance != null) {
                    float dx = x - eye.x(), dy = y - eye.y(), dz = z - eye.z();
                    distance[i] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                }
            }
        }
        if (visible == 0) {
            counts[1]++;
            if (draw.cast) {
                draw.inView = false;
                opaque.add(draw);
            }
            return;
        }
        counts[0]++;
        if (distance != null && n <= MAX_SORTED_INSTANCES) {
            // Each in-view instance is a draw of its own with its own distance; neighbours of the same kind merge again
            // into one call once they are sorted.
            for (int k = 0; k < visible; k++) {
                Draw each = new Draw();
                each.node = draw.node;
                each.mesh = draw.mesh;
                each.material = draw.material;
                each.world = draw.world;
                each.mirrored = draw.mirrored;
                each.receive = draw.receive;
                each.key = draw.key;
                each.order = new int[]{seen[k]};
                each.visible = 1;
                each.distance = distance[seen[k]];
                blended.add(each);
            }
            return;
        }
        if (visible < n) {
            int[] order = new int[draw.cast ? n : visible];
            System.arraycopy(seen, 0, order, 0, visible);
            if (draw.cast) {
                boolean[] in = new boolean[n];
                for (int k = 0; k < visible; k++) in[seen[k]] = true;
                int at = visible;
                for (int i = 0; i < n; i++) if (!in[i]) order[at++] = i;
            }
            draw.order = order;
            draw.visible = visible;
        }
        (draw.material.isBlend() ? blended : opaque).add(draw);
    }

    /**
     * Past this many instances a see-through node is not split into draws to sort one by one.
     */
    private static final int MAX_SORTED_INSTANCES = 20_000;

    /**
     * The six planes of the view volume, each as (a, b, c, d) with a unit normal pointing inside, for {@code ax + by + cz + d >= 0}.
     */
    static float[] frustumPlanes(Mat4 viewProj) {
        float[] m = viewProj.toArray();
        // Each plane is the w row times 0 or 1 plus a signed row of x, y or z: left w + x, right w - x, bottom w + y,
        // top w - y, near z (depth runs 0 to 1) and far w - z.
        int[] row = {0, 0, 1, 1, 2, 2};
        float[] sign = {1f, -1f, 1f, -1f, 1f, -1f};
        float[] withW = {1f, 1f, 1f, 1f, 0f, 1f};
        float[] planes = new float[24];
        for (int p = 0; p < 6; p++) {
            int r = row[p];
            float[] plane = new float[4];
            for (int c = 0; c < 4; c++) plane[c] = withW[p] * m[c * 4 + 3] + sign[p] * m[c * 4 + r];
            float len = (float) Math.sqrt(plane[0] * plane[0] + plane[1] * plane[1] + plane[2] * plane[2]);
            if (len < 1e-12f) len = 1f;
            for (int c = 0; c < 4; c++) planes[p * 4 + c] = plane[c] / len;
        }
        return planes;
    }

    /**
     * Whether a sphere around the mesh (or around every instance of it) reaches into the view volume. Conservative: a
     * node that is only just outside can still be drawn, one that is inside never is skipped.
     */
    private static boolean inFrustum(float[] planes, Mat4 world, Mesh mesh, Instances instances) {
        float[] sphere = worldSphere(world, mesh, instances);
        return ShadowPlanner.intersects(planes, sphere[0], sphere[1], sphere[2], sphere[3]);
    }

    /**
     * A sphere {@code {x, y, z, radius}} in world space around the mesh, or around every instance of it.
     */
    private static float[] worldSphere(Mat4 world, Mesh mesh, Instances instances) {
        Bounds bounds = mesh.bounds();
        Vec3 c = bounds.center();
        float radius = bounds.radius();
        float[] w = world.toArray();
        float cx = c.x(), cy = c.y(), cz = c.z();
        float lx, ly, lz, lr;
        if (instances == null) {
            lx = cx;
            ly = cy;
            lz = cz;
            lr = radius;
        } else {
            int n = instances.size();
            float[] data = instances.matrixData();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                int o = i * 16;
                float x = data[o] * cx + data[o + 4] * cy + data[o + 8] * cz + data[o + 12];
                float y = data[o + 1] * cx + data[o + 5] * cy + data[o + 9] * cz + data[o + 13];
                float z = data[o + 2] * cx + data[o + 6] * cy + data[o + 10] * cz + data[o + 14];
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
            lx = (minX + maxX) * 0.5f;
            ly = (minY + maxY) * 0.5f;
            lz = (minZ + maxZ) * 0.5f;
            lr = 0f;
            for (int i = 0; i < n; i++) {
                int o = i * 16;
                float x = data[o] * cx + data[o + 4] * cy + data[o + 8] * cz + data[o + 12] - lx;
                float y = data[o + 1] * cx + data[o + 5] * cy + data[o + 9] * cz + data[o + 13] - ly;
                float z = data[o + 2] * cx + data[o + 6] * cy + data[o + 10] * cz + data[o + 14] - lz;
                lr = Math.max(lr, (float) Math.sqrt(x * x + y * y + z * z) + radius * maxColumn(data, o));
            }
        }
        float x = w[0] * lx + w[4] * ly + w[8] * lz + w[12];
        float y = w[1] * lx + w[5] * ly + w[9] * lz + w[13];
        float z = w[2] * lx + w[6] * ly + w[10] * lz + w[14];
        float r = lr * maxColumn(w, 0);
        return new float[]{x, y, z, r};
    }

    /**
     * The longest of the three axes of a matrix's upper 3x3, the most it stretches any direction.
     */
    private static float maxColumn(float[] m, int o) {
        float a = m[o] * m[o] + m[o + 1] * m[o + 1] + m[o + 2] * m[o + 2];
        float b = m[o + 4] * m[o + 4] + m[o + 5] * m[o + 5] + m[o + 6] * m[o + 6];
        float c = m[o + 8] * m[o + 8] + m[o + 9] * m[o + 9] + m[o + 10] * m[o + 10];
        return (float) Math.sqrt(Math.max(a, Math.max(b, c)));
    }

    /**
     * Writes each draw's records into the instance stream and merges neighbours that can share a call.
     */
    private List<Group> build(List<Draw> draws, Resources res, int[] cursor) {
        List<Group> groups = new ArrayList<>();
        Group last = null;
        for (Draw draw : draws) {
            boolean skinned = (draw.key & KEY_SKINNED) != 0;
            int count = 1;
            Instances instances = skinned ? null : draw.node.getInstances();
            if (instances != null) count = draw.order != null ? draw.order.length : instances.size();
            ensureScratch(cursor[0] + count);
            // Records out of view come after those in view, so a call for the main pass can stop short of them; nothing
            // can follow them in the same call, and no other call can run on into them.
            boolean hidden = draw.order != null && draw.visible < draw.order.length;

            boolean merge = last != null && !skinned && last.joints < 0 && last.vertices == null && draw.morph == null && !last.isolated && !hidden && !draw.sortTriangles && last.indices == null
                    && last.mesh == draw.mesh && last.material == draw.material
                    && last.key == draw.key && last.cast == draw.cast && last.inView == draw.inView;
            if (!merge) {
                last = new Group();
                last.mesh = draw.mesh;
                last.material = draw.material;
                last.key = draw.key;
                last.cast = draw.cast;
                last.inView = draw.inView;
                last.sphere = draw.sphere;
                last.unbounded = draw.unbounded;
                last.first = cursor[0];
                last.isolated = hidden;
                if (skinned) last.joints = writeJoints(res, draw.node.getSkin());
                if ((draw.key & KEY_MORPH) != 0) {
                    last.morphTexture = morphTextures.get(draw.mesh).texture;
                    last.morph = writeMorph(res, morphTextures.get(draw.mesh), draw.morph);
                } else if (draw.morph != null) {
                    last.vertices = morphedBuffer(draw.node, draw.mesh, draw.morph);
                }
                if (draw.sortTriangles) last.indices = sortedIndices(res, draw);
                groups.add(last);
            }
            if (merge && draw.cast) {
                last.sphere = union(last.sphere, draw.sphere);
                last.unbounded |= draw.unbounded;
            }
            writeRecords(draw, instances, skinned);
            last.count += count;
            last.viewCount += hidden ? draw.visible : count;
            cursor[0] += count;
        }
        return groups;
    }

    /**
     * The smallest sphere around two spheres {@code {x, y, z, radius}}.
     */
    static float[] union(float[] a, float[] b) {
        float dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d + b[3] <= a[3]) return a;
        if (d + a[3] <= b[3]) return b;
        float r = (d + a[3] + b[3]) * 0.5f;
        float t = (r - a[3]) / d;
        return new float[]{a[0] + dx * t, a[1] + dy * t, a[2] + dz * t, r};
    }

    private void ensureScratch(int records) {
        long needed = (long) records * INSTANCE_BYTES;
        if (needed > instanceScratch.capacity()) {
            ByteBuffer bigger = MemoryUtil.memAlloc((int) Math.max(needed, instanceScratch.capacity() * 2L)).order(ByteOrder.nativeOrder());
            instanceScratch.flip();
            bigger.put(instanceScratch);
            MemoryUtil.memFree(instanceScratch);
            instanceScratch = bigger;
        }
        if (writingTemporal != null && (long) records * PREV_BYTES > prevScratch.capacity()) {
            ByteBuffer bigger = MemoryUtil.memAlloc((int) Math.max((long) records * PREV_BYTES, prevScratch.capacity() * 2L)).order(ByteOrder.nativeOrder());
            prevScratch.flip();
            bigger.put(prevScratch);
            MemoryUtil.memFree(prevScratch);
            prevScratch = bigger;
        }
    }

    private void uploadInstances(Resources res, int records) {
        long bytes = (long) Math.max(records, 1) * INSTANCE_BYTES;
        if (res.instances == null || res.instanceCapacity < bytes) {
            if (res.instances != null) res.instances.close();
            res.instanceCapacity = Math.max(bytes, Math.max(res.instanceCapacity * 2, INSTANCE_BYTES * 64L));
            res.instances = device.createDynamicBuffer(BufferType.VERTEX, res.instanceCapacity);
        }
        instanceScratch.position(0).limit((int) Math.max(records * (long) INSTANCE_BYTES, 4));
        if (records == 0) instanceScratch.limit(4);
        res.instances.update(instanceScratch);
        instanceScratch.clear();
        if (writingTemporal != null) {
            long prevBytes = (long) Math.max(records, 1) * PREV_BYTES;
            if (res.prevInstances == null || res.prevCapacity < prevBytes) {
                if (res.prevInstances != null) res.prevInstances.close();
                res.prevCapacity = Math.max(prevBytes, Math.max(res.prevCapacity * 2, PREV_BYTES * 64L));
                res.prevInstances = device.createDynamicBuffer(BufferType.VERTEX, res.prevCapacity);
            }
            prevScratch.flip();
            if (records == 0) prevScratch.limit(4);
            res.prevInstances.update(prevScratch);
            prevScratch.clear();
        }
    }

    /**
     * The mesh's triangles sorted far to near as seen from the camera, in an index buffer of this render call: one per
     * sorted node, so a scene shown by two viewports gives each its own order.
     */
    private GpuBuffer sortedIndices(Resources res, Draw draw) {
        Mesh mesh = draw.mesh;
        int[] source = mesh.indices();
        int triangles = source.length / 3;
        float[] middle = centroids.computeIfAbsent(mesh, m -> {
            float[] v = m.vertices();
            float[] c = new float[triangles * 3];
            for (int t = 0; t < triangles; t++) {
                for (int k = 0; k < 3; k++) {
                    int o = source[t * 3 + k] * Mesh.FLOATS_PER_VERTEX;
                    c[t * 3] += v[o] / 3f;
                    c[t * 3 + 1] += v[o + 1] / 3f;
                    c[t * 3 + 2] += v[o + 2] / 3f;
                }
            }
            return c;
        });
        float[] w = draw.world.toArray();
        long[] keys = new long[triangles];
        for (int t = 0; t < triangles; t++) {
            float x = w[0] * middle[t * 3] + w[4] * middle[t * 3 + 1] + w[8] * middle[t * 3 + 2] + w[12] - eye.x();
            float y = w[1] * middle[t * 3] + w[5] * middle[t * 3 + 1] + w[9] * middle[t * 3 + 2] + w[13] - eye.y();
            float z = w[2] * middle[t * 3] + w[6] * middle[t * 3 + 1] + w[10] * middle[t * 3 + 2] + w[14] - eye.z();
            // A squared distance is never negative, so its bits sort like the number; the largest sorts first.
            keys[t] = (long) Float.floatToIntBits(x * x + y * y + z * z) << 32 | t;
        }
        java.util.Arrays.sort(keys);
        ByteBuffer data = MemoryUtil.memAlloc(source.length * 4).order(ByteOrder.nativeOrder());
        try {
            for (int k = triangles - 1; k >= 0; k--) {
                int t = (int) (keys[k] & 0xFFFFFFFFL);
                data.putInt(source[t * 3]).putInt(source[t * 3 + 1]).putInt(source[t * 3 + 2]);
            }
            data.flip();
            int slot = sortedCounter.merge(res, 1, Integer::sum) - 1;
            while (res.sortedIndices.size() <= slot) res.sortedIndices.add(null);
            GpuBuffer buffer = res.sortedIndices.get(slot);
            if (buffer == null || buffer.size() < data.remaining()) {
                if (buffer != null) buffer.close();
                buffer = device.createDynamicBuffer(BufferType.INDEX, Math.max(data.remaining(), 1024));
                res.sortedIndices.set(slot, buffer);
            }
            buffer.update(data);
            return buffer;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private int writeJoints(Resources res, Skin skin) {
        // Joint buffers are handed out in order within a render call; the call's groups use consecutive slots, counted from
        // the first at the start of each call (render() resets the count).
        int slot = jointsWritten(res);
        while (res.joints.size() <= slot) res.joints.add(device.createDynamicBuffer(BufferType.UNIFORM, JOINT_BLOCK_SIZE));
        scratch.clear();
        for (int i = 0; i < skin.joints().size(); i++) skin.jointMatrix(i).put(scratch);
        scratch.flip();
        res.joints.get(slot).update(scratch);
        jointCounter.merge(res, 1, Integer::sum);
        return slot;
    }

    private final Map<Resources, Integer> jointCounter = new IdentityHashMap<>();

    private int jointsWritten(Resources res) {
        return jointCounter.getOrDefault(res, 0);
    }

    /**
     * Fills the records of one draw: its transform and normal matrix, tint, and the material's parameters.
     */
    private void writeRecords(Draw draw, Instances instances, boolean skinned) {
        Material m = draw.material;
        float[] world = draw.world.toArray();
        // Where the node was in the last frame, for the motion of a temporal upscaler; a node that was not drawn then has not moved.
        float[] before = null;
        float[] beforeModel = new float[16];
        if (writingTemporal != null) {
            before = writingTemporal.previousWorld(draw.node);
            writingTemporal.remember(draw.node, world);
            if (before == null) before = world;
        }
        int flags = (m.getNormalTexture() != null ? FLAG_NORMAL_MAP : 0) | (draw.receive ? FLAG_RECEIVE_SHADOW : 0) | (m.texCoordBits() << FLAG_UV1_SHIFT);
        int mode = m.isUnlit() || draw.mesh.isLines() ? MODE_UNLIT : m.isPbr() ? MODE_PBR : MODE_CLASSIC;
        float p0 = m.isPbr() ? m.getMetallic() : m.getSpecular();
        float p1 = m.isPbr() ? m.getRoughness() : Math.max(1f, m.getShininess());
        float rotationCos = (float) Math.cos(m.getUvRotation()), rotationSin = (float) Math.sin(m.getUvRotation());
        float[] model = new float[16];
        ByteBuffer b = instanceScratch;
        int count = instances == null ? 1 : draw.order != null ? draw.order.length : instances.size();
        for (int slot = 0; slot < count; slot++) {
            int i = draw.order != null ? draw.order[slot] : slot;
            float tr = m.getRed(), tg = m.getGreen(), tb = m.getBlue(), ta = m.getAlpha();
            if (skinned) {
                java.util.Arrays.fill(model, 0f);
                model[0] = model[5] = model[10] = model[15] = 1f;
            } else if (instances == null) {
                System.arraycopy(world, 0, model, 0, 16);
            } else {
                multiply(world, instances.matrixData(), i * 16, model);
                if (instances.hasTints()) {
                    float[] t = instances.tintData();
                    tr *= t[i * 4];
                    tg *= t[i * 4 + 1];
                    tb *= t[i * 4 + 2];
                    ta *= t[i * 4 + 3];
                }
            }
            if (before != null) {
                // A skinned mesh has the identity here: its joints are its transform, and it counts as not having moved on its own.
                if (skinned) System.arraycopy(model, 0, beforeModel, 0, 16);
                else if (instances == null) System.arraycopy(before, 0, beforeModel, 0, 16);
                else multiply(before, instances.matrixData(), i * 16, beforeModel);
                for (float v : beforeModel) prevScratch.putFloat(v);
            }
            for (float v : model) b.putFloat(v);
            b.putFloat(tr).putFloat(tg).putFloat(tb).putFloat(ta);
            b.putFloat(p0).putFloat(p1).putFloat(mode).putFloat(m.getAlphaCutoff());
            b.putFloat(m.getEmissiveRed()).putFloat(m.getEmissiveGreen()).putFloat(m.getEmissiveBlue()).putFloat(flags);
            b.putFloat(m.getNormalScale()).putFloat(m.isAdditive() ? 1f : 0f).putFloat(rotationCos).putFloat(rotationSin);
            b.putFloat(m.getUvOffsetU()).putFloat(m.getUvOffsetV()).putFloat(m.getUvScaleU()).putFloat(m.getUvScaleV());
        }
    }

    /**
     * out = a * b[offset..offset+16), all column-major.
     */
    private static void multiply(float[] a, float[] b, int offset, float[] out) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                out[c * 4 + r] = a[r] * b[offset + c * 4] + a[4 + r] * b[offset + c * 4 + 1]
                        + a[8 + r] * b[offset + c * 4 + 2] + a[12 + r] * b[offset + c * 4 + 3];
            }
        }
    }

    // ---- shadows -----------------------------------------------------------------------------------------------

    /**
     * What the main pass needs to read this frame's shadows: the lights' plans (views with their tiles) and the atlas.
     */
    private record ShadowFrame(List<ShadowPlanner.LightPlan> plans, int atlasWidth, int atlasHeight, boolean bottomLeft) {
        static final ShadowFrame NONE = new ShadowFrame(List.of(), 0, 0, false);

        int viewCount() {
            int n = 0;
            for (ShadowPlanner.LightPlan plan : plans) n += plan.views().size();
            return n;
        }
    }

    /**
     * What the rays of one frame do: shadows traced in place of maps, reflections traced from smooth surfaces, and ambient
     * occlusion traced in place of the one read from the depth picture.
     */
    private record RayFrame(boolean shadows, boolean reflections, boolean occlusion, boolean gi) {
        static final RayFrame NONE = new RayFrame(false, false, false, false);
    }

    /**
     * Works out which of the ray traced effects this frame uses, and if any, places the scene's meshes for the rays. An effect
     * the scene did not ask for is off; one it asked for that the GPU can not do (reported once), or whose shader does not
     * compile here, is off too and the scene is drawn the usual way: shadow maps, the sky's reflection, screen-space occlusion.
     */
    private RayFrame prepareRays(Scene3D scene, Resources res, List<Light> lights, int env, boolean clustered, boolean postProcessed) {
        boolean wantShadows = scene.isRayTracedShadows(), wantReflections = scene.isRayTracedReflections();
        boolean wantOcclusion = scene.isRayTracedAmbientOcclusion() && scene.getAmbientOcclusion() > 0f && postProcessed;
        boolean wantGi = scene.isRayTracedGlobalIllumination() && postProcessed;
        if (!wantShadows && !wantReflections && !wantOcclusion && !wantGi) {
            scene.setUsingRayTracedShadows(false);
            scene.setUsingRayTracedReflections(false);
            return RayFrame.NONE;
        }
        boolean capable = device.supportsRayTracing();
        if (!capable && !warnedRay && !rayBroken) {
            warnedRay = true;
            log.warn("Ray tracing was asked for, but this device can not trace rays (Vulkan on a GPU with ray queries is needed); shadows are drawn as maps, reflections show the sky and occlusion is read from the depth picture instead.");
        }
        int clusterBit = clustered ? KEY_CLUSTER : 0;
        boolean shadows = wantShadows && capable && !rayBroken;
        if (shadows) {
            // The shader is compiled now, so a mistake shows up here and not in the middle of the draw.
            try {
                pipeline((env << KEY_ENV_SHIFT) | KEY_RAY | clusterBit);
            } catch (RuntimeException e) {
                rayBroken = true;
                shadows = false;
                log.error("The ray traced shadow shader does not compile on this GPU; shadows are drawn as maps instead.", e);
            }
        }
        scene.setUsingRayTracedShadows(shadows);
        boolean reflections = wantReflections && capable && !reflectBroken;
        if (reflections) {
            try {
                pipeline((env << KEY_ENV_SHIFT) | (shadows ? KEY_RAY : 0) | KEY_REFLECT | clusterBit);
            } catch (RuntimeException e) {
                reflectBroken = true;
                reflections = false;
                if (!warnedReflect) {
                    warnedReflect = true;
                    log.error("The ray traced reflection shader does not compile on this GPU; the sky is reflected instead.", e);
                }
            }
        }
        scene.setUsingRayTracedReflections(reflections);
        boolean gi = wantGi && capable && !giBroken && res.post.giAvailable();
        if (gi) {
            try {
                pipeline((env << KEY_ENV_SHIFT) | (shadows ? KEY_RAY : 0) | KEY_GI_TRACE);
                pipeline((env << KEY_ENV_SHIFT) | (shadows ? KEY_RAY : 0) | (reflections ? KEY_REFLECT : 0) | KEY_GI | clusterBit);
            } catch (RuntimeException e) {
                giBroken = true;
                gi = false;
                log.error("The global illumination shader does not compile on this GPU; surfaces keep the flat or sky ambient light.", e);
            }
        }
        boolean occlusion = wantOcclusion && capable;
        boolean anyCaster = false;
        for (Light light : lights) anyCaster |= light.isCastShadow();
        boolean tracedShadows = shadows && anyCaster;
        if (!tracedShadows && !reflections && !occlusion && !gi) return RayFrame.NONE;
        if (rayWorld == null) rayWorld = new RayWorld(device);
        raySlots.clear();
        rayTextureList.clear();
        List<RayInstance> placed = new ArrayList<>();
        // Reflections, occlusion and bounced light see every solid node; shadows only the ones that cast.
        rayWorld.collect(scene.root(), placed, reflections || occlusion || gi, reflections || gi ? this::rayTextureSlot : material -> 0);
        if (res.rayScene == null || res.rayScene.capacity() < placed.size()) {
            if (res.rayScene != null) res.rayScene.close();
            res.rayScene = device.createRayScene(Math.max(64, nextPowerOfTwo(placed.size())));
        }
        res.rayScene.update(placed);
        return new RayFrame(tracedShadows, reflections, occlusion, gi);
    }

    /**
     * Traces the indirect light the solid objects in view receive and filters it: they are drawn once more, with the same camera
     * as the scene, into a picture that holds in each pixel the light that reaches that surface from around it; the post pass
     * then averages it over frames and neighbours. Returns that picture, which the scene reads as its diffuse ambient light.
     */
    private GpuTexture indirectLight(Resources res, Scene3D scene, Camera camera, List<Group> groups, int traceBits, int env, Skybox sky,
                                     float aspect, boolean bottomLeft) {
        RenderTarget raw = res.post.giTarget();
        try (RenderPass pass = device.beginPass(raw, 0f, 0f, 0f, 0f)) {
            pass.bindTexture(7, whiteTexture);
            pass.bindAccelerationStructure(RAY_BINDING, res.rayScene);
            pass.bindUniformBuffer(RAY_BLOCK_BINDING, res.rayBlock);
            pass.bindTextures(RAY_TEXTURES_BINDING, rayTextureArray());
            if (env >= ENV_CUBE) pass.bindTexture(8, gpuSky(sky));
            int[] state = {-1, 0};
            Material[] lastMaterial = {null};
            for (Group group : groups) {
                if (!group.inView || group.viewCount == 0 || (group.key & KEY_LINES) != 0) continue;
                drawGroup(pass, res, group, state, lastMaterial, false, traceBits, null);
            }
        }
        return res.post.denoiseGi(camera, PostPass.Settings.of(scene, aspect), bottomLeft ? 1f : -1f, scene.temporalResets());
    }

    /**
     * The place, plus one, of a material's base colour texture in the array the reflection shader reads; 0 for a material
     * without one, or when the frame already shows as many textures as the array holds.
     */
    private int rayTextureSlot(Material material) {
        if (material.getTexture() == null) return 0;
        GpuTexture texture = gpuImage(material.getTexture());
        Integer slot = raySlots.get(texture);
        if (slot != null) return slot + 1;
        if (rayTextureList.size() >= RAY_TEXTURES) return 0;
        raySlots.put(texture, rayTextureList.size());
        rayTextureList.add(texture);
        return rayTextureList.size();
    }

    /**
     * The array of textures the reflection shader reads: this frame's, then white for the places no material uses.
     */
    private GpuTexture[] rayTextureArray() {
        GpuTexture[] array = new GpuTexture[RAY_TEXTURES];
        for (int i = 0; i < array.length; i++) array[i] = i < rayTextureList.size() ? rayTextureList.get(i) : whiteTexture;
        return array;
    }

    /**
     * Writes the block reflection rays read: where the ray scene keeps its table of instances, which frame this is (the
     * pattern a rough surface scatters its rays in turns with it while a temporal upscaler is there to average them), how far a
     * ray goes, and the roughness up to which a surface reflects by ray.
     */
    private void writeRayBlock(Resources res, Scene3D scene, boolean temporal) {
        if (res.rayBlock == null) res.rayBlock = device.createDynamicBuffer(BufferType.UNIFORM, RAY_BLOCK_BYTES);
        scratch.clear();
        scratch.putLong(res.rayScene.dataAddress()).putLong(0L);
        // The bounced light's rays turn every frame, whatever the upscaler: the filter is what averages them.
        scratch.putFloat(temporal ? (float) (frame & 7) : 0f).putFloat(scene.getRayTracedReflectionDistance()).putFloat((float) (frame & 63)).putFloat(0f);
        scratch.putFloat(scene.getRayTracedReflectionRoughness()).putFloat(scene.getRayTracedGlobalIlluminationRays())
                .putFloat(scene.getRayTracedGlobalIlluminationDistance()).putFloat(scene.getRayTracedGlobalIlluminationStrength());
        scratch.flip();
        res.rayBlock.update(scratch);
    }

    /**
     * Picks the lights that get shadows, gives each a tile size that fits the atlas, builds their views and lays the tiles out.
     */
    private ShadowFrame planShadows(Scene3D scene, List<Light> lights, Camera camera, float aspect, float[] cameraPlanes, List<Draw> opaque) {
        List<float[]> casters = new ArrayList<>();
        for (Draw d : opaque) if (d.cast) casters.add(d.sphere);
        if (casters.isEmpty()) return ShadowFrame.NONE;
        boolean[] truncated = {false};
        List<ShadowPlanner.Candidate> candidates = ShadowPlanner.select(scene, lights, camera.getPosition(), cameraPlanes, truncated);
        if (truncated[0] && !warnedShadowLights) {
            warnedShadowLights = true;
            log.warn("A Scene3D draws the shadows of at most {} lights; the others, farthest from the camera first, cast none.", Scene3D.MAX_SHADOW_LIGHTS);
        }
        if (candidates.isEmpty()) return ShadowFrame.NONE;

        int[] requested = new int[candidates.size()], counts = new int[candidates.size()];
        for (int i = 0; i < requested.length; i++) {
            ShadowPlanner.Candidate c = candidates.get(i);
            requested[i] = c.size();
            counts[i] = ShadowPlanner.viewCount(c.light(), c.shadows());
        }
        ShadowAtlas.Fit fit = ShadowAtlas.fit(requested, counts, scene.getShadowAtlasSize());
        if (!java.util.Arrays.equals(fit.lightSizes(), requested) && !warnedShadowShrink) {
            warnedShadowShrink = true;
            log.warn("The shadows of {} lights don't fit a {} pixel atlas at the sizes asked for; the biggest are drawn smaller. "
                    + "Lower their quality or raise Scene3D.setShadowAtlasSize.", candidates.size(), scene.getShadowAtlasSize());
        }
        RenderTarget target = atlas.target(fit.layout());
        atlasUsed = frame;

        List<ShadowPlanner.LightPlan> plans = new ArrayList<>();
        int tile = 0;
        for (int i = 0; i < candidates.size(); i++) {
            ShadowPlanner.LightPlan plan = ShadowPlanner.plan(scene, camera, aspect, candidates.get(i), fit.lightSizes()[i], casters);
            for (ShadowPlanner.View view : plan.views()) view.rect = fit.layout().rects().get(tile++);
            plans.add(plan);
        }
        return new ShadowFrame(plans, target.width(), target.height(), target.isBottomLeftOrigin());
    }

    private Pipeline velocityPipeline(int key) {
        Pipeline existing = velocityPipelines.get(key);
        if (existing != null) return existing;
        boolean skinned = (key & KEY_SKINNED) != 0, morph = (key & KEY_MORPH) != 0;
        // The instance stream, then the skin or, for a mesh that is not skinned, the previous frame's matrices.
        List<VertexLayout> extra = List.of(instanceLayout, skinned ? skinLayout : prevLayout);
        Pipeline created = device.createPipeline(new PipelineDesc(velocityVertex(skinned, morph), VELOCITY_FRAGMENT, meshLayout, true,
                (key & KEY_CULL) != 0, false, (key & KEY_MIRRORED) != 0, extra));
        velocityPipelines.put(key, created);
        return created;
    }

    /**
     * Draws the solid objects in view again, with the jitter the scene was drawn with, into the post pass's velocity target:
     * how far each pixel moved since the last frame in its colour, and the depth in its depth buffer. The sky is drawn
     * first, as the far plane, with the motion of the camera's turning.
     */
    private void velocityPass(Resources res, List<Group> groups, Mat4 drawViewProj, Mat4 viewProj, Camera camera, PostPass.Frame upFrame) {
        RenderTarget velocity = res.post.velocityTarget();
        float rowSign = velocity.isBottomLeftOrigin() ? 1f : -1f;
        float[] before = res.post.temporal().previousViewProj;
        if (before == null) before = viewProj.toArray();
        if (res.velocityBlock == null) res.velocityBlock = device.createDynamicBuffer(BufferType.UNIFORM, 64 * 3 + 16);
        if (res.skyMotionBlock == null) res.skyMotionBlock = device.createDynamicBuffer(BufferType.UNIFORM, 64 * 2 + 32);
        scratch.clear();
        drawViewProj.put(scratch);
        viewProj.put(scratch);
        for (float v : before) scratch.putFloat(v);
        scratch.putFloat(upFrame.renderWidth()).putFloat(upFrame.renderHeight()).putFloat(rowSign).putFloat(0f);
        scratch.flip();
        res.velocityBlock.update(scratch);
        scratch.clear();
        viewProj.inverse().put(scratch);
        for (float v : before) scratch.putFloat(v);
        Vec3 eye = camera.getPosition();
        scratch.putFloat(eye.x()).putFloat(eye.y()).putFloat(eye.z()).putFloat(0f);
        scratch.putFloat(upFrame.renderWidth()).putFloat(upFrame.renderHeight()).putFloat(rowSign).putFloat(0f);
        scratch.flip();
        res.skyMotionBlock.update(scratch);
        if (skyMotionPipeline == null) {
            skyMotionPipeline = device.createPipeline(new PipelineDesc(SKY_MOTION_VERTEX, SKY_MOTION_FRAGMENT, VertexLayout.of(VertexFormat.FLOAT2), true, false, false));
        }
        try (RenderPass pass = device.beginPass(velocity, 0f, 0f, 0f, 1f)) {
            pass.bindPipeline(skyMotionPipeline);
            pass.bindUniformBuffer(0, res.skyMotionBlock);
            pass.bindVertexBuffer(0, skyTriangle);
            pass.draw(3);
            int bound = -1;
            Material lastMaterial = null;
            for (Group group : groups) {
                if (!group.inView || group.viewCount == 0 || (group.key & KEY_LINES) != 0) continue;
                int key = group.key & (KEY_SKINNED | KEY_MORPH | KEY_CULL | KEY_MIRRORED);
                if (key != bound) {
                    pass.bindPipeline(velocityPipeline(key));
                    pass.bindUniformBuffer(0, res.velocityBlock);
                    bound = key;
                    lastMaterial = null;
                }
                GpuMesh mesh = gpuMesh(group.mesh);
                if (group.joints >= 0) pass.bindUniformBuffer(1, res.joints.get(group.joints));
                if (group.morph >= 0) {
                    pass.bindUniformBuffer(10, res.morphBlocks.get(group.morph));
                    pass.bindTexture(9, group.morphTexture);
                }
                if (group.material != lastMaterial) {
                    pass.bindTexture(2, group.material.getTexture() == null ? whiteTexture : gpuImage(group.material.getTexture()));
                    lastMaterial = group.material;
                }
                pass.bindVertexBuffer(0, group.vertices != null ? group.vertices : mesh.vertices);
                pass.bindVertexBuffer(1, res.instances);
                if ((key & KEY_SKINNED) != 0) {
                    if (mesh.skin != null) pass.bindVertexBuffer(2, mesh.skin);
                } else {
                    pass.bindVertexBuffer(2, res.prevInstances);
                }
                pass.bindIndexBuffer(group.indices != null ? group.indices : mesh.indices);
                pass.drawIndexed(mesh.indexCount, group.viewCount, group.first);
            }
        }
    }

    /**
     * Draws the solid objects in view from the camera into the post pass's depth target, packed the way the shadow map is
     * (the distance along the view axis between the near and far plane), for ambient occlusion to read. The same shader and
     * geometry as the shadow pass, so skinned, morphed and instanced objects are all there.
     */
    private void depthPass(Resources res, List<Group> groups, Mat4 viewProj, Camera camera) {
        if (res.depthView == null) res.depthView = device.createDynamicBuffer(BufferType.UNIFORM, SHADOW_BLOCK_SIZE);
        scratch.clear();
        viewProj.put(scratch);
        scratch.putFloat(1f).putFloat(camera.getNear()).putFloat(camera.getFar()).putFloat(0f);
        scratch.flip();
        res.depthView.update(scratch);
        try (RenderPass pass = device.beginPass(res.post.depthTarget(), 1f, 1f, 1f, 1f)) {
            int[] state = {-1, 0};
            Material[] lastMaterial = {null};
            for (Group group : groups) {
                if (!group.inView || group.viewCount == 0 || (group.key & KEY_LINES) != 0) continue;
                drawGroup(pass, res, group, state, lastMaterial, true, 0, res.depthView, true);
            }
        }
    }

    /**
     * Draws the casters into every view's tile of the atlas, leaving out those that can't show in that view.
     */
    private void shadowPass(Resources res, List<Group> groups, ShadowFrame shadows) {
        int views = shadows.viewCount();
        while (res.shadowViews.size() < views) res.shadowViews.add(device.createDynamicBuffer(BufferType.UNIFORM, SHADOW_BLOCK_SIZE));
        int v = 0;
        for (ShadowPlanner.LightPlan plan : shadows.plans()) {
            for (ShadowPlanner.View view : plan.views()) {
                scratch.clear();
                view.viewProj.put(scratch);
                scratch.putFloat(view.perspective ? 1f : 0f).putFloat(view.near).putFloat(view.far).putFloat(0f);
                scratch.flip();
                res.shadowViews.get(v++).update(scratch);
            }
        }
        try (RenderPass pass = device.beginPass(atlas.current(), 1f, 1f, 1f, 1f)) {
            v = 0;
            for (ShadowPlanner.LightPlan plan : shadows.plans()) {
                for (ShadowPlanner.View view : plan.views()) {
                    ShadowAtlas.Rect r = view.rect;
                    pass.setViewport(r.x(), r.y(), r.size(), r.size());
                    GpuBuffer buffer = res.shadowViews.get(v++);
                    int[] state = {-1, 0};
                    Material[] lastMaterial = {null};
                    for (Group group : groups) {
                        if (!group.cast) continue;
                        if (!group.unbounded && !ShadowPlanner.intersects(view.planes, group.sphere[0], group.sphere[1], group.sphere[2], group.sphere[3])) continue;
                        drawGroup(pass, res, group, state, lastMaterial, true, 0, buffer);
                    }
                }
            }
        }
    }

    // ---- the light grid ------------------------------------------------------------------------------------------

    /**
     * Whether the scene shader with the light grid compiles here. If it does not (reported once), scenes shade their lights
     * directly from then on.
     */
    private boolean clusterShaderWorks(int env) {
        if (clusterBroken) return false;
        try {
            pipeline((env << KEY_ENV_SHIFT) | KEY_CLUSTER);
            return true;
        } catch (RuntimeException e) {
            clusterBroken = true;
            log.error("The light grid's shader does not compile on this GPU; lights are shaded directly instead, and a scene with more than {} lights leaves some out.",
                    Scene3D.MAX_LIGHTS, e);
            return false;
        }
    }

    /**
     * Writes the grid into this render call's three textures, making them bigger first if the frame needs more room.
     */
    private static int nextPowerOfTwo(int n) {
        return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }

    private void uploadClusters(Resources res, LightClusters grid, List<Light> locals, Variant variant) {
        if (res.cluster == null) res.cluster = new ClusterTextures();
        ClusterTextures t = res.cluster;

        int lightRows = Math.max(16, nextPowerOfTwo(locals.size()));
        boolean fresh = t.lights == null || t.lightRows < lightRows;
        lightRows = fresh ? lightRows : t.lightRows;
        ByteBuffer data = MemoryUtil.memAlloc(lightRows * LightClusters.LIGHT_TEXELS * 4);
        try {
            LightClusters.writeLights(data, locals, lightRows, light -> falloffId(light.getFalloff(), variant));
            if (fresh) {
                if (t.lights != null) t.lights.close();
                t.lights = device.createTexture(LightClusters.LIGHT_TEXELS, lightRows, data);
                t.lightRows = lightRows;
            } else {
                t.lights.update(data);
            }
        } finally {
            MemoryUtil.memFree(data);
        }

        data = MemoryUtil.memAlloc(LightClusters.HEADER_WIDTH * LightClusters.HEADER_ROWS * 4);
        try {
            grid.writeHeaders(data);
            if (t.headers == null) t.headers = device.createTexture(LightClusters.HEADER_WIDTH, LightClusters.HEADER_ROWS, data);
            else t.headers.update(data);
        } finally {
            MemoryUtil.memFree(data);
        }

        int indexRows = nextPowerOfTwo(grid.indexRows());
        fresh = t.indices == null || t.indexRows < indexRows;
        indexRows = fresh ? indexRows : t.indexRows;
        data = MemoryUtil.memAlloc(indexRows * LightClusters.INDEX_WIDTH * 4);
        try {
            grid.writeIndices(data, indexRows);
            if (fresh) {
                if (t.indices != null) t.indices.close();
                t.indices = device.createTexture(LightClusters.INDEX_WIDTH, indexRows, data);
                t.indexRows = indexRows;
            } else {
                t.indices.update(data);
            }
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    // ---- uniform blocks ----------------------------------------------------------------------------------------

    /**
     * The custom pieces this frame's lights use, as the index of the shader variant that holds them. A set that doesn't
     * compile is reported once and its lights fall back to the built-in filter and falloff.
     */
    private int variantFor(List<Light> lights, List<Light> local, ShadowFrame shadows, int env, boolean clustered) {
        java.util.TreeSet<CustomShadowFilter> filters = new java.util.TreeSet<>(Comparator.comparing(CustomShadowFilter::name).thenComparing(CustomShadowFilter::glsl));
        java.util.TreeSet<CustomLightFalloff> falloffs = new java.util.TreeSet<>(Comparator.comparing(CustomLightFalloff::name).thenComparing(CustomLightFalloff::glsl));
        for (ShadowPlanner.LightPlan plan : shadows.plans()) {
            if (plan.shadows().resolveFilter() instanceof CustomShadowFilter f) filters.add(f);
        }
        for (int i = 0; i < Math.min(lights.size(), Scene3D.MAX_LIGHTS); i++) {
            if (lights.get(i).getType() != Light.Type.DIRECTIONAL && lights.get(i).getFalloff() instanceof CustomLightFalloff f) falloffs.add(f);
        }
        for (Light light : local) {
            if (light.getFalloff() instanceof CustomLightFalloff f) falloffs.add(f);
        }
        if (filters.isEmpty() && falloffs.isEmpty()) return 0;
        Variant wanted = new Variant(List.copyOf(filters), List.copyOf(falloffs));
        int index = variants.indexOf(wanted);
        if (index < 0) {
            index = variants.size();
            variants.add(wanted);
        }
        if (brokenVariants.contains(index)) return 0;
        try {
            pipeline((env << KEY_ENV_SHIFT) | (index << KEY_VARIANT_SHIFT) | (clustered ? KEY_CLUSTER : 0)); // compiles now, so a mistake shows up here
        } catch (RuntimeException e) {
            brokenVariants.add(index);
            log.error("A custom shadow filter or light falloff does not compile ({}); lights using one fall back to the built-in filter and falloff.",
                    describe(wanted), e);
            return 0;
        }
        return index;
    }

    private static String describe(Variant variant) {
        List<String> names = new ArrayList<>();
        for (CustomShadowFilter f : variant.filters()) names.add("filter " + f.name());
        for (CustomLightFalloff f : variant.falloffs()) names.add("falloff " + f.name());
        return String.join(", ", names);
    }

    private static int falloffId(LightFalloff falloff, Variant variant) {
        if (falloff instanceof BuiltinLightFalloff b) return b.id();
        int i = falloff instanceof CustomLightFalloff c ? variant.falloffs().indexOf(c) : -1;
        return i < 0 ? 0 : 3 + i;
    }

    private static int filterId(ShadowFilter filter, Variant variant) {
        if (filter instanceof BuiltinShadowFilter b) return b.id();
        int i = filter instanceof CustomShadowFilter c ? variant.filters().indexOf(c) : -1;
        return i < 0 ? ((BuiltinShadowFilter) ShadowFilter.PCF).id() : 3 + i;
    }

    private static float linear(float srgb) {
        return (float) Math.pow(Math.max(0f, srgb), 2.2);
    }

    private void writeFrameBlock(GpuBuffer buffer, Scene3D scene, List<Light> lights, Camera camera, Mat4 viewProj, ShadowFrame shadows, int env, Variant variant, boolean linearOutput, LightClusters clusters, float lodBias, boolean rayShadows) {
        scratch.clear();
        viewProj.put(scratch);
        Vec3 eye = camera.getPosition();
        Vec3 forward = camera.getTarget().sub(eye).normalized();
        scratch.putFloat(eye.x()).putFloat(eye.y()).putFloat(eye.z()).putFloat(1f);
        scratch.putFloat(forward.x()).putFloat(forward.y()).putFloat(forward.z()).putFloat(lodBias);
        Color ambient = scene.getAmbient();
        float ai = scene.getAmbientIntensity();
        scratch.putFloat(linear(ambient.getR()) * ai).putFloat(linear(ambient.getG()) * ai).putFloat(linear(ambient.getB()) * ai).putFloat(1f);

        int count = Math.min(lights.size(), Scene3D.MAX_LIGHTS);
        // The light grid's near plane and its depth scale, for the shader to find a pixel's slice.
        scratch.putFloat(count).putFloat(clusters == null ? 1f : clusters.near).putFloat(clusters == null ? 0f : clusters.scale).putFloat(0f);
        // The atlas's texel size, and whether its rows run from the bottom (OpenGL).
        scratch.putFloat(shadows.atlasWidth() == 0 ? 0f : 1f / shadows.atlasWidth()).putFloat(shadows.atlasHeight() == 0 ? 0f : 1f / shadows.atlasHeight())
                .putFloat(shadows.bottomLeft() ? 1f : 0f).putFloat(0f);
        for (int i = 0; i < Scene3D.MAX_LIGHTS; i++) {
            if (i < count) {
                Light light = lights.get(i);
                Vec3 v = light.getType() == Light.Type.DIRECTIONAL ? light.getVector().negate() : light.getVector();
                scratch.putFloat(v.x()).putFloat(v.y()).putFloat(v.z()).putFloat(light.getType() == Light.Type.DIRECTIONAL ? 0f : light.getType() == Light.Type.POINT ? 1f : 2f);
            } else {
                scratch.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f);
            }
        }
        for (int i = 0; i < Scene3D.MAX_LIGHTS; i++) {
            if (i < count) {
                Light light = lights.get(i);
                Color c = light.getColor();
                float k = light.getIntensity();
                scratch.putFloat(linear(c.getR()) * k).putFloat(linear(c.getG()) * k).putFloat(linear(c.getB()) * k).putFloat(light.getRange());
            } else {
                scratch.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f);
            }
        }
        for (int i = 0; i < Scene3D.MAX_LIGHTS; i++) { // spot directions, with the cosine of the outer angle
            if (i < count && lights.get(i).getType() == Light.Type.SPOT) {
                Light light = lights.get(i);
                Vec3 d = light.getDirection();
                scratch.putFloat(d.x()).putFloat(d.y()).putFloat(d.z()).putFloat((float) Math.cos(Math.toRadians(light.getOuterAngle())));
            } else {
                scratch.putFloat(0f).putFloat(-1f).putFloat(0f).putFloat(-1f);
            }
        }
        for (int i = 0; i < Scene3D.MAX_LIGHTS; i++) { // the cosine of the inner angle, and the falloff
            float inner = i < count && lights.get(i).getType() == Light.Type.SPOT
                    ? (float) Math.cos(Math.toRadians(lights.get(i).getInnerAngle())) : 1f;
            scratch.putFloat(inner).putFloat(i < count ? falloffId(lights.get(i).getFalloff(), variant) : 0f).putFloat(0f).putFloat(0f);
        }
        // Per light: the first of its shadow views, how many it has (0 for none), its filter and its softness; then the cascade splits.
        int[] first = new int[Scene3D.MAX_LIGHTS];
        ShadowPlanner.LightPlan[] planOf = new ShadowPlanner.LightPlan[Scene3D.MAX_LIGHTS];
        int view = 0;
        for (ShadowPlanner.LightPlan plan : shadows.plans()) {
            if (plan.index() < Scene3D.MAX_LIGHTS) {
                planOf[plan.index()] = plan;
                first[plan.index()] = view;
            }
            view += plan.views().size();
        }
        for (int i = 0; i < Scene3D.MAX_LIGHTS; i++) {
            ShadowPlanner.LightPlan plan = planOf[i];
            if (plan == null) {
                if (rayShadows && i < count && lights.get(i).isCastShadow()) {
                    // A filter id of -1 sends the shader to trace a ray instead of looking in the atlas.
                    scratch.putFloat(0f).putFloat(1f).putFloat(-1f).putFloat(1f);
                } else {
                    scratch.putFloat(-1f).putFloat(0f).putFloat(0f).putFloat(1f);
                }
            } else {
                scratch.putFloat(first[i]).putFloat(plan.views().size()).putFloat(filterId(plan.shadows().resolveFilter(), variant))
                        .putFloat(plan.shadows().resolveSoftness(scene));
            }
        }
        for (int i = 0; i < Scene3D.MAX_LIGHTS; i++) {
            float[] splits = planOf[i] == null ? new float[4] : planOf[i].splits();
            scratch.putFloat(splits[0]).putFloat(splits[1]).putFloat(splits[2]).putFloat(splits[3]);
        }
        for (ShadowPlanner.LightPlan plan : shadows.plans()) {
            for (ShadowPlanner.View v : plan.views()) {
                v.viewProj.put(scratch);
                ShadowAtlas.Rect r = v.rect;
                // The tile as a share of the atlas, from its top-left corner.
                scratch.putFloat((float) r.x() / shadows.atlasWidth()).putFloat((float) r.y() / shadows.atlasHeight())
                        .putFloat((float) r.size() / shadows.atlasWidth()).putFloat((float) r.size() / shadows.atlasHeight());
                scratch.putFloat(v.normalOffset).putFloat(v.depthBias).putFloat(v.near).putFloat(v.far);
            }
        }
        for (int i = shadows.viewCount(); i < MAX_SHADOW_VIEWS; i++) {
            for (int k = 0; k < SHADOW_VIEW_BYTES / 4; k++) scratch.putFloat(0f);
        }
        Color fog = scene.getFogColor();
        scratch.putFloat(linear(fog.getR())).putFloat(linear(fog.getG())).putFloat(linear(fog.getB())).putFloat(scene.getFogDensity());
        scratch.putFloat(scene.getFogHeightFalloff()).putFloat(scene.getExposure()).putFloat(scene.getToneMapping().ordinal()).putFloat(linearOutput ? 1f : 0f);
        Skybox sky = scene.getSkybox();
        if (env == ENV_NONE) {
            for (int i = 0; i < 13; i++) scratch.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f);
        } else {
            scratch.putFloat(scene.getEnvironmentLighting()).putFloat(env).putFloat(Environment.maxLod(sky)).putFloat(sky.getBrightness());
            float[] sh = skyIrradiance(sky);
            for (int i = 0; i < 9; i++) scratch.putFloat(sh[i * 3]).putFloat(sh[i * 3 + 1]).putFloat(sh[i * 3 + 2]).putFloat(0f);
            if (sky.kind == Skybox.Kind.GRADIENT) {
                for (Color c : new Color[]{sky.top, sky.horizon, sky.bottom}) scratch.putFloat(c.getR()).putFloat(c.getG()).putFloat(c.getB()).putFloat(0f);
            } else {
                for (int i = 0; i < 3; i++) scratch.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f);
            }
        }
        scratch.flip();
        buffer.update(scratch);
    }

    private void writeSky(GpuBuffer buffer, Scene3D scene, Camera camera, float aspect, Skybox sky, boolean linearOutput) {
        Vec3 f = camera.getTarget().sub(camera.getPosition()).normalized();
        Vec3 r = f.cross(camera.getUp()).normalized();
        Vec3 u = r.cross(f);
        float tanY = (float) Math.tan(Math.toRadians(camera.getFovYDegrees()) * 0.5);
        scratch.clear();
        scratch.putFloat(f.x()).putFloat(f.y()).putFloat(f.z()).putFloat(linearOutput ? 1f : 0f);
        scratch.putFloat(r.x()).putFloat(r.y()).putFloat(r.z()).putFloat(tanY * aspect);
        scratch.putFloat(u.x()).putFloat(u.y()).putFloat(u.z()).putFloat(tanY);
        if (sky.kind == Skybox.Kind.GRADIENT) {
            putColor(sky.top, sky.getBrightness());
            putColor(sky.horizon, 1f);
            putColor(sky.bottom, 1f);
        } else {
            scratch.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(sky.getBrightness());
            // A sky of linear light is exposed and tone mapped like the scene; the others are drawn as they are.
            scratch.putFloat(scene.getExposure()).putFloat(scene.getToneMapping().ordinal()).putFloat(0f).putFloat(1f);
            scratch.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f);
        }
        scratch.flip();
        buffer.update(scratch);
    }

    private void putColor(Color c, float alphaSlot) {
        scratch.putFloat(c.getR()).putFloat(c.getG()).putFloat(c.getB()).putFloat(alphaSlot);
    }

    // ---- GPU objects -------------------------------------------------------------------------------------------

    /**
     * Uploads one mesh, texture or skybox now, as drawing it would.
     */
    void preload(Object item, boolean skyLight) {
        switch (item) {
            case Mesh mesh -> gpuMesh(mesh);
            case TextureData texture -> gpuImage(texture);
            case Skybox sky -> {
                gpuSky(sky);
                if (skyLight) skyIrradiance(sky);
            }
            default -> throw new IllegalArgumentException("Cannot preload " + item);
        }
    }

    private GpuMesh gpuMesh(Mesh mesh) {
        GpuMesh gpu = meshes.get(mesh);
        if (gpu == null) {
            float[] vertices = mesh.vertices();
            int[] indices = mesh.indices();
            ByteBuffer vb = MemoryUtil.memAlloc(vertices.length * 4);
            ByteBuffer ib = MemoryUtil.memAlloc(indices.length * 4);
            ByteBuffer sb = null;
            try {
                vb.order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices);
                ib.order(ByteOrder.nativeOrder()).asIntBuffer().put(indices);
                GpuBuffer skin = null;
                if (mesh.isSkinned()) {
                    int[] joints = mesh.skinJoints();
                    float[] weights = mesh.skinWeights();
                    sb = MemoryUtil.memAlloc(joints.length * 8).order(ByteOrder.nativeOrder());
                    for (int v = 0; v < joints.length / 4; v++) {
                        for (int k = 0; k < 4; k++) sb.putFloat(joints[v * 4 + k]);
                        for (int k = 0; k < 4; k++) sb.putFloat(weights[v * 4 + k]);
                    }
                    sb.flip();
                    skin = device.createBuffer(BufferType.VERTEX, sb);
                }
                gpu = new GpuMesh(device.createBuffer(BufferType.VERTEX, vb), device.createBuffer(BufferType.INDEX, ib), skin, null, indices.length);
            } finally {
                MemoryUtil.memFree(vb);
                MemoryUtil.memFree(ib);
                if (sb != null) MemoryUtil.memFree(sb);
            }
            meshes.put(mesh, gpu);
        }
        if (gpu.uv2 == null && mesh.hasUvs2()) {
            float[] uvs = mesh.uvs2();
            ByteBuffer ub = MemoryUtil.memAlloc(uvs.length * 4);
            try {
                ub.order(ByteOrder.nativeOrder()).asFloatBuffer().put(uvs);
                gpu.uv2 = device.createBuffer(BufferType.VERTEX, ub);
            } finally {
                MemoryUtil.memFree(ub);
            }
        }
        gpu.lastUsed = frame;
        return gpu;
    }

    private GpuTexture gpuImage(TextureData data) {
        GpuImage image = images.get(data);
        if (image == null) {
            long[] version = new long[1];
            ByteBuffer pixels = data.toBuffer(version);
            try {
                image = new GpuImage(device.createTexture(data.width(), data.height(), pixels, true));
                image.version = version[0];
            } finally {
                MemoryUtil.memFree(pixels);
            }
            images.put(data, image);
        } else if (image.version != data.version()) {
            // Changed since the last upload (an animated screen): send the new picture, mipmaps included.
            long[] version = new long[1];
            ByteBuffer pixels = data.toBuffer(version);
            try {
                image.texture.update(pixels);
                image.version = version[0];
            } finally {
                MemoryUtil.memFree(pixels);
            }
        }
        image.lastUsed = frame;
        return image.texture;
    }

    /**
     * The mesh's blend shapes on the GPU, or null when they can't be blended there: too many shape-vertices for a texture,
     * or more shapes switched on than {@link SceneShaders#MAX_ACTIVE_MORPHS}.
     */
    private GpuMorph gpuMorph(Mesh mesh, float[] weights) {
        GpuMorph m = morphTextures.get(mesh);
        if (m != null && m.targets != mesh.morphTargets()) {
            m.close();
            m = null;
        }
        if (m == null) {
            m = new GpuMorph(device, mesh);
            morphTextures.put(mesh, m);
        }
        m.lastUsed = frame;
        if (m.texture == null) return null;
        int active = 0;
        for (int i = 0; i < weights.length && i < m.targets.size(); i++) if (weights[i] != 0f) active++;
        return active <= MAX_ACTIVE_MORPHS ? m : null;
    }

    /**
     * Writes the shapes a draw has switched on and their weights into the next morph block of the render call, as the
     * joints are, and gives the block's number.
     */
    private int writeMorph(Resources res, GpuMorph m, float[] weights) {
        int slot = morphCounter.getOrDefault(res, 0);
        while (res.morphBlocks.size() <= slot) res.morphBlocks.add(device.createDynamicBuffer(BufferType.UNIFORM, MORPH_BLOCK_SIZE));
        morphBlock.clear();
        for (int i = 0; i < MORPH_BLOCK_SIZE / 4; i++) morphBlock.putFloat(0f);
        int active = 0;
        boolean normals = false;
        for (int i = 0; i < weights.length && i < m.targets.size(); i++) {
            if (weights[i] == 0f) continue;
            morphBlock.putFloat(16 + active * 16, i).putFloat(20 + active * 16, weights[i]);
            normals |= m.normals[i];
            active++;
        }
        morphBlock.putFloat(0, active).putFloat(4, m.vertexCount).putFloat(8, MORPH_ROW).putFloat(12, normals ? 1f : 0f);
        morphBlock.position(0).limit(MORPH_BLOCK_SIZE);
        res.morphBlocks.get(slot).update(morphBlock);
        morphCounter.merge(res, 1, Integer::sum);
        GPU_MORPHS.incrementAndGet();
        return slot;
    }

    /**
     * The node's vertices with its blend shapes mixed in at the weights, kept on the GPU until the weights change.
     */
    private GpuBuffer morphedBuffer(Node3D node, Mesh mesh, float[] weights) {
        MorphedVertices m = morphed.get(node);
        if (m != null && m.mesh != mesh) {
            m.buffer.close();
            morphed.remove(node);
            m = null;
        }
        if (m == null) {
            m = new MorphedVertices(mesh, device.createDynamicBuffer(BufferType.VERTEX, (long) mesh.vertices().length * 4));
            morphed.put(node, m);
        }
        float[] used = java.util.Arrays.copyOf(weights, mesh.morphTargets().size());
        if (m.weights == null || !java.util.Arrays.equals(m.weights, used)) {
            CPU_MORPHS.incrementAndGet();
            float[] vertices = mesh.morph(used);
            int bytes = vertices.length * 4;
            if (morphScratch.capacity() < bytes) {
                MemoryUtil.memFree(morphScratch);
                morphScratch = MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
            }
            morphScratch.clear();
            morphScratch.asFloatBuffer().put(vertices);
            morphScratch.position(0).limit(bytes);
            m.buffer.update(morphScratch);
            m.weights = used;
        }
        m.lastUsed = frame;
        return m.buffer;
    }

    private float[] skyIrradiance(Skybox sky) {
        return skyLight.computeIfAbsent(sky, Environment::irradiance);
    }

    private GpuTexture gpuSky(Skybox sky) {
        GpuImage image = skies.get(sky);
        if (image == null) {
            if (sky.kind == Skybox.Kind.CUBE) {
                ByteBuffer[] faces = new ByteBuffer[6];
                try {
                    for (int i = 0; i < 6; i++) faces[i] = sky.faces[i].toBuffer();
                    image = new GpuImage(device.createCubeTexture(sky.faces[0].width(), faces, true));
                } finally {
                    for (ByteBuffer face : faces) if (face != null) MemoryUtil.memFree(face);
                }
            } else if (sky.kind == Skybox.Kind.EQUIRECT_HDR) {
                java.nio.FloatBuffer pixels = sky.hdr.toBuffer();
                try {
                    image = new GpuImage(device.createHdrTexture(sky.hdr.width(), sky.hdr.height(), pixels, true));
                } finally {
                    MemoryUtil.memFree(pixels);
                }
            } else {
                ByteBuffer pixels = sky.panorama.toBuffer();
                try {
                    image = new GpuImage(device.createTexture(sky.panorama.width(), sky.panorama.height(), pixels, true));
                } finally {
                    MemoryUtil.memFree(pixels);
                }
            }
            skies.put(sky, image);
        }
        image.lastUsed = frame;
        return image.texture;
    }

    /**
     * Frees what the scene hasn't drawn for a while, so a model that was swapped out gives its memory back.
     */
    private void sweep() {
        if (frame - atlasUsed > KEEP_FRAMES) atlas.close();
        if (rayWorld != null) rayWorld.sweep(KEEP_FRAMES);
        for (Iterator<GpuMesh> it = meshes.values().iterator(); it.hasNext(); ) {
            GpuMesh mesh = it.next();
            if (frame - mesh.lastUsed > KEEP_FRAMES) {
                mesh.close();
                it.remove();
            }
        }
        centroids.keySet().removeIf(mesh -> !meshes.containsKey(mesh));
        for (Iterator<MorphedVertices> it = morphed.values().iterator(); it.hasNext(); ) {
            MorphedVertices m = it.next();
            if (frame - m.lastUsed > KEEP_FRAMES) {
                m.buffer.close();
                it.remove();
            }
        }
        for (Iterator<GpuMorph> it = morphTextures.values().iterator(); it.hasNext(); ) {
            GpuMorph m = it.next();
            if (frame - m.lastUsed > KEEP_FRAMES) {
                m.close();
                it.remove();
            }
        }
        for (Map<?, GpuImage> map : List.of(images, skies)) {
            for (Iterator<GpuImage> it = map.values().iterator(); it.hasNext(); ) {
                GpuImage image = it.next();
                if (frame - image.lastUsed > KEEP_FRAMES) {
                    image.texture.close();
                    it.remove();
                }
            }
        }
    }

    void close() {
        for (GpuMesh mesh : meshes.values()) mesh.close();
        for (GpuImage image : images.values()) image.texture.close();
        for (GpuImage image : skies.values()) image.texture.close();
        for (MorphedVertices m : morphed.values()) m.buffer.close();
        morphed.clear();
        for (GpuMorph m : morphTextures.values()) m.close();
        morphTextures.clear();
        MemoryUtil.memFree(morphBlock);
        MemoryUtil.memFree(morphScratch);
        for (Resources r : resourcePool) r.close();
        for (Pipeline pipeline : pipelines.values()) pipeline.close();
        for (Pipeline pipeline : skyPipelines.values()) pipeline.close();
        for (Pipeline pipeline : velocityPipelines.values()) pipeline.close();
        velocityPipelines.clear();
        if (skyMotionPipeline != null) skyMotionPipeline.close();
        atlas.close();
        if (rayWorld != null) rayWorld.close();
        skyTriangle.close();
        whiteTexture.close();
        flatNormalTexture.close();
        meshes.clear();
        images.clear();
        skies.clear();
        skyLight.clear();
        resourcePool.clear();
        pipelines.clear();
        skyPipelines.clear();
        MemoryUtil.memFree(instanceScratch);
        MemoryUtil.memFree(prevScratch);
        MemoryUtil.memFree(scratch);
    }
}
