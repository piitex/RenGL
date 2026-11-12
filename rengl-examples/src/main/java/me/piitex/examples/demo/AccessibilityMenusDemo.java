package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.access.speech.Accessibility;
import me.piitex.engine.access.speech.SpeechHost;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.MessageBox;
import me.piitex.engine.ui.containers.SplitContainer;
import me.piitex.engine.ui.containers.StatusBar;
import me.piitex.engine.ui.containers.ToolBar;
import me.piitex.engine.ui.menu.MenuBarOverlay;
import me.piitex.engine.ui.menu.MenuItem;
import me.piitex.engine.ui.menu.PopupMenu;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.DateTimePickerOverlay;
import me.piitex.engine.ui.overlays.LoadingSpinnerOverlay;
import me.piitex.engine.ui.overlays.RadioButtonOverlay;
import me.piitex.engine.ui.overlays.RadioGroup;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;

/**
 * The fourth accessibility page: menus, dialogs and the rest of the toolkit. Press Tab to move around.
 * <ul>
 *   <li><b>Menu bar.</b> Tab to it. Left and Right choose File or Edit, Down (or Enter) opens it, then Up and Down read each item,
 *       Right enters a submenu, Left leaves it, Escape closes. F10 also opens it.</li>
 *   <li><b>Toolbar.</b> Three icon-less buttons named by their tooltips.</li>
 *   <li><b>Date and time.</b> Enter opens the calendar or clock; arrows move and read; Enter picks. Or type a date and press Enter.</li>
 *   <li><b>Difficulty.</b> Radio buttons that say where each is in the group.</li>
 *   <li><b>Divider.</b> Tab to it and use Left and Right to resize the panes; Shift moves further.</li>
 *   <li><b>Buttons.</b> Show a dialog, show a prompt, set the status message, and stop the spinner.</li>
 * </ul>
 */
public class AccessibilityMenusDemo {
    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Accessibility Menus Demo").setDimensions(860, 640).setSplashEnabled(false));

        Container root = new Container(860, 640);
        window.addContainer(root);

        // Menu bar, drawn in the window on every platform so it can be heard on any of them.
        MenuBarOverlay bar = new MenuBarOverlay(860);
        bar.setUseNativeMenuBar(false);
        bar.setPosition(0, 0);
        boolean[] grid = {true};
        bar.addMenu("File",
                new MenuItem("New", () -> System.out.println("[demo] New")),
                new MenuItem("Open", () -> System.out.println("[demo] Open")),
                MenuItem.separator(),
                MenuItem.check("Show grid", true, on -> {
                    grid[0] = on;
                    System.out.println("[demo] grid " + on);
                }),
                MenuItem.submenu("Recent files",
                        new MenuItem("level1.map", () -> System.out.println("[demo] level1")),
                        new MenuItem("level2.map", () -> System.out.println("[demo] level2"))),
                new MenuItem("Quit", () -> System.out.println("[demo] Quit")));
        bar.addMenu("Edit",
                new MenuItem("Undo", () -> System.out.println("[demo] Undo")),
                new MenuItem("Redo", () -> System.out.println("[demo] Redo")));
        bar.getMenus().get(0).getItems().getFirst().setShortcut("Ctrl+N");
        root.addElement(bar);

        ToolBar tools = new ToolBar(860);
        tools.setPosition(0, 30);
        tools.addButton("Cut", () -> System.out.println("[demo] Cut"));
        ButtonOverlay iconOnly = tools.addButton(null, "Paste from clipboard", () -> System.out.println("[demo] Paste"));
        iconOnly.setWidth(36);
        root.addElement(tools);

        label(root, "Appointment", 30, 90);
        DateTimePickerOverlay date = new DateTimePickerOverlay(180, 32);
        date.setPosition(30, 115);
        date.setClearable(true);
        date.onChange(p -> System.out.println("[demo] date " + p.getDate()));
        root.addElement(date);

        label(root, "Time", 250, 90);
        DateTimePickerOverlay time = new DateTimePickerOverlay(140, 32, DateTimePickerOverlay.Mode.TIME);
        time.setPosition(250, 115);
        time.onChange(p -> System.out.println("[demo] time " + p.getTime()));
        root.addElement(time);

        label(root, "Difficulty", 30, 170);
        RadioGroup group = new RadioGroup();
        String[] levels = {"Easy", "Normal", "Hard"};
        for (int i = 0; i < levels.length; i++) {
            RadioButtonOverlay radio = new RadioButtonOverlay();
            radio.setPosition(30, 200 + i * 32);
            radio.setAccessibleName(levels[i]);
            group.add(radio);
            root.addElement(radio);
            label(root, levels[i], 60, 200 + i * 32);
        }
        group.setSelected(group.getButtons().get(1));

        SplitContainer split = new SplitContainer(SplitContainer.Orientation.HORIZONTAL, 400, 160);
        split.setPosition(250, 200);
        Container left = new Container(10, 10);
        Container right = new Container(10, 10);
        left.addElement(new TextOverlay("Sidebar"));
        right.addElement(new TextOverlay("Main area"));
        split.setFirst(left);
        split.setSecond(right);
        split.setDividerAccessibleName("Sidebar width");
        root.addElement(split);

        StatusBar status = new StatusBar(860);
        status.setPosition(0, 610);
        status.setMessage("Ready");
        status.addRightText("Ln 1, Col 1");
        root.addElement(status);

        LoadingSpinnerOverlay spinner = new LoadingSpinnerOverlay(28);
        spinner.setPosition(30, 400);
        root.addElement(spinner);

        ButtonOverlay dialog = new ButtonOverlay("Show a dialog", 180, 40);
        dialog.setPosition(30, 440);
        dialog.onAction(() -> MessageBox.confirm(window, "Delete level?", "This cannot be undone.", yes ->
                status.setMessage(yes ? "Level deleted" : "Kept the level")));
        root.addElement(dialog);

        ButtonOverlay prompt = new ButtonOverlay("Show a prompt", 180, 40);
        prompt.setPosition(30, 490);
        prompt.onAction(() -> MessageBox.prompt(window, "Rename level", "Type the new name and press Enter.", "level1", name ->
                status.setMessage(name == null ? "Rename cancelled" : "Renamed to " + name)));
        root.addElement(prompt);

        ButtonOverlay saved = new ButtonOverlay("Set status to Saved", 180, 40);
        saved.setPosition(250, 380);
        saved.onAction(() -> status.setMessage("Saved at " + java.time.LocalTime.now().withNano(0)));
        root.addElement(saved);

        ButtonOverlay stop = new ButtonOverlay("Stop the spinner", 180, 40);
        stop.setPosition(250, 430);
        stop.onAction(() -> spinner.setSpinning(false));
        root.addElement(stop);

        PopupMenu context = new PopupMenu(new MenuItem("Copy", () -> System.out.println("[demo] Copy")),
                new MenuItem("Paste", () -> System.out.println("[demo] Paste")));
        ButtonOverlay more = new ButtonOverlay("More...", 180, 40);
        more.setPosition(250, 480);
        more.onAction(() -> context.showBelow(more));
        root.addElement(more);

        SpeechHost speech = Accessibility.enable(window);
        System.out.println("[demo] ready; self-voicing " + (speech.isEnabled() ? "on" : "off"));
        new Engine().start(window);
    }

    private static TextOverlay label(Container root, String text, double x, double y) {
        TextOverlay label = new TextOverlay(text);
        label.setPosition(x, y);
        root.addElement(label);
        return label;
    }
}
