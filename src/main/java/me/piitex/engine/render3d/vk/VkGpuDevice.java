package me.piitex.engine.render3d.vk;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.gl.renderer.vulkan.VulkanContext;
import me.piitex.engine.render3d.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;

/**
 * {@link GpuDevice} on Vulkan, sharing the device and queue of the window's {@link VulkanContext}, which is also the
 * device Skia draws with. 3D work and Skia's work go to the same queue, so submission order is what orders them.
 * <p>
 * Frames: {@link #beginFrame()} waits for the frame that used the same slot two frames ago, then resets that slot's
 * command and descriptor pools; {@link #endFrame()} submits an empty batch carrying the slot's fence. Passes submit
 * their own command buffers when they end. Closing a buffer, texture, pipeline or target defers its destruction until
 * the GPU is done with every frame that could have used it.
 * <p>
 * Clip space: Vulkan's Y points down, so passes use a viewport with a negative height, which flips Y and makes clip
 * space match OpenGL's (+Y up). Depth stays 0..1. Needs Vulkan 1.1 for the negative height.
 */
public final class VkGpuDevice implements GpuDevice {
    static final int FRAMES = 2;
    static final int COLOR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    static final int HDR_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    /**
     * Sets in each descriptor pool. A frame that needs more gets another pool, so this is a step, not a ceiling.
     */
    private static final int SETS_PER_POOL = 1024;
    /**
     * A set holds a few uniform buffers and up to this many samplers; the pools are sized for that.
     */
    private static final int BUFFERS_PER_SET = 4;
    private static final int SAMPLERS_PER_SET = 8;

    record Alloc(long handle, long allocation, long mapped) {
    }

    private record Deferred(long frame, Runnable action) {
    }

    final org.lwjgl.vulkan.VkDevice device;
    final VkPhysicalDevice physical;
    final VkQueue queue;
    final int family;
    final long allocator;
    final int depthFormat;
    final int depthAspect;
    /**
     * The sampler for ordinary textures: linear, repeating, and anisotropic where the GPU can.
     */
    final long sampler;
    /**
     * Samplers for render targets that are read as textures: clamped to the edge, nearest or linear.
     */
    final long clampNearestSampler, clampLinearSampler;
    private final int maxAnisotropy;
    /**
     * Whether the colour format can be shrunk by {@code vkCmdBlitImage}, which is how mipmaps are made.
     */
    final boolean canBlitColor;
    /**
     * The same for the 16-bit float colour format of HDR textures.
     */
    final boolean canBlitHdr;

    private final String name;
    private final LongSupplier frames;
    private final long uploadPool;
    private final long[] commandPools = new long[FRAMES];
    private final java.util.List<java.util.List<Long>> descriptorPools = new java.util.ArrayList<>(); // per slot
    private final int[] activePool = new int[FRAMES];
    private VkCommandBuffer pendingUploads;
    private final long[] fences = new long[FRAMES];
    private final java.util.Map<Integer, Long> renderPasses = new java.util.HashMap<>(); // by samples, depth and sampled
    private final int maxSamples;
    private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
    private boolean closed;

    int slot;
    long frameCounter;
    boolean frameActive;

    public VkGpuDevice(VulkanContext context, LongSupplier frames) {
        this.frames = frames;
        if (VK_API_VERSION_MAJOR(context.apiVersion()) == 1 && VK_API_VERSION_MINOR(context.apiVersion()) < 1) {
            throw new IllegalStateException("The 3D layer needs Vulkan 1.1 or newer; this GPU reports "
                    + VK_API_VERSION_MAJOR(context.apiVersion()) + "." + VK_API_VERSION_MINOR(context.apiVersion()));
        }
        device = context.vkDevice();
        physical = context.vkPhysicalDevice();
        queue = context.vkQueue();
        family = context.queueFamily();
        name = context.gpuName();
        try (MemoryStack stack = stackPush()) {
            depthFormat = pickDepthFormat(stack);
            depthAspect = depthFormat == VK_FORMAT_D32_SFLOAT ? VK_IMAGE_ASPECT_DEPTH_BIT
                    : VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
            VkFormatProperties colorProperties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(physical, COLOR_FORMAT, colorProperties);
            int blitFeatures = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT
                    | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            canBlitColor = (colorProperties.optimalTilingFeatures() & blitFeatures) == blitFeatures;
            VkFormatProperties hdrProperties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(physical, HDR_FORMAT, hdrProperties);
            canBlitHdr = (hdrProperties.optimalTilingFeatures() & blitFeatures) == blitFeatures;
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            int supported = properties.limits().framebufferColorSampleCounts() & properties.limits().framebufferDepthSampleCounts();
            int most = 1;
            for (int n = 2; n <= 8; n *= 2) if ((supported & n) != 0) most = n; // VK_SAMPLE_COUNT_n_BIT is n
            maxSamples = most;
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(physical, features);
            // The window's context turns on every feature the GPU has, so reported support is enabled support.
            float limit = features.samplerAnisotropy() ? properties.limits().maxSamplerAnisotropy() : 1f;
            maxAnisotropy = (int) Math.max(1f, Math.min(limit, Integer.getInteger("rengl.3d.anisotropy", 8)));

            VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack).set(context.vkInstance(), device);
            VmaAllocatorCreateInfo info = VmaAllocatorCreateInfo.calloc(stack)
                    .physicalDevice(physical)
                    .device(device)
                    .instance(context.vkInstance())
                    .pVulkanFunctions(functions)
                    .vulkanApiVersion(VK_API_VERSION_1_1);
            PointerBuffer pAllocator = stack.mallocPointer(1);
            check(vmaCreateAllocator(info, pAllocator), "create the memory allocator");
            allocator = pAllocator.get(0);

            uploadPool = createCommandPool(stack, VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
            for (int i = 0; i < FRAMES; i++) {
                commandPools[i] = createCommandPool(stack, 0);

                descriptorPools.add(new java.util.ArrayList<>(java.util.List.of(createDescriptorPool(stack))));
                LongBuffer out = stack.mallocLong(1);

                VkFenceCreateInfo fence = VkFenceCreateInfo.calloc(stack).sType$Default()
                        .flags(VK_FENCE_CREATE_SIGNALED_BIT);
                check(vkCreateFence(device, fence, null, out), "create a fence");
                fences[i] = out.get(0);
            }

            sampler = createSampler(stack, VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_REPEAT, maxAnisotropy);
            clampNearestSampler = createSampler(stack, VK_FILTER_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, 1);
            clampLinearSampler = createSampler(stack, VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, 1);
        }
    }

    private long createSampler(MemoryStack stack, int filter, int address, int anisotropy) {
        VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(filter).minFilter(filter)
                .mipmapMode(filter == VK_FILTER_LINEAR ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(address).addressModeV(address).addressModeW(address)
                .anisotropyEnable(anisotropy > 1).maxAnisotropy(anisotropy)
                .maxLod(VK_LOD_CLAMP_NONE)
                .borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK);
        LongBuffer out = stack.mallocLong(1);
        check(vkCreateSampler(device, info, null, out), "create a sampler");
        return out.get(0);
    }

    private long createDescriptorPool(MemoryStack stack) {
        VkDescriptorPoolCreateInfo pool = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(SETS_PER_POOL)
                .pPoolSizes(VkDescriptorPoolSize.calloc(2, stack)
                        .apply(0, s -> s.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(SETS_PER_POOL * BUFFERS_PER_SET))
                        .apply(1, s -> s.type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SETS_PER_POOL * SAMPLERS_PER_SET)));
        LongBuffer out = stack.mallocLong(1);
        check(vkCreateDescriptorPool(device, pool, null, out), "create a descriptor pool");
        return out.get(0);
    }

    /**
     * A descriptor set from the frame's pools, valid until the slot's next {@link #beginFrame()}. When the pool in use
     * is full the frame moves on to another, so the number of draws that bind different resources is not capped.
     */
    long allocateDescriptorSet(MemoryStack stack, long layout) {
        java.util.List<Long> pools = descriptorPools.get(slot);
        LongBuffer set = stack.mallocLong(1);
        while (true) {
            VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pools.get(activePool[slot])).pSetLayouts(stack.longs(layout));
            int result = vkAllocateDescriptorSets(device, info, set);
            if (result == VK_SUCCESS) return set.get(0);
            if (result != org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY && result != VK_ERROR_FRAGMENTED_POOL) {
                check(result, "allocate a descriptor set");
            }
            if (++activePool[slot] == pools.size()) pools.add(createDescriptorPool(stack));
        }
    }

    private int pickDepthFormat(MemoryStack stack) {
        for (int format : new int[]{VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT}) {
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(physical, format, properties);
            if ((properties.optimalTilingFeatures() & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) return format;
        }
        throw new IllegalStateException("This GPU has no usable depth format");
    }

    private long createCommandPool(MemoryStack stack, int flags) {
        VkCommandPoolCreateInfo info = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                .flags(flags).queueFamilyIndex(family);
        LongBuffer out = stack.mallocLong(1);
        check(vkCreateCommandPool(device, info, null, out), "create a command pool");
        return out.get(0);
    }

    // ---- GpuDevice ----------------------------------------------------------------------------------------------

    @Override
    public RenderAPI api() {
        return RenderAPI.VULKAN;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public long frameNumber() {
        return frames.getAsLong();
    }

    @Override
    public void beginFrame() {
        if (frameActive) throw new IllegalStateException("beginFrame() called twice");
        frameActive = true;
        frameCounter++;
        check(vkWaitForFences(device, fences[slot], true, Long.MAX_VALUE), "wait for the frame fence");
        check(vkResetFences(device, fences[slot]), "reset the frame fence");
        check(vkResetCommandPool(device, commandPools[slot], 0), "reset the command pool");
        for (long pool : descriptorPools.get(slot)) check(vkResetDescriptorPool(device, pool, 0), "reset a descriptor pool");
        activePool[slot] = 0;
        // Frames up to counter-FRAMES are finished now (this slot's fence covered the one before).
        while (!deferred.isEmpty() && deferred.peekFirst().frame <= frameCounter - FRAMES) {
            deferred.pollFirst().action.run();
        }
    }

    @Override
    public void endFrame() {
        if (!frameActive) throw new IllegalStateException("endFrame() without beginFrame()");
        flushUploads();
        try (MemoryStack stack = stackPush()) {
            // Nothing to submit: the fence signals once every earlier submission on the queue has finished.
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default();
            check(vkQueueSubmit(queue, submit, fences[slot]), "submit the frame fence");
        }
        frameActive = false;
        slot = (slot + 1) % FRAMES;
    }

    @Override
    public GpuBuffer createBuffer(BufferType type, ByteBuffer data) {
        return new VkBuf(this, type, data.remaining(), data);
    }

    @Override
    public GpuBuffer createDynamicBuffer(BufferType type, long size) {
        return new VkBuf(this, type, size, null);
    }

    @Override
    public GpuTexture createTexture(int width, int height, ByteBuffer rgba, boolean mipmaps) {
        return new VkTex(this, width, height, 1, new ByteBuffer[]{rgba}, mipmaps && canBlitColor);
    }

    @Override
    public GpuTexture createHdrTexture(int width, int height, java.nio.FloatBuffer rgba, boolean mipmaps) {
        int count = width * height * 4;
        if (rgba.remaining() < count) throw new IllegalArgumentException("Not enough pixel data");
        ByteBuffer halves = org.lwjgl.system.MemoryUtil.memAlloc(count * 2).order(java.nio.ByteOrder.nativeOrder());
        try {
            for (int i = 0; i < count; i++) halves.putShort(Float.floatToFloat16(Math.min(rgba.get(rgba.position() + i), 65504f)));
            halves.flip();
            return new VkTex(this, width, height, 1, new ByteBuffer[]{halves}, mipmaps && canBlitHdr, HDR_FORMAT, 8);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(halves);
        }
    }

    @Override
    public GpuTexture createCubeTexture(int size, ByteBuffer[] faces, boolean mipmaps) {
        if (faces.length != 6) throw new IllegalArgumentException("A cube texture needs six faces");
        return new VkTex(this, size, size, 6, faces, mipmaps && canBlitColor);
    }

    @Override
    public Pipeline createPipeline(PipelineDesc desc) {
        return new VkPipe(this, desc);
    }

    @Override
    public RenderTarget createRenderTarget(int width, int height, boolean depth, int samples) {
        int wanted = Integer.highestOneBit(Math.max(1, samples));
        return new VkTarget(this, width, height, depth, Math.min(wanted, maxSamples), false, true);
    }

    @Override
    public RenderTarget createSampledTarget(int width, int height, boolean depth, boolean linear) {
        return new VkTarget(this, width, height, depth, 1, true, linear);
    }

    @Override
    public int maxAnisotropy() {
        return maxAnisotropy;
    }

    @Override
    public int maxSamples() {
        return maxSamples;
    }

    @Override
    public RenderPass beginPass(RenderTarget target, float r, float g, float b, float a) {
        if (!frameActive) throw new IllegalStateException("Call beginFrame() before beginPass()");
        return new VkPass(this, (VkTarget) target, r, g, b, a);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        vkDeviceWaitIdle(device);
        while (!deferred.isEmpty()) deferred.pollFirst().action.run();
        for (long renderPass : renderPasses.values()) vkDestroyRenderPass(device, renderPass, null);
        renderPasses.clear();
        vkDestroySampler(device, sampler, null);
        vkDestroySampler(device, clampNearestSampler, null);
        vkDestroySampler(device, clampLinearSampler, null);
        for (int i = 0; i < FRAMES; i++) {
            vkDestroyFence(device, fences[i], null);
            for (long pool : descriptorPools.get(i)) vkDestroyDescriptorPool(device, pool, null);
            vkDestroyCommandPool(device, commandPools[i], null);
        }
        vkDestroyCommandPool(device, uploadPool, null);
        vmaDestroyAllocator(allocator);
    }

    // ---- helpers for the other classes --------------------------------------------------------------------------

    static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan could not " + what + " (VkResult " + result + ")");
    }

    VkCommandBuffer allocateFrameCommandBuffer() {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo info = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPools[slot]).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer out = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, info, out), "allocate a command buffer");
            return new VkCommandBuffer(out.get(0), device);
        }
    }

    /**
     * Records and runs a one-off command buffer, waiting for it. For uploads and readbacks, not for frames.
     */
    void immediate(Consumer<VkCommandBuffer> record) {
        flushUploads();
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo info = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(uploadPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer out = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, info, out), "allocate an upload command buffer");
            VkCommandBuffer cmd = new VkCommandBuffer(out.get(0), device);
            try {
                check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin an upload command buffer");
                record.accept(cmd);
                check(vkEndCommandBuffer(cmd), "end an upload command buffer");
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd)),
                        VK_NULL_HANDLE), "submit an upload");
                check(vkQueueWaitIdle(queue), "wait for an upload");
            } finally {
                vkFreeCommandBuffers(device, uploadPool, cmd);
            }
        }
    }

    /**
     * Records an upload to run before the frame's next pass, instead of stopping to wait for the GPU: a mesh or texture
     * made in the middle of a frame costs the copy, not a stall. {@code cleanup} (freeing the staging memory) runs once
     * the GPU has finished with the frame. Outside a frame it is the plain blocking upload.
     */
    void upload(Consumer<VkCommandBuffer> record, Runnable cleanup) {
        if (!frameActive) {
            immediate(record);
            cleanup.run();
            return;
        }
        if (pendingUploads == null) {
            pendingUploads = allocateFrameCommandBuffer();
            try (MemoryStack stack = stackPush()) {
                check(vkBeginCommandBuffer(pendingUploads, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin the upload command buffer");
            }
        }
        record.accept(pendingUploads);
        destroyLater(cleanup);
    }

    /**
     * The finished upload commands to submit ahead of a pass, or null when nothing was uploaded since the last one.
     */
    VkCommandBuffer takeUploads() {
        VkCommandBuffer cmd = pendingUploads;
        if (cmd == null) return null;
        pendingUploads = null;
        try (MemoryStack stack = stackPush()) {
            // Copies are done before anything reads what they wrote: vertices, indices, uniforms and textures.
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0, VkMemoryBarrier.calloc(1, stack).sType$Default()
                            .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT
                                    | VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT),
                    null, null);
            check(vkEndCommandBuffer(cmd), "end the upload command buffer");
        }
        return cmd;
    }

    /**
     * Submits pending uploads on their own, for when no pass is about to carry them.
     */
    void flushUploads() {
        VkCommandBuffer cmd = takeUploads();
        if (cmd == null) return;
        try (MemoryStack stack = stackPush()) {
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd)),
                    VK_NULL_HANDLE), "submit uploads");
        }
    }

    Alloc createBuffer(long size, int usage, int vmaFlags) {
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(Math.max(size, 4)).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocation = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_AUTO).flags(vmaFlags);
            LongBuffer buffer = stack.mallocLong(1);
            PointerBuffer memory = stack.mallocPointer(1);
            VmaAllocationInfo result = VmaAllocationInfo.calloc(stack);
            check(vmaCreateBuffer(allocator, info, allocation, buffer, memory, result), "create a buffer");
            return new Alloc(buffer.get(0), memory.get(0), result.pMappedData());
        }
    }

    /**
     * A host-writable buffer that stays mapped, for staging uploads and per-frame uniforms.
     */
    Alloc createMappedBuffer(long size, int usage) {
        return createBuffer(size, usage,
                VMA_ALLOCATION_CREATE_MAPPED_BIT | VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
    }

    Alloc createImage(int width, int height, int format, int usage) {
        return createImage(width, height, format, usage, 1, VK_SAMPLE_COUNT_1_BIT);
    }

    Alloc createImage(int width, int height, int format, int usage, int mipLevels, int samples) {
        return createImage(width, height, format, usage, mipLevels, samples, 1);
    }

    /**
     * @param layers 1 for an ordinary image, 6 for a cube map
     */
    Alloc createImage(int width, int height, int format, int usage, int mipLevels, int samples, int layers) {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                    .flags(layers == 6 ? VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0)
                    .imageType(VK_IMAGE_TYPE_2D).format(format)
                    .extent(e -> e.width(width).height(height).depth(1))
                    .mipLevels(mipLevels).arrayLayers(layers).samples(samples)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            VmaAllocationCreateInfo allocation = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO);
            LongBuffer image = stack.mallocLong(1);
            PointerBuffer memory = stack.mallocPointer(1);
            check(vmaCreateImage(allocator, info, allocation, image, memory, null), "create an image");
            return new Alloc(image.get(0), memory.get(0), 0);
        }
    }

    long createImageView(long image, int format, int aspect) {
        return createImageView(image, format, aspect, 1);
    }

    long createImageView(long image, int format, int aspect, int levels) {
        return createImageView(image, format, aspect, levels, 1);
    }

    long createImageView(long image, int format, int aspect, int levels, int layers) {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(layers == 6 ? VK_IMAGE_VIEW_TYPE_CUBE : VK_IMAGE_VIEW_TYPE_2D).format(format)
                    .subresourceRange(r -> r.aspectMask(aspect).levelCount(levels).layerCount(layers));
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateImageView(device, info, null, out), "create an image view");
            return out.get(0);
        }
    }

    void upload(Alloc target, ByteBuffer data) {
        upload(target, 0, data);
    }

    void upload(Alloc target, long offset, ByteBuffer data) {
        memCopy(memAddress(data), target.mapped() + offset, data.remaining());
        vmaFlushAllocation(allocator, target.allocation(), 0, VK_WHOLE_SIZE);
    }

    void barrier(VkCommandBuffer cmd, long image, int aspect, int oldLayout, int newLayout,
                 int srcStage, int dstStage, int srcAccess, int dstAccess) {
        barrier(cmd, image, aspect, oldLayout, newLayout, srcStage, dstStage, srcAccess, dstAccess, 0, 1);
    }

    void barrier(VkCommandBuffer cmd, long image, int aspect, int oldLayout, int newLayout,
                 int srcStage, int dstStage, int srcAccess, int dstAccess, int baseMip, int levels) {
        barrier(cmd, image, aspect, oldLayout, newLayout, srcStage, dstStage, srcAccess, dstAccess, baseMip, levels, 1);
    }

    void barrier(VkCommandBuffer cmd, long image, int aspect, int oldLayout, int newLayout,
                 int srcStage, int dstStage, int srcAccess, int dstAccess, int baseMip, int levels, int layers) {
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                    .oldLayout(oldLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .subresourceRange(r -> r.aspectMask(aspect).baseMipLevel(baseMip).levelCount(levels).layerCount(layers));
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    /**
     * The render pass every target of this kind is drawn with. It clears on load, and leaves the colour image in
     * {@code COLOR_ATTACHMENT_OPTIMAL}: the layout the Skia wrapper declares. It starts from {@code UNDEFINED}, which is
     * how it takes the image back from Skia, which leaves it in {@code TRANSFER_SRC_OPTIMAL}.
     * <p>
     * With more than one sample the attachments are the multisampled colour (0), the single-sample colour it resolves
     * into (1) and the multisampled depth (2). Without, they are the colour (0) and the depth (1). The multisampled
     * images are only ever written within the pass, so their contents are not kept.
     */
    long renderPass(boolean depth, int samples) {
        return renderPass(depth, samples, false);
    }

    /**
     * @param sampled the colour image is read by shaders afterwards, so it is left in {@code SHADER_READ_ONLY_OPTIMAL}
     *                instead (single sample only; such a target is never shown by the UI layer)
     */
    long renderPass(boolean depth, int samples, boolean sampled) {
        int key = (samples << 2) | (sampled ? 2 : 0) | (depth ? 1 : 0);
        Long existing = renderPasses.get(key);
        if (existing != null) return existing;
        boolean multisampled = samples > 1;
        int sampleBit = samples; // VK_SAMPLE_COUNT_n_BIT is n
        try (MemoryStack stack = stackPush()) {
            int count = (multisampled ? 2 : 1) + (depth ? 1 : 0);
            int depthIndex = multisampled ? 2 : 1;
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(count, stack);
            attachments.get(0)
                    .format(COLOR_FORMAT).samples(sampleBit)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(multisampled ? VK_ATTACHMENT_STORE_OP_DONT_CARE : VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(sampled ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            if (multisampled) {
                attachments.get(1)
                        .format(COLOR_FORMAT).samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }
            if (depth) {
                attachments.get(depthIndex)
                        .format(depthFormat).samples(sampleBit)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }
            VkAttachmentReference.Buffer color = VkAttachmentReference.calloc(1, stack)
                    .attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1).pColorAttachments(color);
            if (multisampled) {
                subpass.pResolveAttachments(VkAttachmentReference.calloc(1, stack)
                        .attachment(1).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL));
            }
            if (depth) {
                subpass.pDepthStencilAttachment(VkAttachmentReference.calloc(stack)
                        .attachment(depthIndex).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL));
            }
            int attachmentStages = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                    | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            VkSubpassDependency.Buffer dependencies = VkSubpassDependency.calloc(2, stack);
            // Whatever read or wrote the images last (Skia's copy, our previous frame) finishes before we write.
            dependencies.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                    .srcStageMask(attachmentStages | VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .dstStageMask(attachmentStages)
                    .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT
                            | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            // Our colour writes (and the resolve, which happens at the same stage) are visible to whoever copies or
            // samples the image next.
            dependencies.get(1)
                    .srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT);

            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack).sType$Default()
                    .pAttachments(attachments).pSubpasses(subpass).pDependencies(dependencies);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateRenderPass(device, info, null, out), "create a render pass");
            renderPasses.put(key, out.get(0));
            return out.get(0);
        }
    }

    /**
     * Runs the action once the GPU has finished every frame recorded so far.
     */
    void destroyLater(Runnable action) {
        if (closed) {
            action.run();
            return;
        }
        deferred.addLast(new Deferred(frameCounter, action));
    }
}
