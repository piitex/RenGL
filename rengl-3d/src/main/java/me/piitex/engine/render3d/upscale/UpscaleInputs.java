package me.piitex.engine.render3d.upscale;

import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.GpuTexture;
import me.piitex.engine.render3d.RenderTarget;
import me.piitex.engine.render3d.math.Vec3;

/**
 * What an {@link Upscaler} is given for one frame.
 * <p>
 * Every texture is read in the layout the device keeps its images in: on Vulkan row 0 is the top of the picture and on
 * OpenGL it is the bottom ({@link #bottomLeftOrigin()}). Motion vectors and jitter are in pixels of that image, in the
 * directions described below, so a Vulkan upscaler can use them as they are.
 *
 * @param device          the device the textures belong to
 * @param color           the scene in linear light at the render size, 16-bit floating point, drawn with the camera moved
 *                        by {@code jitterX} and {@code jitterY} for a temporal upscaler
 * @param depth           the depth of the solid objects, drawn with the same jitter, at the render size, or null for a
 *                        spatial upscaler. Read the red channel: 0 at the near plane and 1 at the far plane, and a hair
 *                        under 1 where nothing was drawn (the sky)
 * @param motion          where each pixel was in the last frame, or null for a spatial upscaler: the red and green
 *                        channels are the previous position minus this one, in render pixels, with +x to the right and
 *                        +y toward higher rows of the image. Anything that moved by itself is in it, and the sky moves
 *                        with the camera's turning only. Skinned and blend-shaped meshes show the camera's motion and
 *                        not their own
 * @param output          where to write the picture: display size, 16-bit floating point, linear filtering. What it
 *                        holds after the call is what the rest of the post-processing reads
 * @param previousOutput  last frame's {@code output}, or null where there was none or the history is to be dropped
 *                        ({@code reset}); an upscaler that wants a history can use it as one. It is the {@code output} of
 *                        the frame before, so it is not valid after another call that changed the size
 * @param renderWidth     the size of {@code color}, {@code depth} and {@code motion}
 * @param displayWidth    the size of {@code output}
 * @param jitterX         the offset the camera was moved by this frame, in render pixels: +x to the right and +y
 *                        <em>down the picture</em>, in the range -0.5 to 0.5. A pixel of {@code color} holds the scene at
 *                        the pixel's centre minus this. Zero for a spatial upscaler. (The picture's down is toward lower
 *                        rows on OpenGL; see {@link #bottomLeftOrigin()}.)
 * @param near            the camera's near plane
 * @param far             the camera's far plane
 * @param fovYRadians     the camera's vertical field of view
 * @param cameraPosition  where the camera is, in world space
 * @param cameraRight     the camera's right, up and forward as unit vectors in world space (frame generation wants them)
 * @param cameraUp        the camera's up
 * @param cameraForward   the way the camera looks
 * @param deltaSeconds    the time since the last frame
 * @param frame           the number of frames drawn since the history was last dropped, so 0 on the first
 * @param reset           true when the history must be ignored: the first frame, a resize, a change of upscaler, or a
 *                        camera cut ({@link me.piitex.engine.render3d.scene.Scene3D#resetTemporalHistory()})
 * @param bottomLeftOrigin true on OpenGL, where row 0 of every image is the bottom of the picture
 * @param diffuseAlbedo   for an upscaler that also cleans up the noise of path tracing ({@link Upscaler#denoisesPathTracing}),
 *                        and only in a frame that was path traced for it: the colour the surfaces reflect diffusely (a metal
 *                        has none), linear, at the render size, drawn with the same jitter as {@code color}. The scene's
 *                        {@code color} then holds the light of one noisy path per pixel, with nothing filtered. Null otherwise
 * @param specularAlbedo  how much light the surfaces reflect as a mirror would, red, green and blue, at the render size; null
 *                        when {@code diffuseAlbedo} is
 * @param normalRoughness the surface normals in world space (red, green and blue, -1 to 1) and the roughness in alpha
 *                        (0 smooth, 1 rough), at the render size; null when {@code diffuseAlbedo} is
 * @param worldToView     the camera's view matrix in the order of {@link me.piitex.engine.render3d.math.Mat4#toArray()}, which is
 *                        a row-major matrix for row vectors as well; null when {@code diffuseAlbedo} is
 */
public record UpscaleInputs(GpuDevice device, GpuTexture color, GpuTexture depth, GpuTexture motion, RenderTarget output,
                            RenderTarget previousOutput, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY, float near, float far, float fovYRadians, Vec3 cameraPosition, Vec3 cameraRight,
                            Vec3 cameraUp, Vec3 cameraForward, float deltaSeconds, long frame, boolean reset, boolean bottomLeftOrigin,
                            GpuTexture diffuseAlbedo, GpuTexture specularAlbedo, GpuTexture normalRoughness, float[] worldToView) {
    /** The inputs of an upscaler that is not given the guides of a denoiser. */
    public UpscaleInputs(GpuDevice device, GpuTexture color, GpuTexture depth, GpuTexture motion, RenderTarget output,
                         RenderTarget previousOutput, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                         float jitterX, float jitterY, float near, float far, float fovYRadians, Vec3 cameraPosition, Vec3 cameraRight,
                         Vec3 cameraUp, Vec3 cameraForward, float deltaSeconds, long frame, boolean reset, boolean bottomLeftOrigin) {
        this(device, color, depth, motion, output, previousOutput, renderWidth, renderHeight, displayWidth, displayHeight, jitterX, jitterY,
                near, far, fovYRadians, cameraPosition, cameraRight, cameraUp, cameraForward, deltaSeconds, frame, reset, bottomLeftOrigin,
                null, null, null, null);
    }

    /** Whether this frame carries the guides of a denoiser. */
    public boolean hasGuides() {
        return diffuseAlbedo != null;
    }
}
