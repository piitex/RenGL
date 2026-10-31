package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.scene.Mesh;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Ray queries on a real GPU: a cube is built into an acceleration structure, placed in a scene, and a fragment shader
 * shoots a ray down the z axis from every pixel and paints red where it hits and blue where it does not. The cube is then
 * moved by updating the scene, and the red must follow. Skips where the GPU has no ray queries (and on OpenGL, which has none).
 */
class RayQueryGpuTest {
    private static final int VIEW = 256;

    /** The orthographic view covers this many world units across. */
    private static final float SPAN = 8f;

    private static final String VERTEX = """
            #version 450
            layout(location = 0) in vec2 position;
            layout(location = 0) out vec2 uv;
            void main() {
                uv = position * 0.5 + 0.5;
                gl_Position = vec4(position, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 460
            #extension GL_EXT_ray_query : require
            layout(set = 0, binding = 0) uniform accelerationStructureEXT world;
            layout(location = 0) in vec2 uv;
            layout(location = 0) out vec4 outColor;
            void main() {
                vec3 origin = vec3((uv.x - 0.5) * %SPAN%, (uv.y - 0.5) * %SPAN%, 10.0);
                rayQueryEXT query;
                rayQueryInitializeEXT(query, world, gl_RayFlagsOpaqueEXT, 0xFF, origin, 0.001, vec3(0.0, 0.0, -1.0), 100.0);
                while (rayQueryProceedEXT(query)) { }
                bool hit = rayQueryGetIntersectionTypeEXT(query, true) != gl_RayQueryCommittedIntersectionNoneEXT;
                outColor = hit ? vec4(1.0, 0.0, 0.0, 1.0) : vec4(0.0, 0.0, 1.0, 1.0);
            }
            """.replace("%SPAN%", Float.toString(SPAN));

    private static final class Content implements Viewport3D.Content {
        RayGeometry geometry;
        RayScene scene;
        Pipeline pipeline;
        GpuBuffer triangle;
        boolean supported;
        int frame;

        @Override
        public void init(GpuDevice device) {
            supported = device.supportsRayTracing();
            if (!supported) return;
            Mesh cube = Mesh.cube(1f);
            geometry = device.createRayGeometry(cube.vertices(), cube.vertexCount(), Mesh.FLOATS_PER_VERTEX, cube.indices());
            scene = device.createRayScene(4);
            ByteBuffer data = MemoryUtil.memAlloc(24).order(ByteOrder.nativeOrder());
            data.asFloatBuffer().put(new float[]{-1, -1, 3, -1, -1, 3});
            triangle = device.createBuffer(BufferType.VERTEX, data);
            MemoryUtil.memFree(data);
            pipeline = device.createPipeline(new PipelineDesc(VERTEX, FRAGMENT, VertexLayout.of(VertexFormat.FLOAT2), false, false, false));
        }

        @Override
        public void resize(GpuDevice device, int width, int height) {
        }

        @Override
        public void render(GpuDevice device, RenderTarget target, float delta) {
            if (!supported) return;
            frame++;
            // In the middle for the first frames, then two units to the right.
            float x = frame <= 3 ? 0f : 2f;
            scene.update(List.of(new RayInstance(geometry, new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, 0, 0, 1})));
            try (RenderPass pass = device.beginPass(target, 0f, 0f, 0f, 1f)) {
                pass.bindPipeline(pipeline);
                pass.bindAccelerationStructure(0, scene);
                pass.bindVertexBuffer(0, triangle);
                pass.draw(3);
            }
        }

        @Override
        public void dispose(GpuDevice device) {
            if (!supported) return;
            if (pipeline != null) pipeline.close();
            if (triangle != null) triangle.close();
            if (scene != null) scene.close();
            if (geometry != null) geometry.close();
        }
    }

    @Test
    void aCubeIsHitWhereItStands() {
        Content content = new Content();
        GpuHarness.Run run = GpuHarness.run(RenderAPI.VULKAN, VIEW, viewport -> viewport.setContent(content), (device, target, recorder) -> {
            if (!device.supportsRayTracing()) return;
            ByteBuffer pixels = target.readPixels();
            try {
                // The view is SPAN units across: (x, 0) in the world is at pixel (x / SPAN + 0.5) * VIEW across, halfway down.
                recorder.check("ray query: the cube's old place is empty", isBlue(pixels, 0f), "pixel at x = 0 is " + describe(pixels, 0f));
                recorder.check("ray query: the cube was found where it moved to", isRed(pixels, 2f), "pixel at x = 2 is " + describe(pixels, 2f));
                recorder.check("ray query: beside the cube is empty", isBlue(pixels, 3.2f) && isBlue(pixels, 0.8f),
                        "x = 3.2 is " + describe(pixels, 3.2f) + ", x = 0.8 is " + describe(pixels, 0.8f));
            } finally {
                MemoryUtil.memFree(pixels);
            }
        });
        assumeTrue(run.available() && run.api() == RenderAPI.VULKAN, run.reason() == null ? "the window did not run on Vulkan" : run.reason());
        assumeTrue(content.supported, "this GPU has no ray queries");
        run.results().forEach((name, failure) -> assertNull(failure, name + ": " + failure));
    }

    private static int at(ByteBuffer pixels, float worldX) {
        int x = Math.round((worldX / SPAN + 0.5f) * VIEW), y = VIEW / 2;
        return (y * VIEW + x) * 4;
    }

    private static boolean isRed(ByteBuffer pixels, float worldX) {
        int i = at(pixels, worldX);
        return (pixels.get(i) & 255) > 200 && (pixels.get(i + 2) & 255) < 50;
    }

    private static boolean isBlue(ByteBuffer pixels, float worldX) {
        int i = at(pixels, worldX);
        return (pixels.get(i + 2) & 255) > 200 && (pixels.get(i) & 255) < 50;
    }

    private static String describe(ByteBuffer pixels, float worldX) {
        int i = at(pixels, worldX);
        return "(" + (pixels.get(i) & 255) + ", " + (pixels.get(i + 1) & 255) + ", " + (pixels.get(i + 2) & 255) + ")";
    }
}
