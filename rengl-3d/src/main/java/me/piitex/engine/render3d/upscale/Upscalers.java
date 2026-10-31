package me.piitex.engine.render3d.upscale;

import me.piitex.engine.render3d.GpuDevice;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every {@link UpscalerProvider} there is: the built-in temporal one, those found on the class path, and those added
 * with {@link #register}. Give one to {@link me.piitex.engine.render3d.scene.Scene3D#setUpscaler}.
 */
public final class Upscalers {
    private static final Logger log = LogManager.getLogger("Engine");

    private static final List<UpscalerProvider> registered = new CopyOnWriteArrayList<>();
    private static volatile List<UpscalerProvider> discovered;

    private Upscalers() {
    }

    /**
     * The temporal upscaler that is part of the 3D layer: it works on every GPU, needs no library, and is the fallback
     * when no better one is available.
     */
    public static UpscalerProvider builtIn() {
        return TemporalUpscaler.PROVIDER;
    }

    /**
     * Adds a provider.
     */
    public static void register(UpscalerProvider provider) {
        registered.add(provider);
    }

    /**
     * Every provider, the built-in one included, best first, whether or not it works on a given device.
     */
    public static List<UpscalerProvider> all() {
        List<UpscalerProvider> list = new ArrayList<>(registered);
        List<UpscalerProvider> found = discovered;
        if (found == null) {
            found = new ArrayList<>();
            try {
                for (UpscalerProvider provider : ServiceLoader.load(UpscalerProvider.class)) found.add(provider);
            } catch (java.util.ServiceConfigurationError | RuntimeException e) {
                log.warn("Could not load an upscaler provider: {}", e.toString());
            }
            discovered = found;
        }
        list.addAll(found);
        list.add(TemporalUpscaler.PROVIDER);
        list.sort(Comparator.comparingInt(UpscalerProvider::priority).reversed());
        return list;
    }

    /**
     * The providers that work on the device, best first. The built-in one is always among them.
     */
    public static List<UpscalerProvider> available(GpuDevice device) {
        List<UpscalerProvider> list = new ArrayList<>();
        for (UpscalerProvider provider : all()) {
            boolean ok;
            try {
                ok = provider.isAvailable(device);
            } catch (RuntimeException | LinkageError e) {
                log.debug("The upscaler {} is not available: {}", provider.name(), e.toString());
                ok = false;
            }
            if (ok) list.add(provider);
        }
        return list;
    }

    /**
     * The best provider that works on the device.
     */
    public static UpscalerProvider best(GpuDevice device) {
        return available(device).get(0);
    }
}
