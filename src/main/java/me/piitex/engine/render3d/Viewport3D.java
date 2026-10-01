package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.gl.renderer.GpuInterop;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.render3d.gl.GlRenderTarget;
import me.piitex.engine.render3d.vk.VkTarget;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.scroll.Scrollable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.skia.BackendRenderTarget;
import org.jetbrains.skia.ColorSpace;
import org.jetbrains.skia.DirectContext;
import org.jetbrains.skia.FramebufferFormat;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.Surface;
import org.jetbrains.skia.SurfaceColorFormat;
import org.jetbrains.skia.SurfaceOrigin;
import org.jetbrains.skia.SurfaceProps;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;

/**
 * A rectangle of the UI that shows 3D. Add it to a container like any other element, give it a {@link Content} that
 * draws the picture, and the rest of the UI draws around and over it:
 * <pre>
 * Viewport3D view = new Viewport3D(640, 360);
 * view.setContent(myScene);
 * container.addElement(view);
 * </pre>
 * It has a position, size, anchor, opacity, rounded corners and the other element features, and it takes the mouse and
 * keyboard through {@link InputListener}.
 * <p>
 * Each frame the content draws into an offscreen {@link RenderTarget} the size of the viewport in pixels, which the
 * UI layer then draws as an image. The target is made again when the viewport is resized, so the picture stays sharp;
 * {@link #setResolutionScale} trades sharpness for speed. The 3D device is made the first time a viewport draws and is
 * shared by every viewport in the window. A window with no viewport never starts it.
 * <p>
 * It needs a window that runs on OpenGL or Vulkan. On macOS that means asking for Vulkan with
 * {@code windowOptions.setRenderer(RenderAPI.VULKAN)}; a Metal window can't show it (the viewport logs that once and
 * draws nothing). A {@link me.piitex.engine.ui.Element#takeSnapshot snapshot} of the UI leaves the viewport out,
 * since a snapshot is drawn on the CPU.
 * <p>
 * The picture is opaque. Whatever alpha the content leaves in the target is drawn as premultiplied alpha.
 * <p>
 * Threading: the {@link Content} callbacks run on the render thread, with the window's graphics context current.
 */
public class Viewport3D extends Element implements Focusable, Draggable, Scrollable {
    private static final Logger log = LogManager.getLogger("Engine");

    /**
     * What a viewport shows. All methods run on the render thread.
     */
    public interface Content {
        /**
         * Called once, before the first {@link #render}, and again if the content is moved to another window's
         * viewport. Create buffers, textures and pipelines here.
         */
        default void init(GpuDevice device) {
        }

        /**
         * The viewport's target was (re)created at this size in pixels, before the first frame and after every resize.
         * The place to update a camera's aspect ratio.
         */
        default void resize(GpuDevice device, int width, int height) {
        }

        /**
         * Draws one frame into {@code target}: begin a pass with {@link GpuDevice#beginPass}, draw, and close it.
         * The device's frame is already begun and is ended after this returns. Dynamic buffers must be
         * {@link GpuBuffer#update updated} every frame; see {@link GpuDevice#createDynamicBuffer}.
         *
         * @param delta seconds since the previous frame
         */
        void render(GpuDevice device, RenderTarget target, float delta);

        /**
         * Called when the content is replaced, the viewport is closed or its window closes. Free what {@link #init}
         * made.
         */
        default void dispose(GpuDevice device) {
        }
    }

    /**
     * Mouse and keyboard input for a viewport. Positions are in points from the viewport's top-left corner, the same
     * space as {@link #getWidth()}. Every method does nothing by default. A {@link Content} that implements this is
     * used without a call to {@link #setInputListener}.
     * <p>
     * A press on the viewport starts a left-button drag and focuses it; a click elsewhere takes the focus away. Keys
     * go to a focused viewport and not to the game behind the UI. Only the left button drags: for the others, set an
     * {@link #onMouseClick} listener.
     */
    public interface InputListener {
        /**
         * The mouse moved over the viewport with no drag going on.
         */
        default void pointerMoved(double x, double y) {
        }

        /**
         * The mouse left the viewport.
         */
        default void pointerExited() {
        }

        default void dragStarted(double x, double y) {
        }

        /**
         * @param dx how far the mouse moved since the last call, in points
         */
        default void dragged(double x, double y, double dx, double dy) {
        }

        default void dragEnded(double x, double y) {
        }

        /**
         * The wheel or trackpad scrolled over the viewport. {@code y} is the usual zoom axis.
         */
        default void scrolled(double dx, double dy, double x, double y) {
        }

        /**
         * A key went down, repeated or came up while the viewport has focus. {@code action} is GLFW's
         * {@code GLFW_PRESS}, {@code GLFW_REPEAT} or {@code GLFW_RELEASE}.
         */
        default void keyPressed(int key, int action, int mods) {
        }

        default void charTyped(int codepoint) {
        }

        default void focusChanged(boolean focused) {
        }
    }

    private Content content;
    private InputListener input;
    private float resolutionScale = 1f;

    // Render thread
    private Content activeContent;
    private Gpu3D gpu;
    private RenderTarget target;
    private boolean failed;
    private boolean reportedUnsupported;
    private float delta;
    /**
     * Skia objects wrapping the target for the frame being drawn. Skia reads them when it flushes, at the end of the
     * frame, so they are closed the next time this viewport draws.
     */
    private final List<AutoCloseable> inFlight = new ArrayList<>();

    private boolean dragging;
    private boolean pointerInside;
    private double lastDragX, lastDragY, lastHoverX = Double.NaN, lastHoverY = Double.NaN;

    public Viewport3D(double width, double height) {
        setWidth(width);
        setHeight(height);
    }

    /**
     * What the viewport shows. Takes effect on the next frame; the previous content is disposed then. Clears an earlier
     * failure, so a content that threw can be replaced.
     */
    public void setContent(Content content) {
        this.content = content;
        this.failed = false;
    }

    public Content getContent() {
        return content;
    }

    /**
     * Where this viewport's input goes, or null (the default) for the {@link Content} if it implements
     * {@link InputListener}, and nobody otherwise.
     */
    public void setInputListener(InputListener input) {
        this.input = input;
    }

    private InputListener listener() {
        if (input != null) return input;
        return content instanceof InputListener l ? l : null;
    }

    /**
     * The picture's resolution relative to the viewport's size in pixels. 1 (the default) is one rendered pixel per
     * screen pixel; 0.5 renders a quarter as many and lets the UI layer scale it up. Clamped to 0.1 to 4.
     */
    public void setResolutionScale(float resolutionScale) {
        this.resolutionScale = Math.max(0.1f, Math.min(4f, resolutionScale));
    }

    public float getResolutionScale() {
        return resolutionScale;
    }

    /**
     * The target's size in pixels, or 0 before the first frame.
     */
    public int getPixelWidth() {
        return target == null ? 0 : target.width();
    }

    public int getPixelHeight() {
        return target == null ? 0 : target.height();
    }

    /**
     * The device the content draws with, or null before the first frame.
     */
    public GpuDevice getDevice() {
        return gpu == null ? null : gpu.device;
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        if (!isEnabled()) return;
        this.delta = delta;

        // Hover movement has no event of its own on an element, so it is read from the tracked mouse position.
        InputListener listener = listener();
        boolean over = isHovered() && !dragging && MouseTracker.isInWindow();
        if (over) {
            double x = localX(MouseTracker.getX()), y = localY(MouseTracker.getY());
            if (!pointerInside || x != lastHoverX || y != lastHoverY) {
                lastHoverX = x;
                lastHoverY = y;
                if (listener != null) listener.pointerMoved(x, y);
            }
            pointerInside = true;
        } else if (pointerInside && !dragging) {
            pointerInside = false;
            lastHoverX = lastHoverY = Double.NaN;
            if (listener != null) listener.pointerExited();
        }
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);
        closeInFlight();
        if (content == null || failed) return;

        if (!(renderer instanceof GpuInterop interop)) {
            if (!reportedUnsupported) {
                reportedUnsupported = true;
                log.warn("A Viewport3D can only draw in a window running on OpenGL or Vulkan (on macOS, RenderAPI.VULKAN), not on Metal or in a snapshot, so it draws nothing here.");
            }
            return;
        }

        float scale = interop.pixelScale() * resolutionScale;
        int width = Math.max(1, Math.round((float) getWidth() * scale));
        int height = Math.max(1, Math.round((float) getHeight() * scale));
        try {
            drawFrame(renderer, interop, width, height);
        } catch (RuntimeException e) {
            failed = true;
            log.error("The 3D viewport failed and is switched off; set a new content to try again", e);
        }
    }

    private void drawFrame(Renderer renderer, GpuInterop interop, int width, int height) {
        if (gpu == null) {
            gpu = Gpu3D.of(interop);
            gpu.attach(this);
        }
        GpuDevice device = gpu.device;

        boolean resized = false;
        if (activeContent != content) {
            if (activeContent != null) activeContent.dispose(device);
            activeContent = content;
            activeContent.init(device);
            resized = true;
        }
        if (target == null || target.width() != width || target.height() != height) {
            if (target != null) target.close();
            target = device.createRenderTarget(width, height, true);
            resized = true;
        }
        if (resized) activeContent.resize(device, width, height);

        device.beginFrame();
        try {
            activeContent.render(device, target, delta);
        } finally {
            device.endFrame();
        }

        Image image = wrap(interop, target);
        renderer.drawImage(image, (float) getX(), (float) getY(), (float) getWidth(), (float) getHeight(),
                getStyling().getCornerRadius(), 1f);
    }

    /**
     * Hands the target's GPU image to Skia: wraps it as a render target, takes a snapshot (a GPU copy Skia does when it
     * flushes), and returns that. OpenGL targets are stored bottom row first and Vulkan ones top row first, so each
     * is told to Skia with its own origin.
     */
    private Image wrap(GpuInterop interop, RenderTarget target) {
        DirectContext context = interop.skiaContext();
        BackendRenderTarget backend;
        SurfaceOrigin origin;
        if (interop.gpuApi() == RenderAPI.VULKAN) {
            VkTarget vk = (VkTarget) target;
            backend = BackendRenderTarget.Companion.makeVulkan(target.width(), target.height(), vk.image(),
                    VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, vk.format(), vk.imageUsage(), 1, 1);
            origin = SurfaceOrigin.TOP_LEFT;
        } else {
            // The pass changed OpenGL state behind Skia's back, so make it forget what it thinks is set.
            context.resetGLAll();
            backend = BackendRenderTarget.Companion.makeGL(target.width(), target.height(), 0, 0,
                    ((GlRenderTarget) target).framebufferId(), FramebufferFormat.GR_GL_RGBA8);
            origin = SurfaceOrigin.BOTTOM_LEFT;
        }
        inFlight.add(backend);
        Surface surface = Surface.Companion.makeFromBackendRenderTarget(context, backend, origin,
                SurfaceColorFormat.RGBA_8888, ColorSpace.Companion.getSRGB(), new SurfaceProps());
        if (surface == null) throw new IllegalStateException("Skia could not wrap the 3D target");
        inFlight.add(surface);
        Image image = surface.makeImageSnapshot();
        inFlight.add(image);
        return image;
    }

    private void closeInFlight() {
        for (int i = inFlight.size() - 1; i >= 0; i--) {
            try {
                inFlight.get(i).close();
            } catch (Exception e) {
                log.debug("Could not close a Skia object of a 3D viewport: {}", e.toString());
            }
        }
        inFlight.clear();
    }

    /**
     * Frees the viewport's GPU objects: the content (disposed) and the target. The viewport can draw again, which
     * starts them afresh. Render thread, between frames. This happens by itself when the window closes; call it when
     * the viewport is removed earlier.
     */
    public void close() {
        if (gpu == null) return;
        gpu.detach(this);
        releaseGpu(gpu.device);
    }

    void releaseGpu(GpuDevice device) {
        closeInFlight();
        if (activeContent != null) {
            try {
                activeContent.dispose(device);
            } finally {
                activeContent = null;
            }
        }
        if (target != null) {
            target.close();
            target = null;
        }
        gpu = null;
    }

    /* INPUT */

    private double localX(double windowX) {
        return toLocalX(windowX) - getX();
    }

    private double localY(double windowY) {
        return toLocalY(windowY) - getY();
    }

    @Override
    public void onDragStart(double x, double y) {
        dragging = true;
        lastDragX = localX(x);
        lastDragY = localY(y);
        InputListener listener = listener();
        if (listener != null) listener.dragStarted(lastDragX, lastDragY);
    }

    @Override
    public void onDragUpdate(double x, double y) {
        double lx = localX(x), ly = localY(y);
        InputListener listener = listener();
        if (listener != null) listener.dragged(lx, ly, lx - lastDragX, ly - lastDragY);
        lastDragX = lx;
        lastDragY = ly;
    }

    @Override
    public void onDragEnd(double x, double y) {
        dragging = false;
        InputListener listener = listener();
        if (listener != null) listener.dragEnded(localX(x), localY(y));
    }

    @Override
    public void onScroll(double deltaX, double deltaY) {
        InputListener listener = listener();
        if (listener != null) listener.scrolled(deltaX, deltaY, localX(MouseTracker.getX()), localY(MouseTracker.getY()));
    }

    @Override
    public boolean canScroll(double deltaX, double deltaY) {
        return listener() != null;
    }

    @Override
    public void onFocusGained() {
        InputListener listener = listener();
        if (listener != null) listener.focusChanged(true);
    }

    @Override
    public void onFocusLost() {
        InputListener listener = listener();
        if (listener != null) listener.focusChanged(false);
    }

    @Override
    public void onCharTyped(int codepoint) {
        InputListener listener = listener();
        if (listener != null) listener.charTyped(codepoint);
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        InputListener listener = listener();
        if (listener != null) listener.keyPressed(key, action, mods);
    }
}
