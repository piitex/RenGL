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

    private static final int FRAME_BLOCK_SIZE = 64 + 16 * 5 + 16 * Scene3D.MAX_LIGHTS * 4 + 64 + 32 + 16 * 13;
    private static final int SHADOW_BLOCK_SIZE = 64 + 16;
    private static final int INSTANCE_BYTES = INSTANCE_FLOATS * 4;
    private static final int JOINT_BLOCK_SIZE = Skin.MAX_JOINTS * 64;
    private static final int SKY_BLOCK_SIZE = 16 * 6;
    /**
     * Frames an uploaded mesh or texture may go undrawn before it is freed, and how often to look.
     */
    private static final int KEEP_FRAMES = 600;
    private static final int SWEEP_EVERY = 120;
    private static final Material DEFAULT_MATERIAL = new Material();

    private static final int KEY_CULL = 1, KEY_BLEND = 2, KEY_MIRRORED = 4, KEY_SKINNED = 8, KEY_SHADOW = 16, KEY_ADDITIVE = 32, KEY_ENV_SHIFT = 6;

    private static final class GpuMesh {
        final GpuBuffer vertices;
        final GpuBuffer indices;
        final GpuBuffer skin;
        final int indexCount;
        long lastUsed;

        GpuMesh(GpuBuffer vertices, GpuBuffer indices, GpuBuffer skin, int indexCount) {
            this.vertices = vertices;
            this.indices = indices;
            this.skin = skin;
            this.indexCount = indexCount;
        }

        void close() {
            vertices.close();
            indices.close();
            if (skin != null) skin.close();
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

    private static final class GpuImage {
        final GpuTexture texture;
        long lastUsed;

        GpuImage(GpuTexture texture) {
            this.texture = texture;
        }
    }

    /**
     * The buffers one render call fills.
     */
    private static final class Resources {
        final GpuBuffer frame, shadowFrame, sky;
        GpuBuffer instances;
        long instanceCapacity;
        final List<GpuBuffer> joints = new ArrayList<>();

        Resources(GpuDevice device) {
            frame = device.createDynamicBuffer(BufferType.UNIFORM, FRAME_BLOCK_SIZE);
            shadowFrame = device.createDynamicBuffer(BufferType.UNIFORM, SHADOW_BLOCK_SIZE);
            sky = device.createDynamicBuffer(BufferType.UNIFORM, SKY_BLOCK_SIZE);
        }

        void close() {
            frame.close();
            shadowFrame.close();
            sky.close();
            if (instances != null) instances.close();
            for (GpuBuffer b : joints) b.close();
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
        float[] morph; // blend-shape weights to draw with, or null
        int key;
    }

    /**
     * One instanced call: {@code count} consecutive records starting at {@code first}.
     */
    private static final class Group {
        Mesh mesh;
        Material material;
        int key;
        boolean cast;
        boolean inView = true; // false: only drawn into the shadow map
        GpuBuffer vertices; // a morphed node's own vertices, or null for the mesh's
        int first, count;
        int joints = -1; // index into the render call's joint buffers, for a skinned group
    }

    private final GpuDevice device;
    private final VertexLayout meshLayout = VertexLayout.of(VertexFormat.FLOAT3, VertexFormat.FLOAT3, VertexFormat.FLOAT2);
    private final VertexLayout instanceLayout = VertexLayout.of(3, VertexLayout.Rate.INSTANCE,
            VertexFormat.FLOAT4, VertexFormat.FLOAT4, VertexFormat.FLOAT4, VertexFormat.FLOAT4,
            VertexFormat.FLOAT4, VertexFormat.FLOAT4, VertexFormat.FLOAT4,
            VertexFormat.FLOAT4, VertexFormat.FLOAT4, VertexFormat.FLOAT4, VertexFormat.FLOAT4);
    private final VertexLayout skinLayout = VertexLayout.of(14, VertexLayout.Rate.VERTEX, VertexFormat.FLOAT4, VertexFormat.FLOAT4);
    private final Map<Integer, Pipeline> pipelines = new HashMap<>();
    private final Map<Skybox.Kind, Pipeline> skyPipelines = new HashMap<>();
    private final Map<Mesh, GpuMesh> meshes = new HashMap<>();
    private final Map<TextureData, GpuImage> images = new IdentityHashMap<>();
    private final Map<Skybox, GpuImage> skies = new IdentityHashMap<>();
    private final Map<Skybox, float[]> skyLight = new IdentityHashMap<>();
    private final Map<Node3D, MorphedVertices> morphed = new IdentityHashMap<>();
    private ByteBuffer morphScratch = MemoryUtil.memAlloc(1024).order(ByteOrder.nativeOrder());
    private final List<Resources> resourcePool = new ArrayList<>();
    private final GpuTexture whiteTexture, flatNormalTexture;
    private final GpuBuffer skyTriangle;
    private ByteBuffer instanceScratch = MemoryUtil.memAlloc(INSTANCE_BYTES * 256).order(ByteOrder.nativeOrder());
    private final ByteBuffer scratch = MemoryUtil.memAlloc(Math.max(FRAME_BLOCK_SIZE, JOINT_BLOCK_SIZE)).order(ByteOrder.nativeOrder());
    private RenderTarget shadowTarget;
    private int shadowTargetSize;
    private long frame;
    private long lastDeviceFrame = -1;
    private int resourcesUsed;
    private boolean warnedLights;

    SceneRenderer(GpuDevice device) {
        this.device = device;
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
        boolean skinned = (key & KEY_SKINNED) != 0;
        PipelineDesc desc;
        if ((key & KEY_SHADOW) != 0) {
            desc = new PipelineDesc(shadowVertex(skinned), SHADOW_FRAGMENT, meshLayout, true, false, false, false,
                    skinned ? List.of(instanceLayout, skinLayout) : List.of(instanceLayout));
        } else {
            boolean blend = (key & KEY_BLEND) != 0;
            // Blended surfaces test against the depth buffer but don't write to it, so a see-through pixel never hides
            // something drawn after it.
            desc = new PipelineDesc(vertex(skinned), fragment((key >> KEY_ENV_SHIFT) & 3), meshLayout, true, (key & KEY_CULL) != 0, blend,
                    (key & KEY_MIRRORED) != 0, skinned ? List.of(instanceLayout, skinLayout) : List.of(instanceLayout),
                    (key & KEY_ADDITIVE) != 0, !blend);
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
        }, VertexLayout.of(VertexFormat.FLOAT2), false, false, false)));
    }

    // ---- one render --------------------------------------------------------------------------------------------

    void render(Scene3D scene, RenderTarget target) {
        frame++;
        long deviceFrame = device.frameNumber();
        if (deviceFrame != lastDeviceFrame) {
            lastDeviceFrame = deviceFrame;
            resourcesUsed = 0;
        }
        while (resourcePool.size() <= resourcesUsed) resourcePool.add(new Resources(device));
        Resources res = resourcePool.get(resourcesUsed++);

        float aspect = (float) target.width() / Math.max(1, target.height());
        Camera camera = scene.camera();
        Mat4 viewProj = camera.projection(aspect).mul(camera.view());

        List<Draw> opaque = new ArrayList<>();
        List<Draw> blended = new ArrayList<>();
        float[] planes = frustumPlanes(viewProj);
        int[] counts = {0, 0};
        collect(scene.root(), Mat4.identity(), camera.getPosition(), planes, counts, opaque, blended);
        opaque.sort(Comparator.<Draw>comparingInt(d -> d.key).thenComparingInt(d -> System.identityHashCode(d.material))
                .thenComparingInt(d -> System.identityHashCode(d.mesh)));
        blended.sort((a, b) -> Float.compare(b.distance, a.distance));

        int[] cursor = {0};
        instanceScratch.clear();
        List<Group> opaqueGroups = build(opaque, res, cursor);
        List<Group> blendedGroups = build(blended, res, cursor);
        uploadInstances(res, cursor[0]);

        Light shadowLight = shadowCaster(scene);
        boolean shadows = shadowLight != null && opaqueGroups.stream().anyMatch(g -> g.cast);
        float[] shadowParams = new float[8];
        Mat4 lightViewProj = Mat4.identity();
        if (shadows) {
            lightViewProj = shadowLight.getType() == Light.Type.SPOT
                    ? spotShadowMatrices(scene, shadowLight, shadowParams)
                    : shadowMatrices(scene, camera, aspect, opaque, shadowLight, shadowParams);
            scratch.clear();
            lightViewProj.put(scratch);
            scratch.putFloat(shadowParams[4]).putFloat(shadowParams[5]).putFloat(shadowParams[6]).putFloat(0f);
            scratch.flip();
            res.shadowFrame.update(scratch);
            shadowPass(scene, opaqueGroups, res);
        }
        Skybox sky = scene.getSkybox();
        int env = environmentKind(scene, sky);
        writeFrameBlock(res.frame, scene, camera, viewProj, shadows ? shadowLight : null, lightViewProj, shadowParams, env);
        if (sky != null) writeSky(res.sky, camera, aspect, sky);

        Color bg = scene.getBackground();
        int drawCalls = 0, drawnRecords = 0;
        try (RenderPass pass = device.beginPass(target, bg.getR(), bg.getG(), bg.getB(), 1f)) {
            if (sky != null) drawSky(pass, res, sky);
            pass.bindTexture(7, shadows ? shadowTarget.texture() : whiteTexture);
            if (env >= ENV_CUBE) pass.bindTexture(8, gpuSky(sky));
            int[] state = {-1, 0};
            Material[] lastMaterial = {null};
            for (List<Group> list : List.of(opaqueGroups, blendedGroups)) {
                for (Group group : list) {
                    if (!group.inView) continue;
                    drawGroup(pass, res, group, state, lastMaterial, false, env);
                    drawCalls++;
                    drawnRecords += group.count;
                }
            }
        }
        scene.setStats(new Scene3D.Stats(counts[0], counts[1], drawnRecords, drawCalls));
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
    private void drawGroup(RenderPass pass, Resources res, Group group, int[] state, Material[] lastMaterial, boolean shadowPass, int env) {
        int key = shadowPass ? (group.key & KEY_SKINNED) | KEY_SHADOW : group.key | (env << KEY_ENV_SHIFT);
        GpuMesh mesh = gpuMesh(group.mesh);
        if (key != state[0]) {
            pass.bindPipeline(pipeline(key));
            pass.bindUniformBuffer(0, shadowPass ? res.shadowFrame : res.frame);
            state[0] = key;
            lastMaterial[0] = null;
        }
        if (group.joints >= 0) pass.bindUniformBuffer(1, res.joints.get(group.joints));
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
        if (mesh.skin != null && (key & KEY_SKINNED) != 0) pass.bindVertexBuffer(2, mesh.skin);
        pass.bindIndexBuffer(mesh.indices);
        pass.drawIndexed(mesh.indexCount, group.count, group.first);
    }

    // ---- collecting and building the instance stream -----------------------------------------------------------

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
            draw.cast = node.isCastShadow() && !draw.material.isBlend();
            draw.receive = node.isReceiveShadow();
            draw.morph = node.activeMorph();
            boolean skinned = node.getSkin() != null && mesh.isSkinned();
            if (node.getSkin() != null && !mesh.isSkinned()) {
                log.warn("The node {} has a skin but its mesh has no skinning data; drawing it unskinned.", node.getName());
            }
            draw.key = (draw.material.isDoubleSided() ? 0 : KEY_CULL) | (draw.material.isBlend() ? KEY_BLEND : 0) | (draw.material.isAdditive() ? KEY_ADDITIVE : 0)
                    | (draw.mirrored && !skinned ? KEY_MIRRORED : 0) | (skinned ? KEY_SKINNED : 0);
            if (node.getInstances() != null && node.getInstances().size() == 0 && !skinned) {
                // nothing to draw
            } else if (skinned || !node.isFrustumCulled() || inFrustum(planes, world, mesh, node.getInstances())) {
                counts[0]++;
                (draw.material.isBlend() ? blended : opaque).add(draw);
            } else {
                counts[1]++;
                if (draw.cast) {
                    // Out of view, but its shadow may fall into it.
                    draw.inView = false;
                    opaque.add(draw);
                }
            }
        }
        for (Node3D child : node.getChildren()) collect(child, world, eye, planes, counts, opaque, blended);
    }

    /**
     * The six planes of the view volume, each as (a, b, c, d) with a unit normal pointing inside, for {@code ax + by + cz + d >= 0}.
     */
    private static float[] frustumPlanes(Mat4 viewProj) {
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
        for (int p = 0; p < 6; p++) {
            if (planes[p * 4] * x + planes[p * 4 + 1] * y + planes[p * 4 + 2] * z + planes[p * 4 + 3] < -r) return false;
        }
        return true;
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
            if (instances != null) count = instances.size();
            ensureScratch(cursor[0] + count);

            boolean merge = last != null && !skinned && last.joints < 0 && last.vertices == null && draw.morph == null
                    && last.mesh == draw.mesh && last.material == draw.material
                    && last.key == draw.key && last.cast == draw.cast && last.inView == draw.inView;
            if (!merge) {
                last = new Group();
                last.mesh = draw.mesh;
                last.material = draw.material;
                last.key = draw.key;
                last.cast = draw.cast;
                last.inView = draw.inView;
                last.first = cursor[0];
                if (skinned) last.joints = writeJoints(res, draw.node.getSkin());
                if (draw.morph != null) last.vertices = morphedBuffer(draw.node, draw.mesh, draw.morph);
                groups.add(last);
            }
            writeRecords(draw, instances, skinned);
            last.count += count;
            cursor[0] += count;
        }
        return groups;
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
    }

    private int writeJoints(Resources res, Skin skin) {
        int slot = 0;
        // Joint buffers are handed out in order within a render call; the call's groups use consecutive slots.
        for (GpuBuffer ignored : res.joints) slot++;
        // Find the first slot not used by an earlier group of this call: tracked by the count written so far.
        slot = jointsWritten(res);
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
        int flags = (m.getNormalTexture() != null ? FLAG_NORMAL_MAP : 0) | (draw.receive ? FLAG_RECEIVE_SHADOW : 0);
        int mode = m.isUnlit() ? MODE_UNLIT : m.isPbr() ? MODE_PBR : MODE_CLASSIC;
        float p0 = m.isPbr() ? m.getMetallic() : m.getSpecular();
        float p1 = m.isPbr() ? m.getRoughness() : Math.max(1f, m.getShininess());
        float[] model = new float[16];
        float[] normal = new float[12];
        ByteBuffer b = instanceScratch;
        int count = instances == null ? 1 : instances.size();
        for (int i = 0; i < count; i++) {
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
            normalMatrix(model, normal);
            for (float v : model) b.putFloat(v);
            for (int c = 0; c < 3; c++) b.putFloat(normal[c * 3]).putFloat(normal[c * 3 + 1]).putFloat(normal[c * 3 + 2]).putFloat(0f);
            b.putFloat(tr).putFloat(tg).putFloat(tb).putFloat(ta);
            b.putFloat(p0).putFloat(p1).putFloat(mode).putFloat(m.getAlphaCutoff());
            b.putFloat(m.getEmissiveRed()).putFloat(m.getEmissiveGreen()).putFloat(m.getEmissiveBlue()).putFloat(flags);
            b.putFloat(m.getNormalScale()).putFloat(m.isAdditive() ? 1f : 0f).putFloat(0f).putFloat(0f);
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

    /**
     * The inverse transpose of the model matrix's 3x3, as three columns, so normals stay perpendicular under scaling.
     */
    private static void normalMatrix(float[] m, float[] out) {
        float c0x = m[0], c0y = m[1], c0z = m[2], c1x = m[4], c1y = m[5], c1z = m[6], c2x = m[8], c2y = m[9], c2z = m[10];
        float n0x = c1y * c2z - c1z * c2y, n0y = c1z * c2x - c1x * c2z, n0z = c1x * c2y - c1y * c2x;
        float det = c0x * n0x + c0y * n0y + c0z * n0z;
        if (Math.abs(det) < 1e-20f) {
            java.util.Arrays.fill(out, 0f);
            out[0] = out[4] = out[8] = 1f;
            return;
        }
        float inv = 1f / det;
        out[0] = n0x * inv;
        out[1] = n0y * inv;
        out[2] = n0z * inv;
        out[3] = (c2y * c0z - c2z * c0y) * inv;
        out[4] = (c2z * c0x - c2x * c0z) * inv;
        out[5] = (c2x * c0y - c2y * c0x) * inv;
        out[6] = (c0y * c1z - c0z * c1y) * inv;
        out[7] = (c0z * c1x - c0x * c1z) * inv;
        out[8] = (c0x * c1y - c0y * c1x) * inv;
    }

    // ---- shadows -----------------------------------------------------------------------------------------------

    private static Light shadowCaster(Scene3D scene) {
        List<Light> lights = scene.lights();
        for (int i = 0; i < Math.min(lights.size(), Scene3D.MAX_LIGHTS); i++) {
            Light light = lights.get(i);
            if (light.isCastShadow() && light.getType() != Light.Type.POINT) return light;
        }
        return null;
    }

    /**
     * The orthographic matrix that looks down the light over a sphere around the part of the view frustum that gets
     * shadows, as seen from the light. The sphere does not change when the camera turns, and its centre moves in whole
     * texels, so the shadows don't shimmer as the camera moves.
     *
     * @param params receives {@code {normalOffset, depthBias, texelUv, bottomLeft}} for the shader
     */
    private Mat4 shadowMatrices(Scene3D scene, Camera camera, float aspect, List<Draw> draws, Light light, float[] params) {
        int size = scene.getShadowMapSize();
        if (shadowTarget == null || shadowTargetSize != size) {
            if (shadowTarget != null) shadowTarget.close();
            shadowTarget = device.createSampledTarget(size, size, true, false);
            shadowTargetSize = size;
        }
        Vec3 dir = light.getVector().normalized();
        Vec3 eye = camera.getPosition();
        Vec3 f = camera.getTarget().sub(eye).normalized();
        Vec3 r = f.cross(camera.getUp()).normalized();
        Vec3 u = r.cross(f);
        float near = camera.getNear();
        float far = Math.min(camera.getFar(), Math.max(near + 0.01f, scene.getShadowDistance()));
        float tanY = (float) Math.tan(Math.toRadians(camera.getFovYDegrees()) * 0.5);
        float tanX = tanY * aspect;

        Vec3[] corners = new Vec3[8];
        Vec3 centre = Vec3.ZERO;
        for (int i = 0; i < 8; i++) {
            float d = (i & 1) == 0 ? near : far;
            float sx = (i & 2) == 0 ? -1 : 1, sy = (i & 4) == 0 ? -1 : 1;
            corners[i] = eye.add(f.mul(d)).add(r.mul(sx * tanX * d)).add(u.mul(sy * tanY * d));
            centre = centre.add(corners[i]);
        }
        centre = centre.mul(1f / 8f);
        float radius = 0f;
        for (Vec3 c : corners) radius = Math.max(radius, c.sub(centre).length());
        radius = Math.max(radius, 0.5f);

        // The light's own axes, to snap the centre to the shadow map's texel grid.
        Vec3 ref = Math.abs(dir.y()) > 0.99f ? Vec3.X : Vec3.UP;
        Vec3 lr = dir.cross(ref).normalized();
        Vec3 lu = lr.cross(dir);
        float texel = 2f * radius / size;
        float sx = (float) Math.floor(centre.dot(lr) / texel) * texel;
        float sy = (float) Math.floor(centre.dot(lu) / texel) * texel;
        centre = lr.mul(sx).add(lu.mul(sy)).add(dir.mul(centre.dot(dir)));

        // How far toward the light things can be and still need to cast into the picture.
        float back = radius;
        for (Draw d : draws) {
            if (!d.cast) continue;
            Bounds bounds = d.mesh.bounds().transform(d.world);
            Vec3 lo = bounds.min(), hi = bounds.max();
            for (int i = 0; i < 8; i++) {
                Vec3 p = new Vec3((i & 1) == 0 ? lo.x() : hi.x(), (i & 2) == 0 ? lo.y() : hi.y(), (i & 4) == 0 ? lo.z() : hi.z());
                back = Math.max(back, centre.sub(p).dot(dir));
            }
        }
        back = Math.min(back, 2000f) + 1f;
        Vec3 lightEye = centre.sub(dir.mul(back));
        Mat4 view = Mat4.lookAt(lightEye, centre, ref);
        Mat4 proj = Mat4.orthographic(-radius, radius, -radius, radius, 0f, back + radius);

        params[0] = texel * 1.5f * scene.getShadowBias();
        params[1] = (texel * 2.5f * scene.getShadowBias()) / (back + radius);
        params[2] = 1f / size;
        params[3] = shadowTarget.isBottomLeftOrigin() ? 1f : 0f;
        return proj.mul(view);
    }

    /**
     * The perspective matrix of a spot light's cone. The map stores the distance along the axis, so the offsets grow
     * with the distance from the light, as the texels do.
     *
     * @param params as for {@link #shadowMatrices}, plus {@code {perspective, near, far}} at 4 to 6
     */
    private Mat4 spotShadowMatrices(Scene3D scene, Light light, float[] params) {
        int size = scene.getShadowMapSize();
        if (shadowTarget == null || shadowTargetSize != size) {
            if (shadowTarget != null) shadowTarget.close();
            shadowTarget = device.createSampledTarget(size, size, true, false);
            shadowTargetSize = size;
        }
        Vec3 position = light.getVector();
        Vec3 dir = light.getDirection();
        Vec3 ref = Math.abs(dir.y()) > 0.99f ? Vec3.X : Vec3.UP;
        float far = light.getRange();
        float near = Math.max(0.05f, far * 0.005f);
        float half = (float) Math.toRadians(light.getOuterAngle());
        Mat4 view = Mat4.lookAt(position, position.add(dir), ref);
        Mat4 proj = Mat4.perspective(Math.min(half * 2f + 0.05f, 3.0f), 1f, near, far);
        // The width of one texel at distance 1 along the axis.
        float texel = 2f * (float) Math.tan(Math.min(half + 0.025f, 1.5f)) / size;
        params[0] = texel * 1.5f * scene.getShadowBias();
        params[1] = texel * 2.5f * scene.getShadowBias() / (far - near);
        params[2] = 1f / size;
        params[3] = shadowTarget.isBottomLeftOrigin() ? 1f : 0f;
        params[4] = 1f;
        params[5] = near;
        params[6] = far;
        return proj.mul(view);
    }

    private void shadowPass(Scene3D scene, List<Group> groups, Resources res) {
        try (RenderPass pass = device.beginPass(shadowTarget, 1f, 1f, 1f, 1f)) {
            int[] state = {-1, 0};
            Material[] lastMaterial = {null};
            for (Group group : groups) {
                if (group.cast) drawGroup(pass, res, group, state, lastMaterial, true, ENV_NONE);
            }
        }
    }

    // ---- uniform blocks ----------------------------------------------------------------------------------------

    private static float linear(float srgb) {
        return (float) Math.pow(Math.max(0f, srgb), 2.2);
    }

    private void writeFrameBlock(GpuBuffer buffer, Scene3D scene, Camera camera, Mat4 viewProj, Light shadowLight,
                                 Mat4 lightViewProj, float[] shadow, int env) {
        scratch.clear();
        viewProj.put(scratch);
        Vec3 eye = camera.getPosition();
        scratch.putFloat(eye.x()).putFloat(eye.y()).putFloat(eye.z()).putFloat(1f);
        Color ambient = scene.getAmbient();
        float ai = scene.getAmbientIntensity();
        scratch.putFloat(linear(ambient.getR()) * ai).putFloat(linear(ambient.getG()) * ai).putFloat(linear(ambient.getB()) * ai).putFloat(1f);

        List<Light> lights = scene.lights();
        if (lights.size() > Scene3D.MAX_LIGHTS && !warnedLights) {
            warnedLights = true;
            log.warn("A Scene3D uses at most {} lights; the other {} are ignored.", Scene3D.MAX_LIGHTS, lights.size() - Scene3D.MAX_LIGHTS);
        }
        int count = Math.min(lights.size(), Scene3D.MAX_LIGHTS);
        scratch.putFloat(count).putFloat(0f).putFloat(0f).putFloat(0f);
        int shadowIndex = shadowLight == null ? -1 : lights.indexOf(shadowLight);
        scratch.putFloat(shadowIndex).putFloat(shadow[3]).putFloat(shadow[2]).putFloat(shadow[1]);
        scratch.putFloat(shadow[0]).putFloat(shadow[4]).putFloat(shadow[5]).putFloat(shadow[6]);
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
        for (int i = 0; i < Scene3D.MAX_LIGHTS; i++) { // the cosine of the inner angle
            float inner = i < count && lights.get(i).getType() == Light.Type.SPOT
                    ? (float) Math.cos(Math.toRadians(lights.get(i).getInnerAngle())) : 1f;
            scratch.putFloat(inner).putFloat(0f).putFloat(0f).putFloat(0f);
        }
        lightViewProj.put(scratch);
        Color fog = scene.getFogColor();
        scratch.putFloat(linear(fog.getR())).putFloat(linear(fog.getG())).putFloat(linear(fog.getB())).putFloat(scene.getFogDensity());
        scratch.putFloat(scene.getFogHeightFalloff()).putFloat(scene.getShadowSoftness()).putFloat(0f).putFloat(0f);
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

    private void writeSky(GpuBuffer buffer, Camera camera, float aspect, Skybox sky) {
        Vec3 f = camera.getTarget().sub(camera.getPosition()).normalized();
        Vec3 r = f.cross(camera.getUp()).normalized();
        Vec3 u = r.cross(f);
        float tanY = (float) Math.tan(Math.toRadians(camera.getFovYDegrees()) * 0.5);
        scratch.clear();
        scratch.putFloat(f.x()).putFloat(f.y()).putFloat(f.z()).putFloat(0f);
        scratch.putFloat(r.x()).putFloat(r.y()).putFloat(r.z()).putFloat(tanY * aspect);
        scratch.putFloat(u.x()).putFloat(u.y()).putFloat(u.z()).putFloat(tanY);
        if (sky.kind == Skybox.Kind.GRADIENT) {
            putColor(sky.top, sky.getBrightness());
            putColor(sky.horizon, 1f);
            putColor(sky.bottom, 1f);
        } else {
            scratch.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(sky.getBrightness());
            scratch.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f);
            scratch.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f);
        }
        scratch.flip();
        buffer.update(scratch);
    }

    private void putColor(Color c, float alphaSlot) {
        scratch.putFloat(c.getR()).putFloat(c.getG()).putFloat(c.getB()).putFloat(alphaSlot);
    }

    // ---- GPU objects -------------------------------------------------------------------------------------------

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
                gpu = new GpuMesh(device.createBuffer(BufferType.VERTEX, vb), device.createBuffer(BufferType.INDEX, ib), skin, indices.length);
            } finally {
                MemoryUtil.memFree(vb);
                MemoryUtil.memFree(ib);
                if (sb != null) MemoryUtil.memFree(sb);
            }
            meshes.put(mesh, gpu);
        }
        gpu.lastUsed = frame;
        return gpu;
    }

    private GpuTexture gpuImage(TextureData data) {
        GpuImage image = images.get(data);
        if (image == null) {
            ByteBuffer pixels = data.toBuffer();
            try {
                image = new GpuImage(device.createTexture(data.width(), data.height(), pixels, true));
            } finally {
                MemoryUtil.memFree(pixels);
            }
            images.put(data, image);
        }
        image.lastUsed = frame;
        return image.texture;
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
        for (Iterator<GpuMesh> it = meshes.values().iterator(); it.hasNext(); ) {
            GpuMesh mesh = it.next();
            if (frame - mesh.lastUsed > KEEP_FRAMES) {
                mesh.close();
                it.remove();
            }
        }
        for (Iterator<MorphedVertices> it = morphed.values().iterator(); it.hasNext(); ) {
            MorphedVertices m = it.next();
            if (frame - m.lastUsed > KEEP_FRAMES) {
                m.buffer.close();
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
        MemoryUtil.memFree(morphScratch);
        for (Resources r : resourcePool) r.close();
        for (Pipeline pipeline : pipelines.values()) pipeline.close();
        for (Pipeline pipeline : skyPipelines.values()) pipeline.close();
        if (shadowTarget != null) shadowTarget.close();
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
        MemoryUtil.memFree(scratch);
    }
}
