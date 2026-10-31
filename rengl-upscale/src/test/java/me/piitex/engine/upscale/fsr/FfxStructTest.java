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
 * The structs handed to the FidelityFX library, checked field by field against the layout its C headers give them on a
 * 64-bit target: where each field sits and how big each struct is. A wrong offset would not fail here but hand the SDK
 * garbage, so the offsets are written out by hand from the headers and not computed.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class FfxStructTest {
    private static final VkImageInfo COLOR = new VkImageInfo(0xC0L, 1, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT, 640, 360,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
    private static final VkImageInfo DEPTH = new VkImageInfo(0xD0L, 2, VK_FORMAT_D32_SFLOAT, VK_IMAGE_ASPECT_DEPTH_BIT, 640, 360,
            VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
    private static final VkImageInfo MOTION = new VkImageInfo(0xA0L, 3, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT, 640, 360,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
    private static final VkImageInfo OUTPUT = new VkImageInfo(0xE0L, 4, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT, 1280, 720,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT);

    @Test
    void theDispatchDescriptionHasTheLayoutOfTheHeader() {
        try (Arena arena = Arena.ofConfined()) {
            CStruct d = FsrUpscaler.dispatchDescription(arena, 0xCBL, COLOR, DEPTH, MOTION, OUTPUT, 0.25f, -0.4f,
                    640, 360, 1280, 720, 16.5f, true, 0.1f, 500f, 1.0f);
            MemorySegment s = d.segment();
            assertEquals(432, d.size(), "sizeof(ffxDispatchDescUpscale)");
            assertEquals(0x00010001L, s.get(JAVA_LONG, 0), "header.type");
            assertEquals(0L, s.get(JAVA_LONG, 8), "header.pNext");
            assertEquals(0xCBL, s.get(ADDRESS, 16).address(), "commandList");

            // color at 24, depth at 72, motionVectors at 120, exposure at 168, reactive at 216, transparencyAndComposition at 264, output at 312
            assertEquals(0xC0L, s.get(ADDRESS, 24).address(), "color.resource");
            assertEquals(2, s.get(JAVA_INT, 32), "color.description.type is a 2D texture");
            assertEquals(4, s.get(JAVA_INT, 36), "color.description.format is R16G16B16A16_FLOAT");
            assertEquals(640, s.get(JAVA_INT, 40), "color.description.width");
            assertEquals(360, s.get(JAVA_INT, 44), "color.description.height");
            assertEquals(1, s.get(JAVA_INT, 48), "color.description.depth");
            assertEquals(1, s.get(JAVA_INT, 52), "color.description.mipCount");
            assertEquals(0, s.get(JAVA_INT, 56), "color.description.flags");
            assertEquals(1, s.get(JAVA_INT, 60), "color.description.usage is a render target");
            assertEquals(4, s.get(JAVA_INT, 64), "color.state is compute read");

            assertEquals(0xD0L, s.get(ADDRESS, 72).address(), "depth.resource");
            assertEquals(28, s.get(JAVA_INT, 84), "depth.description.format is R32_FLOAT");
            assertEquals(4, s.get(JAVA_INT, 108), "depth.description.usage is a depth target");
            assertEquals(0xA0L, s.get(ADDRESS, 120).address(), "motionVectors.resource");
            for (long at = 168; at < 312; at++) assertEquals(0, s.get(JAVA_BYTE, at), "exposure, reactive and transparency are empty at " + at);

            assertEquals(0xE0L, s.get(ADDRESS, 312).address(), "output.resource");
            assertEquals(1280, s.get(JAVA_INT, 328), "output.description.width");
            assertEquals(720, s.get(JAVA_INT, 332), "output.description.height");
            assertEquals(3, s.get(JAVA_INT, 348), "output.description.usage is a render target and a storage image");
            assertEquals(2, s.get(JAVA_INT, 352), "output.state is unordered access");

            assertEquals(0.25f, s.get(JAVA_FLOAT, 360), "jitterOffset.x");
            assertEquals(-0.4f, s.get(JAVA_FLOAT, 364), "jitterOffset.y");
            assertEquals(1f, s.get(JAVA_FLOAT, 368), "motionVectorScale.x");
            assertEquals(1f, s.get(JAVA_FLOAT, 372), "motionVectorScale.y");
            assertEquals(640, s.get(JAVA_INT, 376), "renderSize.width");
            assertEquals(360, s.get(JAVA_INT, 380), "renderSize.height");
            assertEquals(1280, s.get(JAVA_INT, 384), "upscaleSize.width");
            assertEquals(720, s.get(JAVA_INT, 388), "upscaleSize.height");
            assertEquals(0, s.get(JAVA_BYTE, 392), "enableSharpening");
            assertEquals(0f, s.get(JAVA_FLOAT, 396), "sharpness");
            assertEquals(16.5f, s.get(JAVA_FLOAT, 400), "frameTimeDelta");
            assertEquals(1f, s.get(JAVA_FLOAT, 404), "preExposure");
            assertEquals(1, s.get(JAVA_BYTE, 408), "reset");
            assertEquals(0.1f, s.get(JAVA_FLOAT, 412), "cameraNear");
            assertEquals(500f, s.get(JAVA_FLOAT, 416), "cameraFar");
            assertEquals(1.0f, s.get(JAVA_FLOAT, 420), "cameraFovAngleVertical");
            assertEquals(1f, s.get(JAVA_FLOAT, 424), "viewSpaceToMetersFactor");
            assertEquals(0, s.get(JAVA_INT, 428), "flags");
        }
    }

    @Test
    void theContextDescriptionsHaveTheLayoutOfTheHeaders() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment messages = MemorySegment.ofAddress(0x99L);
            CStruct create = Ffx.createUpscale(arena, Ffx.ENABLE_HIGH_DYNAMIC_RANGE | Ffx.ENABLE_AUTO_EXPOSURE, 640, 360, 1280, 720, messages);
            MemorySegment c = create.segment();
            assertEquals(48, create.size(), "sizeof(ffxCreateContextDescUpscale)");
            assertEquals(0x00010000L, c.get(JAVA_LONG, 0));
            assertEquals(33, c.get(JAVA_INT, 16), "flags: high dynamic range (1) and auto exposure (32)");
            assertEquals(640, c.get(JAVA_INT, 20), "maxRenderSize.width");
            assertEquals(360, c.get(JAVA_INT, 24), "maxRenderSize.height");
            assertEquals(1280, c.get(JAVA_INT, 28), "maxUpscaleSize.width");
            assertEquals(720, c.get(JAVA_INT, 32), "maxUpscaleSize.height");
            assertEquals(0x99L, c.get(ADDRESS, 40).address(), "fpMessage");

            CStruct version = Ffx.headerVersion(arena);
            assertEquals(24, version.size());
            assertEquals(0x0001000BL, version.segment().get(JAVA_LONG, 0));
            assertEquals(4 * 4194304 + 1 * 4096 + 2, version.segment().get(JAVA_INT, 16), "(4 << 22) | (1 << 12) | 2");

            CStruct override = Ffx.overrideVersion(arena, 0x8000_0001_2345L);
            assertEquals(24, override.size());
            assertEquals(5L, override.segment().get(JAVA_LONG, 0));
            assertEquals(0x8000_0001_2345L, override.segment().get(JAVA_LONG, 16));

            create.chain(version);
            assertEquals(version.segment().address(), c.get(ADDRESS, 8).address(), "header.pNext");
        }
    }

    @Test
    void theBackendDescriptionCarriesTheDeviceContextByValue() {
        try (Arena arena = Arena.ofConfined()) {
            // A struct written the way backend() writes it, without a device: the fields are three pointers after the header.
            CStruct backend = new CStruct(arena, 40).header(Ffx.CREATE_BACKEND_VK).ptr(0xD1L).ptr(0xD2L).ptr(0xD3L).end();
            assertEquals(40, backend.size(), "sizeof(ffxCreateBackendVKDesc)");
            assertEquals(0x01000003L, backend.segment().get(JAVA_LONG, 0), "FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_VK");
            assertEquals(0xD1L, backend.segment().get(ADDRESS, 16).address(), "vkDevice");
            assertEquals(0xD2L, backend.segment().get(ADDRESS, 24).address(), "vkPhysicalDevice");
            assertEquals(0xD3L, backend.segment().get(ADDRESS, 32).address(), "vkDeviceProcAddr");
        }
    }

    @Test
    void theQueryDescriptionHasTheLayoutOfTheHeader() {
        try (Arena arena = Arena.ofConfined()) {
            CStruct q = Ffx.getVersions(arena, MemorySegment.ofAddress(0x10), MemorySegment.ofAddress(0x20), MemorySegment.ofAddress(0x30),
                    MemorySegment.ofAddress(0x40));
            MemorySegment s = q.segment();
            assertEquals(56, q.size(), "sizeof(ffxQueryDescGetVersions)");
            assertEquals(4L, s.get(JAVA_LONG, 0), "FFX_API_QUERY_DESC_TYPE_GET_VERSIONS");
            assertEquals(0x00010000L, s.get(JAVA_LONG, 16), "createDescType");
            assertEquals(0x10L, s.get(ADDRESS, 24).address(), "device");
            assertEquals(0x20L, s.get(ADDRESS, 32).address(), "outputCount");
            assertEquals(0x30L, s.get(ADDRESS, 40).address(), "versionIds");
            assertEquals(0x40L, s.get(ADDRESS, 48).address(), "versionNames");
        }
    }

    @Test
    void formatsAndUsagesMapToTheSdksValues() {
        assertEquals(4, Ffx.surfaceFormat(VK_FORMAT_R16G16B16A16_SFLOAT));
        assertEquals(28, Ffx.surfaceFormat(VK_FORMAT_D32_SFLOAT));
        assertEquals(10, Ffx.surfaceFormat(VK_FORMAT_R8G8B8A8_UNORM));
        assertEquals(0, Ffx.surfaceFormat(VK_FORMAT_D24_UNORM_S8_UINT), "a format the SDK does not take is unknown");
        assertEquals(0, Ffx.resourceUsage(VK_IMAGE_USAGE_SAMPLED_BIT));
        assertEquals(1 | 2, Ffx.resourceUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_STORAGE_BIT));
        assertEquals(4, Ffx.resourceUsage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT));
    }
}
