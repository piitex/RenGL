package me.piitex.engine.render3d.vk;

import me.piitex.engine.render3d.RayGeometry;
import me.piitex.engine.render3d.RayInstance;
import me.piitex.engine.render3d.RayScene;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.util.vma.Vma.vmaFlushAllocation;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Ray tracing for a device without ray queries: the hierarchy a ray walks is built here, on the CPU, and lives in storage
 * buffers that the fragment shaders of {@code SoftRayShaders} read. It is the same two levels the hardware has.
 * <p>
 * A {@link RayGeometry} is a bottom level: its triangles in a {@link SoftBvh}, built once and kept in the one buffer all
 * geometries share (the arena, which grows by copying itself into a bigger one). A {@link RayScene} is a top level: the instances
 * placed in it, with a hierarchy over their boxes rebuilt on every update, in a small buffer of its own for each frame in flight.
 * <p>
 * The words of both are the shader's to read; this file and {@code SoftRayShaders} are the two ends of the same layout.
 * <pre>
 * a geometry   [0] where the triangles' corners start, [1] where their indices do, [2] where the vertices do (all counted from
 *              the start of the geometry), [3] floats to a vertex; then the nodes of its hierarchy, nine floats of corners for
 *              each triangle in leaf order, three vertex numbers for each, and the vertices as they were given
 * a scene      [0] how many instances, [1] where the nodes of the hierarchy start, [2] where the instances do, [3] where the
 *              records do; then the nodes; then for each instance (sixteen words) the three rows of the matrix that takes the
 *              world into the geometry, the place of the geometry in the arena and the ray mask; then for each instance
 *              (twenty-four words) the place of the geometry again followed by the {@link RayInstance#data()}
 * </pre>
 * The instances are written in the order the hierarchy holds them, so an instance's number is its place in both lists.
 */
final class VkSoftRay {
    /** Words in front of a geometry's nodes. */
    static final int GEOMETRY_HEADER = 4;
    static final int SCENE_HEADER = 8;
    static final int INSTANCE_WORDS = 16;
    static final int RECORD_WORDS = 24;
    private static final int STORAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    /** 4 MB to begin with. */
    private static final long FIRST_ARENA_WORDS = 1L << 20;
    /** The largest the arena grows: the offsets in the scene's words are ints, and a shader indexes with them. */
    private static final long ARENA_LIMIT_WORDS = 1L << 28;

    private final VkGpuDevice device;
    private final long maxWords;
    private VkGpuDevice.Alloc arena;
    private long arenaWords;
    private long used;
    /** Gaps left by geometries that were freed, as {offset, size} in words. */
    private final List<long[]> gaps = new ArrayList<>();

    VkSoftRay(VkGpuDevice device) {
        this.device = device;
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(device.physical, properties);
            maxWords = Math.min(ARENA_LIMIT_WORDS, Integer.toUnsignedLong(properties.limits().maxStorageBufferRange()) / 4);
        }
    }

    // ---- the arena -------------------------------------------------------------------------------------------------

    private void ensureArena() {
        if (arena == null) grow(FIRST_ARENA_WORDS);
    }

    /** A bigger buffer with everything the old one held; the old one goes once the frames that may be reading it are done. */
    private void grow(long needed) {
        long words = Math.max(FIRST_ARENA_WORDS, arenaWords * 2);
        while (words < needed) words *= 2;
        if (words > maxWords) {
            if (needed > maxWords) {
                throw new IllegalStateException("The triangles of the scene need " + (needed * 4 >> 20) + " MB, more than a storage buffer of this GPU holds ("
                        + (maxWords * 4 >> 20) + " MB)");
            }
            words = maxWords;
        }
        VkGpuDevice.Alloc fresh = device.createBuffer(words * 4, STORAGE, 0);
        VkGpuDevice.Alloc old = arena;
        if (old != null) {
            long bytes = used * 4;
            device.upload(cmd -> {
                try (MemoryStack stack = stackPush()) {
                    // What earlier uploads wrote into the old buffer is read now.
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                            VkMemoryBarrier.calloc(1, stack).sType$Default()
                                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT), null, null);
                    if (bytes > 0) vkCmdCopyBuffer(cmd, old.handle(), fresh.handle(), VkBufferCopy.calloc(1, stack).size(bytes));
                }
            }, () -> {
            });
            device.destroyLater(() -> vmaDestroyBuffer(device.allocator, old.handle(), old.allocation()));
        }
        arena = fresh;
        arenaWords = words;
    }

    /** Room for {@code words} words, as the word they start at. */
    private long allocate(long words) {
        ensureArena();
        for (int i = 0; i < gaps.size(); i++) {
            long[] gap = gaps.get(i);
            if (gap[1] < words) continue;
            long at = gap[0];
            if (gap[1] == words) {
                gaps.remove(i);
            } else {
                gap[0] += words;
                gap[1] -= words;
            }
            return at;
        }
        if (used + words > arenaWords) grow(used + words);
        long at = used;
        used += words;
        return at;
    }

    private void release(long at, long words) {
        if (at + words == used) {
            used = at;
            return;
        }
        gaps.add(new long[]{at, words});
    }

    private void write(long at, int[] words) {
        long bytes = (long) words.length * 4;
        VkGpuDevice.Alloc staging = device.createMappedBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        MemoryUtil.memIntBuffer(staging.mapped(), words.length).put(words);
        vmaFlushAllocation(device.allocator, staging.allocation(), 0, VK_WHOLE_SIZE);
        device.upload(cmd -> {
            try (MemoryStack stack = stackPush()) {
                vkCmdCopyBuffer(cmd, staging.handle(), arena.handle(), VkBufferCopy.calloc(1, stack).dstOffset(at * 4).size(bytes));
            }
        }, () -> vmaDestroyBuffer(device.allocator, staging.handle(), staging.allocation()));
    }

    void close() {
        if (arena != null) vmaDestroyBuffer(device.allocator, arena.handle(), arena.allocation());
        arena = null;
    }

    // ---- bottom level ----------------------------------------------------------------------------------------------

    RayGeometry geometry(float[] vertices, int vertexCount, int floatsPerVertex, int[] indices) {
        if (vertexCount <= 0 || indices.length < 3) throw new IllegalArgumentException("A ray geometry needs triangles");
        if (floatsPerVertex < 3) throw new IllegalArgumentException("A vertex holds at least a position");
        int triangles = indices.length / 3;
        float[] boxes = new float[triangles * 6];
        for (int t = 0; t < triangles; t++) {
            for (int a = 0; a < 3; a++) {
                float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
                for (int j = 0; j < 3; j++) {
                    int index = indices[t * 3 + j];
                    if (index < 0 || index >= vertexCount) throw new IllegalArgumentException("Triangle " + t + " uses vertex " + index + " of " + vertexCount);
                    float p = vertices[index * floatsPerVertex + a];
                    low = Math.min(low, p);
                    high = Math.max(high, p);
                }
                boxes[t * 6 + a] = low;
                boxes[t * 6 + 3 + a] = high;
            }
        }
        SoftBvh bvh = SoftBvh.build(boxes, triangles, 4);
        long nodeWords = (long) bvh.nodeCount * SoftBvh.NODE_WORDS;
        long cornerAt = GEOMETRY_HEADER + nodeWords, indexAt = cornerAt + triangles * 9L, vertexAt = indexAt + triangles * 3L;
        long total = vertexAt + (long) vertexCount * floatsPerVertex;
        if (total > maxWords) throw new IllegalArgumentException("This mesh needs " + (total * 4 >> 20) + " MB of storage for ray tracing, more than this GPU allows");
        int[] blob = new int[(int) total];
        blob[0] = (int) cornerAt;
        blob[1] = (int) indexAt;
        blob[2] = (int) vertexAt;
        blob[3] = floatsPerVertex;
        System.arraycopy(bvh.nodes, 0, blob, GEOMETRY_HEADER, (int) nodeWords);
        for (int k = 0; k < triangles; k++) {
            int source = bvh.order[k];
            for (int j = 0; j < 3; j++) {
                int index = indices[source * 3 + j];
                blob[(int) indexAt + k * 3 + j] = index;
                for (int a = 0; a < 3; a++) blob[(int) cornerAt + k * 9 + j * 3 + a] = Float.floatToRawIntBits(vertices[index * floatsPerVertex + a]);
            }
        }
        for (int i = 0; i < vertexCount * floatsPerVertex; i++) blob[(int) vertexAt + i] = Float.floatToRawIntBits(vertices[i]);
        long at = allocate(total);
        write(at, blob);
        return new Geometry(at, total, triangles, new float[]{bvh.minX(), bvh.minY(), bvh.minZ(), bvh.maxX(), bvh.maxY(), bvh.maxZ()});
    }

    final class Geometry implements RayGeometry {
        final long at;
        private final long words;
        private final int triangles;
        /** The box round the triangles, in the geometry's own space. */
        final float[] box;
        private boolean closed;

        Geometry(long at, long words, int triangles, float[] box) {
            this.at = at;
            this.words = words;
            this.triangles = triangles;
            this.box = box;
        }

        @Override
        public long vertexAddress() {
            return 0L;
        }

        @Override
        public long indexAddress() {
            return 0L;
        }

        @Override
        public int triangleCount() {
            return triangles;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            device.destroyLater(() -> release(at, words));
        }
    }

    // ---- top level -------------------------------------------------------------------------------------------------

    RayScene scene(int capacity) {
        ensureArena();
        return new Scene(Math.max(1, capacity));
    }

    final class Scene implements RayScene {
        private final int capacity;
        private final long totalWords;
        private final int instanceAt, recordAt;
        private final VkGpuDevice.Alloc[] buffers = new VkGpuDevice.Alloc[VkGpuDevice.FRAMES];
        private boolean closed;

        Scene(int capacity) {
            this.capacity = capacity;
            // A tree over n boxes has at most 2n - 1 nodes.
            instanceAt = SCENE_HEADER + 2 * capacity * SoftBvh.NODE_WORDS;
            recordAt = instanceAt + capacity * INSTANCE_WORDS;
            totalWords = (long) recordAt + (long) capacity * RECORD_WORDS;
            for (int i = 0; i < buffers.length; i++) buffers[i] = device.createMappedBuffer(totalWords * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        }

        @Override
        public int capacity() {
            return capacity;
        }

        @Override
        public long dataAddress() {
            return 0L;
        }

        /** The buffer of the frame being recorded, for a descriptor to point at. */
        long buffer() {
            return buffers[device.slot].handle();
        }

        long bufferSize() {
            return totalWords * 4;
        }

        long geometryBuffer() {
            return arena.handle();
        }

        long geometryBufferSize() {
            return arenaWords * 4;
        }

        @Override
        public void update(List<RayInstance> placed) {
            if (!device.frameActive) throw new IllegalStateException("Update a ray scene between beginFrame() and endFrame()");
            int slot = device.slot;
            int limit = Math.min(placed.size(), capacity);
            float[] boxes = new float[limit * 6];
            float[][] inverses = new float[limit][];
            RayInstance[] live = new RayInstance[limit];
            int n = 0;
            for (int i = 0; i < limit; i++) {
                RayInstance instance = placed.get(i);
                if (!(instance.geometry() instanceof Geometry geometry)) throw new IllegalArgumentException("Not a geometry of this device: " + instance.geometry());
                float[] inverse = invert(instance.transform());
                // A flattened instance (a scale of nothing) can not be hit, and has no inverse to take a ray into it.
                if (inverse == null) continue;
                worldBox(instance.transform(), geometry.box, boxes, n * 6);
                inverses[n] = inverse;
                live[n] = instance;
                n++;
            }
            IntBuffer words = MemoryUtil.memIntBuffer(buffers[slot].mapped(), (int) totalWords);
            words.put(0, n).put(1, SCENE_HEADER).put(2, instanceAt).put(3, recordAt);
            if (n > 0) {
                SoftBvh tlas = SoftBvh.build(boxes, n, 2);
                for (int i = 0; i < tlas.nodeCount * SoftBvh.NODE_WORDS; i++) words.put(SCENE_HEADER + i, tlas.nodes[i]);
                for (int k = 0; k < n; k++) {
                    int j = tlas.order[k];
                    RayInstance instance = live[j];
                    int at = instanceAt + k * INSTANCE_WORDS;
                    float[] inverse = inverses[j];
                    for (int w = 0; w < 12; w++) words.put(at + w, Float.floatToRawIntBits(inverse[w]));
                    int base = (int) ((Geometry) instance.geometry()).at;
                    words.put(at + 12, base).put(at + 13, instance.mask()).put(at + 14, 0).put(at + 15, 0);
                    int record = recordAt + k * RECORD_WORDS;
                    words.put(record, base).put(record + 1, 0).put(record + 2, 0).put(record + 3, 0);
                    float[] data = instance.data();
                    for (int w = 0; w < RayInstance.DATA_FLOATS; w++) {
                        words.put(record + 4 + w, Float.floatToRawIntBits(data != null && w < data.length ? data[w] : 0f));
                    }
                }
            }
            vmaFlushAllocation(device.allocator, buffers[slot].allocation(), 0, VK_WHOLE_SIZE);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            device.destroyLater(() -> {
                for (VkGpuDevice.Alloc buffer : buffers) vmaDestroyBuffer(device.allocator, buffer.handle(), buffer.allocation());
            });
        }
    }

    // ---- matrices --------------------------------------------------------------------------------------------------

    /**
     * The inverse of a column-major 4x4 that moves and turns and scales (its last row is 0 0 0 1), as the three rows of its upper
     * part, or null where it can not be undone.
     */
    static float[] invert(float[] m) {
        double a = m[0], b = m[4], c = m[8], d = m[1], e = m[5], f = m[9], g = m[2], h = m[6], i = m[10];
        double co0 = e * i - f * h, co1 = f * g - d * i, co2 = d * h - e * g;
        double det = a * co0 + b * co1 + c * co2;
        if (!(Math.abs(det) > 1e-20)) return null;
        double s = 1.0 / det;
        double[] r = {
                co0 * s, (c * h - b * i) * s, (b * f - c * e) * s,
                co1 * s, (a * i - c * g) * s, (c * d - a * f) * s,
                co2 * s, (b * g - a * h) * s, (a * e - b * d) * s};
        double tx = m[12], ty = m[13], tz = m[14];
        float[] out = new float[12];
        for (int row = 0; row < 3; row++) {
            out[row * 4] = (float) r[row * 3];
            out[row * 4 + 1] = (float) r[row * 3 + 1];
            out[row * 4 + 2] = (float) r[row * 3 + 2];
            out[row * 4 + 3] = (float) -(r[row * 3] * tx + r[row * 3 + 1] * ty + r[row * 3 + 2] * tz);
        }
        for (float v : out) if (!Float.isFinite(v)) return null;
        return out;
    }

    /** The box round the eight corners of a box after the matrix moved them, written to {@code into} at {@code at}. */
    static void worldBox(float[] m, float[] box, float[] into, int at) {
        float lx = Float.POSITIVE_INFINITY, ly = lx, lz = lx, hx = Float.NEGATIVE_INFINITY, hy = hx, hz = hx;
        for (int corner = 0; corner < 8; corner++) {
            float x = box[(corner & 1) == 0 ? 0 : 3], y = box[(corner & 2) == 0 ? 1 : 4], z = box[(corner & 4) == 0 ? 2 : 5];
            float wx = m[0] * x + m[4] * y + m[8] * z + m[12];
            float wy = m[1] * x + m[5] * y + m[9] * z + m[13];
            float wz = m[2] * x + m[6] * y + m[10] * z + m[14];
            lx = Math.min(lx, wx);
            ly = Math.min(ly, wy);
            lz = Math.min(lz, wz);
            hx = Math.max(hx, wx);
            hy = Math.max(hy, wy);
            hz = Math.max(hz, wz);
        }
        into[at] = lx;
        into[at + 1] = ly;
        into[at + 2] = lz;
        into[at + 3] = hx;
        into[at + 4] = hy;
        into[at + 5] = hz;
    }
}
