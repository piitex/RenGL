package me.piitex.engine.render3d.vk;

import me.piitex.engine.render3d.RayGeometry;
import me.piitex.engine.render3d.RayInstance;
import me.piitex.engine.render3d.RayScene;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.util.vma.Vma.vmaFlushAllocation;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;

/**
 * Builds the acceleration structures a ray query traces through, on a device made with ray queries
 * ({@code VulkanContext.rayTracing()}). The structures and the buffers they are built from live in memory the GPU can
 * address; building is recorded as an upload, so it runs ahead of the next pass in the same submission.
 * <p>
 * A {@link RayGeometry} is a bottom-level structure, built once. A {@link RayScene} is a top-level one, rebuilt every frame
 * from the instances placed in it, one copy per frame in flight.
 */
final class VkRay {
    /** A transform of 12 floats, three rows of a column-major 4x4's upper part. */
    private static final int INSTANCE_BYTES = 64;
    private static final int ADDRESSED = VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    /** Vertices and indices are read by the build, and kept for the shaders that read what a ray hit through their address. */
    private static final int BUILD_INPUT = ADDRESSED | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
    /** One record of the table a scene keeps for its instances: two addresses, then the instance's data. */
    private static final int RECORD_BYTES = 16 + RayInstance.DATA_FLOATS * 4;
    private static final int STRUCTURE_STORAGE = ADDRESSED | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
    private static final int SCRATCH = ADDRESSED | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

    private final VkGpuDevice device;
    private final long scratchAlignment;

    VkRay(VkGpuDevice device) {
        this.device = device;
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceAccelerationStructurePropertiesKHR properties = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            vkGetPhysicalDeviceProperties2(device.physical, VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(properties.address()));
            scratchAlignment = Math.max(16, properties.minAccelerationStructureScratchOffsetAlignment());
        }
    }

    private long addressOf(long buffer) {
        try (MemoryStack stack = stackPush()) {
            return vkGetBufferDeviceAddress(device.device, VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
        }
    }

    /** A host-writable buffer for the GPU to read while it builds. */
    private VkGpuDevice.Alloc input(long size) {
        return device.createMappedBuffer(size, BUILD_INPUT);
    }

    private void free(VkGpuDevice.Alloc buffer) {
        vmaDestroyBuffer(device.allocator, buffer.handle(), buffer.allocation());
    }

    /** The address of a scratch buffer made {@code size + alignment} long, rounded up to the alignment builds need. */
    private long alignedAddress(VkGpuDevice.Alloc scratch) {
        long address = addressOf(scratch.handle());
        return (address + scratchAlignment - 1) & -scratchAlignment;
    }

    private long createStructure(MemoryStack stack, long buffer, long size, int type) {
        VkAccelerationStructureCreateInfoKHR info = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                .buffer(buffer).offset(0).size(size).type(type);
        LongBuffer out = stack.mallocLong(1);
        VkGpuDevice.check(vkCreateAccelerationStructureKHR(device.device, info, null, out), "create an acceleration structure");
        return out.get(0);
    }

    private long structureAddress(MemoryStack stack, long structure) {
        return vkGetAccelerationStructureDeviceAddressKHR(device.device,
                VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(structure));
    }

    private void barrier(VkCommandBuffer cmd, int srcStage, int dstStage, int srcAccess, int dstAccess) {
        try (MemoryStack stack = stackPush()) {
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess), null, null);
        }
    }

    // ---- bottom level ----------------------------------------------------------------------------------------------

    RayGeometry geometry(float[] vertices, int vertexCount, int floatsPerVertex, int[] indices) {
        if (vertexCount <= 0 || indices.length < 3) throw new IllegalArgumentException("A ray geometry needs triangles");
        if (floatsPerVertex < 3) throw new IllegalArgumentException("A vertex holds at least a position");
        int triangles = indices.length / 3;
        VkGpuDevice.Alloc vertexBuffer = input((long) vertexCount * floatsPerVertex * 4L);
        VkGpuDevice.Alloc indexBuffer = input((long) triangles * 12L);
        MemoryUtil.memFloatBuffer(vertexBuffer.mapped(), vertexCount * floatsPerVertex).put(vertices, 0, vertexCount * floatsPerVertex);
        MemoryUtil.memIntBuffer(indexBuffer.mapped(), triangles * 3).put(indices, 0, triangles * 3);
        vmaFlushAllocation(device.allocator, vertexBuffer.allocation(), 0, VK_WHOLE_SIZE);
        vmaFlushAllocation(device.allocator, indexBuffer.allocation(), 0, VK_WHOLE_SIZE);

        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureGeometryTrianglesDataKHR data = VkAccelerationStructureGeometryTrianglesDataKHR.calloc(stack).sType$Default()
                    .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexData(d -> d.deviceAddress(addressOf(vertexBuffer.handle())))
                    .vertexStride((long) floatsPerVertex * 4L)
                    .maxVertex(vertexCount - 1)
                    .indexType(VK_INDEX_TYPE_UINT32)
                    .indexData(d -> d.deviceAddress(addressOf(indexBuffer.handle())));
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR)
                    .geometry().triangles(data);
            VkAccelerationStructureBuildGeometryInfoKHR build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack).sType$Default()
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .pGeometries(geometry)
                    .geometryCount(1);
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(device.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, build,
                    stack.ints(triangles), sizes);

            VkGpuDevice.Alloc storage = device.createBuffer(sizes.accelerationStructureSize(), STRUCTURE_STORAGE, 0);
            VkGpuDevice.Alloc scratch = device.createBuffer(sizes.buildScratchSize() + scratchAlignment, SCRATCH, 0);
            long structure = createStructure(stack, storage.handle(), sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            long address = structureAddress(stack, structure);
            build.dstAccelerationStructure(structure).scratchData(d -> d.deviceAddress(alignedAddress(scratch)));

            device.upload(cmd -> {
                try (MemoryStack inner = stackPush()) {
                    PointerBuffer ranges = inner.pointers(VkAccelerationStructureBuildRangeInfoKHR.calloc(1, inner).primitiveCount(triangles).address());
                    vkCmdBuildAccelerationStructuresKHR(cmd, VkAccelerationStructureBuildGeometryInfoKHR.create(build.address(), 1), ranges);
                }
                // Whatever builds a top level over this one, in this command buffer or a later one, sees it finished.
                barrier(cmd, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
            }, () -> free(scratch));
            return new Geometry(structure, address, storage, triangles, vertexBuffer, indexBuffer,
                    addressOf(vertexBuffer.handle()), addressOf(indexBuffer.handle()));
        }
    }

    final class Geometry implements RayGeometry {
        private final long structure;
        final long address;
        private final VkGpuDevice.Alloc storage;
        private final int triangles;
        private final VkGpuDevice.Alloc vertexBuffer, indexBuffer;
        private final long vertexAddress, indexAddress;
        private boolean closed;

        Geometry(long structure, long address, VkGpuDevice.Alloc storage, int triangles, VkGpuDevice.Alloc vertexBuffer,
                 VkGpuDevice.Alloc indexBuffer, long vertexAddress, long indexAddress) {
            this.structure = structure;
            this.address = address;
            this.storage = storage;
            this.triangles = triangles;
            this.vertexBuffer = vertexBuffer;
            this.indexBuffer = indexBuffer;
            this.vertexAddress = vertexAddress;
            this.indexAddress = indexAddress;
        }

        @Override
        public long vertexAddress() {
            return vertexAddress;
        }

        @Override
        public long indexAddress() {
            return indexAddress;
        }

        @Override
        public int triangleCount() {
            return triangles;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            device.destroyLater(() -> {
                vkDestroyAccelerationStructureKHR(device.device, structure, null);
                free(storage);
                free(vertexBuffer);
                free(indexBuffer);
            });
        }
    }

    // ---- top level -------------------------------------------------------------------------------------------------

    RayScene scene(int capacity) {
        return new Scene(Math.max(1, capacity));
    }

    final class Scene implements RayScene {
        private final int capacity;
        private final VkGpuDevice.Alloc[] instances = new VkGpuDevice.Alloc[VkGpuDevice.FRAMES];
        private final VkGpuDevice.Alloc[] storage = new VkGpuDevice.Alloc[VkGpuDevice.FRAMES];
        private final VkGpuDevice.Alloc[] scratch = new VkGpuDevice.Alloc[VkGpuDevice.FRAMES];
        private final VkGpuDevice.Alloc[] table = new VkGpuDevice.Alloc[VkGpuDevice.FRAMES];
        private final long[] tableAddress = new long[VkGpuDevice.FRAMES];
        private final long[] structures = new long[VkGpuDevice.FRAMES];
        private boolean closed;

        Scene(int capacity) {
            this.capacity = capacity;
            try (MemoryStack stack = stackPush()) {
                VkAccelerationStructureGeometryKHR.Buffer geometry = instanceGeometry(stack, 0L);
                VkAccelerationStructureBuildGeometryInfoKHR build = buildInfo(stack, geometry);
                VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
                vkGetAccelerationStructureBuildSizesKHR(device.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, build,
                        stack.ints(capacity), sizes);
                for (int i = 0; i < structures.length; i++) {
                    instances[i] = input((long) capacity * INSTANCE_BYTES);
                    table[i] = input((long) capacity * RECORD_BYTES);
                    tableAddress[i] = addressOf(table[i].handle());
                    storage[i] = device.createBuffer(sizes.accelerationStructureSize(), STRUCTURE_STORAGE, 0);
                    scratch[i] = device.createBuffer(sizes.buildScratchSize() + scratchAlignment, SCRATCH, 0);
                    structures[i] = createStructure(stack, storage[i].handle(), sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
                }
            }
        }

        private VkAccelerationStructureGeometryKHR.Buffer instanceGeometry(MemoryStack stack, long address) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            VkAccelerationStructureGeometryInstancesDataKHR data = VkAccelerationStructureGeometryInstancesDataKHR.calloc(stack).sType$Default()
                    .arrayOfPointers(false).data(d -> d.deviceAddress(address));
            geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR)
                    .geometry().instances(data);
            return geometry;
        }

        private VkAccelerationStructureBuildGeometryInfoKHR buildInfo(MemoryStack stack, VkAccelerationStructureGeometryKHR.Buffer geometry) {
            return VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack).sType$Default()
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .pGeometries(geometry)
                    .geometryCount(1);
        }

        @Override
        public int capacity() {
            return capacity;
        }

        @Override
        public long dataAddress() {
            return tableAddress[device.slot];
        }

        /** The structure of the frame being recorded, for a descriptor to point at. */
        long structure() {
            return structures[device.slot];
        }

        @Override
        public void update(List<RayInstance> placed) {
            if (!device.frameActive) throw new IllegalStateException("Update a ray scene between beginFrame() and endFrame()");
            int slot = device.slot;
            int count = Math.min(placed.size(), capacity);
            VkAccelerationStructureInstanceKHR.Buffer buffer = VkAccelerationStructureInstanceKHR.create(instances[slot].mapped(), capacity);
            java.nio.ByteBuffer records = MemoryUtil.memByteBuffer(table[slot].mapped(), capacity * RECORD_BYTES).order(java.nio.ByteOrder.nativeOrder());
            for (int i = 0; i < count; i++) {
                RayInstance instance = placed.get(i);
                float[] m = instance.transform();
                VkAccelerationStructureInstanceKHR slotInstance = buffer.get(i);
                FloatBuffer rows = slotInstance.transform().matrix();
                // The upper 3x4 of the column-major matrix, row by row.
                for (int row = 0; row < 3; row++) {
                    for (int column = 0; column < 4; column++) rows.put(row * 4 + column, m[column * 4 + row]);
                }
                Geometry geometry = (Geometry) instance.geometry();
                int at = i * RECORD_BYTES;
                records.putLong(at, geometry.vertexAddress).putLong(at + 8, geometry.indexAddress);
                float[] data = instance.data();
                for (int k = 0; k < RayInstance.DATA_FLOATS; k++) records.putFloat(at + 16 + k * 4, data != null && k < data.length ? data[k] : 0f);
                slotInstance.instanceCustomIndex(i).mask(instance.mask()).instanceShaderBindingTableRecordOffset(0)
                        // Shadows come from closed meshes seen from either side, so no triangle is skipped for facing away.
                        .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                        .accelerationStructureReference(geometry.address);
            }
            vmaFlushAllocation(device.allocator, instances[slot].allocation(), 0, VK_WHOLE_SIZE);
            vmaFlushAllocation(device.allocator, table[slot].allocation(), 0, VK_WHOLE_SIZE);

            device.upload(cmd -> {
                try (MemoryStack stack = stackPush()) {
                    VkAccelerationStructureGeometryKHR.Buffer geometry = instanceGeometry(stack, addressOf(instances[slot].handle()));
                    VkAccelerationStructureBuildGeometryInfoKHR build = buildInfo(stack, geometry)
                            .dstAccelerationStructure(structures[slot])
                            .scratchData(d -> d.deviceAddress(alignedAddress(scratch[slot])));
                    // After the bottom levels built so far, and after the last build that used this scratch memory.
                    barrier(cmd, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_HOST_BIT,
                            VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                            VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR | VK_ACCESS_HOST_WRITE_BIT,
                            VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
                    PointerBuffer ranges = stack.pointers(VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack).primitiveCount(count).address());
                    vkCmdBuildAccelerationStructuresKHR(cmd, VkAccelerationStructureBuildGeometryInfoKHR.create(build.address(), 1), ranges);
                    // The shaders that trace through it wait for the build.
                    barrier(cmd, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                            VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                }
            }, () -> {
            });
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            device.destroyLater(() -> {
                for (int i = 0; i < structures.length; i++) {
                    vkDestroyAccelerationStructureKHR(device.device, structures[i], null);
                    free(storage[i]);
                    free(scratch[i]);
                    free(instances[i]);
                    free(table[i]);
                }
            });
        }
    }
}
