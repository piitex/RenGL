package me.piitex.engine.render3d.vk;

import me.piitex.engine.render3d.GpuTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.util.vma.Vma.vmaDestroyImage;
import static org.lwjgl.vulkan.VK10.*;

final class VkTex implements GpuTexture {
    private final VkGpuDevice device;
    private final int width, height;
    private final VkGpuDevice.Alloc image;
    final long view;
    private boolean closed;

    VkTex(VkGpuDevice device, int width, int height, ByteBuffer rgba) {
        long bytes = (long) width * height * 4;
        if (rgba.remaining() < bytes) throw new IllegalArgumentException("Not enough pixel data");
        this.device = device;
        this.width = width;
        this.height = height;

        image = device.createImage(width, height, VkGpuDevice.COLOR_FORMAT,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        VkGpuDevice.Alloc staging = device.createMappedBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        try {
            device.upload(staging, rgba.duplicate().limit(rgba.position() + (int) bytes));
            device.immediate(cmd -> {
                device.barrier(cmd, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                            .imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                            .imageExtent(e -> e.width(width).height(height).depth(1));
                    vkCmdCopyBufferToImage(cmd, staging.handle(), image.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                }
                device.barrier(cmd, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
            });
        } finally {
            vmaDestroyBuffer(device.allocator, staging.handle(), staging.allocation());
        }
        view = device.createImageView(image.handle(), VkGpuDevice.COLOR_FORMAT, VK_IMAGE_ASPECT_COLOR_BIT);
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
    public void close() {
        if (closed) return;
        closed = true;
        device.destroyLater(() -> {
            vkDestroyImageView(device.device, view, null);
            vmaDestroyImage(device.allocator, image.handle(), image.allocation());
        });
    }
}
