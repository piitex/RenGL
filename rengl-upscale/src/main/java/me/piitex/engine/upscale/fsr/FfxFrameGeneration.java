package me.piitex.engine.upscale.fsr;

import me.piitex.engine.render3d.vk.VkImageInfo;
import me.piitex.engine.upscale.CStruct;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

/**
 * The descriptor structs of the FidelityFX frame generation API and of its Vulkan swapchain
 * ({@code ffx_framegeneration.h}, {@code ffx_api_framegeneration_vk.h}), written into native memory the way {@link Ffx} writes
 * the upscaler's. Sizes and offsets follow what a 64-bit C++ compiler gives the declarations.
 */
final class FfxFrameGeneration {
    private FfxFrameGeneration() {
    }

    /** The size of a {@code VkSwapchainCreateInfoKHR}, which the swapchain's creation takes by value. */
    static final int SWAPCHAIN_CREATE_INFO_SIZE = 104;
    /** Where the create info keeps {@code oldSwapchain}. */
    static final int OLD_SWAPCHAIN_OFFSET = 96;

    /** FfxApiSurfaceFormat of the swapchain's formats, 0 (unknown) for one it does not take. */
    static int surfaceFormat(int vkFormat) {
        if (vkFormat == VK_FORMAT_B8G8R8A8_UNORM) return 14;
        if (vkFormat == VK_FORMAT_R8G8B8A8_UNORM) return 10;
        return 0;
    }

    /** An {@code ffxCreateContextDescFrameGeneration} (40 bytes). */
    static CStruct createContext(Arena arena, int flags, int displayWidth, int displayHeight, int maxRenderWidth, int maxRenderHeight,
                                 int backBufferFormat) {
        return new CStruct(arena, 40).header(Ffx.CREATE_FRAME_GENERATION).u32(flags).u32(displayWidth).u32(displayHeight)
                .u32(maxRenderWidth).u32(maxRenderHeight).u32(backBufferFormat).end();
    }

    /** An {@code ffxCreateContextDescFrameGenerationVersion} (24 bytes). */
    static CStruct contextVersion(Arena arena) {
        return new CStruct(arena, 24).header(Ffx.CREATE_FRAME_GENERATION_VERSION).u32(Ffx.FRAME_GENERATION_HEADER_VERSION).end();
    }

    /**
     * An {@code ffxConfigureDescFrameGeneration} (144 bytes). The swapchain calls {@code dispatchCallback} on the presenting
     * thread to have the generated frame recorded; no UI composition callback is given (the swapchain's own copies the picture).
     */
    static CStruct configure(Arena arena, long swapchain, MemorySegment dispatchCallback, MemorySegment callbackContext, boolean enabled,
                             boolean async, int flags, int width, int height, long frameId) {
        return new CStruct(arena, 144).header(Ffx.CONFIGURE_FRAME_GENERATION)
                .ptr(swapchain)                  // swapChain
                .ptr(MemorySegment.NULL)         // presentCallback
                .ptr(MemorySegment.NULL)         // presentCallbackUserContext
                .ptr(dispatchCallback)           // frameGenerationCallback
                .ptr(callbackContext)            // frameGenerationCallbackUserContext
                .bool(enabled).bool(async)
                .ptr(MemorySegment.NULL).skip(40) // HUDLessColor: none
                .u32(flags)
                .bool(false)                     // onlyPresentGenerated
                .u32(0).u32(0).u32(width).u32(height) // generationRect
                .u64(frameId)
                .end();
    }

    /**
     * An {@code ffxDispatchDescFrameGenerationPrepareV2} (232 bytes): the depth, the motion and the camera of this frame, from
     * which the context makes what it needs to generate the frame between this one and the last.
     */
    static CStruct prepare(Arena arena, long frameId, long commandList, int renderWidth, int renderHeight, float jitterX, float jitterY,
                           float deltaMilliseconds, boolean reset, float near, float far, float fovYRadians, VkImageInfo depth,
                           VkImageInfo motion, float[] position, float[] up, float[] right, float[] forward) {
        CStruct d = new CStruct(arena, 232).header(Ffx.DISPATCH_FRAME_GENERATION_PREPARE_V2).u64(frameId).u32(0).ptr(commandList)
                .u32(renderWidth).u32(renderHeight)
                .f32(jitterX).f32(jitterY)
                .f32(1f).f32(1f)                  // motion is in render pixels already
                .f32(deltaMilliseconds)
                .bool(reset)
                .f32(near).f32(far).f32(fovYRadians).f32(1f);
        Ffx.resource(d, depth, Ffx.STATE_COMPUTE_READ);
        Ffx.resource(d, motion, Ffx.STATE_COMPUTE_READ);
        for (float[] v : new float[][]{position, up, right, forward}) d.f32(v[0]).f32(v[1]).f32(v[2]);
        return d.end();
    }

    /** One {@code VkQueueInfoFFXAPI} (24 bytes): the queue, its family, and no submit function of our own. */
    private static void queue(CStruct into, long queue, int family) {
        into.ptr(queue).u32(family).ptr(MemorySegment.NULL);
    }

    /**
     * An {@code ffxCreateContextDescFrameGenerationSwapChainVK} (248 bytes) for a swapchain made from {@code createInfo}, the
     * address of the engine's {@code VkSwapchainCreateInfoKHR}. {@code pSwapchain} receives the swapchain that replaces it.
     */
    static CStruct createSwapchain(Arena arena, long physicalDevice, long device, MemorySegment pSwapchain, long createInfo,
                                   long gameQueue, long computeQueue, long presentQueue, long acquireQueue, int family) {
        CStruct c = new CStruct(arena, 248).header(Ffx.CREATE_SWAPCHAIN).ptr(physicalDevice).ptr(device).ptr(pSwapchain)
                .ptr(MemorySegment.NULL)
                .raw(MemorySegment.ofAddress(createInfo).reinterpret(SWAPCHAIN_CREATE_INFO_SIZE), SWAPCHAIN_CREATE_INFO_SIZE);
        // A new swapchain has no old one to replace.
        c.segment().set(java.lang.foreign.ValueLayout.JAVA_LONG, 48 + OLD_SWAPCHAIN_OFFSET, 0L);
        queue(c, gameQueue, family);
        queue(c, computeQueue, family);
        queue(c, presentQueue, family);
        queue(c, acquireQueue, family);
        return c.end();
    }

    /** An {@code ffxCreateContextDescFrameGenerationSwapChainVersionVK} (24 bytes). */
    static CStruct swapchainVersion(Arena arena) {
        return new CStruct(arena, 24).header(Ffx.CREATE_SWAPCHAIN_VERSION).u32(Ffx.SWAPCHAIN_HEADER_VERSION).end();
    }

    /**
     * An {@code ffxQueryDescSwapchainReplacementFunctionsVK} (72 bytes): the library fills in seven function pointers, at 16 and
     * on by 8: create, destroy, get images, acquire, present, set HDR metadata, and the count of presents.
     */
    static CStruct replacementFunctions(Arena arena) {
        return new CStruct(arena, 72).header(Ffx.QUERY_SWAPCHAIN_FUNCTIONS).skip(56).end();
    }

    /** An {@code ffxDispatchDescFrameGenerationSwapChainWaitForPresentsVK} (16 bytes). */
    static CStruct waitForPresents(Arena arena) {
        return new CStruct(arena, 16).header(Ffx.DISPATCH_SWAPCHAIN_WAIT).end();
    }
}
