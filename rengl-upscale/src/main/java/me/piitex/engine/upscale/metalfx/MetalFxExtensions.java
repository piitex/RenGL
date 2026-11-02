package me.piitex.engine.upscale.metalfx;

import me.piitex.engine.gl.renderer.vulkan.VulkanExtensionProvider;

import java.util.Collection;
import java.util.List;

/**
 * Asks for {@code VK_EXT_metal_objects} when the window's Vulkan device is made, on macOS: the extension MoltenVK offers to hand
 * out the Metal textures behind Vulkan images, which MetalFX needs. Off macOS it asks for nothing, and a MoltenVK without the
 * extension leaves the device as it was.
 */
public final class MetalFxExtensions implements VulkanExtensionProvider {
    @Override
    public Collection<String> deviceExtensions() {
        return ObjC.isMac() ? List.of("VK_EXT_metal_objects") : List.of();
    }
}
