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
        return requirements().deviceExtensions();
    }

    @Override
    public void deviceCreated() {
        synchronized (DlssExtensions.class) {
            devices++;
        }
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
