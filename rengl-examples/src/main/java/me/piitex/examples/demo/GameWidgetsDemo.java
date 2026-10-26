package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.input.Binding;
import me.piitex.engine.input.InputContext;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.ConsoleLog;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.menu.RadialMenu;
import me.piitex.engine.ui.overlays.GaugeOverlay;
import me.piitex.engine.ui.overlays.HotkeyOverlay;
import me.piitex.engine.ui.overlays.InventoryGridOverlay;
import me.piitex.engine.ui.overlays.JoystickOverlay;
import me.piitex.engine.ui.overlays.KnobOverlay;
import me.piitex.engine.ui.overlays.MinimapOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.lwjgl.glfw.GLFW;

import java.io.File;

/**
 * The widgets a game's HUD and menus are made of, in one window:
 * <ul>
 *   <li>a {@link KnobOverlay} driving a {@link GaugeOverlay} (turn the knob, the needle eases to it);</li>
 *   <li>a {@link JoystickOverlay} that steers a marker on a {@link MinimapOverlay} (click the map to teleport, wheel to zoom);</li>
 *   <li>two linked {@link InventoryGridOverlay}s: drag items between them, Ctrl-drag for half a stack, Shift-click to send a stack across;</li>
 *   <li>{@link HotkeyOverlay} rows that rebind actions of an {@link InputContext}, warning when a key is taken;</li>
 *   <li>a {@link ConsoleLog} that echoes everything and answers a few commands (Tab completes);</li>
 *   <li>a {@link RadialMenu} on a right-click anywhere on the background.</li>
 * </ul>
 */
public class GameWidgetsDemo {
    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Game Widgets Demo").setDimensions(980, 640));

        Container root = new Container(980, 640);
        window.addContainer(root);

        ConsoleLog console = new ConsoleLog(420, 250);
        console.setPosition(540, 370);
        console.info("Right-click the background for the radial menu.");
        console.setCompleter(prefix -> java.util.stream.Stream.of("help", "give potion", "give sword", "clear")
                .filter(c -> c.startsWith(prefix)).toList());

        // Knob and gauge
        label(root, "Throttle", 24, 16);
        GaugeOverlay gauge = new GaugeOverlay(170, 0, 100);
        gauge.setPosition(24, 40);
        gauge.setStyle(GaugeOverlay.Style.NEEDLE);
        gauge.setTitle("% power");
        gauge.setTickCount(6);
        gauge.addZone(0, 60, Color.GREEN).addZone(60, 85, Color.YELLOW).addZone(85, 100, Color.RED);
        root.addElement(gauge);
        KnobOverlay knob = new KnobOverlay(80, 0, 100);
        knob.setPosition(220, 80);
        knob.setStep(1);
        knob.setDefaultValue(0);
        knob.setLabelFormatter(v -> String.valueOf(Math.round(v)));
        knob.onValueChange(gauge::setValue);
        root.addElement(knob);
        label(root, "Drag the knob up and down;\ndouble-click resets it.", 220, 170);

        // Joystick and minimap
        label(root, "Map", 330, 16);
        MinimapOverlay map = new MinimapOverlay(200, 200);
        map.setPosition(330, 40);
        map.setWorld(0, 0, 2000, 2000);
        map.setViewport(700, 700, 600, 400);
        MinimapOverlay.Marker player = map.addMarker(1000, 1000, Color.WHITE).setShape(MinimapOverlay.Shape.ARROW).setSize(5);
        map.addMarker(300, 400, Color.RED).setShape(MinimapOverlay.Shape.DIAMOND);
        map.addMarker(1600, 1500, Color.CYAN).setShape(MinimapOverlay.Shape.SQUARE);
        map.onNavigate((x, y) -> player.setPosition(x, y));
        map.onMarkerClick(m -> console.info("Clicked a marker at " + Math.round(m.getX()) + ", " + Math.round(m.getY())));
        root.addElement(map);
        label(root, "Stick", 330, 252);
        JoystickOverlay stick = new JoystickOverlay(120);
        stick.setPosition(330, 276);
        root.addElement(stick);
        // The stick drives the marker; the map's update step does the moving, so it keeps going while the stick is held.
        map.onUpdate(event -> {
            double speed = 400 * event.getDelta();
            if (stick.isActive()) {
                player.setPosition(Math.max(0, Math.min(2000, player.getX() + stick.getAxisX() * speed)),
                        Math.max(0, Math.min(2000, player.getY() - stick.getAxisY() * speed)));
                player.setHeading(-stick.getAngle());
            }
        });

        // Inventories
        label(root, "Backpack", 24, 260);
        InventoryGridOverlay<String> bag = new InventoryGridOverlay<>(5, 3, 46);
        bag.setPosition(24, 284);
        InventoryGridOverlay<String> chest = new InventoryGridOverlay<>(5, 3, 46);
        chest.setPosition(24, 440);
        label(root, "Chest (linked)", 24, 416);
        for (InventoryGridOverlay<String> grid : java.util.List.of(bag, chest)) {
            grid.setMaxStack(item -> item.equals("Sword") ? 1 : 20);
            grid.setBorderProvider(item -> item.equals("Sword") ? Color.GOLD : null);
            grid.setTooltipProvider(item -> item + (item.equals("Sword") ? "\nDoes not stack" : "\nStacks to 20"));
            grid.onUse(slot -> console.info("Used " + grid.getItem(slot)));
            grid.onDropOutside((slot, item, count) -> {
                console.warn("Dropped " + count + " " + item + " on the ground");
                return true;
            });
        }
        bag.link(chest);
        bag.add("Potion", 12);
        bag.add("Sword", 1);
        bag.add("Arrow", 35);
        chest.add("Potion", 9);
        chest.setLocked(14, true);
        chest.setSlotFilter(0, item -> item.equals("Sword"));
        root.addElement(bag);
        root.addElement(chest);

        // Hotkeys
        label(root, "Controls (click one, press a key; Backspace unbinds)", 540, 16);
        InputContext controls = new InputContext("demo")
                .bind("jump", Binding.key(GLFW.GLFW_KEY_SPACE))
                .bind("fire", Binding.key(GLFW.GLFW_KEY_F))
                .bind("inventory", Binding.key(GLFW.GLFW_KEY_I));
        HotkeyOverlay[] boxes = new HotkeyOverlay[3];
        String[] actions = {"jump", "fire", "inventory"};
        for (int i = 0; i < actions.length; i++) {
            label(root, actions[i], 540, 48 + i * 40);
            HotkeyOverlay box = new HotkeyOverlay(170, 30);
            box.setPosition(650, 42 + i * 40);
            box.bindTo(controls, actions[i]);
            boxes[i] = box;
            box.onListenStart(() -> {
                for (HotkeyOverlay other : boxes) if (other != null && other != box) other.stopListening();
            });
            root.addElement(box);
        }
        HotkeyOverlay mouseBox = new HotkeyOverlay(170, 30);
        mouseBox.setPosition(650, 42 + 3 * 40);
        mouseBox.setAllowMouse(true);
        mouseBox.setBinding(Binding.mouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
        mouseBox.onBindingChange(b -> console.info("Pan is now " + (b == null ? "unbound" : b.getDisplayName())));
        label(root, "pan (mouse ok)", 540, 48 + 3 * 40);
        root.addElement(mouseBox);

        // Console
        console.onCommand(line -> {
            switch (line) {
                case "help" -> console.info("Commands: help, give <item>, clear");
                case "clear" -> console.clear();
                case "give potion" -> console.info(bag.add("Potion", 5) == 0 ? "Added 5 potions" : "Bag is full");
                case "give sword" -> console.info(bag.add("Sword", 1) == 0 ? "Added a sword" : "Bag is full");
                default -> console.error("Unknown command: " + line);
            }
        });
        console.chat("Ana", "welcome to the demo", Color.CYAN);
        root.addElement(console);

        // Radial menu on a right-click of the background
        RadialMenu menu = new RadialMenu();
        menu.addItem("Heal", Feather.HEART, () -> console.info("Healed"));
        menu.addItem("Map", Feather.MAP, () -> console.info("Opened the map"));
        menu.addItem("Build", Feather.HOME, () -> console.info("Build mode"));
        menu.addItem("Wave", Feather.SMILE, () -> console.info("You wave"));
        menu.addItem("Locked", Feather.LOCK, () -> {
        }).setEnabled(false);
        menu.addItem("Sleep", Feather.MOON, () -> console.info("Zzz"));
        root.onMouseClick(event -> {
            if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) menu.show(window, event.getMouseX(), event.getMouseY());
        });

        new Engine().start(window);
    }

    private static void label(Container root, String text, double x, double y) {
        TextOverlay label = new TextOverlay(text, 15f, Color.WHITE);
        label.setPosition(x, y);
        root.addElement(label);
    }
}
