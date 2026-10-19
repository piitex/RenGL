package me.piitex.engine.gl.renderer.vulkan;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.gl.renderer.GpuInterop;
import me.piitex.engine.gl.renderer.SkiaRenderer;
import me.piitex.engine.utils.flags.GeneratedFragments;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.skia.*;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;

/**
 * Skiko renderer backed by Vulkan, drawing into the swapchain images of a {@link VulkanContext}.
 * <p>
 * Like Metal, the render target is a different image every frame, so the BackendRenderTarget and Surface are rebuilt
 * in {@link #begin()} and closed in {@link #end()}. {@link VulkanContext#beginFrame()} leaves the image in
 * {@code COLOR_ATTACHMENT_OPTIMAL}, which is the layout the render target is created with, and Skia leaves it there
 * when it is done; {@link VulkanContext#endFrame()} then moves it to the presentation layout.
 * <p>
 * Threading: construct {@link VulkanContext} on the main thread, then call everything here on the render thread.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public class VulkanSkikoRenderer extends SkiaRenderer implements GpuInterop {
    private static final Logger log = LogManager.getLogger("Engine");

    private final VulkanContext vulkan;
    private DirectContext directContext;
    private BackendRenderTarget renderTarget;
    private Surface surface;
    private boolean imageAcquired;
    /** Skia read this frame back (the dump aid), which leaves the image as a transfer source. */
    private boolean readBack;
    private boolean reportedSurfaceFailure;
    private final java.util.List<Runnable> disposeListeners = new java.util.ArrayList<>();
    private boolean disposed;

    // Debug aid: -Drengl.vulkan.dump=frame.png writes one real frame, read back from the GPU, once DUMP_AFTER_FRAMES
    // have been drawn. It's how Vulkan output can be checked without a screen to look at.
    private static final String DUMP_PATH = System.getProperty("rengl.vulkan.dump");
    private static final int DUMP_AFTER_FRAMES = 90;
    private int framesDrawn;

    /**
     * Logical (points) size the UI layer draws in.
     */
    private int logicalWidth = 1;
    private int logicalHeight = 1;

    public VulkanSkikoRenderer(VulkanContext vulkan) {
        this.vulkan = vulkan;
    }

    /**
     * Whether the Skiko on the classpath was built with Vulkan support. Releases up to 0.150 have no
     * {@code DirectContext.makeVulkan}, and calling it there is a NoSuchMethodError, so this looks for it by name first.
     */
    public static boolean skikoHasVulkan() {
        try {
            for (java.lang.reflect.Method method : Class.forName("org.jetbrains.skia.DirectContext$Companion").getMethods()) {
                if (method.getName().equals("makeVulkan")) return true;
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
        }
        return false;
    }

    /**
     * The jar the Skia classes were loaded from, for the message when Vulkan isn't there.
     */
    public static String skikoVersion() {
        try {
            java.security.CodeSource source = Font.class.getProtectionDomain().getCodeSource();
            if (source != null) return java.nio.file.Path.of(source.getLocation().toURI()).getFileName().toString();
        } catch (Exception ignored) {
        }
        return "unknown version";
    }

    public void init() {
        log.info("Using Skiko Vulkan backend...");
        directContext = DirectContext.Companion.makeVulkan(
                vulkan.instance(),
                vulkan.physicalDevice(),
                vulkan.device(),
                vulkan.queue(),
                vulkan.queueFamily(),
                vulkan.instanceProcAddr(),
                vulkan.deviceProcAddr(),
                vulkan.apiVersion(),
                null
        );
    }

    @Override
    public RenderAPI gpuApi() {
        return RenderAPI.VULKAN;
    }

    @Override
    public DirectContext skiaContext() {
        return directContext;
    }

    @Override
    public VulkanContext vulkanContext() {
        return vulkan;
    }

    @Override
    public float pixelScale() {
        return (float) vulkan.width() / logicalWidth;
    }

    @Override
    public void onDispose(Runnable cleanup) {
        if (disposed) cleanup.run();
        else disposeListeners.add(cleanup);
    }

    /**
     * The window changed size. The swapchain is rebuilt on the next frame, and the canvas scale follows the new
     * image size then.
     */
    public void onResize(int logicalWidth, int logicalHeight) {
        this.logicalWidth = Math.max(1, logicalWidth);
        this.logicalHeight = Math.max(1, logicalHeight);
        vulkan.resize();
    }

    @Override
    public void begin() {
        frames++;
        alphas.reset();
        frameValid = false;
        imageAcquired = false;

        if (!vulkan.beginFrame()) return;
        imageAcquired = true;

        int width = vulkan.width();
        int height = vulkan.height();
        try {
            renderTarget = BackendRenderTarget.Companion.makeVulkan(
                    width, height,
                    vulkan.image(),
                    VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    vulkan.format(),
                    vulkan.imageUsage(),
                    1, 1
            );
            surface = Surface.Companion.makeFromBackendRenderTarget(
                    directContext,
                    renderTarget,
                    SurfaceOrigin.TOP_LEFT,
                    vulkan.isBgra() ? SurfaceColorFormat.BGRA_8888 : SurfaceColorFormat.RGBA_8888,
                    ColorSpace.Companion.getSRGB(),
                    new SurfaceProps()
            );
        } catch (RuntimeException e) {
            surface = null;
            if (renderTarget != null) renderTarget.close();
            renderTarget = null;
            if (!reportedSurfaceFailure) log.error("Skia could not wrap the Vulkan swapchain image: {}", e.toString());
            reportedSurfaceFailure = true;
        }
        if (surface == null) {
            // The image is still acquired, so end() presents it (blank) rather than leaking it.
            if (!reportedSurfaceFailure) log.error("Skia could not create a surface for the Vulkan swapchain image.");
            reportedSurfaceFailure = true;
            return;
        }

        canvas = surface.getCanvas();
        frameValid = true;

        canvas.clear(argb(clearA, clearR, clearG, clearB));
        canvas.save();
        canvas.scale((float) width / logicalWidth, (float) height / logicalHeight);
    }

    @Override
    public void end() {
        if (!imageAcquired) return; // begin() found nothing to draw to
        if (frameValid) {
            canvas.restore();
            directContext.flushAndSubmit(surface, false);
            if (DUMP_PATH != null && ++framesDrawn == DUMP_AFTER_FRAMES) {
                dumpFrame();
                readBack = true;
            }
        }
        vulkan.endFrame(readBack);
        readBack = false;

        if (surface != null) surface.close();
        if (renderTarget != null) renderTarget.close();
        surface = null;
        renderTarget = null;
        canvas = null;
        frameValid = false;
        imageAcquired = false;
        runFrameTasks();
    }

    private void dumpFrame() {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocPixels(ImageInfo.Companion.makeN32Premul(vulkan.width(), vulkan.height()));
            if (!surface.readPixels(bitmap, 0, 0)) {
                log.error("Could not read the Vulkan frame back for {}", DUMP_PATH);
                return;
            }
            try (Image image = Image.Companion.makeFromBitmap(bitmap);
                 Data png = image.encodeToData(EncodedImageFormat.PNG, 100, 6)) {
                java.nio.file.Files.write(java.nio.file.Path.of(DUMP_PATH), png.getBytes());
                log.info("Wrote Vulkan frame {} to {}", framesDrawn, DUMP_PATH);
            }
        } catch (Exception e) {
            log.error("Could not write the Vulkan frame to {}: {}", DUMP_PATH, e.toString());
        }
    }

    /**
     * Frees the Skia objects, waiting for work already sent to finish. Render thread, between frames. The renderer
     * can't be used afterwards; the {@link VulkanContext} is left for its owner to close, after this.
     */
    public void dispose() {
        vulkan.waitIdle();
        disposed = true;
        disposedRenderer = true;
        for (Runnable cleanup : disposeListeners) {
            try {
                cleanup.run();
            } catch (RuntimeException e) {
                log.error("A GPU cleanup failed while closing the window", e);
            }
        }
        disposeListeners.clear();
        if (surface != null) surface.close();
        if (renderTarget != null) renderTarget.close();
        if (directContext != null) {
            directContext.flush();
            directContext.submit(true);
            directContext.close();
        }
        surface = null;
        renderTarget = null;
        directContext = null;
        canvas = null;
    }
}
