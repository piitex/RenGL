package me.piitex.engine.render3d.upscale;

import me.piitex.engine.render3d.GpuDevice;

/**
 * Makes {@link Upscaler}s of one kind. A module that brings an upscaler (a vendor's library, for instance) offers a
 * provider two ways: list it in {@code META-INF/services/me.piitex.engine.render3d.upscale.UpscalerProvider}, or hand it to
 * {@link Upscalers#register}. Nothing in the 3D layer depends on any provider, so a build without the module has none of
 * its code.
 */
public interface UpscalerProvider {
    /**
     * A name for logs and settings screens, such as "FSR".
     */
    String name();

    /**
     * Whether this provider can make an upscaler for the device: the right GPU and API, the library present and loadable.
     * Must be cheap and must not throw; answer false instead.
     */
    boolean isAvailable(GpuDevice device);

    /**
     * Higher is better: {@link Upscalers#best} takes the available provider with the highest number. The built-in
     * temporal upscaler is 0.
     */
    default int priority() {
        return 0;
    }

    /**
     * A new upscaler for the device. Called on the render thread, once for each place a scene is drawn.
     */
    Upscaler create(GpuDevice device);
}
