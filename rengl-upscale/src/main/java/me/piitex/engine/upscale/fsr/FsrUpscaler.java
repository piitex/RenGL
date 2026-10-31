package me.piitex.engine.upscale.fsr;

import me.piitex.engine.render3d.upscale.UpscaleInputs;
import me.piitex.engine.render3d.upscale.Upscaler;
import me.piitex.engine.render3d.vk.VkGpuDevice;
import me.piitex.engine.render3d.vk.VkImageInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.lang.foreign.Arena;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * AMD FidelityFX Super Resolution on Vulkan: FSR 4 where the GPU and the installed libraries have it, FSR 3 otherwise.
 * <p>
 * The scene's colour, depth and motion are what the SDK reads, and it writes the display-size picture as a storage image
 * ({@link #writesStorageImage()}). Each frame the upscaler brings those images into the layouts the SDK expects, lets it
 * record its dispatch into a command buffer of the device's queue, and puts them back.
 */
final class FsrUpscaler implements Upscaler {
    private final VkGpuDevice device;
    private final Ffx ffx;
    private Ffx.Context context;
    private int renderWidth, renderHeight, displayWidth, displayHeight;

    FsrUpscaler(VkGpuDevice device, Ffx ffx) {
        this.device = device;
        this.ffx = ffx;
    }

    @Override
    public String name() {
        return context == null ? "FSR" : context.versionName;
    }

    @Override
    public boolean writesStorageImage() {
        return true;
    }

    /**
     * Makes the SDK's context for these sizes, or makes it again when they changed. True when it is new, which means the SDK
     * has no history.
     */
    private boolean ensureContext(UpscaleInputs in) {
        if (context != null && renderWidth == in.renderWidth() && renderHeight == in.renderHeight()
                && displayWidth == in.displayWidth() && displayHeight == in.displayHeight()) {
            return false;
        }
        release();
        context = ffx.createContext(device, Ffx.ENABLE_HIGH_DYNAMIC_RANGE | Ffx.ENABLE_AUTO_EXPOSURE,
                in.renderWidth(), in.renderHeight(), in.displayWidth(), in.displayHeight(), null);
        renderWidth = in.renderWidth();
        renderHeight = in.renderHeight();
        displayWidth = in.displayWidth();
        displayHeight = in.displayHeight();
        return true;
    }

    private void release() {
        if (context == null) return;
        // The context's own images and pipelines may still be in use by frames in flight.
        vkDeviceWaitIdle(device.vkDevice());
        context.close();
        context = null;
    }

    @Override
    public void upscale(UpscaleInputs in) {
        if (in.depth() == null || in.motion() == null) throw new IllegalStateException("FSR needs depth and motion vectors");
        boolean fresh = ensureContext(in) || in.reset();
        VkImageInfo color = device.imageOf(in.color()), depth = device.imageOf(in.depth()), motion = device.imageOf(in.motion()),
                output = device.imageOf(in.output().texture());
        float milliseconds = in.deltaSeconds() > 0f ? in.deltaSeconds() * 1000f : 1000f / 60f;
        device.record(cmd -> {
            before(cmd, color, depth, motion, output);
            try (Arena arena = Arena.ofConfined()) {
                CStruct description = dispatchDescription(arena, cmd.address(), color, depth, motion, output, in.jitterX(), in.jitterY(),
                        in.renderWidth(), in.renderHeight(), in.displayWidth(), in.displayHeight(), milliseconds, fresh,
                        in.near(), in.far(), in.fovYRadians());
                int result = context.dispatch(description);
                if (result != 0) throw new IllegalStateException("ffxDispatch of the FSR upscaler returned " + result);
            }
            after(cmd, depth, output);
        });
    }

    /**
     * The {@code ffxDispatchDescUpscale}. Motion is in render pixels, previous position minus this one, which is the
     * scale (1, 1); the jitter is the offset the camera was moved by, +y down the picture; depth is not reversed and has
     * a finite far plane; the colour is linear light that has not been given an exposure (the SDK finds one itself).
     */
    static CStruct dispatchDescription(Arena arena, long commandList, VkImageInfo color, VkImageInfo depth, VkImageInfo motion,
                                       VkImageInfo output, float jitterX, float jitterY, int renderWidth, int renderHeight,
                                       int displayWidth, int displayHeight, float deltaMilliseconds, boolean reset,
                                       float near, float far, float fovYRadians) {
        CStruct d = new CStruct(arena, 432).header(Ffx.DISPATCH_UPSCALE).ptr(commandList);
        Ffx.resource(d, color, Ffx.STATE_COMPUTE_READ);
        Ffx.resource(d, depth, Ffx.STATE_COMPUTE_READ);
        Ffx.resource(d, motion, Ffx.STATE_COMPUTE_READ);
        d.skip(3 * 48); // exposure, reactive mask, transparency and composition mask: not given
        Ffx.resource(d, output, Ffx.STATE_UNORDERED_ACCESS);
        return d.f32(jitterX).f32(jitterY)
                .f32(1f).f32(1f)
                .u32(renderWidth).u32(renderHeight)
                .u32(displayWidth).u32(displayHeight)
                .bool(false).f32(0f)
                .f32(deltaMilliseconds).f32(1f)
                .bool(reset)
                .f32(near).f32(far).f32(fovYRadians).f32(1f)
                .u32(0)
                .end();
    }

    private static final int PASS_STAGES = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
            | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    private static void barrier(VkImageMemoryBarrier barrier, VkImageInfo image, int oldLayout, int newLayout, int srcAccess, int dstAccess) {
        barrier.sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess).oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image.image())
                .subresourceRange(r -> r.aspectMask(image.aspect()).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
    }

    /**
     * What the passes before wrote is visible to the compute shaders, the depth is read as an ordinary sampled image (the
     * layout the SDK treats a read as), and the output may be written. The output's old picture is not wanted.
     */
    private static void before(VkCommandBuffer cmd, VkImageInfo color, VkImageInfo depth, VkImageInfo motion, VkImageInfo output) {
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(4, stack);
            barrier(barriers.get(0), color, color.layout(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
            barrier(barriers.get(1), motion, motion.layout(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
            barrier(barriers.get(2), depth, depth.layout(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
            // The passes that read the output last frame must be done before it is written again.
            barrier(barriers.get(3), output, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                    0, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(cmd, PASS_STAGES, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, barriers);
        }
    }

    /**
     * The output goes back to the layout the rest of the post-processing reads it in, the depth to the one it is left in by
     * the pass that drew it, and nothing later starts writing what the SDK was still reading.
     */
    private static void after(VkCommandBuffer cmd, VkImageInfo depth, VkImageInfo output) {
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(2, stack);
            barrier(barriers.get(0), output, VK_IMAGE_LAYOUT_GENERAL, output.layout(), VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
            barrier(barriers.get(1), depth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, depth.layout(), 0, VK_ACCESS_SHADER_READ_BIT);
            VkMemoryBarrier.Buffer memory = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                            | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, PASS_STAGES | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                    memory, null, barriers);
        }
    }

    @Override
    public void close() {
        release();
    }
}
