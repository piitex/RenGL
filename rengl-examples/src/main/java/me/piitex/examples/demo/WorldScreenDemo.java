package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.*;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.ColorPickerOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;

/**
 * A control panel on a screen standing in the 3D world, which works with the mouse like any other part of the interface:
 * <ul>
 *   <li><b>Drag the slider</b> on the screen to change how fast the shape turns. A press that lands on something that drags
 *       (a slider's thumb, here) is a drag until the button comes up, and it goes on when the pointer leaves the screen: it
 *       holds at the last place on the screen. A press that lands on bare wall, floor or the screen's background orbits the
 *       camera instead.</li>
 *   <li><b>Open the dropdown</b> to choose the shape: its list opens over the screen, is chosen from with a click, and closes on a
 *       press outside it or on Escape.</li>
 *   <li><b>Open the colour picker</b> beside it to tint the shape. It is a popup like the list.</li>
 *   <li>The check box switches the lamp over the shape.</li>
 * </ul>
 * Underneath, a {@link UiScreen} draws the container into a texture on the CPU, and {@code scene.pick} says where on the screen
 * the mouse is. Scroll zooms the camera, except over a list that scrolls. Like the other 3D demos it asks for Vulkan on macOS
 * and OpenGL elsewhere; {@code -Drengl.demo.api=OPENGL} chooses.
 */
public class WorldScreenDemo {
    private static final String[] SHAPES = {"Cube", "Sphere", "Cone", "Cylinder"};

    static void main() {
        // -XstartOnFirstThread is required on macOS.
        Window window = new Window(new WindowOptions("A screen in the world").setDimensions(1100, 700).setRenderer(chooseApi()));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(1100, 700);
        root.getStyling().setBackgroundColor(new Color(0.11f, 0.11f, 0.14f, 1f));
        window.addContainer(root);

        Room room = new Room();

        Viewport3D view = new Viewport3D(1060, 620);
        view.setPosition(20, 20);
        view.getStyling().setCornerRadius(12f);
        view.setContent(room.scene);
        view.setInputListener(room.listener(view));
        root.addElement(view);

        TextOverlay hint = new TextOverlay("Use the screen with the mouse. Drag anywhere else to orbit, scroll to zoom.", 14f,
                new Color(1f, 1f, 1f, 0.7f));
        hint.setPosition(24, 654);
        root.addElement(hint);

        new Engine().start(window);
    }

    private static RenderAPI chooseApi() {
        String property = System.getProperty("rengl.demo.api");
        if (property != null) return RenderAPI.valueOf(property.toUpperCase());
        return System.getProperty("os.name").toLowerCase().contains("mac") ? RenderAPI.VULKAN : RenderAPI.OPENGL;
    }

    /**
     * The scene, the screen and what its controls change.
     */
    private static final class Room {
        final Scene3D scene = new Scene3D();
        final OrbitControls orbit = new OrbitControls(scene.camera());
        final Node3D monitor;
        final Node3D shape;
        final Material shapeMaterial = new Material(new Color(0.85f, 0.4f, 0.3f, 1f)).setSpecular(0.6f).setShininess(40);
        final Light lamp = Light.point(new Vec3(0f, 3.2f, 2.5f), new Color(1f, 0.9f, 0.75f, 1f), 2.2f, 9f);
        final UiScreen screen;
        final Mesh[] meshes = {Mesh.cube(1.2f), Mesh.sphere(0.7f, 32, 16), Mesh.cone(0.7f, 1.4f, 32), Mesh.cylinder(0.6f, 1.3f, 32)};
        float speed = 1f;
        boolean dragOnScreen;

        Room() {
            scene.setBackground(new Color(0.05f, 0.06f, 0.09f, 1f));
            scene.setAmbient(new Color(0.6f, 0.7f, 1f, 1f), 0.12f);
            scene.lights().getFirst().setIntensity(0.25f);
            scene.lights().add(lamp);

            scene.add(new Node3D("floor", Mesh.plane(24, 24), new Material(
                    TextureData.checker(256, 16, new float[]{0.4f, 0.4f, 0.42f}, new float[]{0.25f, 0.25f, 0.28f})).setSpecular(0.05f)));

            Node3D pedestal = new Node3D("pedestal", Mesh.cylinder(1.1f, 0.5f, 32), new Material(new Color(0.3f, 0.3f, 0.34f, 1f)));
            pedestal.setPosition(0f, 0.25f, 2.2f);
            scene.add(pedestal);

            shape = new Node3D("shape", meshes[0], shapeMaterial);
            shape.setPosition(0f, 1.3f, 2.2f);
            scene.add(shape);

            Container ui = buildPanel();
            screen = new UiScreen(ui, 1.5f);

            Node3D frame = new Node3D("frame", Mesh.cube(1f), new Material(new Color(0.08f, 0.08f, 0.1f, 1f)));
            frame.setScale(new Vec3(5.6f, 3.7f, 0.2f));
            frame.setPosition(0f, 2.4f, -1.1f);
            scene.add(frame);

            monitor = new Node3D("screen", Mesh.plane(5.2f, 3.25f),
                    new Material(Color.BLACK).setEmissiveTexture(screen.texture()).setEmissive(Color.WHITE, 1f));
            monitor.setRotation(Quat.fromAxisAngle(Vec3.X, (float) (Math.PI / 2)));
            monitor.setPosition(0f, 2.4f, -0.98f);
            scene.add(monitor);

            orbit.setTarget(new Vec3(0f, 2f, 0f)).setDistance(9.5f).setAngles(0.35f, 0.18f).setDistanceLimits(3f, 24f);
            scene.setUpdateHandler((s, dt) -> {
                shape.rotate(Vec3.UP, dt * speed);
                screen.refresh(dt);
            });
        }

        private Container buildPanel() {
            Container ui = new Container(480, 300);
            ui.getStyling().setBackgroundColor(new Color(0.09f, 0.11f, 0.16f, 1f));
            ui.getStyling().setBorderThickness(0f);

            TextOverlay title = new TextOverlay("Display controls", 22f, Color.WHITE);
            title.setPosition(24, 16);
            ui.addElement(title);

            TextOverlay speedLabel = new TextOverlay("Spin speed: 1.0", 14f, Color.WHITE);
            speedLabel.setPosition(24, 62);
            ui.addElement(speedLabel);
            SliderOverlay slider = new SliderOverlay(432, 0, 6, 1);
            slider.setPosition(24, 86);
            slider.onValueChange(value -> {
                speed = value.floatValue();
                speedLabel.setText(String.format("Spin speed: %.1f", speed));
            });
            ui.addElement(slider);

            TextOverlay shapeLabel = new TextOverlay("Shape", 14f, Color.WHITE);
            shapeLabel.setPosition(24, 134);
            ui.addElement(shapeLabel);
            DropdownOverlay<String> shapes = new DropdownOverlay<>(200, 34);
            for (String name : SHAPES) shapes.addItem(name);
            shapes.setSelectedIndex(0);
            shapes.setPosition(24, 158);
            shapes.onSelect(name -> {
                for (int i = 0; i < SHAPES.length; i++) if (SHAPES[i].equals(name)) shape.setMesh(meshes[i]);
            });
            ui.addElement(shapes);

            TextOverlay colorLabel = new TextOverlay("Colour", 14f, Color.WHITE);
            colorLabel.setPosition(256, 134);
            ui.addElement(colorLabel);
            ColorPickerOverlay picker = new ColorPickerOverlay(200, 34, new Color(0.85f, 0.4f, 0.3f, 1f));
            picker.setPosition(256, 158);
            picker.onChange(color -> shapeMaterial.setColor(color));
            ui.addElement(picker);

            CheckBoxOverlay lampBox = new CheckBoxOverlay(22, true);
            lampBox.setPosition(24, 224);
            lampBox.onToggle(on -> lamp.setIntensity(on ? 2.2f : 0f));
            ui.addElement(lampBox);
            TextOverlay lampLabel = new TextOverlay("Lamp over the shape", 14f, Color.WHITE);
            lampLabel.setPosition(56, 227);
            ui.addElement(lampLabel);
            return ui;
        }

        /**
         * Sends the mouse to the screen while it is pointing at it, and to the camera otherwise.
         */
        Viewport3D.InputListener listener(Viewport3D view) {
            return new Viewport3D.InputListener() {
                private Hit pick(double x, double y) {
                    return scene.pick(x, y, view.getWidth(), view.getHeight());
                }

                @Override
                public void pointerMoved(double x, double y) {
                    screen.pointAt(pick(x, y), monitor);
                }

                @Override
                public void pointerExited() {
                    screen.pointAway();
                }

                @Override
                public void dragStarted(double x, double y) {
                    screen.pointAt(pick(x, y), monitor);
                    dragOnScreen = screen.isPointing() && screen.press();
                }

                @Override
                public void dragged(double x, double y, double dx, double dy) {
                    if (dragOnScreen) screen.pointAt(pick(x, y), monitor); // off the screen the drag holds at its last place
                    else orbit.dragged(x, y, dx, dy);
                }

                @Override
                public void dragEnded(double x, double y) {
                    if (!dragOnScreen) return;
                    screen.pointAt(pick(x, y), monitor);
                    screen.release();
                    dragOnScreen = false;
                }

                @Override
                public void scrolled(double dx, double dy, double x, double y) {
                    if (!screen.scroll(dx, dy)) orbit.scrolled(dx, dy, x, y);
                }

                @Override
                public void keyPressed(int key, int action, int mods) {
                    screen.key(key, action, mods);
                }

                @Override
                public void charTyped(int codepoint) {
                    screen.typeChar(codepoint);
                }
            };
        }
    }
}
