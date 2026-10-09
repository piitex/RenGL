package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.gl.renderer.GpuInterop;
import me.piitex.engine.render3d.gl.GlDevice;
import me.piitex.engine.render3d.vk.VkGpuDevice;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The 3D layer's {@link GpuDevice} for one window, made the first time something in that window needs it and closed
 * with the window's renderer. A window that never shows a {@link Viewport3D} never gets one, so the 3D layer costs
 * nothing until it is used.
 * <p>
 * Render thread only.
 */
final class Gpu3D {
    private static final Logger log = LogManager.getLogger("Engine");
    private static final Map<GpuInterop, Gpu3D> BY_RENDERER = new HashMap<>();

    final GpuInterop interop;
    final GpuDevice device;
    private final Set<Viewport3D> viewports = new LinkedHashSet<>();

    private Gpu3D(GpuInterop interop, GpuDevice device) {
        this.interop = interop;
        this.device = device;
    }

    /**
     * The window's device, created on first use. Throws if the GPU can't run the 3D layer.
     */
    static Gpu3D of(GpuInterop interop) {
        Gpu3D existing = BY_RENDERER.get(interop);
        if (existing != null) {
            return existing;
        }

        GpuDevice device = interop.gpuApi() == RenderAPI.VULKAN
                ? new VkGpuDevice(interop.vulkanContext(), interop::frameNumber)
                : new GlDevice(interop::frameNumber);
        Gpu3D created = new Gpu3D(interop, device);
        BY_RENDERER.put(interop, created);
        interop.onDispose(created::shutdown);
        log.info("3D layer started on {} ({})", device.api(), device.name());
        return created;
    }

    void attach(Viewport3D viewport) {
        viewports.add(viewport);
    }

    void detach(Viewport3D viewport) {
        viewports.remove(viewport);
    }

    /**
     * The window's renderer is being disposed: free every viewport's GPU objects, then the device.
     */
    private void shutdown() {
        BY_RENDERER.remove(interop);
        for (Viewport3D viewport : new ArrayList<>(viewports)) {
            try {
                viewport.releaseGpu(device);
            } catch (RuntimeException e) {
                log.error("A 3D viewport failed to release its GPU objects", e);
            }
        }
        viewports.clear();
        device.close();
    }
}
