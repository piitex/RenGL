package me.piitex.engine.render3d.vk;

import me.piitex.engine.gl.renderer.vulkan.VulkanExtensionProvider;

import java.util.Collection;
import java.util.List;

/**
 * Asks for Vulkan 1.2's {@code drawIndirectCount} when the window's device is made: drawing as many commands as a count the
 * GPU wrote says, which is how the clusters of virtual geometry a compute shader picked are drawn. The window's context turns
 * it on only where the GPU has it.
 * <p>
 * {@code -Drengl.3d.virtualgeometry=false} leaves it off, and scenes draw their classic levels of detail.
 */
public final class VirtualGeometryExtensions implements VulkanExtensionProvider {
    static boolean wanted() {
        return !"false".equalsIgnoreCase(System.getProperty("rengl.3d.virtualgeometry"));
    }

    @Override
    public Collection<String> deviceFeatures12() {
        return wanted() ? List.of("drawIndirectCount") : List.of();
    }
}
