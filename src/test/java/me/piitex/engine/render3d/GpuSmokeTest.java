package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Camera;
import me.piitex.engine.render3d.scene.Hit;
import me.piitex.engine.render3d.scene.Instances;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Skybox;
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
        Scene3D featureScene = buildFeatureScene();
        GpuHarness.Run features = GpuHarness.run(api, VIEW, viewport -> viewport.setContent(new SceneContent(featureScene)),
                (device, target, recorder) -> {
                    if (device.api() == api) featureChecks(featureScene, target, recorder);
                });
        Scene3D spotScene = buildSpotScene();
        GpuHarness.Run spots = GpuHarness.run(api, VIEW, viewport -> viewport.setContent(new SceneContent(spotScene)),
                (device, target, recorder) -> {
                    if (device.api() == api) spotChecks(spotScene, target, recorder);
                });
        Scene3D effectsScene = buildEffectsScene();
        GpuHarness.Run effects = GpuHarness.run(api, VIEW, viewport -> viewport.setContent(new SceneContent(effectsScene)),
                (device, target, recorder) -> {
                    if (device.api() == api) effectChecks(effectsScene, target, recorder);
                });
        Scene3D cullScene = buildCullScene();
        GpuHarness.Run culling = GpuHarness.run(api, VIEW, viewport -> viewport.setContent(new SceneContent(cullScene)),
                (device, target, recorder) -> {
                    if (device.api() == api) cullChecks(cullScene, device, target, recorder);
                });
        Scene3D shadowScene = buildSoftShadowScene();
        GpuHarness.Run softness = GpuHarness.run(api, VIEW, viewport -> viewport.setContent(new SceneContent(shadowScene)),
                (device, target, recorder) -> {
                    if (device.api() == api) softnessChecks(shadowScene, device, target, recorder);
                });
        Scene3D cubeEnv = buildEnvScene(cubeSky());
        GpuHarness.Run cubeRun = GpuHarness.run(api, VIEW, viewport -> viewport.setContent(new SceneContent(cubeEnv)),
                (device, target, recorder) -> {
                    if (device.api() == api) envChecks("cube", cubeEnv, target, recorder, true);
                });
        Scene3D panoEnv = buildEnvScene(panoramaSky());
        GpuHarness.Run panoRun = GpuHarness.run(api, VIEW, viewport -> viewport.setContent(new SceneContent(panoEnv)),
                (device, target, recorder) -> {
                    if (device.api() == api) envChecks("panorama", panoEnv, target, recorder, false);
                });
        Scene3D gradientEnv = buildEnvScene(Skybox.gradient(new Color(1f, 0f, 0f, 1f), Color.BLACK, new Color(0f, 0f, 1f, 1f)));
        GpuHarness.Run gradientRun = GpuHarness.run(api, VIEW, viewport -> viewport.setContent(new SceneContent(gradientEnv)),
                (device, target, recorder) -> {
                    if (device.api() == api) gradientChecks(gradientEnv, target, recorder);
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
                    () -> assertNull(failure, name + ": " + failure))));
        }
        if (usable && features.available()) {
            features.results().forEach((name, failure) -> tests.add(DynamicTest.dynamicTest(api + ": " + name,
                    () -> assertNull(failure, name + ": " + failure))));
        }
        if (usable && spots.available()) {
            spots.results().forEach((name, failure) -> tests.add(DynamicTest.dynamicTest(api + ": " + name,
                    () -> assertNull(failure, name + ": " + failure))));
        }
        for (GpuHarness.Run extra : List.of(effects, culling, softness, cubeRun, panoRun, gradientRun)) {
            if (usable && extra.available()) {
                extra.results().forEach((name, failure) -> tests.add(DynamicTest.dynamicTest(api + ": " + name,
                        () -> assertNull(failure, name + ": " + failure))));
            }
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

    /**
     * Shadows, a skybox, instancing, tints, PBR and emissive surfaces in one picture.
     */
    private static Scene3D buildFeatureScene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.lights().add(Light.directional(new Vec3(-0.3f, -1f, -0.2f), Color.WHITE, 1.2f).setCastShadow(true));
        scene.setAmbient(Color.WHITE, 0.15f);
        scene.setSkybox(Skybox.gradient(new Color(0.2f, 0.4f, 0.8f, 1f), new Color(0.2f, 0.4f, 0.8f, 1f), new Color(0.2f, 0.4f, 0.8f, 1f)));
        scene.add(new Node3D("floor", Mesh.plane(40, 40), new Material(Color.WHITE).setRoughness(0.9f)));
        Node3D pillar = new Node3D("pillar", Mesh.cube(1f), new Material(Color.WHITE).setRoughness(0.8f));
        pillar.setPosition(0, 2f, 0);
        pillar.setScale(new Vec3(1f, 4f, 1f));
        scene.add(pillar);
        Node3D crowd = new Node3D("crowd", Mesh.cube(1f), new Material(Color.WHITE).setUnlit(true));
        Instances instances = new Instances();
        Color[] tints = {new Color(1f, 0f, 0f, 1f), new Color(0f, 1f, 0f, 1f), new Color(0f, 0f, 1f, 1f)};
        for (int i = 0; i < 3; i++) {
            int index = instances.add(new Vec3(0.7f + i * 1.1f, 0.5f, 1.5f));
            instances.setTint(index, tints[i]);
        }
        crowd.setInstances(instances);
        scene.add(crowd);
        Node3D lamp = new Node3D("lamp", Mesh.cube(1f), new Material(Color.BLACK).setEmissive(Color.WHITE, 3f));
        lamp.setPosition(-3.2f, 0.5f, 2f);
        scene.add(lamp);
        Node3D ball = new Node3D("ball", Mesh.sphere(0.7f, 32, 16), new Material(new Color(1f, 0.1f, 0.1f, 1f)).setMetallic(0f).setRoughness(0.5f));
        ball.setPosition(-1.5f, 0.7f, 3f);
        scene.add(ball);
        scene.camera().lookAt(new Vec3(0, 3f, 9f), new Vec3(0, 0.8f, 0)).setFovYDegrees(50);
        return scene;
    }

    private void featureChecks(Scene3D scene, RenderTarget target, GpuHarness.Recorder r) {
        r.guard("features: shadows, sky, instancing, PBR and emissive agree with the picture", () -> {
            int w = target.width(), h = target.height();
            ByteBuffer px = target.readPixels();
            try {
                Camera camera = scene.camera();
                Mat4 viewProjection = camera.projection((float) w / h).mul(camera.view());
                IntFunction3 at = (x, y, z) -> {
                    Vec3 ndc = viewProjection.transformPoint(new Vec3(x, y, z));
                    int sx = Math.round((ndc.x() * 0.5f + 0.5f) * w), sy = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * h);
                    int i = (Math.max(0, Math.min(h - 1, sy)) * w + Math.max(0, Math.min(w - 1, sx))) * 4;
                    return new int[]{px.get(i) & 255, px.get(i + 1) & 255, px.get(i + 2) & 255};
                };
                int[] shadowed = at.get(-0.9f, 0f, -0.6f), lit = at.get(-3.5f, 0f, -3f);
                int shadowedLuma = shadowed[0] + shadowed[1] + shadowed[2], litLuma = lit[0] + lit[1] + lit[2];
                r.check("features: the pillar's shadow darkens the floor", shadowedLuma < litLuma * 0.7,
                        "shadowed " + java.util.Arrays.toString(shadowed) + " lit " + java.util.Arrays.toString(lit));

                int[] sky = {px.get(2 * w * 4 + (w / 2) * 4) & 255, px.get(2 * w * 4 + (w / 2) * 4 + 1) & 255, px.get(2 * w * 4 + (w / 2) * 4 + 2) & 255};
                r.check("features: the skybox fills the top of the picture", Math.abs(sky[0] - 51) < 6 && Math.abs(sky[1] - 102) < 6 && Math.abs(sky[2] - 204) < 6,
                        java.util.Arrays.toString(sky));

                for (int i = 0; i < 3; i++) {
                    int[] c = at.get(0.7f + i * 1.1f, 0.5f, 1.5f);
                    boolean dominant = c[i] > 200 && c[(i + 1) % 3] < 60 && c[(i + 2) % 3] < 60;
                    r.check("features: instance " + i + " has its own tint", dominant, java.util.Arrays.toString(c));
                }
                int[] glow = at.get(-3.2f, 0.5f, 2f);
                r.check("features: an emissive surface glows", glow[0] > 200 && glow[1] > 200 && glow[2] > 200, java.util.Arrays.toString(glow));
                int[] ball = at.get(-1.5f, 0.7f, 3f);
                r.check("features: a PBR sphere takes its colour", ball[0] > ball[1] * 2 && ball[0] > ball[2] * 2 && ball[0] > 40, java.util.Arrays.toString(ball));
            } finally {
                MemoryUtil.memFree(px);
            }
        });
    }

    /**
     * A spot light over a floor, with a box in its cone that casts a shadow.
     */
    private static Scene3D buildSpotScene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.lights().add(Light.spot(new Vec3(0, 6, 0), new Vec3(0, -1, 0), Color.WHITE, 3f, 20f, 15f, 30f).setCastShadow(true));
        scene.setAmbient(Color.WHITE, 0.02f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.add(new Node3D("floor", Mesh.plane(40, 40), new Material(Color.WHITE).setRoughness(0.9f)));
        Node3D box = new Node3D("box", Mesh.cube(1f), new Material(Color.WHITE).setRoughness(0.8f));
        box.setPosition(-1f, 1f, 0f);
        box.setScale(new Vec3(1f, 1f, 0.6f));
        scene.add(box);
        scene.camera().lookAt(new Vec3(3f, 7f, 8f), new Vec3(0, 0, 0)).setFovYDegrees(60);
        return scene;
    }

    private void spotChecks(Scene3D scene, RenderTarget target, GpuHarness.Recorder r) {
        r.guard("spot: the cone lights the floor and the box shadows it", () -> {
            ByteBuffer px = target.readPixels();
            try {
                int[] inside = pixelAt(scene, target, px, 1f, 0f, 1.2f);
                int[] outside = pixelAt(scene, target, px, 6f, 0f, 4f);
                int[] lit = pixelAt(scene, target, px, 1.55f, 0f, 0f);
                int[] shadowed = pixelAt(scene, target, px, -1.55f, 0f, 0f);
                r.check("spot: the floor inside the cone is lit", luma(inside) > 60, java.util.Arrays.toString(inside));
                r.check("spot: the floor outside the cone gets only the ambient light", luma(outside) < luma(inside) * 0.4, "outside " + java.util.Arrays.toString(outside) + " inside " + java.util.Arrays.toString(inside));
                r.check("spot: the box shadows the floor", luma(shadowed) < luma(lit) * 0.5 && luma(lit) > 60,
                        "shadowed " + java.util.Arrays.toString(shadowed) + " lit " + java.util.Arrays.toString(lit));
            } finally {
                MemoryUtil.memFree(px);
            }
        });
    }

    /**
     * Fog on a near and a far cube, and an additive quad over a green sky.
     */
    private static Scene3D buildEffectsScene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setSkybox(Skybox.gradient(new Color(0f, 0.4f, 0f, 1f), new Color(0f, 0.4f, 0f, 1f), new Color(0f, 0.4f, 0f, 1f)));
        scene.setFog(new Color(0f, 0f, 1f, 1f), 0.03f);
        Material white = new Material(Color.WHITE).setUnlit(true);
        Node3D near = new Node3D("near", Mesh.cube(2f), white);
        near.setPosition(-2.5f, 3f, 0f);
        Node3D far = new Node3D("far", Mesh.cube(14f), white);
        far.setPosition(5f, 3f, -60f);
        Node3D glow = new Node3D("glow", Mesh.plane(2f, 2f), new Material(new Color(1f, 0f, 0f, 1f)).setUnlit(true).setAdditive(true).setDoubleSided(true));
        glow.setPosition(2.5f, 3f, 0f);
        glow.setRotation(Quat.fromAxisAngle(Vec3.X, (float) Math.PI / 2f)); // the plane lies flat; stand it up
        scene.add(near).add(far).add(glow);
        scene.camera().lookAt(new Vec3(0, 3f, 10f), new Vec3(0, 3f, 0)).setFovYDegrees(50);
        return scene;
    }

    private void effectChecks(Scene3D scene, RenderTarget target, GpuHarness.Recorder r) {
        r.guard("effects: fog and additive blending", () -> {
            ByteBuffer px = target.readPixels();
            try {
                int[] near = pixelAt(scene, target, px, -2.5f, 3f, 1f);
                int[] far = pixelAt(scene, target, px, 5f, 3f, -53f);
                int[] glow = pixelAt(scene, target, px, 2.5f, 3f, 0f);
                r.check("effects: a near surface is barely fogged", near[0] > 180 && near[1] > 180, java.util.Arrays.toString(near));
                r.check("effects: a far surface is the fog colour", far[0] < 80 && far[1] < 80 && far[2] > 200, java.util.Arrays.toString(far));
                r.check("effects: an additive surface adds to the sky instead of covering it",
                        glow[0] > 180 && glow[1] > 70 && glow[1] < 140, java.util.Arrays.toString(glow));
            } finally {
                MemoryUtil.memFree(px);
            }
        });
    }


    // ---- culling, shadow softness and sky lighting --------------------------------------------------------------

    /**
     * Two cubes in view, several things out of it, and a cube above the view that casts a shadow into it.
     */
    private static Scene3D buildCullScene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.lights().add(Light.directional(new Vec3(0.6f, -1f, 0f), Color.WHITE, 1.2f).setCastShadow(true));
        scene.setAmbient(Color.WHITE, 0.2f);
        scene.add(new Node3D("floor", Mesh.plane(40, 40), new Material(Color.WHITE).setRoughness(0.9f)));
        Node3D red = new Node3D("red", Mesh.cube(1f), new Material(Color.RED).setUnlit(true));
        red.setPosition(3f, 0.5f, 2f);
        scene.add(red);
        Material plain = new Material(Color.BLUE);
        Node3D behind = new Node3D("behind", Mesh.cube(1f), plain).setCastShadow(false);
        behind.setPosition(0, 0.5f, 40f);
        Node3D aside = new Node3D("aside", Mesh.cube(1f), plain).setCastShadow(false);
        aside.setPosition(-80f, 0.5f, 0f);
        Node3D field = new Node3D("field", Mesh.cube(1f), plain).setCastShadow(false);
        Instances far = new Instances();
        for (int i = 0; i < 10; i++) far.add(new Vec3(200f + i, 0.5f, 0f));
        field.setInstances(far);
        Node3D caster = new Node3D("caster", Mesh.cube(2f), plain);
        caster.setPosition(-6f, 8f, 0f);
        scene.add(behind).add(aside).add(field).add(caster);
        scene.camera().lookAt(new Vec3(0, 3f, 9f), new Vec3(0, 0.5f, 0)).setFovYDegrees(50);
        return scene;
    }

    private void cullChecks(Scene3D scene, GpuDevice device, RenderTarget target, GpuHarness.Recorder r) {
        r.guard("culling: what is outside the view is left out, and its shadow still falls in", () -> {
            Scene3D.Stats stats = scene.getStats();
            r.check("culling: the floor and the red cube are drawn", stats.nodes() == 2, stats.toString());
            r.check("culling: the cubes behind and beside, the instanced field and the cube above are left out", stats.culled() == 4, stats.toString());
            r.check("culling: only the drawn records are counted", stats.instances() == 2, stats.toString());
            ByteBuffer px = target.readPixels();
            try {
                int[] red = pixelAt(scene, target, px, 3f, 0.5f, 2.5f);
                int[] shadowed = pixelAt(scene, target, px, -1.2f, 0f, 0f);
                int[] lit = pixelAt(scene, target, px, 4f, 0f, -3f);
                r.check("culling: a cube in view is drawn", red[0] > 200 && red[1] < 60, java.util.Arrays.toString(red));
                r.check("culling: a cube above the view still shadows the floor in it", luma(shadowed) < luma(lit) * 0.6,
                        "shadowed " + java.util.Arrays.toString(shadowed) + " lit " + java.util.Arrays.toString(lit));
            } finally {
                MemoryUtil.memFree(px);
            }
            // The same scene turned to look the other way: the cubes that were behind and beside are now in view.
            scene.camera().lookAt(new Vec3(0, 3f, 9f), new Vec3(0, 0.5f, 40f));
            scene.render(device, target, 0f);
            Scene3D.Stats turned = scene.getStats();
            r.check("culling: turning the camera brings the cube behind it into view", turned.nodes() >= 2 && turned.nodes() > 0
                    && turned.culled() >= 3, turned.toString());
        });
    }

    /**
     * A box over a floor with the light straight down, and a small shadow map: the shadow is a square with soft edges.
     */
    private static Scene3D buildSoftShadowScene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.lights().add(Light.directional(new Vec3(0f, -1f, 0f), Color.WHITE, 0.8f).setCastShadow(true));
        scene.setAmbient(Color.WHITE, 0.1f);
        scene.setShadowMapSize(256).setShadowDistance(14f);
        Color grey = new Color(0.6f, 0.6f, 0.6f, 1f);
        scene.add(new Node3D("floor", Mesh.plane(40, 40), new Material(grey).setRoughness(0.9f)));
        Node3D box = new Node3D("box", Mesh.cube(1f), new Material(grey).setRoughness(0.9f));
        box.setPosition(0, 3f, 0);
        box.setScale(new Vec3(2f, 0.4f, 2f));
        scene.add(box);
        scene.camera().lookAt(new Vec3(0, 6f, 9f), new Vec3(0, 0, 0)).setFovYDegrees(40);
        return scene;
    }

    private void softnessChecks(Scene3D scene, GpuDevice device, RenderTarget target, GpuHarness.Recorder r) {
        r.guard("softness: a larger softness widens the shadow's edge", () -> {
            double[] widths = new double[2];
            float[] softness = {1f, 4f};
            for (int pass = 0; pass < 2; pass++) {
                scene.setShadowSoftness(softness[pass]);
                scene.render(device, target, 0f);
                ByteBuffer px = target.readPixels();
                try {
                    // Walk the floor across the shadow's near edge (z = 1) and count the steps that are neither lit nor shadowed.
                    int steps = 400;
                    int[] l = new int[steps];
                    int lo = 255 * 3, hi = 0;
                    for (int i = 0; i < steps; i++) {
                        float z = 0.2f + 2.4f * i / (steps - 1);
                        l[i] = luma(pixelAt(scene, target, px, 0f, 0f, z));
                        lo = Math.min(lo, l[i]);
                        hi = Math.max(hi, l[i]);
                    }
                    int between = 0;
                    for (int i = 0; i < steps; i++) if (l[i] > lo + (hi - lo) * 0.15f && l[i] < hi - (hi - lo) * 0.15f) between++;
                    widths[pass] = between * 2.4 / steps;
                    r.check("softness: the shadow is visible at " + softness[pass], hi - lo > 100, "range " + lo + " to " + hi);
                } finally {
                    MemoryUtil.memFree(px);
                }
            }
            r.check("softness: the edge at 4 is wider than at 1", widths[1] > widths[0] * 2 && widths[1] > widths[0] + 0.1,
                    "edge width in scene units " + widths[0] + " at 1, " + widths[1] + " at 4");
        });
    }

    private static TextureData flat(float r, float g, float b) {
        return TextureData.checker(8, 1, new float[]{r, g, b}, new float[]{r, g, b});
    }

    /**
     * Six faces: +Y red, +X green, -X blue, the rest black.
     */
    private static Skybox cubeSky() {
        return Skybox.cube(flat(0, 1, 0), flat(0, 0, 1), flat(1, 0, 0), flat(0, 0, 0), flat(0, 0, 0), flat(0, 0, 0));
    }

    /**
     * A panorama: a green stripe around +X, a blue one around -X, and elsewhere red above the horizon and black below.
     */
    private static Skybox panoramaSky() {
        int w = 128, h = 64;
        byte[] rgba = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float u = (x + 0.5f) / w;
                int[] c = Math.abs(u - 0.75f) < 0.125f ? new int[]{0, 255, 0}
                        : Math.abs(u - 0.25f) < 0.125f ? new int[]{0, 0, 255}
                        : y < h / 2 ? new int[]{255, 0, 0} : new int[]{0, 0, 0};
                int o = (y * w + x) * 4;
                rgba[o] = (byte) c[0];
                rgba[o + 1] = (byte) c[1];
                rgba[o + 2] = (byte) c[2];
                rgba[o + 3] = (byte) 255;
            }
        }
        return Skybox.equirect(new TextureData(w, h, rgba));
    }

    /**
     * No lights at all: a rough white sphere on the left, a polished metal one on the right, both lit by the sky.
     */
    private static Scene3D buildEnvScene(Skybox sky) {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setSkybox(sky);
        scene.setEnvironmentLighting(1f);
        scene.setAmbient(Color.WHITE, 0f);
        Node3D rough = new Node3D("rough", Mesh.sphere(1f, 48, 24), new Material(Color.WHITE).setMetallic(0f).setRoughness(1f));
        rough.setPosition(-1.6f, 0f, 0f);
        Node3D metal = new Node3D("metal", Mesh.sphere(1f, 48, 24), new Material(Color.WHITE).setMetallic(1f).setRoughness(0.05f));
        metal.setPosition(1.6f, 0f, 0f);
        scene.add(rough).add(metal);
        scene.camera().lookAt(new Vec3(0, 0, 8f), new Vec3(0, 0, 0)).setFovYDegrees(40);
        return scene;
    }

    /**
     * The point of a sphere of radius 1 at (cx, 0, 0) whose normal is n, pulled toward the camera so that it is on the
     * visible side.
     */
    private static int[] onSphere(Scene3D scene, RenderTarget target, ByteBuffer px, float cx, float nx, float ny) {
        float nz = (float) Math.sqrt(Math.max(0.01, 1 - nx * nx - ny * ny));
        return pixelAt(scene, target, px, cx + nx, ny, nz);
    }

    private void envChecks(String name, Scene3D scene, RenderTarget target, GpuHarness.Recorder r, boolean specular) {
        r.guard(name + " sky: surfaces take the light of the sky in front of them", () -> {
            ByteBuffer px = target.readPixels();
            try {
                int[] top = onSphere(scene, target, px, -1.6f, 0f, 0.7f);
                int[] bottom = onSphere(scene, target, px, -1.6f, 0f, -0.7f);
                int[] right = onSphere(scene, target, px, -1.6f, 0.7f, 0f);
                int[] left = onSphere(scene, target, px, -1.6f, -0.7f, 0f);
                String d = " top " + java.util.Arrays.toString(top) + " bottom " + java.util.Arrays.toString(bottom)
                        + " right " + java.util.Arrays.toString(right) + " left " + java.util.Arrays.toString(left);
                // Compared as light, not as the gamma-encoded bytes.
                r.check(name + " sky: a rough surface facing up is lit by the red above", top[0] > 40 && lin(top[0]) > lin(top[1]) * 2 && lin(top[0]) > lin(top[2]) * 2, d);
                r.check(name + " sky: one facing right is lit by the green on that side", right[1] > 40 && lin(right[1]) > lin(right[0]) * 1.5 && lin(right[1]) > lin(right[2]) * 3, d);
                r.check(name + " sky: one facing left is lit by the blue on that side", left[2] > 40 && lin(left[2]) > lin(left[0]) * 1.5 && lin(left[2]) > lin(left[1]) * 3, d);
                r.check(name + " sky: one facing down gets much less red than one facing up", lin(bottom[0]) * 2 < lin(top[0]), d);
                if (specular) {
                    int[] mTop = onSphere(scene, target, px, 1.6f, 0f, 0.7f);
                    int[] mRight = onSphere(scene, target, px, 1.6f, 0.7f, 0f);
                    int[] mLeft = onSphere(scene, target, px, 1.6f, -0.7f, 0f);
                    String m = " top " + java.util.Arrays.toString(mTop) + " right " + java.util.Arrays.toString(mRight) + " left " + java.util.Arrays.toString(mLeft);
                    r.check(name + " sky: a polished metal sphere reflects the sky: up is red", mTop[0] > 100 && mTop[1] < 60 && mTop[2] < 60, m);
                    r.check(name + " sky: its right side reflects the green face", mRight[1] > 100 && mRight[0] < 60 && mRight[2] < 60, m);
                    r.check(name + " sky: its left side reflects the blue face", mLeft[2] > 100 && mLeft[0] < 60 && mLeft[1] < 60, m);
                }
            } finally {
                MemoryUtil.memFree(px);
            }
        });
    }

    private void gradientChecks(Scene3D scene, RenderTarget target, GpuHarness.Recorder r) {
        r.guard("gradient sky: light and reflections", () -> {
            ByteBuffer px = target.readPixels();
            try {
                int[] top = onSphere(scene, target, px, -1.6f, 0f, 0.7f);
                int[] bottom = onSphere(scene, target, px, -1.6f, 0f, -0.7f);
                r.check("gradient sky: a surface facing up is lit by the red top", top[0] > 40 && top[0] > top[2] * 2, java.util.Arrays.toString(top));
                r.check("gradient sky: one facing down is lit by the blue bottom", bottom[2] > 40 && bottom[2] > bottom[0] * 2, java.util.Arrays.toString(bottom));
                int[] mTop = onSphere(scene, target, px, 1.6f, 0f, 0.7f);
                int[] mBottom = onSphere(scene, target, px, 1.6f, 0f, -0.7f);
                r.check("gradient sky: a metal sphere reflects red above and blue below",
                        mTop[0] > 100 && mTop[2] < 60 && mBottom[2] > 100 && mBottom[0] < 60,
                        "top " + java.util.Arrays.toString(mTop) + " bottom " + java.util.Arrays.toString(mBottom));
            } finally {
                MemoryUtil.memFree(px);
            }
        });
    }

    private static int[] pixelAt(Scene3D scene, RenderTarget target, ByteBuffer px, float x, float y, float z) {
        int w = target.width(), h = target.height();
        Camera camera = scene.camera();
        Vec3 ndc = camera.projection((float) w / h).mul(camera.view()).transformPoint(new Vec3(x, y, z));
        int sx = Math.round((ndc.x() * 0.5f + 0.5f) * w), sy = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * h);
        int i = (Math.max(0, Math.min(h - 1, sy)) * w + Math.max(0, Math.min(w - 1, sx))) * 4;
        return new int[]{px.get(i) & 255, px.get(i + 1) & 255, px.get(i + 2) & 255};
    }

    private static double lin(int c) {
        return Math.pow(c / 255.0, 2.2);
    }

    private static int luma(int[] c) {
        return c[0] + c[1] + c[2];
    }

    @FunctionalInterface
    private interface IntFunction3 {
        int[] get(float x, float y, float z);
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
        // Anisotropic filtering averages along the slant even without mipmaps, so the plain texture is only noisy when it is off.
        r.check("device: without mipmaps the distant checker is rougher", device.maxAnisotropy() == 1 ? spread[0] > 40 : spread[0] > spread[1],
                "stddev=" + spread[0] + " (mipmapped " + spread[1] + ", anisotropy " + device.maxAnisotropy() + ")");
        r.check("device: with mipmaps the distant checker is smooth", spread[1] < 15, "stddev=" + spread[1]);
    }
}
