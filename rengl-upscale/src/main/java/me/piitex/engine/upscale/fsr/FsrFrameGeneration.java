package me.piitex.engine.upscale.fsr;

import me.piitex.engine.gl.renderer.vulkan.VulkanExtensionProvider;
import me.piitex.engine.render3d.upscale.UpscaleInputs;
import me.piitex.engine.render3d.vk.VkImageInfo;
import me.piitex.engine.upscale.CStruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.vulkan.VK;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * AMD FidelityFX frame generation (FSR 3 frame generation, or FSR 4 where the GPU has it) on the window's Vulkan swapchain:
 * for every frame the scene draws, one more is made between it and the last and shown in between.
 * <p>
 * It is opt-in, with {@code -Drengl.fsr.framegen=true}, and needs the
 * frame generation library of the FidelityFX SDK ({@value Ffx#FRAME_GENERATION}) beside the other two. The window's swapchain is
 * then not Vulkan's: it is the library's, which hands the engine images of its own to draw into, copies each to the real
 * swapchain, and between two of them presents the frame it made from the pair on a timing of its own. For that the window's
 * device is made with three more queues ({@link FsrExtensions}), and what the scene was upscaled from (depth, motion, camera) is
 * handed over after the upscaler has run ({@link #prepare}). Where anything of that fails the window falls back to Vulkan's own
 * swapchain and nothing is generated.
 * <p>
 * Limits: one window; the 3D view has to fill the window, since the frame is made from the finished window picture and the
 * depth and motion that go with it; the UI the window draws over the scene is part of the picture, so it is interpolated with
 * the scene.
 */
public final class FsrFrameGeneration {
    private static final Logger log = LogManager.getLogger("Engine");

    /** The layout the library's own images are left in when presented. */
    private static final int SHADER_READ_ONLY_OPTIMAL = 5;

    /** {@code -Drengl.fsr.framegen.debug=true} has the library mark the frames it generates with bars of changing colour at the sides. */
    private static final boolean DEBUG_TEAR_LINES = Boolean.getBoolean("rengl.fsr.framegen.debug");

    private static final Object lock = new Object();
    private static volatile boolean enabled = !"false".equalsIgnoreCase(System.getProperty("rengl.fsr.framegen.on"));
    private static Session session;
    private static String failure;
    private static MemorySegment dispatchCallback;

    private FsrFrameGeneration() {
    }

    /**
     * What frame generation is doing.
     *
     * @param active        true while it generates frames
     * @param presentations frames given to the display since the swapchain was made: with frame generation on, about twice
     *                      the number the scene drew
     * @param framesDrawn   window frames the scene has drawn
     */
    public record State(boolean active, long presentations, long framesDrawn) {
    }

    /** True when the window was asked to use it ({@code -Drengl.fsr.framegen=true}). */
    static boolean requested() {
        return Boolean.getBoolean("rengl.fsr.framegen");
    }

    /** True once the window's swapchain is the library's, so that frames can be generated. */
    public static boolean isAvailable() {
        Session s = session;
        return s != null && s.proxy != 0L && !s.plain;
    }

    /** Why frames can not be generated, or null where they can. */
    public static String unavailableReason() {
        if (isAvailable()) return null;
        if (!requested()) return "frame generation was not asked for (-Drengl.fsr.framegen=true)";
        Ffx ffx = Ffx.load();
        if (ffx == null) return Ffx.failure();
        if (!ffx.hasFrameGeneration()) return Ffx.FRAME_GENERATION + " was not found beside " + ffx.loader().getFileName();
        synchronized (lock) {
            return failure != null ? failure : "the window's device was made without it (a Vulkan window with the module on the class path is needed)";
        }
    }

    /** Turns frame generation on or off while the window runs; it is on to start with ({@code rengl.fsr.framegen.on=false} starts it off). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static State state() {
        Session s = session;
        if (s == null || s.proxy == 0L || s.plain) return new State(false, 0, 0);
        long presentations = 0;
        try {
            presentations = (long) s.count.invokeExact(s.proxy);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        return new State(s.fg != null && s.configuredOn, presentations, s.frameId);
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException e) throw e;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    // ---- the window's device ------------------------------------------------------------------------------------

    /** What this device has to say to the library. Everything of one window. */
    private static final class Session {
        final Ffx ffx;
        final long instance, physicalDevice, device;
        final int family;
        final long gameQueue, computeQueue, presentQueue, acquireQueue;
        final Plain vulkan;
        /** The library's swapchain: its context (an 8-byte cell the library writes), the handle it returned, and its functions. */
        Arena swapchainArena;
        MemorySegment swapchainContext;
        long proxy;
        boolean plain; // the library would not make a swapchain: Vulkan's own is used
        MethodHandle create, destroy, images, acquire, present, count;
        long skipDestroy;
        int width, height, format;
        /** The frame generation context, once the scene has handed over a frame. */
        Fg fg;
        boolean fgFailed, warnedSize;
        boolean configuredOn, prepared;
        long frameId;

        Session(Ffx ffx, long instance, long physicalDevice, long device, int family, long[] queues) {
            this.ffx = ffx;
            this.instance = instance;
            this.physicalDevice = physicalDevice;
            this.device = device;
            this.family = family;
            this.gameQueue = queues[0];
            this.computeQueue = queues[1];
            this.presentQueue = queues[2];
            this.acquireQueue = queues[3];
            this.vulkan = new Plain();
        }
    }

    /** A frame generation context and the size it was made for. */
    private record Fg(Arena arena, MemorySegment handle, int width, int height) {
    }

    /**
     * Called as the window's device is made: takes hold of its queues and returns the swapchain calls to use in place of Vulkan's,
     * or null where this can not be done.
     */
    static VulkanExtensionProvider.SwapchainHooks attach(VulkanExtensionProvider.DeviceInfo info) {
        Ffx ffx = Ffx.load();
        if (ffx == null || !ffx.hasFrameGeneration()) return null;
        synchronized (lock) {
            failure = null;
            if (info.queueCount() < 4) {
                failure = "the GPU's queue family has only " + info.queueCount() + " queues; frame generation needs 4";
                log.warn("FSR frame generation is not available: {}", failure);
                return null;
            }
            try {
                int first = info.queueCount() - 3;
                long[] queues = {queue(info, 0), queue(info, first), queue(info, first + 1), queue(info, first + 2)};
                session = new Session(ffx, info.instance(), info.physicalDevice(), info.device(), info.queueFamily(), queues);
                if (Boolean.getBoolean("rengl.fsr.debug")) debugMessages(ffx);
                return new Hooks(session);
            } catch (Throwable t) {
                failure = "could not take hold of the queues: " + t;
                log.warn("FSR frame generation is not available: {}", failure);
                session = null;
                return null;
            }
        }
    }

    private static void debugMessages(Ffx ffx) {
        try (Arena arena = Arena.ofConfined()) {
            CStruct debug = new CStruct(arena, 40).header(7L).u64(0L).ptr(Ffx.messageCallback()).u32(0).end();
            ffx.configure(MemorySegment.NULL, debug.segment());
        }
    }

    /** {@code vkGetDeviceQueue}, through the loader. */
    private static long queue(VulkanExtensionProvider.DeviceInfo info, int index) throws Throwable {
        long address = VK.getFunctionProvider().getFunctionAddress("vkGetDeviceQueue");
        if (address == 0L) throw new IllegalStateException("vkGetDeviceQueue was not found");
        MethodHandle get = Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(address),
                FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(8, 8);
            get.invokeExact(info.device(), info.queueFamily(), index, out);
            return out.get(JAVA_LONG, 0);
        }
    }

    /** The device is going: let go of everything made on it. */
    static void detach() {
        synchronized (lock) {
            Session s = session;
            if (s != null) teardown(s, s.device);
            session = null;
        }
    }

    // ---- Vulkan's own swapchain calls, for when the library's would not start ------------------------------------

    private static final class Plain {
        private MethodHandle create, destroy, images, acquire, present;

        private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
            long address = VK.getFunctionProvider().getFunctionAddress(name);
            if (address == 0L) throw new IllegalStateException(name + " was not found");
            return Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(address), descriptor);
        }

        synchronized void load() {
            if (create != null) return;
            create = bind("vkCreateSwapchainKHR", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
            destroy = bind("vkDestroySwapchainKHR", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG));
            images = bind("vkGetSwapchainImagesKHR", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
            acquire = bind("vkAcquireNextImageKHR", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
            present = bind("vkQueuePresentKHR", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));
        }
    }

    // ---- the swapchain ------------------------------------------------------------------------------------------

    /** The five swapchain calls: the library's while it has a swapchain, Vulkan's if it never made one. */
    private static final class Hooks implements VulkanExtensionProvider.SwapchainHooks {
        private final Session s;

        Hooks(Session s) {
            this.s = s;
        }

        @Override
        public int createSwapchain(long device, long createInfo, long pSwapchain) {
            synchronized (lock) {
                try {
                    if (!s.plain) {
                        long old = MemorySegment.ofAddress(createInfo).reinterpret(FfxFrameGeneration.SWAPCHAIN_CREATE_INFO_SIZE)
                                .get(JAVA_LONG, FfxFrameGeneration.OLD_SWAPCHAIN_OFFSET);
                        if (s.proxy != 0L && old == s.proxy) return recreate(device, createInfo, pSwapchain);
                        if (s.proxy != 0L) teardown(s, device);
                        int result = createProxy(device, createInfo, pSwapchain);
                        if (result == VK_SUCCESS) return result;
                        s.plain = true;
                        log.warn("FSR frame generation: the library's swapchain was not made ({}); the window uses Vulkan's own", failure);
                    }
                    s.vulkan.load();
                    return (int) s.vulkan.create.invokeExact(device, createInfo, 0L, pSwapchain);
                } catch (Throwable t) {
                    throw rethrow(t);
                }
            }
        }

        @Override
        public void destroySwapchain(long device, long swapchain) {
            synchronized (lock) {
                try {
                    if (s.plain || swapchain != s.proxy) {
                        s.vulkan.load();
                        s.vulkan.destroy.invokeExact(device, swapchain, 0L);
                    } else if (swapchain == s.skipDestroy) {
                        s.skipDestroy = 0L; // the swapchain was made again in place and is the same one
                    } else {
                        teardown(s, device);
                    }
                } catch (Throwable t) {
                    throw rethrow(t);
                }
            }
        }

        @Override
        public int swapchainImages(long device, long swapchain, long pCount, long pImages) {
            try {
                if (s.plain) return (int) s.vulkan.images.invokeExact(device, swapchain, pCount, pImages);
                return (int) s.images.invokeExact(device, swapchain, pCount, pImages);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        @Override
        public int acquireNextImage(long device, long swapchain, long timeout, long semaphore, long fence, long pIndex) {
            try {
                if (s.plain) return (int) s.vulkan.acquire.invokeExact(device, swapchain, timeout, semaphore, fence, pIndex);
                return (int) s.acquire.invokeExact(device, swapchain, timeout, semaphore, fence, pIndex);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        @Override
        public int present(long queue, long presentInfo) {
            try {
                if (s.plain) return (int) s.vulkan.present.invokeExact(queue, presentInfo);
                return (int) s.present.invokeExact(queue, presentInfo);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        @Override
        public int presentLayout() {
            return s.plain ? 0 : SHADER_READ_ONLY_OPTIMAL;
        }
    }

    /** Makes the library's swapchain in place of the one the engine asks for. */
    private static int createProxy(long device, long createInfo, long pSwapchain) throws Throwable {
        Session s = session;
        Arena arena = Arena.ofShared();
        try (Arena call = Arena.ofConfined()) {
            MemorySegment context = arena.allocate(8, 8), swapchain = call.allocate(8, 8);
            CStruct desc = FfxFrameGeneration.createSwapchain(call, s.physicalDevice, device, swapchain, createInfo, s.gameQueue,
                    s.computeQueue, s.presentQueue, s.acquireQueue, s.family);
            desc.chain(FfxFrameGeneration.swapchainVersion(call));
            int result = s.ffx.rawCreate(context, desc.segment());
            if (result != 0) {
                failure = "ffxCreateContext of the frame generation swapchain returned " + result;
                arena.close();
                return -1;
            }
            CStruct functions = FfxFrameGeneration.replacementFunctions(call);
            if (s.ffx.query(context, functions.segment()) != 0) {
                failure = "the frame generation swapchain would not give its functions";
                s.ffx.destroyContext(context);
                arena.close();
                return -1;
            }
            Linker linker = Linker.nativeLinker();
            MemorySegment f = functions.segment();
            s.create = linker.downcallHandle(f.get(ADDRESS, 16), FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
            s.destroy = linker.downcallHandle(f.get(ADDRESS, 24), FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
            s.images = linker.downcallHandle(f.get(ADDRESS, 32), FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
            s.acquire = linker.downcallHandle(f.get(ADDRESS, 40),
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
            s.present = linker.downcallHandle(f.get(ADDRESS, 48), FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));
            s.count = linker.downcallHandle(f.get(ADDRESS, 64), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
            s.swapchainArena = arena;
            s.swapchainContext = context;
            s.proxy = swapchain.get(JAVA_LONG, 0);
            noteSize(s, createInfo);
            s.frameId = 0;
            MemorySegment.ofAddress(pSwapchain).reinterpret(8).set(JAVA_LONG, 0, s.proxy);
            log.info("FSR frame generation: swapchain {}x{}", s.width, s.height);
            return VK_SUCCESS;
        } catch (Throwable t) {
            arena.close();
            throw t;
        }
    }

    /** The swapchain is made again for a new size or present mode, keeping the library's context. */
    private static int recreate(long device, long createInfo, long pSwapchain) throws Throwable {
        Session s = session;
        // The library passes the create info on to Vulkan as it is, and its "old swapchain" is not a Vulkan swapchain.
        MemorySegment.ofAddress(createInfo).reinterpret(FfxFrameGeneration.SWAPCHAIN_CREATE_INFO_SIZE)
                .set(JAVA_LONG, FfxFrameGeneration.OLD_SWAPCHAIN_OFFSET, 0L);
        int result = (int) s.create.invokeExact(device, createInfo, 0L, pSwapchain, s.proxy);
        if (result == VK_SUCCESS) {
            s.skipDestroy = s.proxy; // the engine now destroys "the old one", which is this one
            noteSize(s, createInfo);
            log.info("FSR frame generation: swapchain made again, {}x{}", s.width, s.height);
        }
        return result;
    }

    private static void noteSize(Session s, long createInfo) {
        MemorySegment info = MemorySegment.ofAddress(createInfo).reinterpret(FfxFrameGeneration.SWAPCHAIN_CREATE_INFO_SIZE);
        s.format = info.get(JAVA_INT, 36);
        s.width = info.get(JAVA_INT, 44);
        s.height = info.get(JAVA_INT, 48);
    }

    /** Takes down frame generation and the library's swapchain, in the order its documentation gives. */
    private static void teardown(Session s, long device) {
        dropFrameGeneration(s);
        if (s.proxy == 0L) return;
        try {
            s.ffx.dispatch(s.swapchainContext, FfxFrameGeneration.waitForPresents(Arena.ofAuto()).segment());
            int result = s.ffx.destroyContext(s.swapchainContext);
            if (result != 0) log.warn("ffxDestroyContext of the frame generation swapchain returned {}", result);
            s.destroy.invokeExact(device, s.proxy, 0L, 0L);
        } catch (Throwable t) {
            log.warn("FSR frame generation: taking down the swapchain failed: {}", t.toString());
        }
        s.swapchainArena.close();
        s.proxy = 0L;
        s.skipDestroy = 0L;
        log.info("FSR frame generation: swapchain taken down");
    }

    // ---- frame generation ---------------------------------------------------------------------------------------

    private static boolean ensureFrameGeneration(Session s) {
        if (s.fg != null) return true;
        if (s.fgFailed) return false;
        Arena arena = Arena.ofShared();
        try (Arena call = Arena.ofConfined()) {
            int flags = Boolean.getBoolean("rengl.fsr.debug") ? 1 << 6 : 0;
            MemorySegment handle = arena.allocate(8, 8);
            // The renders can be as big as the display, so a change of the render scale does not need another context.
            CStruct create = FfxFrameGeneration.createContext(call, flags, s.width, s.height, s.width, s.height,
                    FfxFrameGeneration.surfaceFormat(s.format));
            CStruct backend = Ffx.backend(call, s.device, s.physicalDevice, deviceProcAddr());
            create.chain(backend);
            backend.chain(FfxFrameGeneration.contextVersion(call));
            int result = s.ffx.rawCreate(handle, create.segment());
            if (result != 0) {
                s.fgFailed = true;
                log.warn("FSR frame generation: ffxCreateContext returned {}", result);
                arena.close();
                return false;
            }
            s.fg = new Fg(arena, handle, s.width, s.height);
            log.info("FSR frame generation: context for {}x{}", s.width, s.height);
            return true;
        }
    }

    private static long deviceProcAddr() {
        long address = VK.getFunctionProvider().getFunctionAddress("vkGetDeviceProcAddr");
        if (address == 0L) throw new IllegalStateException("vkGetDeviceProcAddr was not found");
        return address;
    }

    /** Waits for the frames in flight and takes the frame generation context down; it is made again for the next frame. */
    private static void dropFrameGeneration(Session s) {
        Fg fg = s.fg;
        if (fg == null) return;
        s.fg = null;
        s.fgFailed = false;
        try {
            if (s.proxy != 0L) {
                configure(s, fg, false);
                s.ffx.dispatch(s.swapchainContext, FfxFrameGeneration.waitForPresents(Arena.ofAuto()).segment());
            }
            int result = s.ffx.destroyContext(fg.handle);
            if (result != 0) log.warn("ffxDestroyContext of the frame generation context returned {}", result);
        } finally {
            fg.arena.close();
            s.configuredOn = false;
        }
    }

    private static int configure(Session s, Fg fg, boolean on) {
        try (Arena call = Arena.ofConfined()) {
            CStruct config = FfxFrameGeneration.configure(call, s.proxy, on ? dispatchCallback() : MemorySegment.NULL,
                    on ? fg.handle : MemorySegment.NULL, on, false, DEBUG_TEAR_LINES ? 1 : 0, s.width, s.height, s.frameId);
            return s.ffx.configure(fg.handle, config.segment());
        }
    }

    /** What the swapchain calls, while it presents, to have the generated frame recorded: the library does it, as the one asked. */
    private static int onDispatch(MemorySegment params, MemorySegment context) {
        try {
            return Ffx.load().dispatch(context, params);
        } catch (Throwable t) {
            log.error("FSR frame generation dispatch failed: {}", t.toString());
            return 1;
        }
    }

    private static synchronized MemorySegment dispatchCallback() {
        if (dispatchCallback == null) {
            try {
                MethodHandle target = MethodHandles.lookup().findStatic(FsrFrameGeneration.class, "onDispatch",
                        MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class));
                dispatchCallback = Linker.nativeLinker().upcallStub(target, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), Arena.global());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return dispatchCallback;
    }

    /**
     * Hands frame generation the depth, the motion and the camera of the scene just upscaled, in the command buffer the upscaler
     * records into, and tells the swapchain to generate a frame between this one and the last when the window presents.
     */
    static void prepare(UpscaleInputs in, long commandBuffer, VkImageInfo depth, VkImageInfo motion, float deltaMilliseconds, boolean reset) {
        Session s = session;
        if (s == null) return;
        synchronized (lock) {
            if (s.proxy == 0L || s.plain) return;
            if (in.displayWidth() != s.width || in.displayHeight() != s.height) {
                if (!s.warnedSize) {
                    s.warnedSize = true;
                    log.warn("FSR frame generation: the 3D view is {}x{} and the window {}x{}; the view has to fill the window",
                            in.displayWidth(), in.displayHeight(), s.width, s.height);
                }
                return;
            }
            s.warnedSize = false;
            if (!enabled && s.fg == null) return;
            if (!ensureFrameGeneration(s)) return;
            int result = configure(s, s.fg, enabled);
            if (result != 0) {
                log.warn("FSR frame generation: ffxConfigure returned {}", result);
                return;
            }
            s.configuredOn = enabled;
            if (!enabled) {
                s.prepared = true;
                return;
            }
            try (Arena call = Arena.ofConfined()) {
                CStruct prepare = FfxFrameGeneration.prepare(call, s.frameId, commandBuffer, in.renderWidth(), in.renderHeight(), in.jitterX(),
                        in.jitterY(), deltaMilliseconds, reset, in.near(), in.far(), in.fovYRadians(), depth, motion,
                        vector(in.cameraPosition()), vector(in.cameraUp()), vector(in.cameraRight()), vector(in.cameraForward()));
                result = s.ffx.dispatch(s.fg.handle, prepare.segment());
            }
            if (result != 0) {
                log.warn("FSR frame generation: the prepare dispatch returned {}", result);
                s.configuredOn = false;
                return;
            }
            s.prepared = true;
        }
    }

    private static float[] vector(me.piitex.engine.render3d.math.Vec3 v) {
        return v == null ? new float[3] : new float[]{v.x(), v.y(), v.z()};
    }

    /** The points of a window frame: each frame has its number, and a frame the scene did not draw is presented without generating. */
    static void onFrame(VulkanExtensionProvider.FrameEvent event) {
        Session s = session;
        if (s == null) return;
        switch (event) {
            case RENDER_STARTED -> {
                s.frameId++;
                s.prepared = false;
            }
            case PRESENT_STARTED -> {
                synchronized (lock) {
                    // Depth and motion were not handed over this frame (the scene was not drawn): there is nothing to generate from.
                    if (s.fg != null && !s.prepared && s.configuredOn && s.proxy != 0L) {
                        configure(s, s.fg, false);
                        s.configuredOn = false;
                    }
                }
            }
            case SWAPCHAIN_CHANGING -> {
                synchronized (lock) {
                    dropFrameGeneration(s);
                }
            }
            default -> {
            }
        }
    }
}
