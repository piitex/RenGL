package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Camera;
import me.piitex.engine.render3d.scene.Hit;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.TextureData;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Draws on a real GPU and reads the pictures back. Each test skips when the machine has no GPU for the API.
 * <p>
 * Vulkan runs everywhere there is a driver; OpenGL runs where the engine really uses it (Windows and Linux).
 */
class GpuSmokeTest {
    private static final int VIEW = 256;

    private static final String TRI_V = """
            #version 450
            layout(location = 0) in vec2 position;
            void main() { gl_Position = vec4(position, 0.0, 1.0); }
            """;
    private static final String TRI_F = """
            #version 450
            layout(location = 0) out vec4 outColor;
            void main() { outColor = vec4(1.0); }
            """;
    private static final String TEX_V = """
            #version 450
            layout(location = 0) in vec3 position;
            layout(location = 1) in vec2 uv;
            layout(set = 0, binding = 0) uniform G { mat4 mvp; };
            layout(location = 0) out vec2 vUv;
            void main() { gl_Position = mvp * vec4(position, 1.0); vUv = uv; }
            """;
    private static final String TEX_F = """
            #version 450
            layout(location = 0) in vec2 vUv;
            layout(set = 0, binding = 1) uniform sampler2D tex;
            layout(location = 0) out vec4 outColor;
            void main() { outColor = vec4(texture(tex, vUv).rgb, 1.0); }
            """;

    @TestFactory
    List<DynamicTest> vulkan() {
        return tests(RenderAPI.VULKAN);
    }

    @TestFactory
    List<DynamicTest> openGl() {
        // macOS windows have no OpenGL context, so the engine would hand this a Metal window and the harness would skip
        return tests(RenderAPI.OPENGL);
    }

    private List<DynamicTest> tests(RenderAPI api) {
        Scene3D scene = buildScene();
        GpuHarness.Run run = GpuHarness.run(api, VIEW, viewport -> viewport.setContent(new SceneContent(scene)),
                (device, target, recorder) -> {
                    if (device.api() != api) return;
                    deviceChecks(device, recorder);
                    sceneChecks(scene, target, recorder);
                });
        boolean usable = run.available() && run.api() == api;
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(DynamicTest.dynamicTest(api + ": a device exists", () -> {
            assumeTrue(usable, run.reason() == null ? "the window ran on " + run.api() : run.reason());
            assertNotNull(run.deviceName());
            assertFalse(run.results().isEmpty(), "the checks ran");
        }));
        if (usable) {
            run.results().forEach((name, failure) -> tests.add(DynamicTest.dynamicTest(api + ": " + name,
                    () -> assertNull(failure, failure))));
        }
        return tests;
    }

    // ---- the scene ---------------------------------------------------------------------------------------------

    private static Scene3D buildScene() {
        Scene3D scene = new Scene3D();
        Node3D floor = new Node3D("floor", Mesh.plane(40, 120), new Material(Color.WHITE).setTexture(
                TextureData.checker(256, 64, new float[]{1, 1, 1}, new float[]{0.1f, 0.1f, 0.1f})));
        floor.setPosition(0, -1, -50);
        scene.add(floor);
        Node3D red = new Node3D("red", Mesh.cube(1.4f), new Material(Color.RED));
        red.setPosition(-2.2f, 0.2f, 0);
        red.setRotation(Quat.fromAxisAngle(new Vec3(1, 1, 0).normalized(), 0.6f));
        Node3D green = new Node3D("green", Mesh.sphere(0.8f, 48, 24), new Material(Color.GREEN));
        green.setPosition(0, 0.2f, 0);
        Node3D blue = new Node3D("blue", Mesh.cube(1.4f), new Material(Color.BLUE));
        blue.setPosition(2.2f, 0.2f, 0);
        blue.setScale(new Vec3(-1, 1, 1)); // mirrored: must still draw solid, front faces out
        blue.setRotation(Quat.fromAxisAngle(new Vec3(0, 1, 1).normalized(), 0.5f));
        scene.add(red).add(green).add(blue);
        scene.camera().lookAt(new Vec3(0, 1.2f, 6), new Vec3(0, 0, 0));
        return scene;
    }

    private static final class SceneContent implements Viewport3D.Content {
        private final Scene3D scene;

        SceneContent(Scene3D scene) {
            this.scene = scene;
        }

        @Override
        public void init(GpuDevice device) {
            scene.init(device);
        }

        @Override
        public void resize(GpuDevice device, int width, int height) {
            scene.resize(device, width, height);
        }

        @Override
        public void render(GpuDevice device, RenderTarget target, float delta) {
            scene.render(device, target, delta);
        }

        @Override
        public void dispose(GpuDevice device) {
            scene.dispose(device);
        }
    }

    private void sceneChecks(Scene3D scene, RenderTarget target, GpuHarness.Recorder r) {
        r.guard("scene: pixels and picking agree", () -> {
            int w = target.width(), h = target.height();
            ByteBuffer px = target.readPixels();
            try {
                Camera camera = scene.camera();
                Mat4 viewProjection = camera.projection((float) w / h).mul(camera.view());
                for (String name : new String[]{"red", "green", "blue"}) {
                    Node3D node = scene.root().find(name);
                    Vec3 ndc = viewProjection.transformPoint(node.worldMatrix().transformPoint(Vec3.ZERO));
                    double sx = (ndc.x() * 0.5 + 0.5) * VIEW, sy = (1 - (ndc.y() * 0.5 + 0.5)) * VIEW;
                    Hit hit = scene.pick(sx, sy, VIEW, VIEW);
                    r.check("scene: picking " + name + " at its screen centre", hit != null && hit.node() == node, String.valueOf(hit));
                    int i = ((int) Math.round(sy / VIEW * h) * w + (int) Math.round(sx / VIEW * w)) * 4;
                    int red = px.get(i) & 255, green = px.get(i + 1) & 255, blue = px.get(i + 2) & 255;
                    boolean dominant = switch (name) {
                        case "red" -> red > green && red > blue;
                        case "green" -> green > red && green > blue;
                        default -> blue > red && blue > green;
                    };
                    r.check("scene: the " + name + " object is drawn solid there", dominant, "rgb=" + red + "," + green + "," + blue);
                }
                r.check("scene: empty sky picks nothing", scene.pick(VIEW / 2.0, 2, VIEW, VIEW) == null, "hit the sky");
                Hit floor = scene.pick(VIEW / 2.0, VIEW - 2, VIEW, VIEW);
                r.check("scene: the floor is picked at the bottom", floor != null && floor.node().getName().equals("floor")
                        && floor.normal().y() > 0.99f, String.valueOf(floor));
            } finally {
                MemoryUtil.memFree(px);
            }
        });
    }

    // ---- the device --------------------------------------------------------------------------------------------

    private void deviceChecks(GpuDevice device, GpuHarness.Recorder r) {
        r.guard("device: anti-aliasing", () -> msaaChecks(device, r));
        r.guard("device: mipmaps", () -> mipmapChecks(device, r));
    }

    private static ByteBuffer floats(float... v) {
        ByteBuffer b = MemoryUtil.memAlloc(v.length * 4).order(ByteOrder.nativeOrder());
        b.asFloatBuffer().put(v);
        return b;
    }

    private void msaaChecks(GpuDevice device, GpuHarness.Recorder r) {
        r.check("device: the sample limit is between 1 and 8", device.maxSamples() >= 1 && device.maxSamples() <= 8, "max=" + device.maxSamples());
        ByteBuffer tri = floats(-0.8f, -0.7f, 0.85f, -0.3f, -0.1f, 0.8f);
        GpuBuffer vertices = device.createBuffer(BufferType.VERTEX, tri);
        MemoryUtil.memFree(tri);
        Pipeline pipeline = device.createPipeline(PipelineDesc.of(TRI_V, TRI_F, VertexLayout.of(VertexFormat.FLOAT2))
                .withDepthTest(false).withCullBackFaces(false));
        try {
            for (int requested : new int[]{1, 2, 4, 8, 16}) {
                RenderTarget t = device.createRenderTarget(128, 128, true, requested);
                try {
                    try (RenderPass pass = device.beginPass(t, 0, 0, 0, 1)) {
                        pass.bindPipeline(pipeline);
                        pass.bindVertexBuffer(vertices);
                        pass.draw(3);
                    }
                    ByteBuffer px = t.readPixels();
                    int grays = 0, white = 0;
                    for (int i = 0; i < 128 * 128; i++) {
                        int red = px.get(i * 4) & 255;
                        if (red == 255) white++;
                        else if (red != 0) grays++;
                    }
                    MemoryUtil.memFree(px);
                    String label = "device: " + requested + " samples requested";
                    r.check(label + " gives a power of two within the limit", t.samples() >= 1 && t.samples() <= device.maxSamples()
                            && Integer.bitCount(t.samples()) == 1, "samples=" + t.samples());
                    if (t.samples() == 1) r.check(label + ": no gray edge pixels", grays == 0, "grays=" + grays);
                    else r.check(label + ": gray edge pixels appear", grays > 40, "grays=" + grays);
                    r.check(label + ": the interior is filled", white > 3000, "white=" + white);
                } finally {
                    t.close();
                }
            }
        } finally {
            vertices.close();
            pipeline.close();
        }
    }

    private void mipmapChecks(GpuDevice device, GpuHarness.Recorder r) {
        int n = 64;
        ByteBuffer pixels = MemoryUtil.memAlloc(n * n * 4);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                byte v = (byte) (((x + y) & 1) == 0 ? 255 : 0);
                pixels.put(v).put(v).put(v).put((byte) 255);
            }
        }
        pixels.flip();
        GpuTexture plain = device.createTexture(n, n, pixels);
        GpuTexture mipped = device.createTexture(n, n, pixels, true);
        MemoryUtil.memFree(pixels);
        r.check("device: a plain texture has 1 level", plain.mipLevels() == 1, "levels=" + plain.mipLevels());
        r.check("device: a mipmapped 64x64 texture has 7 levels", mipped.mipLevels() == 7, "levels=" + mipped.mipLevels());

        float far = 200f;
        ByteBuffer quad = floats(-50, 0, 0, 0, 0, 50, 0, 0, 100, 0, 50, 0, -far, 100, far, -50, 0, -far, 0, far);
        ByteBuffer indices = MemoryUtil.memAlloc(24).order(ByteOrder.nativeOrder());
        indices.asIntBuffer().put(new int[]{0, 1, 2, 0, 2, 3});
        GpuBuffer vertexBuffer = device.createBuffer(BufferType.VERTEX, quad);
        GpuBuffer indexBuffer = device.createBuffer(BufferType.INDEX, indices);
        MemoryUtil.memFree(quad);
        MemoryUtil.memFree(indices);
        Mat4 mvp = Mat4.perspective((float) Math.toRadians(60), 1f, 0.1f, 300f)
                .mul(Mat4.lookAt(new Vec3(0, 1.2f, 2), new Vec3(0, 0, -60), Vec3.UP));
        ByteBuffer matrix = MemoryUtil.memAlloc(64).order(ByteOrder.nativeOrder());
        mvp.put(matrix);
        matrix.flip();
        GpuBuffer uniform = device.createDynamicBuffer(BufferType.UNIFORM, 64);
        uniform.update(matrix);
        MemoryUtil.memFree(matrix);
        Pipeline pipeline = device.createPipeline(PipelineDesc.of(TEX_V, TEX_F,
                VertexLayout.of(VertexFormat.FLOAT3, VertexFormat.FLOAT2)).withCullBackFaces(false));
        double[] spread = new double[2];
        int k = 0;
        try {
            for (GpuTexture texture : new GpuTexture[]{plain, mipped}) {
                RenderTarget target = device.createRenderTarget(256, 256, true);
                try (RenderPass pass = device.beginPass(target, 0, 0, 0, 1)) {
                    pass.bindPipeline(pipeline);
                    pass.bindUniformBuffer(0, uniform);
                    pass.bindTexture(1, texture);
                    pass.bindVertexBuffer(vertexBuffer);
                    pass.bindIndexBuffer(indexBuffer);
                    pass.drawIndexed(6);
                }
                ByteBuffer px = target.readPixels();
                double sum = 0, sum2 = 0;
                int count = 0;
                for (int y = 135; y < 165; y++) {
                    for (int x = 96; x < 160; x++) {
                        int v = px.get((y * 256 + x) * 4) & 255;
                        sum += v;
                        sum2 += (double) v * v;
                        count++;
                    }
                }
                spread[k++] = Math.sqrt(Math.max(0, sum2 / count - (sum / count) * (sum / count)));
                MemoryUtil.memFree(px);
                target.close();
            }
        } finally {
            pipeline.close();
            uniform.close();
            vertexBuffer.close();
            indexBuffer.close();
            plain.close();
            mipped.close();
        }
        r.check("device: without mipmaps the distant checker is noisy", spread[0] > 40, "stddev=" + spread[0]);
        r.check("device: with mipmaps the distant checker is smooth", spread[1] < 15, "stddev=" + spread[1]);
    }
}
