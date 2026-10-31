package me.piitex.engine.upscale.dlss;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.upscale.UpscaleInputs;
import me.piitex.engine.render3d.vk.VkImageInfo;
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

/**
 * The structs written for Streamline, checked field by field against the offsets a C++ compiler gives the declarations in
 * Streamline's headers (64-bit: a pointer is 8 bytes and aligned to 8, a base struct is a pointer, a 16-byte GUID and a 64-bit
 * version, so 32 bytes, and the fields of a struct start after it).
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class SlStructTest {
    private static UpscaleInputs inputs() {
        return new UpscaleInputs(null, null, null, null, null, null, 64, 48, 128, 96, 0.25f, -0.25f, 0.5f, 20f, 1.0f, Vec3.ZERO, Vec3.X, Vec3.UP, Vec3.Z, 0.016f, 3, false, false);
    }

    @Test
    void theHeaderHoldsTheGuidAndTheVersion() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = Sl.struct(arena, 144, "1CA10965-BF8E-432B-8DA1-6716D879FB14", 1);
            assertEquals(0L, s.get(JAVA_LONG, 0), "no next struct");
            assertEquals(0x1CA10965, s.get(JAVA_INT, 8));
            assertEquals((short) 0xBF8E, s.get(java.lang.foreign.ValueLayout.JAVA_SHORT, 12));
            assertEquals((short) 0x432B, s.get(java.lang.foreign.ValueLayout.JAVA_SHORT, 14));
            int[] tail = {0x8D, 0xA1, 0x67, 0x16, 0xD8, 0x79, 0xFB, 0x14};
            for (int i = 0; i < 8; i++) assertEquals((byte) tail[i], s.get(JAVA_BYTE, 16 + i), "byte " + i + " of the last group");
            assertEquals(1L, s.get(JAVA_LONG, 24));
        }
    }

    @Test
    void theConstantsAreWhereTheHeaderPutsThem() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment c = DlssUpscaler.constants(arena, inputs(), true);
            assertEquals(456, c.byteSize());
            assertEquals(2L, c.get(JAVA_LONG, 24), "version 2");
            assertEquals(0.25f, c.get(JAVA_FLOAT, 352), "jitterOffset.x");
            assertEquals(-0.25f, c.get(JAVA_FLOAT, 356), "jitterOffset.y");
            assertEquals(1f / 64, c.get(JAVA_FLOAT, 360), "mvecScale.x");
            assertEquals(1f / 48, c.get(JAVA_FLOAT, 364), "mvecScale.y");
            assertEquals(0.5f, c.get(JAVA_FLOAT, 424), "cameraNear");
            assertEquals(20f, c.get(JAVA_FLOAT, 428), "cameraFar");
            assertEquals(1.0f, c.get(JAVA_FLOAT, 432), "cameraFOV");
            assertEquals(64f / 48, c.get(JAVA_FLOAT, 436), 1e-6f, "cameraAspectRatio");
            assertEquals(0, c.get(JAVA_BYTE, 444), "depth is not inverted");
            assertEquals(1, c.get(JAVA_BYTE, 445), "the motion includes the camera's");
            assertEquals(1, c.get(JAVA_BYTE, 447), "reset");
            assertEquals(40f, c.get(JAVA_FLOAT, 452), "minRelativeLinearDepthObjectSeparation");
        }
    }

    @Test
    void theProjectionAndItsInverseMultiplyToTheIdentity() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment c = DlssUpscaler.constants(arena, inputs(), false);
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 4; col++) {
                    float sum = 0;
                    for (int k = 0; k < 4; k++) sum += c.get(JAVA_FLOAT, 32 + 4L * (row * 4 + k)) * c.get(JAVA_FLOAT, 96 + 4L * (k * 4 + col));
                    assertEquals(row == col ? 1f : 0f, sum, 1e-4f, "element " + row + "," + col);
                }
            }
        }
    }

    @Test
    void aTaggedResourceCarriesTheImageAndItsLayout() {
        try (Arena arena = Arena.ofConfined()) {
            VkImageInfo image = new VkImageInfo(0x1111L, 0x2222L, 97, 1, 64, 48, 5, 0x1c);
            MemorySegment resource = Sl.resource(arena, image, 5);
            assertEquals(112, resource.byteSize());
            assertEquals(0x1111L, resource.get(ADDRESS, 40).address(), "native");
            assertEquals(0x2222L, resource.get(ADDRESS, 56).address(), "view");
            assertEquals(5, resource.get(JAVA_INT, 64), "state");
            assertEquals(64, resource.get(JAVA_INT, 68), "width");
            assertEquals(48, resource.get(JAVA_INT, 72), "height");
            assertEquals(97, resource.get(JAVA_INT, 76), "format");
            assertEquals(0x1c, resource.get(JAVA_INT, 100), "usage");
            MemorySegment tag = Sl.tag(arena, resource, Sl.BUFFER_DEPTH);
            assertEquals(resource.address(), tag.get(ADDRESS, 32).address());
            assertEquals(Sl.VALID_UNTIL_EVALUATE, tag.get(JAVA_INT, 44));
        }
    }

    @Test
    void theFrameGenerationStructsAreWhereTheHeadersPutThem() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment reflex = Sl.reflexOptions(arena, 1);
            assertEquals(48, reflex.byteSize());
            assertEquals(1L, reflex.get(JAVA_LONG, 24), "version 1");
            assertEquals(1, reflex.get(JAVA_INT, 32), "mode");

            MemorySegment options = Sl.dlssgOptions(arena, 1, 3, 8);
            assertEquals(120, options.byteSize());
            assertEquals(5L, options.get(JAVA_LONG, 24), "version 5");
            assertEquals(0xFAC5F1CB, options.get(JAVA_INT, 8), "its GUID");
            assertEquals(1, options.get(JAVA_INT, 32), "mode");
            assertEquals(3, options.get(JAVA_INT, 36), "numFramesToGenerate");
            assertEquals(8, options.get(JAVA_INT, 40), "flags");

            MemorySegment state = Sl.dlssgState(arena);
            assertEquals(88, state.byteSize());
            assertEquals(4L, state.get(JAVA_LONG, 24), "version 4");
            assertEquals(0xCC8AC8E1, state.get(JAVA_INT, 8), "its GUID");
        }
    }

    @Test
    void aTagCanNameTheAreaOfTheResourceToUse() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tag = Sl.tag(arena, MemorySegment.NULL, Sl.BUFFER_BACKBUFFER, Sl.VALID_UNTIL_PRESENT, 640, 480);
            assertEquals(53, tag.get(JAVA_INT, 40), "the back buffer");
            assertEquals(Sl.VALID_UNTIL_PRESENT, tag.get(JAVA_INT, 44));
            assertEquals(0, tag.get(JAVA_INT, 48), "top");
            assertEquals(0, tag.get(JAVA_INT, 52), "left");
            assertEquals(640, tag.get(JAVA_INT, 56), "width");
            assertEquals(480, tag.get(JAVA_INT, 60), "height");
        }
    }

    @Test
    void streamlinesOwnQueuesFollowTheEnginesInTheVulkanInfo() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = Sl.vulkanInfo(arena, 1L, 2L, 3L, 7, 1, 2);
            assertEquals(1, info.get(JAVA_INT, 64), "graphics queues start at 1");
            assertEquals(2, info.get(JAVA_INT, 56), "compute queues start at 2");
            assertEquals(7, info.get(JAVA_INT, 68), "graphics family");
            assertEquals(7, info.get(JAVA_INT, 60), "compute family");
        }
    }

    @Test
    void theModeFollowsTheRatioOfTheSizes() {
        assertEquals(6, DlssUpscaler.modeFor(1920, 1920), "DLAA");
        assertEquals(3, DlssUpscaler.modeFor(1280, 1920), "quality at two thirds");
        assertEquals(2, DlssUpscaler.modeFor(1114, 1920), "balanced at 58%");
        assertEquals(1, DlssUpscaler.modeFor(960, 1920), "performance at a half");
        assertEquals(4, DlssUpscaler.modeFor(640, 1920), "ultra performance at a third");
    }
}
