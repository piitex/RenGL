package me.piitex.engine.upscale.metalfx;

import me.piitex.engine.render3d.upscale.UpscalerProvider;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What can be checked of MetalFX away from a Mac: that it is offered for discovery, that it asks for nothing and says why it is
 * unavailable elsewhere, and how it moves the render scale. The Metal side runs only on macOS and has to be tried there.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class MetalFxProviderTest {
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    @Test
    void theProviderIsListedForDiscovery() {
        boolean found = ServiceLoader.load(UpscalerProvider.class).stream().anyMatch(p -> p.type() == MetalFxProvider.class);
        assertTrue(found, "MetalFxProvider is missing from META-INF/services");
    }

    @Test
    void itIsNamedAndRanksWithTheOtherVendors() {
        MetalFxProvider provider = new MetalFxProvider();
        assertEquals("MetalFX", provider.name());
        assertEquals(100, provider.priority());
    }

    @Test
    void itTakesNoScaleBelowAThirdAndLeavesTheRestAlone() {
        MetalFxProvider provider = new MetalFxProvider();
        assertEquals(0.5f, provider.supportedRenderScale(0.5f, 1920, 1080));
        assertEquals(0.334f, provider.supportedRenderScale(0.25f, 1920, 1080));
    }

    @Test
    void offAMacItSaysSoAndAsksForNoExtension() {
        Assumptions.assumeFalse(MAC);
        String reason = MetalFxProvider.unavailableReason(null);
        assertNotNull(reason);
        assertTrue(reason.contains("macOS"), reason);
        assertFalse(new MetalFxProvider().isAvailable(null));
        assertTrue(new MetalFxExtensions().deviceExtensions().isEmpty());
        assertNull(ObjC.load());
    }

    @Test
    void onAMacItAsksForTheExtensionThatExportsMetalObjects() {
        Assumptions.assumeTrue(MAC);
        assertTrue(new MetalFxExtensions().deviceExtensions().contains("VK_EXT_metal_objects"));
    }
}
