package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.menu.MenuBarOverlay;
import me.piitex.engine.ui.menu.MenuItem;
import me.piitex.engine.ui.menu.PopupMenu;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.LightTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;

/**
 * Shows {@link MenuBarOverlay}, {@link PopupMenu} and context menus.
 * <ul>
 *   <li><b>Menu bar</b> across the top (in the macOS system menu bar on a Mac, in the window elsewhere): File (with a Recent submenu that nests one level deeper), Edit
 *       (Undo is disabled), View (check items and a theme switch). Hover across the titles while one is
 *       open to swap menus; Left/Right does the same from the keyboard.</li>
 *   <li><b>Right-click</b> the grey panel for a context menu, or the text below it (which has its own).</li>
 *   <li><b>Keyboard:</b> Ctrl+S saves from anywhere (the File menu's shortcut is bound), F10 or Alt+F/E/V open a menu,
 *       a letter chooses the underlined row. View has a radio group and a long list that scrolls.</li>
 *   <li><b>"More..." button</b> opens a plain popup menu under itself with {@link PopupMenu#showBelow}.</li>
 * </ul>
 * Whatever you pick shows in the status line.
 */
public class MenuDemo {
    private static boolean dark = true;

    static void main() {
        // To reduce console spam add these jvm flags
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny

        Window window = new Window(new WindowOptions("Menus").setDimensions(1000, 640));
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(1000, 640);
        root.getStyling().setBackgroundColor(Color.TRANSPARENT);
        root.getStyling().setHoverColor(Color.TRANSPARENT);
        root.getStyling().setBorderThickness(0f);
        window.addContainer(root);

        TextOverlay status = new TextOverlay("Pick something from a menu.", 15f, Color.WHITE);
        status.setPosition(40, 80);
        root.addElement(status);

        MenuBarOverlay bar = new MenuBarOverlay(1000);
        bar.addMenu("File", fileMenu(status));
        bar.addMenu("Edit", editMenu(status));
        bar.addMenu("View", viewMenu(status));
        bar.setMnemonic(0, 'F'); // Alt+F opens File (in-window bar); F10 opens the first menu
        bar.setMnemonic(1, 'E');
        bar.setMnemonic(2, 'V');
        root.addElement(bar);

        // A themed panel with a context menu whose contents depend on state, refreshed on every open.
        Container panel = new Container(40, 130, 520, 300);
        panel.useTheme();
        panel.setContextMenu(panelMenu(status));
        root.addElement(panel);

        TextOverlay hint = new TextOverlay("Right-click anywhere in this panel", 14f, Color.WHITE);
        hint.setPosition(20, 20);
        panel.addElement(hint);

        // A child with its own menu wins over the panel's.
        TextOverlay own = new TextOverlay("...or on this text, which has its own menu", 14f, Color.WHITE);
        own.setPosition(20, 60);
        own.setContextMenu(new PopupMenu(
                new MenuItem("Copy text", () -> status.setText("Copied the text")),
                new MenuItem("Look up", () -> status.setText("Looked it up"))));
        panel.addElement(own);

        ButtonOverlay more = new ButtonOverlay("More...", 100, 32);
        more.setPosition(600, 130);
        PopupMenu moreMenu = new PopupMenu(
                new MenuItem("Rename", () -> status.setText("Rename")),
                new MenuItem("Duplicate", () -> status.setText("Duplicate")),
                MenuItem.separator(),
                new MenuItem("Delete", () -> status.setText("Delete")));
        more.onAction(() -> moreMenu.showBelow(more));
        root.addElement(more);

        new Engine().start(window);
    }

    private static PopupMenu fileMenu(TextOverlay status) {
        MenuItem recent = MenuItem.submenu("Open Recent",
                new MenuItem("project-alpha.txt", () -> status.setText("Opened project-alpha.txt")),
                new MenuItem("notes.md", () -> status.setText("Opened notes.md")),
                MenuItem.separator(),
                MenuItem.submenu("Older",
                        new MenuItem("2024-report.txt", () -> status.setText("Opened 2024-report.txt")),
                        new MenuItem("draft.txt", () -> status.setText("Opened draft.txt"))));

        MenuItem save = new MenuItem("Save", () -> status.setText("Saved"));
        save.setShortcut("Ctrl+S"); // binds the key too: press it anywhere in the window
        save.setIcon(Feather.SAVE);
        save.setMnemonic('S');
        MenuItem open = new MenuItem("Open...", () -> status.setText("Open..."));
        open.setShortcut("Ctrl+O");
        MenuItem exit = new MenuItem("Exit", () -> System.exit(0));
        exit.setShortcut("Alt+F4");

        return new PopupMenu(new MenuItem("New", () -> status.setText("New")), open, recent, MenuItem.separator(),
                save, MenuItem.separator(), exit);
    }

    private static PopupMenu editMenu(TextOverlay status) {
        MenuItem undo = new MenuItem("Undo", () -> status.setText("Undo"));
        undo.setShortcut("Ctrl+Z");
        undo.setEnabled(false);
        MenuItem cut = new MenuItem("Cut", () -> status.setText("Cut"));
        cut.setShortcut("Ctrl+X");
        MenuItem copy = new MenuItem("Copy", () -> status.setText("Copy"));
        copy.setShortcut("Ctrl+C");
        MenuItem paste = new MenuItem("Paste", () -> status.setText("Paste"));
        paste.setShortcut("Ctrl+V");
        return new PopupMenu(undo, MenuItem.separator(), cut, copy, paste);
    }

    private static PopupMenu viewMenu(TextOverlay status) {
        MenuItem toggleTheme = new MenuItem("Switch theme", () -> {
            dark = !dark;
            ThemeManager.setCurrent(dark ? new DarkTheme() : new LightTheme());
        });
        MenuItem[] many = new MenuItem[80];
        for (int i = 0; i < many.length; i++) {
            int n = i + 1;
            many[i] = new MenuItem("Item " + n, () -> status.setText("Picked item " + n));
        }
        PopupMenu menu = new PopupMenu(
                MenuItem.check("Show grid", true, on -> status.setText("Grid " + (on ? "on" : "off"))),
                MenuItem.check("Word wrap", false, on -> status.setText("Word wrap " + (on ? "on" : "off"))),
                MenuItem.separator());
        menu.addAll(MenuItem.radioGroup(1, i -> status.setText("Mode " + i), "Day", "Dusk", "Night"));
        menu.addSeparator();
        menu.add(MenuItem.submenu("Long list (scrolls)", many));
        menu.add(toggleTheme);
        return menu;
    }

    private static PopupMenu panelMenu(TextOverlay status) {
        int[] clicks = {0};
        PopupMenu menu = new PopupMenu();
        menu.onOpen(m -> {
            m.clear();
            clicks[0]++;
            m.add("Opened " + clicks[0] + " time(s)", () -> status.setText("Count: " + clicks[0]));
            m.addSeparator();
            m.add(MenuItem.submenu("Create",
                    new MenuItem("Folder", () -> status.setText("Create folder")),
                    new MenuItem("File", () -> status.setText("Create file"))));
            m.add("Refresh", () -> status.setText("Refreshed"));
        });
        return menu;
    }
}
