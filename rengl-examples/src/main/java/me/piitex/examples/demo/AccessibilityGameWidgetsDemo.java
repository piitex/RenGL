package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.access.speech.Accessibility;
import me.piitex.engine.access.speech.SpeechHost;
import me.piitex.engine.input.Binding;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.ConsoleLog;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.menu.RadialMenu;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.HotkeyOverlay;
import me.piitex.engine.ui.overlays.InventoryGridOverlay;
import me.piitex.engine.ui.overlays.JoystickOverlay;
import me.piitex.engine.ui.overlays.MinimapOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.lwjgl.glfw.GLFW;

import java.io.File;

/**
 * The third accessibility page: the game widgets. Tab to each one.
 * <ul>
 *   <li><b>Joystick.</b> Hold the arrow keys; it says the direction when it changes, and "centered" when you let go.</li>
 *   <li><b>Jump key.</b> Enter, then press a key. Escape cancels, Backspace unbinds. Binding Jump to the key Fire has is refused.</li>
 *   <li><b>Map.</b> Page Up and Page Down move between markers, the arrow keys move a cursor, Enter goes there, plus and minus zoom.</li>
 *   <li><b>Backpack and chest.</b> Arrow keys move between slots, M picks a stack up and puts it down, Shift+Enter sends it to the other grid.</li>
 *   <li><b>Radial menu.</b> Press the button, then the arrow keys read out each wedge and Enter chooses.</li>
 *   <li><b>Console.</b> New lines are read out as they arrive; a burst is cut short.</li>
 * </ul>
 */
public class AccessibilityGameWidgetsDemo {
    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Accessibility Game Widgets Demo").setDimensions(860, 640).setSplashEnabled(false));

        Container root = new Container(860, 640);
        window.addContainer(root);

        TextOverlay title = new TextOverlay("Game widgets");
        title.setPosition(30, 15);
        root.addElement(title);

        // Left column: joystick, two hotkey boxes, map
        TextOverlay stickLabel = label(root, "Move stick", 30, 50);
        JoystickOverlay stick = new JoystickOverlay(110);
        stick.setPosition(30, 75);
        stick.setLabelledBy(stickLabel);
        stick.onMove((x, y) -> System.out.printf("[demo] stick %.2f, %.2f%n", x, y));
        root.addElement(stick);

        TextOverlay jumpLabel = label(root, "Jump key", 30, 200);
        HotkeyOverlay jump = new HotkeyOverlay(170, 32);
        jump.setBinding(Binding.key(GLFW.GLFW_KEY_SPACE));
        jump.setPosition(30, 225);
        jump.setLabelledBy(jumpLabel);
        jump.onBindingChange(b -> System.out.println("[demo] Jump " + (b == null ? "unbound" : b.getDisplayName())));
        root.addElement(jump);

        TextOverlay fireLabel = label(root, "Fire key", 30, 270);
        HotkeyOverlay fire = new HotkeyOverlay(170, 32);
        fire.setBinding(Binding.key(GLFW.GLFW_KEY_F));
        fire.setPosition(30, 295);
        fire.setLabelledBy(fireLabel);
        root.addElement(fire);
        // Each refuses the other's key, so the refusal can be heard.
        jump.setConflictCheck(b -> b.equals(fire.getBinding()) ? "In use by Fire" : null);
        fire.setConflictCheck(b -> b.equals(jump.getBinding()) ? "In use by Jump" : null);

        TextOverlay mapLabel = label(root, "Map", 30, 345);
        MinimapOverlay map = new MinimapOverlay(200, 200);
        map.setWorld(0, 0, 1000, 1000);
        MinimapOverlay.Marker player = map.addMarker(150, 800, Color.WHITE).setShape(MinimapOverlay.Shape.ARROW).setSize(5).setLabel("Player");
        map.addMarker(700, 200, Color.RED).setShape(MinimapOverlay.Shape.DIAMOND).setLabel("Shop");
        map.addMarker(900, 850, Color.CYAN).setShape(MinimapOverlay.Shape.SQUARE).setLabel("Gate");
        map.setFollow(player);
        map.setPosition(30, 370);
        map.setLabelledBy(mapLabel);
        map.onNavigate((x, y) -> {
            player.setPosition(x, y);
            System.out.printf("[demo] player to %.0f, %.0f%n", x, y);
        });
        map.onMarkerClick(m -> System.out.println("[demo] marker " + m.getLabel()));
        root.addElement(map);

        // Middle column: backpack and chest
        TextOverlay bagLabel = label(root, "Backpack", 290, 50);
        InventoryGridOverlay<String> bag = new InventoryGridOverlay<>(4, 3, 50);
        bag.setPosition(290, 75);
        bag.setLabelledBy(bagLabel);
        bag.setMaxStack(item -> item.equals("Potion") ? 20 : 1);
        bag.add("Sword", 1);
        bag.add("Potion", 5);
        bag.add("Rope", 1);
        bag.setLocked(11, true);
        root.addElement(bag);

        TextOverlay chestLabel = label(root, "Chest", 290, 260);
        InventoryGridOverlay<String> chest = new InventoryGridOverlay<>(4, 2, 50);
        chest.setPosition(290, 285);
        chest.setLabelledBy(chestLabel);
        chest.setMaxStack(item -> item.equals("Potion") ? 20 : 1);
        chest.add("Shield", 1);
        chest.add("Potion", 12);
        bag.link(chest);
        root.addElement(chest);
        bag.onUse(slot -> System.out.println("[demo] used " + bag.getItem(slot)));

        // Right column: radial menu button and console
        RadialMenu radial = new RadialMenu();
        radial.addItem("Attack", () -> System.out.println("[demo] Attack"));
        radial.addItem("Defend", () -> System.out.println("[demo] Defend"));
        radial.addItem("Use item", () -> System.out.println("[demo] Use item"));
        radial.addItem("Run", () -> System.out.println("[demo] Run"));
        ButtonOverlay open = new ButtonOverlay("Open radial menu", 200, 40);
        open.setPosition(560, 50);
        open.onAction(() -> radial.show(window, 660, 330));
        root.addElement(open);

        TextOverlay consoleLabel = label(root, "Console", 560, 105);
        ConsoleLog console = new ConsoleLog(270, 240);
        console.setPosition(560, 130);
        console.setLabelledBy(consoleLabel);
        console.info("Welcome");
        console.onCommand(line -> console.info("Ran " + line));
        root.addElement(console);

        ButtonOverlay burst = new ButtonOverlay("Write 12 log lines", 200, 40);
        burst.setPosition(560, 385);
        burst.onAction(() -> {
            for (int i = 1; i <= 12; i++) console.info("Line " + i);
            console.warn("That was a burst");
        });
        root.addElement(burst);

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
