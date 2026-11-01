package me.piitex.engine.render3d.upscale;

/**
 * Turns the scene drawn at a lower resolution into a picture at the size it is shown at. A {@link
 * me.piitex.engine.render3d.scene.Scene3D} calls it once per drawn frame, after the scene and before the bloom, occlusion
 * and colour grade, so it works on linear light that can go far above white.
 * <p>
 * An upscaler is made by an {@link UpscalerProvider}, one for every place a scene is drawn (so a scene shown by two
 * viewports has two), and keeps whatever it needs between frames: the history a temporal one blends in. It runs on the
 * render thread with the window's graphics context current.
 *
 * @see Upscalers
 */
public interface Upscaler extends AutoCloseable {
    /**
     * A name for logs and settings screens.
     */
    String name();

    /**
     * True (the default) when the upscaler builds its picture from earlier frames. The scene then jitters the camera by a
     * fraction of a pixel every frame ({@link #jitterPhases}), draws the depth and the motion of every pixel for it
     * ({@link UpscaleInputs#depth()}, {@link UpscaleInputs#motion()}), and draws without multisampling, since the
     * upscaler anti-aliases by itself. False for a spatial upscaler, which gets the colour alone.
     */
    default boolean isTemporal() {
        return true;
    }

    /**
     * How many different jitter offsets the camera cycles through before repeating, for a temporal upscaler. The more
     * the picture is enlarged, the more samples a pixel needs; the default is the number AMD recommends for FidelityFX,
     * {@code 8 * (display / render)^2}, and at least 8.
     */
    default int jitterPhases(int renderWidth, int displayWidth) {
        return JitterSequence.phases(renderWidth, displayWidth);
    }

    /**
     * True when the upscaler writes {@link UpscaleInputs#output()} from compute shaders, as a storage image, instead of
     * drawing into it. The output is then made with {@link me.piitex.engine.render3d.GpuDevice#createStorageTarget} and
     * its previous contents do not matter. Only on Vulkan.
     */
    default boolean writesStorageImage() {
        return false;
    }

    /**
     * True when the upscaler can also clean up the noise of {@linkplain me.piitex.engine.render3d.scene.Scene3D#setPathTracing
     * path tracing} (a denoiser built into the upscaler, such as DLSS Ray Reconstruction). A scene that is path traced and asks for
     * it ({@link me.piitex.engine.render3d.scene.Scene3D#setRayReconstruction}) then skips its own filter, gives the upscaler the
     * noisy light of one path per pixel and the guide pictures that go with it ({@link UpscaleInputs#diffuseAlbedo()},
     * {@link UpscaleInputs#specularAlbedo()}, {@link UpscaleInputs#normalRoughness()}), and takes the clean, enlarged picture back.
     * Only a temporal upscaler can, and the answer may change from one frame to the next (the library may fail to start).
     */
    default boolean denoisesPathTracing() {
        return false;
    }

    /**
     * Records the work of one frame: reads {@link UpscaleInputs#color()} (and the depth and motion for a temporal
     * upscaler) and writes the picture into {@link UpscaleInputs#output()}, which has the display size and 16-bit floating
     * point colour.
     */
    void upscale(UpscaleInputs inputs);

    /**
     * Frees what the upscaler holds on the GPU.
     */
    @Override
    void close();
}
