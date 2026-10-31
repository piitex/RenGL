package me.piitex.engine.upscale;

import me.piitex.engine.render3d.vk.VkImageInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The layout changes a vendor's compute work around an upscale needs, which are the same for each: the scene's colour,
 * depth and motion are read as ordinary sampled images and the output is written as a storage image. They go in a command
 * buffer made by {@link me.piitex.engine.render3d.vk.VkGpuDevice#record}.
 */
public final class UpscaleBarriers {
    private UpscaleBarriers() {
    }

    private static final int PASS_STAGES = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
            | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    private static void barrier(VkImageMemoryBarrier barrier, VkImageInfo image, int oldLayout, int newLayout, int srcAccess, int dstAccess) {
        barrier.sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess).oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image.image())
                .subresourceRange(r -> r.aspectMask(image.aspect()).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
    }

    /**
     * What the passes before wrote is visible to the compute shaders, the depth is read as an ordinary sampled image, and
     * the output may be written. The output's old picture is not wanted.
     */
    public static void before(VkCommandBuffer cmd, VkImageInfo color, VkImageInfo depth, VkImageInfo motion, VkImageInfo output) {
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
     * the pass that drew it, and nothing later starts writing what the vendor's shaders were still reading.
     */
    public static void after(VkCommandBuffer cmd, VkImageInfo depth, VkImageInfo output) {
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
}
