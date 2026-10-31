package me.piitex.engine.upscale.fsr;

import me.piitex.engine.render3d.upscale.UpscaleInputs;
import me.piitex.engine.render3d.upscale.Upscaler;
import me.piitex.engine.render3d.vk.VkGpuDevice;
import me.piitex.engine.render3d.vk.VkImageInfo;
import me.piitex.engine.upscale.CStruct;
import me.piitex.engine.upscale.UpscaleBarriers;

import java.lang.foreign.Arena;

import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;


/**
 * AMD FidelityFX Super Resolution on Vulkan: FSR 4 where the GPU and the installed libraries have it, FSR 3 otherwise.
 * <p>
 * The scene's colour, depth and motion are what the SDK reads, and it writes the display-size picture as a storage image
 * ({@link #writesStorageImage()}). Each frame the upscaler brings those images into the layouts the SDK expects, lets it
 * record its dispatch into a command buffer of the device's queue, and puts them back.
 */
final class FsrUpscaler implements Upscaler {
    private final VkGpuDevice device;
    private final Ffx ffx;
    private Ffx.Context context;
    private int renderWidth, renderHeight, displayWidth, displayHeight;

    FsrUpscaler(VkGpuDevice device, Ffx ffx) {
        this.device = device;
        this.ffx = ffx;
    }

    @Override
    public String name() {
        return context == null ? "FSR" : context.versionName;
    }

    @Override
    public boolean writesStorageImage() {
        return true;
    }

    /**
     * Makes the SDK's context for these sizes, or makes it again when they changed. True when it is new, which means the SDK
     * has no history.
     */
    private boolean ensureContext(UpscaleInputs in) {
        if (context != null && renderWidth == in.renderWidth() && renderHeight == in.renderHeight()
                && displayWidth == in.displayWidth() && displayHeight == in.displayHeight()) {
            return false;
        }
        release();
        context = ffx.createContext(device, Ffx.ENABLE_HIGH_DYNAMIC_RANGE | Ffx.ENABLE_AUTO_EXPOSURE,
                in.renderWidth(), in.renderHeight(), in.displayWidth(), in.displayHeight(), null);
        renderWidth = in.renderWidth();
        renderHeight = in.renderHeight();
        displayWidth = in.displayWidth();
        displayHeight = in.displayHeight();
        return true;
    }

    private void release() {
        if (context == null) return;
        // The context's own images and pipelines may still be in use by frames in flight.
        vkDeviceWaitIdle(device.vkDevice());
        context.close();
        context = null;
    }

    @Override
    public void upscale(UpscaleInputs in) {
        if (in.depth() == null || in.motion() == null) throw new IllegalStateException("FSR needs depth and motion vectors");
        boolean fresh = ensureContext(in) || in.reset();
        VkImageInfo color = device.imageOf(in.color()), depth = device.imageOf(in.depth()), motion = device.imageOf(in.motion()),
                output = device.imageOf(in.output().texture());
        float milliseconds = in.deltaSeconds() > 0f ? in.deltaSeconds() * 1000f : 1000f / 60f;
        device.record(cmd -> {
            UpscaleBarriers.before(cmd, color, depth, motion, output);
            try (Arena arena = Arena.ofConfined()) {
                CStruct description = dispatchDescription(arena, cmd.address(), color, depth, motion, output, in.jitterX(), in.jitterY(),
                        in.renderWidth(), in.renderHeight(), in.displayWidth(), in.displayHeight(), milliseconds, fresh,
                        in.near(), in.far(), in.fovYRadians());
                int result = context.dispatch(description);
                if (result != 0) throw new IllegalStateException("ffxDispatch of the FSR upscaler returned " + result);
                // Frame generation reads the same depth and motion, while they are still in the layout the upscaler read them in.
                FsrFrameGeneration.prepare(in, cmd.address(), depth, motion, milliseconds, fresh);
            }
            UpscaleBarriers.after(cmd, depth, output);
        });
    }

    /**
     * The {@code ffxDispatchDescUpscale}. Motion is in render pixels, previous position minus this one, which is the
     * scale (1, 1); the jitter is the offset the camera was moved by, +y down the picture; depth is not reversed and has
     * a finite far plane; the colour is linear light that has not been given an exposure (the SDK finds one itself).
     */
    static CStruct dispatchDescription(Arena arena, long commandList, VkImageInfo color, VkImageInfo depth, VkImageInfo motion,
                                       VkImageInfo output, float jitterX, float jitterY, int renderWidth, int renderHeight,
                                       int displayWidth, int displayHeight, float deltaMilliseconds, boolean reset,
                                       float near, float far, float fovYRadians) {
        CStruct d = new CStruct(arena, 432).header(Ffx.DISPATCH_UPSCALE).ptr(commandList);
        Ffx.resource(d, color, Ffx.STATE_COMPUTE_READ);
        Ffx.resource(d, depth, Ffx.STATE_COMPUTE_READ);
        Ffx.resource(d, motion, Ffx.STATE_COMPUTE_READ);
        d.skip(3 * 48); // exposure, reactive mask, transparency and composition mask: not given
        Ffx.resource(d, output, Ffx.STATE_UNORDERED_ACCESS);
        return d.f32(jitterX).f32(jitterY)
                .f32(1f).f32(1f)
                .u32(renderWidth).u32(renderHeight)
                .u32(displayWidth).u32(displayHeight)
                .bool(false).f32(0f)
                .f32(deltaMilliseconds).f32(1f)
                .bool(reset)
                .f32(near).f32(far).f32(fovYRadians).f32(1f)
                .u32(0)
                .end();
    }


    @Override
    public void close() {
        release();
    }
}
