package me.piitex.engine.render3d.upscale;

import me.piitex.engine.render3d.BufferType;
import me.piitex.engine.render3d.GpuBuffer;
import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.Pipeline;
import me.piitex.engine.render3d.PipelineDesc;
import me.piitex.engine.render3d.RenderPass;
import me.piitex.engine.render3d.VertexFormat;
import me.piitex.engine.render3d.VertexLayout;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The upscaler that needs no library: temporal anti-aliasing that also enlarges. Each frame it rebuilds the display-size
 * picture from the render-size samples around each pixel (weighted by how close their sub-pixel position is, which the
 * camera's jitter has moved to a new place every frame), then blends in last frame's result, moved along the motion
 * vectors and clamped to the colours around the pixel so that what has changed does not leave a ghost behind.
 * <p>
 * It is the reference for what a vendor upscaler is given, and the fallback where none is available. It does not match
 * the quality of FSR, DLSS or XeSS: the history is resampled bilinearly, and the rejection of stale history is a simple
 * colour clamp.
 */
final class TemporalUpscaler implements Upscaler {
    static final UpscalerProvider PROVIDER = new UpscalerProvider() {
        @Override
        public String name() {
            return "Temporal";
        }

        @Override
        public boolean isAvailable(GpuDevice device) {
            return true;
        }

        @Override
        public Upscaler create(GpuDevice device) {
            return new TemporalUpscaler(device);
        }
    };

    private static final String VERTEX = """
            #version 450
            layout(location = 0) in vec2 aPos;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """;

    /**
     * a is the render size and one over it, b the display size and one over it, c the jitter in render pixels along the
     * image's own axes with 1 or -1 (whether the image's rows run down the picture) and whether to drop the history, and
     * d the least share of the new frame in the result, the most frames the history may be worth and how sharply a sample's
     * weight falls with its distance.
     * <p>
     * The history is kept in the output's alpha channel as the number of frames it holds, and the colour is blended after
     * a cheap invertible tone curve, so a bright pixel can not drown out the rest of the neighbourhood.
     */
    private static final String FRAGMENT = """
            #version 450
            layout(set = 0, binding = 0) uniform Params {
                vec4 a;
                vec4 b;
                vec4 c;
                vec4 d;
            };
            layout(set = 0, binding = 1) uniform sampler2D colorTex;
            layout(set = 0, binding = 2) uniform sampler2D depthTex;
            layout(set = 0, binding = 3) uniform sampler2D motionTex;
            layout(set = 0, binding = 4) uniform sampler2D historyTex;
            layout(location = 0) out vec4 outColor;

            // The history resampled with a cubic filter, which keeps detail that a plain bilinear fetch smears when the picture moves.
            vec4 historyAt(vec2 uv) {
                vec2 size = b.xy;
                vec2 samplePos = uv * size;
                vec2 base = floor(samplePos - 0.5) + 0.5;
                vec2 f = samplePos - base;
                vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
                vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
                vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
                vec2 w3 = f * f * (-0.5 + 0.5 * f);
                vec2 w12 = w1 + w2;
                vec2 p0 = (base - 1.0) / size, p3 = (base + 2.0) / size, p12 = (base + w2 / w12) / size;
                vec4 sum = texture(historyTex, vec2(p0.x, p0.y)) * w0.x * w0.y
                         + texture(historyTex, vec2(p12.x, p0.y)) * w12.x * w0.y
                         + texture(historyTex, vec2(p3.x, p0.y)) * w3.x * w0.y
                         + texture(historyTex, vec2(p0.x, p12.y)) * w0.x * w12.y
                         + texture(historyTex, vec2(p12.x, p12.y)) * w12.x * w12.y
                         + texture(historyTex, vec2(p3.x, p12.y)) * w3.x * w12.y
                         + texture(historyTex, vec2(p0.x, p3.y)) * w0.x * w3.y
                         + texture(historyTex, vec2(p12.x, p3.y)) * w12.x * w3.y
                         + texture(historyTex, vec2(p3.x, p3.y)) * w3.x * w3.y;
                return max(sum, vec4(0.0));
            }
            vec3 toWork(vec3 v) {
                return v / (1.0 + max(max(v.r, v.g), v.b));
            }
            vec3 fromWork(vec3 v) {
                return v / max(1.0 - max(max(v.r, v.g), v.b), 1e-4);
            }
            void main() {
                vec2 uv = gl_FragCoord.xy * b.zw;
                vec2 rp = uv * a.xy;
                ivec2 base = ivec2(floor(rp));
                ivec2 last = ivec2(a.xy) - 1;
                vec3 sum = vec3(0.0);
                float weights = 0.0;
                vec3 lo = vec3(1e9), hi = vec3(-1e9);
                float nearest = 2.0;
                ivec2 nearestAt = clamp(base, ivec2(0), last);
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        ivec2 q = base + ivec2(x, y);
                        ivec2 f = clamp(q, ivec2(0), last);
                        vec3 col = toWork(min(texelFetch(colorTex, f, 0).rgb, vec3(65000.0)));
                        // Where this sample was taken from, once the camera's jitter is taken out.
                        vec2 at = vec2(q) + 0.5 - c.xy;
                        vec2 off = at - rp;
                        float w = exp(-d.z * dot(off, off));
                        sum += col * w;
                        weights += w;
                        lo = min(lo, col);
                        hi = max(hi, col);
                        float depth = texelFetch(depthTex, f, 0).r;
                        if (depth < nearest) {
                            nearest = depth;
                            nearestAt = f;
                        }
                    }
                }
                vec3 current = sum / max(weights, 1e-5);
                vec2 motion = texelFetch(motionTex, nearestAt, 0).xy;
                vec2 prevUv = uv + motion * a.zw;
                bool valid = c.w < 0.5 && prevUv.x >= 0.0 && prevUv.x <= 1.0 && prevUv.y >= 0.0 && prevUv.y <= 1.0;
                if (!valid) {
                    outColor = vec4(fromWork(min(current, vec3(0.9999))), 1.0);
                    return;
                }
                vec4 history = historyAt(prevUv);
                vec3 past = toWork(min(history.rgb, vec3(65000.0)));
                vec3 slack = (hi - lo) * 0.25 + 0.002;
                vec3 kept = clamp(past, lo - slack, hi + slack);
                // How much of the history survived the clamp says how well it fits; what did not fit counts for less.
                float fit = 1.0 - clamp(length(past - kept) / (length(past) + 0.02) * 3.0, 0.0, 1.0);
                float frames = history.a * fit;
                float share = max(1.0 / (frames + 1.0), d.x);
                vec3 result = mix(kept, current, share);
                outColor = vec4(fromWork(min(result, vec3(0.9999))), min(frames + 1.0, d.y));
            }
            """;

    /** The least share of the new frame in the picture, the most frames the history can be worth, and how sharply a sample's weight falls with its distance in render pixels. */
    private static final float MIN_SHARE = 0.12f, MAX_FRAMES = 32f, KERNEL = 12f;

    private final GpuDevice device;
    private final Pipeline pipeline;
    private final GpuBuffer triangle, uniform;
    private final ByteBuffer scratch = MemoryUtil.memAlloc(64).order(ByteOrder.nativeOrder());

    private TemporalUpscaler(GpuDevice device) {
        this.device = device;
        pipeline = device.createPipeline(new PipelineDesc(VERTEX, FRAGMENT, VertexLayout.of(VertexFormat.FLOAT2), false, false, false));
        ByteBuffer data = MemoryUtil.memAlloc(6 * 4).order(ByteOrder.nativeOrder());
        data.asFloatBuffer().put(new float[]{-1, -1, 3, -1, -1, 3});
        triangle = device.createBuffer(BufferType.VERTEX, data);
        MemoryUtil.memFree(data);
        uniform = device.createDynamicBuffer(BufferType.UNIFORM, 64);
    }

    @Override
    public String name() {
        return "Temporal";
    }

    @Override
    public void upscale(UpscaleInputs in) {
        // The jitter is given down the picture; the image's rows run the other way on OpenGL.
        float rowSign = in.bottomLeftOrigin() ? -1f : 1f;
        boolean fresh = in.reset() || in.previousOutput() == null;
        scratch.clear();
        scratch.putFloat(in.renderWidth()).putFloat(in.renderHeight()).putFloat(1f / in.renderWidth()).putFloat(1f / in.renderHeight());
        scratch.putFloat(in.displayWidth()).putFloat(in.displayHeight()).putFloat(1f / in.displayWidth()).putFloat(1f / in.displayHeight());
        scratch.putFloat(in.jitterX()).putFloat(in.jitterY() * rowSign).putFloat(rowSign).putFloat(fresh ? 1f : 0f);
        scratch.putFloat(MIN_SHARE).putFloat(MAX_FRAMES).putFloat(KERNEL).putFloat(0f);
        scratch.flip();
        uniform.update(scratch);
        try (RenderPass pass = device.beginPass(in.output(), 0f, 0f, 0f, 1f)) {
            pass.bindPipeline(pipeline);
            pass.bindUniformBuffer(0, uniform);
            pass.bindTexture(1, in.color());
            pass.bindTexture(2, in.depth());
            pass.bindTexture(3, in.motion());
            // Nothing reads the history on a fresh start, but the shader declares it.
            pass.bindTexture(4, fresh ? in.color() : in.previousOutput().texture());
            pass.bindVertexBuffer(0, triangle);
            pass.draw(3);
        }
    }

    @Override
    public void close() {
        pipeline.close();
        triangle.close();
        uniform.close();
        MemoryUtil.memFree(scratch);
    }
}
