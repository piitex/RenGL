package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
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
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a denoising upscaler (DLSS Ray Reconstruction is one) is given by a path traced scene, on a real GPU: the pictures it is
 * guided by hold the colour each surface reflects diffusely, how much it reflects like a mirror, and its normal and roughness, and
 * the scene shows its noisy, unfiltered light instead of running its own filter. A probe upscaler that says it denoises writes
 * the guides into pictures the test can read. Where the upscaler does not denoise, the setting is off or the device can not trace
 * rays, no guides are made and the scene filters the light itself. Skips where there is no GPU or no ray queries.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class RayReconstructionGuidesGpuTest {
    private static final int VIEW = 192, FRAMES = 12;

    private static final String VERTEX = """
            #version 450
            layout(location = 0) in vec2 aPos;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """;

    private static final String ENCODE = """
            #version 450
            layout(set = 0, binding = 1) uniform sampler2D guide;
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = %OUT%;
            }
            """;

    /** A guide as it is, or with a normal's -1..1 brought into 0..1 (the roughness in alpha is already). */
    private static String encode(boolean normal) {
        String read = "texelFetch(guide, ivec2(gl_FragCoord.xy), 0)";
        return ENCODE.replace("%OUT%", normal ? "vec4(" + read + ".xyz * 0.5 + 0.5, " + read + ".w)" : read);
    }

    /** Takes the guides of every frame it is given and writes the last ones into targets that can be read back. */
    private static final class Probe implements Upscaler {
        final GpuDevice device;
        final boolean denoises;
        final Pipeline raw, normals;
        final GpuBuffer triangle;
        RenderTarget diffuse, specular, normal;
        final List<UpscaleInputs> history = new ArrayList<>();

        Probe(GpuDevice device, boolean denoises) {
            this.device = device;
            this.denoises = denoises;
            raw = device.createPipeline(new PipelineDesc(VERTEX, encode(false), VertexLayout.of(VertexFormat.FLOAT2), false, false, false));
            normals = device.createPipeline(new PipelineDesc(VERTEX, encode(true), VertexLayout.of(VertexFormat.FLOAT2), false, false, false));
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
        public boolean denoisesPathTracing() {
            return denoises;
        }

        private RenderTarget encoded(RenderTarget old, UpscaleInputs in) {
            if (old != null && old.width() == in.renderWidth() && old.height() == in.renderHeight()) return old;
            if (old != null) old.close();
            return device.createSampledTarget(in.renderWidth(), in.renderHeight(), false, false);
        }

        private void write(RenderTarget into, Pipeline pipeline, GpuTexture guide) {
            try (RenderPass pass = device.beginPass(into, 0f, 0f, 0f, 1f)) {
                pass.bindPipeline(pipeline);
                pass.bindTexture(1, guide);
                pass.bindVertexBuffer(0, triangle);
                pass.draw(3);
            }
        }

        @Override
        public void upscale(UpscaleInputs in) {
            history.add(in);
            if (in.hasGuides()) {
                diffuse = encoded(diffuse, in);
                specular = encoded(specular, in);
                normal = encoded(normal, in);
                write(diffuse, raw, in.diffuseAlbedo());
                write(specular, raw, in.specularAlbedo());
                write(normal, normals, in.normalRoughness());
            }
            try (RenderPass pass = device.beginPass(in.output(), 0f, 0f, 0f, 1f)) {
            }
        }

        @Override
        public void close() {
            for (RenderTarget t : new RenderTarget[]{diffuse, specular, normal}) if (t != null) t.close();
            raw.close();
            normals.close();
            triangle.close();
        }

        /** The four channels (0 to 1) of a guide at the pixel a world point lands on, for a camera of the scene looking at a square picture. */
        float[] at(RenderTarget target, Scene3D scene, float x, float y, float z) {
            var camera = scene.camera();
            Vec3 ndc = camera.projection(1f).mul(camera.view()).transformPoint(new Vec3(x, y, z));
            int w = target.width(), h = target.height();
            int px = Math.round((ndc.x() * 0.5f + 0.5f) * w - 0.5f), py = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * h - 0.5f);
            return pixel(target, px, py);
        }

        float[] pixel(RenderTarget target, int px, int py) {
            ByteBuffer data = target.readPixels();
            try {
                int i = (py * target.width() + px) * 4;
                return new float[]{(data.get(i) & 255) / 255f, (data.get(i + 1) & 255) / 255f, (data.get(i + 2) & 255) / 255f, (data.get(i + 3) & 255) / 255f};
            } finally {
                MemoryUtil.memFree(data);
            }
        }
    }

    private static final class ProbeProvider implements UpscalerProvider {
        final boolean denoises;
        Probe probe;

        ProbeProvider(boolean denoises) {
            this.denoises = denoises;
        }

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
            probe = new Probe(device, denoises);
            return probe;
        }
    }

    /**
     * A white floor, a red wall rising from it at x = 2.5 and a smooth, mostly metal ball, lit by a sun, with a black sky; path
     * traced. The camera looks down at them, so the top of the picture is sky.
     */
    private static Scene3D scene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setAmbient(Color.BLACK, 0f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.lights().add(Light.directional(new Vec3(1f, -1.2f, 0f), Color.WHITE, 0.8f));
        scene.setPathTracing(true);
        scene.add(new Node3D("floor", Mesh.plane(40f, 40f), new Material(Color.WHITE).setRoughness(0.9f)));
        Node3D wall = new Node3D("wall", Mesh.cube(1f), new Material(Color.RED).setRoughness(0.9f));
        wall.setPosition(2.5f, 2f, 0f);
        wall.setScale(new Vec3(1f, 4f, 12f));
        scene.add(wall);
        Node3D ball = new Node3D("ball", Mesh.sphere(1f, 48, 24), new Material(new Color(0.9f, 0.9f, 0.9f, 1f)).setRoughness(0.15f).setMetallic(0.8f));
        ball.setPosition(-1f, 1f, 1f);
        scene.add(ball);
        scene.camera().lookAt(new Vec3(-3f, 7f, 9f), new Vec3(0f, 0f, 0f)).setFovYDegrees(60);
        scene.setRenderScale(0.5f);
        return scene;
    }

    private interface Check {
        void run(Scene3D scene, ProbeProvider provider, GpuHarness.Recorder r, boolean traces);
    }

    private record Scenario(String name, boolean denoises, Consumer<Scene3D> setup, Check check) {
    }

    private static boolean near(float got, float wanted, float slack) {
        return Math.abs(got - wanted) <= slack;
    }

    private static String show(float[] c) {
        return Arrays.toString(c);
    }

    private static Scenario guides() {
        return new Scenario("the guides describe the surfaces", true, scene -> {
        }, (scene, provider, r, traces) -> {
            Probe probe = provider.probe;
            if (!traces) {
                r.check("guides: without ray queries there are none", probe.history.stream().noneMatch(UpscaleInputs::hasGuides) && !scene.isUsingRayReconstruction(),
                        "guides were made, or the scene says it reconstructs");
                return;
            }
            r.check("guides: they came with every frame", probe.history.size() == FRAMES && probe.history.stream().allMatch(UpscaleInputs::hasGuides),
                    probe.history.size() + " calls, " + probe.history.stream().filter(UpscaleInputs::hasGuides).count() + " with guides");
            r.check("guides: the scene says Ray Reconstruction cleaned it and it was path traced", scene.isUsingRayReconstruction() && scene.isUsingPathTracing(),
                    "reconstruction " + scene.isUsingRayReconstruction() + ", path tracing " + scene.isUsingPathTracing());
            UpscaleInputs last = probe.history.get(probe.history.size() - 1);
            r.check("guides: they are the render size", last.diffuseAlbedo().width() == last.renderWidth() && last.normalRoughness().height() == last.renderHeight()
                    && last.specularAlbedo().width() == last.renderWidth(), "render " + last.renderWidth() + "x" + last.renderHeight());
            r.check("guides: the view matrix is the camera's", Arrays.equals(last.worldToView(), scene.camera().view().toArray()), "it is another matrix");
            r.guard("guides: the floor is white, dull and faces up", () -> {
                float[] d = probe.at(probe.diffuse, scene, -4f, 0f, 0f), s = probe.at(probe.specular, scene, -4f, 0f, 0f), n = probe.at(probe.normal, scene, -4f, 0f, 0f);
                r.check("guides: the floor is white, dull and faces up", d[0] > 0.97f && d[1] > 0.97f && d[2] > 0.97f && s[0] < 0.06f && near(n[0], 0.5f, 0.03f)
                        && near(n[1], 1f, 0.03f) && near(n[2], 0.5f, 0.03f) && near(n[3], 0.9f, 0.03f),
                        "diffuse " + show(d) + " specular " + show(s) + " normal " + show(n));
            });
            r.guard("guides: the wall is red and faces the floor", () -> {
                float[] d = probe.at(probe.diffuse, scene, 2f, 2f, 0f), n = probe.at(probe.normal, scene, 2f, 2f, 0f);
                r.check("guides: the wall is red and faces the floor", d[0] > 0.97f && d[1] < 0.03f && d[2] < 0.03f && n[0] < 0.04f && near(n[1], 0.5f, 0.04f) && near(n[2], 0.5f, 0.04f),
                        "diffuse " + show(d) + " normal " + show(n));
            });
            r.guard("guides: the ball is a mirror with little colour of its own, smooth, and faces the camera", () -> {
                // The point of the sphere (centre -1, 1, 1, radius 1) that faces the camera at (-3, 7, 9).
                float[] d = probe.at(probe.diffuse, scene, -1.196f, 1.588f, 1.784f), s = probe.at(probe.specular, scene, -1.196f, 1.588f, 1.784f),
                        n = probe.at(probe.normal, scene, -1.196f, 1.588f, 1.784f);
                r.check("guides: the ball is a mirror with little colour of its own, smooth, and faces the camera",
                        d[0] > 0.1f && d[0] < 0.22f && s[0] > 0.5f && s[0] < 0.85f && near(n[3], 0.15f, 0.03f)
                                && near(n[0], 0.402f, 0.07f) && near(n[1], 0.794f, 0.07f) && near(n[2], 0.892f, 0.07f),
                        "diffuse " + show(d) + " specular " + show(s) + " normal " + show(n));
            });
            r.guard("guides: the sky has none", () -> {
                float[] d = probe.pixel(probe.diffuse, probe.diffuse.width() / 2, 1), s = probe.pixel(probe.specular, probe.specular.width() / 2, 1);
                r.check("guides: the sky has none", d[0] + d[1] + d[2] + d[3] < 0.02f && s[0] + s[1] + s[2] + s[3] < 0.02f, "diffuse " + show(d) + " specular " + show(s));
            });
        });
    }

    private static Scenario off() {
        return new Scenario("the setting turns it off", true, scene -> scene.setRayReconstruction(false), (scene, provider, r, traces) -> {
            Probe probe = provider.probe;
            r.check("off: no guides were made", probe.history.stream().noneMatch(UpscaleInputs::hasGuides) && !scene.isUsingRayReconstruction(), "guides were made");
            if (traces) r.check("off: the scene filtered the light itself", scene.isUsingPathTracing(), "it was not path traced");
        });
    }

    private static Scenario notDenoising() {
        return new Scenario("an upscaler that does not denoise gets none", false, scene -> {
        }, (scene, provider, r, traces) -> {
            Probe probe = provider.probe;
            r.check("plain: no guides were made", probe.history.stream().noneMatch(UpscaleInputs::hasGuides) && !scene.isUsingRayReconstruction(), "guides were made");
            if (traces) r.check("plain: the scene filtered the light itself", scene.isUsingPathTracing(), "it was not path traced");
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
        for (Scenario scenario : List.of(guides(), off(), notDenoising())) {
            Scene3D scene = scene();
            scenario.setup().accept(scene);
            ProbeProvider provider = new ProbeProvider(scenario.denoises());
            scene.setUpscaler(provider);
            boolean[] traces = {false};
            GpuHarness.Run run = GpuHarness.run(api, VIEW, (Consumer<Viewport3D>) viewport -> viewport.setContent(new Viewport3D.Content() {
                @Override
                public void init(GpuDevice device) {
                    scene.init(device);
                    traces[0] = device.supportsRayTracing();
                }

                @Override
                public void render(GpuDevice device, RenderTarget target, float delta) {
                    scene.render(device, target, delta);
                }

                @Override
                public void dispose(GpuDevice device) {
                    scene.dispose(device);
                }
            }), (device, target, recorder) -> {
                if (device.api() != api) return;
                scenario.check().run(scene, provider, recorder, traces[0]);
            }, FRAMES);
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
