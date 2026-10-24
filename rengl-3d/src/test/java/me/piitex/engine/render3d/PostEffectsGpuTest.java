package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.render3d.scene.TextureData;
import me.piitex.engine.ui.color.Color;
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
 * The finishing effects on a real GPU, on Vulkan and OpenGL: the colour grade, the vignette, the colour fringe and ambient
 * occlusion. Each is judged by what the picture shows, against the same scene without it. Skips where there is no GPU.
 */
class PostEffectsGpuTest {
    private static final int VIEW = 256;

    private record Scenario(String name, Scene3D scene, Check check) {
    }

    private interface Check {
        void run(GpuDevice device, Scene3D scene, RenderTarget target, GpuHarness.Recorder r);
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

    private static int[] rgbAt(RenderTarget target, int x, int y) {
        ByteBuffer data = target.readPixels();
        try {
            int i = (y * target.width() + x) * 4;
            return new int[]{data.get(i) & 255, data.get(i + 1) & 255, data.get(i + 2) & 255};
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static int[] rgbAtWorld(Scene3D scene, RenderTarget target, float x, float y, float z) {
        var camera = scene.camera();
        Vec3 ndc = camera.projection((float) target.width() / target.height()).mul(camera.view()).transformPoint(new Vec3(x, y, z));
        int px = Math.round((ndc.x() * 0.5f + 0.5f) * target.width()), py = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * target.height());
        if (px < 0 || py < 0 || px >= target.width() || py >= target.height()) throw new IllegalStateException("the point " + px + ", " + py + " is outside the view");
        return rgbAt(target, px, py);
    }

    private static int sum(int[] c) {
        return c[0] + c[1] + c[2];
    }

    private static void fill(TextureData texture, int r, int g, int b) {
        texture.edit(px -> {
            for (int i = 0; i < px.length; i += 4) {
                px[i] = (byte) r;
                px[i + 1] = (byte) g;
                px[i + 2] = (byte) b;
                px[i + 3] = (byte) 255;
            }
        });
    }

    private static Scene3D flat() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        return scene;
    }

    /**
     * A square in front of the camera, bigger than the view, drawn as it is (unlit).
     */
    private static Node3D wall(float x0, float x1, TextureData texture) {
        Mesh quad = new Mesh(new float[]{x0, -5, 0, x1, -5, 0, x1, 5, 0, x0, 5, 0}, new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[]{0, 1, 1, 1, 1, 0, 0, 0}, new int[]{0, 1, 2, 0, 2, 3});
        return new Node3D("wall", quad, new Material(Color.WHITE).setUnlit(true).setTexture(texture));
    }

    private static Color rgb(float r, float g, float b) {
        return new Color(r, g, b, 1f);
    }

    // ---- the grade and the vignette --------------------------------------------------------------------------------

    private static Scenario look() {
        TextureData texture = new TextureData(8, 8, new byte[8 * 8 * 4]);
        fill(texture, 200, 100, 50);
        Scene3D scene = flat();
        scene.add(wall(-5, 5, texture));
        scene.camera().lookAt(new Vec3(0, 0, 3), new Vec3(0, 0, 0)).setFovYDegrees(60);
        return new Scenario("the look", scene, (device, s, target, r) -> {
            int[] plain = {0, 0, 0};
            r.guard("look: the plain picture is the texture", () -> {
                int[] c = rgbAt(target, VIEW / 2, VIEW / 2);
                System.arraycopy(c, 0, plain, 0, 3);
                r.check("look: the plain picture is the texture", Math.abs(c[0] - 200) <= 3 && Math.abs(c[1] - 100) <= 3 && Math.abs(c[2] - 50) <= 3,
                        "centre is " + c[0] + ", " + c[1] + ", " + c[2] + ", wanted 200, 100, 50");
            });
            r.guard("look: going through the effects pass changes no colour", () -> {
                s.setBloom(0.0001f);
                s.render(device, target, 1f / 60f);
                int[] c = rgbAt(target, VIEW / 2, VIEW / 2);
                s.setBloom(0f);
                r.check("look: going through the effects pass changes no colour",
                        Math.abs(c[0] - plain[0]) <= 3 && Math.abs(c[1] - plain[1]) <= 3 && Math.abs(c[2] - plain[2]) <= 3,
                        "was " + plain[0] + ", " + plain[1] + ", " + plain[2] + ", is " + c[0] + ", " + c[1] + ", " + c[2]);
            });
            r.guard("look: no saturation is grey", () -> {
                s.setSaturation(0f);
                s.render(device, target, 1f / 60f);
                int[] c = rgbAt(target, VIEW / 2, VIEW / 2);
                s.setSaturation(1f);
                int spread = Math.max(c[0], Math.max(c[1], c[2])) - Math.min(c[0], Math.min(c[1], c[2]));
                r.check("look: no saturation is grey", spread <= 3 && c[0] > 60, "centre is " + c[0] + ", " + c[1] + ", " + c[2]);
            });
            r.guard("look: contrast spreads the tones", () -> {
                s.setContrast(2f);
                fill(texture, 180, 180, 180);
                s.render(device, target, 1f / 60f);
                int[] bright = rgbAt(target, VIEW / 2, VIEW / 2);
                fill(texture, 80, 80, 80);
                s.render(device, target, 1f / 60f);
                int[] dark = rgbAt(target, VIEW / 2, VIEW / 2);
                s.setContrast(1f);
                r.check("look: contrast spreads the tones", bright[0] > 180 + 30 && dark[0] < 80 - 30,
                        "180 became " + bright[0] + ", 80 became " + dark[0]);
            });
            r.guard("look: the shadow tint colours the dark and leaves the bright", () -> {
                s.setShadowTint(rgb(1f, 0f, 0f));
                fill(texture, 30, 30, 30);
                s.render(device, target, 1f / 60f);
                int[] dark = rgbAt(target, VIEW / 2, VIEW / 2);
                fill(texture, 250, 250, 250);
                s.render(device, target, 1f / 60f);
                int[] bright = rgbAt(target, VIEW / 2, VIEW / 2);
                s.setShadowTint(Color.WHITE);
                r.check("look: the shadow tint colours the dark and leaves the bright", dark[0] > 15 && dark[1] < 6 && dark[2] < 6 && bright[1] > 235 && bright[2] > 235,
                        "dark " + dark[0] + ", " + dark[1] + ", " + dark[2] + "; bright " + bright[0] + ", " + bright[1] + ", " + bright[2]);
            });
            r.guard("look: the highlight tint colours the bright and leaves the dark", () -> {
                s.setHighlightTint(rgb(0f, 0f, 1f));
                fill(texture, 250, 250, 250);
                s.render(device, target, 1f / 60f);
                int[] bright = rgbAt(target, VIEW / 2, VIEW / 2);
                fill(texture, 30, 30, 30);
                s.render(device, target, 1f / 60f);
                int[] dark = rgbAt(target, VIEW / 2, VIEW / 2);
                s.setHighlightTint(Color.WHITE);
                r.check("look: the highlight tint colours the bright and leaves the dark", bright[2] > 235 && bright[0] < 6 && bright[1] < 6 && dark[0] > 20 && dark[1] > 20,
                        "bright " + bright[0] + ", " + bright[1] + ", " + bright[2] + "; dark " + dark[0] + ", " + dark[1] + ", " + dark[2]);
            });
            r.guard("look: the vignette darkens the corners and not the middle", () -> {
                s.setVignette(1f);
                fill(texture, 200, 200, 200);
                s.render(device, target, 1f / 60f);
                int middle = rgbAt(target, VIEW / 2, VIEW / 2)[0], corner = rgbAt(target, 4, 4)[0], opposite = rgbAt(target, VIEW - 5, VIEW - 5)[0];
                s.setVignette(0f);
                r.check("look: the vignette darkens the corners and not the middle", middle > 195 && corner < middle * 0.4 && opposite < middle * 0.4,
                        "middle " + middle + ", corners " + corner + " and " + opposite);
            });
        });
    }

    // ---- the colour fringe -----------------------------------------------------------------------------------------

    private static Scenario fringe() {
        TextureData texture = new TextureData(8, 8, new byte[8 * 8 * 4]);
        fill(texture, 255, 255, 255);
        Scene3D scene = flat();
        // A white wall that ends at x = 1.2, which is toward the right edge of the view: a hard edge for the fringe to split.
        scene.add(wall(-5, 1.2f, texture));
        scene.camera().lookAt(new Vec3(0, 0, 3), new Vec3(0, 0, 0)).setFovYDegrees(60);
        return new Scenario("the colour fringe", scene, (device, s, target, r) -> {
            r.guard("fringe: without it red and blue always agree", () -> {
                s.setVignette(0.0001f); // through the effects pass, with nothing else on
                s.render(device, target, 1f / 60f);
                r.check("fringe: without it red and blue always agree", widestSplit(target) <= 2, "red and blue differ by " + widestSplit(target));
            });
            r.guard("fringe: with it the edge splits red from blue", () -> {
                s.setChromaticAberration(0.05f);
                s.render(device, target, 1f / 60f);
                int split = widestSplit(target);
                int[] middle = rgbAt(target, VIEW / 2 - 20, VIEW / 2);
                r.check("fringe: with it the edge splits red from blue", split > 60, "red and blue differ by only " + split + " along the edge");
                r.check("fringe: the middle of the picture is untouched", middle[0] > 250 && middle[1] > 250 && middle[2] > 250,
                        "left of centre is " + middle[0] + ", " + middle[1] + ", " + middle[2]);
            });
        });
    }

    /**
     * The biggest difference between red and blue along the row through the middle, from the wall's edge out to the picture's.
     */
    private static int widestSplit(RenderTarget target) {
        ByteBuffer data = target.readPixels();
        try {
            int worst = 0, y = target.height() / 2;
            for (int x = target.width() * 3 / 4; x < target.width(); x++) {
                int i = (y * target.width() + x) * 4;
                worst = Math.max(worst, Math.abs((data.get(i) & 255) - (data.get(i + 2) & 255)));
            }
            return worst;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    // ---- ambient occlusion -----------------------------------------------------------------------------------------

    private static Scenario corner() {
        Scene3D scene = flat();
        TextureData white = new TextureData(8, 8, new byte[8 * 8 * 4]);
        fill(white, 255, 255, 255);
        // A floor, and a wall standing on it at z = 0 that faces the camera: a crease where they meet.
        scene.add(new Node3D("floor", Mesh.plane(40f, 40f), new Material(Color.WHITE).setUnlit(true).setTexture(white)));
        Mesh quad = new Mesh(new float[]{-8, 0, 0, 8, 0, 0, 8, 6, 0, -8, 6, 0}, new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[]{0, 1, 1, 1, 1, 0, 0, 0}, new int[]{0, 1, 2, 0, 2, 3});
        scene.add(new Node3D("wall", quad, new Material(Color.WHITE).setUnlit(true).setTexture(white).setDoubleSided(true)));
        scene.camera().lookAt(new Vec3(0, 4f, 7f), new Vec3(0, 1.5f, 0)).setFovYDegrees(55);
        return new Scenario("the corner", scene, (device, s, target, r) -> {
            r.guard("occlusion: off, the floor and the wall are the same all over", () -> {
                int crease = sum(rgbAtWorld(s, target, 0, 0, 0.15f)), far = sum(rgbAtWorld(s, target, 0, 0, 2.5f)), high = sum(rgbAtWorld(s, target, 0, 4.4f, 0));
                r.check("occlusion: off, the floor and the wall are the same all over", crease > 750 && far > 750 && high > 750,
                        "near the crease " + crease + ", far floor " + far + ", high on the wall " + high + " of 765");
            });
            r.guard("occlusion: the crease is darker than the open floor", () -> {
                s.setAmbientOcclusion(1.5f);
                s.setAmbientOcclusionRadius(1f);
                s.render(device, target, 1f / 60f);
                int crease = sum(rgbAtWorld(s, target, 0, 0, 0.15f)), far = sum(rgbAtWorld(s, target, 0, 0, 2.5f));
                r.check("occlusion: the crease is darker than the open floor", far > 700 && crease < far - 120,
                        "near the crease " + crease + ", open floor " + far);
            });
            r.guard("occlusion: flat surfaces don't darken themselves", () -> {
                int far = sum(rgbAtWorld(s, target, 0, 0, 2.5f)), high = sum(rgbAtWorld(s, target, 0, 4.4f, 0)), side = sum(rgbAtWorld(s, target, 1.2f, 0, 2.5f));
                r.check("occlusion: flat surfaces don't darken themselves", far > 700 && high > 700 && side > 700,
                        "open floor " + far + ", high on the wall " + high + ", far side of the floor " + side + " of 765");
            });
            r.guard("occlusion: the wall darkens toward the floor too", () -> {
                int low = sum(rgbAtWorld(s, target, 0, 0.15f, 0)), high = sum(rgbAtWorld(s, target, 0, 4.4f, 0));
                r.check("occlusion: the wall darkens toward the floor too", low < high - 100, "low on the wall " + low + ", high " + high);
            });
            r.guard("occlusion: a bigger radius reaches farther", () -> {
                s.setAmbientOcclusionRadius(0.1f);
                s.render(device, target, 1f / 60f);
                int small = sum(rgbAtWorld(s, target, 0, 0, 0.8f));
                s.setAmbientOcclusionRadius(2.5f);
                s.render(device, target, 1f / 60f);
                int big = sum(rgbAtWorld(s, target, 0, 0, 0.8f));
                r.check("occlusion: a bigger radius reaches farther", small > 750 && big < small - 40,
                        "radius 0.1 gives " + small + ", radius 2.5 gives " + big + " a little way from the crease");
            });
            r.guard("occlusion: turned off again, the picture is plain", () -> {
                s.setAmbientOcclusion(0f);
                s.render(device, target, 1f / 60f);
                int crease = sum(rgbAtWorld(s, target, 0, 0, 0.15f));
                r.check("occlusion: turned off again, the picture is plain", crease > 750, "near the crease " + crease);
            });
        });
    }

    // ---- running them ----------------------------------------------------------------------------------------------

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
        for (Scenario scenario : List.of(look(), fringe(), corner())) {
            GpuHarness.Run run = GpuHarness.run(api, VIEW, (Consumer<Viewport3D>) viewport -> viewport.setContent(new SceneContent(scenario.scene())),
                    (device, target, recorder) -> {
                        if (device.api() != api) return;
                        scenario.check().run(device, scenario.scene(), target, recorder);
                    });
            boolean usable = run.available() && run.api() == api;
            tests.add(DynamicTest.dynamicTest(api + ": " + scenario.name() + ": runs", () -> {
                assumeTrue(usable, run.reason() == null ? "the window ran on " + run.api() : run.reason());
            }));
            if (usable) {
                run.results().forEach((name, failure) -> tests.add(DynamicTest.dynamicTest(api + ": " + name,
                        () -> assertNull(failure, name + ": " + failure))));
            }
        }
        return tests;
    }
}
