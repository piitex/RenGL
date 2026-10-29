package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Hit;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.LightAnimation;
import me.piitex.engine.render3d.scene.LightPattern;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.render3d.scene.TextureData;
import me.piitex.engine.render3d.scene.UiScreen;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a cyberpunk scene needs, on a real GPU on Vulkan and OpenGL: a texture that changes while it is on screen (an
 * animated screen), a 2D interface on a surface that is clicked and typed into by aiming at it, lights and glowing surfaces that
 * blink, and more lights than the old limit of eight. Skips where there is no GPU, like the other GPU tests.
 */
class LiveScreenGpuTest {
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

    /**
     * The red, green and blue at a pixel of a target's picture, summed with the others as a brightness.
     */
    private static int[] viewAt(RenderTarget target, int x, int y) {
        float scale = (float) target.width() / VIEW;
        return rgbAt(target, (int) ((x + 0.5f) * scale), (int) ((y + 0.5f) * scale));
    }

    /**
     * The colour at a pixel of the target itself. The target is bigger than {@code VIEW} where the screen is Retina, so the
     * places in this file's checks, given in {@code VIEW} pixels, go through {@link #viewAt}.
     */
    private static int[] rgbAt(RenderTarget target, int x, int y) {
        ByteBuffer data = target.readPixels();
        try {
            int i = (y * target.width() + x) * 4;
            return new int[]{data.get(i) & 255, data.get(i + 1) & 255, data.get(i + 2) & 255};
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static int luma(Scene3D scene, RenderTarget target, float x, float y, float z) {
        var camera = scene.camera();
        Vec3 ndc = camera.projection((float) target.width() / target.height()).mul(camera.view()).transformPoint(new Vec3(x, y, z));
        int sx = Math.round((ndc.x() * 0.5f + 0.5f) * target.width()), sy = Math.round((1 - (ndc.y() * 0.5f + 0.5f)) * target.height());
        int[] c = rgbAt(target, sx, sy);
        return c[0] + c[1] + c[2];
    }

    // ---- an animated screen ----------------------------------------------------------------------------------------

    private static TextureData fill(TextureData texture, int r, int g, int b) {
        texture.edit(px -> {
            for (int i = 0; i < px.length; i += 4) {
                px[i] = (byte) r;
                px[i + 1] = (byte) g;
                px[i + 2] = (byte) b;
                px[i + 3] = (byte) 255;
            }
        });
        return texture;
    }

    /**
     * A square from -1 to 1 facing +z, with the picture's top-left corner at the top left.
     */
    private static Mesh quad() {
        return new Mesh(new float[]{-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0}, new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[]{0, 1, 1, 1, 1, 0, 0, 0}, new int[]{0, 1, 2, 0, 2, 3});
    }

    private static Scenario screen() {
        TextureData texture = fill(new TextureData(64, 64, new byte[64 * 64 * 4]), 255, 0, 0);
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        // A square facing the camera, drawn as it is (unlit), with the texture on it.
        scene.add(new Node3D("screen", quad(), new Material(Color.WHITE).setUnlit(true).setTexture(texture)));
        scene.camera().lookAt(new Vec3(0, 0, 3), new Vec3(0, 0, 0)).setFovYDegrees(60);
        return new Scenario("an animated screen", scene, (device, s, target, r) -> {
            r.guard("screen: shows the first picture", () -> {
                int[] c = viewAt(target, VIEW / 2, VIEW / 2);
                r.check("screen: shows the first picture", c[0] > 200 && c[1] < 40 && c[2] < 40, "centre is " + c[0] + ", " + c[1] + ", " + c[2] + ", wanted red");
            });
            r.guard("screen: shows a changed picture on the next frame", () -> {
                fill(texture, 0, 255, 0);
                s.render(device, target, 1f / 60f);
                int[] c = viewAt(target, VIEW / 2, VIEW / 2);
                r.check("screen: shows a changed picture on the next frame", c[1] > 200 && c[0] < 40 && c[2] < 40, "centre is " + c[0] + ", " + c[1] + ", " + c[2] + ", wanted green");
            });
            r.guard("screen: keeps changing, many times", () -> {
                boolean ok = true;
                String seen = "";
                for (int i = 0; i < 12 && ok; i++) {
                    boolean blue = i % 2 == 0;
                    fill(texture, 0, 0, blue ? 255 : 0);
                    s.render(device, target, 1f / 60f);
                    int[] c = viewAt(target, VIEW / 2, VIEW / 2);
                    ok = blue ? c[2] > 200 : c[2] < 40;
                    seen = i + ": " + c[0] + ", " + c[1] + ", " + c[2];
                }
                r.check("screen: keeps changing, many times", ok, "stopped following the texture at update " + seen);
            });
        });
    }

    // ---- a clickable panel -----------------------------------------------------------------------------------------

    /**
     * A container on the same square: red, with a blue button in its top right quarter that turns green when clicked. The
     * clicks are aimed with {@link Scene3D#pick}, as a game would aim the mouse.
     */
    private static Scenario panel() {
        Container ui = new Container(64, 64);
        ui.getStyling().setBackgroundColor(new Color(1f, 0f, 0f, 1f));
        Container button = new Container(32, 0, 32, 32);
        button.getStyling().setBackgroundColor(new Color(0f, 0f, 1f, 1f));
        int[] clicks = {0};
        button.onMouseClick(e -> {
            clicks[0]++;
            button.getStyling().setBackgroundColor(new Color(0f, 1f, 0f, 1f));
        });
        ui.addElement(button);
        UiScreen screen = new UiScreen(ui);
        screen.refresh();

        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        Node3D monitor = new Node3D("monitor", quad(), new Material(Color.WHITE).setUnlit(true).setTexture(screen.texture()));
        scene.add(monitor);
        scene.camera().lookAt(new Vec3(0, 0, 3), new Vec3(0, 0, 0)).setFovYDegrees(60);

        // Where the square's top right and bottom right quarters, and its left half, are in the 256 pixel picture.
        int topRightX = 165, topRightY = 91, bottomRightY = 165, leftX = 91;
        return new Scenario("a clickable panel", scene, (device, s, target, r) -> {
            r.guard("panel: shows the container, top row first", () -> {
                int[] button1 = viewAt(target, topRightX, topRightY), below = viewAt(target, topRightX, bottomRightY), left = viewAt(target, leftX, 128);
                r.check("panel: shows the container, top row first",
                        button1[2] > 200 && button1[0] < 40 && below[0] > 200 && below[2] < 40 && left[0] > 200 && left[2] < 40,
                        "button " + button1[0] + ", " + button1[1] + ", " + button1[2] + " (wanted blue), below it " + below[0] + ", " + below[1] + ", " + below[2]
                                + " and left " + left[0] + ", " + left[1] + ", " + left[2] + " (wanted red)");
            });
            r.guard("panel: a click on bare background is left to the game", () -> {
                Hit hit = s.pick(topRightX, bottomRightY, VIEW, VIEW);
                boolean found = hit != null && hit.node() == monitor;
                boolean taken = found && screen.click(hit, monitor);
                r.check("panel: a click on bare background is left to the game", found && !taken && clicks[0] == 0,
                        "found the screen " + found + ", clicked something " + taken + ", button clicks " + clicks[0]);
            });
            r.guard("panel: a click on the button reaches it", () -> {
                Hit hit = s.pick(topRightX, topRightY, VIEW, VIEW);
                boolean found = hit != null && hit.node() == monitor;
                boolean taken = found && screen.click(hit, monitor);
                r.check("panel: a click on the button reaches it", found && taken && clicks[0] == 1,
                        "found the screen " + found + ", clicked something " + taken + ", button clicks " + clicks[0]);
            });
            r.guard("panel: what the click changed shows on the screen", () -> {
                screen.refresh();
                s.render(device, target, 1f / 60f);
                int[] c = viewAt(target, topRightX, topRightY);
                int[] other = viewAt(target, leftX, 128);
                r.check("panel: what the click changed shows on the screen", c[1] > 200 && c[0] < 40 && c[2] < 40 && other[0] > 200,
                        "button is " + c[0] + ", " + c[1] + ", " + c[2] + " (wanted green), the rest " + other[0] + ", " + other[1] + ", " + other[2]);
            });
        });
    }

    // ---- typing on a panel -----------------------------------------------------------------------------------------

    /**
     * Every byte of a target's picture, top row first.
     */
    private static byte[] picture(RenderTarget target) {
        ByteBuffer data = target.readPixels();
        try {
            byte[] bytes = new byte[target.width() * target.height() * 4];
            data.get(0, bytes);
            return bytes;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /**
     * How many pixels in the box (x0, y0 to x1, y1, the end excluded) differ between two pictures of the same target.
     */
    private static int changedIn(byte[] before, byte[] after, int x0, int y0, int x1, int y1) {
        int changed = 0;
        int side = (int) Math.round(Math.sqrt(before.length / 4.0));
        float scale = (float) side / VIEW;
        for (int y = (int) (y0 * scale); y < (int) (y1 * scale); y++) {
            for (int x = (int) (x0 * scale); x < (int) (x1 * scale); x++) {
                int i = (y * side + x) * 4;
                if (before[i] != after[i] || before[i + 1] != after[i + 1] || before[i + 2] != after[i + 2]) changed++;
            }
        }
        return changed;
    }

    /**
     * A container with a text field near the top, on the same square. The keyboard is given by aiming at the field with
     * {@link Scene3D#pick}, and what is typed is sent to the screen the way a game's key handler would.
     */
    private static Scenario typing() {
        Container ui = new Container(64, 64);
        ui.getStyling().setBackgroundColor(new Color(1f, 0f, 0f, 1f));
        TextFieldOverlay field = new TextFieldOverlay(4, 4, 56, 20);
        ui.addElement(field);
        UiScreen screen = new UiScreen(ui, 2f);
        screen.refresh();

        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        Node3D monitor = new Node3D("monitor", quad(), new Material(Color.WHITE).setUnlit(true).setTexture(screen.texture()));
        scene.add(monitor);
        scene.camera().lookAt(new Vec3(0, 0, 3), new Vec3(0, 0, 0)).setFovYDegrees(60);

        // The field's middle, and bare background below it, in the 256 pixel picture. The field covers x 63 to 192 and y 63 to 110.
        int fieldX = 128, fieldY = 86, bareY = 165;
        return new Scenario("typing on a panel", scene, (device, s, target, r) -> {
            r.guard("typing: a click on the field gives it the keyboard", () -> {
                Hit hit = s.pick(fieldX, fieldY, VIEW, VIEW);
                boolean found = hit != null && hit.node() == monitor;
                boolean taken = found && screen.click(hit, monitor);
                r.check("typing: a click on the field gives it the keyboard", found && taken && screen.focused() == field && field.isFocused(),
                        "found the screen " + found + ", taken " + taken + ", has the keyboard " + screen.hasFocus());
            });
            byte[][] shots = new byte[2][];
            r.guard("typing: typed text shows on the screen", () -> {
                screen.refresh();
                s.render(device, target, 1f / 60f);
                shots[0] = picture(target);
                for (char c : "WWWW".toCharArray()) screen.typeChar(c);
                screen.refresh();
                s.render(device, target, 1f / 60f);
                shots[1] = picture(target);
                int inField = changedIn(shots[0], shots[1], 70, 66, 185, 106), elsewhere = changedIn(shots[0], shots[1], 70, 130, 185, 190);
                r.check("typing: typed text shows on the screen", "WWWW".equals(field.getText()) && inField > 40 && elsewhere == 0,
                        "text '" + field.getText() + "', pixels changed in the field " + inField + " (wanted many) and below it " + elsewhere + " (wanted none)");
            });
            r.guard("typing: a key edits it and the screen follows", () -> {
                boolean used = true;
                for (int i = 0; i < 4; i++) used &= screen.key(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_PRESS, 0);
                screen.refresh();
                s.render(device, target, 1f / 60f);
                byte[] erased = picture(target);
                int inField = changedIn(shots[1], erased, 70, 66, 185, 106);
                r.check("typing: a key edits it and the screen follows", used && field.getText().isEmpty() && inField > 40,
                        "used " + used + ", text '" + field.getText() + "', pixels changed in the field " + inField + " (wanted many)");
            });
            r.guard("typing: a click on bare background lets go of the keyboard", () -> {
                Hit hit = s.pick(fieldX, bareY, VIEW, VIEW);
                boolean found = hit != null && hit.node() == monitor;
                boolean taken = found && screen.click(hit, monitor);
                r.check("typing: a click on bare background lets go of the keyboard", found && !taken && !screen.hasFocus() && !field.isFocused(),
                        "found the screen " + found + ", taken " + taken + ", has the keyboard " + screen.hasFocus());
            });
        });
    }

    // ---- more lights than the old limit --------------------------------------------------------------------------

    private static Scenario manyLights() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setAmbient(Color.WHITE, 0f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.add(new Node3D("floor", Mesh.plane(80f, 80f), new Material(Color.WHITE).setRoughness(0.9f)));
        int count = 40;
        for (int i = 0; i < count; i++) {
            scene.lights().add(Light.point(new Vec3(-19.5f + i, 1.5f, 0), Color.WHITE, 3f, 1.6f));
        }
        scene.camera().lookAt(new Vec3(0, 40f, 0.01f), new Vec3(0, 0, 0)).setFovYDegrees(60);
        return new Scenario("forty lights", scene, (device, s, target, r) -> {
            for (int i : new int[]{0, 7, 8, 20, 39}) {
                String name = "lights: light number " + i + " lights the floor under it";
                r.guard(name, () -> {
                    int under = luma(s, target, -19.5f + i, 0, 0), away = luma(s, target, -19.5f + i, 0, 9f);
                    r.check(name, under > away + 60, "under it " + under + ", away from every light " + away);
                });
            }
        });
    }

    // ---- blinking lights -------------------------------------------------------------------------------------------

    /**
     * A wall lit by a lamp, and a bulb that glows by itself, both driven by one {@link LightAnimation}. The pattern is
     * a function the check controls, so the brightness it asks for is known; the frames before the check are real
     * viewport frames, and show whether the scene itself advances the animation.
     */
    private static Scenario blinking() {
        float[] asked = {1f};
        List<Float> times = new ArrayList<>();
        LightPattern follow = time -> {
            times.add(time);
            return asked[0];
        };
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setAmbient(Color.WHITE, 0f);
        scene.setSkybox(Skybox.gradient(Color.BLACK, Color.BLACK, Color.BLACK));
        scene.add(new Node3D("wall", quad(), new Material(Color.WHITE).setRoughness(0.9f)).setScale(3f));
        Material bulb = new Material(Color.BLACK).setEmissive(0.5f, 0.5f, 0.5f);
        scene.add(new Node3D("bulb", quad(), bulb).setPosition(2f, 0f, 0.1f).setScale(0.4f));
        Light lamp = Light.point(new Vec3(-1.5f, 0f, 1.5f), Color.WHITE, 0.4f, 8f);
        scene.lights().add(lamp);
        LightAnimation animation = LightAnimation.of(follow).light(lamp).glow(bulb);
        scene.animate(animation);
        scene.camera().lookAt(new Vec3(0, 0, 6), new Vec3(0, 0, 0)).setFovYDegrees(60);
        return new Scenario("a blinking lamp and bulb", scene, (device, s, target, r) -> {
            int[] lit = new int[2];
            r.guard("blinking: the scene advances the animation on every frame it draws", () -> {
                boolean forward = times.size() >= 5 && times.get(times.size() - 1) > times.get(0);
                for (int i = 1; i < times.size(); i++) forward &= times.get(i) >= times.get(i - 1);
                r.check("blinking: the scene advances the animation on every frame it draws", forward,
                        "the pattern was asked " + times.size() + " times, at " + times);
            });
            r.guard("blinking: the lamp and the bulb are lit while the pattern is at full", () -> {
                lit[0] = luma(s, target, -1.5f, 0f, 0f);
                lit[1] = luma(s, target, 2f, 0f, 0.1f);
                r.check("blinking: the lamp and the bulb are lit while the pattern is at full", lit[0] > 150 && lit[0] < 700 && lit[1] > 300 && lit[1] < 700,
                        "wall near the lamp " + lit[0] + " (wanted 150 to 700), bulb " + lit[1] + " (wanted 300 to 700; pure white is 765, which hides the dimming)");
            });
            r.guard("blinking: both go dark when the pattern says off", () -> {
                asked[0] = 0f;
                animation.update(0f);
                s.render(device, target, 1f / 60f);
                int wall = luma(s, target, -1.5f, 0f, 0f), glow = luma(s, target, 2f, 0f, 0.1f);
                r.check("blinking: both go dark when the pattern says off", wall < 30 && glow < 30,
                        "wall near the lamp " + wall + " and bulb " + glow + " (wanted both under 30; lit they were " + lit[0] + " and " + lit[1] + ")");
            });
            r.guard("blinking: part way is between off and full", () -> {
                asked[0] = 0.5f;
                animation.update(0f);
                s.render(device, target, 1f / 60f);
                int wall = luma(s, target, -1.5f, 0f, 0f), glow = luma(s, target, 2f, 0f, 0.1f);
                r.check("blinking: part way is between off and full", wall > 30 && wall < lit[0] - 30 && glow > 30 && glow < lit[1] - 30,
                        "wall near the lamp " + wall + " (between 30 and " + (lit[0] - 30) + "), bulb " + glow + " (between 30 and " + (lit[1] - 30) + ")");
            });
            r.guard("blinking: back to full they are as bright as before", () -> {
                asked[0] = 1f;
                animation.update(0f);
                s.render(device, target, 1f / 60f);
                int wall = luma(s, target, -1.5f, 0f, 0f), glow = luma(s, target, 2f, 0f, 0.1f);
                r.check("blinking: back to full they are as bright as before", Math.abs(wall - lit[0]) < 8 && Math.abs(glow - lit[1]) < 8,
                        "wall near the lamp " + wall + " (was " + lit[0] + "), bulb " + glow + " (was " + lit[1] + ")");
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
        for (Scenario scenario : List.of(screen(), panel(), typing(), blinking(), manyLights())) {
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
