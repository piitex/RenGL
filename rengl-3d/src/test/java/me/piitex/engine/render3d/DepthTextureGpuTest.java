package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A target made with {@link GpuDevice#createDepthSampledTarget} lets a later pass read the depth buffer, with the same
 * values on both APIs: a triangle drawn at depth 0.25 reads back as 0.25, and a nearer triangle drawn over a farther one
 * leaves the nearer one's depth. Skips where there is no GPU.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class DepthTextureGpuTest {
    private static final int SIZE = 32;

    private static final String VERTEX = """
            #version 450
            layout(location = 0) in vec3 aPos;
            void main() {
                gl_Position = vec4(aPos, 1.0);
            }
            """;

    private static final String FILL = """
            #version 450
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = vec4(1.0, 0.0, 0.0, 1.0);
            }
            """;

    private static final String READ_VERTEX = """
            #version 450
            layout(location = 0) in vec3 aPos;
            void main() {
                gl_Position = vec4(aPos.xy, 0.0, 1.0);
            }
            """;

    /**
     * The depth in the first two channels: the whole steps of 1/255, then what is left of the step, so 16 bits survive the
     * 8-bit target.
     */
    private static final String READ = """
            #version 450
            layout(set = 0, binding = 1) uniform sampler2D depthTex;
            layout(location = 0) out vec4 outColor;
            void main() {
                float d = texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r;
                float hi = floor(d * 255.0) / 255.0;
                outColor = vec4(hi, fract(d * 255.0), 0.0, 1.0);
            }
            """;

    private static GpuBuffer triangle(GpuDevice device, float z) {
        ByteBuffer data = MemoryUtil.memAlloc(9 * 4);
        data.asFloatBuffer().put(new float[]{-1, -1, z, 3, -1, z, -1, 3, z});
        GpuBuffer buffer = device.createBuffer(BufferType.VERTEX, data);
        MemoryUtil.memFree(data);
        return buffer;
    }

    private static void run(RenderAPI api) {
        String[] failure = new String[1];
        GpuHarness.Run run = GpuHarness.run(api, 64, (Consumer<Viewport3D>) viewport -> viewport.setContent(new Viewport3D.Content() {
            @Override
            public void render(GpuDevice device, RenderTarget target, float delta) {
                try (RenderPass pass = device.beginPass(target, 0f, 0f, 0f, 1f)) {
                }
            }
        }), (device, target, recorder) -> {
            if (device.api() != api) return;
            VertexLayout layout = VertexLayout.of(VertexFormat.FLOAT3);
            Pipeline fill = device.createPipeline(new PipelineDesc(VERTEX, FILL, layout, true, false, false));
            Pipeline read = device.createPipeline(new PipelineDesc(READ_VERTEX, READ, layout, false, false, false));
            GpuBuffer far = triangle(device, 0.5f), near = triangle(device, 0.25f);
            RenderTarget source = device.createDepthSampledTarget(SIZE, SIZE, false, false);
            RenderTarget copy = device.createSampledTarget(SIZE, SIZE, false, false);
            try {
                recorder.check("the target says its depth is readable", source.hasReadableDepth() && source.depthTexture() != null, "it does not");
                recorder.check("an ordinary target has no depth texture", !copy.hasReadableDepth(), "it claims one");
                // Two frames in a row, the second drawn in the opposite order, to see the target taken back for drawing.
                for (int frame = 0; frame < 2; frame++) {
                    try (RenderPass pass = device.beginPass(source, 0f, 0f, 0f, 1f)) {
                        pass.bindPipeline(fill);
                        pass.bindVertexBuffer(0, frame == 0 ? far : near);
                        pass.draw(3);
                        pass.bindVertexBuffer(0, frame == 0 ? near : far);
                        pass.draw(3);
                    }
                    try (RenderPass pass = device.beginPass(copy, 0f, 0f, 0f, 1f)) {
                        pass.bindPipeline(read);
                        pass.bindTexture(1, source.depthTexture());
                        pass.bindVertexBuffer(0, far);
                        pass.draw(3);
                    }
                    ByteBuffer pixels = copy.readPixels();
                    try {
                        int i = ((SIZE / 2) * SIZE + SIZE / 2) * 4;
                        float depth = ((pixels.get(i) & 255) + (pixels.get(i + 1) & 255) / 255f) / 255f;
                        recorder.check("frame " + frame + ": the nearer triangle's depth is what the depth texture holds",
                                Math.abs(depth - 0.25f) < 0.002f, "read " + depth + ", wanted 0.25");
                        // The triangle covers the whole target; the first pixel is as deep as the last.
                        int j = ((SIZE - 1) * SIZE + SIZE - 1) * 4;
                        float corner = ((pixels.get(j) & 255) + (pixels.get(j + 1) & 255) / 255f) / 255f;
                        recorder.check("frame " + frame + ": the same depth at the corner", Math.abs(corner - 0.25f) < 0.002f, "read " + corner);
                    } finally {
                        MemoryUtil.memFree(pixels);
                    }
                }
            } finally {
                source.close();
                copy.close();
                far.close();
                near.close();
                fill.close();
                read.close();
            }
        });
        assumeTrue(run.available() && run.api() == api, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        run.results().forEach((name, message) -> assertNull(message, name + ": " + message));
        assertNull(failure[0]);
    }

    @Test
    void vulkan() {
        run(RenderAPI.VULKAN);
    }

    @Test
    void openGl() {
        run(RenderAPI.OPENGL);
    }
}
