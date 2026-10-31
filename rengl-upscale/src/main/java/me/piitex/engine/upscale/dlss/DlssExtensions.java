package me.piitex.engine.upscale.dlss;

import me.piitex.engine.gl.renderer.vulkan.VulkanExtensionProvider;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What DLSS needs from the window's Vulkan instance and device, which has to be on when they are made: Streamline is started
 * here (it must be, before the device exists) and says which extensions and features its DLSS wants.
 * <p>
 * It does nothing, and costs nothing, unless Streamline's libraries are found ({@link Sl}); where the GPU lacks something,
 * {@link DlssProvider} says DLSS is not available on it.
 */
public final class DlssExtensions implements VulkanExtensionProvider {
    private static int devices;
    private static SwapchainHooks swapchain; // the window's, between its device being made and destroyed

    private static Sl.Requirements requirements() {
        Sl sl = Sl.load();
        return sl == null ? Sl.Requirements.NONE : sl.start();
    }

    @Override
    public Collection<String> instanceExtensions() {
        return requirements().instanceExtensions();
    }

    @Override
    public Collection<String> deviceExtensions() {
        // Streamline's low latency extension needs ids on presents, which the list it reports does not mention.
        Set<String> extensions = new LinkedHashSet<>(requirements().deviceExtensions());
        if (extensions.contains("VK_NV_low_latency2")) extensions.add("VK_KHR_present_id");
        return extensions;
    }

    /**
     * Frame generation has to see the window's surface being made: it works out which window it presents to from that.
     * Only done where it is on.
     */
    @Override
    public long createWindowSurface(long instance, long nativeWindow) {
        Sl sl = Sl.load();
        if (sl == null || !DlssFrameGeneration.isAvailable()) return 0L;
        return DlssFrameGeneration.createSurface(sl, instance, nativeWindow);
    }

    @Override
    public void destroyWindowSurface(long instance, long surface) {
        Sl sl = Sl.load();
        if (sl != null) DlssFrameGeneration.destroySurface(sl, instance, surface);
    }

    /** Streamline's own queues, which the device is made with (frame generation wants some). */
    @Override
    public int extraQueues() {
        Sl.Requirements requirements = requirements();
        return requirements.graphicsQueues() + requirements.computeQueues();
    }

    @Override
    public void deviceCreated(DeviceInfo info) {
        synchronized (DlssExtensions.class) {
            devices++;
        }
        Sl sl = Sl.load();
        if (sl == null || !sl.started()) return;
        Sl.Requirements requirements = requirements();
        // The engine's queue is queue 0 of the family; Streamline's graphics queues follow it, then its compute queues.
        int graphics = requirements.graphicsQueues() > 0 ? 1 : 0;
        int compute = requirements.computeQueues() > 0 ? 1 + requirements.graphicsQueues() : 0;
        if (!DlssProvider.attach(sl, info.device(), info.instance(), info.physicalDevice(), info.queueFamily(), graphics, compute)) return;
        swapchain = DlssFrameGeneration.attach(sl, info, requirements.graphicsQueues(), requirements.computeQueues());
    }

    @Override
    public SwapchainHooks swapchainHooks() {
        return swapchain;
    }

    @Override
    public void beforePresent(SwapchainImage image) {
        DlssFrameGeneration.tagBackbuffer(image);
    }

    @Override
    public void frameEvent(FrameEvent event) {
        DlssFrameGeneration.onFrame(event);
    }

    /** Streamline is stopped with the last device, while the device is still there (it wants its objects freed before that). */
    @Override
    public void deviceDestroying() {
        synchronized (DlssExtensions.class) {
            if (--devices > 0) return;
            devices = 0;
        }
        Sl sl = Sl.load();
        if (sl != null) {
            DlssFrameGeneration.detach();
            swapchain = null;
            DlssProvider.forgetDevices();
            sl.stop();
        }
    }

    @Override
    public Collection<String> deviceFeatures12() {
        return requirements().features12();
    }

    @Override
    public Collection<String> deviceFeatures13() {
        // Streamline's own bookkeeping uses private data slots, which the list it reports does not mention.
        Set<String> features = new LinkedHashSet<>(requirements().features13());
        if (Sl.load() != null && Sl.load().started()) features.add("privateData");
        return features;
    }
}
