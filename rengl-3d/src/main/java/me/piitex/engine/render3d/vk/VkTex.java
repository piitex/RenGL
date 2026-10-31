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
    private final int width, height, mipLevels, layers;
    private final VkGpuDevice.Alloc image;
    final long view;
    /**
     * The sampler a draw reads this texture with.
     */
    final long sampler;
    /**
     * The layout the image is in whenever a pass reads it: shader-read for a colour image, depth-read for a depth one.
     */
    final int layout;
    private final boolean owned;
    private final int format, pixelBytes;
    /** The image behind the view, with its format, aspect and usage, for {@link VkGpuDevice#imageOf}. */
    private final long imageHandle;
    private final int aspect, usage;
    private boolean closed;

    /**
     * An uploaded texture: one layer for an ordinary one, six for a cube map. {@code data[i]} is layer {@code i}.
     */
    VkTex(VkGpuDevice device, int width, int height, int layers, ByteBuffer[] data, boolean mipmaps) {
        this(device, width, height, layers, data, mipmaps, VkGpuDevice.COLOR_FORMAT, 4);
    }

    /**
     * The same in another colour format, {@code pixelBytes} to a pixel.
     */
    VkTex(VkGpuDevice device, int width, int height, int layers, ByteBuffer[] data, boolean mipmaps, int format, int pixelBytes) {
        long bytes = (long) width * height * pixelBytes;
        for (ByteBuffer layer : data) {
            if (layer.remaining() < bytes) throw new IllegalArgumentException("Not enough pixel data");
        }
        this.device = device;
        this.width = width;
        this.height = height;
        this.layers = layers;
        this.sampler = device.sampler;
        this.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        this.owned = true;
        this.format = format;
        this.pixelBytes = pixelBytes;
        this.mipLevels = mipmaps ? 32 - Integer.numberOfLeadingZeros(Math.max(width, height)) : 1;

        // Mipmaps are made by shrinking each level into the next, so every level is both a source and a destination.
        int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                | (mipLevels > 1 ? VK_IMAGE_USAGE_TRANSFER_SRC_BIT : 0);
        image = device.createImage(width, height, format, usage, mipLevels, VK_SAMPLE_COUNT_1_BIT, layers);
        this.imageHandle = image.handle();
        this.aspect = VK_IMAGE_ASPECT_COLOR_BIT;
        this.usage = usage;
        VkGpuDevice.Alloc staging = device.createMappedBuffer(bytes * layers, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        for (int layer = 0; layer < layers; layer++) {
            device.upload(staging, bytes * layer, data[layer].duplicate().limit(data[layer].position() + (int) bytes));
        }
        device.upload(cmd -> {
            device.barrier(cmd, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                    0, mipLevels, layers);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer regions = VkBufferImageCopy.calloc(layers, stack);
                for (int layer = 0; layer < layers; layer++) {
                    final int l = layer;
                    regions.get(layer).bufferOffset(bytes * layer)
                            .imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseArrayLayer(l).layerCount(1))
                            .imageExtent(e -> e.width(width).height(height).depth(1));
                }
                vkCmdCopyBufferToImage(cmd, staging.handle(), image.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);
            }
            if (mipLevels > 1) generateMipmaps(cmd);
            else toShaderRead(cmd, 0, 1);
        }, () -> vmaDestroyBuffer(device.allocator, staging.handle(), staging.allocation()));
        view = device.createImageView(image.handle(), format, VK_IMAGE_ASPECT_COLOR_BIT, mipLevels, layers);
    }

    /**
     * Another name for an image someone else owns, such as a sampled render target's colour or its depth buffer. A pass
     * leaves it in {@code layout} (shader-read for a colour image, depth-read for a depth one) whenever it is read, and
     * closing the name frees nothing.
     */
    VkTex(VkGpuDevice device, int width, int height, long view, long sampler, int layout,
          long imageHandle, int format, int aspect, int usage) {
        this.layout = layout;
        this.device = device;
        this.width = width;
        this.height = height;
        this.layers = 1;
        this.mipLevels = 1;
        this.image = null;
        this.view = view;
        this.sampler = sampler;
        this.owned = false;
        this.format = format;
        this.pixelBytes = 4;
        this.imageHandle = imageHandle;
        this.aspect = aspect;
        this.usage = usage;
    }

    /**
     * The Vulkan image behind this texture, in the layout a pass reads it in.
     */
    VkImageInfo info() {
        return new VkImageInfo(imageHandle, view, format, aspect, width, height, layout, usage);
    }

    @Override
    public void update(ByteBuffer rgba) {
        if (!owned || layers != 1 || format != VkGpuDevice.COLOR_FORMAT) throw new UnsupportedOperationException("This texture can not be updated");
        long bytes = (long) width * height * pixelBytes;
        if (rgba.remaining() < bytes) throw new IllegalArgumentException("Not enough pixel data");
        VkGpuDevice.Alloc staging = device.createMappedBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        device.upload(staging, rgba.duplicate().limit(rgba.position() + (int) bytes));
        device.upload(cmd -> {
            // Every level is rewritten (level 0 here, the rest by the blits), so the old contents are not kept. The
            // source stages wait for earlier frames that are still reading the picture.
            device.barrier(cmd, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, 0, mipLevels, 1);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.get(0).bufferOffset(0)
                        .imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseArrayLayer(0).layerCount(1))
                        .imageExtent(e -> e.width(width).height(height).depth(1));
                vkCmdCopyBufferToImage(cmd, staging.handle(), image.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            }
            if (mipLevels > 1) generateMipmaps(cmd);
            else toShaderRead(cmd, 0, 1);
        }, () -> vmaDestroyBuffer(device.allocator, staging.handle(), staging.allocation()));
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
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, level - 1, 1, layers);
            int nextW = Math.max(1, w / 2), nextH = Math.max(1, h / 2);
            final int srcW = w, srcH = h, dstW = nextW, dstH = nextH, src = level - 1, dst = level;
            try (MemoryStack stack = stackPush()) {
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(src).layerCount(layers);
                blit.srcOffsets(1).set(srcW, srcH, 1);
                blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(dst).layerCount(layers);
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
                VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT, 0, mipLevels - 1, layers);
        toShaderRead(cmd, mipLevels - 1, 1);
    }

    private void toShaderRead(VkCommandBuffer cmd, int baseMip, int levels) {
        device.barrier(cmd, image.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, baseMip, levels, layers);
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
        if (closed || !owned) return;
        closed = true;
        device.destroyLater(() -> {
            vkDestroyImageView(device.device, view, null);
            vmaDestroyImage(device.allocator, image.handle(), image.allocation());
        });
    }
}
