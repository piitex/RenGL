package me.piitex.engine.upscale.fsr;

import me.piitex.engine.render3d.vk.VkImageInfo;
import me.piitex.engine.upscale.CStruct;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The structs of FidelityFX frame generation and its Vulkan swapchain, checked against the layout the headers
 * ({@code ffx_framegeneration.h}, {@code ffx_api_framegeneration_vk.h}) give them on a 64-bit target. The offsets are written
 * out by hand from the declarations, not computed.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class FfxFrameGenStructTest {
    private static final VkImageInfo DEPTH = new VkImageInfo(0xD0L, 2, VK_FORMAT_D32_SFLOAT, VK_IMAGE_ASPECT_DEPTH_BIT, 640, 360,
            VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
    private static final VkImageInfo MOTION = new VkImageInfo(0xA0L, 3, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT, 640, 360,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);

    @Test
    void theContextDescriptionsHaveTheLayoutOfTheHeader() {
        try (Arena arena = Arena.ofConfined()) {
            CStruct create = FfxFrameGeneration.createContext(arena, 64, 1280, 720, 1000, 600, 14);
            MemorySegment s = create.segment();
            assertEquals(40, create.size(), "sizeof(ffxCreateContextDescFrameGeneration)");
            assertEquals(0x00020001L, s.get(JAVA_LONG, 0), "header.type");
            assertEquals(64, s.get(JAVA_INT, 16), "flags");
            assertEquals(1280, s.get(JAVA_INT, 20), "displaySize.width");
            assertEquals(720, s.get(JAVA_INT, 24), "displaySize.height");
            assertEquals(1000, s.get(JAVA_INT, 28), "maxRenderSize.width");
            assertEquals(600, s.get(JAVA_INT, 32), "maxRenderSize.height");
            assertEquals(14, s.get(JAVA_INT, 36), "backBufferFormat");

            CStruct version = FfxFrameGeneration.contextVersion(arena);
            assertEquals(24, version.size());
            assertEquals(0x0002000EL, version.segment().get(JAVA_LONG, 0), "header.type");
            assertEquals((4 << 22) | 2, version.segment().get(JAVA_INT, 16), "version is 4.0.2");

            CStruct swapchainVersion = FfxFrameGeneration.swapchainVersion(arena);
            assertEquals(24, swapchainVersion.size());
            assertEquals(0x0004000AL, swapchainVersion.segment().get(JAVA_LONG, 0), "header.type");
            assertEquals(4 << 22, swapchainVersion.segment().get(JAVA_INT, 16), "version is 4.0.0");
        }
    }

    @Test
    void theConfigurationHasTheLayoutOfTheHeader() {
        try (Arena arena = Arena.ofConfined()) {
            CStruct c = FfxFrameGeneration.configure(arena, 0x5CL, MemorySegment.ofAddress(0xCB), MemorySegment.ofAddress(0xC0), true, false,
                    1, 1280, 720, 77L);
            MemorySegment s = c.segment();
            assertEquals(144, c.size(), "sizeof(ffxConfigureDescFrameGeneration)");
            assertEquals(0x00020002L, s.get(JAVA_LONG, 0), "header.type");
            assertEquals(0x5CL, s.get(ADDRESS, 16).address(), "swapChain");
            assertEquals(0L, s.get(ADDRESS, 24).address(), "presentCallback");
            assertEquals(0L, s.get(ADDRESS, 32).address(), "presentCallbackUserContext");
            assertEquals(0xCBL, s.get(ADDRESS, 40).address(), "frameGenerationCallback");
            assertEquals(0xC0L, s.get(ADDRESS, 48).address(), "frameGenerationCallbackUserContext");
            assertEquals(1, s.get(JAVA_BYTE, 56), "frameGenerationEnabled");
            assertEquals(0, s.get(JAVA_BYTE, 57), "allowAsyncWorkloads");
            for (long at = 64; at < 112; at++) assertEquals(0, s.get(JAVA_BYTE, at), "HUDLessColor is empty at " + at);
            assertEquals(1, s.get(JAVA_INT, 112), "flags");
            assertEquals(0, s.get(JAVA_BYTE, 116), "onlyPresentGenerated");
            assertEquals(0, s.get(JAVA_INT, 120), "generationRect.left");
            assertEquals(0, s.get(JAVA_INT, 124), "generationRect.top");
            assertEquals(1280, s.get(JAVA_INT, 128), "generationRect.width");
            assertEquals(720, s.get(JAVA_INT, 132), "generationRect.height");
            assertEquals(77L, s.get(JAVA_LONG, 136), "frameID");
        }
    }

    @Test
    void thePrepareDescriptionHasTheLayoutOfTheHeader() {
        try (Arena arena = Arena.ofConfined()) {
            CStruct d = FfxFrameGeneration.prepare(arena, 9L, 0xCBL, 640, 360, 0.25f, -0.4f, 16.5f, true, 0.1f, 500f, 1.0f, DEPTH, MOTION,
                    new float[]{1, 2, 3}, new float[]{0, 1, 0}, new float[]{1, 0, 0}, new float[]{0, 0, -1});
            MemorySegment s = d.segment();
            assertEquals(232, d.size(), "sizeof(ffxDispatchDescFrameGenerationPrepareV2)");
            assertEquals(0x0002000CL, s.get(JAVA_LONG, 0), "header.type");
            assertEquals(9L, s.get(JAVA_LONG, 16), "frameID");
            assertEquals(0, s.get(JAVA_INT, 24), "flags");
            assertEquals(0xCBL, s.get(ADDRESS, 32).address(), "commandList");
            assertEquals(640, s.get(JAVA_INT, 40), "renderSize.width");
            assertEquals(360, s.get(JAVA_INT, 44), "renderSize.height");
            assertEquals(0.25f, s.get(JAVA_FLOAT, 48), "jitterOffset.x");
            assertEquals(-0.4f, s.get(JAVA_FLOAT, 52), "jitterOffset.y");
            assertEquals(1f, s.get(JAVA_FLOAT, 56), "motionVectorScale.x");
            assertEquals(1f, s.get(JAVA_FLOAT, 60), "motionVectorScale.y");
            assertEquals(16.5f, s.get(JAVA_FLOAT, 64), "frameTimeDelta");
            assertEquals(1, s.get(JAVA_BYTE, 68), "reset");
            assertEquals(0.1f, s.get(JAVA_FLOAT, 72), "cameraNear");
            assertEquals(500f, s.get(JAVA_FLOAT, 76), "cameraFar");
            assertEquals(1.0f, s.get(JAVA_FLOAT, 80), "cameraFovAngleVertical");
            assertEquals(1f, s.get(JAVA_FLOAT, 84), "viewSpaceToMetersFactor");

            assertEquals(0xD0L, s.get(ADDRESS, 88).address(), "depth.resource");
            assertEquals(28, s.get(JAVA_INT, 100), "depth.description.format is R32_FLOAT");
            assertEquals(640, s.get(JAVA_INT, 104), "depth.description.width");
            assertEquals(4, s.get(JAVA_INT, 124), "depth.description.usage is a depth target");
            assertEquals(4, s.get(JAVA_INT, 128), "depth.state is compute read");
            assertEquals(0xA0L, s.get(ADDRESS, 136).address(), "motionVectors.resource");
            assertEquals(4, s.get(JAVA_INT, 148), "motionVectors.description.format is R16G16B16A16_FLOAT");
            assertEquals(4, s.get(JAVA_INT, 176), "motionVectors.state is compute read");

            assertEquals(1f, s.get(JAVA_FLOAT, 184), "cameraPosition.x");
            assertEquals(3f, s.get(JAVA_FLOAT, 192), "cameraPosition.z");
            assertEquals(1f, s.get(JAVA_FLOAT, 200), "cameraUp.y");
            assertEquals(1f, s.get(JAVA_FLOAT, 208), "cameraRight.x");
            assertEquals(-1f, s.get(JAVA_FLOAT, 228), "cameraForward.z");
        }
    }

    @Test
    void theSwapchainDescriptionTakesTheCreateInfoByValue() {
        try (Arena arena = Arena.ofConfined()) {
            // A VkSwapchainCreateInfoKHR: 104 bytes, with the old swapchain (offset 96) set, which a new swapchain must not keep.
            MemorySegment info = arena.allocate(FfxFrameGeneration.SWAPCHAIN_CREATE_INFO_SIZE, 8);
            info.set(JAVA_INT, 0, 1000001000); // sType
            info.set(JAVA_INT, 36, VK_FORMAT_B8G8R8A8_UNORM);
            info.set(JAVA_INT, 44, 512);
            info.set(JAVA_INT, 48, 384);
            info.set(JAVA_LONG, 96, 0xDEADL);
            MemorySegment pSwapchain = arena.allocate(8, 8);
            CStruct c = FfxFrameGeneration.createSwapchain(arena, 0x9AL, 0xDEL, pSwapchain, info.address(), 0x61L, 0x62L, 0x63L, 0x64L, 3);
            MemorySegment s = c.segment();
            assertEquals(248, c.size(), "sizeof(ffxCreateContextDescFrameGenerationSwapChainVK)");
            assertEquals(0x00040001L, s.get(JAVA_LONG, 0), "header.type");
            assertEquals(0x9AL, s.get(ADDRESS, 16).address(), "physicalDevice");
            assertEquals(0xDEL, s.get(ADDRESS, 24).address(), "device");
            assertEquals(pSwapchain.address(), s.get(ADDRESS, 32).address(), "swapchain");
            assertEquals(0L, s.get(ADDRESS, 40).address(), "allocator");
            assertEquals(1000001000, s.get(JAVA_INT, 48), "createInfo.sType");
            assertEquals(VK_FORMAT_B8G8R8A8_UNORM, s.get(JAVA_INT, 48 + 36), "createInfo.imageFormat");
            assertEquals(512, s.get(JAVA_INT, 48 + 44), "createInfo.imageExtent.width");
            assertEquals(384, s.get(JAVA_INT, 48 + 48), "createInfo.imageExtent.height");
            assertEquals(0L, s.get(JAVA_LONG, 48 + 96), "createInfo.oldSwapchain is dropped");
            assertEquals(0xDEADL, info.get(JAVA_LONG, 96), "the engine's own create info is left as it was");

            // gameQueue, asyncComputeQueue, presentQueue, imageAcquireQueue: the queue, its family and no submit function
            long[] queues = {0x61, 0x62, 0x63, 0x64};
            for (int i = 0; i < 4; i++) {
                long at = 152 + 24L * i;
                assertEquals(queues[i], s.get(ADDRESS, at).address(), "queue " + i);
                assertEquals(3, s.get(JAVA_INT, at + 8), "family of queue " + i);
                assertEquals(0L, s.get(ADDRESS, at + 16).address(), "submitFunc of queue " + i);
            }
        }
    }

    @Test
    void theQueryAndDispatchDescriptionsAreAsBigAsTheHeaderSays() {
        try (Arena arena = Arena.ofConfined()) {
            CStruct functions = FfxFrameGeneration.replacementFunctions(arena);
            assertEquals(72, functions.size(), "sizeof(ffxQueryDescSwapchainReplacementFunctionsVK): a header and seven functions");
            assertEquals(0x00040005L, functions.segment().get(JAVA_LONG, 0), "header.type");
            CStruct wait = FfxFrameGeneration.waitForPresents(arena);
            assertEquals(16, wait.size(), "sizeof(ffxDispatchDescFrameGenerationSwapChainWaitForPresentsVK)");
            assertEquals(0x00040007L, wait.segment().get(JAVA_LONG, 0), "header.type");
        }
    }

    @Test
    void theSwapchainFormatsAreTheLibrarys() {
        assertEquals(14, FfxFrameGeneration.surfaceFormat(VK_FORMAT_B8G8R8A8_UNORM), "FFX_API_SURFACE_FORMAT_B8G8R8A8_UNORM");
        assertEquals(10, FfxFrameGeneration.surfaceFormat(VK_FORMAT_R8G8B8A8_UNORM), "FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM");
        assertEquals(0, FfxFrameGeneration.surfaceFormat(VK_FORMAT_R16G16B16A16_SFLOAT), "unknown");
    }
}
