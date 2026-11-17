package me.piitex.engine.render3d.vk;

import me.piitex.engine.render3d.BufferType;
import me.piitex.engine.render3d.GpuBuffer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;

import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memCopy;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.util.vma.Vma.vmaFlushAllocation;
import static org.lwjgl.vulkan.VK10.*;

final class VkBuf implements GpuBuffer {
    private final VkGpuDevice device;
    private final BufferType type;
    private final long size;
    private final VkGpuDevice.Alloc[] allocations;
    private final boolean dynamic;
    /**
     * Dynamic buffers only: how many bytes were last written to each frame slot's copy and which update that was, so a
     * slot that missed an update can be brought up to date before it is read.
     */
    private final long[] slotVersion;
    private final long[] slotBytes;
    private long version;
    private int latestSlot = -1;
    private boolean closed;

    /** How a buffer's memory is kept. */
    enum Kind {
        /** Contents given once, in the GPU's own memory. */
        FIXED,
        /** Written by the CPU every frame: a mapped copy per frame in flight. */
        DYNAMIC,
        /** Written only by the GPU, in its own memory. */
        GPU,
        /** Copied into by the GPU and read by the CPU: a mapped copy per frame in flight. */
        READBACK
    }

    private final Kind kind;

    VkBuf(VkGpuDevice device, BufferType type, long size, ByteBuffer data) {
        this(device, type, size, data, data == null ? Kind.DYNAMIC : Kind.FIXED);
    }

    VkBuf(VkGpuDevice device, BufferType type, long size, ByteBuffer data, Kind kind) {
        this.device = device;
        this.type = type;
        this.size = size;
        this.kind = kind;
        int usage = switch (type) {
            // A vertex buffer can be read as storage too: the records of instances, by the compute shader that picks clusters.
            case VERTEX -> VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | (device.supportsVirtualGeometry() ? VK_BUFFER_USAGE_STORAGE_BUFFER_BIT : 0);
            case INDEX -> VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
            case UNIFORM -> VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
            case STORAGE -> VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
                    | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        };
        dynamic = kind == Kind.DYNAMIC || kind == Kind.READBACK;
        if (kind == Kind.DYNAMIC) {
            // One copy per frame in flight, so writing this frame's never touches what the GPU is still reading.
            allocations = new VkGpuDevice.Alloc[VkGpuDevice.FRAMES];
            for (int i = 0; i < allocations.length; i++) allocations[i] = device.createMappedBuffer(size, usage);
            slotVersion = new long[VkGpuDevice.FRAMES];
            slotBytes = new long[VkGpuDevice.FRAMES];
        } else if (kind == Kind.READBACK) {
            allocations = new VkGpuDevice.Alloc[VkGpuDevice.FRAMES];
            for (int i = 0; i < allocations.length; i++) {
                allocations[i] = device.createBuffer(size, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT | org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
                org.lwjgl.system.MemoryUtil.memSet(allocations[i].mapped(), 0, size);
            }
            slotVersion = null;
            slotBytes = null;
        } else if (kind == Kind.GPU) {
            slotVersion = null;
            slotBytes = null;
            allocations = new VkGpuDevice.Alloc[]{device.createBuffer(size, usage, 0)};
        } else {
            slotVersion = null;
            slotBytes = null;
            VkGpuDevice.Alloc staging = device.createMappedBuffer(size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            VkGpuDevice.Alloc local = device.createBuffer(size, usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT, 0);
            device.upload(staging, data);
            device.upload(cmd -> {
                try (MemoryStack stack = stackPush()) {
                    vkCmdCopyBuffer(cmd, staging.handle(), local.handle(), VkBufferCopy.calloc(1, stack).size(size));
                }
            }, () -> vmaDestroyBuffer(device.allocator, staging.handle(), staging.allocation()));
            allocations = new VkGpuDevice.Alloc[]{local};
        }
    }

    /**
     * The VkBuffer to bind in the device's current frame. A dynamic buffer's copy for this frame slot is brought up to
     * date first if the slot missed the latest {@link #update}: a frame that skips updating still draws what was last
     * written, not what was written two frames ago.
     */
    long handle() {
        if (!dynamic) return allocations[0].handle();
        int slot = device.slot;
        if (kind == Kind.READBACK) return allocations[slot].handle();
        if (latestSlot >= 0 && slotVersion[slot] != version) {
            VkGpuDevice.Alloc from = allocations[latestSlot], to = allocations[slot];
            memCopy(from.mapped(), to.mapped(), slotBytes[latestSlot]);
            vmaFlushAllocation(device.allocator, to.allocation(), 0, VK_WHOLE_SIZE);
            slotVersion[slot] = version;
            slotBytes[slot] = slotBytes[latestSlot];
        }
        return allocations[slot].handle();
    }

    @Override
    public BufferType type() {
        return type;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public void read(ByteBuffer into) {
        if (kind != Kind.READBACK) throw new UnsupportedOperationException("Only a readback buffer can be read");
        if (into.remaining() > size) throw new IllegalArgumentException("More than the buffer holds");
        // This frame slot's copy: the GPU finished writing it the last time the slot was used, before beginFrame() went on.
        VkGpuDevice.Alloc a = allocations[device.slot];
        org.lwjgl.util.vma.Vma.vmaInvalidateAllocation(device.allocator, a.allocation(), 0, VK_WHOLE_SIZE);
        memCopy(a.mapped(), org.lwjgl.system.MemoryUtil.memAddress(into), into.remaining());
    }

    @Override
    public void update(ByteBuffer data) {
        if (kind != Kind.DYNAMIC) throw new IllegalStateException("This buffer was made with fixed contents");
        if (!device.frameActive) throw new IllegalStateException("Update dynamic buffers between beginFrame() and endFrame()");
        if (data.remaining() > size) throw new IllegalArgumentException("Data is larger than the buffer");
        int slot = device.slot;
        device.upload(allocations[slot], data);
        version++;
        slotVersion[slot] = version;
        slotBytes[slot] = data.remaining();
        latestSlot = slot;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        VkGpuDevice.Alloc[] toFree = allocations;
        device.destroyLater(() -> {
            for (VkGpuDevice.Alloc a : toFree) vmaDestroyBuffer(device.allocator, a.handle(), a.allocation());
        });
    }
}
