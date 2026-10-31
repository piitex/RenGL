package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Camera;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.render3d.upscale.UpscaleInputs;
import me.piitex.engine.render3d.upscale.Upscaler;
import me.piitex.engine.render3d.upscale.UpscalerProvider;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a temporal upscaler is given, on a real GPU on both APIs: that the scene is drawn at the render size, that the
 * motion vectors say how far each pixel moved (for a camera that moved, an object that moved, and the sky), that the depth
 * is the depth of the solid objects, and that the jitter is the one the frame says. A probe upscaler writes what it is given
 * into a picture the test can read. The scene is stepped once per frame, as a viewport draws it, because the history is kept
 * from one frame of the window to the next. Skips where there is no GPU.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class UpscaleInputsGpuTest {
    private static final int VIEW = 128;

    private static final String VERTEX = """
            #version 450
            layout(location = 0) in vec2 aPos;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """;

    /**
     * The motion in the first two channels (an eighth of a pixel to a step, around one half) and the depth in the third.
     */
    private static final String ENCODE = """
            #version 450
            layout(set = 0, binding = 1) uniform sampler2D motionTex;
            layout(set = 0, binding = 2) uniform sampler2D depthTex;
            layout(location = 0) out vec4 outColor;
            void main() {
                ivec2 p = ivec2(gl_FragCoord.xy);
                vec2 m = texelFetch(motionTex, p, 0).xy;
                float d = texelFetch(depthTex, p, 0).r;
                outColor = vec4(0.5 + m / 32.0, d, 1.0);
            }
            """;

    /**
     * Records what the upscaler was given in every frame and encodes the last frame's motion and depth into {@link #encoded}.
     */
    private static final class Probe implements Upscaler {
        final GpuDevice device;
        final Pipeline pipeline;
        final GpuBuffer triangle;
        RenderTarget encoded;
        final List<UpscaleInputs> history = new ArrayList<>();

        Probe(GpuDevice device) {
            this.device = device;
            pipeline = device.createPipeline(new PipelineDesc(VERTEX, ENCODE, VertexLayout.of(VertexFormat.FLOAT2), false, false, false));
            ByteBuffer data = MemoryUtil.memAlloc(6 * 4);
            data.asFloatBuffer().put(new float[]{-1, -1, 3, -1, -1, 3});
            triangle = device.createBuffer(BufferType.VERTEX, data);
            MemoryUtil.memFree(data);
        }

        @Override
        public String name() {
            return "Probe";
        }

        @Override
        public void upscale(UpscaleInputs in) {
            history.add(in);
            if (encoded == null || encoded.width() != in.renderWidth() || encoded.height() != in.renderHeight()) {
                if (encoded != null) encoded.close();
                encoded = device.createSampledTarget(in.renderWidth(), in.renderHeight(), false, false);
            }
            try (RenderPass pass = device.beginPass(encoded, 0f, 0f, 0f, 1f)) {
                pass.bindPipeline(pipeline);
                pass.bindTexture(1, in.motion());
                pass.bindTexture(2, in.depth());
                pass.bindVertexBuffer(0, triangle);
                pass.draw(3);
            }
            try (RenderPass pass = device.beginPass(in.output(), 0f, 0f, 0f, 1f)) {
            }
        }

        @Override
        public void close() {
            if (encoded != null) encoded.close();
            pipeline.close();
            triangle.close();
        }

        /**
         * The motion (x, y) and depth at a pixel of the render image.
         */
        float[] at(int x, int y) {
            ByteBuffer data = encoded.readPixels();
            try {
                int i = (y * encoded.width() + x) * 4;
                return new float[]{((data.get(i) & 255) / 255f - 0.5f) * 32f, ((data.get(i + 1) & 255) / 255f - 0.5f) * 32f, (data.get(i + 2) & 255) / 255f};
            } finally {
                MemoryUtil.memFree(data);
            }
        }
    }

    private static final class ProbeProvider implements UpscalerProvider {
        Probe probe;

        @Override
        public String name() {
            return "Probe";
        }

        @Override
        public boolean isAvailable(GpuDevice device) {
            return true;
        }

        @Override
        public Upscaler create(GpuDevice device) {
            probe = new Probe(device);
            return probe;
        }
    }

    /**
     * {@code step} runs before frame {@code n} (from 1) is drawn; {@code check} runs after frame 6 has been, when the probe
     * holds what the upscaler was given in each.
     */
    private interface Step {
        void run(Scene3D scene, ProbeProvider provider, int frame);
    }

    private interface Check {
        void run(GpuDevice device, Scene3D scene, ProbeProvider provider, GpuHarness.Recorder r);
    }

    private record Scenario(String name, Step step, Check check) {
    }

    private static Scene3D scene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(Color.BLACK);
        scene.setSkybox(Skybox.gradient(new Color(0.2f, 0.4f, 0.9f, 1f), new Color(0.9f, 0.9f, 0.9f, 1f), new Color(0.1f, 0.1f, 0.1f, 1f)));
        scene.setAmbient(Color.WHITE, 1f);
        return scene;
    }

    private static Node3D wall(float halfSize) {
        Mesh quad = new Mesh(new float[]{-halfSize, -halfSize, 0, halfSize, -halfSize, 0, halfSize, halfSize, 0, -halfSize, halfSize, 0},
                new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1}, new float[]{0, 1, 1, 1, 1, 0, 0, 0}, new int[]{0, 1, 2, 0, 2, 3});
        return new Node3D("wall", quad, new Material(Color.WHITE).setUnlit(true));
    }

    private static void look(Scene3D scene) {
        scene.camera().lookAt(new Vec3(0, 0, 3), new Vec3(0, 0, 0)).setFovYDegrees(60).setClip(2f, 6f);
    }

    /**
     * The motion CPU maths gives for a world point seen by two cameras: the previous position minus this one, in pixels of
     * an image {@code size} wide and tall, with +y down the picture when {@code rowsDown} and up it otherwise.
     */
    private static float[] expectedMotion(Camera before, Camera now, Vec3 point, int size, boolean rowsDown) {
        Vec3 a = before.projection(1f).mul(before.view()).transformPoint(point);
        Vec3 b = now.projection(1f).mul(now.view()).transformPoint(point);
        float dx = (a.x() - b.x()) * 0.5f * size, dy = (a.y() - b.y()) * 0.5f * size;
        return new float[]{dx, rowsDown ? -dy : dy};
    }

    private static Camera copy(Camera c) {
        return new Camera().lookAt(c.getPosition(), c.getTarget()).setFovYDegrees(c.getFovYDegrees()).setClip(c.getNear(), c.getFar());
    }

    private static boolean near(float got, float wanted, float slack) {
        return Math.abs(got - wanted) <= slack;
    }

    private static Scenario sizes() {
        return new Scenario("the scene is drawn at the render size", (scene, provider, frame) -> {
            if (frame == 1) {
                scene.add(wall(10));
                look(scene);
                scene.setUpscaler(provider);
                scene.setRenderScale(0.5f);
            }
            if (frame == 5) scene.resetTemporalHistory();
        }, (device, scene, provider, r) -> {
            List<UpscaleInputs> calls = provider.probe.history;
            r.check("sizes: it ran every frame", calls.size() == 6, calls.size() + " calls");
            UpscaleInputs in = calls.get(0);
            int w = in.displayWidth(), h = in.displayHeight();
            r.check("sizes: the render size is half the display", in.renderWidth() == Math.round(w * 0.5f) && in.renderHeight() == Math.round(h * 0.5f),
                    "render " + in.renderWidth() + "x" + in.renderHeight() + " of " + w + "x" + h);
            r.check("sizes: the output is the display size", in.output().width() == w && in.output().height() == h, "output " + in.output().width() + "x" + in.output().height());
            r.check("sizes: the inputs are the render size", in.color().width() == in.renderWidth() && in.depth().width() == in.renderWidth() && in.motion().width() == in.renderWidth(),
                    "color " + in.color().width() + ", depth " + in.depth().width() + ", motion " + in.motion().width());
            r.check("sizes: the first frame is a reset", in.reset() && in.previousOutput() == null && in.frame() == 0, "reset " + in.reset() + ", frame " + in.frame());
            in = calls.get(1);
            r.check("sizes: the second frame keeps the history", !in.reset() && in.previousOutput() != null && in.frame() == 1, "reset " + in.reset() + ", frame " + in.frame());
            r.check("sizes: the history is last frame's output", in.previousOutput() == calls.get(0).output(), "it is another target");
            r.check("sizes: the outputs take turns", calls.get(2).output() == calls.get(0).output() && calls.get(2).output() != calls.get(1).output(), "they do not");
            r.check("sizes: the jitter stays within a pixel", Math.abs(in.jitterX()) <= 0.5f && Math.abs(in.jitterY()) <= 0.5f && (in.jitterX() != 0f || in.jitterY() != 0f),
                    "jitter " + in.jitterX() + ", " + in.jitterY());
            r.check("sizes: the jitter changes from frame to frame", calls.get(1).jitterX() != calls.get(2).jitterX() || calls.get(1).jitterY() != calls.get(2).jitterY(), "the same twice");
            r.check("sizes: asking for a reset drops the history", calls.get(4).reset() && calls.get(4).previousOutput() == null && calls.get(4).frame() == 0,
                    "reset " + calls.get(4).reset() + ", frame " + calls.get(4).frame());
            r.check("sizes: and the next frame goes on from it", !calls.get(5).reset() && calls.get(5).previousOutput() != null, "reset " + calls.get(5).reset());
        });
    }

    private static Scenario still() {
        return new Scenario("nothing moves when nothing does", (scene, provider, frame) -> {
            if (frame == 1) {
                scene.add(wall(10));
                look(scene);
                scene.setUpscaler(provider);
            }
        }, (device, scene, provider, r) -> {
            Probe probe = provider.probe;
            int w = probe.encoded.width(), h = probe.encoded.height();
            float[] wallPixel = probe.at(w / 2, h / 2);
            r.check("still: the wall does not move", near(wallPixel[0], 0, 0.2f) && near(wallPixel[1], 0, 0.2f), "motion " + wallPixel[0] + ", " + wallPixel[1]);
            // Depth of the wall: 3 away between planes 2 and 6.
            r.check("still: the depth is where the wall is", near(wallPixel[2], 0.5f, 0.02f), "depth " + wallPixel[2]);
            float[] corner = probe.at(1, 1);
            r.check("still: the sky does not move either", near(corner[0], 0, 0.2f) && near(corner[1], 0, 0.2f), "motion " + corner[0] + ", " + corner[1]);
        });
    }

    private static Scenario cameraMoves() {
        Camera[] before = new Camera[1];
        return new Scenario("a moving camera shows in the motion", (scene, provider, frame) -> {
            if (frame == 1) {
                scene.add(wall(10));
                look(scene);
                scene.setUpscaler(provider);
            }
            if (frame == 6) {
                before[0] = copy(scene.camera());
                // Right, and up, so both axes are checked (and which way the rows of the image run).
                scene.camera().lookAt(new Vec3(0.2f, 0.1f, 3), new Vec3(0.2f, 0.1f, 0));
            }
        }, (device, scene, provider, r) -> {
            Probe probe = provider.probe;
            int w = probe.encoded.width(), h = probe.encoded.height();
            boolean rowsDown = device.api() == RenderAPI.VULKAN;
            float[] want = expectedMotion(before[0], scene.camera(), new Vec3(0.2f, 0.1f, 0), w, rowsDown);
            float[] got = probe.at(w / 2, h / 2);
            r.check("camera: the wall's motion is how far the camera moved", near(got[0], want[0], 0.35f) && near(got[1], want[1], 0.35f),
                    "motion " + got[0] + ", " + got[1] + ", wanted " + want[0] + ", " + want[1]);
            r.check("camera: the motion is not zero", Math.abs(want[0]) > 1f && Math.abs(want[1]) > 0.5f, "the test moved the camera too little: " + want[0] + ", " + want[1]);
        });
    }

    private static Scenario objectMoves() {
        Node3D wall = wall(1.5f);
        return new Scenario("a moving object shows in the motion", (scene, provider, frame) -> {
            if (frame == 1) {
                scene.add(wall);
                look(scene);
                scene.setUpscaler(provider);
            }
            if (frame == 6) wall.setPosition(new Vec3(0.3f, 0, 0));
        }, (device, scene, provider, r) -> {
            Probe probe = provider.probe;
            int w = probe.encoded.width(), h = probe.encoded.height();
            Camera camera = scene.camera();
            Mat4 viewProj = camera.projection(1f).mul(camera.view());
            // The wall's centre was at the origin a frame ago, and is at x = 0.3 now: the middle of the image is part of it either way.
            float want = (viewProj.transformPoint(new Vec3(0f, 0, 0)).x() - viewProj.transformPoint(new Vec3(0.3f, 0, 0)).x()) * 0.5f * w;
            float[] got = probe.at(w / 2, h / 2);
            r.check("object: the wall's motion is how far it moved", near(got[0], want, 0.35f) && near(got[1], 0, 0.3f),
                    "motion " + got[0] + ", " + got[1] + ", wanted " + want + ", 0");
            // The sky behind it, with a still camera, has no motion.
            float[] sky = probe.at(1, 1);
            r.check("object: the sky behind does not move", near(sky[0], 0, 0.2f) && near(sky[1], 0, 0.2f), "motion " + sky[0] + ", " + sky[1]);
        });
    }

    private static Scenario skyTurns() {
        Camera[] before = new Camera[1];
        return new Scenario("the sky moves when the camera turns", (scene, provider, frame) -> {
            if (frame == 1) {
                look(scene);
                scene.setUpscaler(provider);
            }
            if (frame == 6) {
                before[0] = copy(scene.camera());
                // Turned toward +x without moving.
                scene.camera().lookAt(new Vec3(0, 0, 3), new Vec3(0.3f, 0, 0));
            }
        }, (device, scene, provider, r) -> {
            Probe probe = provider.probe;
            int w = probe.encoded.width(), h = probe.encoded.height();
            // The direction through the middle pixel now, as a point very far away: the camera's turning moves it, nothing else.
            Vec3 forward = scene.camera().getTarget().sub(scene.camera().getPosition()).normalized();
            Vec3 far = scene.camera().getPosition().add(forward.mul(1e5f));
            boolean rowsDown = device.api() == RenderAPI.VULKAN;
            float[] want = expectedMotion(before[0], scene.camera(), far, w, rowsDown);
            float[] got = probe.at(w / 2, h / 2);
            r.check("sky: the turn moves the sky as it does a far point", near(got[0], want[0], 0.5f) && near(got[1], want[1], 0.35f),
                    "motion " + got[0] + ", " + got[1] + ", wanted " + want[0] + ", " + want[1]);
            r.check("sky: the motion is not zero", Math.abs(want[0]) > 2f, "the test turned the camera too little: " + want[0]);
            r.check("sky: its depth is the far plane", got[2] > 0.99f, "depth " + got[2]);
        });
    }

    private static Scenario spatialGetsNoHistory() {
        UpscaleInputs[] seen = new UpscaleInputs[1];
        UpscalerProvider spatial = new UpscalerProvider() {
            @Override
            public String name() {
                return "Spatial";
            }

            @Override
            public boolean isAvailable(GpuDevice d) {
                return true;
            }

            @Override
            public Upscaler create(GpuDevice d) {
                return new Upscaler() {
                    @Override
                    public String name() {
                        return "Spatial";
                    }

                    @Override
                    public boolean isTemporal() {
                        return false;
                    }

                    @Override
                    public void upscale(UpscaleInputs in) {
                        seen[0] = in;
                        try (RenderPass pass = d.beginPass(in.output(), 0f, 0f, 0f, 1f)) {
                        }
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
        return new Scenario("a spatial upscaler gets the colour alone", (scene, provider, frame) -> {
            if (frame == 1) {
                scene.add(wall(10));
                look(scene);
                scene.setUpscaler(spatial);
                scene.setRenderScale(0.5f);
            }
        }, (device, scene, provider, r) -> {
            r.check("spatial: no depth and no motion", seen[0] != null && seen[0].depth() == null && seen[0].motion() == null, "depth and motion were given");
            r.check("spatial: no jitter", seen[0].jitterX() == 0f && seen[0].jitterY() == 0f, "jitter " + seen[0].jitterX() + ", " + seen[0].jitterY());
        });
    }

    @TestFactory
    List<DynamicTest> vulkan() {
        return tests(RenderAPI.VULKAN);
    }

    @TestFactory
    List<DynamicTest> openGl() {
        return tests(RenderAPI.OPENGL);
    }

    private List<DynamicTest> tests(RenderAPI api) {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario scenario : List.of(sizes(), still(), cameraMoves(), objectMoves(), skyTurns(), spatialGetsNoHistory())) {
            Scene3D scene = scene();
            ProbeProvider provider = new ProbeProvider();
            GpuHarness.Run run = GpuHarness.run(api, VIEW, (Consumer<Viewport3D>) viewport -> viewport.setContent(new Viewport3D.Content() {
                int frame;

                @Override
                public void init(GpuDevice device) {
                    scene.init(device);
                }

                @Override
                public void render(GpuDevice device, RenderTarget target, float delta) {
                    scenario.step().run(scene, provider, ++frame);
                    scene.render(device, target, delta);
                }

                @Override
                public void dispose(GpuDevice device) {
                    scene.dispose(device);
                }
            }), (device, target, recorder) -> {
                if (device.api() != api) return;
                scenario.check().run(device, scene, provider, recorder);
            });
            boolean usable = run.available() && run.api() == api;
            tests.add(DynamicTest.dynamicTest(api + ": " + scenario.name() + ": runs", () ->
                    assumeTrue(usable, run.reason() == null ? "the window ran on " + run.api() : run.reason())));
            if (usable) {
                run.results().forEach((name, failure) -> tests.add(DynamicTest.dynamicTest(api + ": " + name, () -> assertNull(failure, name + ": " + failure))));
            }
        }
        return tests;
    }
}
