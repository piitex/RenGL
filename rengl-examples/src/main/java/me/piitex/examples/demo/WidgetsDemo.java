package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.AccordionContainer;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ListBox;
import me.piitex.engine.ui.containers.MessageBox;
import me.piitex.engine.ui.containers.StatusBar;
import me.piitex.engine.ui.containers.ToastManager;
import me.piitex.engine.ui.containers.ToolBar;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.ComboBoxOverlay;
import me.piitex.engine.ui.overlays.RangeSliderOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The everyday controls in one window:
 * <ul>
 *   <li>a {@link ListBox} of save slots: click, Ctrl/Shift-click for several, type to jump, double-click to load;</li>
 *   <li>a {@link ComboBoxOverlay} that suggests cities as you type (Down or the arrow shows them all);</li>
 *   <li>a {@link RangeSliderOverlay} picking a price range, with a live readout in the {@link StatusBar};</li>
 *   <li>an {@link AccordionContainer} of settings sections that fold;</li>
 *   <li>a {@link ToolBar} whose buttons raise {@link ToastManager} toasts and {@link MessageBox} dialogs.</li>
 * </ul>
 */
public class WidgetsDemo {
    private static final String[] CITIES = {"Amsterdam", "Athens", "Auckland", "Bangkok", "Barcelona", "Beijing", "Berlin", "Bern",
            "Boston", "Brussels", "Budapest", "Cairo", "Chicago", "Copenhagen", "Dallas", "Delhi", "Dubai", "Dublin", "Helsinki",
            "Istanbul", "Lagos", "Lima", "Lisbon", "London", "Madrid", "Mexico City", "Montreal", "Moscow", "Mumbai", "Nairobi",
            "New York", "Oslo", "Paris", "Prague", "Rome", "Seoul", "Singapore", "Stockholm", "Sydney", "Tokyo", "Toronto", "Vienna",
            "Warsaw", "Zurich"};

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Widgets Demo").setDimensions(980, 640));

        Container root = new Container(980, 640);
        window.addContainer(root);
        ToastManager toasts = ToastManager.of(window);

        StatusBar status = new StatusBar(980);
        status.setPosition(0, 612);
        status.setMessage("Ready");
        var selection = status.addRightText("0 selected");
        var price = status.addRightText("$100 - $400");

        ToolBar toolBar = new ToolBar(980);
        toolBar.addButton(Feather.SAVE, "Save (shows a toast)", () -> toasts.success("Game saved"));
        toolBar.addButton(Feather.ALERT_TRIANGLE, "Warning toast", () -> toasts.warning("Low disk space", "Only 1.2 GB left on the drive."));
        toolBar.addButton(Feather.X_CIRCLE, "Error toast", () -> toasts.error("Upload failed", "The server did not answer in time."));
        toolBar.addSeparator();
        toolBar.addButton("Confirm", () -> MessageBox.confirm(window, "Delete save?", "This cannot be undone.", "Delete", "Keep",
                yes -> toasts.info(yes ? "Deleted" : "Kept")));
        toolBar.addButton("Prompt", () -> MessageBox.prompt(window, "Rename city", "New name:", "Springfield",
                name -> toasts.info(name == null ? "Rename cancelled" : "Renamed to " + name)));
        toolBar.addButton("Choose", () -> MessageBox.choose(window, "Unsaved changes", "Save before closing?",
                List.of("Save", "Discard", "Cancel"), i -> toasts.info(i < 0 ? "Dismissed" : "Pressed button " + i)));
        toolBar.addSpacer();
        toolBar.addButton("Undo toast", () -> toasts.show(new ToastManager.Toast("Item removed").setDuration(6f)
                .setAction("Undo", () -> toasts.success("Restored"))));
        root.addElement(toolBar);

        TextOverlay savesTitle = new TextOverlay("Save slots", 16f, Color.WHITE);
        savesTitle.setPosition(16, 52);
        root.addElement(savesTitle);
        List<String> slots = new ArrayList<>();
        for (int i = 1; i <= 80; i++) slots.add("Slot " + i + " - " + CITIES[i % CITIES.length]);
        ListBox<String> saves = new ListBox<>(280, 500);
        saves.setPosition(16, 76);
        saves.useTheme();
        saves.setMultiSelect(true);
        saves.setStriped(true);
        saves.setItems(slots);
        saves.setEmptyText("No saves");
        saves.onSelectionChange(chosen -> selection.setText(chosen.size() + " selected"));
        saves.onActivate(slot -> toasts.info("Loading", slot));
        root.addElement(saves);

        TextOverlay cityTitle = new TextOverlay("City (type to search)", 16f, Color.WHITE);
        cityTitle.setPosition(330, 52);
        root.addElement(cityTitle);
        ComboBoxOverlay<String> city = new ComboBoxOverlay<>(320, 34);
        city.setPosition(330, 76);
        city.setItems(List.of(CITIES));
        city.setPlaceholder("Start typing a city...");
        city.onItemSelect(name -> status.setMessage("Selected " + name));
        root.addElement(city);

        TextOverlay rangeTitle = new TextOverlay("Price range", 16f, Color.WHITE);
        rangeTitle.setPosition(330, 134);
        root.addElement(rangeTitle);
        RangeSliderOverlay range = new RangeSliderOverlay(320, 56, 0, 500, 100, 400);
        range.setPosition(330, 166);
        range.setStep(10);
        range.setMinSpan(20);
        range.setTickSpacing(100);
        range.setLabelFormatter(v -> "$" + Math.round(v));
        range.onRangeChange((low, high) -> price.setText("$" + Math.round(low) + " - $" + Math.round(high)));
        root.addElement(range);

        TextOverlay settingsTitle = new TextOverlay("Settings", 16f, Color.WHITE);
        settingsTitle.setPosition(330, 244);
        root.addElement(settingsTitle);
        AccordionContainer settings = new AccordionContainer(420);
        settings.setPosition(330, 268);
        settings.useTheme();
        settings.addSection("Graphics", checks("Fullscreen", "Vsync", "Shadows"), true);
        VerticalLayout audio = new VerticalLayout(400, 0);
        audio.setSpacing(8f);
        audio.setSizeToContent(false, true);
        audio.addElement(new TextOverlay("Master volume", 14f, Color.WHITE));
        audio.addElement(new SliderOverlay(300, 0, 100, 70));
        settings.addSection("Audio", audio);
        TextFlowOverlay help = new TextFlowOverlay("Click a title to fold or open its section. Tab to the settings and use the "
                + "arrow keys and Enter, too. Turn on exclusive mode to keep one open at a time.", 400, 14f, Color.WHITE);
        help.setAutoHeight(true);
        settings.addSection("Help", help);
        settings.onToggle((section, open) -> status.setMessage(section.getTitle() + (open ? " opened" : " folded")));
        root.addElement(settings);

        root.addElement(status);
        new Engine().start(window);
    }

    private static VerticalLayout checks(String... labels) {
        VerticalLayout column = new VerticalLayout(400, 0);
        column.setSpacing(8f);
        column.setSizeToContent(false, true);
        for (String label : labels) {
            me.piitex.engine.ui.layout.HorizontalLayout row = new me.piitex.engine.ui.layout.HorizontalLayout(300, 22);
            row.setSpacing(8f);
            row.addElement(new CheckBoxOverlay(18));
            row.addElement(new TextOverlay(label, 15f, Color.WHITE));
            column.addElement(row);
        }
        return column;
    }
}
