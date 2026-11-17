package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The GPU half of virtual geometry: each {@link ClusterDag} in GPU memory, and every frame a compute shader that picks, for
 * every record of every group drawn as clusters, the clusters to draw ({@link ClusterDag#cut}'s choice, made per record) and
 * writes one indexed draw command per cluster, which the passes then draw with {@link RenderPass#drawIndexedIndirectCount}.
 * The commands index the mesh's own vertex buffer and carry the record as their first instance, so the scene's ordinary
 * shaders draw them: materials, lights, shadows and motion are what they are for any mesh.
 * <p>
 * Each group gets two lists: what the camera sees (the cut at the pixel error, records in view only, each cluster tested
 * against the view volume) and what casts shadows (the cut at the shadow's coarser error, every record, nothing culled: the
 * shadow passes cull by light).
 * <p>
 * A job with many records and clusters walks the hierarchy over the clusters ({@link ClusterDag.Tree}) a depth at a time,
 * skipping each node with nothing under it to draw, and tests only the clusters of the leaves it reaches; a smaller one
 * tests every cluster of every record, which is cheaper than the walk's passes there. Both pick the same clusters.
 * <p>
 * With occlusion on, the camera's list is made in two steps, the way Nanite's is. First each cluster is tested against a
 * pyramid of the farthest depth in each block of last frame's picture: those it does not hide go into the list, the others
 * into a list of maybes. The scene's solid things and that first list are then drawn into a depth picture
 * ({@link #occlusionTarget}), a pyramid is made of it ({@link #retest}), and the maybes are tested again against what is in
 * front of them now. What this frame shows is drawn either way; what the camera moved to see is found in the second step.
 */
final class VirtualGeometry implements AutoCloseable {
    /** The most draw commands a frame's lists hold together: 20 MB. Past it, clusters are left out (and a warning says so). */
    static final int MAX_COMMANDS = 1 << 20;
    static final int COMMAND_BYTES = 20;
    private static final int GROUP_SIZE = 64;
    private static final int CLUSTER_BYTES = 80;
    /** Bytes before the groups' counts: the triangles in view, and a flag set when a list was too short. */
    private static final int TOTALS_BYTES = 16;
    private static final int VIEW_BLOCK_BYTES = 16 * 7;
    /** A depth pyramid has at most this many levels (enough for a picture 65536 pixels wide). */
    private static final int MAX_LEVELS = 16;
    private static final int OCCLUSION_BLOCK_BYTES = 64 + 16 + 16 * MAX_LEVELS;
    private static final int UNUSED_FRAMES = 600;

    /** What both the first pick and the second test read: a depth pyramid, and where it is. */
    private static final String OCCLUSION_GLSL = """
            // Level k of the pyramid is levels[k].x by levels[k].y texels from levels[k].z floats in; each holds the farthest
            // depth of the 2^(k+1) pixels square of the picture it covers. size: levels, the picture's width and height, and
            // whether there is a pyramid at all.
            layout(std430, set = 0, binding = 5) readonly buffer Pyramid { float hiz[]; };
            layout(std140, set = 0, binding = 6) uniform Occlusion { mat4 occlusionViewProj; uvec4 size; uvec4 levels[16]; };

            // Whether the picture holds something in front of all of the sphere: its box is projected, the level whose texels
            // are a third of that is read (at most four by four texels), and the nearest corner is farther than all of them.
            bool occluded(vec3 c, float r) {
                if (size.w == 0u) return false;
                vec2 lo = vec2(1e30), hi = vec2(-1e30);
                float nearest = 1e30;
                for (int k = 0; k < 8; k++) {
                    vec3 corner = c + r * vec3((k & 1) != 0 ? 1.0 : -1.0, (k & 2) != 0 ? 1.0 : -1.0, (k & 4) != 0 ? 1.0 : -1.0);
                    vec4 p = occlusionViewProj * vec4(corner, 1.0);
                    if (p.w <= 1e-5) return false; // reaches behind the eye
                    vec3 ndc = p.xyz / p.w;
                    lo = min(lo, ndc.xy);
                    hi = max(hi, ndc.xy);
                    nearest = min(nearest, ndc.z);
                }
                if (nearest <= 0.0) return false;
                float w = float(size.y), h = float(size.z);
                // Rows run down from the top of the picture, where clip space's +y is.
                ivec2 a = ivec2(floor((lo.x + 1.0) * 0.5 * w), floor((1.0 - hi.y) * 0.5 * h));
                ivec2 b = ivec2(floor((hi.x + 1.0) * 0.5 * w), floor((1.0 - lo.y) * 0.5 * h));
                a = clamp(a, ivec2(0), ivec2(int(size.y) - 1, int(size.z) - 1));
                b = clamp(b, ivec2(0), ivec2(int(size.y) - 1, int(size.z) - 1));
                int span = max(b.x - a.x, b.y - a.y) + 1;
                int level = 0;
                while ((2 << level) * 3 < span) level++;
                level = min(level, int(size.x) - 1);
                ivec2 ta = a >> (level + 1), tb = b >> (level + 1);
                uvec4 l = levels[level];
                tb = min(tb, ivec2(l.xy) - 1);
                float farthest = 0.0;
                for (int y = ta.y; y <= tb.y; y++) {
                    for (int x = ta.x; x <= tb.x; x++) farthest = max(farthest, hiz[l.z + uint(y) * l.x + uint(x)]);
                }
                return nearest > farthest;
            }
            """;

    private static final String COMMON_GLSL = """
            struct Cluster { vec4 sphere; vec4 parentSphere; float error; float parentError; uint first; uint count; vec4 bounds;
                             uint underFirst; uint underCount; };
            layout(std430, set = 0, binding = 0) readonly buffer Clusters { Cluster clusters[]; };
            layout(std430, set = 0, binding = 1) readonly buffer Records { float records[]; };
            struct Command { uint indexCount; uint instanceCount; uint firstIndex; int vertexOffset; uint firstInstance; };
            layout(std430, set = 0, binding = 2) buffer Commands { Command commands[]; };
            layout(std430, set = 0, binding = 3) buffer Counts { uint counts[]; };
            // The clusters the first test left for the second: the cluster and its record.
            layout(std430, set = 0, binding = 7) buffer Maybes { uvec2 maybes[]; };
            layout(push_constant) uniform Job {
                uint recordFirst; uint records; uint viewRecords; uint clusterCount;
                uint viewBase; uint viewMax; uint shadowBase; uint shadowMax;
                uint countSlot; float maxPixels; float shadowMaxPixels; uint rowWidth;
                uint occlusion;
                // The walk of the hierarchy: this pass's depth in it, the roots, where the job's queues are (two, one read
                // and one written by each pass, of queueMax entries each) and where its counts for each depth are in counts[].
                uint depth; uint roots; uint queueBase; uint queueMax; uint argsBase;
            } job;

            mat4 modelOf(uint record) {
                uint base = record * 36u;
                mat4 m;
                for (int k = 0; k < 4; k++) {
                    uint o = base + uint(k) * 4u;
                    m[k] = vec4(records[o], records[o + 1u], records[o + 2u], records[o + 3u]);
                }
                return m;
            }

            float scaleOf(mat4 m) {
                return sqrt(max(dot(m[0].xyz, m[0].xyz), max(dot(m[1].xyz, m[1].xyz), dot(m[2].xyz, m[2].xyz))));
            }

            void drawInView(Cluster c, uint instance) {
                uint i = atomicAdd(counts[4u + job.countSlot * 4u], 1u);
                if (i < job.viewMax) {
                    commands[job.viewBase + i] = Command(c.count, 1u, c.first, 0, instance);
                    atomicAdd(counts[0], c.count / 3u);
                } else {
                    atomicOr(counts[1], 1u);
                }
            }
            """;

    /** What the flat cut and the walk share: the camera, and the choice made for one cluster of one record. */
    private static final String PICK_GLSL = """
            // The view volume's planes (inside where dot(xyz, p) + w >= 0), the eye, and pixels per unit at distance 1.
            layout(std140, set = 0, binding = 4) uniform View { vec4 planes[6]; vec4 eye; };

            // The error as pixels on screen from the nearest point of the sphere; the same steps for a cluster's own error and
            // for its parent's (computed by its siblings and by the parent itself alike), so the two choices always agree.
            float projected(float error, vec4 s, mat4 m, float scale) {
                if (error == 0.0) return 0.0;
                precise vec3 c = (m * vec4(s.xyz, 1.0)).xyz - eye.xyz;
                precise float d = length(c) - s.w * scale;
                if (d <= 1e-6) return 1e30;
                precise float p = error * scale * eye.w / d;
                return p;
            }

            bool inView(vec3 w, float r) {
                for (int p = 0; p < 6; p++) {
                    if (dot(planes[p].xyz, w) + planes[p].w < -r) return false;
                }
                return true;
            }

            // Cluster ci of a record, for the camera's list (when view) and the shadows' (when shadow): drawn where its own
            // error is fine enough and its replacement's is not.
            void pick(uint ci, uint record, mat4 m, float scale, bool view, bool shadow) {
                Cluster c = clusters[ci];
                uint instance = job.recordFirst + record;
                float own = projected(c.error, c.sphere, m, scale);
                float parent = c.parentError < 0.0 ? 1e30 : projected(c.parentError, c.parentSphere, m, scale);
                uint slot = 4u + job.countSlot * 4u;
                if (view && own <= job.maxPixels && parent > job.maxPixels) {
                    vec3 w = (m * vec4(c.bounds.xyz, 1.0)).xyz;
                    float r = c.bounds.w * scale;
                    if (inView(w, r)) {
                        if (job.occlusion != 0u && occluded(w, r)) {
                            uint i = atomicAdd(counts[slot + 2u], 1u);
                            if (i < job.viewMax) maybes[job.viewBase + i] = uvec2(ci, instance);
                        } else {
                            drawInView(c, instance);
                        }
                    }
                }
                if (shadow && own <= job.shadowMaxPixels && parent > job.shadowMaxPixels) {
                    uint i = atomicAdd(counts[slot + 1u], 1u);
                    if (i < job.shadowMax) commands[job.shadowBase + i] = Command(c.count, 1u, c.first, 0, instance);
                    else atomicOr(counts[1], 1u);
                }
            }
            """;

    /** Every cluster of every record tested, one thread each: for a job the hierarchy's queues have no room for. */
    static final String CUT_SHADER = "#version 450\nlayout(local_size_x = 64) in;\n" + COMMON_GLSL + OCCLUSION_GLSL + PICK_GLSL + """
            void main() {
                uint id = gl_GlobalInvocationID.y * job.rowWidth + gl_GlobalInvocationID.x;
                uint record = id / job.clusterCount;
                if (record >= job.records) return;
                uint ci = id - record * job.clusterCount;
                mat4 m = modelOf(job.recordFirst + record);
                pick(ci, record, m, scaleOf(m), record < job.viewRecords, job.shadowMax > 0u);
            }
            """;

    /**
     * One depth of the walk down the hierarchy ({@link ClusterDag.Tree}): one thread per record and node, the roots at depth 0
     * and after that the nodes the depth above queued. A node that neither the camera's list nor the shadows' has a use for
     * is left with all under it; a leaf tests its clusters; any other node queues its children for the next depth and counts
     * the work groups they take where the next depth's dispatch reads them.
     */
    static final String WALK_SHADER = ("#version 450\nlayout(local_size_x = 64) in;\n" + COMMON_GLSL + OCCLUSION_GLSL + PICK_GLSL + """
            layout(std430, set = 0, binding = 9) buffer Queue { uvec2 queue[]; };

            // A node is kept after the clusters, in their shape: sphere and error are the sphere around its clusters' own
            // spheres and the least own error under it; parentSphere and parentError the same for their replacements, with the
            // greatest error (-1 for none above); bounds the sphere around their triangles; first and count its children
            // (clusters when the top bit of count is set, else nodes); underFirst and underCount all the clusters under it.
            bool needed(Cluster n, mat4 m, float scale, float limit) {
                // Every cluster under it is replaced: even from its nearest point, the coarsest replacement is fine enough.
                if (n.parentError >= 0.0 && projected(n.parentError, n.parentSphere, m, scale) <= limit * FINE_MARGIN) return false;
                // Every cluster under it is too coarse: even from its farthest point, the finest is not fine enough.
                if (n.error > 0.0) {
                    float far = length((m * vec4(n.sphere.xyz, 1.0)).xyz - eye.xyz) + n.sphere.w * scale;
                    if (n.error * scale * eye.w / far > limit * COARSE_MARGIN) return false;
                }
                return true;
            }

            void main() {
                uint record, node;
                if (job.depth == 0u) {
                    uint id = gl_GlobalInvocationID.y * job.rowWidth + gl_GlobalInvocationID.x;
                    record = id / job.roots;
                    if (record >= job.records) return;
                    node = id - record * job.roots;
                } else {
                    uint id = gl_GlobalInvocationID.x;
                    if (id >= min(counts[job.argsBase + job.depth * 4u + 3u], job.queueMax)) return;
                    uvec2 entry = queue[job.queueBase + (job.depth & 1u) * job.queueMax + id];
                    record = entry.x;
                    node = entry.y;
                }
                Cluster n = clusters[job.clusterCount + node];
                mat4 m = modelOf(job.recordFirst + record);
                float scale = scaleOf(m);
                bool view = record < job.viewRecords && needed(n, m, scale, job.maxPixels)
                        && inView((m * vec4(n.bounds.xyz, 1.0)).xyz, n.bounds.w * scale);
                bool shadow = job.shadowMax > 0u && needed(n, m, scale, job.shadowMaxPixels);
                if (!view && !shadow) return;
                uint count = n.count & 0x7FFFFFFFu;
                if ((n.count & 0x80000000u) != 0u) {
                    for (uint k = 0u; k < count; k++) pick(n.first + k, record, m, scale, view, shadow);
                    return;
                }
                uint at = job.argsBase + (job.depth + 1u) * 4u;
                uint start = atomicAdd(counts[at + 3u], count);
                uint fit = start >= job.queueMax ? 0u : min(count, job.queueMax - start);
                // The children the queue has no room for: every cluster under them is tested here. Slower, but nothing is
                // left out.
                for (uint k = fit; k < count; k++) {
                    Cluster child = clusters[job.clusterCount + n.first + k];
                    for (uint ci = child.underFirst; ci < child.underFirst + child.underCount; ci++) pick(ci, record, m, scale, view, shadow);
                }
                if (fit == 0u) return;
                uint base = job.queueBase + ((job.depth + 1u) & 1u) * job.queueMax;
                for (uint k = 0u; k < fit; k++) queue[base + start + k] = uvec2(record, n.first + k);
                // The next depth's dispatch: enough groups of 64 for every entry, in one row.
                atomicMax(counts[at], (start + fit - 1u) / 64u + 1u);
                atomicMax(counts[at + 1u], 1u);
                atomicMax(counts[at + 2u], 1u);
            }
            """).replace("FINE_MARGIN", Float.toString(ClusterDag.FINE_MARGIN)).replace("COARSE_MARGIN", Float.toString(ClusterDag.COARSE_MARGIN));

    /** The second test: the maybes against this frame's pyramid; those it does not hide join the camera's list. */
    static final String RETEST_SHADER = "#version 450\nlayout(local_size_x = 64) in;\n" + COMMON_GLSL + OCCLUSION_GLSL + """
            void main() {
                uint id = gl_GlobalInvocationID.y * job.rowWidth + gl_GlobalInvocationID.x;
                uint slot = 4u + job.countSlot * 4u;
                if (id >= min(counts[slot + 2u], job.viewMax)) return;
                uvec2 maybe = maybes[job.viewBase + id];
                Cluster c = clusters[maybe.x];
                mat4 m = modelOf(maybe.y);
                float scale = scaleOf(m);
                vec3 w = (m * vec4(c.bounds.xyz, 1.0)).xyz;
                if (!occluded(w, c.bounds.w * scale)) drawInView(c, maybe.y);
            }
            """;

    /** The pyramid's first level: the farthest depth of each 2 by 2 pixels of the depth picture. */
    static final String PYRAMID_FIRST = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(set = 0, binding = 0) uniform sampler2D depth;
            layout(std430, set = 0, binding = 1) writeonly buffer Pyramid { float hiz[]; };
            layout(push_constant) uniform Level { uint srcW; uint srcH; uint dstW; uint dstH; uint dstOffset; } level;
            void main() {
                uvec2 t = gl_GlobalInvocationID.xy;
                if (t.x >= level.dstW || t.y >= level.dstH) return;
                ivec2 s = ivec2(t * 2u), edge = ivec2(level.srcW - 1u, level.srcH - 1u);
                float d = max(max(texelFetch(depth, min(s, edge), 0).r, texelFetch(depth, min(s + ivec2(1, 0), edge), 0).r),
                              max(texelFetch(depth, min(s + ivec2(0, 1), edge), 0).r, texelFetch(depth, min(s + ivec2(1, 1), edge), 0).r));
                hiz[level.dstOffset + t.y * level.dstW + t.x] = d;
            }
            """;

    /** Every further level: the farthest of 2 by 2 texels of the level below. */
    static final String PYRAMID_NEXT = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(std430, set = 0, binding = 1) buffer Pyramid { float hiz[]; };
            layout(push_constant) uniform Level { uint srcW; uint srcH; uint dstW; uint dstH; uint dstOffset; uint srcOffset; } level;
            float at(uvec2 p) {
                p = min(p, uvec2(level.srcW - 1u, level.srcH - 1u));
                return hiz[level.srcOffset + p.y * level.srcW + p.x];
            }
            void main() {
                uvec2 t = gl_GlobalInvocationID.xy;
                if (t.x >= level.dstW || t.y >= level.dstH) return;
                uvec2 s = t * 2u;
                hiz[level.dstOffset + t.y * level.dstW + t.x] = max(max(at(s), at(s + uvec2(1, 0))), max(at(s + uvec2(0, 1)), at(s + uvec2(1, 1))));
            }
            """;

    /** A graph in GPU memory: its clusters for the compute shader, and every cluster's triangles as one index buffer. */
    static final class GpuClusters {
        /** The clusters, then the nodes of the hierarchy in the same shape. */
        final GpuBuffer clusters, indices;
        final int count, leaves;
        /** The hierarchy's roots, depths, and most nodes at a depth below the roots. */
        final int roots, depths, widest;
        long lastUsed;

        GpuClusters(GpuBuffer clusters, GpuBuffer indices, int count, int leaves, ClusterDag.Tree tree) {
            this.clusters = clusters;
            this.indices = indices;
            this.count = count;
            this.leaves = leaves;
            roots = tree.roots();
            depths = tree.depths();
            widest = tree.widest();
        }
    }

    /** Where a group's two lists are in a frame's buffers. */
    static final class Lists {
        GpuClusters clusters;
        long viewOffset, shadowOffset, countOffset;
        int viewMax, shadowMax;
        /** Whether the hierarchy is walked (else every cluster of every record is tested), and the walk's place in the buffers. */
        boolean walked;
        long queueBase;
        int queueMax, argsBase;
    }

    /** One group's part of a frame's work. */
    record Job(ClusterDag dag, int first, int records, int viewRecords, float maxPixels, float shadowMaxPixels, Lists lists) {
    }

    /** One render call's buffers, used again every frame (one set per {@code SceneRenderer.Resources}). */
    static final class Frame implements AutoCloseable {
        GpuBuffer commands, counts, maybes, view, readback, queue;
        long commandCapacity, countCapacity, queueCapacity;
        /** What the readback said: the triangles drawn in view a few frames ago, and whether a list ran short then. */
        long triangles;
        boolean overflowed;
        /** The depth the occluders are drawn into, and the camera block it is drawn with. */
        RenderTarget depth;
        GpuBuffer depthView;
        /** Two pyramids, this frame's and last frame's, and the blocks that say where they are. */
        final GpuBuffer[] pyramids = new GpuBuffer[2];
        final long[] pyramidFloats = new long[2];
        final GpuBuffer[] occlusionBlocks = new GpuBuffer[2];
        /** The pyramid this frame builds; the other is last frame's, or none when {@link #previousValid} is false. */
        int current;
        boolean previousValid;
        /** Last frame's camera and pyramid layout, written into the block the first test reads. */
        final ByteBuffer previousBlock = MemoryUtil.memAlloc(OCCLUSION_BLOCK_BYTES).order(ByteOrder.nativeOrder());
        /** The jobs of this frame, for the second test. */
        List<Job> jobs;
        boolean occlusion;

        @Override
        public void close() {
            for (GpuBuffer b : new GpuBuffer[]{commands, counts, maybes, view, readback, queue, depthView, pyramids[0], pyramids[1],
                    occlusionBlocks[0], occlusionBlocks[1]}) {
                if (b != null) b.close();
            }
            if (depth != null) depth.close();
            commands = counts = maybes = view = readback = queue = depthView = null;
            pyramids[0] = pyramids[1] = occlusionBlocks[0] = occlusionBlocks[1] = null;
            depth = null;
            MemoryUtil.memFree(previousBlock);
        }
    }

    private final GpuDevice device;
    private final Map<ClusterDag, GpuClusters> uploaded = new IdentityHashMap<>();
    private ComputePipeline cut, walk, retest, pyramidFirst, pyramidNext;
    /** The jobs that walked it so far, for tests. */
    static volatile long walks;
    /** The most queue entries a frame's walks hold together (16 MB). */
    private static final long MAX_QUEUE = 1L << 21;
    /**
     * The fewest cluster tests (records times clusters) a job must have to walk the hierarchy: about 0.07 ms of testing every
     * cluster on a 4090 (measured 0.39 ms for 23 million), past the few hundredths of a millisecond the walk's passes add.
     */
    static final long WALK_FROM = 1L << 22;
    /** {@link #WALK_FROM}, which tests set to 0 (always walk) or to the largest long (never) to compare the two. */
    static volatile long walkFrom = WALK_FROM;
    /** Bound where a shader declares a pyramid but there is none yet. */
    private GpuBuffer emptyPyramid;
    private final ByteBuffer push = MemoryUtil.memAlloc(128).order(ByteOrder.nativeOrder());
    private final ByteBuffer block = MemoryUtil.memAlloc(Math.max(VIEW_BLOCK_BYTES, OCCLUSION_BLOCK_BYTES)).order(ByteOrder.nativeOrder());
    private final ByteBuffer totals = MemoryUtil.memAlloc(TOTALS_BYTES).order(ByteOrder.nativeOrder());
    private boolean warnedShort;

    VirtualGeometry(GpuDevice device) {
        this.device = device;
    }

    /** The graph's GPU copy, uploaded the first time. */
    GpuClusters gpu(ClusterDag dag, long frame) {
        GpuClusters g = uploaded.get(dag);
        if (g == null) {
            int n = dag.size();
            ClusterDag.Tree tree = dag.tree;
            ByteBuffer data = MemoryUtil.memAlloc((n + tree.size()) * CLUSTER_BYTES).order(ByteOrder.nativeOrder());
            ByteBuffer indices = MemoryUtil.memAlloc(dag.indices.length * 4).order(ByteOrder.nativeOrder());
            try {
                int leaves = 0;
                for (int i = 0; i < n; i++) {
                    for (int k = 0; k < 4; k++) data.putFloat(dag.sphere[i * 4 + k]);
                    for (int k = 0; k < 4; k++) data.putFloat(dag.parentSphere[i * 4 + k]);
                    data.putFloat(dag.error[i]);
                    // The top of the graph has nothing coarser: -1 says so (an infinity might not survive the shader's maths).
                    data.putFloat(dag.parentError[i] == Float.POSITIVE_INFINITY ? -1f : dag.parentError[i]);
                    data.putInt(dag.first[i]).putInt(dag.count[i]);
                    for (int k = 0; k < 4; k++) data.putFloat(dag.bounds[i * 4 + k]);
                    data.putInt(0).putInt(0).putInt(0).putInt(0);
                    if (dag.level[i] == 0) leaves++;
                }
                // The nodes, in the clusters' shape (see WALK_SHADER).
                for (int i = 0; i < tree.size(); i++) {
                    for (int k = 0; k < 4; k++) data.putFloat(tree.ownSphere()[i * 4 + k]);
                    for (int k = 0; k < 4; k++) data.putFloat(tree.lodSphere()[i * 4 + k]);
                    data.putFloat(tree.minError()[i]);
                    data.putFloat(tree.maxParentError()[i] == Float.POSITIVE_INFINITY ? -1f : tree.maxParentError()[i]);
                    data.putInt(tree.first()[i]).putInt(tree.count()[i] | (tree.leaf()[i] ? 0x80000000 : 0));
                    for (int k = 0; k < 4; k++) data.putFloat(tree.bounds()[i * 4 + k]);
                    data.putInt(tree.under()[i * 2]).putInt(tree.under()[i * 2 + 1]).putInt(0).putInt(0);
                }
                data.flip();
                for (int i : dag.indices) indices.putInt(i);
                indices.flip();
                g = new GpuClusters(device.createBuffer(BufferType.STORAGE, data), device.createBuffer(BufferType.INDEX, indices), n, leaves, tree);
            } finally {
                MemoryUtil.memFree(data);
                MemoryUtil.memFree(indices);
            }
            uploaded.put(dag, g);
        }
        g.lastUsed = frame;
        return g;
    }

    /**
     * Gives each job its place in the frame's buffers (as many commands as it could ever need, a draw per finest cluster per
     * record, or the same share of that for every list once the frame's total is reached), then picks the clusters: by
     * walking the hierarchy a depth at a time, or, for a job its queues have no room for, by testing every cluster of every
     * record.
     *
     * @param planes        the camera's view volume, six planes {x, y, z, w}
     * @param pixelsPerUnit pixels one unit covers at distance 1
     * @param occlusion     whether to make the camera's list in two steps, with {@link #occlusionTarget} and {@link #retest}
     *                      to follow
     */
    void cut(Frame f, List<Job> jobs, float[] planes, me.piitex.engine.render3d.math.Vec3 eye, float pixelsPerUnit, GpuBuffer records,
             long frame, boolean occlusion) {
        if (cut == null) cut = device.createComputePipeline(CUT_SHADER);
        if (emptyPyramid == null) emptyPyramid = device.createStorageBuffer(16);
        // What the GPU counted when this frame's buffers were last used, before this frame's work replaces it.
        if (f.readback != null) {
            totals.clear();
            f.readback.read(totals);
            f.triangles = totals.getInt(0) & 0xFFFFFFFFL;
            f.overflowed = totals.getInt(4) != 0;
            if (f.overflowed && !warnedShort) {
                warnedShort = true;
                org.apache.logging.log4j.LogManager.getLogger("Engine").warn(
                        "Virtual geometry asked for more than {} cluster draws in a frame; some clusters were left out.", MAX_COMMANDS);
            }
        }
        long commands = 0, queue = 0;
        // Each walked job's counts per depth (x, y and z of the dispatch, then the entries queued) follow the jobs' own.
        int argsAt = TOTALS_BYTES / 4 + 4 * jobs.size(), depths = 0;
        long wanted = 0, queues = 0;
        for (Job job : jobs) {
            GpuClusters g = gpu(job.dag(), frame);
            Lists lists = job.lists();
            lists.clusters = g;
            wanted += (long) job.viewRecords() * g.leaves + (Double.isNaN(job.shadowMaxPixels()) ? 0 : (long) job.records() * g.leaves);
            // Walked where testing every cluster would be a big dispatch: below it, the walk's passes cost more than they save.
            lists.walked = (long) job.records() * g.count >= walkFrom;
            // A depth of the walk queues at most every record times the nodes at it.
            if (lists.walked) queues += 2L * job.records() * g.widest;
        }
        // Past the frame's total, every list gets the same share of what it could need, so none is left with no room. A
        // queue too short for a depth has its node's thread test what does not fit itself.
        double share = wanted <= MAX_COMMANDS ? 1 : (double) MAX_COMMANDS / wanted;
        double queueShare = queues <= MAX_QUEUE ? 1 : (double) MAX_QUEUE / queues;
        for (Job job : jobs) {
            Lists lists = job.lists();
            GpuClusters g = lists.clusters;
            lists.viewMax = (int) ((long) job.viewRecords() * g.leaves * share);
            lists.viewOffset = commands * COMMAND_BYTES;
            commands += lists.viewMax;
            lists.shadowMax = Double.isNaN(job.shadowMaxPixels()) ? 0 : (int) ((long) job.records() * g.leaves * share);
            lists.shadowOffset = commands * COMMAND_BYTES;
            commands += lists.shadowMax;
            if (lists.walked) {
                // In one row of work groups.
                long room = Math.min(65535L * GROUP_SIZE, (long) ((long) job.records() * g.widest * queueShare));
                lists.queueBase = queue;
                lists.queueMax = (int) room;
                queue += 2 * room;
                lists.argsBase = argsAt;
                argsAt += 4 * g.depths;
                depths = Math.max(depths, g.depths);
            }
        }
        long countBytes = 4L * argsAt;
        if (f.commands == null || f.commandCapacity < commands * COMMAND_BYTES) {
            if (f.commands != null) f.commands.close();
            if (f.maybes != null) f.maybes.close();
            f.commandCapacity = Math.max(commands * COMMAND_BYTES, Math.max(f.commandCapacity * 2, 64L * 1024));
            f.commandCapacity = Math.min(f.commandCapacity, (long) MAX_COMMANDS * COMMAND_BYTES);
            f.commands = device.createStorageBuffer(f.commandCapacity);
            // A maybe is 8 bytes, at the place of a command of the camera's list.
            f.maybes = device.createStorageBuffer(f.commandCapacity / COMMAND_BYTES * 8);
        }
        if (f.counts == null || f.countCapacity < countBytes) {
            if (f.counts != null) f.counts.close();
            f.countCapacity = Math.max(countBytes, Math.max(f.countCapacity * 2, 1024));
            f.counts = device.createStorageBuffer(f.countCapacity);
        }
        if (queue > 0 && (f.queue == null || f.queueCapacity < queue * 8)) {
            if (f.queue != null) f.queue.close();
            f.queueCapacity = Math.max(queue * 8, Math.max(f.queueCapacity * 2, 64L * 1024));
            f.queue = device.createStorageBuffer(f.queueCapacity);
        }
        if (depths > 0 && walk == null) walk = device.createComputePipeline(WALK_SHADER);
        if (f.view == null) f.view = device.createDynamicBuffer(BufferType.UNIFORM, VIEW_BLOCK_BYTES);
        if (f.readback == null) f.readback = device.createReadbackBuffer(TOTALS_BYTES);
        for (int i = 0; i < 2; i++) {
            if (f.occlusionBlocks[i] == null) f.occlusionBlocks[i] = device.createDynamicBuffer(BufferType.UNIFORM, OCCLUSION_BLOCK_BYTES);
        }
        block.clear();
        for (int i = 0; i < 24; i++) block.putFloat(planes[i]);
        block.putFloat(eye.x()).putFloat(eye.y()).putFloat(eye.z()).putFloat(pixelsPerUnit);
        block.flip();
        f.view.update(block);
        // The first test reads last frame's pyramid, with the camera of last frame.
        boolean previous = occlusion && f.previousValid;
        GpuBuffer previousBlock = f.occlusionBlocks[1 - f.current];
        if (previous) {
            f.previousBlock.clear();
            previousBlock.update(f.previousBlock);
        } else {
            previousBlock.update(emptyOcclusionBlock());
        }
        f.jobs = jobs;
        f.occlusion = occlusion;

        try (ComputePass pass = device.beginCompute()) {
            pass.fill(f.counts, 0, countBytes, 0);
            pass.barrier();
            pass.bindPipeline(cut);
            pass.bindStorageBuffer(1, records);
            pass.bindStorageBuffer(2, f.commands);
            pass.bindStorageBuffer(3, f.counts);
            pass.bindUniformBuffer(4, f.view);
            pass.bindStorageBuffer(5, previous ? f.pyramids[1 - f.current] : emptyPyramid);
            pass.bindUniformBuffer(6, previousBlock);
            pass.bindStorageBuffer(7, f.maybes);
            for (int j = 0; j < jobs.size(); j++) {
                Job job = jobs.get(j);
                Lists lists = job.lists();
                lists.countOffset = TOTALS_BYTES + 16L * j;
                long threads = (long) job.records() * lists.clusters.count;
                if (threads == 0 || lists.walked) continue;
                int[] xy = groups(threads);
                pass.bindStorageBuffer(0, lists.clusters.clusters);
                pushJob(j, job, lists, xy[0], occlusion, 0);
                pass.pushConstants(push);
                pass.dispatch(xy[0], xy[1], 1);
            }
            // The walks, a depth at a time over every walked job: the roots of every record, then what each depth queued.
            if (depths > 0) {
                pass.bindPipeline(walk);
                pass.bindStorageBuffer(9, f.queue != null ? f.queue : emptyPyramid); // no queue when no depth needs one
                for (int d = 0; d < depths; d++) {
                    if (d > 0) pass.barrier();
                    for (int j = 0; j < jobs.size(); j++) {
                        Job job = jobs.get(j);
                        Lists lists = job.lists();
                        if (!lists.walked || job.records() == 0 || d >= lists.clusters.depths) continue;
                        if (d == 0) walks++;
                        pass.bindStorageBuffer(0, lists.clusters.clusters);
                        if (d == 0) {
                            int[] xy = groups((long) job.records() * lists.clusters.roots);
                            pushJob(j, job, lists, xy[0], occlusion, 0);
                            pass.pushConstants(push);
                            pass.dispatch(xy[0], xy[1], 1);
                        } else {
                            pushJob(j, job, lists, 0, occlusion, d);
                            pass.pushConstants(push);
                            pass.dispatchIndirect(f.counts, 4L * (lists.argsBase + 4 * d));
                        }
                    }
                }
            }
            if (!occlusion) {
                pass.barrier();
                pass.copy(f.counts, 0, f.readback, 0, TOTALS_BYTES);
            }
        }
    }

    private ByteBuffer emptyOcclusionBlock() {
        block.clear();
        for (int i = 0; i < OCCLUSION_BLOCK_BYTES; i++) block.put((byte) 0);
        block.flip();
        return block;
    }

    private static int[] groups(long threads) {
        long groups = (threads + GROUP_SIZE - 1) / GROUP_SIZE;
        int x = (int) Math.min(groups, 65535), y = (int) ((groups + x - 1) / x);
        return new int[]{x, y};
    }

    private void pushJob(int j, Job job, Lists lists, int groupsX, boolean occlusion, int depth) {
        push.clear();
        push.putInt(job.first()).putInt(job.records()).putInt(job.viewRecords()).putInt(lists.clusters.count);
        push.putInt((int) (lists.viewOffset / COMMAND_BYTES)).putInt(lists.viewMax);
        push.putInt((int) (lists.shadowOffset / COMMAND_BYTES)).putInt(lists.shadowMax);
        push.putInt(j).putFloat(job.maxPixels()).putFloat(Double.isNaN(job.shadowMaxPixels()) ? 0f : job.shadowMaxPixels())
                .putInt(groupsX * GROUP_SIZE).putInt(occlusion ? 1 : 0);
        push.putInt(depth).putInt(lists.clusters.roots).putInt((int) lists.queueBase).putInt(lists.queueMax).putInt(lists.argsBase);
        push.flip();
    }

    /**
     * The depth picture the occluders are drawn into for the second test, at the size the scene is drawn, and the camera
     * block to draw them with: {@code viewProj}, then 1, near, far, 0, as the scene's depth pass has it.
     */
    RenderTarget occlusionTarget(Frame f, int width, int height, ByteBuffer cameraBlock, int blockSize) {
        if (f.depth == null || f.depth.width() != width || f.depth.height() != height) {
            if (f.depth != null) f.depth.close();
            f.depth = device.createDepthSampledTarget(width, height, false, false);
            f.previousValid = false; // last frame's pyramid has another size
        }
        if (f.depthView == null) f.depthView = device.createDynamicBuffer(BufferType.UNIFORM, blockSize);
        f.depthView.update(cameraBlock);
        return f.depth;
    }

    /**
     * Makes the pyramid of {@link #occlusionTarget}'s depth, tests the maybes of {@link #cut} against it, and keeps it (with the
     * camera it was drawn with) for the next frame's first test.
     *
     * @param viewProj the camera the depth was drawn with, column-major
     */
    void retest(Frame f, GpuBuffer records, float[] viewProj) {
        if (retest == null) retest = device.createComputePipeline(RETEST_SHADER);
        if (pyramidFirst == null) pyramidFirst = device.createComputePipeline(PYRAMID_FIRST);
        if (pyramidNext == null) pyramidNext = device.createComputePipeline(PYRAMID_NEXT);
        int w = f.depth.width(), h = f.depth.height();
        // The levels: half the picture each way (rounded up), then half of that, down to one texel.
        int[][] levels = new int[MAX_LEVELS][3];
        int count = 0;
        long floats = 0;
        int lw = w, lh = h;
        do {
            lw = (lw + 1) / 2;
            lh = (lh + 1) / 2;
            levels[count] = new int[]{lw, lh, (int) floats};
            floats += (long) lw * lh;
            count++;
        } while ((lw > 1 || lh > 1) && count < MAX_LEVELS);
        int cur = f.current;
        if (f.pyramids[cur] == null || f.pyramidFloats[cur] < floats) {
            if (f.pyramids[cur] != null) f.pyramids[cur].close();
            f.pyramids[cur] = device.createStorageBuffer(floats * 4);
            f.pyramidFloats[cur] = floats;
        }
        block.clear();
        for (float v : viewProj) block.putFloat(v);
        block.putInt(count).putInt(w).putInt(h).putInt(1);
        for (int i = 0; i < MAX_LEVELS; i++) block.putInt(levels[i][0]).putInt(levels[i][1]).putInt(levels[i][2]).putInt(0);
        block.flip();
        f.occlusionBlocks[cur].update(block);
        // Next frame's first test reads this one, with this frame's camera.
        block.rewind();
        f.previousBlock.clear();
        f.previousBlock.put(block).flip();
        block.rewind();

        try (ComputePass pass = device.beginCompute()) {
            pass.bindPipeline(pyramidFirst);
            pass.bindTexture(0, f.depth.depthTexture());
            pass.bindStorageBuffer(1, f.pyramids[cur]);
            pushLevel(w, h, levels[0], 0);
            pass.pushConstants(push);
            pass.dispatch((levels[0][0] + 7) / 8, (levels[0][1] + 7) / 8, 1);
            if (count > 1) {
                pass.bindPipeline(pyramidNext);
                pass.bindStorageBuffer(1, f.pyramids[cur]);
                for (int i = 1; i < count; i++) {
                    pass.barrier();
                    pushLevel(levels[i - 1][0], levels[i - 1][1], levels[i], levels[i - 1][2]);
                    pass.pushConstants(push);
                    pass.dispatch((levels[i][0] + 7) / 8, (levels[i][1] + 7) / 8, 1);
                }
            }
            pass.barrier();
            pass.bindPipeline(retest);
            pass.bindStorageBuffer(1, records);
            pass.bindStorageBuffer(2, f.commands);
            pass.bindStorageBuffer(3, f.counts);
            pass.bindStorageBuffer(5, f.pyramids[cur]);
            pass.bindUniformBuffer(6, f.occlusionBlocks[cur]);
            pass.bindStorageBuffer(7, f.maybes);
            for (int j = 0; j < f.jobs.size(); j++) {
                Job job = f.jobs.get(j);
                Lists lists = job.lists();
                if (lists.viewMax == 0) continue;
                int[] xy = groups(lists.viewMax);
                pass.bindStorageBuffer(0, lists.clusters.clusters);
                pushJob(j, job, lists, xy[0], true, 0);
                pass.pushConstants(push);
                pass.dispatch(xy[0], xy[1], 1);
            }
            pass.barrier();
            pass.copy(f.counts, 0, f.readback, 0, TOTALS_BYTES);
        }
        f.previousValid = true;
        f.current = 1 - cur;
        f.jobs = null;
    }

    private void pushLevel(int srcW, int srcH, int[] dst, int srcOffset) {
        push.clear();
        push.putInt(srcW).putInt(srcH).putInt(dst[0]).putInt(dst[1]).putInt(dst[2]).putInt(srcOffset);
        push.flip();
    }

    /** The view's or the shadows' list of a group, drawn with the bound vertex and instance buffers. */
    void draw(RenderPass pass, Frame f, Lists lists, boolean shadows) {
        int max = shadows ? lists.shadowMax : lists.viewMax;
        if (max == 0) return;
        pass.bindIndexBuffer(lists.clusters.indices);
        pass.drawIndexedIndirectCount(f.commands, shadows ? lists.shadowOffset : lists.viewOffset, f.counts,
                lists.countOffset + (shadows ? 4 : 0), max);
    }

    /** Frees the graphs no frame has drawn for a while. */
    void sweep(long frame) {
        for (Iterator<GpuClusters> it = uploaded.values().iterator(); it.hasNext(); ) {
            GpuClusters g = it.next();
            if (frame - g.lastUsed > UNUSED_FRAMES) {
                g.clusters.close();
                g.indices.close();
                it.remove();
            }
        }
    }

    @Override
    public void close() {
        for (GpuClusters g : uploaded.values()) {
            g.clusters.close();
            g.indices.close();
        }
        uploaded.clear();
        for (ComputePipeline p : new ComputePipeline[]{cut, walk, retest, pyramidFirst, pyramidNext}) if (p != null) p.close();
        cut = walk = retest = pyramidFirst = pyramidNext = null;
        if (emptyPyramid != null) emptyPyramid.close();
        MemoryUtil.memFree(push);
        MemoryUtil.memFree(block);
        MemoryUtil.memFree(totals);
    }
}
