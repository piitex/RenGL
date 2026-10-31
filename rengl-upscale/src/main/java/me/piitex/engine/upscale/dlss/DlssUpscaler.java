package me.piitex.engine.upscale.dlss;

import me.piitex.engine.render3d.upscale.UpscaleInputs;
import me.piitex.engine.render3d.upscale.Upscaler;
import me.piitex.engine.render3d.vk.VkGpuDevice;
import me.piitex.engine.render3d.vk.VkImageInfo;
import me.piitex.engine.upscale.UpscaleBarriers;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;

/**
 * NVIDIA DLSS super resolution on Vulkan, through Streamline ({@link Sl}).
 * <p>
 * The scene's colour, depth and motion are what DLSS reads, and it writes the display-size picture as a storage image
 * ({@link #writesStorageImage()}). Each frame the upscaler brings those images into the layouts DLSS expects, tells Streamline
 * the camera's constants and the options, tags the four images and lets it record its work into a command buffer of the device's
 * queue, then puts the images back.
 */
final class DlssUpscaler implements Upscaler {
    private static final AtomicInteger viewports = new AtomicInteger(1), frames = new AtomicInteger();

    // sl::DLSSMode
    private static final int MAX_PERFORMANCE = 1, BALANCED = 2, MAX_QUALITY = 3, ULTRA_PERFORMANCE = 4, DLAA = 6;

    private final VkGpuDevice device;
    private final Sl sl;
    private final int viewport = viewports.getAndIncrement();
    private MethodHandle setOptions;
    private int renderWidth, renderHeight, displayWidth, displayHeight;

    DlssUpscaler(VkGpuDevice device, Sl sl) {
        this.device = device;
        this.sl = sl;
        DlssProvider.attach(sl, device);
    }

    @Override
    public String name() {
        return "DLSS";
    }

    @Override
    public boolean writesStorageImage() {
        return true;
    }

    /** The mode DLSS is told it runs in, from the ratio of the sizes: it picks its model and tunes itself by it. */
    static int modeFor(int renderWidth, int displayWidth) {
        double ratio = (double) renderWidth / displayWidth;
        if (ratio >= 0.99) return DLAA;
        if (ratio >= 0.64) return MAX_QUALITY;
        if (ratio >= 0.55) return BALANCED;
        if (ratio >= 0.45) return MAX_PERFORMANCE;
        return ULTRA_PERFORMANCE;
    }

    /** An {@code sl::DLSSOptions} (version 3, 88 bytes). Colour is linear HDR and no exposure is given, so DLSS finds one itself. */
    static MemorySegment options(Arena arena, int mode, int outputWidth, int outputHeight) {
        MemorySegment o = Sl.struct(arena, 88, "6AC826E4-4C61-4101-A92D-638D421057B8", 3);
        o.set(JAVA_INT, 32, mode);
        o.set(JAVA_INT, 36, outputWidth);
        o.set(JAVA_INT, 40, outputHeight);
        o.set(JAVA_FLOAT, 44, 0f);   // sharpness
        o.set(JAVA_FLOAT, 48, 1f);   // preExposure
        o.set(JAVA_FLOAT, 52, 1f);   // exposureScale
        o.set(JAVA_BYTE, 56, Sl.TRUE); // colorBuffersHDR
        o.set(JAVA_BYTE, 84, Sl.TRUE); // useAutoExposure
        return o;
    }

    /**
     * An {@code sl::Constants} (version 2, 456 bytes) for a camera with the projection the 3D layer uses (depth 0 to 1, not
     * reversed). Matrices are row-major for row vectors and carry no jitter; the motion already includes the camera's own, so
     * the matrices that would reconstruct it are the identity.
     */
    static MemorySegment constants(Arena arena, UpscaleInputs in, boolean reset) {
        MemorySegment c = Sl.struct(arena, 456, "DCD35AD7-4E4A-4BAD-A90C-E0C49EB23AFE", 2);
        float aspect = (float) in.renderWidth() / in.renderHeight();
        float f = (float) (1.0 / Math.tan(in.fovYRadians() / 2.0));
        float a = f / aspect, n = in.near(), r = in.far();
        float cc = r / (n - r), d = n * r / (n - r);
        putMatrix(c, 32, a, 0, 0, 0, 0, f, 0, 0, 0, 0, cc, -1, 0, 0, d, 0);                  // cameraViewToClip
        putMatrix(c, 96, 1 / a, 0, 0, 0, 0, 1 / f, 0, 0, 0, 0, 0, 1 / d, 0, 0, -1, cc / d);  // clipToCameraView
        for (int i = 0; i < 16; i++) c.set(JAVA_FLOAT, 160 + 4L * i, Sl.INVALID_FLOAT);        // clipToLensClip: not given
        putMatrix(c, 224, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);                    // clipToPrevClip
        putMatrix(c, 288, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);                    // prevClipToClip
        c.set(JAVA_FLOAT, 352, in.jitterX());
        c.set(JAVA_FLOAT, 356, in.jitterY());
        c.set(JAVA_FLOAT, 360, 1f / in.renderWidth());   // mvecScale: motion is in render pixels, previous minus this position
        c.set(JAVA_FLOAT, 364, 1f / in.renderHeight());
        c.set(JAVA_FLOAT, 368, 0f);                      // cameraPinholeOffset: none
        c.set(JAVA_FLOAT, 372, 0f);
        putVector(c, 376, 0, 0, 0);   // cameraPos
        putVector(c, 388, 0, 1, 0);   // cameraUp
        putVector(c, 400, 1, 0, 0);   // cameraRight
        putVector(c, 412, 0, 0, -1);  // cameraFwd
        c.set(JAVA_FLOAT, 424, n);
        c.set(JAVA_FLOAT, 428, r);
        c.set(JAVA_FLOAT, 432, in.fovYRadians());
        c.set(JAVA_FLOAT, 436, aspect);
        c.set(JAVA_FLOAT, 440, Sl.INVALID_FLOAT);        // motionVectorsInvalidValue: only for motion without the camera's
        c.set(JAVA_BYTE, 444, Sl.FALSE);                 // depthInverted
        c.set(JAVA_BYTE, 445, Sl.TRUE);                  // cameraMotionIncluded
        c.set(JAVA_BYTE, 446, Sl.FALSE);                 // motionVectors3D
        c.set(JAVA_BYTE, 447, reset ? Sl.TRUE : Sl.FALSE);
        c.set(JAVA_BYTE, 448, Sl.FALSE);                 // orthographicProjection
        c.set(JAVA_BYTE, 449, Sl.FALSE);                 // motionVectorsDilated
        c.set(JAVA_BYTE, 450, Sl.FALSE);                 // motionVectorsJittered
        c.set(JAVA_FLOAT, 452, 40f);                     // minRelativeLinearDepthObjectSeparation
        return c;
    }

    private static void putMatrix(MemorySegment s, long offset, float... values) {
        for (int i = 0; i < 16; i++) s.set(JAVA_FLOAT, offset + 4L * i, values[i]);
    }

    private static void putVector(MemorySegment s, long offset, float x, float y, float z) {
        s.set(JAVA_FLOAT, offset, x);
        s.set(JAVA_FLOAT, offset + 4, y);
        s.set(JAVA_FLOAT, offset + 8, z);
    }

    private static void check(String call, int result) {
        if (result != Sl.OK) throw new IllegalStateException(call + " returned " + Sl.describe(result));
    }

    @Override
    public void upscale(UpscaleInputs in) {
        if (in.depth() == null || in.motion() == null) throw new IllegalStateException("DLSS needs depth and motion vectors");
        boolean resized = renderWidth != in.renderWidth() || renderHeight != in.renderHeight() || displayWidth != in.displayWidth() || displayHeight != in.displayHeight();
        if (resized && renderWidth != 0) {
            vkDeviceWaitIdle(device.vkDevice());
            try (Arena arena = Arena.ofConfined()) {
                check("slFreeResources", sl.freeResources(Sl.FEATURE_DLSS, Sl.viewport(arena, viewport)));
            }
        }
        renderWidth = in.renderWidth();
        renderHeight = in.renderHeight();
        displayWidth = in.displayWidth();
        displayHeight = in.displayHeight();
        if (setOptions == null) setOptions = sl.featureFunction(Sl.FEATURE_DLSS, "slDLSSSetOptions", 2);

        VkImageInfo color = device.imageOf(in.color()), depth = device.imageOf(in.depth()), motion = device.imageOf(in.motion()),
                output = device.imageOf(in.output().texture());
        boolean reset = in.reset() || resized;
        device.record(cmd -> {
            UpscaleBarriers.before(cmd, color, depth, motion, output);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment frame = sl.newFrameToken(arena, frames.getAndIncrement());
                MemorySegment handle = Sl.viewport(arena, viewport);
                check("slSetConstants", sl.setConstants(constants(arena, in, reset), frame, handle));
                try {
                    int result = (int) setOptions.invokeExact(handle, options(arena, modeFor(in.renderWidth(), in.displayWidth()),
                            in.displayWidth(), in.displayHeight()));
                    check("slDLSSSetOptions", result);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
                MemorySegment tags = arena.allocate(64 * 4, 8);
                MemorySegment.copy(Sl.tag(arena, Sl.resource(arena, color, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL), Sl.BUFFER_SCALING_INPUT_COLOR), 0, tags, 0, 64);
                MemorySegment.copy(Sl.tag(arena, Sl.resource(arena, output, VK_IMAGE_LAYOUT_GENERAL), Sl.BUFFER_SCALING_OUTPUT_COLOR), 0, tags, 64, 64);
                MemorySegment.copy(Sl.tag(arena, Sl.resource(arena, depth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL), Sl.BUFFER_DEPTH), 0, tags, 128, 64);
                MemorySegment.copy(Sl.tag(arena, Sl.resource(arena, motion, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL), Sl.BUFFER_MOTION_VECTORS), 0, tags, 192, 64);
                check("slSetTagForFrame", sl.setTagForFrame(frame, handle, tags, 4, cmd.address()));
                MemorySegment inputs = arena.allocate(8);
                inputs.set(ADDRESS, 0, handle);
                check("slEvaluateFeature", sl.evaluate(Sl.FEATURE_DLSS, frame, inputs, 1, cmd.address()));
            }
            UpscaleBarriers.after(cmd, depth, output);
        });
    }

    @Override
    public void close() {
        if (renderWidth == 0) return;
        vkDeviceWaitIdle(device.vkDevice());
        try (Arena arena = Arena.ofConfined()) {
            sl.freeResources(Sl.FEATURE_DLSS, Sl.viewport(arena, viewport));
        }
        renderWidth = 0;
    }
}
