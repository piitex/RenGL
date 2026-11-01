package me.piitex.engine.upscale.dlss;

import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.upscale.Upscaler;
import me.piitex.engine.render3d.upscale.UpscalerProvider;
import me.piitex.engine.render3d.vk.VkGpuDevice;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import static java.lang.foreign.ValueLayout.JAVA_INT;

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
        synchronized (reconstruction) {
            reconstruction.clear();
        }
    }

    private static final Map<GpuDevice, Boolean> reconstruction = new WeakHashMap<>();

    /**
     * Whether DLSS Ray Reconstruction can clean up path tracing on this device: its libraries were loaded with Streamline (they are
     * beside the interposer, see {@link Sl#RAY_RECONSTRUCTION_LIBRARIES}) and the GPU supports it. Asked after DLSS itself was found
     * to work on the device.
     */
    static boolean rayReconstructionAvailable(VkGpuDevice device) {
        Sl sl = Sl.load();
        if (sl == null || !sl.rayReconstructionLoaded()) return false;
        synchronized (reconstruction) {
            return reconstruction.computeIfAbsent(device, d -> {
                try (Arena arena = Arena.ofConfined()) {
                    int result = sl.support(Sl.FEATURE_DLSS_RR, Sl.adapter(arena, device.vkPhysicalDevice().address()));
                    if (result != Sl.OK) log.info("DLSS Ray Reconstruction is not supported on {}: {}", device.name(), Sl.describe(result));
                    return result == Sl.OK;
                } catch (RuntimeException | LinkageError e) {
                    log.warn("DLSS Ray Reconstruction would not start on {}: {}", device.name(), e.toString());
                    return false;
                }
            });
        }
    }

    /** Why Ray Reconstruction can not be used on this device, or null where it can. */
    public static String rayReconstructionUnavailableReason(GpuDevice device) {
        String general = unavailableReason(device);
        if (general != null) return general;
        VkGpuDevice vk = (VkGpuDevice) device;
        if (rayReconstructionAvailable(vk)) return null;
        String why = Sl.load().rayReconstructionFailure();
        return why != null ? why : "DLSS Ray Reconstruction is not supported on " + vk.name();
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

    /**
     * What DLSS says of one mode at a display size ({@code sl::DLSSOptimalSettings}): the render widths it takes. Outside them the
     * picture it gives back is black.
     */
    record Range(int mode, int optimalWidth, int minWidth, int maxWidth) {
    }

    // sl::DLSSMode, from the smallest picture to the largest
    private static final int[] MODES = {4, 1, 2, 3, 6};

    private static MethodHandle getOptimalSettings;
    private static List<Range> ranges = List.of();
    private static long rangesFor = -1;

    /** The render widths each mode takes when the picture is {@code displayWidth} by {@code displayHeight}; empty if DLSS would not say. */
    static synchronized List<Range> ranges(int displayWidth, int displayHeight) {
        long key = (long) displayWidth << 32 | displayHeight;
        if (key == rangesFor) return ranges;
        List<Range> found = new ArrayList<>();
        Sl sl = Sl.load();
        if (sl != null) {
            try (Arena arena = Arena.ofConfined()) {
                if (getOptimalSettings == null) getOptimalSettings = sl.featureFunction(Sl.FEATURE_DLSS, "slDLSSGetOptimalSettings", 2);
                for (int mode : MODES) {
                    MemorySegment settings = Sl.struct(arena, 64, "EF1D0957-FD58-4DF7-B504-8B69D8AA6B76", 1);
                    int result = (int) getOptimalSettings.invokeExact(DlssUpscaler.options(arena, mode, displayWidth, displayHeight), settings);
                    if (result == Sl.OK) found.add(new Range(mode, settings.get(JAVA_INT, 32), settings.get(JAVA_INT, 44), settings.get(JAVA_INT, 52)));
                }
            } catch (Throwable t) {
                log.warn("DLSS would not say which render sizes it takes: {}", t.toString());
            }
        }
        log.debug("DLSS render widths at {}x{}: {}", displayWidth, displayHeight, found);
        ranges = found;
        rangesFor = key;
        return found;
    }

    /**
     * DLSS takes the render widths of the modes it has, and between them there are gaps: at 1920 wide, Ultra Performance takes
     * only 640 and the other modes 960 to 1920, so 700 or 900 come back black. The scale moves to the nearest width it takes.
     */
    @Override
    public float supportedRenderScale(float requested, int displayWidth, int displayHeight) {
        if (requested >= 1f || displayWidth <= 0) return requested;
        List<Range> modes = ranges(displayWidth, displayHeight);
        int wanted = Math.max(1, Math.round(displayWidth * requested)), best = -1;
        for (Range range : modes) {
            if (wanted >= range.minWidth() && wanted <= range.maxWidth()) return requested;
            for (int edge : new int[]{range.minWidth(), range.maxWidth()}) {
                if (edge > 0 && (best < 0 || Math.abs(edge - wanted) < Math.abs(best - wanted))) best = edge;
            }
        }
        return best < 0 ? requested : (float) best / displayWidth;
    }

    @Override
    public Upscaler create(GpuDevice device) {
        String reason = unavailableReason(device);
        if (reason != null) throw new IllegalStateException("DLSS is not available: " + reason);
        return new DlssUpscaler((VkGpuDevice) device, Sl.load());
    }
}
