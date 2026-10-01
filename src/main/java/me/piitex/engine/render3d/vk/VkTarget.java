package me.piitex.engine.render3d.vk;

import me.piitex.engine.render3d.RenderTarget;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Colour (RGBA8) and optional depth images plus the framebuffer that ties them to the device's render pass. The
 * colour image has the usage Skia needs to wrap and snapshot it (colour attachment, sampled, and both transfer directions).
 */
public final class VkTarget implements RenderTarget {
    private static final int COLOR_USAGE = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    private final VkGpuDevice device;
    private final int width, height, samples;
    private final boolean depth;
    /**
     * The single-sample colour image: the finished picture. With multisampling it is what the pass resolves into.
     */
    private final VkGpuDevice.Alloc color;
    private final VkGpuDevice.Alloc depthImage;
    private final VkGpuDevice.Alloc msaaColor;
    private final long colorView, depthView, msaaColorView;
    final long framebuffer;
    private boolean closed;

    VkTarget(VkGpuDevice device, int width, int height, boolean depth, int samples) {
        this.device = device;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.depth = depth;
        this.samples = samples;

        color = device.createImage(this.width, this.height, VkGpuDevice.COLOR_FORMAT, COLOR_USAGE);
        colorView = device.createImageView(color.handle(), VkGpuDevice.COLOR_FORMAT, VK_IMAGE_ASPECT_COLOR_BIT);
        if (samples > 1) {
            msaaColor = device.createImage(this.width, this.height, VkGpuDevice.COLOR_FORMAT,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, 1, samples);
            msaaColorView = device.createImageView(msaaColor.handle(), VkGpuDevice.COLOR_FORMAT, VK_IMAGE_ASPECT_COLOR_BIT);
        } else {
            msaaColor = null;
            msaaColorView = VK_NULL_HANDLE;
        }
        if (depth) {
            depthImage = device.createImage(this.width, this.height, device.depthFormat,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, 1, samples);
            depthView = device.createImageView(depthImage.handle(), device.depthFormat, device.depthAspect);
        } else {
            depthImage = null;
            depthView = VK_NULL_HANDLE;
        }
        try (MemoryStack stack = stackPush()) {
            // The order the render pass declares: multisampled colour and its resolve target, or the colour alone; depth last.
            LongBuffer attachments = stack.mallocLong(attachmentCount());
            if (samples > 1) attachments.put(msaaColorView);
            attachments.put(colorView);
            if (depth) attachments.put(depthView);
            attachments.flip();
            VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack).sType$Default()
                    .renderPass(device.renderPass(depth, samples)).pAttachments(attachments)
                    .width(this.width).height(this.height).layers(1);
            LongBuffer out = stack.mallocLong(1);
            VkGpuDevice.check(vkCreateFramebuffer(device.device, info, null, out), "create a framebuffer");
            framebuffer = out.get(0);
        }
    }

    /**
     * How many attachments the framebuffer has.
     */
    int attachmentCount() {
        return (samples > 1 ? 2 : 1) + (depth ? 1 : 0);
    }

    /**
     * The depth image's index among the attachments.
     */
    int depthAttachment() {
        return samples > 1 ? 2 : 1;
    }

    // ---- what the UI layer needs to wrap the image in Skia ------------------------------------------------------

    /**
     * The colour {@code VkImage}. After a pass it is in {@code COLOR_ATTACHMENT_OPTIMAL}; Skia leaves it in
     * {@code TRANSFER_SRC_OPTIMAL}, which the next pass accepts because it starts from {@code UNDEFINED}.
     */
    public long image() {
        return color.handle();
    }

    public int format() {
        return VkGpuDevice.COLOR_FORMAT;
    }

    public int imageUsage() {
        return COLOR_USAGE;
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
    public boolean hasDepth() {
        return depth;
    }

    @Override
    public int samples() {
        return samples;
    }

    @Override
    public boolean isBottomLeftOrigin() {
        return false;
    }

    @Override
    public ByteBuffer readPixels() {
        long bytes = (long) width * height * 4;
        VkGpuDevice.Alloc staging = device.createBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VMA_ALLOCATION_CREATE_MAPPED_BIT | VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
        try {
            device.immediate(cmd -> {
                device.barrier(cmd, color.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
                try (MemoryStack stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                            .imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                            .imageExtent(e -> e.width(width).height(height).depth(1));
                    vkCmdCopyImageToBuffer(cmd, color.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging.handle(), region);
                }
                // Back to the layout the pass leaves it in, which is what Skia is told when it wraps the image.
                device.barrier(cmd, color.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            });
            vmaInvalidateAllocation(device.allocator, staging.allocation(), 0, VK_WHOLE_SIZE);
            ByteBuffer out = memAlloc((int) bytes);
            memCopy(staging.mapped(), memAddress(out), bytes);
            return out;
        } finally {
            vmaDestroyBuffer(device.allocator, staging.handle(), staging.allocation());
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        device.destroyLater(() -> {
            vkDestroyFramebuffer(device.device, framebuffer, null);
            vkDestroyImageView(device.device, colorView, null);
            vmaDestroyImage(device.allocator, color.handle(), color.allocation());
            if (msaaColor != null) {
                vkDestroyImageView(device.device, msaaColorView, null);
                vmaDestroyImage(device.allocator, msaaColor.handle(), msaaColor.allocation());
            }
            if (depthImage != null) {
                vkDestroyImageView(device.device, depthView, null);
                vmaDestroyImage(device.allocator, depthImage.handle(), depthImage.allocation());
            }
        });
    }
}
