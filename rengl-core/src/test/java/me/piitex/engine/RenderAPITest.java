package me.piitex.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RenderAPITest {
    private static final boolean MAC = System.getProperty("os.name").toLowerCase().contains("mac");

    @Test
    void autoIsThePlatformDefault() {
        assertEquals(RenderAPI.platformDefault(), RenderAPI.resolve(RenderAPI.AUTO));
        assertEquals(RenderAPI.platformDefault(), RenderAPI.resolve(null));
    }

    @Test
    void vulkanIsAlwaysKept() {
        assertEquals(RenderAPI.VULKAN, RenderAPI.resolve(RenderAPI.VULKAN));
    }

    @Test
    void openGlOnMacIsAFatalErrorThatPointsToVulkan() {
        if (MAC) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> RenderAPI.resolve(RenderAPI.OPENGL));
            assertTrue(e.getMessage().contains("RenderAPI.VULKAN"), e.getMessage());
        } else {
            assertEquals(RenderAPI.OPENGL, RenderAPI.resolve(RenderAPI.OPENGL));
        }
    }

    @Test
    void metalOffMacFallsBackToTheDefault() {
        if (!MAC) assertEquals(RenderAPI.OPENGL, RenderAPI.resolve(RenderAPI.METAL));
        else assertEquals(RenderAPI.METAL, RenderAPI.resolve(RenderAPI.METAL));
    }
}
