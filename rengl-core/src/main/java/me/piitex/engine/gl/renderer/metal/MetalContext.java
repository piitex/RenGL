package me.piitex.engine.gl.renderer.metal;

import me.piitex.engine.utils.flags.GeneratedClass;
import me.piitex.engine.utils.flags.Verified;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import static org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow;
import static org.lwjgl.system.JNI.invokeP;
import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.JNI.invokePPPP;
import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_getUid;

/**
 * Minimal Metal/Cocoa interop for a GLFW window on macOS.
 * <p>
 * Creates an MTLDevice + MTLCommandQueue (which Skiko's DirectContext.makeMetal needs)
 * and attaches a CAMetalLayer to the window's content view so we have something to
 * present into.
 * <p>
 * Only call the constructor on the main thread, as AppKit requires. nextDrawable() and
 * present() are safe from the render thread.
 * <p>
 * Note on the JNI helpers: every objc_msgSend call below goes through invokePPP /
 * invokePPPP, i.e. the "returns a pointer" variants, even for selectors that return
 * void. That works on both arm64 and x86-64, since the unused return value is simply
 * ignored, and it keeps this to two overloads instead of a dozen. Selectors that take a
 * float/double or a struct can't be called this way, so those use the Panama handles
 * at the bottom.
 */
@GeneratedClass(
        prompter = "piitex",
        model = "Opus 4.5",
        reviewed = true
)
@Verified(
        reviewer = "piitex",
        accepted = true
)
public class MetalContext implements AutoCloseable {

    private static final long objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

    // MTLPixelFormatBGRA8Unorm
    public static final long PIXEL_FORMAT_BGRA8_UNORM = 80L;

    private static final SharedLibrary METAL = APIUtil.apiCreateLibrary("/System/Library/Frameworks/Metal.framework/Metal");
    private static final SharedLibrary QUARTZ_CORE = APIUtil.apiCreateLibrary("/System/Library/Frameworks/QuartzCore.framework/QuartzCore");

    private final long device;
    private final long commandQueue;
    private final long layer;

    private long currentDrawable = 0L;
    private long currentPool = 0L;

    public MetalContext(long glfwWindow) {
        // id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        long createDevice = METAL.getFunctionAddress("MTLCreateSystemDefaultDevice");
        this.device = invokeP(createDevice);
        if (device == 0L) {
            throw new IllegalStateException("MTLCreateSystemDefaultDevice() returned nil - no Metal device available");
        }

        // id<MTLCommandQueue> queue = [device newCommandQueue];
        this.commandQueue = invokePPP(device, sel_getUid("newCommandQueue"), objc_msgSend);
        if (commandQueue == 0L) {
            throw new IllegalStateException("[device newCommandQueue] returned nil");
        }

        // CAMetalLayer *layer = [[CAMetalLayer layer] retain];
        long caMetalLayer = objc_getClass("CAMetalLayer");
        if (caMetalLayer == 0L) {
            throw new IllegalStateException("CAMetalLayer class not found (QuartzCore not loaded?) " + QUARTZ_CORE);
        }
        long l = invokePPP(caMetalLayer, sel_getUid("layer"), objc_msgSend); // autoreleased
        this.layer = invokePPP(l, sel_getUid("retain"), objc_msgSend);

        invokePPPP(layer, sel_getUid("setDevice:"), device, objc_msgSend);
        invokePPPP(layer, sel_getUid("setPixelFormat:"), PIXEL_FORMAT_BGRA8_UNORM, objc_msgSend);
        invokePPPP(layer, sel_getUid("setFramebufferOnly:"), 1L, objc_msgSend);
        // Present from the render thread without a CoreAnimation transaction.
        invokePPPP(layer, sel_getUid("setPresentsWithTransaction:"), 0L, objc_msgSend);

        // NSView *view = [nsWindow contentView];
        // view.layer = layer; view.wantsLayer = YES;   (order matters: layer-hosting)
        long nsWindow = glfwGetCocoaWindow(glfwWindow);
        if (nsWindow == 0L) {
            throw new IllegalStateException("glfwGetCocoaWindow() returned NULL");
        }
        long contentView = invokePPP(nsWindow, sel_getUid("contentView"), objc_msgSend);
        invokePPPP(contentView, sel_getUid("setLayer:"), layer, objc_msgSend);
        invokePPPP(contentView, sel_getUid("setWantsLayer:"), 1L, objc_msgSend);

        // Match the display's backing scale so we get a real Retina drawable.
        double backingScale = backingScaleFactor(nsWindow);
        setContentsScale(backingScale);
    }

    /**
     * Backing scale of the window's screen, e.g. 2.0 on Retina.
     */
    private double backingScaleFactor(long nsWindow) {
        try {
            return (double) MSG_SEND_RET_DOUBLE.invokeExact(
                    MemorySegment.ofAddress(nsWindow),
                    MemorySegment.ofAddress(sel_getUid("backingScaleFactor")));
        } catch (Throwable t) {
            return 1.0;
        }
    }

    /**
     * Turns vsync on or off by toggling the layer's {@code displaySyncEnabled}. With it off, presenting a
     * drawable no longer waits for the display's refresh, so the render loop has to pace itself.
     */
    public void setVsync(boolean vsync) {
        invokePPPP(layer, sel_getUid("setDisplaySyncEnabled:"), vsync ? 1L : 0L, objc_msgSend);
    }

    /**
     * Call from the resize / framebuffer-size callback (main thread).
     */
    public void resize(int framebufferWidth, int framebufferHeight) {
        setDrawableSize(framebufferWidth, framebufferHeight);
    }

    /**
     * Acquires the next drawable. Returns 0 if none is available (window occluded,
     * minimised, or the pool is exhausted), in which case the frame should be skipped.
     * Opens an autorelease pool that {@link #present()} or {@link #abandon()} closes.
     */
    public long nextDrawable() {
        long poolClass = objc_getClass("NSAutoreleasePool");
        long pool = invokePPP(poolClass, sel_getUid("alloc"), objc_msgSend);
        currentPool = invokePPP(pool, sel_getUid("init"), objc_msgSend);

        currentDrawable = invokePPP(layer, sel_getUid("nextDrawable"), objc_msgSend);
        if (currentDrawable == 0L) {
            abandon();
            return 0L;
        }
        return currentDrawable;
    }

    /**
     * id<MTLTexture> of the current drawable - this is what Skia wraps.
     */
    public long drawableTexture() {
        return invokePPP(currentDrawable, sel_getUid("texture"), objc_msgSend);
    }

    public int textureWidth(long texture) {
        return (int) invokePPP(texture, sel_getUid("width"), objc_msgSend);
    }

    public int textureHeight(long texture) {
        return (int) invokePPP(texture, sel_getUid("height"), objc_msgSend);
    }

    /**
     * Schedules the current drawable for presentation. Call AFTER Skia has flushed
     * and submitted its own work on the same queue.
     */
    public void present() {
        if (currentDrawable != 0L) {
            long commandBuffer = invokePPP(commandQueue, sel_getUid("commandBuffer"), objc_msgSend);
            invokePPPP(commandBuffer, sel_getUid("presentDrawable:"), currentDrawable, objc_msgSend);
            invokePPP(commandBuffer, sel_getUid("commit"), objc_msgSend);
            currentDrawable = 0L;
        }
        drainPool();
    }

    /**
     * Drop the frame without presenting.
     */
    public void abandon() {
        currentDrawable = 0L;
        drainPool();
    }

    private void drainPool() {
        if (currentPool != 0L) {
            invokePPP(currentPool, sel_getUid("drain"), objc_msgSend);
            currentPool = 0L;
        }
    }

    public long getDevice() {
        return device;
    }

    public long getCommandQueue() {
        return commandQueue;
    }

    public long getLayer() {
        return layer;
    }

    @Override
    public void close() {
        abandon();
        invokePPP(layer, sel_getUid("release"), objc_msgSend);
        invokePPP(commandQueue, sel_getUid("release"), objc_msgSend);
        invokePPP(device, sel_getUid("release"), objc_msgSend);
    }

    // ------------------------------------------------------------------
    // Selectors with float/struct arguments can't go through invokePPP,
    // so they use FFM downcalls with the correct ABI descriptors.
    // ------------------------------------------------------------------

    private static final Linker LINKER = Linker.nativeLinker();

    private static final StructLayout CG_SIZE = java.lang.foreign.MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE.withName("width"),
            ValueLayout.JAVA_DOUBLE.withName("height"));

    private static final MethodHandle MSG_SEND_DOUBLE_ARG = LINKER.downcallHandle(
            MemorySegment.ofAddress(objc_msgSend),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE));

    private static final MethodHandle MSG_SEND_RET_DOUBLE = LINKER.downcallHandle(
            MemorySegment.ofAddress(objc_msgSend),
            FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle MSG_SEND_CGSIZE_ARG = LINKER.downcallHandle(
            MemorySegment.ofAddress(objc_msgSend),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, CG_SIZE));

    private void setContentsScale(double scale) {
        try {
            MSG_SEND_DOUBLE_ARG.invokeExact(
                    MemorySegment.ofAddress(layer),
                    MemorySegment.ofAddress(sel_getUid("setContentsScale:")),
                    scale);
        } catch (Throwable t) {
            throw new IllegalStateException("setContentsScale: failed", t);
        }
    }

    private void setDrawableSize(double width, double height) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment size = arena.allocate(CG_SIZE);
            size.set(ValueLayout.JAVA_DOUBLE, 0, width);
            size.set(ValueLayout.JAVA_DOUBLE, 8, height);
            MSG_SEND_CGSIZE_ARG.invokeExact(
                    MemorySegment.ofAddress(layer),
                    MemorySegment.ofAddress(sel_getUid("setDrawableSize:")),
                    size);
        } catch (Throwable t) {
            throw new IllegalStateException("setDrawableSize: failed", t);
        }
    }
}