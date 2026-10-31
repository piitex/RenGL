package me.piitex.engine.gl.renderer.vulkan;

import me.piitex.engine.gl.renderer.RenderBackend;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRComputeShaderDerivatives.VK_KHR_COMPUTE_SHADER_DERIVATIVES_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/**
 * Everything Vulkan the engine owns for one GLFW window: the instance, the GPU and its queue, the window surface and
 * the swapchain, plus the synchronization that hands swapchain images to Skia and back to the display.
 * <p>
 * Skia draws into the swapchain image through Skiko's Vulkan {@code DirectContext}, so this class only deals in raw
 * handles ({@link #instance()}, {@link #image()} and so on) and never records drawing. Skiko can't tell Skia to
 * wait for a semaphore or to leave an image in the presentation layout, so each frame is bracketed by two small
 * submissions of our own on the same queue:
 * <ol>
 *   <li>{@link #beginFrame()} acquires an image and submits a barrier that waits for the display to release it and
 *       moves it to {@code COLOR_ATTACHMENT_OPTIMAL}, the layout the renderer tells Skia the image is in.</li>
 *   <li>Skia records and submits its own work (the renderer's {@code end()}).</li>
 *   <li>{@link #endFrame()} submits a barrier to {@code PRESENT_SRC_KHR} that signals the semaphore presentation
 *       waits on. Queue submission order is what orders it after Skia's work; no CPU wait is needed.</li>
 * </ol>
 * <b>Threading:</b> construct on the main thread (creating a window surface touches AppKit on macOS). After that
 * every method except {@link #close()} belongs to the render thread; {@code close()} runs on the main thread once the
 * render thread has released the renderer, and before the GLFW window is destroyed.
 */
public final class VulkanContext implements RenderBackend.Context {
    private static final Logger log = LogManager.getLogger("Engine");

    private static final int FRAMES_IN_FLIGHT = 2;
    private static final long ACQUIRE_TIMEOUT_NANOS = 1_000_000_000L;
    private static final int API_VERSION_CAP = VK_MAKE_API_VERSION(0, 1, 3, 0);
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";
    private static final boolean VALIDATION = Boolean.getBoolean("rengl.vulkan.validation");

    private static Boolean supported; // null until probed
    private static String probedGpu;

    private VkInstance instance;
    private long debugMessenger;
    private VkDebugUtilsMessengerCallbackEXT debugCallback;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private VkQueue queue;
    private int queueFamily;
    private int apiVersion;
    private String gpuName;
    private long surface;
    private final long windowHandle;

    private long swapchain;
    private int swapchainFormat;
    private int swapchainUsage;
    private int width, height;
    private long[] images = new long[0];
    private long[] renderFinished = new long[0]; // one per swapchain image: presentation waits on these
    private int presentMode;
    private boolean vsync = true;
    private boolean needsRecreate;

    private long commandPool;
    private final VkCommandBuffer[] preBuffers = new VkCommandBuffer[FRAMES_IN_FLIGHT];
    private final VkCommandBuffer[] postBuffers = new VkCommandBuffer[FRAMES_IN_FLIGHT];
    private final long[] acquired = new long[FRAMES_IN_FLIGHT];
    private final long[] inFlight = new long[FRAMES_IN_FLIGHT];
    private int slot;
    private int imageIndex = -1;
    private boolean closed;

    /**
     * Finds out once whether Vulkan can be used on this machine: the loader is there, and a GPU can present to a
     * window and run a swapchain. GLFW must be initialized. Cached after the first call.
     */
    public static synchronized boolean isSupported() {
        if (supported != null) return supported;
        supported = false;
        try {
            if (!glfwVulkanSupported()) {
                log.info("Vulkan is not available: no loader or ICD was found.");
                return false;
            }
            try (MemoryStack stack = stackPush()) {
                VkInstance probe = createInstance(stack, false);
                try {
                    Candidate best = pickDevice(probe, stack, 0L);
                    if (best == null) {
                        log.info("Vulkan is not available: no GPU can present to a window.");
                        return false;
                    }
                    probedGpu = best.name;
                    supported = true;
                } finally {
                    vkDestroyInstance(probe, null);
                }
            }
        } catch (Throwable t) {
            log.warn("Vulkan is not available: {}", t.toString());
            supported = false;
        }
        return supported;
    }

    /**
     * Name of the GPU the probe found, or null if Vulkan isn't supported.
     */
    public static synchronized String probedGpuName() {
        return probedGpu;
    }

    public VulkanContext(long windowHandle) {
        this.windowHandle = windowHandle;
        try (MemoryStack stack = stackPush()) {
            instance = createInstance(stack, VALIDATION);
            if (VALIDATION) installDebugMessenger(stack);

            LongBuffer pSurface = stack.mallocLong(1);
            check(glfwCreateWindowSurface(instance, windowHandle, null, pSurface), "create the window surface");
            surface = pSurface.get(0);

            Candidate chosen = pickDevice(instance, stack, surface);
            if (chosen == null) throw new IllegalStateException("No Vulkan GPU can present to this window");
            physicalDevice = chosen.device;
            queueFamily = chosen.queueFamily;
            gpuName = chosen.name;
            apiVersion = Math.min(chosen.apiVersion, API_VERSION_CAP);

            createDevice(stack, chosen);
            createCommandObjects(stack);
            createSwapchain(stack);
        } catch (RuntimeException e) {
            close();
            throw e;
        }
        log.info("Vulkan: {} (API {}.{}.{}), {}x{} swapchain with {} images, present mode {}", gpuName,
                VK_API_VERSION_MAJOR(apiVersion), VK_API_VERSION_MINOR(apiVersion), VK_API_VERSION_PATCH(apiVersion),
                width, height, images.length, presentModeName(presentMode));
    }

    // ---- handles Skia needs -------------------------------------------------------------------------------------

    /**
     * The LWJGL objects behind the raw handles below, for the optional 3D layer, which records its own Vulkan work on
     * this device and queue.
     */
    public VkInstance vkInstance() {
        return instance;
    }

    public VkPhysicalDevice vkPhysicalDevice() {
        return physicalDevice;
    }

    public VkDevice vkDevice() {
        return device;
    }

    public VkQueue vkQueue() {
        return queue;
    }

    public long instance() {
        return instance.address();
    }

    public long physicalDevice() {
        return physicalDevice.address();
    }

    public long device() {
        return device.address();
    }

    public long queue() {
        return queue.address();
    }

    public int queueFamily() {
        return queueFamily;
    }

    public int apiVersion() {
        return apiVersion;
    }

    @Override
    public String gpuName() {
        return gpuName;
    }

    public long instanceProcAddr() {
        long address = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
        if (address == NULL) throw new IllegalStateException("vkGetInstanceProcAddr was not found");
        return address;
    }

    public long deviceProcAddr() {
        long address = glfwGetInstanceProcAddress(instance, "vkGetDeviceProcAddr");
        if (address == NULL) throw new IllegalStateException("vkGetDeviceProcAddr was not found");
        return address;
    }

    /**
     * The swapchain image acquired by the last successful {@link #beginFrame()}.
     */
    public long image() {
        return images[imageIndex];
    }

    public int format() {
        return swapchainFormat;
    }

    public int imageUsage() {
        return swapchainUsage;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * True when the swapchain's images are BGRA, false when they are RGBA.
     */
    public boolean isBgra() {
        return swapchainFormat == VK_FORMAT_B8G8R8A8_UNORM;
    }

    /**
     * Turns vsync on (FIFO) or off (IMMEDIATE, else MAILBOX). The swapchain is rebuilt on the next frame if the present
     * mode has to change.
     */
    /**
     * The window's Skia renderer on this device: opens Skia's Vulkan context and sizes it. Render thread, once.
     */
    @Override
    public VulkanSkikoRenderer createRenderer(int logicalWidth, int logicalHeight) {
        VulkanSkikoRenderer renderer = new VulkanSkikoRenderer(this);
        renderer.init();
        renderer.onResize(logicalWidth, logicalHeight);
        return renderer;
    }

    @Override
    public void setVsync(boolean vsync) {
        if (this.vsync == vsync) return;
        this.vsync = vsync;
        needsRecreate = true;
    }

    /**
     * The window's framebuffer changed size; the swapchain is rebuilt on the next frame.
     */
    public void resize() {
        needsRecreate = true;
    }

    /**
     * Acquires the next swapchain image and hands it over, ready for Skia to draw in. Returns false when there is
     * nothing to draw to this frame (minimized window, swapchain being rebuilt, no image within a second); the
     * frame is skipped and {@link #endFrame()} must not be called.
     */
    public boolean beginFrame() {
        try (MemoryStack stack = stackPush()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (needsRecreate || swapchain == VK_NULL_HANDLE) {
                    recreateSwapchain(stack);
                    if (swapchain == VK_NULL_HANDLE) return false; // zero-sized
                }

                check(vkWaitForFences(device, inFlight[slot], true, Long.MAX_VALUE), "wait for the frame fence");

                IntBuffer pIndex = stack.mallocInt(1);
                int result = vkAcquireNextImageKHR(device, swapchain, ACQUIRE_TIMEOUT_NANOS, acquired[slot], VK_NULL_HANDLE, pIndex);
                if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                    needsRecreate = true;
                    continue;
                }
                if (result == VK_TIMEOUT || result == VK_NOT_READY) return false; // occluded, or the display is busy
                if (result == VK_SUBOPTIMAL_KHR) needsRecreate = true; // still usable; rebuild after this frame
                else check(result, "acquire a swapchain image");

                imageIndex = pIndex.get(0);
                check(vkResetFences(device, inFlight[slot]), "reset the frame fence");

                // Wait for the display to let go of the image, then move it to the layout Skia is told it has. The
                // old contents are discarded (UNDEFINED); the renderer clears every frame.
                VkCommandBuffer cmd = preBuffers[slot];
                record(stack, cmd, images[imageIndex],
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        0, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT);
                VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
                        .waitSemaphoreCount(1)
                        .pWaitSemaphores(stack.longs(acquired[slot]))
                        .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                        .pCommandBuffers(stack.pointers(cmd));
                check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "submit the acquire barrier");
                return true;
            }
            return false;
        }
    }

    /**
     * Presents the image of the current frame. Call after Skia's work for the frame has been submitted.
     */
    public void endFrame() {
        endFrame(false);
    }

    /**
     * Presents the image of the current frame.
     *
     * @param readBack Skia read the picture back to the CPU this frame, which leaves the image as a transfer source
     *                 instead of a colour attachment
     */
    public void endFrame(boolean readBack) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer cmd = postBuffers[slot];
            if (readBack) {
                record(stack, cmd, images[imageIndex],
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                        0, 0);
            } else {
                record(stack, cmd, images[imageIndex],
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0);
            }
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(stack.longs(renderFinished[imageIndex]));
            check(vkQueueSubmit(queue, submit, inFlight[slot]), "submit the present barrier");

            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default()
                    .pWaitSemaphores(stack.longs(renderFinished[imageIndex]))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain))
                    .pImageIndices(stack.ints(imageIndex));
            int result = vkQueuePresentKHR(queue, present);
            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) needsRecreate = true;
            else check(result, "present");

            imageIndex = -1;
            slot = (slot + 1) % FRAMES_IN_FLIGHT;
        }
    }

    /**
     * Blocks until the GPU has finished everything submitted so far. Render thread, before the renderer frees its
     * Skia objects.
     */
    public void waitIdle() {
        if (device != null) vkDeviceWaitIdle(device);
    }

    /**
     * Destroys everything, in the order Vulkan needs. The renderer must be disposed first, and the GLFW window must
     * still exist. Safe to call twice.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (device != null) {
            vkDeviceWaitIdle(device);
            destroySwapchain();
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                if (acquired[i] != VK_NULL_HANDLE) vkDestroySemaphore(device, acquired[i], null);
                if (inFlight[i] != VK_NULL_HANDLE) vkDestroyFence(device, inFlight[i], null);
            }
            if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, null);
            vkDestroyDevice(device, null);
            device = null;
        }
        if (instance != null) {
            if (surface != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance, surface, null);
            surface = VK_NULL_HANDLE;
            if (debugMessenger != VK_NULL_HANDLE) vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
            debugMessenger = VK_NULL_HANDLE;
            vkDestroyInstance(instance, null);
            instance = null;
        }
        if (debugCallback != null) {
            debugCallback.free();
            debugCallback = null;
        }
    }

    private static VkInstance createInstance(MemoryStack stack, boolean validation) {
        PointerBuffer required = glfwGetRequiredInstanceExtensions();
        if (required == null) throw new IllegalStateException("GLFW found no Vulkan surface extensions");

        Set<String> available = instanceExtensions();

        List<String> extensions = new ArrayList<>();
        for (int i = 0; i < required.remaining(); i++) extensions.add(memASCII(required.get(i)));
        // MoltenVK is a "portability" driver: newer loaders only list it when asked to.
        boolean portability = available.contains(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
        if (portability) extensions.add(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);

        List<String> layers = new ArrayList<>();
        if (validation) {
            if (available.contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME) && hasLayer(VALIDATION_LAYER)) {
                extensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
                layers.add(VALIDATION_LAYER);
            } else {
                log.warn("rengl.vulkan.validation is set but {} isn't available (when LWJGL loads MoltenVK directly "
                        + "there are no layers; load the Vulkan loader with -Dorg.lwjgl.vulkan.libname).", VALIDATION_LAYER);
            }
        }

        int instanceVersion = Math.max(VK.getInstanceVersionSupported(), VK_API_VERSION_1_0);
        int requested = Math.min(instanceVersion, API_VERSION_CAP);
        if (requested < VK_API_VERSION_1_1) {
            throw new IllegalStateException("The Vulkan loader only supports 1.0; Skia needs 1.1");
        }

        VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                .pApplicationName(stack.UTF8("RenGL"))
                .pEngineName(stack.UTF8("RenGL"))
                .apiVersion(requested);
        // Skia draws triangle strips with primitive restart off, which Metal can't do; MoltenVK logs a warning for every
        // such pipeline, which is a lot of console spam. Show only its errors, unless the user chose a level themselves.
        boolean quietMoltenVk = available.contains("VK_EXT_layer_settings") && !validation
                && System.getProperty("os.name", "").toLowerCase().contains("mac")
                && System.getenv("MVK_CONFIG_LOG_LEVEL") == null && !Boolean.getBoolean("rengl.vulkan.mvkLog");
        if (quietMoltenVk) extensions.add("VK_EXT_layer_settings");

        VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default()
                .flags(portability ? VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR : 0)
                .pApplicationInfo(app)
                .ppEnabledExtensionNames(namesOf(stack, extensions));
        if (!layers.isEmpty()) info.ppEnabledLayerNames(namesOf(stack, layers));
        if (quietMoltenVk) {
            VkLayerSettingEXT.Buffer settings = VkLayerSettingEXT.calloc(1, stack);
            settings.get(0)
                    .pLayerName(stack.UTF8("MoltenVK"))
                    .pSettingName(stack.UTF8("MVK_CONFIG_LOG_LEVEL"))
                    .type(EXTLayerSettings.VK_LAYER_SETTING_TYPE_INT32_EXT)
                    .valueCount(1)
                    .pValues(stack.malloc(4).putInt(0, 1));
            info.pNext(VkLayerSettingsCreateInfoEXT.calloc(stack).sType$Default().pSettings(settings));
        }

        PointerBuffer pInstance = stack.mallocPointer(1);
        check(vkCreateInstance(info, null, pInstance), "create the Vulkan instance");
        return new VkInstance(pInstance.get(0), info);
    }

    // The extension and layer lists can run to hundreds of entries, more than fits in the shared 64 KB stack (NVIDIA's
    // Windows driver lists enough device extensions to overflow it by itself), so the lists are heap buffers.
    private static Set<String> instanceExtensions() {
        Set<String> names = new HashSet<>();
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null);
            VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0));
            try {
                vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, props);
                for (VkExtensionProperties p : props) names.add(p.extensionNameString());
            } finally {
                props.free();
            }
        }
        return names;
    }

    private static Set<String> deviceExtensions(VkPhysicalDevice device) {
        Set<String> names = new HashSet<>();
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, count, null);
            VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0));
            try {
                vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, count, props);
                for (VkExtensionProperties p : props) names.add(p.extensionNameString());
            } finally {
                props.free();
            }
        }
        return names;
    }

    private static boolean hasLayer(String name) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumerateInstanceLayerProperties(count, null);
            VkLayerProperties.Buffer layers = VkLayerProperties.malloc(count.get(0));
            try {
                vkEnumerateInstanceLayerProperties(count, layers);
                for (VkLayerProperties layer : layers) {
                    if (name.equals(layer.layerNameString())) return true;
                }
            } finally {
                layers.free();
            }
        }
        return false;
    }

    private static PointerBuffer namesOf(MemoryStack stack, List<String> names) {
        PointerBuffer buffer = stack.mallocPointer(names.size());
        for (String name : names) buffer.put(stack.UTF8(name));
        return buffer.flip();
    }

    private void installDebugMessenger(MemoryStack stack) {
        if (!instance.getCapabilities().VK_EXT_debug_utils) return;
        debugCallback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, pData, user) -> {
            String message = VkDebugUtilsMessengerCallbackDataEXT.create(pData).pMessageString();
            if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) log.error("[Vulkan] {}", message);
            else if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0)
                log.warn("[Vulkan] {}", message);
            else log.debug("[Vulkan] {}", message);
            return VK_FALSE;
        });
        VkDebugUtilsMessengerCreateInfoEXT info = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(debugCallback);
        LongBuffer pMessenger = stack.mallocLong(1);
        if (vkCreateDebugUtilsMessengerEXT(instance, info, null, pMessenger) == VK_SUCCESS) {
            debugMessenger = pMessenger.get(0);
            log.info("Vulkan validation layer enabled.");
        }
    }

    private record Candidate(VkPhysicalDevice device, int queueFamily, String name, int apiVersion, int score) {
    }

    /**
     * The best GPU that can present and run a swapchain, or null. With a surface, presentation is checked against it;
     * without one (the startup probe), against the platform's presentation support. {@code rengl.vulkan.gpu} picks a
     * GPU by a part of its name.
     */
    private static Candidate pickDevice(VkInstance instance, MemoryStack stack, long surface) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumeratePhysicalDevices(instance, count, null);
        if (count.get(0) == 0) return null;
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        vkEnumeratePhysicalDevices(instance, count, devices);

        String wanted = System.getProperty("rengl.vulkan.gpu");
        Candidate best = null;
        for (int i = 0; i < devices.capacity(); i++) {
            VkPhysicalDevice device = new VkPhysicalDevice(devices.get(i), instance);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device, props);
            String name = props.deviceNameString();
            if (props.apiVersion() < VK_API_VERSION_1_1) continue;
            if (!supportsSwapchain(device)) continue;
            int family = findQueueFamily(instance, device, stack, surface);
            if (family < 0) continue;
            if (surface != 0L && !hasSurfaceFormats(device, surface, stack)) continue;

            int score = switch (props.deviceType()) {
                case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 1000;
                case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 500;
                case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 100;
                default -> 0;
            };
            if (wanted != null && !wanted.isBlank() && name.toLowerCase().contains(wanted.toLowerCase()))
                score += 10_000;
            if (best == null || score > best.score)
                best = new Candidate(device, family, name, props.apiVersion(), score);
        }
        return best;
    }

    private static boolean supportsSwapchain(VkPhysicalDevice device) {
        return deviceExtensions(device).contains(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    }

    /**
     * A queue family that does graphics and can present. Skia and presentation share one queue, so a GPU whose
     * presenting family can't draw is not usable.
     */
    private static int findQueueFamily(VkInstance instance, VkPhysicalDevice device, MemoryStack stack, long surface) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);
        IntBuffer supported = stack.mallocInt(1);
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            boolean present;
            if (surface != 0L) {
                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, supported);
                present = supported.get(0) == VK_TRUE;
            } else {
                present = glfwGetPhysicalDevicePresentationSupport(instance, device, i);
            }
            if (present) return i;
        }
        return -1;
    }

    private static boolean hasSurfaceFormats(VkPhysicalDevice device, long surface, MemoryStack stack) {
        IntBuffer formats = stack.mallocInt(1), modes = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, formats, null);
        vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, modes, null);
        return formats.get(0) > 0 && modes.get(0) > 0;
    }

    /** Whether the chosen GPU can do derivatives in a linear compute group (the extension alone does not say). */
    private boolean physicalDeviceSupportsDerivatives(MemoryStack stack) {
        VkPhysicalDeviceComputeShaderDerivativesFeaturesKHR supported = VkPhysicalDeviceComputeShaderDerivativesFeaturesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(supported.address());
        vkGetPhysicalDeviceFeatures2(physicalDevice, features2); // every device here is Vulkan 1.1 or later
        return supported.computeDerivativeGroupLinear();
    }

    private void createDevice(MemoryStack stack, Candidate chosen) {
        // Skiko hands Skia no feature list, so Skia assumes every feature the GPU supports is on. Turn them all on,
        // except robust buffer access, which costs speed and which Skia doesn't need.
        VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
        vkGetPhysicalDeviceFeatures(physicalDevice, features);
        features.robustBufferAccess(false);

        List<String> extensions = new ArrayList<>();
        extensions.add(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
        // A device that advertises the portability subset (MoltenVK) requires it to be enabled.
        Set<String> available = deviceExtensions(physicalDevice);
        if (available.contains("VK_KHR_portability_subset"))
            extensions.add("VK_KHR_portability_subset");

        // The compute shaders of AMD's FidelityFX upscaler (loaded by rengl-upscale) declare derivatives in a compute
        // group, so the device has to have them on when the GPU can.
        VkPhysicalDeviceComputeShaderDerivativesFeaturesKHR derivatives = null;
        if (available.contains(VK_KHR_COMPUTE_SHADER_DERIVATIVES_EXTENSION_NAME) && physicalDeviceSupportsDerivatives(stack)) {
            extensions.add(VK_KHR_COMPUTE_SHADER_DERIVATIVES_EXTENSION_NAME);
            derivatives = VkPhysicalDeviceComputeShaderDerivativesFeaturesKHR.calloc(stack).sType$Default()
                    .computeDerivativeGroupLinear(true);
        }

        VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack);
        queues.get(0).sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1f));

        VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack).sType$Default()
                .pQueueCreateInfos(queues)
                .ppEnabledExtensionNames(namesOf(stack, extensions))
                .pEnabledFeatures(features);
        if (derivatives != null) info.pNext(derivatives.address());
        PointerBuffer pDevice = stack.mallocPointer(1);
        check(vkCreateDevice(physicalDevice, info, null, pDevice), "create the Vulkan device");
        device = new VkDevice(pDevice.get(0), physicalDevice, info);

        PointerBuffer pQueue = stack.mallocPointer(1);
        vkGetDeviceQueue(device, queueFamily, 0, pQueue);
        queue = new VkQueue(pQueue.get(0), device);
    }

    private void createCommandObjects(MemoryStack stack) {
        VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(queueFamily);
        LongBuffer pPool = stack.mallocLong(1);
        check(vkCreateCommandPool(device, poolInfo, null, pPool), "create the command pool");
        commandPool = pPool.get(0);

        VkCommandBufferAllocateInfo alloc = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(FRAMES_IN_FLIGHT * 2);
        PointerBuffer pBuffers = stack.mallocPointer(FRAMES_IN_FLIGHT * 2);
        check(vkAllocateCommandBuffers(device, alloc, pBuffers), "allocate command buffers");

        VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
        VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);
        LongBuffer handle = stack.mallocLong(1);
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            preBuffers[i] = new VkCommandBuffer(pBuffers.get(i * 2), device);
            postBuffers[i] = new VkCommandBuffer(pBuffers.get(i * 2 + 1), device);
            check(vkCreateSemaphore(device, semaphoreInfo, null, handle), "create a semaphore");
            acquired[i] = handle.get(0);
            check(vkCreateFence(device, fenceInfo, null, handle), "create a fence");
            inFlight[i] = handle.get(0);
        }
    }

    // ---- swapchain ----------------------------------------------------------------------------------------------

    private void recreateSwapchain(MemoryStack stack) {
        vkDeviceWaitIdle(device); // Skia may still be using the old images
        createSwapchain(stack);
    }

    /**
     * (Re)builds the swapchain for the surface's current size, leaving {@code swapchain} null when the surface has no
     * area (a minimized window).
     */
    private void createSwapchain(MemoryStack stack) {
        VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
        check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, caps), "query the surface");

        int w = caps.currentExtent().width(), h = caps.currentExtent().height();
        if (w == 0xFFFFFFFF) { // the surface takes whatever the swapchain asks for: use the framebuffer size
            IntBuffer fw = stack.mallocInt(1), fh = stack.mallocInt(1);
            org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(windowHandle, fw, fh);
            w = Math.clamp(fw.get(0), caps.minImageExtent().width(), caps.maxImageExtent().width());
            h = Math.clamp(fh.get(0), caps.minImageExtent().height(), caps.maxImageExtent().height());
        }
        if (w <= 0 || h <= 0) {
            destroySwapchain();
            needsRecreate = true; // try again next frame
            return;
        }

        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, null);
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, formats);
        int format = -1, colorSpace = 0;
        for (int wanted : new int[]{VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM}) {
            for (VkSurfaceFormatKHR f : formats) {
                if (f.format() == wanted && f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    format = f.format();
                    colorSpace = f.colorSpace();
                    break;
                }
            }
            if (format != -1) break;
        }
        // Skia is told the surface is sRGB and does its own encoding, so a *_SRGB format would encode twice.
        if (format == -1) throw new IllegalStateException("The surface offers no 8-bit UNORM format");

        presentMode = choosePresentMode(stack);

        int minImages = Math.max(caps.minImageCount() + 1, 3);
        if (caps.maxImageCount() > 0) minImages = Math.min(minImages, caps.maxImageCount());

        // Skia may read pixels back or blit, so ask for transfer usage where the surface allows it.
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        int supportedUsage = caps.supportedUsageFlags();
        if ((supportedUsage & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) != 0) usage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        if ((supportedUsage & VK_IMAGE_USAGE_TRANSFER_DST_BIT) != 0) usage |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;

        int alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        if ((caps.supportedCompositeAlpha() & alpha) == 0) {
            alpha = Integer.lowestOneBit(caps.supportedCompositeAlpha());
        }

        VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default()
                .surface(surface)
                .minImageCount(minImages)
                .imageFormat(format)
                .imageColorSpace(colorSpace)
                .imageArrayLayers(1)
                .imageUsage(usage)
                .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .preTransform(caps.currentTransform())
                .compositeAlpha(alpha)
                .presentMode(presentMode)
                .clipped(true)
                .oldSwapchain(swapchain);
        info.imageExtent().set(w, h);

        LongBuffer pSwapchain = stack.mallocLong(1);
        int result = vkCreateSwapchainKHR(device, info, null, pSwapchain);
        destroySwapchain(); // the old one is retired by now (we waited for idle)
        check(result, "create the swapchain");

        swapchain = pSwapchain.get(0);
        swapchainFormat = format;
        swapchainUsage = usage;
        width = w;
        height = h;

        vkGetSwapchainImagesKHR(device, swapchain, count, null);
        LongBuffer pImages = stack.mallocLong(count.get(0));
        vkGetSwapchainImagesKHR(device, swapchain, count, pImages);
        images = new long[count.get(0)];
        pImages.get(images);

        renderFinished = new long[images.length];
        VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
        LongBuffer pSemaphore = stack.mallocLong(1);
        for (int i = 0; i < images.length; i++) {
            check(vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore), "create a semaphore");
            renderFinished[i] = pSemaphore.get(0);
        }
        needsRecreate = false;
    }

    private int choosePresentMode(MemoryStack stack) {
        if (vsync) return VK_PRESENT_MODE_FIFO_KHR; // the one mode every driver has
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, count, null);
        IntBuffer modes = stack.mallocInt(count.get(0));
        vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, count, modes);
        boolean mailbox = false;
        for (int i = 0; i < modes.capacity(); i++) {
            if (modes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR) return VK_PRESENT_MODE_IMMEDIATE_KHR;
            if (modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) mailbox = true;
        }
        return mailbox ? VK_PRESENT_MODE_MAILBOX_KHR : VK_PRESENT_MODE_FIFO_KHR;
    }

    /**
     * Frees the swapchain and its semaphores. The device must be idle.
     */
    private void destroySwapchain() {
        for (long semaphore : renderFinished) {
            if (semaphore != VK_NULL_HANDLE) vkDestroySemaphore(device, semaphore, null);
        }
        renderFinished = new long[0];
        images = new long[0];
        if (swapchain != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device, swapchain, null);
            swapchain = VK_NULL_HANDLE;
        }
    }

    private static String presentModeName(int mode) {
        return switch (mode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE";
            case VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED";
            default -> "FIFO";
        };
    }

    // ---- recording ----------------------------------------------------------------------------------------------

    private void record(MemoryStack stack, VkCommandBuffer cmd, long image, int oldLayout, int newLayout,
                        int srcStage, int dstStage, int srcAccess, int dstAccess) {
        vkResetCommandBuffer(cmd, 0);
        VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        check(vkBeginCommandBuffer(cmd, begin), "begin a command buffer");

        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(srcAccess)
                .dstAccessMask(dstAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);

        check(vkEndCommandBuffer(cmd), "end a command buffer");
    }

    private static void check(int result, String action) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("Vulkan failed to " + action + " (VkResult " + result + ")");
        }
    }
}
