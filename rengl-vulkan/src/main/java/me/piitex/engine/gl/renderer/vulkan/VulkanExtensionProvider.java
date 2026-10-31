package me.piitex.engine.gl.renderer.vulkan;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Extensions a library outside the engine needs on the Vulkan instance or device, which can only be asked for when those are
 * made (NVIDIA's DLSS is one: it wants a handful of device extensions turned on). Listed in
 * {@code META-INF/services/me.piitex.engine.gl.renderer.vulkan.VulkanExtensionProvider}.
 * <p>
 * The extensions are optional: those the GPU or the loader does not have are left out, and the library that wanted them is
 * expected to see for itself that it can not run. A provider that throws is ignored.
 */
public interface VulkanExtensionProvider {
    /** Instance extensions to enable where they exist. */
    default Collection<String> instanceExtensions() {
        return List.of();
    }

    /** Device extensions to enable where the GPU has them. */
    default Collection<String> deviceExtensions() {
        return List.of();
    }

    /** What every provider asked for, without repeats. */
    static Set<String> allInstanceExtensions() {
        return collect(true);
    }

    static Set<String> allDeviceExtensions() {
        return collect(false);
    }

    private static Set<String> collect(boolean instance) {
        Logger log = LogManager.getLogger("Engine");
        Set<String> names = new LinkedHashSet<>();
        Iterator<VulkanExtensionProvider> providers = ServiceLoader.load(VulkanExtensionProvider.class).iterator();
        while (true) {
            VulkanExtensionProvider provider;
            try {
                if (!providers.hasNext()) break;
                provider = providers.next();
            } catch (ServiceConfigurationError e) {
                // A listed provider that is not there (a module that was removed): the others still count.
                log.warn("A Vulkan extension provider could not be loaded: {}", e.getMessage());
                continue;
            }
            try {
                names.addAll(new ArrayList<>(instance ? provider.instanceExtensions() : provider.deviceExtensions()));
            } catch (RuntimeException | LinkageError e) {
                log.warn("{} could not name its Vulkan extensions: {}", provider.getClass().getName(), e.toString());
            }
        }
        return names;
    }
}
