package me.piitex.engine.upscale.metalfx;

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
 * Apple MetalFX temporal upscaling, on a Mac (macOS 13 or newer, Apple silicon or an AMD or Intel GPU that supports it).
 * Listed for discovery in {@code META-INF/services}, so having this module on the class path is all it takes for
 * {@link me.piitex.engine.render3d.upscale.Upscalers#available} to offer it.
 * <p>
 * The 3D layer runs on Vulkan there, through MoltenVK, and MetalFX works on Metal textures; the two meet through
 * {@code VK_EXT_metal_objects}, which hands out the Metal texture behind a Vulkan image (see {@link MetalFxExtensions}). Nothing
 * is downloaded or bundled: MetalFX is part of macOS. Off macOS, or on a MoltenVK without that extension, the provider says it is
 * not available and {@link #unavailableReason} tells why.
 * <pre>{@code
 * scene.setRenderScale(0.67f);
 * scene.setUpscaler(new MetalFxProvider());
 * }</pre>
 * Two conventions of MetalFX that Apple does not state plainly can be flipped if the picture shows trails behind moving things or
 * shimmers: {@code -Drengl.metalfx.flip=motion,jitterx,jittery} (any of the three, comma-separated) reverses the sign of the motion
 * vectors and of the camera jitter on that axis.
 */
public final class MetalFxProvider implements UpscalerProvider {
    private static final Logger log = LogManager.getLogger("Engine");
    private static final Map<GpuDevice, String> probed = new WeakHashMap<>();
    /** MetalFX scales by at most three. */
    private static final float MIN_RENDER_SCALE = 0.334f;

    @Override
    public String name() {
        return "MetalFX";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean isAvailable(GpuDevice device) {
        return unavailableReason(device) == null;
    }

    @Override
    public float supportedRenderScale(float requested, int displayWidth, int displayHeight) {
        return Math.max(requested, MIN_RENDER_SCALE);
    }

    /**
     * Why MetalFX can not be used on the device, or null where it can: not a Mac, a device that is not Vulkan, MoltenVK without
     * {@code VK_EXT_metal_objects}, no MetalFX in this macOS, or a GPU that does not take it.
     */
    public static String unavailableReason(GpuDevice device) {
        if (!ObjC.isMac()) return "MetalFX is Apple's and needs macOS";
        if (!(device instanceof VkGpuDevice vk)) return "MetalFX needs Vulkan";
        ObjC objc = ObjC.load();
        if (objc == null) return ObjC.failure();
        // MetalFX reads a 32-bit float depth buffer (MTLPixelFormatDepth32Float).
        if (vk.depthFormat() != VK_FORMAT_D32_SFLOAT) return "MetalFX needs a 32-bit float depth buffer";
        if (!MetalObjects.enabled(vk)) return "the Vulkan device was made without VK_EXT_metal_objects (MoltenVK did not offer it)";
        synchronized (probed) {
            if (!probed.containsKey(device)) probed.put(device, probe(objc, vk));
            return probed.get(device);
        }
    }

    /** The reason, or null, for a device not asked before. */
    private static String probe(ObjC objc, VkGpuDevice vk) {
        try {
            long descriptor = objc.cls("MTLFXTemporalScalerDescriptor");
            if (descriptor == 0L) return "this macOS has no MetalFX temporal scaler (it needs macOS 13)";
            MetalObjects metal = MetalObjects.of(vk);
            if (!objc.ask(descriptor, "supportsDevice:", metal.device())) return "MetalFX does not support " + vk.name();
            return null;
        } catch (RuntimeException | LinkageError e) {
            log.warn("MetalFX is not available on {}: {}", vk.name(), e.toString());
            return "MetalFX would not start on " + vk.name() + ": " + e;
        }
    }

    @Override
    public Upscaler create(GpuDevice device) {
        String reason = unavailableReason(device);
        if (reason != null) throw new IllegalStateException("MetalFX is not available: " + reason);
        return new MetalFxUpscaler((VkGpuDevice) device, ObjC.load());
    }
}
