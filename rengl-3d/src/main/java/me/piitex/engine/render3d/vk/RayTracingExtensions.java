package me.piitex.engine.render3d.vk;

import me.piitex.engine.gl.renderer.vulkan.VulkanExtensionProvider;

import java.util.Collection;
import java.util.List;

/**
 * Asks for what ray queries need when the window's Vulkan device is made: the acceleration structure and ray query extensions
 * (the Khronos ones, which NVIDIA, AMD and Intel all implement), buffers the GPU can address and textures picked per pixel. The window's context turns
 * them on only where the GPU has all of it, and a GPU without leaves the device as it was, so this costs nothing there.
 * <p>
 * {@code -Drengl.3d.raytracing=false} leaves ray tracing off.
 */
public final class RayTracingExtensions implements VulkanExtensionProvider {
    private static boolean wanted() {
        return !"false".equalsIgnoreCase(System.getProperty("rengl.3d.raytracing"));
    }

    @Override
    public Collection<String> deviceExtensions() {
        return wanted() ? List.of("VK_KHR_acceleration_structure", "VK_KHR_ray_query", "VK_KHR_deferred_host_operations") : List.of();
    }

    @Override
    public Collection<String> deviceFeatures12() {
        // The second lets a shader pick one texture of an array per pixel, which is how a ray that hit something reads its material.
        return wanted() ? List.of("bufferDeviceAddress", "shaderSampledImageArrayNonUniformIndexing") : List.of();
    }
}
