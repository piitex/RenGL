package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.TreeTableView;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.TextAlign;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A scene outline as a {@link TreeTableView}: entities nested under groups, with a column of sizes, a switch for
 * visibility and a slider for opacity in every row, at every depth.
 * <ul>
 *   <li>Click an arrow, double-click a group, or use Left and Right to open and close groups.</li>
 *   <li>Click a header to sort; each group's children are sorted among themselves and stay under it.</li>
 *   <li>Double-click a name to rename it. Tick boxes on the left; ticks inside closed groups are kept.</li>
 * </ul>
 */
public class TreeTableDemo {
    private static final class Entity {
        String name;
        final int triangles;
        boolean visible = true;
        double opacity = 100;
        final List<Entity> children = new ArrayList<>();

        Entity(String name, int triangles) {
            this.name = name;
            this.triangles = triangles;
        }

        Entity add(Entity child) {
            children.add(child);
            return this;
        }

        int total() {
            int sum = triangles;
            for (Entity child : children) sum += child.total();
            return sum;
        }
    }

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Tree Table Demo").setDimensions(860, 560).setSplashEnabled(false));

        Container root = new Container(860, 560);
        window.addContainer(root);
        TextOverlay title = new TextOverlay("A tree table: a scene outline with controls at every depth", 15f, Color.WHITE);
        title.setPosition(16, 12);
        root.addElement(title);
        TextOverlay status = new TextOverlay("", 13f, Color.GRAY);
        status.setPosition(16, 530);
        root.addElement(status);

        List<Entity> scene = buildScene();

        TreeTableView<Entity> tree = new TreeTableView<>(828, 460, e -> e.children);
        tree.setPosition(16, 40);
        tree.setRowHeight(30);
        tree.setStriped(true);
        tree.setCheckBoxes(true);
        tree.addColumn("Name", e -> e.name).setWidth(260)
                .setEditable((e, name) -> {
                    e.name = name;
                    status.setText("Renamed to " + name);
                });
        tree.addColumn("Triangles", e -> String.format("%,d", e.total())).setWidth(120).setAlignment(TextAlign.RIGHT)
                .setComparator(Comparator.comparingInt(Entity::total));
        tree.addColumn("Visible", e -> e.visible ? "yes" : "no").setWidth(90).setAlignment(TextAlign.CENTER)
                .setComparator(Comparator.comparing(e -> !e.visible))
                .setCellWidgetFill(false, false)
                .setCellWidget(() -> new ToggleSwitchOverlay(40, 22), (toggle, e) -> {
                    toggle.setOn(e.visible);
                    toggle.onToggle(on -> {
                        e.visible = on;
                        status.setText(e.name + (on ? " shown" : " hidden"));
                    });
                });
        tree.addColumn("Opacity", e -> String.valueOf(Math.round(e.opacity))).setWidth(200)
                .setComparator(Comparator.comparingDouble(e -> e.opacity))
                .setCellWidget(() -> new SliderOverlay(180, 0, 100), (slider, e) -> {
                    slider.setValue(e.opacity);
                    slider.onValueChange(v -> {
                        e.opacity = v;
                        status.setText(e.name + " opacity " + Math.round(v));
                    });
                });
        tree.setRoots(scene);
        tree.expand(scene.getFirst());
        tree.onSelect(e -> status.setText(e == null ? "" : "Selected " + e.name + ", depth " + tree.depthOf(e)));
        tree.onCheck((e, on) -> status.setText(tree.getCheckedItems().size() + " ticked"));
        root.addElement(tree);

        new Engine().start(window);
    }

    private static List<Entity> buildScene() {
        Entity level = new Entity("Level", 0);
        for (int b = 1; b <= 6; b++) {
            Entity building = new Entity("Building " + b, 1200 * b);
            for (int f = 1; f <= 4; f++) {
                Entity floor = new Entity("Floor " + f, 300 + 40 * f);
                floor.add(new Entity("Walls", 800 + 17 * b * f))
                        .add(new Entity("Furniture", 2400 - 90 * f))
                        .add(new Entity("Lights", 60 + b));
                building.add(floor);
            }
            level.add(building);
        }
        Entity props = new Entity("Props", 0);
        for (int i = 1; i <= 30; i++) props.add(new Entity("Crate " + i, 96 + (i * 31) % 200));
        Entity camera = new Entity("Camera", 0);
        Entity sky = new Entity("Sky", 2048);
        return List.of(level, props, camera, sky);
    }
}
