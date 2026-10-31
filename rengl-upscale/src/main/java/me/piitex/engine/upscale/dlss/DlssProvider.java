package me.piitex.engine.upscale.dlss;

import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.upscale.Upscaler;
import me.piitex.engine.render3d.upscale.UpscalerProvider;
import me.piitex.engine.render3d.vk.VkGpuDevice;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * NVIDIA DLSS super resolution on Vulkan, on a GeForce RTX card, through NVIDIA's Streamline libraries. Listed for discovery in
 * {@code META-INF/services}, so having this module on the class path is all it takes for
 * {@link me.piitex.engine.render3d.upscale.Upscalers#available} to offer it, ahead of FSR.
 * <p>
 * The libraries are not part of this module (see {@link Sl}): with them in the folder named by {@code -Drengl.dlss.dir} the
 * window's Vulkan device is made with the extensions DLSS wants ({@link DlssExtensions}) and this provider is available; without
 * them, off Windows, off Vulkan or on a GPU that is not an RTX it says it is not, and {@link #unavailableReason(GpuDevice)} tells why.
 * <pre>{@code
 * scene.setRenderScale(0.67f);
 * scene.setUpscaler(new DlssProvider());
 * }</pre>
 * {@code -Drengl.dlss=false} turns DLSS off; {@code -Drengl.dlss.log=true} shows Streamline's own log.
 */
public final class DlssProvider implements UpscalerProvider {
    private static final Logger log = LogManager.getLogger("Engine");
    private static final Map<GpuDevice, String> probed = new WeakHashMap<>();
    private static final Set<Long> attached = new HashSet<>();
    private static final String WORKS = "";

    @Override
    public String name() {
        return "DLSS";
    }

    @Override
    public int priority() {
        return 200;
    }

    @Override
    public boolean isAvailable(GpuDevice device) {
        return unavailableReason(device) == null;
    }

    /** Why DLSS can not be used at all on this machine, or null: the libraries missing, turned off, or Streamline would not start. */
    public static String unavailableReason() {
        Sl sl = Sl.load();
        if (sl == null) return Sl.failure();
        sl.start();
        return sl.startFailure();
    }

    /** Why DLSS can not be used on this device, or null where it can. */
    public static String unavailableReason(GpuDevice device) {
        if (!(device instanceof VkGpuDevice vk)) return "DLSS needs Vulkan";
        String general = unavailableReason();
        if (general != null) return general;
        synchronized (probed) {
            String outcome = probed.get(device);
            if (outcome == null) {
                outcome = probe(Sl.load(), vk);
                probed.put(device, outcome);
            }
            return outcome == WORKS ? null : outcome;
        }
    }

    private static boolean hasExtension(VkGpuDevice device, String name) {
        var capabilities = device.vkDevice().getCapabilities();
        return switch (name) {
            case "VK_NVX_binary_import" -> capabilities.VK_NVX_binary_import;
            case "VK_NVX_image_view_handle" -> capabilities.VK_NVX_image_view_handle;
            // The core feature of Vulkan 1.2 stands in for this extension (see VulkanContext), so it is not looked for.
            case "VK_EXT_buffer_device_address" -> true;
            case "VK_KHR_push_descriptor" -> capabilities.VK_KHR_push_descriptor;
            default -> true;
        };
    }

    /** The devices are gone: a new one has to be told to Streamline again, and asked about again. */
    static void forgetDevices() {
        synchronized (attached) {
            attached.clear();
        }
        synchronized (probed) {
            probed.clear();
        }
    }

    /** Tells Streamline about the device, once. */
    static boolean attach(Sl sl, VkGpuDevice device) {
        return attach(sl, device.vkDevice().address(), device.vkInstance().address(), device.vkPhysicalDevice().address(),
                device.queueFamily(), 0, 0);
    }

    /** The same from raw handles, with the queues of the family that Streamline's work runs on (see {@link Sl#vulkanInfo}). */
    static synchronized boolean attach(Sl sl, long device, long instance, long physicalDevice, int queueFamily, int graphicsQueueIndex,
                                       int computeQueueIndex) {
        synchronized (attached) {
            if (attached.contains(device)) return true;
        }
        try (Arena arena = Arena.ofConfined()) {
            int result = sl.setVulkanInfo(Sl.vulkanInfo(arena, device, instance, physicalDevice, queueFamily, graphicsQueueIndex,
                    computeQueueIndex));
            if (result != Sl.OK) {
                log.warn("slSetVulkanInfo returned {}", Sl.describe(result));
                return false;
            }
        }
        synchronized (attached) {
            attached.add(device);
        }
        return true;
    }

    private static String probe(Sl sl, VkGpuDevice device) {
        try {
            for (String extension : sl.start().deviceExtensions()) {
                if (!hasExtension(device, extension)) {
                    return device.name() + " was not made with " + extension + " (an NVIDIA RTX GPU is needed, and the module has to be on the class path before the window opens)";
                }
            }
            if (!attach(sl, device)) return "Streamline would not take the Vulkan device of " + device.name();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment adapter = Sl.adapter(arena, device.vkPhysicalDevice().address());
                return sl.isSupported(Sl.FEATURE_DLSS, adapter) ? WORKS : "DLSS super resolution is not supported on " + device.name();
            }
        } catch (RuntimeException | LinkageError e) {
            log.warn("DLSS is not available on {}: {}", device.name(), e.toString());
            return "DLSS would not start on " + device.name() + ": " + e;
        }
    }

    @Override
    public Upscaler create(GpuDevice device) {
        String reason = unavailableReason(device);
        if (reason != null) throw new IllegalStateException("DLSS is not available: " + reason);
        return new DlssUpscaler((VkGpuDevice) device, Sl.load());
    }
}
