package me.piitex.engine.upscale.fsr;

import me.piitex.engine.gl.renderer.vulkan.VulkanExtensionProvider;

import java.util.Collection;
import java.util.List;

/**
 * What FSR frame generation needs from the window's Vulkan device, which has to be there when it is made: three queues more
 * (the library runs the generated frame, presents, and acquires images on queues of its own) and timeline semaphores. It does
 * nothing, and costs nothing, unless frame generation was asked for ({@code -Drengl.fsr.framegen=true}) and the library is found.
 */
public final class FsrExtensions implements VulkanExtensionProvider {
    private static SwapchainHooks swapchain; // the window's, between its device being made and destroyed

    private static boolean wanted() {
        if (!FsrFrameGeneration.requested() || !System.getProperty("os.name", "").toLowerCase().contains("win")) return false;
        Ffx ffx = Ffx.load();
        return ffx != null && ffx.hasFrameGeneration();
    }

    @Override
    public int extraQueues() {
        return wanted() ? 3 : 0;
    }

    @Override
    public Collection<String> deviceFeatures12() {
        return wanted() ? List.of("timelineSemaphore") : List.of();
    }

    @Override
    public void deviceCreated(DeviceInfo info) {
        if (wanted()) swapchain = FsrFrameGeneration.attach(info);
    }

    @Override
    public SwapchainHooks swapchainHooks() {
        return swapchain;
    }

    @Override
    public void frameEvent(FrameEvent event) {
        FsrFrameGeneration.onFrame(event);
    }

    @Override
    public void deviceDestroying() {
        swapchain = null;
        FsrFrameGeneration.detach();
    }
}
