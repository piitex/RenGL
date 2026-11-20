package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ListBox;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Two {@link ListBox}es with live controls in their rows.
 * <ul>
 *   <li>Mods: a switch at the right end of each row, beside the label. Type to jump, F2 or Enter to reach the switch,
 *   Space to flip it, Escape to go back to the list.</li>
 *   <li>Players: each row is a small container of a name, a volume slider and a Kick button, stretched over the row.</li>
 * </ul>
 * Only the rows on screen have controls; scroll and they are handed to the rows coming into view.
 */
public class ListWidgetsDemo {
    private static final class Mod {
        final String name;
        boolean enabled;

        Mod(String name, boolean enabled) {
            this.name = name;
            this.enabled = enabled;
        }
    }

    private static final class Player {
        final String name;
        double volume;

        Player(String name, double volume) {
            this.name = name;
            this.volume = volume;
        }
    }

    private static final String[] MOD_NAMES = {"Better Lighting", "Extra Hotkeys", "Faster Boats", "Map Markers",
            "Quiet Footsteps", "Retro Palette", "Sky Overhaul", "Trade Routes", "Weather Plus", "Zoom Out"};

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("List Widgets Demo").setDimensions(860, 560).setSplashEnabled(false));

        Container root = new Container(860, 560);
        window.addContainer(root);
        TextOverlay title = new TextOverlay("Lists with live controls in their rows", 15f, Color.WHITE);
        title.setPosition(16, 12);
        root.addElement(title);
        TextOverlay status = new TextOverlay("", 13f, Color.GRAY);
        status.setPosition(16, 530);
        root.addElement(status);

        List<Mod> mods = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            mods.add(new Mod(MOD_NAMES[i % MOD_NAMES.length] + (i < MOD_NAMES.length ? "" : " " + (i / MOD_NAMES.length + 1)), i % 3 != 0));
        }
        ListBox<Mod> modList = new ListBox<>(340, 460);
        modList.setPosition(16, 40);
        modList.setRowHeight(32);
        modList.setStriped(true);
        modList.setLabelProvider(m -> m.name + (m.enabled ? "" : " (off)"));
        modList.setDimmed(m -> !m.enabled);
        modList.setRowWidget(() -> new ToggleSwitchOverlay(40, 22), (toggle, mod) -> {
            toggle.setOn(mod.enabled);
            toggle.onToggle(on -> {
                mod.enabled = on;
                status.setText(mod.name + (on ? " on" : " off"));
            });
        });
        modList.setItems(mods);
        modList.onSelect(m -> status.setText(m == null ? "" : "Selected " + m.name));
        root.addElement(modList);

        List<Player> players = new ArrayList<>();
        for (int i = 0; i < 40; i++) players.add(new Player("Player " + (i + 1), (i * 29) % 101));
        ListBox<Player> playerList = new ListBox<>(472, 460);
        playerList.setPosition(372, 40);
        playerList.setRowHeight(40);
        playerList.setStriped(true);
        playerList.setLabelProvider(p -> p.name);
        playerList.setRowWidgetFill(true);
        playerList.setRowWidget(() -> {
            Container row = new Container(440, 36);
            TextOverlay name = new TextOverlay("", 14f, Color.WHITE);
            name.setPosition(6, 9);
            row.addElement(name);
            SliderOverlay volume = new SliderOverlay(200, 0, 100);
            volume.setPosition(130, 8);
            row.addElement(volume);
            ButtonOverlay kick = new ButtonOverlay("Kick", 64, 26);
            kick.setPosition(360, 5);
            row.addElement(kick);
            return row;
        }, (row, player) -> {
            List<?> parts = List.copyOf(row.getElements().values());
            ((TextOverlay) parts.get(0)).setText(player.name);
            SliderOverlay volume = (SliderOverlay) parts.get(1);
            volume.setValue(player.volume);
            volume.onValueChange(v -> {
                player.volume = v;
                status.setText(player.name + " volume " + Math.round(v));
            });
            ((ButtonOverlay) parts.get(2)).onMouseClick(_ -> {
                players.remove(player);
                playerList.setItems(players);
                status.setText("Kicked " + player.name);
            });
        });
        playerList.setItems(players);
        playerList.onSelect(p -> status.setText(p == null ? "" : "Selected " + p.name));
        root.addElement(playerList);

        new Engine().start(window);
    }
}
