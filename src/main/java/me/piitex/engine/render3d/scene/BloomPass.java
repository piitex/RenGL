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
 * The scene is drawn into an offscreen target of its own instead of the one the viewport shows, and then this takes the
 * picture through a few full-screen passes: keep only what is brighter than a threshold, blur that at half the size and
 * again at a quarter of it (a wide soft glow and a tight one), and draw the picture and both glows added together into the
 * viewport's target. The blurs are separable (a row pass, then a column pass), nine taps each. The glow is worked out in
 * linear light and stored with the gamma curve on, so the 8-bit targets keep their dark values.
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
            vec3 decode(vec3 c) { return pow(c, vec3(2.2)); }
            vec3 encode(vec3 c) { return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }
            """;

    /**
     * Keeps the part of each pixel above the threshold, with a soft shoulder so a pixel doesn't switch on at a hard edge:
     * a.z is the threshold, a.w the width of the shoulder.
     */
    private static final String BRIGHT = HEAD + """
            void main() {
                vec3 c = decode(texture(src, gl_FragCoord.xy * a.xy).rgb);
                float peak = max(c.r, max(c.g, c.b));
                float soft = clamp(peak - a.z + a.w, 0.0, 2.0 * a.w);
                soft = soft * soft / (4.0 * a.w + 1e-5);
                float keep = max(soft, peak - a.z) / max(peak, 1e-4);
                outColor = vec4(encode(c * keep), 1.0);
            }
            """;

    /**
     * One direction of a gaussian blur: b.xy is the step between taps, in texture coordinates, along that direction.
     */
    private static final String BLUR = HEAD + """
            void main() {
                vec2 uv = gl_FragCoord.xy * a.xy;
                vec3 sum = decode(texture(src, uv).rgb) * 0.227027;
                sum += (decode(texture(src, uv + b.xy).rgb) + decode(texture(src, uv - b.xy).rgb)) * 0.1945946;
                sum += (decode(texture(src, uv + b.xy * 2.0).rgb) + decode(texture(src, uv - b.xy * 2.0).rgb)) * 0.1216216;
                sum += (decode(texture(src, uv + b.xy * 3.0).rgb) + decode(texture(src, uv - b.xy * 3.0).rgb)) * 0.054054;
                sum += (decode(texture(src, uv + b.xy * 4.0).rgb) + decode(texture(src, uv - b.xy * 4.0).rgb)) * 0.016216;
                outColor = vec4(encode(sum), 1.0);
            }
            """;

    /**
     * The picture and the two glows added in linear light: a.z is the strength of the glow.
     */
    private static final String COMPOSITE = HEAD.replace("layout(location = 0) out", """
            layout(set = 0, binding = 2) uniform sampler2D wide;
            layout(set = 0, binding = 3) uniform sampler2D tight;
            layout(location = 0) out""") + """
            void main() {
                vec2 uv = gl_FragCoord.xy * a.xy;
                vec3 c = decode(texture(src, uv).rgb);
                vec3 glow = decode(texture(wide, uv).rgb) + decode(texture(tight, uv).rgb);
                outColor = vec4(encode(c + glow * a.z), 1.0);
            }
            """;

    private static final int UNIFORM_BYTES = 32;
    private static final int PASSES = 7;

    private final GpuDevice device;
    private final GpuBuffer triangle;
    private final Pipeline bright, blur, composite;
    private final GpuBuffer[] uniforms = new GpuBuffer[PASSES];
    private final ByteBuffer scratch = MemoryUtil.memAlloc(UNIFORM_BYTES).order(ByteOrder.nativeOrder());
    private RenderTarget scene, half1, half2, quarter1, quarter2;
    private int width, height;

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
     * The target the scene is drawn into, the size of the one it will be shown in. It has no multisampling: the picture is
     * read back by shaders, and an anti-aliased one would have to be resolved first. A viewport's resolution scale above 1
     * (see {@code Viewport3D#setResolutionScale}) smooths the edges again.
     */
    RenderTarget sceneTarget(int width, int height) {
        if (scene == null || this.width != width || this.height != height) {
            closeTargets();
            this.width = width;
            this.height = height;
            scene = device.createSampledTarget(width, height, true, true);
            int hw = Math.max(1, (width + 1) / 2), hh = Math.max(1, (height + 1) / 2);
            int qw = Math.max(1, (hw + 1) / 2), qh = Math.max(1, (hh + 1) / 2);
            half1 = device.createSampledTarget(hw, hh, false, true);
            half2 = device.createSampledTarget(hw, hh, false, true);
            quarter1 = device.createSampledTarget(qw, qh, false, true);
            quarter2 = device.createSampledTarget(qw, qh, false, true);
        }
        return scene;
    }

    /**
     * Turns the scene target's picture into the shown picture with its glow.
     *
     * @param threshold how bright, in linear light, a pixel has to be to glow (1 is white)
     * @param radius    how far the glow spreads, 1 for the usual
     */
    void apply(RenderTarget output, float strength, float threshold, float radius) {
        int pass = 0;
        float shoulder = 0.5f * Math.max(threshold, 0.05f);
        // Bright parts, at half size.
        fullScreen(half1, bright, uniforms[pass++], 1f / half1.width(), 1f / half1.height(), threshold, shoulder, 0f, 0f, scene.texture(), null, null);
        // A soft glow at half size, then a wide one at a quarter.
        float step = radius;
        fullScreen(half2, blur, uniforms[pass++], 1f / half2.width(), 1f / half2.height(), 0f, 0f, step / half1.width(), 0f, half1.texture(), null, null);
        fullScreen(half1, blur, uniforms[pass++], 1f / half1.width(), 1f / half1.height(), 0f, 0f, 0f, step / half2.height(), half2.texture(), null, null);
        fullScreen(quarter2, blur, uniforms[pass++], 1f / quarter2.width(), 1f / quarter2.height(), 0f, 0f, 2f * step / half1.width(), 0f, half1.texture(), null, null);
        fullScreen(quarter1, blur, uniforms[pass++], 1f / quarter1.width(), 1f / quarter1.height(), 0f, 0f, 0f, 2f * step / quarter2.height(), quarter2.texture(), null, null);
        // And the picture with both glows added.
        fullScreen(output, composite, uniforms[pass++], 1f / output.width(), 1f / output.height(), strength, 0f, 0f, 0f, scene.texture(), half1.texture(), quarter1.texture());
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
