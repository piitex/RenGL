package me.piitex.engine.upscale.metalfx;

import me.piitex.engine.render3d.vk.VkGpuDevice;
import me.piitex.engine.render3d.vk.VkImageInfo;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMetalObjects;
import org.lwjgl.vulkan.VkExportMetalCommandQueueInfoEXT;
import org.lwjgl.vulkan.VkExportMetalDeviceInfoEXT;
import org.lwjgl.vulkan.VkExportMetalObjectsInfoEXT;
import org.lwjgl.vulkan.VkExportMetalTextureInfoEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceDriverProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VK11;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;

/**
 * The Metal objects behind the window's Vulkan ones, which MoltenVK hands out through {@code VK_EXT_metal_objects}: the
 * {@code MTLDevice}, the {@code MTLCommandQueue} of the engine's queue, and the {@code MTLTexture} of an image. MetalFX works on
 * Metal textures, so this is how it reads the scene the Vulkan passes drew and writes the picture they read next.
 * <p>
 * The objects are not retained for the caller; a texture is asked for again every frame, since MoltenVK may make a new one.
 *
 * @param device the {@code id<MTLDevice>}
 * @param queue  the {@code id<MTLCommandQueue>} the Vulkan work is submitted to, so a command buffer of ours made from it runs
 *               in order with that work
 */
record MetalObjects(long device, long queue) {
    /** Whether the Vulkan device was made with the extension (see {@link MetalFxExtensions}). */
    static boolean enabled(VkGpuDevice vk) {
        return vk.vkDevice().getCapabilities().VK_EXT_metal_objects;
    }

    /**
     * The device and queue of the Vulkan device.
     *
     * @throws IllegalStateException when MoltenVK gave nothing back
     */
    static MetalObjects of(VkGpuDevice vk) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(vk.vkDevice(), vk.queueFamily(), 0, pQueue);
            VkQueue queue = new VkQueue(pQueue.get(0), vk.vkDevice());

            // One call for each, so a driver that stops at the first structure of a chain cannot hide the other.
            VkExportMetalDeviceInfoEXT device = VkExportMetalDeviceInfoEXT.calloc(stack).sType$Default();
            EXTMetalObjects.vkExportMetalObjectsEXT(vk.vkDevice(), VkExportMetalObjectsInfoEXT.calloc(stack).sType$Default().pNext(device));
            VkExportMetalCommandQueueInfoEXT commands = VkExportMetalCommandQueueInfoEXT.calloc(stack).sType$Default().queue(queue);
            EXTMetalObjects.vkExportMetalObjectsEXT(vk.vkDevice(), VkExportMetalObjectsInfoEXT.calloc(stack).sType$Default().pNext(commands));
            if (device.mtlDevice() == 0L || commands.mtlCommandQueue() == 0L) {
                throw new IllegalStateException("vkExportMetalObjectsEXT gave MTLDevice 0x" + Long.toHexString(device.mtlDevice())
                        + " and MTLCommandQueue 0x" + Long.toHexString(commands.mtlCommandQueue()) + " (VkQueue 0x"
                        + Long.toHexString(queue.address()) + ", " + driver(vk, stack) + ")");
            }
            return new MetalObjects(device.mtlDevice(), commands.mtlCommandQueue());
        }
    }

    /** The driver's name and version, for a message about it. */
    private static String driver(VkGpuDevice vk, MemoryStack stack) {
        try {
            VkPhysicalDeviceDriverProperties driver = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(driver);
            VK11.vkGetPhysicalDeviceProperties2(vk.vkDevice().getPhysicalDevice(), properties);
            return driver.driverNameString() + " " + driver.driverInfoString();
        } catch (RuntimeException | LinkageError e) {
            return "driver unknown";
        }
    }

    /**
     * The {@code id<MTLTexture>} of each image, in order.
     *
     * @throws IllegalStateException when MoltenVK gave nothing back for one
     */
    static long[] textures(VkGpuDevice vk, VkImageInfo... images) {
        try (MemoryStack stack = stackPush()) {
            VkExportMetalTextureInfoEXT.Buffer textures = VkExportMetalTextureInfoEXT.calloc(images.length, stack);
            for (int i = 0; i < images.length; i++) {
                textures.get(i).sType$Default().image(images[i].image()).plane(images[i].aspect());
                if (i > 0) textures.get(i - 1).pNext(textures.get(i).address());
            }
            VkExportMetalObjectsInfoEXT info = VkExportMetalObjectsInfoEXT.calloc(stack).sType$Default().pNext(textures.get(0));
            EXTMetalObjects.vkExportMetalObjectsEXT(vk.vkDevice(), info);
            long[] out = new long[images.length];
            for (int i = 0; i < images.length; i++) {
                out[i] = textures.get(i).mtlTexture();
                if (out[i] == 0L) throw new IllegalStateException("MoltenVK gave no Metal texture for image " + i + " of " + images.length);
            }
            return out;
        }
    }
}
