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
import java.util.function.Function;
import java.util.Set;

/**
 * Extensions a library outside the engine needs on the Vulkan instance or device, which can only be asked for when those are
 * made (NVIDIA's DLSS is one: it wants a handful of device extensions and features turned on). Listed in
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

    /** Features of {@code VkPhysicalDeviceVulkan12Features} to turn on where the GPU has them, by name ({@code timelineSemaphore}). */
    default Collection<String> deviceFeatures12() {
        return List.of();
    }

    /** The same for {@code VkPhysicalDeviceVulkan13Features}. */
    default Collection<String> deviceFeatures13() {
        return List.of();
    }

    /**
     * The window's device has just been made with what was asked for. (A provider is a new object each time; keep state in
     * statics.)
     */
    default void deviceCreated() {
    }

    /** The window's device is about to be destroyed, with the GPU idle: let go of everything made on it. */
    default void deviceDestroying() {
    }

    static void notifyDeviceCreated() {
        notifyAll(VulkanExtensionProvider::deviceCreated);
    }

    static void notifyDeviceDestroying() {
        notifyAll(VulkanExtensionProvider::deviceDestroying);
    }

    private static void notifyAll(java.util.function.Consumer<VulkanExtensionProvider> what) {
        Logger log = LogManager.getLogger("Engine");
        Iterator<VulkanExtensionProvider> providers = ServiceLoader.load(VulkanExtensionProvider.class).iterator();
        while (true) {
            try {
                if (!providers.hasNext()) break;
                what.accept(providers.next());
            } catch (ServiceConfigurationError | RuntimeException | LinkageError e) {
                log.warn("A Vulkan extension provider failed: {}", e.toString());
            }
        }
    }

    /** What every provider asked for, without repeats. */
    static Set<String> allInstanceExtensions() {
        return collect(VulkanExtensionProvider::instanceExtensions);
    }

    static Set<String> allDeviceExtensions() {
        return collect(VulkanExtensionProvider::deviceExtensions);
    }

    static Set<String> allDeviceFeatures12() {
        return collect(VulkanExtensionProvider::deviceFeatures12);
    }

    static Set<String> allDeviceFeatures13() {
        return collect(VulkanExtensionProvider::deviceFeatures13);
    }

    private static Set<String> collect(Function<VulkanExtensionProvider, Collection<String>> what) {
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
                names.addAll(new ArrayList<>(what.apply(provider)));
            } catch (RuntimeException | LinkageError e) {
                log.warn("{} could not name its Vulkan extensions: {}", provider.getClass().getName(), e.toString());
            }
        }
        return names;
    }
}
