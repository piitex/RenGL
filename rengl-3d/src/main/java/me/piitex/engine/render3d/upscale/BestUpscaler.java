package me.piitex.engine.render3d.upscale;

import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * A provider that is whatever {@link Upscalers#best} turns out to be on the device it is made for, which a scene file can ask for
 * without knowing the GPU: DLSS or FSR where the vendor module and its libraries are there, the built-in upscaler anywhere else.
 * Give it to {@link me.piitex.engine.render3d.scene.Scene3D#setUpscaler} like any other. One scene, one instance.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class BestUpscaler implements UpscalerProvider {
    private volatile UpscalerProvider chosen;

    @Override
    public String name() {
        UpscalerProvider provider = chosen;
        return provider == null ? "Best Available" : provider.name();
    }

    @Override
    public boolean isAvailable(GpuDevice device) {
        return true;
    }

    @Override
    public float supportedRenderScale(float requested, int displayWidth, int displayHeight) {
        UpscalerProvider provider = chosen;
        return provider == null ? requested : provider.supportedRenderScale(requested, displayWidth, displayHeight);
    }

    @Override
    public Upscaler create(GpuDevice device) {
        UpscalerProvider provider = Upscalers.best(device);
        Upscaler upscaler = provider.create(device);
        chosen = provider;
        return upscaler;
    }
}
