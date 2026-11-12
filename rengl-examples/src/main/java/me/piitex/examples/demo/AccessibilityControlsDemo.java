package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.access.speech.Accessibility;
import me.piitex.engine.access.speech.SpeechHost;
import me.piitex.engine.ui.containers.AccordionContainer;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ListBox;
import me.piitex.engine.ui.containers.TabContainer;
import me.piitex.engine.ui.containers.TableView;
import me.piitex.engine.ui.containers.TreeView;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.overlays.ColorPickerOverlay;
import me.piitex.engine.ui.overlays.GaugeOverlay;
import me.piitex.engine.ui.overlays.KnobOverlay;
import me.piitex.engine.ui.overlays.RangeSliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.util.List;

/**
 * The second accessibility page: the controls that hold more than one thing. Tab to each and use the arrow keys.
 * <ul>
 *   <li><b>Knob and range slider.</b> Arrows, Home and End change them; Up, Down or Space on the range slider switches thumb.</li>
 *   <li><b>List, tree, table.</b> Up and Down move the row; Right and Left open and close tree items.</li>
 *   <li><b>Tabs.</b> Left and Right change tab.</li>
 *   <li><b>Accordion.</b> Up and Down move between sections, Enter opens or closes one.</li>
 *   <li><b>Colour picker.</b> Left and right change the colour, Up and Down pick hue, saturation, brightness or opacity, Page Up and Down pick a preset, Enter opens the panel with the cursor in the hex field and Escape closes it.</li>
 *   <li><b>Gauge.</b> A Tab stop read as a meter. It moves by itself, and once you Tab onto it each new reading is spoken until you Tab away.</li>
 * </ul>
 * Each change is also written to the console.
 */
public class AccessibilityControlsDemo {
    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Accessibility Controls Demo").setDimensions(820, 620).setSplashEnabled(false));

        Container root = new Container(820, 620);
        window.addContainer(root);

        TextOverlay title = new TextOverlay("Controls with more inside");
        title.setPosition(30, 15);
        root.addElement(title);

        // Left column: knob, range slider, gauge
        TextOverlay knobLabel = label(root, "Master gain", 30, 60);
        KnobOverlay knob = new KnobOverlay(70, 0, 10);
        knob.setStep(1);
        knob.setPosition(30, 85);
        knob.setLabelledBy(knobLabel);
        knob.onValueChange(v -> System.out.println("[demo] Master gain " + Math.round(v)));
        root.addElement(knob);

        TextOverlay rangeLabel = label(root, "Price filter", 30, 175);
        RangeSliderOverlay price = new RangeSliderOverlay(240, 0, 100);
        price.setStep(10);
        price.setRange(20, 80);
        price.setPosition(30, 205);
        price.setLabelledBy(rangeLabel);
        root.addElement(price);

        TextOverlay gaugeLabel = label(root, "Engine heat", 30, 260);
        GaugeOverlay heat = new GaugeOverlay(90, 0, 100);
        heat.setPosition(30, 285);
        heat.setLabelledBy(gaugeLabel);
        root.addElement(heat);

        TextOverlay colorLabel = label(root, "Accent colour", 30, 395);
        ColorPickerOverlay accent = new ColorPickerOverlay(140, 32, Color.BLUE);
        accent.setPosition(30, 420);
        accent.setLabelledBy(colorLabel);
        accent.onChange(c -> System.out.println("[demo] Accent colour " + accent.getAccessibleValue()));
        root.addElement(accent);

        // Middle column: list, tree, table
        TextOverlay listLabel = label(root, "Inventory", 330, 60);
        ListBox<String> inventory = new ListBox<>(220, 110);
        inventory.setItems(List.of("Sword", "Shield", "Health potion", "Rope", "Lantern"));
        inventory.setPosition(330, 85);
        inventory.setLabelledBy(listLabel);
        root.addElement(inventory);

        TextOverlay treeLabel = label(root, "Skills", 330, 210);
        TreeView<String> skills = new TreeView<String>(220, 130, item -> switch (item) {
            case "Combat" -> List.of("Sword", "Bow");
            case "Magic" -> List.of("Fire", "Ice");
            default -> List.of();
        });
        skills.setRoots(List.of("Combat", "Magic", "Crafting"));
        skills.setPosition(330, 235);
        skills.setLabelledBy(treeLabel);
        root.addElement(skills);

        TextOverlay tableLabel = label(root, "Shop", 330, 380);
        TableView<String[]> shop = new TableView<>(440, 110);
        shop.addColumn("Item", row -> row[0]);
        shop.addColumn("Price", row -> row[1]);
        shop.addColumn("Stock", row -> row[2]);
        shop.setItems(List.of(new String[]{"Sword", "40", "3"}, new String[]{"Shield", "25", "5"}, new String[]{"Potion", "8", "20"}));
        shop.setPosition(330, 405);
        shop.setLabelledBy(tableLabel);
        root.addElement(shop);

        // Right column: tabs and accordion
        TabContainer tabs = new TabContainer(220, 90);
        tabs.addTab("Graphics", new TextOverlay("Graphics options"));
        tabs.addTab("Audio", new TextOverlay("Audio options"));
        tabs.addTab("Controls", new TextOverlay("Control options"));
        tabs.setPosition(580, 60);
        root.addElement(tabs);

        AccordionContainer help = new AccordionContainer(220);
        help.addSection("Movement", new TextOverlay("WASD to walk"));
        help.addSection("Combat", new TextOverlay("Click to attack"));
        help.addSection("Trading", new TextOverlay("Talk to a shopkeeper"));
        help.setPosition(580, 190);
        root.addElement(help);

        // The gauge climbs and falls so a listener can follow a value that nobody is pressing a key for.
        Thread.ofVirtual().name("demo-gauge").start(() -> {
            double value = 0;
            double direction = 1;
            while (true) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    return;
                }
                value += 10 * direction;
                if (value >= 100 || value <= 0) direction = -direction;
                double now = value;
                window.runOnMainThread(() -> heat.setValue(now));
            }
        });

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
