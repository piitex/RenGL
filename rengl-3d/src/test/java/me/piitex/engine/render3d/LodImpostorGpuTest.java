package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Instances;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Picture cards on a real GPU on both APIs: a card drawn in place of a mesh covers the same pixels in about the same colours,
 * hides behind what is in front of the mesh, is picked on its own when the mesh is small enough, and a field of them costs
 * two triangles each.
 */
class LodImpostorGpuTest {
    private static final int VIEW = 192;
    private static final Color SKY = new Color(0.1f, 0.15f, 0.3f, 1f);

    /** A cone lying on its side and turned, so every view of it is different, with one simpler level. */
    private static Mesh cone() {
        Mesh cone = Mesh.cone(1f, 2.5f, 48);
        cone.addLod(Mesh.cone(1f, 2.5f, 12));
        cone.setImpostor(true);
        return cone;
    }

    private static Scene3D scene() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(SKY);
        scene.setSkybox(Skybox.gradient(SKY, SKY, SKY));
        scene.setAmbient(Color.WHITE, 0.3f);
        scene.setLodTransition(Scene3D.LodTransition.NONE);
        scene.lights().add(Light.directional(new Vec3(-0.6f, -1f, -0.5f), Color.WHITE, 1.1f));
        Node3D cone = new Node3D("cone", cone(), new Material(new Color(0.9f, 0.55f, 0.2f, 1f)));
        cone.setRotation(Quat.fromAxisAngle(new Vec3(0.3f, 0.2f, 1f).normalized(), 1.1f));
        scene.add(cone);
        // A box in front of the cone's left half: the card must hide behind it as the mesh does.
        scene.add(new Node3D("box", Mesh.cube(1f), new Material(new Color(0.3f, 0.8f, 0.35f, 1f))).setScale(new Vec3(1.2f, 4f, 0.2f)).setPosition(-0.9f, 0, 1.6f));
        scene.camera().lookAt(new Vec3(0, 0.5f, 9f), Vec3.ZERO).setFovYDegrees(40).setClip(0.1f, 2000f);
        return scene;
    }

    private void run(RenderAPI api) {
        Scene3D scene = scene();
        GpuHarness.Run run = GpuHarness.run(api, VIEW, (Consumer<Viewport3D>) viewport -> viewport.setContent(new Viewport3D.Content() {
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
        }), (device, target, recorder) -> {
            if (device.api() != api) return;
            recorder.guard("picture cards", () -> {
                Node3D cone = scene.root().find("cone");
                int card = cone.getMesh().getLods().size();
                // Up close, so the card is big enough to compare pixel by pixel; forced, as it would not be picked here.
                cone.setForcedLod(0);
                scene.render(device, target, 1f / 60f);
                ByteBuffer mesh = target.readPixels();
                cone.setForcedLod(card);
                scene.render(device, target, 1f / 60f);
                ByteBuffer drawn = target.readPixels();
                Scene3D.Stats stats = scene.getStats();
                try {
                    int n = target.width() * target.height();
                    int meshCover = 0, cardCover = 0, both = 0;
                    long colourDiff = 0;
                    int boxMesh = 0, boxCard = 0;
                    for (int p = 0; p < n; p++) {
                        boolean inMesh = isCone(mesh, p), inCard = isCone(drawn, p);
                        if (inMesh) meshCover++;
                        if (inCard) cardCover++;
                        if (inMesh && inCard) {
                            both++;
                            for (int c = 0; c < 3; c++) colourDiff += Math.abs((mesh.get(p * 4 + c) & 255) - (drawn.get(p * 4 + c) & 255));
                        }
                        if (isBox(mesh, p)) boxMesh++;
                        if (isBox(drawn, p)) boxCard++;
                    }
                    recorder.check("the card is drawn as one record of its own level, two triangles", stats.recordsAtLevel(card) == 1 && stats.triangles() == 2 + 12,
                            "levels " + java.util.Arrays.toString(stats.levelRecords()) + ", triangles " + stats.triangles());
                    recorder.check("the cone covers a good part of the picture", meshCover > n / 30, meshCover + " of " + n);
                    recorder.check("the card covers the pixels the mesh does", both > meshCover * 0.85 && cardCover < meshCover * 1.15,
                            "mesh " + meshCover + ", card " + cardCover + ", both " + both);
                    recorder.check("in about the same colours", colourDiff / Math.max(1, both * 3L) < 30,
                            "mean difference " + colourDiff / Math.max(1, both * 3L));
                    recorder.check("and it hides behind the box as the mesh does", Math.abs(boxCard - boxMesh) < boxMesh * 0.08 + 20,
                            "box pixels: mesh " + boxMesh + ", card " + boxCard);
                } finally {
                    MemoryUtil.memFree(mesh);
                    MemoryUtil.memFree(drawn);
                }

                // Picked on its own: far off the card, near the mesh.
                cone.setForcedLod(-1);
                scene.camera().lookAt(new Vec3(0, 0.5f, 400f), Vec3.ZERO);
                scene.render(device, target, 1f / 60f);
                recorder.check("far off the card is picked", scene.getStats().recordsAtLevel(card) == 1,
                        java.util.Arrays.toString(scene.getStats().levelRecords()));
                scene.camera().lookAt(new Vec3(0, 0.5f, 6f), Vec3.ZERO);
                scene.render(device, target, 1f / 60f);
                recorder.check("near, the mesh", scene.getStats().recordsAtLevel(0) >= 1 && scene.getStats().recordsAtLevel(card) == 0,
                        java.util.Arrays.toString(scene.getStats().levelRecords()));

                // A field of them in the distance: two triangles a card, one set of pictures.
                Instances field = new Instances();
                for (int i = 0; i < 400; i++) field.add(new Vec3((i % 20) * 6f - 57f, 0f, -300f - (i / 20) * 6f));
                Node3D forest = new Node3D("forest", cone.getMesh(), cone.getMaterial()).setInstances(field);
                scene.add(forest);
                scene.camera().lookAt(new Vec3(0, 20f, 0f), new Vec3(0, 0, -340f));
                scene.render(device, target, 1f / 60f);
                Scene3D.Stats far = scene.getStats();
                recorder.check("the field is all cards", far.recordsAtLevel(card) >= 300, java.util.Arrays.toString(far.levelRecords()));
                recorder.check("costing two triangles each", far.triangles() <= 2L * far.recordsAtLevel(card) + 48L * (far.instances() - far.recordsAtLevel(card)) + 100,
                        far.triangles() + " triangles for " + java.util.Arrays.toString(far.levelRecords()));
                ByteBuffer px = target.readPixels();
                try {
                    int coned = 0;
                    for (int p = 0; p < target.width() * target.height(); p++) if (isCone(px, p)) coned++;
                    recorder.check("and they show", coned > 100, coned + " pixels of cone");
                } finally {
                    MemoryUtil.memFree(px);
                }
            });
        });
        assumeTrue(run.available() && run.api() == api, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        run.results().forEach((name, failure) -> assertNull(failure, name + ": " + failure));
    }

    /** Orange, lit or in shade: red well above blue. */
    private static boolean isCone(ByteBuffer px, int p) {
        int r = px.get(p * 4) & 255, g = px.get(p * 4 + 1) & 255, b = px.get(p * 4 + 2) & 255;
        return r > b + 40 && r > g;
    }

    /** Green: green well above red and blue. */
    private static boolean isBox(ByteBuffer px, int p) {
        int r = px.get(p * 4) & 255, g = px.get(p * 4 + 1) & 255, b = px.get(p * 4 + 2) & 255;
        return g > r + 30 && g > b + 30;
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
