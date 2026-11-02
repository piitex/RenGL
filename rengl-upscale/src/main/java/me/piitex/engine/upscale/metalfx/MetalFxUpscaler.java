package me.piitex.engine.upscale.metalfx;

import me.piitex.engine.render3d.upscale.UpscaleInputs;
import me.piitex.engine.render3d.upscale.Upscaler;
import me.piitex.engine.render3d.vk.VkGpuDevice;
import me.piitex.engine.render3d.vk.VkImageInfo;
import me.piitex.engine.upscale.UpscaleBarriers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;

/**
 * Apple MetalFX temporal upscaling on a Mac, between the Vulkan passes of the frame.
 * <p>
 * The Vulkan work before it is submitted by {@link VkGpuDevice#record} on its own, MoltenVK commits that to its Metal command
 * queue, and then a Metal command buffer of ours, made from the same queue, encodes the scaler and is committed: a queue runs its
 * command buffers in the order they were committed, so MetalFX sees the finished scene and the Vulkan work after it sees the
 * finished picture. The scaler reads the Metal textures behind the scene's colour, depth and motion images and writes the one
 * behind the output, a storage image ({@link #writesStorageImage()}).
 */
final class MetalFxUpscaler implements Upscaler {
    private static final Logger log = LogManager.getLogger("Engine");

    private final VkGpuDevice device;
    private final ObjC objc;
    private final MetalObjects metal;
    private final float motionSign, jitterXSign, jitterYSign;
    private long scaler;
    private int renderWidth, renderHeight, displayWidth, displayHeight;
    private long colorFormat, depthFormat, motionFormat, outputFormat;

    MetalFxUpscaler(VkGpuDevice device, ObjC objc) {
        this.device = device;
        this.objc = objc;
        this.metal = MetalObjects.of(device);
        String flip = "," + System.getProperty("rengl.metalfx.flip", "").toLowerCase().replace(" ", "") + ",";
        this.motionSign = flip.contains(",motion,") ? -1f : 1f;
        this.jitterXSign = flip.contains(",jitterx,") ? -1f : 1f;
        this.jitterYSign = flip.contains(",jittery,") ? -1f : 1f;
    }

    @Override
    public String name() {
        return "MetalFX";
    }

    @Override
    public boolean writesStorageImage() {
        return true;
    }

    @Override
    public void upscale(UpscaleInputs in) {
        if (in.depth() == null || in.motion() == null) throw new IllegalStateException("MetalFX needs depth and motion vectors");
        VkImageInfo color = device.imageOf(in.color()), depth = device.imageOf(in.depth()), motion = device.imageOf(in.motion()),
                output = device.imageOf(in.output().texture());
        // The scene's passes are submitted; what they wrote is made readable, and the output may be written.
        device.record(cmd -> UpscaleBarriers.before(cmd, color, depth, motion, output));
        long pool = objc.push();
        try {
            encode(in, color, depth, motion, output);
        } finally {
            objc.pop(pool);
        }
        device.record(cmd -> UpscaleBarriers.after(cmd, depth, output));
    }

    private void encode(UpscaleInputs in, VkImageInfo color, VkImageInfo depth, VkImageInfo motion, VkImageInfo output) {
        long[] textures = MetalObjects.textures(device, color, depth, motion, output);
        boolean fresh = ensureScaler(in, textures);
        objc.call(scaler, "setColorTexture:", textures[0]);
        objc.call(scaler, "setDepthTexture:", textures[1]);
        objc.call(scaler, "setMotionTexture:", textures[2]);
        objc.call(scaler, "setOutputTexture:", textures[3]);
        objc.call(scaler, "setJitterOffsetX:", in.jitterX() * jitterXSign);
        objc.call(scaler, "setJitterOffsetY:", in.jitterY() * jitterYSign);
        // The motion is in render pixels, as the scaler wants it, so only its sign is open.
        objc.call(scaler, "setMotionVectorScaleX:", motionSign);
        objc.call(scaler, "setMotionVectorScaleY:", motionSign);
        objc.call(scaler, "setReset:", fresh || in.reset());
        objc.call(scaler, "setDepthReversed:", false);
        long commands = objc.object(metal.queue(), "commandBuffer");
        if (commands == 0L) throw new IllegalStateException("MetalFX: the command queue gave no command buffer");
        objc.call(scaler, "encodeToCommandBuffer:", commands);
        objc.call(commands, "commit");
    }

    /**
     * Makes the scaler for these sizes and pixel formats, or makes it again when they changed. True when it is new, which means it
     * has no history.
     */
    private boolean ensureScaler(UpscaleInputs in, long[] textures) {
        long cf = objc.number(textures[0], "pixelFormat"), df = objc.number(textures[1], "pixelFormat"),
                mf = objc.number(textures[2], "pixelFormat"), of = objc.number(textures[3], "pixelFormat");
        if (scaler != 0L && renderWidth == in.renderWidth() && renderHeight == in.renderHeight() && displayWidth == in.displayWidth()
                && displayHeight == in.displayHeight() && colorFormat == cf && depthFormat == df && motionFormat == mf && outputFormat == of) {
            return false;
        }
        release();
        long descriptor = objc.object(objc.object(objc.cls("MTLFXTemporalScalerDescriptor"), "alloc"), "init");
        try {
            objc.call(descriptor, "setInputWidth:", (long) in.renderWidth());
            objc.call(descriptor, "setInputHeight:", (long) in.renderHeight());
            objc.call(descriptor, "setOutputWidth:", (long) in.displayWidth());
            objc.call(descriptor, "setOutputHeight:", (long) in.displayHeight());
            objc.call(descriptor, "setColorTextureFormat:", cf);
            objc.call(descriptor, "setDepthTextureFormat:", df);
            objc.call(descriptor, "setMotionTextureFormat:", mf);
            objc.call(descriptor, "setOutputTextureFormat:", of);
            // The scene is linear light that no exposure has been applied to.
            objc.call(descriptor, "setAutoExposureEnabled:", true);
            scaler = objc.object(descriptor, "newTemporalScalerWithDevice:", metal.device());
        } finally {
            objc.release(descriptor);
        }
        if (scaler == 0L) {
            throw new IllegalStateException("MetalFX would not make a temporal scaler from " + in.renderWidth() + "x" + in.renderHeight()
                    + " to " + in.displayWidth() + "x" + in.displayHeight() + " (pixel formats " + cf + ", " + df + ", " + mf + ", " + of + ")");
        }
        renderWidth = in.renderWidth();
        renderHeight = in.renderHeight();
        displayWidth = in.displayWidth();
        displayHeight = in.displayHeight();
        colorFormat = cf;
        depthFormat = df;
        motionFormat = mf;
        outputFormat = of;
        checkUsage("colour", textures[0], "colorTextureUsage");
        checkUsage("depth", textures[1], "depthTextureUsage");
        checkUsage("motion", textures[2], "motionTextureUsage");
        checkUsage("output", textures[3], "outputTextureUsage");
        return true;
    }

    /** Logs a texture MoltenVK made without a usage the scaler says it needs; the scaler may then draw nothing. */
    private void checkUsage(String what, long texture, String selector) {
        long needed = objc.number(scaler, selector);
        long has = objc.number(texture, "usage");
        if ((has & needed) != needed) {
            log.warn("MetalFX: the {} texture has Metal usage {} but the scaler needs {}", what, has, needed);
        }
    }

    private void release() {
        if (scaler == 0L) return;
        // Frames in flight may still be using it.
        vkDeviceWaitIdle(device.vkDevice());
        objc.release(scaler);
        scaler = 0L;
    }

    @Override
    public void close() {
        release();
    }
}
