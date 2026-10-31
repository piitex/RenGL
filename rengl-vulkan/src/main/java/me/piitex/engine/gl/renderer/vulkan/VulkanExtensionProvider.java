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
     * Queues of the engine's queue family to make besides the one the engine uses (which is queue 0): the library gets queues
     * {@code 1} to {@code extraQueues()} of the family, as {@link DeviceInfo#queueCount()} says. Capped at what the family has.
     */
    default int extraQueues() {
        return 0;
    }

    /**
     * The window's device has just been made with what was asked for. (A provider is a new object each time; keep state in
     * statics.)
     */
    default void deviceCreated() {
    }

    /** {@link #deviceCreated()} with the handles of what was made, for a library that has to know the device (NVIDIA's Streamline does). */
    default void deviceCreated(DeviceInfo info) {
        deviceCreated();
    }

    /** The window's device is about to be destroyed, with the GPU idle: let go of everything made on it. */
    default void deviceDestroying() {
    }

    /**
     * Functions to call in place of the window's swapchain calls, or null to leave them as they are. A library that presents
     * frames of its own (frame generation) has to sit between the engine and the driver here. Asked for after
     * {@link #deviceCreated(DeviceInfo)}.
     */
    default SwapchainHooks swapchainHooks() {
        return null;
    }

    /**
     * A library that sits in the swapchain (frame generation) has to see the window's surface being made, to know which window
     * it presents to: it makes the surface itself, through its own copy of {@code vkCreateWin32SurfaceKHR}. Windows only, and
     * asked for once the device is made, in place of the surface the GPU was picked with.
     *
     * @param nativeWindow the window's {@code HWND}
     * @return the {@code VkSurfaceKHR}, or 0 to leave the surface to the engine
     */
    default long createWindowSurface(long instance, long nativeWindow) {
        return 0L;
    }

    /** Destroys a surface this provider made with {@link #createWindowSurface}. */
    default void destroyWindowSurface(long instance, long surface) {
    }

    /**
     * The image about to be presented, with the layout it was left in, for a library that has to know the back buffer
     * (Streamline is told its state). Called just before {@link FrameEvent#PRESENT_STARTED}.
     */
    default void beforePresent(SwapchainImage image) {
    }

    /** A swapchain image: its {@code VkImage}, {@code VkFormat}, size, {@code VkImageUsageFlags} and {@code VkImageLayout}. */
    record SwapchainImage(long image, int format, int width, int height, int usage, int layout) {
    }

    /** Called at the points of a window frame that a library timing the frame wants to know (latency markers, sleeping). */
    default void frameEvent(FrameEvent event) {
    }

    /**
     * The device the window made, as raw handles.
     *
     * @param queueFamily the family of the engine's queue, which does graphics and presents
     * @param queueCount  how many queues of that family were made: the engine's is index 0, those asked for by
     *                    {@link #extraQueues()} follow
     */
    record DeviceInfo(long instance, long physicalDevice, long device, int queueFamily, int queueCount) {
    }

    /** The points of a window frame, in the order they come. */
    enum FrameEvent {
        /** The frame is starting, before an image is acquired. */
        FRAME_STARTED,
        /** An image was acquired and the frame is drawn into it. */
        RENDER_STARTED,
        /** The drawing is submitted; the image is about to be presented. */
        RENDER_FINISHED,
        PRESENT_STARTED,
        PRESENT_FINISHED,
        /** The swapchain is about to be taken down and made again (the window was resized, or vsync changed). */
        SWAPCHAIN_CHANGING
    }

    /**
     * The five swapchain calls, with the addresses of the native structs the Vulkan calls take. Each returns what the call it
     * stands for returns ({@code VkResult}).
     */
    interface SwapchainHooks {
        /** {@code vkCreateSwapchainKHR(device, pCreateInfo, null, pSwapchain)} */
        int createSwapchain(long device, long createInfo, long pSwapchain);

        /** {@code vkDestroySwapchainKHR(device, swapchain, null)} */
        void destroySwapchain(long device, long swapchain);

        /** {@code vkGetSwapchainImagesKHR(device, swapchain, pCount, pImages)} */
        int swapchainImages(long device, long swapchain, long pCount, long pImages);

        /** {@code vkAcquireNextImageKHR(device, swapchain, timeout, semaphore, fence, pIndex)} */
        int acquireNextImage(long device, long swapchain, long timeout, long semaphore, long fence, long pIndex);

        /** {@code vkQueuePresentKHR(queue, pPresentInfo)} */
        int present(long queue, long presentInfo);
    }

    static void notifyDeviceCreated(DeviceInfo info) {
        notifyAll(provider -> provider.deviceCreated(info));
    }

    /** Every provider that can be loaded, once each; one that is listed but missing is skipped. */
    static List<VulkanExtensionProvider> loadAll() {
        Logger log = LogManager.getLogger("Engine");
        List<VulkanExtensionProvider> all = new ArrayList<>();
        Iterator<VulkanExtensionProvider> providers = ServiceLoader.load(VulkanExtensionProvider.class).iterator();
        while (true) {
            try {
                if (!providers.hasNext()) break;
                all.add(providers.next());
            } catch (ServiceConfigurationError e) {
                log.warn("A Vulkan extension provider could not be loaded: {}", e.getMessage());
            }
        }
        return all;
    }

    /** How many queues the providers want besides the engine's. */
    static int allExtraQueues() {
        int total = 0;
        Logger log = LogManager.getLogger("Engine");
        for (VulkanExtensionProvider provider : loadAll()) {
            try {
                total += Math.max(0, provider.extraQueues());
            } catch (RuntimeException | LinkageError e) {
                log.warn("{} could not name its queues: {}", provider.getClass().getName(), e.toString());
            }
        }
        return total;
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
