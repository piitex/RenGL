package me.piitex.engine.upscale.fsr;

import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.upscale.Upscaler;
import me.piitex.engine.render3d.upscale.UpscalerProvider;
import me.piitex.engine.render3d.vk.VkGpuDevice;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.WeakHashMap;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;

/**
 * AMD FidelityFX Super Resolution: FSR 4 on GPUs that run it, FSR 3 on any other Vulkan GPU. Listed for discovery in
 * {@code META-INF/services}, so having this module on the class path is all it takes for
 * {@link me.piitex.engine.render3d.upscale.Upscalers#available} to offer it.
 * <p>
 * It needs AMD's libraries, which this module does not carry (see {@link Ffx}); without them, or off Vulkan, it says it is
 * not available. {@link #unavailableReason()} tells why.
 * <pre>{@code
 * scene.setRenderScale(0.67f);
 * scene.setUpscaler(new FsrProvider());
 * }</pre>
 * System properties: {@code rengl.fsr.dir} names the folder of the libraries, {@code rengl.fsr.version} picks a version by a
 * part of its name (such as "3.1"; unset, the library picks the newest that works), and {@code rengl.fsr.debug} turns on the
 * SDK's checks of what it is given.
 */
public final class FsrProvider implements UpscalerProvider {
    private static final Logger log = LogManager.getLogger("Engine");
    private static final Map<GpuDevice, Boolean> probed = new WeakHashMap<>();

    @Override
    public String name() {
        return "FSR";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean isAvailable(GpuDevice device) {
        return reason(device) == null;
    }

    /**
     * Why FSR can not be used, or null where it can: the libraries missing, a device that is not Vulkan, or a context the
     * SDK would not make.
     */
    public static String unavailableReason() {
        Ffx ffx = Ffx.load();
        return ffx == null ? Ffx.failure() : null;
    }

    private static String reason(GpuDevice device) {
        if (!(device instanceof VkGpuDevice vk)) return "FSR needs Vulkan";
        Ffx ffx = Ffx.load();
        if (ffx == null) return Ffx.failure();
        // FidelityFX reads a 32-bit float depth buffer.
        if (vk.depthFormat() != VK_FORMAT_D32_SFLOAT) return "FSR needs a 32-bit float depth buffer";
        synchronized (probed) {
            Boolean works = probed.get(device);
            if (works == null) {
                works = probe(ffx, vk);
                probed.put(device, works);
            }
            return works ? null : "the FidelityFX upscaler would not start on " + device.name();
        }
    }

    /**
     * Makes a context of a plain size and drops it: whether the SDK takes this GPU is not known before.
     */
    private static boolean probe(Ffx ffx, VkGpuDevice device) {
        try (Ffx.Context ignored = ffx.createContext(device, Ffx.ENABLE_HIGH_DYNAMIC_RANGE | Ffx.ENABLE_AUTO_EXPOSURE, 640, 360, 1280, 720, null)) {
            return true;
        } catch (RuntimeException | LinkageError e) {
            log.warn("FSR is not available on {}: {}", device.name(), e.toString());
            return false;
        }
    }

    @Override
    public Upscaler create(GpuDevice device) {
        String reason = reason(device);
        if (reason != null) throw new IllegalStateException("FSR is not available: " + reason);
        return new FsrUpscaler((VkGpuDevice) device, Ffx.load());
    }
}
