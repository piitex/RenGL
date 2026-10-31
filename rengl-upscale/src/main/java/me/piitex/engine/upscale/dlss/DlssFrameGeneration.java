package me.piitex.engine.upscale.dlss;

import me.piitex.engine.gl.renderer.vulkan.VulkanExtensionProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * NVIDIA DLSS frame generation (DLSS 3 and up) on Vulkan, through Streamline: after each frame the engine draws, the GPU makes
 * a second one in between from the scene's depth and motion and shows both, so the picture updates about twice as often as the
 * scene is drawn.
 * <p>
 * It asks a lot of the setup, so it is only started when told to: run with {@code -Drengl.dlss.framegen=true} and put
 * {@code sl.dlss_g.dll}, {@code nvngx_dlssg.dll}, {@code sl.reflex.dll} and {@code sl.pcl.dll} from the Streamline SDK next to the
 * DLSS ones ({@link DlssProvider}). The window's Vulkan device is then made with extra queues, its swapchain goes through
 * Streamline, and every frame is bracketed with Reflex latency markers (frame generation requires Reflex). The GPU has to be a
 * GeForce RTX 40 or newer, and on Windows hardware-accelerated GPU scheduling has to be on.
 * <p>
 * The frames to be generated from come from the scene: a 3D viewport drawn with a DLSS upscaler (at render scale 1 that is DLAA)
 * hands its depth and motion over, and the viewport should fill the window (what is drawn around it is interpolated with it).
 * <pre>{@code
 * scene.setUpscaler(new DlssProvider());
 * DlssFrameGeneration.setEnabled(true);   // on by default once the libraries are loaded
 * }</pre>
 * Vsync is not supported together with frame generation on Vulkan; run the window without it.
 */
public final class DlssFrameGeneration {
    private static final Logger log = LogManager.getLogger("Engine");

    // sl::PCLMarker
    private static final int SIMULATION_START = 0, SIMULATION_END = 1, RENDER_SUBMIT_START = 2, RENDER_SUBMIT_END = 3,
            PRESENT_START = 4, PRESENT_END = 5;
    // sl::DLSSGFlags
    private static final int RETAIN_RESOURCES_WHEN_OFF = 1 << 3;

    private DlssFrameGeneration() {
    }

    /**
     * What frame generation is doing.
     *
     * @param status                0 where everything is as it should be, otherwise the {@code sl::DLSSGStatus} bits
     * @param statusText            the status in words
     * @param framesPresented       the frames shown since the last call, generated ones included
     * @param maxFramesToGenerate   the most frames the GPU can make between two drawn ones
     * @param estimatedVramBytes    the memory the feature is expected to use
     */
    public record State(boolean active, int status, String statusText, int framesPresented, int maxFramesToGenerate,
                        long estimatedVramBytes) {
    }

    private static final Object lock = new Object();
    private static volatile boolean enabled = !"false".equalsIgnoreCase(System.getProperty("rengl.dlss.framegen.on"));
    private static volatile int framesToGenerate = 1;
    private static volatile boolean swapchainChanging;

    private static boolean supported;           // the swapchain goes through Streamline and DLSS-G can run on this GPU
    private static String failure = "frame generation has not been looked at yet";
    private static MethodHandle reflexSetOptions, reflexSleep, setMarker, setOptions, getState;
    private static int viewport = -1;           // the viewport that tagged depth and motion this frame
    private static boolean tagged;
    private static boolean lastOn;

    // ---- for the application ---------------------------------------------------------------------------------------

    /** Whether frame generation can run: the libraries are there, were started, and this GPU and system can do it. */
    public static boolean isAvailable() {
        synchronized (lock) {
            return supported;
        }
    }

    /** Why frame generation is not available, or null where it is. */
    public static String unavailableReason() {
        synchronized (lock) {
            if (supported) return null;
        }
        Sl sl = Sl.load();
        if (sl == null) return Sl.failure();
        sl.start();
        String reason = sl.frameGenerationFailure();
        synchronized (lock) {
            return reason != null ? reason : failure;
        }
    }

    /** Turns frame generation on or off while the window runs. It is on once available, unless {@code -Drengl.dlss.framegen.on=false}. */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * How many frames are made between two drawn ones: 1 doubles the frame rate. More is only possible on GeForce RTX 50
     * (see {@link State#maxFramesToGenerate()}); a larger number is brought down to what the GPU can do.
     */
    public static void setFramesToGenerate(int frames) {
        framesToGenerate = Math.max(1, frames);
    }

    /** What frame generation did since the last call. Call it on the thread that draws the window. */
    public static State state() {
        synchronized (lock) {
            if (!supported || viewport < 0 || getState == null) return new State(false, 0, "not running", 0, 0, 0);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = Sl.dlssgState(arena);
                int result = (int) getState.invokeExact(Sl.viewport(arena, viewport), state, MemorySegment.NULL);
                if (result != Sl.OK) return new State(false, 0, "slDLSSGGetState returned " + Sl.describe(result), 0, 0, 0);
                int status = state.get(JAVA_INT, 40);
                return new State(lastOn && status == 0, status, statusText(status), state.get(JAVA_INT, 48), state.get(JAVA_INT, 52),
                        state.get(JAVA_LONG, 32));
            } catch (Throwable t) {
                return new State(false, 0, t.toString(), 0, 0, 0);
            }
        }
    }

    private static String statusText(int status) {
        if (status == 0) return "ok";
        StringBuilder text = new StringBuilder();
        if ((status & 1) != 0) text.append("output resolution too low, ");
        if ((status & 2) != 0) text.append("Reflex is not active, ");
        if ((status & 4) != 0) text.append("HDR format not supported, ");
        if ((status & 8) != 0) text.append("camera constants invalid, ");
        if ((status & 16) != 0) text.append("current back buffer index not asked for, ");
        return text.isEmpty() ? "status " + status : text.substring(0, text.length() - 2);
    }

    // ---- for the DLSS module -----------------------------------------------------------------------------------------

    /**
     * Whether the upscaler should hand depth and motion over for the frame to be generated from. It does so even while frame
     * generation is switched off: Streamline copies what it is told is only good for a moment, which is a waste and fails on
     * images that can not be copied from.
     */
    static boolean wantsInputs() {
        synchronized (lock) {
            return supported;
        }
    }

    /** A viewport tagged its depth and motion for this frame. */
    static void inputsTagged(int viewportId) {
        synchronized (lock) {
            if (!tagged) viewport = viewportId;
            tagged |= viewport == viewportId;
        }
    }

    /**
     * The device has been made: looks at whether frame generation can run on it and, if so, returns the swapchain calls that go
     * through Streamline. Null where it can not (the engine then presents as usual).
     */
    static VulkanExtensionProvider.SwapchainHooks attach(Sl sl, VulkanExtensionProvider.DeviceInfo info, int graphicsQueues, int computeQueues) {
        synchronized (lock) {
            supported = false;
            if (!sl.frameGenerationLoaded()) {
                failure = sl.frameGenerationFailure();
                return null;
            }
            int needed = 1 + graphicsQueues + computeQueues;
            if (info.queueCount() < needed) {
                failure = "the GPU's queue family has " + info.queueCount() + " queues and frame generation needs " + needed;
                log.warn("DLSS frame generation is not available: {}", failure);
                return null;
            }
            try (Arena arena = Arena.ofConfined()) {
                int result = sl.support(Sl.FEATURE_DLSS_G, Sl.adapter(arena, info.physicalDevice()));
                if (result != Sl.OK) {
                    failure = "DLSS frame generation is not supported here: " + Sl.describe(result);
                    log.warn("{}", failure);
                    return null;
                }
                reflexSetOptions = sl.featureFunction(Sl.FEATURE_REFLEX, "slReflexSetOptions", 1);
                reflexSleep = sl.featureFunction(Sl.FEATURE_REFLEX, "slReflexSleep", 1);
                setMarker = sl.featureFunction(Sl.FEATURE_PCL, "slPCLSetMarker", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
                setOptions = sl.featureFunction(Sl.FEATURE_DLSS_G, "slDLSSGSetOptions", 2);
                getState = sl.featureFunction(Sl.FEATURE_DLSS_G, "slDLSSGGetState", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
                int reflex = (int) reflexSetOptions.invokeExact(Sl.reflexOptions(arena, 1));
                if (reflex != Sl.OK) log.warn("slReflexSetOptions returned {}", Sl.describe(reflex));
                Hooks hooks = new Hooks(sl, info.device());
                supported = true;
                failure = null;
                log.info("DLSS frame generation is on the window's swapchain");
                return hooks;
            } catch (Throwable t) {
                failure = "DLSS frame generation would not start: " + t;
                log.warn("{}", failure);
                if (t instanceof Error e && !(t instanceof LinkageError)) throw e;
                return null;
            }
        }
    }

    /**
     * The window's surface, made through Streamline's {@code vkCreateWin32SurfaceKHR} so that it knows the window. Returns 0
     * (and the engine makes the surface as usual) if that fails.
     */
    static long createSurface(Sl sl, long instance, long hwnd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment create = sl.instanceProcAddress(instance, "vkCreateWin32SurfaceKHR");
            if (create == null) return 0L;
            MethodHandle call = Linker.nativeLinker().downcallHandle(create, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            // VkWin32SurfaceCreateInfoKHR: sType, pNext, flags, hinstance, hwnd
            MemorySegment info = arena.allocate(40, 8);
            info.set(JAVA_INT, 0, 1000009000);
            info.set(ADDRESS, 24, moduleHandle());
            info.set(ADDRESS, 32, MemorySegment.ofAddress(hwnd));
            MemorySegment out = arena.allocate(8);
            int result = (int) call.invokeExact(MemorySegment.ofAddress(instance), info, MemorySegment.NULL, out);
            if (result != 0) {
                log.warn("vkCreateWin32SurfaceKHR through Streamline returned {}", result);
                return 0L;
            }
            return out.get(JAVA_LONG, 0);
        } catch (Throwable t) {
            log.warn("Could not make the window surface through Streamline: {}", t.toString());
            return 0L;
        }
    }

    static void destroySurface(Sl sl, long instance, long surface) {
        try {
            MemorySegment destroy = sl.instanceProcAddress(instance, "vkDestroySurfaceKHR");
            if (destroy == null) return;
            MethodHandle call = Linker.nativeLinker().downcallHandle(destroy, FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
            call.invokeExact(MemorySegment.ofAddress(instance), surface, MemorySegment.NULL);
        } catch (Throwable t) {
            log.warn("Could not destroy the window surface through Streamline: {}", t.toString());
        }
    }

    /** The handle of the running program ({@code GetModuleHandleW(NULL)}), which a Win32 surface is made for. */
    private static MemorySegment moduleHandle() throws Throwable {
        Linker linker = Linker.nativeLinker();
        MethodHandle get = linker.downcallHandle(java.lang.foreign.SymbolLookup.libraryLookup("kernel32.dll", Arena.global()).find("GetModuleHandleW").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        return (MemorySegment) get.invokeExact(MemorySegment.NULL);
    }

    /**
     * Tells Streamline the state of the back buffer being presented: it copies the frame out of it on its own queue, and with the
     * engine's own bookkeeping of layouts it can not know the layout the engine left it in.
     */
    static void tagBackbuffer(VulkanExtensionProvider.SwapchainImage image) {
        Sl sl = Sl.load();
        if (sl == null) return;
        synchronized (lock) {
            if (!supported || !tagged || viewport < 0) return;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment resource = Sl.resource(arena, new me.piitex.engine.render3d.vk.VkImageInfo(image.image(), 0L, image.format(),
                        1, image.width(), image.height(), image.layout(), image.usage()), image.layout());
                MemorySegment tag = Sl.tag(arena, resource, Sl.BUFFER_BACKBUFFER, Sl.VALID_UNTIL_PRESENT, image.width(), image.height());
                int result = sl.setTagForFrame(FrameClock.current(sl), Sl.viewport(arena, viewport), tag, 1, 0L);
                if (result != Sl.OK) log.warn("slSetTagForFrame (back buffer) returned {}", Sl.describe(result));
            }
        }
    }

    /** The device is going: forget everything made for it. */
    static void detach() {
        synchronized (lock) {
            supported = false;
            tagged = false;
            viewport = -1;
            lastOn = false;
            reflexSetOptions = reflexSleep = setMarker = setOptions = getState = null;
        }
    }

    /** What happens at each point of a window frame: the frame token, Reflex's sleep and markers, and the frame generation options. */
    static void onFrame(VulkanExtensionProvider.FrameEvent event) {
        Sl sl = Sl.load();
        if (sl == null || !sl.started()) return;
        MemorySegment frame;
        switch (event) {
            case FRAME_STARTED -> {
                frame = FrameClock.begin(sl);
                synchronized (lock) {
                    tagged = false;
                    if (!supported) return;
                    swapchainChanging = false;
                    call(reflexSleep, frame);
                    marker(SIMULATION_START, frame);
                    marker(SIMULATION_END, frame);
                }
            }
            case RENDER_STARTED -> marker(RENDER_SUBMIT_START, FrameClock.current(sl));
            case RENDER_FINISHED -> marker(RENDER_SUBMIT_END, FrameClock.current(sl));
            case PRESENT_STARTED -> {
                frame = FrameClock.current(sl);
                synchronized (lock) {
                    if (supported) applyOptions(true);
                }
                marker(PRESENT_START, frame);
            }
            case PRESENT_FINISHED -> {
                marker(PRESENT_END, FrameClock.current(sl));
                FrameClock.end();
            }
            case SWAPCHAIN_CHANGING -> {
                // Frame generation has to be off while the swapchain is rebuilt.
                synchronized (lock) {
                    swapchainChanging = true;
                    if (supported) applyOptions(false);
                }
            }
        }
    }

    private static void applyOptions(boolean frameInProgress) {
        if (setOptions == null || viewport < 0) return;
        boolean on = frameInProgress && enabled && tagged && !swapchainChanging;
        try (Arena arena = Arena.ofConfined()) {
            int result = (int) setOptions.invokeExact(Sl.viewport(arena, viewport),
                    Sl.dlssgOptions(arena, on ? 1 : 0, framesToGenerate, RETAIN_RESOURCES_WHEN_OFF));
            if (result != Sl.OK) log.warn("slDLSSGSetOptions returned {}", Sl.describe(result));
            lastOn = on;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static void marker(int marker, MemorySegment frame) {
        synchronized (lock) {
            if (!supported || setMarker == null) return;
            try {
                int result = (int) setMarker.invokeExact(marker, frame);
                if (result != Sl.OK) log.debug("slPCLSetMarker({}) returned {}", marker, Sl.describe(result));
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }
    }

    private static void call(MethodHandle function, MemorySegment frame) {
        if (function == null) return;
        try {
            int result = (int) function.invokeExact(frame);
            if (result != Sl.OK) log.debug("Streamline returned {}", Sl.describe(result));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    /** The five swapchain calls as Streamline's interposer gives them for the device. */
    private static final class Hooks implements VulkanExtensionProvider.SwapchainHooks {
        private final MethodHandle create, destroy, images, acquire, present;

        Hooks(Sl sl, long device) {
            Linker linker = Linker.nativeLinker();
            create = bind(linker, sl, device, "vkCreateSwapchainKHR", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            destroy = bind(linker, sl, device, "vkDestroySwapchainKHR", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
            images = bind(linker, sl, device, "vkGetSwapchainImagesKHR", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
            acquire = bind(linker, sl, device, "vkAcquireNextImageKHR",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS));
            present = bind(linker, sl, device, "vkQueuePresentKHR", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        }

        private static MethodHandle bind(Linker linker, Sl sl, long device, String name, FunctionDescriptor descriptor) {
            MemorySegment function = sl.deviceProcAddress(device, name);
            if (function == null) throw new IllegalStateException("Streamline has no " + name);
            return linker.downcallHandle(function, descriptor);
        }

        private static MemorySegment ptr(long address) {
            return MemorySegment.ofAddress(address);
        }

        @Override
        public int createSwapchain(long device, long createInfo, long pSwapchain) {
            try {
                return (int) create.invokeExact(ptr(device), ptr(createInfo), MemorySegment.NULL, ptr(pSwapchain));
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        @Override
        public void destroySwapchain(long device, long swapchain) {
            try {
                destroy.invokeExact(ptr(device), swapchain, MemorySegment.NULL);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        @Override
        public int swapchainImages(long device, long swapchain, long pCount, long pImages) {
            try {
                return (int) images.invokeExact(ptr(device), swapchain, ptr(pCount), ptr(pImages));
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        @Override
        public int acquireNextImage(long device, long swapchain, long timeout, long semaphore, long fence, long pIndex) {
            try {
                return (int) acquire.invokeExact(ptr(device), swapchain, timeout, semaphore, fence, ptr(pIndex));
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        @Override
        public int present(long queue, long presentInfo) {
            try {
                return (int) present.invokeExact(ptr(queue), ptr(presentInfo));
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }
    }
}
