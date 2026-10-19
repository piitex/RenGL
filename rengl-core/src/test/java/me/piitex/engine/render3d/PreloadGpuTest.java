package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Preload;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.TextureData;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link Scene3D#preload}: meshes and textures reach the GPU a few per frame, hidden or not, before they are drawn.
 */
class PreloadGpuTest {
    private static final int VIEW = 128;

    @Test
    void anEmptyPreloadIsDoneAtOnce() {
        Scene3D scene = new Scene3D();
        Preload preload = scene.preload(new Node3D("group"));
        AtomicInteger done = new AtomicInteger();
        preload.onDone(done::incrementAndGet);
        assertTrue(preload.isDone());
        assertEquals(1, done.get());
        assertEquals(1f, preload.getProgress());
        assertFalse(scene.needsFrames());
    }

    @Test
    void sharedMeshesAndTexturesCountOnce() {
        Scene3D scene = new Scene3D();
        Mesh mesh = Mesh.cube(1f);
        TextureData texture = TextureData.solid(1f, 0f, 0f, 1f);
        Node3D root = new Node3D("root");
        for (int i = 0; i < 3; i++) root.add(new Node3D("n" + i, mesh, new Material(texture)));
        Preload preload = scene.preload(root);
        assertEquals(2, preload.getTotal());
        assertFalse(preload.isDone());
        assertTrue(scene.needsFrames());
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
        Scene3D scene = new Scene3D().setPreloadBudget(0f); // one item a frame
        Node3D root = new Node3D("model");
        root.add(new Node3D("a", Mesh.cube(1f), new Material(TextureData.checker(8, 2, new float[]{1, 1, 1}, new float[]{0, 0, 0}))));
        root.add(new Node3D("b", Mesh.sphere(1f, 8, 6), new Material(TextureData.solid(0f, 1f, 0f, 1f))));
        root.add(new Node3D("c", Mesh.plane(2f, 2f), new Material(Color.WHITE)));
        root.find("b").setVisible(false); // hidden nodes upload too
        Preload preload = scene.preload(root);
        AtomicInteger done = new AtomicInteger();
        preload.onDone(done::incrementAndGet);
        List<Float> progress = new ArrayList<>();

        GpuHarness.Run run = GpuHarness.run(api, VIEW, viewport -> viewport.setContent(new Viewport3D.Content() {
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
                progress.add(preload.getProgress());
            }

            @Override
            public void dispose(GpuDevice device) {
                scene.dispose(device);
            }
        }), (device, target, recorder) -> {
            if (device.api() != api) return;
            recorder.check("preload: 3 meshes and 2 textures are 5 items", preload.getTotal() == 5, "total " + preload.getTotal());
            recorder.check("preload: the first frame uploads only part", progress.get(0) < 1f, progress.toString());
            recorder.check("preload: progress never goes back", isSorted(progress), progress.toString());
            recorder.check("preload: finishes", preload.isDone() && preload.getProgress() == 1f, progress.toString());
            recorder.check("preload: onDone ran once", done.get() == 1, "ran " + done.get());
            recorder.check("preload: no more frames wanted", !scene.needsFrames(), "still wants frames");
        });
        boolean usable = run.available() && run.api() == api;
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(DynamicTest.dynamicTest(api + ": preload: runs", () ->
                assumeTrue(usable, run.reason() == null ? "the window ran on " + run.api() : run.reason())));
        if (usable) {
            run.results().forEach((name, failure) -> tests.add(DynamicTest.dynamicTest(api + ": " + name,
                    () -> assertNull(failure, name + ": " + failure))));
        }
        return tests;
    }

    private static boolean isSorted(List<Float> values) {
        for (int i = 1; i < values.size(); i++) if (values.get(i) < values.get(i - 1)) return false;
        return true;
    }
}
