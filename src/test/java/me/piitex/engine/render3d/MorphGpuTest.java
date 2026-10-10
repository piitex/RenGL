package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.ShadowQuality;
import me.piitex.engine.render3d.scene.Shadows;
import me.piitex.engine.render3d.scene.Skin;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Blend shapes mixed by the vertex shader, on Vulkan and OpenGL: the picture matches a mesh that was mixed on the CPU,
 * changed weights show on the next frame, a node with more shapes switched on than the shader holds still draws right
 * (blended on the CPU), and a morphed mesh's shadow follows its shape. Each of these also runs on a skinned mesh, where the
 * shapes are mixed first and the joints then move the result.
 */
class MorphGpuTest {
    private static AtomicInteger counter(String name) {
        try {
            var field = Class.forName("me.piitex.engine.render3d.scene.SceneRenderer").getDeclaredField(name);
            field.setAccessible(true);
            return (AtomicInteger) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Viewport3D.Content content(Scene3D scene) {
        return new Viewport3D.Content() {
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
        };
    }

    /**
     * The same mesh with the shapes already mixed in: what the GPU's picture must match.
     */
    private static Mesh mixed(Mesh mesh, float[] weights) {
        float[] v = mesh.morph(weights);
        int n = mesh.vertexCount(), s = Mesh.FLOATS_PER_VERTEX;
        float[] pos = new float[n * 3], nor = new float[n * 3], uv = new float[n * 2];
        for (int i = 0; i < n; i++) {
            System.arraycopy(v, i * s, pos, i * 3, 3);
            System.arraycopy(v, i * s + 3, nor, i * 3, 3);
            System.arraycopy(v, i * s + 6, uv, i * 2, 2);
        }
        return new Mesh(pos, nor, uv, mesh.indices());
    }

    /**
     * Skins {@code target} to two joints, the weight sliding from the first at the bottom of {@code shape} to the second
     * at its top. The weights come from {@code shape} so a mesh with the shapes mixed in is skinned exactly as the original.
     */
    private static Mesh skin(Mesh target, Mesh shape) {
        int n = shape.vertexCount();
        float[] v = shape.vertices();
        int[] joints = new int[n * 4];
        float[] weights = new float[n * 4];
        for (int i = 0; i < n; i++) {
            float top = Math.clamp(0.5f + 0.5f * v[i * Mesh.FLOATS_PER_VERTEX + 1], 0f, 1f);
            joints[i * 4 + 1] = 1;
            weights[i * 4] = 1f - top;
            weights[i * 4 + 1] = top;
        }
        return target.setSkinning(joints, weights);
    }

    /**
     * A sphere with three shapes: one swells it and bends the normals, one squashes it, one slides it right.
     */
    private static Mesh sphereWithShapes() {
        Mesh sphere = Mesh.sphere(1f, 24, 12);
        int n = sphere.vertexCount();
        float[] v = sphere.vertices();
        float[] swell = new float[n * 3], swellNormals = new float[n * 3], squash = new float[n * 3], slide = new float[n * 3];
        for (int i = 0; i < n; i++) {
            int o = i * Mesh.FLOATS_PER_VERTEX;
            for (int a = 0; a < 3; a++) swell[i * 3 + a] = v[o + 3 + a] * 0.4f;
            swellNormals[i * 3] = 0.7f * (float) Math.sin(i);
            swellNormals[i * 3 + 1] = 0.5f;
            squash[i * 3 + 1] = -0.5f * v[o + 1];
            slide[i * 3] = 0.8f;
        }
        return sphere.setMorphTargets(List.of(new Mesh.MorphTarget("swell", swell, swellNormals), new Mesh.MorphTarget("squash", squash, null),
                new Mesh.MorphTarget("slide", slide, null)), null);
    }

    /**
     * A cube with twenty shapes, more than the shader can hold at once.
     */
    private static Mesh cubeWithManyShapes() {
        Mesh plane = Mesh.cube(1.5f);
        List<Mesh.MorphTarget> targets = new ArrayList<>();
        for (int k = 0; k < 20; k++) {
            float[] offsets = new float[plane.vertexCount() * 3];
            for (int i = 0; i < plane.vertexCount(); i++) offsets[i * 3] = 0.03f * (k + 1);
            targets.add(new Mesh.MorphTarget("shape " + k, offsets, null));
        }
        return plane.setMorphTargets(targets, null);
    }

    private static Scene3D lit(Mesh mesh, String name, boolean skinned) {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.lights().add(Light.directional(new Vec3(-1, -1, -1), Color.WHITE, 1.2f));
        scene.setBackground(Color.BLACK);
        Node3D node = new Node3D(name, mesh, new Material(new Color(1f, 0.6f, 0.2f, 1f)).setDoubleSided(true));
        if (skinned) {
            // The first joint stays put, the second is turned and lifted: the top of the mesh follows it.
            Node3D bottom = new Node3D("bottom"), top = new Node3D("top");
            top.setPosition(0.4f, 0.3f, 0f).setRotation(Quat.fromAxisAngle(Vec3.Z, 0.5f));
            scene.add(bottom);
            scene.add(top);
            node.setSkin(new Skin(List.of(bottom, top), List.of(Mat4.identity(), Mat4.identity())));
        }
        scene.add(node);
        scene.camera().lookAt(new Vec3(0, 1.5f, 6f), new Vec3(0.3f, 0, 0)).setFovYDegrees(50);
        return scene;
    }

    /**
     * How many pixels of the two pictures differ by more than a few levels in any channel.
     */
    private static int differing(ByteBuffer a, ByteBuffer b, int pixels) {
        int count = 0;
        for (int i = 0; i < pixels; i++) {
            for (int c = 0; c < 3; c++) {
                if (Math.abs((a.get(i * 4 + c) & 255) - (b.get(i * 4 + c) & 255)) > 12) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static ByteBuffer picture(Scene3D scene, GpuDevice device, RenderTarget target) {
        scene.render(device, target, 0f);
        return target.readPixels();
    }

    /**
     * Draws the morphed mesh at each of the weights, and the same mesh mixed on the CPU, and compares the two.
     */
    private static void compare(String name, Supplier<Mesh> make, List<float[]> weights, boolean onGpu, boolean skinned,
                                GpuDevice device, RenderTarget target, GpuHarness.Recorder r) {
        r.guard(name, () -> {
            Mesh mesh = make.get();
            if (skinned) skin(mesh, mesh);
            Scene3D morphed = lit(mesh, "node", skinned);
            morphed.init(device);
            Scene3D untouched = lit(mesh, "node", skinned);
            untouched.init(device);
            int pixels = target.width() * target.height();
            AtomicInteger gpu = counter("GPU_MORPHS"), cpu = counter("CPU_MORPHS");
            int gpuBefore = gpu.get(), cpuBefore = cpu.get();
            try {
                for (float[] w : weights) {
                    morphed.root().find("node").setMorphWeights(w);
                    Mesh blended = mixed(mesh, w);
                    Scene3D plain = lit(skinned ? skin(blended, mesh) : blended, "plain", skinned);
                    plain.init(device);
                    ByteBuffer expected = null, got = null, flat = null;
                    try {
                        expected = picture(plain, device, target);
                        got = picture(morphed, device, target);
                        int off = differing(expected, got, pixels);
                        r.check(name + ": the picture matches the CPU blend at " + java.util.Arrays.toString(w), off < pixels / 300, off + " of " + pixels + " pixels differ");
                        flat = picture(untouched, device, target);
                        int moved = differing(flat, got, pixels);
                        r.check(name + ": the shapes change the picture at " + java.util.Arrays.toString(w), moved > pixels / 100, moved + " pixels changed");
                    } finally {
                        for (ByteBuffer b : new ByteBuffer[]{expected, got, flat}) if (b != null) MemoryUtil.memFree(b);
                        plain.dispose(device);
                    }
                }
            } finally {
                morphed.dispose(device);
                untouched.dispose(device);
            }
            r.check(name + (onGpu ? ": mixed on the GPU" : ": mixed on the CPU"), onGpu ? gpu.get() > gpuBefore && cpu.get() == cpuBefore
                    : cpu.get() > cpuBefore && gpu.get() == gpuBefore, "gpu " + (gpu.get() - gpuBefore) + ", cpu " + (cpu.get() - cpuBefore));
        });
    }

    /**
     * A small square above a floor, with a shape that slides it to the right: the floor is dark under it where it is.
     */
    private static void shadow(String label, boolean skinned, GpuDevice device, RenderTarget target, GpuHarness.Recorder r) {
        r.guard(label, () -> {
            Mesh square = Mesh.plane(2, 2);
            float[] slide = new float[square.vertexCount() * 3];
            for (int i = 0; i < square.vertexCount(); i++) slide[i * 3] = 4f;
            square.setMorphTargets(List.of(new Mesh.MorphTarget("slide", slide, null)), null);
            Scene3D scene = new Scene3D();
            scene.lights().clear();
            scene.lights().add(Light.directional(new Vec3(0.01f, -1, 0.01f), Color.WHITE, 1.2f).setShadows(Shadows.of(ShadowQuality.HIGH)));
            scene.setShadowDistance(40f);
            scene.setBackground(Color.BLACK);
            scene.add(new Node3D("floor", Mesh.plane(24, 24), new Material(Color.WHITE)));
            Node3D mover = new Node3D("mover", square, new Material(Color.WHITE).setDoubleSided(true));
            mover.setPosition(-2f, 2f, -3f);
            if (skinned) {
                // A skinned node is placed by its joint, not by its own position.
                Node3D joint = new Node3D("joint");
                joint.setPosition(-2f, 2f, -3f);
                scene.add(joint);
                int[] joints = new int[square.vertexCount() * 4];
                float[] weights = new float[square.vertexCount() * 4];
                for (int i = 0; i < square.vertexCount(); i++) weights[i * 4] = 1f;
                square.setSkinning(joints, weights);
                mover.setSkin(new Skin(List.of(joint), List.of(Mat4.identity())));
            }
            scene.add(mover);
            scene.camera().lookAt(new Vec3(0, 12f, 6f), new Vec3(0, 0, -3f)).setFovYDegrees(50);
            scene.init(device);
            AtomicInteger gpu = counter("GPU_MORPHS");
            int before = gpu.get();
            try {
                int[] under = new int[2], beside = new int[2];
                for (int pass = 0; pass < 2; pass++) {
                    mover.setMorphWeights(pass);
                    ByteBuffer px = picture(scene, device, target);
                    try {
                        // The square spans x from -3 to -1 at weight 0 and 1 to 3 at weight 1; its shadow falls straight below.
                        under[pass] = luma(scene, target, px, pass == 0 ? -2f : 2f, 0f, -3f);
                        beside[pass] = luma(scene, target, px, pass == 0 ? 2f : -2f, 0f, -3f);
                    } finally {
                        MemoryUtil.memFree(px);
                    }
                }
                for (int pass = 0; pass < 2; pass++) {
                    r.check(label + ": the floor is darker under the square than beside it at weight " + pass, under[pass] + 60 < beside[pass],
                            "under " + under[pass] + ", beside " + beside[pass]);
                }
            } finally {
                scene.dispose(device);
            }
            r.check(label + ": the square was mixed on the GPU", gpu.get() > before, "writes " + (gpu.get() - before));
        });
    }

    private static int luma(Scene3D scene, RenderTarget target, ByteBuffer px, float x, float y, float z) {
        int w = target.width(), h = target.height();
        var camera = scene.camera();
        Vec3 ndc = camera.projection((float) w / h).mul(camera.view()).transformPoint(new Vec3(x, y, z));
        int sx = Math.clamp(Math.round((ndc.x() * 0.5f + 0.5f) * w), 0, w - 1), sy = Math.clamp(Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * h), 0, h - 1);
        int i = (sy * w + sx) * 4;
        return (px.get(i) & 255) + (px.get(i + 1) & 255) + (px.get(i + 2) & 255);
    }

    private void run(RenderAPI api) {
        Scene3D host = new Scene3D();
        GpuHarness.Run run = GpuHarness.run(api, 192, (Consumer<Viewport3D>) viewport -> viewport.setContent(content(host)), (device, target, recorder) -> {
            compare("sphere", MorphGpuTest::sphereWithShapes, List.of(new float[]{0.6f, 0.5f, 0.4f}, new float[]{1f, 0f, 0.7f}, new float[]{0f, 1f, 0f}),
                    true, false, device, target, recorder);
            compare("many shapes", MorphGpuTest::cubeWithManyShapes, List.of(fill(20, 0.5f)), false, false, device, target, recorder);
            shadow("shadow", false, device, target, recorder);
            compare("skinned sphere", MorphGpuTest::sphereWithShapes, List.of(new float[]{0.6f, 0.5f, 0.4f}, new float[]{1f, 0f, 0.7f}, new float[]{0f, 1f, 0f}),
                    true, true, device, target, recorder);
            compare("skinned, many shapes", MorphGpuTest::cubeWithManyShapes, List.of(fill(20, 0.5f)), false, true, device, target, recorder);
            shadow("skinned shadow", true, device, target, recorder);
        });
        assumeTrue(run.available() && run.api() == api, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        run.results().forEach((name, failure) -> assertNull(failure, name + ": " + failure));
    }

    private static float[] fill(int count, float value) {
        float[] out = new float[count];
        java.util.Arrays.fill(out, value);
        return out;
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
