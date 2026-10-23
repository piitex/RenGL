package me.piitex.engine.gl.renderer.vulkan;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.gl.renderer.RenderBackend;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.vulkan.VK;

/**
 * The Vulkan {@link RenderBackend}. The engine finds it through {@link java.util.ServiceLoader} when this module is on
 * the classpath, and uses it for windows made with {@link RenderAPI#VULKAN}.
 * <p>
 * Nothing here touches Vulkan until the engine asks, so having the module on the classpath costs nothing for a program
 * that stays on OpenGL or Metal.
 */
public final class VulkanBackend implements RenderBackend {
    private static final Logger log = LogManager.getLogger("Engine");

    @Override
    public RenderAPI api() {
        return RenderAPI.VULKAN;
    }

    /**
     * Makes GLFW use the Vulkan library LWJGL loads (the bundled MoltenVK on macOS, the system loader elsewhere), so the
     * surface GLFW creates and the instance the engine creates come from the same driver. A failure only means Vulkan
     * isn't there, which {@link #unsupportedReason()} reports.
     */
    @Override
    public void beforeGlfwInit() {
        try {
            GLFWVulkan.glfwInitVulkanLoader(VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"));
        } catch (Throwable t) {
            log.debug("Could not hand GLFW the Vulkan loader: {}", t.toString());
        }
    }

    @Override
    public String unsupportedReason() {
        if (!VulkanSkikoRenderer.skikoHasVulkan()) {
            return "the Skiko on the classpath (" + VulkanSkikoRenderer.skikoVersion() + ") has no Vulkan support; "
                    + "it needs a build that includes JetBrains/skiko#1201 (0.999.0-SNAPSHOT)";
        }
        if (!VulkanContext.isSupported()) return "it isn't usable here (no Vulkan driver, or no GPU that can present to a window)";
        return null;
    }

    @Override
    public RenderBackend.Context createContext(long windowHandle) {
        return new VulkanContext(windowHandle);
    }
}
