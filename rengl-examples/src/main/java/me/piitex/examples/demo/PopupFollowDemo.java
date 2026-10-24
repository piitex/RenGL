package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.menu.MenuItem;
import me.piitex.engine.ui.menu.PopupMenu;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.ColorPickerOverlay;
import me.piitex.engine.ui.overlays.DateTimePickerOverlay;
import me.piitex.engine.ui.overlays.DateTimePickerOverlay.Mode;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;

/**
 * Shows popups following the element that opened them.
 * <ul>
 *   <li><b>The panel</b> holds a dropdown, a color picker, a date picker, a "More..." menu button, and a context menu
 *       (right-click its background). Drag it by its background, or turn on <b>Move the panel</b> so it drifts
 *       around the window on its own. Open any popup and watch it ride along.</li>
 *   <li><b>Window edges</b>: when the panel nears the bottom, a list below its dropdown flips above it; near the
 *       right edge, the popups slide back inside. Shrinking the window with a popup open does the same.</li>
 *   <li><b>The scroll list</b> on the right: open a dropdown, then scroll the list with the wheel (pointer over the
 *       list, not the popup). The popup follows its row, and closes once the row is scrolled out of view.</li>
 * </ul>
 */
public class PopupFollowDemo {
    private static final float SPEED_X = 70f; // points per second while moving
    private static final float SPEED_Y = 45f;

    static void main() {
        // To reduce console spam add these jvm flags
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny

        Window window = new Window(new WindowOptions("Popups follow their element").setDimensions(1000, 680).setResizable(true));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(2560, 1600); // larger than the window, so it still covers it after a resize
        window.addContainer(root);

        root.addElement(label("Popups follow the element that opened them", 22f, 30, 24));
        TextOverlay status = label("Open a popup, then move its element.", 14f, 30, 60);
        root.addElement(status);

        ToggleSwitchOverlay move = new ToggleSwitchOverlay(44, 24, false);
        move.setPosition(30, 96);
        move.onToggle(on -> status.setText(on ? "The panel is moving. Open a popup on it." : "The panel stopped. Drag it by its background."));
        root.addElement(move);
        root.addElement(label("Move the panel", 15f, 86, 97));

        root.addElement(scrollList(status));
        root.addElement(movingPanel(window, move, status)); // added last so it passes over the list

        new Engine().start(window);
    }

    /**
     * A draggable themed panel holding one of each popup-opening element. While {@code move} is on it bounces
     * around inside the window.
     */
    private static Container movingPanel(Window window, ToggleSwitchOverlay move, TextOverlay status) {
        Container panel = new Container(60, 150, 300, 290);
        panel.useTheme();
        panel.setDraggable(true);

        panel.addElement(label("Drag me by the background", 15f, 16, 12));

        DropdownOverlay<String> fruit = new DropdownOverlay<>(200, 30);
        fruit.setPosition(16, 48);
        fruit.setPlaceholder("Pick a fruit");
        for (String name : new String[]{"Apple", "Banana", "Cherry", "Date", "Elderberry", "Fig", "Grape", "Honeydew",
                "Kiwi", "Lemon", "Mango", "Nectarine"}) {
            fruit.addItem(name);
        }
        fruit.onSelect(f -> status.setText("Fruit: " + f));
        panel.addElement(fruit);

        ColorPickerOverlay color = new ColorPickerOverlay(200, 30, new Color(0.12f, 0.53f, 0.90f, 1f));
        color.setPosition(16, 96);
        color.onChange(c -> status.setText("Color: " + color.getHex()));
        panel.addElement(color);

        DateTimePickerOverlay date = new DateTimePickerOverlay(200, 30, Mode.DATE);
        date.setPosition(16, 144);
        date.setPlaceholder("Pick a date");
        date.onChange(p -> status.setText("Date: " + p.getDisplayText()));
        panel.addElement(date);

        ButtonOverlay more = new ButtonOverlay("More...", 100, 30);
        more.setPosition(16, 192);
        PopupMenu moreMenu = new PopupMenu(
                new MenuItem("Rename", () -> status.setText("Rename")),
                new MenuItem("Duplicate", () -> status.setText("Duplicate")),
                MenuItem.submenu("Share",
                        new MenuItem("Email", () -> status.setText("Share by email")),
                        new MenuItem("Link", () -> status.setText("Share a link"))),
                MenuItem.separator(),
                new MenuItem("Delete", () -> status.setText("Delete")));
        more.onAction(() -> moreMenu.showBelow(more));
        panel.addElement(more);

        panel.addElement(label("Right-click the background for a menu", 13f, 16, 244));
        panel.setContextMenu(new PopupMenu(
                new MenuItem("Center the panel", () -> {
                    panel.setX((window.getWindowOptions().getWidth() - panel.getWidth()) / 2);
                    panel.setY((window.getWindowOptions().getHeight() - panel.getHeight()) / 2);
                }),
                MenuItem.submenu("Send to corner",
                        new MenuItem("Bottom right", () -> {
                            panel.setX(window.getWindowOptions().getWidth() - panel.getWidth());
                            panel.setY(window.getWindowOptions().getHeight() - panel.getHeight());
                        }),
                        new MenuItem("Top left", () -> panel.setPosition(0, 0)))));

        // Bounce inside the window while the switch is on.
        float[] velocity = {SPEED_X, SPEED_Y};
        panel.onUpdate(event -> {
            if (!move.isOn()) return;
            double maxX = window.getWindowOptions().getWidth() - panel.getWidth();
            double maxY = window.getWindowOptions().getHeight() - panel.getHeight();
            double x = panel.getX() + velocity[0] * event.getDelta();
            double y = panel.getY() + velocity[1] * event.getDelta();
            if (x < 0 || x > maxX) velocity[0] = -velocity[0];
            if (y < 0 || y > maxY) velocity[1] = -velocity[1];
            panel.setX(Math.max(0, Math.min(x, maxX)));
            panel.setY(Math.max(0, Math.min(y, maxY)));
        });
        return panel;
    }

    /**
     * A scrolling list of rows, each with its own dropdown.
     */
    private static ScrollContainer scrollList(TextOverlay status) {
        ScrollContainer list = new ScrollContainer(640, 150, 300, 260);
        list.useTheme();

        for (int i = 0; i < 12; i++) {
            int row = i + 1;
            list.addElement(label("Row " + row, 14f, 16, 18 + i * 50));

            DropdownOverlay<String> size = new DropdownOverlay<>(150, 30);
            size.setPosition(110, 12 + i * 50);
            size.setPlaceholder("Size");
            for (String s : new String[]{"Small", "Medium", "Large", "Extra large"}) size.addItem(s);
            size.onSelect(s -> status.setText("Row " + row + ": " + s));
            list.addElement(size);
        }
        return list;
    }

    private static TextOverlay label(String text, float size, double x, double y) {
        TextOverlay label = new TextOverlay(text, size, Color.WHITE);
        label.setPosition(x, y);
        return label;
    }
}
