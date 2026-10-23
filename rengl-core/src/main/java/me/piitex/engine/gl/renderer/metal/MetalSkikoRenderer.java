package me.piitex.engine.gl.renderer.metal;

import me.piitex.engine.gl.renderer.SkiaRenderer;
import me.piitex.engine.utils.flags.GeneratedFragments;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.skia.*;

/**
 * Skiko renderer backed by Metal via a CAMetalLayer attached to a GLFW window.
 * <p>
 * Key difference from the GL path: the render target is the drawable's texture, and
 * a new drawable is handed to you every frame. So the BackendRenderTarget and Surface
 * are rebuilt in begin() and destroyed in end(); they cannot be cached across frames.
 * <p>
 * Threading: construct MetalContext on the main thread, then call begin()/end() on the
 * render thread only.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class MetalSkikoRenderer extends SkiaRenderer {
    private static final Logger log = LogManager.getLogger("Engine");

    private final MetalContext metal;
    private DirectContext directContext;

    private BackendRenderTarget renderTarget;
    private Surface surface;

    /**
     * Logical (points) size the UI layer draws in.
     */
    private int logicalWidth;
    private int logicalHeight;
    private float scaleX = 1.0f;
    private float scaleY = 1.0f;

    public MetalSkikoRenderer(MetalContext metal) {
        this.metal = metal;
    }

    public void init() {
        log.info("Using Skiko Metal backend...");
        directContext = DirectContext.Companion.makeMetal(metal.getDevice(), metal.getCommandQueue());
    }

    public void onResize(int logicalWidth, int logicalHeight, int framebufferWidth, int framebufferHeight) {
        this.logicalWidth = Math.max(1, logicalWidth);
        this.logicalHeight = Math.max(1, logicalHeight);
        this.scaleX = (float) framebufferWidth / this.logicalWidth;
        this.scaleY = (float) framebufferHeight / this.logicalHeight;
    }

    @Override
    public void begin() {
        alphas.reset();
        frameValid = false;

        long drawable = metal.nextDrawable();
        if (drawable == 0L) {
            return;
        }

        long texture = metal.drawableTexture();
        int width = metal.textureWidth(texture);
        int height = metal.textureHeight(texture);

        renderTarget = BackendRenderTarget.Companion.makeMetal(width, height, texture);

        surface = Surface.Companion.makeFromBackendRenderTarget(
                directContext,
                renderTarget,
                SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.BGRA_8888,
                ColorSpace.Companion.getSRGB(),
                new SurfaceProps()
        );

        canvas = surface.getCanvas();
        frameValid = true;

        canvas.clear(argb(clearA, clearR, clearG, clearB));
        canvas.save();
        canvas.scale(scaleX, scaleY);
    }

    @Override
    public void end() {
        if (!frameValid) {
            metal.abandon();
            return;
        }

        canvas.restore();
        surface.flushAndSubmit();
        metal.present();
        surface.close();
        renderTarget.close();

        surface = null;
        renderTarget = null;
        canvas = null;
        frameValid = false;
    }


    /**
     * Frees the surface and the GPU context, waiting for work already sent to finish. Render thread, between frames.
     * The renderer can't be used afterwards; the {@link MetalContext} is left for its owner to close.
     */
    public void dispose() {
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
    }
}
