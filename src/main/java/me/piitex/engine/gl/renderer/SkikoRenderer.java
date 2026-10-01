package me.piitex.engine.gl.renderer;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.gl.renderer.vulkan.VulkanContext;
import me.piitex.engine.utils.flags.GeneratedFragments;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.skia.*;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.opengl.GL11.*;

@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class SkikoRenderer extends SkiaRenderer implements GpuInterop {
    private static final Logger log = LogManager.getLogger(SkikoRenderer.class);
    private DirectContext directContext;
    private BackendRenderTarget renderTarget;
    private Surface surface;

    private int logicalWidth;
    private int logicalHeight;
    private float scaleX = 1.0f;
    private float scaleY = 1.0f;
    private final java.util.List<Runnable> disposeListeners = new java.util.ArrayList<>();
    private boolean disposed;

    public SkikoRenderer(long windowHandle) {
        log.info("Using Skiko OpenGL backend...");
        directContext = DirectContext.Companion.makeGL();
        recreateSurface(windowHandle);
    }

    /**
     * Frees the surface and the GPU context. Render thread, with this renderer's OpenGL context current. The renderer
     * can't be used afterwards.
     */
    public void dispose() {
        disposed = true;
        runDisposeListeners();
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

    @Override
    public RenderAPI gpuApi() {
        return RenderAPI.OPENGL;
    }

    @Override
    public DirectContext skiaContext() {
        return directContext;
    }

    @Override
    public VulkanContext vulkanContext() {
        return null;
    }

    @Override
    public float pixelScale() {
        return scaleX;
    }

    @Override
    public void onDispose(Runnable cleanup) {
        if (disposed) cleanup.run();
        else disposeListeners.add(cleanup);
    }

    private void runDisposeListeners() {
        for (Runnable cleanup : disposeListeners) {
            try {
                cleanup.run();
            } catch (RuntimeException e) {
                log.error("A GPU cleanup failed while closing the window", e);
            }
        }
        disposeListeners.clear();
    }

    public void recreateSurface(long windowHandle) {
        if (surface != null) surface.close();
        if (renderTarget != null) renderTarget.close();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer fbw = stack.mallocInt(1);
            IntBuffer fbh = stack.mallocInt(1);

            glfwGetWindowSize(windowHandle, w, h);
            glfwGetFramebufferSize(windowHandle, fbw, fbh);

            this.logicalWidth = w.get(0);
            this.logicalHeight = h.get(0);
            int frameBufferWidth = fbw.get(0);
            int frameBufferHeight = fbh.get(0);

            this.scaleX = (float) frameBufferWidth / logicalWidth;
            this.scaleY = (float) frameBufferHeight / logicalHeight;

            // OpenGL targets physical pixels, not logical window size
            renderTarget = BackendRenderTarget.Companion.makeGL(
                    frameBufferWidth, frameBufferHeight, 0, 8, 0, FramebufferFormat.GR_GL_RGBA8
            );

            surface = Surface.Companion.makeFromBackendRenderTarget(
                    directContext,
                    renderTarget,
                    SurfaceOrigin.BOTTOM_LEFT,
                    SurfaceColorFormat.RGBA_8888,
                    ColorSpace.Companion.getSRGB(),
                    new SurfaceProps()
            );

            canvas = surface.getCanvas();
        }
    }

    @Override
    public void begin() {
        alphas.reset();
        frameValid = true; // the OpenGL surface lives across frames, so there is always something to draw to
        canvas.clear(argb(clearA, clearR, clearG, clearB));
        canvas.save();
        // Automatically scale Skia's grid to match the input coordinate space
        canvas.scale(scaleX, scaleY);
    }

    @Override
    public void end() {
        canvas.restore();
        directContext.flushAndSubmit(surface, false);
        frameValid = false;
    }

    @Override
    public void setViewport(int x, int y, int width, int height) {
        // Warning: This signature might need the window handle passed in the future to handle resize events properly
        glViewport(x, y, width, height);
    }
}
