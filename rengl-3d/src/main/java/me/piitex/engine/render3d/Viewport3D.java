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
 * {@link #setResolutionScale} trades sharpness for speed, and {@link #setSamples} anti-aliases the edges (4 samples by default). The 3D device is made the first time a viewport draws and is
 * shared by every viewport in the window. A window with no viewport never starts it.
 * <p>
 * It needs a window that runs on OpenGL or Vulkan. On macOS that means asking for Vulkan with
 * {@code windowOptions.setRenderer(RenderAPI.VULKAN)}; a Metal window can't show it (the viewport logs that once and
 * draws nothing). A {@link me.piitex.engine.ui.Element#takeSnapshot snapshot} of the UI leaves the viewport out,
 * since a snapshot is drawn on the CPU.
 * <p>
 * By default the content draws every frame. For a picture that rarely changes, {@link #setContinuous
 * setContinuous(false)} draws it only when it is asked to ({@link #requestRender}), and otherwise shows the last
 * picture again at no cost. Input that reaches the viewport's {@link InputListener} counts as a request, so an orbiting
 * camera still redraws.
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
         * True while the content has work that needs frames even though nothing changed on screen, such as a
         * {@link me.piitex.engine.render3d.scene.Scene3D#preload preload} in progress. A viewport that is not
         * {@link #setContinuous continuous} draws a frame whenever this is true.
         */
        default boolean needsFrames() {
            return false;
        }

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
         * The left button went down and came up without the mouse moving more than {@link Viewport3D#CLICK_SLOP}
         * points, so it was a click and not a drag. It follows {@link #dragEnded}. The place to pick what is under the mouse.
         */
        default void clicked(double x, double y) {
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

    /**
     * How far the mouse may move, in points, between a press and its release for the pair to count as a click.
     */
    public static final double CLICK_SLOP = 4;

    private Content content;
    private InputListener input;
    private float resolutionScale = 1f;
    private volatile int samples = 4;
    private volatile boolean continuous = true;
    private volatile boolean renderRequested = true;

    // Render thread
    private Content activeContent;
    private Gpu3D gpu;
    private long lastDrawnFrame = -1;
    private RenderTarget target;
    /**
     * The picture of the last frame drawn, shown again by frames that don't redraw.
     */
    private Image picture;
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
    private double pressX, pressY;
    private boolean pressMoved;

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
        this.renderRequested = true;
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
     * Anti-aliasing: how many samples per pixel the content is drawn with. 4 is the default, 1 turns it off and 2, 4 and
     * 8 are the choices; the GPU's limit ({@link GpuDevice#maxSamples()}) applies, and another number is rounded down to
     * a power of two. More smooths jagged edges at the cost of memory and fill time, which grow with the number. The
     * content draws into a multisampled target and the result is resolved to an ordinary one, so a
     * {@link Content} needs no changes.
     */
    public void setSamples(int samples) {
        this.samples = Math.max(1, Math.min(16, samples));
        renderRequested = true;
    }

    public int getSamples() {
        return samples;
    }

    /**
     * True (the default) draws the content every frame. False draws it only after {@link #requestRender}, a resize, new
     * content, or input to the {@link InputListener}, and shows the last picture otherwise. Use false for a scene that
     * doesn't move by itself: a model viewer, a preview. A scene with an update handler that animates it needs true.
     */
    public void setContinuous(boolean continuous) {
        this.continuous = continuous;
        if (!continuous) renderRequested = true;
    }

    public boolean isContinuous() {
        return continuous;
    }

    /**
     * Asks for the content to be drawn on the next frame. Only matters when {@link #setContinuous continuous} is
     * false: call it after changing what the content shows. Safe from any thread.
     */
    public void requestRender() {
        renderRequested = true;
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
                if (listener != null) {
                    renderRequested = true;
                    listener.pointerMoved(x, y);
                }
            }
            pointerInside = true;
        } else if (pointerInside && !dragging) {
            pointerInside = false;
            lastHoverX = lastHoverY = Double.NaN;
            if (listener != null) {
                renderRequested = true;
                listener.pointerExited();
            }
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
        // A minimized window (or a layout with no room) leaves nothing to draw into. Drawing a 1 by 1 picture instead would
        // reach upscalers that refuse a size that small, and a failure switches the viewport off for good.
        float pixelScale = interop.pixelScale();
        if (Math.round((float) getWidth() * pixelScale) < 1 || Math.round((float) getHeight() * pixelScale) < 1) return;
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
        lastDrawnFrame = interop.frameNumber();
        GpuDevice device = prepare(interop, width, height);

        if (continuous || renderRequested || picture == null || activeContent.needsFrames()) {
            renderRequested = false;
            device.beginFrame();
            try {
                activeContent.render(device, target, delta);
            } finally {
                device.endFrame();
            }
            if (picture != null) picture.close();
            picture = null;
            picture = wrap(interop, target);
        }
        renderer.drawImage(picture, (float) getX(), (float) getY(), (float) getWidth(), (float) getHeight(),
                getStyling().getCornerRadius(), 1f);
    }

    /**
     * The window's 3D device, made the first time and shared by every viewport of the window.
     */
    private GpuDevice ensureDevice(GpuInterop interop) {
        if (gpu == null) {
            gpu = Gpu3D.of(interop);
            gpu.attach(this);
        }
        return gpu.device;
    }

    /**
     * Gets everything a frame needs ready: the window's device (made the first time), the content (initialised when it
     * is new) and a target of {@code width} x {@code height}, resizing the content when either changed.
     */
    private GpuDevice prepare(GpuInterop interop, int width, int height) {
        GpuDevice device = ensureDevice(interop);

        boolean resized = false;
        if (activeContent != content) {
            if (activeContent != null) activeContent.dispose(device);
            activeContent = content;
            activeContent.init(device);
            resized = true;
        }
        int wantedSamples = Math.min(Integer.highestOneBit(samples), device.maxSamples());
        if (target == null || target.width() != width || target.height() != height || target.samples() != wantedSamples) {
            if (target != null) target.close();
            target = device.createRenderTarget(width, height, true, wantedSamples);
            resized = true;
        }
        if (resized) {
            activeContent.resize(device, width, height);
            renderRequested = true;
        }
        return device;
    }

    /**
     * The most frames {@link #warmUp()} draws while the content still asks for more, so a content that always does cannot
     * hold the application back for ever.
     */
    private static final int WARM_UP_MAX_FRAMES = 600;

    /**
     * Work for {@link me.piitex.engine.WindowOptions#addWarmUp}: gets this viewport ready under the splash screen, so its
     * first frame in the application does not stall. It makes the window's 3D device, initialises the content and draws
     * the content a frame or more into the viewport's own target, which builds the content's pipelines and acceleration
     * structures and makes its {@linkplain me.piitex.engine.render3d.scene.Scene3D#setUpscaler upscaler} (loading a
     * vendor's library, for DLSS), and goes on while the content {@linkplain Content#needsFrames needs frames}, such as
     * a {@linkplain me.piitex.engine.render3d.scene.Scene3D#preload preload} in progress. The frames are not shown.
     * <pre>{@code
     * options.addWarmUp(viewport.warmUp());
     * }</pre>
     * Set the content before the engine starts, or there is nothing to warm. The viewport is drawn at the size it has
     * been laid out at, or the window's if it has none yet; if it turns out another size the content resizes, which is
     * cheap next to what was done. A failure switches the viewport off, as it would when drawn.
     */
    public me.piitex.engine.WarmUp warmUp() {
        return new me.piitex.engine.WarmUp() {
            /** 0 before anything, 1 once the device is made, 2 once the content and target are ready. */
            private int stage;
            private int frames;

            @Override
            public String getName() {
                return "3D scene";
            }

            @Override
            public boolean step(me.piitex.engine.Window window, Renderer renderer) {
                if (content == null || failed || !(renderer instanceof GpuInterop interop)) return true;
                try {
                    // The viewport may not be laid out yet, as the application is held back, so the window's size stands in.
                    double layoutWidth = getWidth() >= 1 ? getWidth() : window.getWindowOptions().getWidth();
                    double layoutHeight = getHeight() >= 1 ? getHeight() : window.getWindowOptions().getHeight();
                    float scale = interop.pixelScale() * resolutionScale;
                    int width = Math.max(1, Math.round((float) layoutWidth * scale));
                    int height = Math.max(1, Math.round((float) layoutHeight * scale));
                    // Each stage is a call of its own, so the splash is drawn between them and none of them is long.
                    if (stage == 0) {
                        ensureDevice(interop);
                        stage = 1;
                        return false;
                    }
                    GpuDevice device = Viewport3D.this.prepare(interop, width, height);
                    if (stage == 1) {
                        stage = 2;
                        return false;
                    }
                    device.beginFrame();
                    try {
                        activeContent.render(device, target, 0f);
                    } finally {
                        device.endFrame();
                    }
                    frames++;
                    return !activeContent.needsFrames() || frames >= WARM_UP_MAX_FRAMES;
                } catch (RuntimeException e) {
                    failed = true;
                    log.error("The 3D viewport failed while it was being warmed up and is switched off; set a new content to try again", e);
                    return true;
                }
            }
        };
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
        return surface.makeImageSnapshot();
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
     * Frees the GPU objects when the viewport is taken out of its container: once the frame that is in progress, or the
     * next one, has finished, unless it has been drawn again by then (put back in a container). So a viewport that is
     * removed does not hold its content and its picture until the window closes.
     */
    @Override
    public void onRemoved() {
        super.onRemoved();
        closeAfterFrame();
    }

    /**
     * Like {@link #close()}, when the frame that is in progress, or the next one, has finished, and not at all if the
     * viewport is drawn again in the meantime. Safe from any thread.
     */
    void closeAfterFrame() {
        Gpu3D owner = gpu;
        if (owner == null) return;
        long asked = owner.interop.frameNumber();
        owner.interop.afterFrame(() -> {
            if (gpu == owner && lastDrawnFrame <= asked) close();
        });
    }

    /**
     * Frees the viewport's GPU objects: the content (disposed) and the target. The viewport can draw again, which
     * starts them afresh. Render thread, between frames. This happens by itself when the window closes, and when the
     * viewport is removed from its container (see {@link #onRemoved()}); call it yourself to free them at another time.
     */
    public void close() {
        if (gpu == null) return;
        gpu.detach(this);
        releaseGpu(gpu.device);
    }

    void releaseGpu(GpuDevice device) {
        closeInFlight();
        if (picture != null) {
            picture.close();
            picture = null;
        }
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
        pressMoved = false;
        lastDragX = pressX = localX(x);
        lastDragY = pressY = localY(y);
        InputListener listener = listener();
        if (listener != null) {
            renderRequested = true;
            listener.dragStarted(lastDragX, lastDragY);
        }
    }

    @Override
    public void onDragUpdate(double x, double y) {
        double lx = localX(x), ly = localY(y);
        if (Math.hypot(lx - pressX, ly - pressY) > CLICK_SLOP) pressMoved = true;
        InputListener listener = listener();
        if (listener != null) {
            renderRequested = true;
            listener.dragged(lx, ly, lx - lastDragX, ly - lastDragY);
        }
        lastDragX = lx;
        lastDragY = ly;
    }

    @Override
    public void onDragEnd(double x, double y) {
        dragging = false;
        InputListener listener = listener();
        if (listener != null) {
            renderRequested = true;
            double lx = localX(x), ly = localY(y);
            listener.dragEnded(lx, ly);
            if (!pressMoved && Math.hypot(lx - pressX, ly - pressY) <= CLICK_SLOP) listener.clicked(lx, ly);
        }
    }

    @Override
    public void onScroll(double deltaX, double deltaY) {
        InputListener listener = listener();
        if (listener != null) {
            renderRequested = true;
            listener.scrolled(deltaX, deltaY, localX(MouseTracker.getX()), localY(MouseTracker.getY()));
        }
    }

    @Override
    public boolean canScroll(double deltaX, double deltaY) {
        return listener() != null;
    }

    @Override
    public void onFocusGained() {
        InputListener listener = listener();
        if (listener != null) {
            renderRequested = true;
            listener.focusChanged(true);
        }
    }

    @Override
    public void onFocusLost() {
        InputListener listener = listener();
        if (listener != null) {
            renderRequested = true;
            listener.focusChanged(false);
        }
    }

    @Override
    public void onCharTyped(int codepoint) {
        InputListener listener = listener();
        if (listener != null) {
            renderRequested = true;
            listener.charTyped(codepoint);
        }
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        InputListener listener = listener();
        if (listener != null) {
            renderRequested = true;
            listener.keyPressed(key, action, mods);
        }
    }
}
