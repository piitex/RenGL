package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.BufferType;
import me.piitex.engine.render3d.GpuBuffer;
import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.Pipeline;
import me.piitex.engine.render3d.PipelineDesc;
import me.piitex.engine.render3d.RenderPass;
import me.piitex.engine.render3d.RenderTarget;
import me.piitex.engine.render3d.VertexFormat;
import me.piitex.engine.render3d.VertexLayout;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Bloom: light bleeding out of the brightest parts of the picture, as a bright lamp or a glint does in a camera.
 * <p>
 * The scene is drawn into an offscreen target of its own instead of the one the viewport shows, in linear light and in
 * 16-bit floating point, so a lamp ten times brighter than white is still ten times brighter when this reads it. The
 * target is multisampled like the viewport's and resolved when the scene is done. Then this takes the picture through a few
 * full-screen passes: keep only what is brighter than a threshold, blur that at half the size and again at a quarter of it
 * (a wide soft glow and a tight one), and draw the picture and both glows added together into the viewport's target, tone
 * mapped and gamma encoded there. The blurs are separable (a row pass, then a column pass), nine taps each.
 * <p>
 * One of these belongs to each render call of a scene (like the buffers the scene fills every frame), so a scene shown by
 * two viewports in a frame doesn't have one overwrite what the other is about to read.
 */
final class BloomPass {
    private static final String VERTEX = """
            #version 450
            layout(location = 0) in vec2 aPos;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """;

    private static final String HEAD = """
            #version 450
            layout(set = 0, binding = 0) uniform Post {
                vec4 a;
                vec4 b;
            };
            layout(set = 0, binding = 1) uniform sampler2D src;
            layout(location = 0) out vec4 outColor;
            """;

    /**
     * Keeps the part of each pixel above the threshold, with a soft shoulder so a pixel doesn't switch on at a hard edge:
     * a.z is the threshold, a.w the width of the shoulder.
     */
    private static final String BRIGHT = HEAD + """
            void main() {
                // Capped so one blinding pixel (a sun, a specular spike) doesn't flood the whole glow.
                vec3 c = min(texture(src, gl_FragCoord.xy * a.xy).rgb, vec3(64.0));
                float peak = max(c.r, max(c.g, c.b));
                float soft = clamp(peak - a.z + a.w, 0.0, 2.0 * a.w);
                soft = soft * soft / (4.0 * a.w + 1e-5);
                float keep = max(soft, peak - a.z) / max(peak, 1e-4);
                outColor = vec4(c * keep, 1.0);
            }
            """;

    /**
     * One direction of a gaussian blur: b.xy is the step between taps, in texture coordinates, along that direction.
     */
    private static final String BLUR = HEAD + """
            void main() {
                vec2 uv = gl_FragCoord.xy * a.xy;
                vec3 sum = texture(src, uv).rgb * 0.227027;
                sum += (texture(src, uv + b.xy).rgb + texture(src, uv - b.xy).rgb) * 0.1945946;
                sum += (texture(src, uv + b.xy * 2.0).rgb + texture(src, uv - b.xy * 2.0).rgb) * 0.1216216;
                sum += (texture(src, uv + b.xy * 3.0).rgb + texture(src, uv - b.xy * 3.0).rgb) * 0.054054;
                sum += (texture(src, uv + b.xy * 4.0).rgb + texture(src, uv - b.xy * 4.0).rgb) * 0.016216;
                outColor = vec4(sum, 1.0);
            }
            """;

    /**
     * The picture and the two glows added in linear light, then tone mapped like the scene shader does it (the exposure is
     * already in) and gamma encoded: a.z is the strength of the glow, a.w the tone mapper (see {@code Scene3D#setToneMapping}).
     */
    private static final String COMPOSITE = HEAD.replace("layout(location = 0) out", """
            layout(set = 0, binding = 2) uniform sampler2D wide;
            layout(set = 0, binding = 3) uniform sampler2D tight;
            layout(location = 0) out""") + SceneShaders.TONE_MAP + """
            void main() {
                vec2 uv = gl_FragCoord.xy * a.xy;
                vec3 c = texture(src, uv).rgb;
                vec3 glow = texture(wide, uv).rgb + texture(tight, uv).rgb;
                outColor = vec4(pow(max(toneMap(c + glow * a.z, int(a.w + 0.5)), vec3(0.0)), vec3(1.0 / 2.2)), 1.0);
            }
            """;

    private static final int UNIFORM_BYTES = 32;
    private static final int PASSES = 7;
    private static final float REFERENCE_HEIGHT = 1080f;

    private final GpuDevice device;
    private final GpuBuffer triangle;
    private final Pipeline bright, blur, composite;
    private final GpuBuffer[] uniforms = new GpuBuffer[PASSES];
    private final ByteBuffer scratch = MemoryUtil.memAlloc(UNIFORM_BYTES).order(ByteOrder.nativeOrder());
    private RenderTarget scene, half1, half2, quarter1, quarter2;
    private int width, height, samples;

    BloomPass(GpuDevice device, GpuBuffer fullScreenTriangle) {
        this.device = device;
        this.triangle = fullScreenTriangle;
        VertexLayout layout = VertexLayout.of(VertexFormat.FLOAT2);
        bright = device.createPipeline(new PipelineDesc(VERTEX, BRIGHT, layout, false, false, false));
        blur = device.createPipeline(new PipelineDesc(VERTEX, BLUR, layout, false, false, false));
        composite = device.createPipeline(new PipelineDesc(VERTEX, COMPOSITE, layout, false, false, false));
        for (int i = 0; i < PASSES; i++) uniforms[i] = device.createDynamicBuffer(BufferType.UNIFORM, UNIFORM_BYTES);
    }

    /**
     * The target the scene is drawn into, the size of the one it will be shown in, with floating point colour and the
     * same multisampling (the GPU's limit permitting). Its picture, resolved to one sample per pixel, is what the later
     * passes read.
     */
    RenderTarget sceneTarget(int width, int height, int samples) {
        if (scene == null || this.width != width || this.height != height || this.samples != samples) {
            closeTargets();
            this.width = width;
            this.height = height;
            this.samples = samples;
            scene = device.createSampledTarget(width, height, true, true, true, samples);
            int hw = Math.max(1, (width + 1) / 2), hh = Math.max(1, (height + 1) / 2);
            int qw = Math.max(1, (hw + 1) / 2), qh = Math.max(1, (hh + 1) / 2);
            half1 = device.createSampledTarget(hw, hh, false, true, true, 1);
            half2 = device.createSampledTarget(hw, hh, false, true, true, 1);
            quarter1 = device.createSampledTarget(qw, qh, false, true, true, 1);
            quarter2 = device.createSampledTarget(qw, qh, false, true, true, 1);
        }
        return scene;
    }

    /**
     * Turns the scene target's picture into the shown picture with its glow.
     *
     * @param threshold how bright, in linear light before tone mapping, a pixel has to be to glow (1 is white)
     * @param radius    how far the glow spreads, 1 for the usual (the same share of the picture at any resolution)
     * @param toneMap   the scene's tone mapper, by its number in the shader
     */
    void apply(RenderTarget output, float strength, float threshold, float radius, int toneMap) {
        int pass = 0;
        float shoulder = 0.5f * Math.max(threshold, 0.05f);
        // Bright parts, at half size.
        fullScreen(half1, bright, uniforms[pass++], 1f / half1.width(), 1f / half1.height(), threshold, shoulder, 0f, 0f, scene.texture(), null, null);
        // A soft glow at half size, then a wide one at a quarter.
        // The spread is a share of the picture's height, not a count of pixels: the same glow on a high-density display
        // as on a plain one, and in a small viewport as in a full-screen one. A 1080 pixel high picture has a step of one texel.
        float step = radius * Math.clamp(output.height() / REFERENCE_HEIGHT, 0.1f, 4f);
        fullScreen(half2, blur, uniforms[pass++], 1f / half2.width(), 1f / half2.height(), 0f, 0f, step / half1.width(), 0f, half1.texture(), null, null);
        fullScreen(half1, blur, uniforms[pass++], 1f / half1.width(), 1f / half1.height(), 0f, 0f, 0f, step / half2.height(), half2.texture(), null, null);
        fullScreen(quarter2, blur, uniforms[pass++], 1f / quarter2.width(), 1f / quarter2.height(), 0f, 0f, step / quarter2.width(), 0f, half1.texture(), null, null);
        fullScreen(quarter1, blur, uniforms[pass++], 1f / quarter1.width(), 1f / quarter1.height(), 0f, 0f, 0f, step / quarter2.height(), quarter2.texture(), null, null);
        // And the picture with both glows added.
        fullScreen(output, composite, uniforms[pass++], 1f / output.width(), 1f / output.height(), strength, toneMap, 0f, 0f, scene.texture(), half1.texture(), quarter1.texture());
    }

    private void fullScreen(RenderTarget into, Pipeline pipeline, GpuBuffer uniform, float invWidth, float invHeight, float c, float d,
                            float stepX, float stepY, me.piitex.engine.render3d.GpuTexture first, me.piitex.engine.render3d.GpuTexture second,
                            me.piitex.engine.render3d.GpuTexture third) {
        scratch.clear();
        scratch.putFloat(invWidth).putFloat(invHeight).putFloat(c).putFloat(d).putFloat(stepX).putFloat(stepY).putFloat(0f).putFloat(0f);
        scratch.flip();
        uniform.update(scratch);
        try (RenderPass pass = device.beginPass(into, 0f, 0f, 0f, 1f)) {
            pass.bindPipeline(pipeline);
            pass.bindUniformBuffer(0, uniform);
            pass.bindTexture(1, first);
            if (second != null) pass.bindTexture(2, second);
            if (third != null) pass.bindTexture(3, third);
            pass.bindVertexBuffer(0, triangle);
            pass.draw(3);
        }
    }

    private void closeTargets() {
        for (RenderTarget t : new RenderTarget[]{scene, half1, half2, quarter1, quarter2}) if (t != null) t.close();
        scene = half1 = half2 = quarter1 = quarter2 = null;
    }

    void close() {
        closeTargets();
        bright.close();
        blur.close();
        composite.close();
        for (GpuBuffer b : uniforms) b.close();
        MemoryUtil.memFree(scratch);
    }
}
