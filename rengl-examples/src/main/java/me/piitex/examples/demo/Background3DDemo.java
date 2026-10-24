package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.render3d.Background3D;
import me.piitex.engine.render3d.math.Vec3;
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
import me.piitex.engine.utils.flags.GeneratedFragments;

import java.io.File;

/**
 * 3D behind the whole window: a {@link Background3D} set with {@link Window#setBackground}, with the UI drawn over it.
 * <p>
 * The root container below covers the window and has no click listener, so a press on empty space or on the panel's
 * background isn't taken by the UI and drags the {@link OrbitControls}, and the wheel zooms. Buttons, sliders and check
 * boxes keep their own clicks. The "Show 3D" box turns the background off and on without removing it.
 * <p>
 * Like {@link Scene3DDemo} it asks for Vulkan on macOS and OpenGL elsewhere; {@code -Drengl.demo.api=VULKAN|OPENGL}
 * overrides that.
 */
public class Background3DDemo {
    static void main() {
        // -XstartOnFirstThread is required on macOS.
        Window window = new Window(new WindowOptions("3D Background Demo").setDimensions(1000, 650).setRenderer(chooseApi()));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Scene3D scene = new Scene3D();
        OrbitControls orbit = new OrbitControls(scene.camera());
        Node3D[] spinner = buildScene(scene, orbit);

        Background3D background = new Background3D(window, scene);
        background.setInputListener(orbit); // the content isn't an InputListener itself, so say where input goes
        window.setBackground(background);

        boolean[] spin = {true};
        scene.setUpdateHandler((s, dt) -> {
            if (spin[0]) for (Node3D node : spinner) node.rotate(Vec3.UP, dt * 0.6f);
        });

        Container root = new Container(1000, 650); // covers the window; no background of its own
        window.addContainer(root);
        buildUi(root, background, scene, orbit, spin);

        new Engine().start(window);
    }

    private static RenderAPI chooseApi() {
        String property = System.getProperty("rengl.demo.api");
        if (property != null) return RenderAPI.valueOf(property.toUpperCase());
        return System.getProperty("os.name").toLowerCase().contains("mac") ? RenderAPI.VULKAN : RenderAPI.OPENGL;
    }

    /**
     * A floor, a cube and a sphere, and a warm light. Returns the nodes that spin.
     */
    private static Node3D[] buildScene(Scene3D scene, OrbitControls orbit) {
        scene.setBackground(new Color(0.08f, 0.1f, 0.17f, 1f));
        scene.setAmbient(Color.WHITE, 0.15f);
        scene.lights().add(Light.point(new Vec3(-2.5f, 2.2f, 2f), new Color(1f, 0.65f, 0.3f, 1f), 1.5f, 9f));

        scene.add(new Node3D("floor", Mesh.plane(14, 14), new Material(
                TextureData.checker(256, 14, new float[]{0.45f, 0.45f, 0.5f}, new float[]{0.22f, 0.23f, 0.28f})).setSpecular(0.05f)));

        Node3D cube = new Node3D("cube", Mesh.cube(1.2f), new Material(new Color(0.25f, 0.6f, 0.95f, 1f)).setSpecular(0.5f));
        cube.setPosition(-1.4f, 0.6f, 0f);
        cube.setCastShadow(true);
        cube.setReceiveShadow(true);
        scene.add(cube);

        Node3D sphere = new Node3D("sphere", Mesh.sphere(0.75f, 48, 24),
                new Material(new Color(0.95f, 0.35f, 0.3f, 1f)).setSpecular(0.8f).setShininess(64));
        sphere.setPosition(1.5f, 0.75f, 0.3f);
        sphere.setReceiveShadow(true);
        sphere.setCastShadow(true);
        scene.add(sphere);

        orbit.setTarget(new Vec3(0, 0.7f, 0)).setDistance(7f).setAngles(0.5f, 0.35f);
        return new Node3D[]{cube};
    }

    private static void buildUi(Container root, Background3D background, Scene3D scene, OrbitControls orbit, boolean[] spin) {
        TextOverlay title = new TextOverlay("3D behind the UI", 26f, Color.WHITE);
        title.setPosition(28, 22);
        root.addElement(title);

        TextOverlay hint = new TextOverlay("Drag empty space to orbit, scroll to zoom", 14f, new Color(1f, 1f, 1f, 0.7f));
        hint.setPosition(28, 58);
        root.addElement(hint);

        TextOverlay ambientLabel = new TextOverlay("Ambient light", 14f, Color.WHITE);
        ambientLabel.setPosition(28, 110);
        root.addElement(ambientLabel);
        SliderOverlay ambient = new SliderOverlay(240, 0, 1, 0.15);
        ambient.setPosition(28, 132);
        ambient.onValueChange(value -> scene.setAmbient(Color.WHITE, (float) (double) value));
        root.addElement(ambient);

        CheckBoxOverlay spinBox = new CheckBoxOverlay(20, true);
        spinBox.setPosition(28, 180);
        spinBox.onToggle(on -> spin[0] = on);
        root.addElement(spinBox);
        TextOverlay spinText = new TextOverlay("Spin the cube", 14f, Color.WHITE);
        spinText.setPosition(58, 181);
        root.addElement(spinText);

        CheckBoxOverlay showBox = new CheckBoxOverlay(20, true);
        showBox.setPosition(28, 214);
        showBox.onToggle(background::setEnabled);
        root.addElement(showBox);
        TextOverlay showText = new TextOverlay("Show 3D", 14f, Color.WHITE);
        showText.setPosition(58, 215);
        root.addElement(showText);

        ButtonOverlay reset = new ButtonOverlay("Reset camera", 130, 32);
        reset.setPosition(28, 256);
        reset.onAction(() -> orbit.setTarget(new Vec3(0, 0.7f, 0)).setDistance(7f).setAngles(0.5f, 0.35f));
        root.addElement(reset);
    }
}
