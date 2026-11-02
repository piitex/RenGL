package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.scene.Mesh;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Rays traced in shader code, with no ray queries ({@link GpuDevice#softwareRayTracing()}): the same picture as
 * {@link RayQueryGpuTest}, a fragment shader shooting a ray down the z axis from every pixel, but through the hierarchy the CPU
 * builds. Besides where a ray hits, the nearest hit is checked (green is the distance), through an instance that is scaled, one
 * that is turned, and one hidden behind another and listed before it.
 * <p>
 * It turns the software path on itself, so it runs on a GPU that has ray queries too, and skips only where the device is not
 * Vulkan 1.2.
 */
class SoftRayGpuTest {
    private static final int VIEW = 256;
    private static final float SPAN = 8f;
    private static String before;

    @BeforeAll
    static void forceSoftwareRays() {
        before = System.getProperty("rengl.3d.softrays");
        System.setProperty("rengl.3d.softrays", "true");
    }

    @AfterAll
    static void restore() {
        if (before == null) System.clearProperty("rengl.3d.softrays");
        else System.setProperty("rengl.3d.softrays", before);
    }

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
            #version 450
            """ + SoftRayShaders.declarations(0, 1) + """
            layout(location = 0) in vec2 uv;
            layout(location = 0) out vec4 outColor;
            void main() {
                vec3 origin = vec3((uv.x - 0.5) * %SPAN%, (uv.y - 0.5) * %SPAN%, 10.0);
                SoftHit hit;
                bool found = softTrace(origin, vec3(0.0, 0.0, -1.0), 0.001, 100.0, 0xFFu, false, hit);
                outColor = found ? vec4(1.0, hit.t / 20.0, 0.0, 1.0) : vec4(0.0, 0.0, 1.0, 1.0);
            }
            """.replace("%SPAN%", Float.toString(SPAN));

    private static float[] place(float x, float z, float scale, float turn) {
        float c = (float) Math.cos(turn) * scale, s = (float) Math.sin(turn) * scale;
        return new float[]{c, s, 0, 0, -s, c, 0, 0, 0, 0, scale, 0, x, 0, z, 1};
    }

    private static final class Content implements Viewport3D.Content {
        RayGeometry geometry;
        RayScene scene;
        Pipeline pipeline;
        GpuBuffer triangle;
        boolean supported;
        int frame;

        @Override
        public void init(GpuDevice device) {
            supported = device.softwareRayTracing();
            if (!supported) return;
            Mesh cube = Mesh.cube(1f);
            geometry = device.createRayGeometry(cube.vertices(), cube.vertexCount(), Mesh.FLOATS_PER_VERTEX, cube.indices());
            scene = device.createRayScene(8);
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
            // A unit cube that moves after the third frame, a diamond (a cube turned a quarter) with another cube hidden behind it
            // and listed before it, and a cube a half as big again.
            float moving = frame <= 3 ? -2f : -1.4f;
            scene.update(List.of(
                    new RayInstance(geometry, place(0f, -3f, 1f, 0f)),
                    new RayInstance(geometry, place(moving, 0f, 1f, 0f)),
                    new RayInstance(geometry, place(0f, 0f, 1f, (float) (Math.PI / 4))),
                    new RayInstance(geometry, place(2.8f, 0f, 1.5f, 0f))));
            try (RenderPass pass = device.beginPass(target, 0f, 0f, 0f, 1f)) {
                pass.bindPipeline(pipeline);
                pass.bindAccelerationStructure(0, scene);
                pass.bindRayGeometry(1, scene);
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
    void raysFindTheNearestThingWhereItStands() {
        Content content = new Content();
        GpuHarness.Run run = GpuHarness.run(RenderAPI.VULKAN, VIEW, viewport -> viewport.setContent(content), (device, target, recorder) -> {
            if (!device.softwareRayTracing()) return;
            ByteBuffer pixels = target.readPixels();
            try {
                recorder.check("the cube's old place is empty", isBlue(pixels, -2f), "x = -2 is " + describe(pixels, -2f));
                recorder.check("the cube was found where it moved to", isRed(pixels, -1.4f), "x = -1.4 is " + describe(pixels, -1.4f));
                recorder.check("beside the cube is empty", isBlue(pixels, -0.9f) && isBlue(pixels, -2.3f),
                        "x = -0.9 is " + describe(pixels, -0.9f) + ", x = -2.3 is " + describe(pixels, -2.3f));
                // The diamond reaches 0.7 on either side, a cube that was not turned only a half.
                recorder.check("the turned cube is found past the reach of an unturned one", isRed(pixels, 0.6f) && isRed(pixels, -0.6f),
                        "x = 0.6 is " + describe(pixels, 0.6f) + ", x = -0.6 is " + describe(pixels, -0.6f));
                recorder.check("the turned cube ends where a diamond does", isBlue(pixels, 0.85f), "x = 0.85 is " + describe(pixels, 0.85f));
                // The cube in front is 9.5 away (its face at z = 0.5), the one behind it 12.5.
                recorder.check("the nearer of two is the hit", distance(pixels, 0f) > 9.2f && distance(pixels, 0f) < 9.8f,
                        "distance at x = 0 is " + distance(pixels, 0f));
                recorder.check("the scaled cube spans a half as much again", isRed(pixels, 2.2f) && isRed(pixels, 3.4f) && isBlue(pixels, 1.9f) && isBlue(pixels, 3.7f),
                        "x = 1.9, 2.2, 3.4, 3.7 are " + describe(pixels, 1.9f) + describe(pixels, 2.2f) + describe(pixels, 3.4f) + describe(pixels, 3.7f));
                // A face of the scaled cube is 0.75 from its middle: 9.25 away.
                recorder.check("the scaled cube is nearer by its size", Math.abs(distance(pixels, 2.8f) - 9.25f) < 0.3f,
                        "distance at x = 2.8 is " + distance(pixels, 2.8f));
            } finally {
                MemoryUtil.memFree(pixels);
            }
        });
        assumeTrue(run.available() && run.api() == RenderAPI.VULKAN, run.reason() == null ? "the window did not run on Vulkan" : run.reason());
        assumeTrue(content.supported, "this device does not trace rays in shader code");
        run.results().forEach((name, failure) -> assertNull(failure, name + ": " + failure));
    }

    private static int at(float worldX) {
        int x = Math.round((worldX / SPAN + 0.5f) * VIEW), y = VIEW / 2;
        return (y * VIEW + x) * 4;
    }

    private static boolean isRed(ByteBuffer pixels, float worldX) {
        int i = at(worldX);
        return (pixels.get(i) & 255) > 200 && (pixels.get(i + 2) & 255) < 50;
    }

    private static boolean isBlue(ByteBuffer pixels, float worldX) {
        int i = at(worldX);
        return (pixels.get(i + 2) & 255) > 200 && (pixels.get(i) & 255) < 50;
    }

    /** The distance a hit was at, which the shader wrote in green as a twentieth. */
    private static float distance(ByteBuffer pixels, float worldX) {
        return (pixels.get(at(worldX) + 1) & 255) / 255f * 20f;
    }

    private static String describe(ByteBuffer pixels, float worldX) {
        int i = at(worldX);
        return "(" + (pixels.get(i) & 255) + ", " + (pixels.get(i + 1) & 255) + ", " + (pixels.get(i + 2) & 255) + ")";
    }
}
