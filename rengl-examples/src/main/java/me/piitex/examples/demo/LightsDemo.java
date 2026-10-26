package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Instances;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.OrbitControls;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.TextureData;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * A night city for the three things that let a scene have many lights and many objects:
 * <ul>
 *   <li><b>The light grid.</b> The slider adds up to 1500 coloured point lights along the streets. With the grid
 *       ({@link Scene3D.LightMode#CLUSTERED}) every pixel tests only the lights near it, and all of them shine. With
 *       {@code DIRECT} every pixel tests all of the lights that were chosen and no more than {@link Scene3D#MAX_LIGHTS} of
 *       them are, so past that most of the street goes dark. The button cycles the three modes; {@code AUTO} turns the
 *       grid on once the scene has more lights than that.</li>
 *   <li><b>Glowing surfaces.</b> The 64 lamps at the crossings are small emissive spheres. "Lamps light the street" gives
 *       their material a glow with {@link Material#setGlow}, so each lamp is also a light that is not in the light list. Turn
 *       it off to see them shine without lighting anything.</li>
 *   <li><b>Occlusion culling.</b> The buildings are marked with {@link Node3D#setOccluder}, and 700 crates stand between
 *       them. With the check box on, the crates (and buildings) behind other buildings are never sent to the GPU: watch
 *       "hidden" in the numbers, after "Street view" puts the camera low where most of the city is behind something.</li>
 * </ul>
 * Drag to orbit and scroll to zoom. Like the other 3D demos it asks for Vulkan on macOS and OpenGL elsewhere;
 * {@code -Drengl.demo.api=OPENGL} chooses.
 */
public class LightsDemo {
    private static final int MAX_LIGHTS = 1500;
    private static final int GRID = 4;

    static void main() {
        // -XstartOnFirstThread is required on macOS.
        Window window = new Window(new WindowOptions("Lights and culling").setDimensions(1100, 700).setRenderer(chooseApi()));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(1100, 700);
        root.getStyling().setBackgroundColor(new Color(0.11f, 0.11f, 0.14f, 1f));
        window.addContainer(root);

        City city = new City();

        TextOverlay title = new TextOverlay("Lights and culling", 24f, Color.WHITE);
        title.setPosition(24, 18);
        root.addElement(title);

        Viewport3D view = new Viewport3D(760, 660);
        view.setPosition(320, 20);
        view.getStyling().setCornerRadius(12f);
        view.setContent(city.scene);
        view.setInputListener(city.orbit);
        root.addElement(view);

        TextOverlay hint = new TextOverlay("Drag to orbit, scroll to zoom", 13f, new Color(1f, 1f, 1f, 0.55f));
        hint.setPosition(336, 664);
        root.addElement(hint);

        TextOverlay[] stats = new TextOverlay[6];
        for (int i = 0; i < stats.length; i++) {
            stats[i] = new TextOverlay("", 14f, i == 0 ? Color.WHITE : new Color(1f, 1f, 1f, 0.8f));
            stats[i].setPosition(24, 392 + i * 22);
            root.addElement(stats[i]);
        }
        city.onStats = text -> {
            String[] parts = text.split(" \\| ");
            for (int i = 0; i < stats.length && i < parts.length; i++) stats[i].setText(parts[i]);
        };
        city.window = window;

        TextOverlay lightsLabel = new TextOverlay("", 14f, Color.WHITE);
        lightsLabel.setPosition(24, 66);
        root.addElement(lightsLabel);
        SliderOverlay lights = new SliderOverlay(264, 0, MAX_LIGHTS, 300);
        lights.setPosition(24, 90);
        lights.onValueChange(value -> {
            city.setLightCount((int) Math.round(value));
            lightsLabel.setText("Point lights: " + city.lightCount);
        });
        root.addElement(lights);
        city.setLightCount(300);
        lightsLabel.setText("Point lights: 300");

        float y = 128;
        ButtonOverlay mode = new ButtonOverlay("Light mode: AUTO", 264, 32);
        mode.setPosition(24, y);
        mode.onAction(() -> mode.setText("Light mode: " + city.nextMode()));
        root.addElement(mode);
        y += 48;
        y = checkBox(root, "Lamps light the street (glow)", y, true, city::setGlow);
        y = checkBox(root, "Occlusion culling", y, false, city.scene::setOcclusionCulling);
        y += 6;
        y = button(root, "Street view", y, () -> city.orbit.setTarget(new Vec3(0, 2, 0)).setDistance(46f).setAngles(0.4f, 0.08f));
        button(root, "Bird's eye", y, () -> city.orbit.setTarget(new Vec3(0, 0, 0)).setDistance(70f).setAngles(0.6f, 1.1f));

        new Engine().start(window);
    }

    private static RenderAPI chooseApi() {
        String property = System.getProperty("rengl.demo.api");
        if (property != null) return RenderAPI.valueOf(property.toUpperCase());
        return System.getProperty("os.name").toLowerCase().contains("mac") ? RenderAPI.VULKAN : RenderAPI.OPENGL;
    }

    private static float button(Container root, String label, float y, Runnable action) {
        ButtonOverlay button = new ButtonOverlay(label, 264, 32);
        button.setPosition(24, y);
        button.onAction(action);
        root.addElement(button);
        return y + 40;
    }

    private static float checkBox(Container root, String label, float y, boolean checked, Consumer<Boolean> onToggle) {
        CheckBoxOverlay box = new CheckBoxOverlay(20, checked);
        box.setPosition(24, y);
        box.onToggle(onToggle);
        root.addElement(box);
        TextOverlay text = new TextOverlay(label, 14f, Color.WHITE);
        text.setPosition(54, y + 1);
        root.addElement(text);
        return y + 34;
    }

    /**
     * The scene: a grid of buildings with a street between each row, the crates and lamps in the streets, and the point
     * lights.
     */
    private static final class City {
        final Scene3D scene = new Scene3D();
        final OrbitControls orbit = new OrbitControls(scene.camera());
        final Material lampMaterial = new Material(new Color(1f, 0.8f, 0.45f, 1f)).setEmissive(new Color(1f, 0.75f, 0.4f, 1f), 3f);
        final List<Light> lights = new ArrayList<>();
        final Scene3D.LightMode[] modes = Scene3D.LightMode.values();
        int modeIndex;
        int lightCount;
        Consumer<String> onStats = _ -> {
        };
        Window window;
        private String shown = "";

        City() {
            scene.setBackground(new Color(0.02f, 0.03f, 0.06f, 1f));
            scene.setAmbient(new Color(0.5f, 0.6f, 1f, 1f), 0.05f);
            scene.lights().getFirst().setIntensity(0.12f).setColor(new Color(0.55f, 0.65f, 1f, 1f));
            scene.setUpdateHandler((s, dt) -> updateStats());

            Node3D floor = new Node3D("street", Mesh.plane(120, 120), new Material(
                    TextureData.checker(256, 30, new float[]{0.22f, 0.22f, 0.24f}, new float[]{0.16f, 0.16f, 0.18f})).setSpecular(0.1f));
            scene.add(floor);

            buildBuildings();
            buildCrates();
            buildLamps();
            makeLights();

            orbit.setTarget(new Vec3(0, 2, 0)).setDistance(60f).setAngles(0.6f, 0.45f).setDistanceLimits(8f, 110f);
            lampMaterial.setGlow(9f, 0.5f);
        }

        private void buildBuildings() {
            Random random = new Random(5);
            Mesh cube = Mesh.cube(1f);
            Material[] walls = {
                    new Material(new Color(0.45f, 0.42f, 0.40f, 1f)),
                    new Material(new Color(0.36f, 0.40f, 0.46f, 1f)),
                    new Material(new Color(0.50f, 0.38f, 0.34f, 1f))};
            for (int i = -GRID; i <= GRID; i++) {
                for (int j = -GRID; j <= GRID; j++) {
                    float height = 3f + random.nextFloat() * 8f;
                    Node3D building = new Node3D("building", cube, walls[random.nextInt(walls.length)]);
                    building.setScale(new Vec3(5f, height, 5f));
                    building.setPosition(i * 8f, height / 2f, j * 8f);
                    building.setOccluder(true);
                    scene.add(building);
                }
            }
        }

        private void buildCrates() {
            Random random = new Random(9);
            Instances crates = new Instances();
            for (int i = 0; i < 700; i++) {
                float size = 0.5f + random.nextFloat() * 0.5f;
                Vec3 spot = streetSpot(random);
                crates.add(new Vec3(spot.x(), size / 2f, spot.z()), Quat.fromAxisAngle(Vec3.UP, random.nextFloat() * 6.28f),
                        new Vec3(size, size, size));
            }
            Node3D node = new Node3D("crates", Mesh.cube(1f), new Material(new Color(0.65f, 0.5f, 0.3f, 1f)));
            node.setInstances(crates);
            scene.add(node);
        }

        private void buildLamps() {
            Mesh bulb = Mesh.sphere(0.35f, 16, 8);
            Mesh pole = Mesh.cylinder(0.08f, 3f, 8);
            Material iron = new Material(new Color(0.15f, 0.15f, 0.16f, 1f));
            for (int i = -GRID; i < GRID; i++) {
                for (int j = -GRID; j < GRID; j++) {
                    float x = i * 8f + 4f, z = j * 8f + 4f;
                    Node3D post = new Node3D("pole", pole, iron);
                    post.setPosition(x, 1.5f, z);
                    scene.add(post);
                    Node3D lamp = new Node3D("lamp", bulb, lampMaterial);
                    lamp.setPosition(x, 3.2f, z);
                    lamp.setCastShadow(false);
                    scene.add(lamp);
                }
            }
        }

        /** A point on a street: on one of the lines between the rows of buildings. */
        private static Vec3 streetSpot(Random random) {
            float line = (random.nextInt(2 * GRID) - GRID) * 8f + 4f + (random.nextFloat() - 0.5f) * 2f;
            float along = (random.nextFloat() * 2f - 1f) * (GRID * 8f + 4f);
            return random.nextBoolean() ? new Vec3(line, 0, along) : new Vec3(along, 0, line);
        }

        private void makeLights() {
            Random random = new Random(21);
            Color[] colors = {
                    new Color(1f, 0.35f, 0.3f, 1f), new Color(0.3f, 0.7f, 1f, 1f), new Color(0.4f, 1f, 0.5f, 1f),
                    new Color(1f, 0.85f, 0.3f, 1f), new Color(0.85f, 0.4f, 1f, 1f), new Color(1f, 0.6f, 0.2f, 1f)};
            for (int i = 0; i < MAX_LIGHTS; i++) {
                Vec3 spot = streetSpot(random);
                lights.add(Light.point(new Vec3(spot.x(), 1.2f + random.nextFloat() * 1.5f, spot.z()),
                        colors[random.nextInt(colors.length)], 2.5f, 5f));
            }
        }

        void setLightCount(int count) {
            count = Math.max(0, Math.min(MAX_LIGHTS, count));
            while (lightCount < count) scene.lights().add(lights.get(lightCount++));
            while (lightCount > count) scene.lights().remove(lights.get(--lightCount));
        }

        String nextMode() {
            modeIndex = (modeIndex + 1) % modes.length;
            scene.setLightMode(modes[modeIndex]);
            return modes[modeIndex].name();
        }

        void setGlow(boolean on) {
            lampMaterial.setGlow(on ? 9f : 0f, 0.5f);
        }

        private void updateStats() {
            Scene3D.Stats s = scene.getStats();
            String text = String.format("Drawn: %d nodes, %d records | Left out: %d, of them %d hidden behind buildings | Draw calls: %d | "
                            + "Lights in the grid: %d | Lights from glowing lamps: %d | Frames per second: %.0f",
                    s.nodes(), s.instances(), s.culled(), s.occluded(), s.drawCalls(), s.clusteredLights(), s.glowLights(),
                    window == null ? 0.0 : window.getFps());
            if (!text.equals(shown)) {
                shown = text;
                onStats.accept(text);
            }
        }
    }
}
