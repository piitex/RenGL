package me.piitex.engine.render3d.vk;

/**
 * The Vulkan handles behind a texture, for code outside the 3D layer that records Vulkan work of its own on the device's
 * queue (a vendor's upscaler, for instance). See {@link VkGpuDevice#imageOf}.
 *
 * @param image  the {@code VkImage}
 * @param view   a {@code VkImageView} of it (the depth aspect alone for a depth image)
 * @param format the {@code VkFormat}
 * @param aspect the {@code VkImageAspectFlags} the view covers
 * @param width  the size in pixels
 * @param layout the layout the image is in whenever a pass has finished with it and a shader may read it: shader-read for a
 *               colour image, depth-read-only for a depth buffer
 * @param usage  the {@code VkImageUsageFlags} the image was made with
 */
public record VkImageInfo(long image, long view, int format, int aspect, int width, int height, int layout, int usage) {
}
