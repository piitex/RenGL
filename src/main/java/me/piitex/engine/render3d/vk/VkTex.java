package me.piitex.engine.render3d.vk;

import me.piitex.engine.render3d.GpuTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;

import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.*;

final class VkTex implements GpuTexture {
    private final VkGpuDevice device;
    private final int width, height, mipLevels;
    private final VkGpuDevice.Alloc image;
    final long view;
    private boolean closed;

    VkTex(VkGpuDevice device, int width, int height, ByteBuffer rgba, boolean mipmaps) {
        long bytes = (long) width * height * 4;
        if (rgba.remaining() < bytes) throw new IllegalArgumentException("Not enough pixel data");
        this.device = device;
        this.width = width;
        this.height = height;
        this.mipLevels = mipmaps ? 32 - Integer.numberOfLeadingZeros(Math.max(width, height)) : 1;

        // Mipmaps are made by shrinking each level into the next, so every level is both a source and a destination.
        int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                | (mipLevels > 1 ? VK_IMAGE_USAGE_TRANSFER_SRC_BIT : 0);
        image = device.createImage(width, height, VkGpuDevice.COLOR_FORMAT, usage, mipLevels, VK_SAMPLE_COUNT_1_BIT);
        VkGpuDevice.Alloc staging = device.createMappedBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        try {
            device.upload(staging, rgba.duplicate().limit(rgba.position() + (int) bytes));
            device.immediate(cmd -> {
                device.barrier(cmd, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        0, mipLevels);
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                            .imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                            .imageExtent(e -> e.width(width).height(height).depth(1));
                    vkCmdCopyBufferToImage(cmd, staging.handle(), image.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                }
                if (mipLevels > 1) generateMipmaps(cmd);
                else toShaderRead(cmd, 0, 1);
            });
        } finally {
            vmaDestroyBuffer(device.allocator, staging.handle(), staging.allocation());
        }
        view = device.createImageView(image.handle(), VkGpuDevice.COLOR_FORMAT, VK_IMAGE_ASPECT_COLOR_BIT, mipLevels);
    }

    /**
     * Level 0 is filled and everything is in TRANSFER_DST. Shrinks each level into the next one, then moves all of
     * them to the layout a shader reads.
     */
    private void generateMipmaps(VkCommandBuffer cmd) {
        int w = width, h = height;
        for (int level = 1; level < mipLevels; level++) {
            device.barrier(cmd, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, level - 1, 1);
            int nextW = Math.max(1, w / 2), nextH = Math.max(1, h / 2);
            final int srcW = w, srcH = h, dstW = nextW, dstH = nextH, src = level - 1, dst = level;
            try (MemoryStack stack = stackPush()) {
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(src).layerCount(1);
                blit.srcOffsets(1).set(srcW, srcH, 1);
                blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(dst).layerCount(1);
                blit.dstOffsets(1).set(dstW, dstH, 1);
                vkCmdBlitImage(cmd, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        image.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR);
            }
            w = nextW;
            h = nextH;
        }
        // Levels 0 to n-2 were read from, so they are TRANSFER_SRC; the last one was only written.
        device.barrier(cmd, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT, 0, mipLevels - 1);
        toShaderRead(cmd, mipLevels - 1, 1);
    }

    private void toShaderRead(VkCommandBuffer cmd, int baseMip, int levels) {
        device.barrier(cmd, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, baseMip, levels);
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int mipLevels() {
        return mipLevels;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        device.destroyLater(() -> {
            vkDestroyImageView(device.device, view, null);
            vmaDestroyImage(device.allocator, image.handle(), image.allocation());
        });
    }
}
