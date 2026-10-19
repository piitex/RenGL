package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ColorPickerOverlay;
import me.piitex.engine.ui.overlays.DateTimePickerOverlay;
import me.piitex.engine.ui.overlays.DateTimePickerOverlay.Mode;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.LightTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.time.LocalDate;

/**
 * Shows {@link ToggleSwitchOverlay}, {@link ColorPickerOverlay} and {@link DateTimePickerOverlay}.
 * <ul>
 *   <li><b>Switches</b>: the first one flips the theme, the second reports its state below.
 *       Click one, then press Space to toggle it from the keyboard.</li>
 *   <li><b>Color picker</b>: drag in the square and bars, type a hex code, or pick a preset. The panel to
 *       its right follows the color live.</li>
 *   <li><b>Date picker</b>: limited to the next 30 days, so earlier days are dimmed. Arrow keys move the highlighted
 *       day, Page Up/Down change month, Enter picks.</li>
 *   <li><b>Time picker</b>: a 12-hour clock in 15-minute steps. The mouse wheel over a column changes it.</li>
 *   <li><b>Date and time</b>: both at once, with a Clear button. Tab switches between the calendar and the clock.</li>
 * </ul>
 */
public class PickerDemo {

    static void main() {
        // To reduce console spam add these jvm flags
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny

        Window window = new Window(new WindowOptions("Pickers").setDimensions(900, 640));
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(900, 640);
        root.getStyling().setBackgroundColor(Color.TRANSPARENT);
        root.getStyling().setHoverColor(Color.TRANSPARENT);
        root.getStyling().setBorderThickness(0f);
        window.addContainer(root);

        root.addElement(label("Toggle switch, color picker, date and time pickers", 22f, 40, 30));
        TextOverlay status = label("Change something.", 14f, 40, 590);
        root.addElement(status);

        // Switches.
        ToggleSwitchOverlay dark = new ToggleSwitchOverlay(44, 24, true);
        dark.setPosition(40, 90);
        dark.onToggle(on -> {
            ThemeManager.setCurrent(on ? new DarkTheme() : new LightTheme());
            status.setText("Dark mode " + (on ? "on" : "off"));
        });
        root.addElement(dark);
        root.addElement(label("Dark mode", 15f, 100, 91));

        ToggleSwitchOverlay notifications = new ToggleSwitchOverlay();
        notifications.setPosition(40, 130);
        notifications.onToggle(on -> status.setText("Notifications " + (on ? "on" : "off")));
        root.addElement(notifications);
        root.addElement(label("Notifications", 15f, 100, 131));

        // Color picker with a live preview.
        root.addElement(label("Accent color", 15f, 40, 190));
        ColorPickerOverlay accent = new ColorPickerOverlay(190, 34, new Color(0.12f, 0.53f, 0.90f, 1f));
        accent.setPosition(40, 214);
        Container preview = new Container(250, 214, 150, 34);
        preview.getStyling().setBackgroundColor(accent.getColor());
        preview.getStyling().setHoverColor(accent.getColor());
        preview.getStyling().setCornerRadius(6f);
        accent.onChange(c -> {
            preview.getStyling().setBackgroundColor(c);
            preview.getStyling().setHoverColor(c);
            status.setText("Accent " + accent.getHex());
        });
        root.addElement(accent);
        root.addElement(preview);

        // Date picker limited to the next 30 days.
        root.addElement(label("Date (next 30 days)", 15f, 40, 290));
        DateTimePickerOverlay date = new DateTimePickerOverlay(190, 34, Mode.DATE);
        date.setPosition(40, 314);
        date.setPlaceholder("Pick a date");
        date.setMinDate(LocalDate.now());
        date.setMaxDate(LocalDate.now().plusDays(30));
        date.onChange(p -> status.setText("Date " + p.getDisplayText()));
        root.addElement(date);

        // 12-hour time in 15-minute steps.
        root.addElement(label("Time (15 minute steps)", 15f, 300, 290));
        DateTimePickerOverlay time = new DateTimePickerOverlay(190, 34, Mode.TIME);
        time.setPosition(300, 314);
        time.setPlaceholder("Pick a time");
        time.setUse24Hour(false);
        time.setMinuteStep(15);
        time.onChange(p -> status.setText("Time " + p.getDisplayText()));
        root.addElement(time);

        // Both, clearable.
        root.addElement(label("Date and time", 15f, 40, 380));
        DateTimePickerOverlay both = new DateTimePickerOverlay(260, 34, Mode.DATE_TIME);
        both.setPosition(40, 404);
        both.setPlaceholder("Pick a date and time");
        both.setClearable(true);
        both.onChange(p -> status.setText("Date and time " + (p.getDisplayText() == null ? "cleared" : p.getDisplayText())));
        root.addElement(both);

        new Engine().start(window);
    }

    private static TextOverlay label(String text, float size, double x, double y) {
        TextOverlay label = new TextOverlay(text, size, Color.WHITE);
        label.setPosition(x, y);
        return label;
    }
}
