package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.entity.Entity;
import me.piitex.engine.entity.EntityScene;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.TreeView;
import me.piitex.engine.ui.menu.MenuItem;
import me.piitex.engine.ui.menu.PopupMenu;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.lwjgl.glfw.GLFW;

import java.io.File;

/**
 * A {@link TreeView} as an editor's hierarchy panel, over the entity demo's running scene.
 * <ul>
 *   <li>Drag a row onto another to make it a child (the middle of the row), or between two rows to put it there.
 *       Drag the moon out of the planet and it stops orbiting it.</li>
 *   <li>Double-click or F2 renames; Delete removes; right-click for a menu.</li>
 *   <li>The arrow keys move the selection and open and close rows.</li>
 * </ul>
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class TreeDemo {

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Tree Demo").setDimensions(1000, 640));
        EntityScene scene = EntityScene.load("src/main/resources/demo/entities.json");
        window.getSceneManager().set(scene);

        Container panel = new Container(0, 0, 280, 640);
        TextOverlay title = new TextOverlay("Hierarchy", 16f, Color.WHITE);
        title.setPosition(12, 10);
        panel.addElement(title);
        TreeView<Entity> tree = new TreeView<>(264, 590, Entity::getChildren);
        tree.setPosition(8, 40);
        tree.setLabelProvider(Entity::getName);
        tree.setParentProvider(Entity::getParent);
        tree.setDimmed(entity -> !entity.isActiveInTree());
        tree.setDragEnabled(true);
        tree.setRoots(scene.getEntities());
        tree.expandAll();

        tree.onDrop(drop -> {
            if (drop.parent() == null) scene.add(drop.item(), drop.index());
            else drop.parent().addChild(drop.item(), drop.index());
            tree.refresh();
        });
        tree.onRename((entity, name) -> {
            entity.setName(name);
            tree.refresh();
        });
        tree.onActivate(tree::startRename);
        tree.onKey((key, mods) -> {
            Entity selected = tree.getSelected();
            if (selected == null) return false;
            if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
                selected.destroy();
                tree.refresh();
                return true;
            }
            if (key == GLFW.GLFW_KEY_F2) {
                tree.startRename(selected);
                return true;
            }
            return false;
        });

        MenuItem rename = new MenuItem("Rename", () -> tree.startRename(tree.getSelected()));
        MenuItem active = MenuItem.check("Active", true, on -> tree.getSelected().setActive(on));
        MenuItem delete = new MenuItem("Delete", () -> {
            tree.getSelected().destroy();
            tree.refresh();
        });
        PopupMenu menu = new PopupMenu(new MenuItem("New entity", () -> {
            Entity parent = tree.getSelected();
            Entity entity = new Entity("New entity");
            if (parent == null) scene.add(entity);
            else parent.addChild(entity);
            tree.refresh();
            tree.setSelected(entity);
            tree.startRename(entity);
        }), MenuItem.separator(), rename, active, delete);
        menu.onOpen(m -> {
            Entity selected = tree.getSelected();
            rename.setEnabled(selected != null);
            active.setEnabled(selected != null);
            active.setChecked(selected != null && selected.isActive());
            delete.setEnabled(selected != null);
        });
        tree.setContextMenu(menu);

        panel.addElement(tree);
        window.addContainer(panel);
        new Engine().start(window);
    }
}
