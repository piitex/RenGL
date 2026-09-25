package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.background.ElectricNodeBackground;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.RainbowColor;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;

/**
 * Shows {@link ElectricNodeBackground}: three bouncing nodes that arc to the walls, to each other, and to the
 * cursor when it comes near. "Discharge" fires a burst from every node, and "Color" cycles the glow palette,
 * ending on a {@link RainbowColor} RGB cycle.
 */
public class ElectricDemo {
    private static final Color[][] PALETTES = {
            {new Color(0.35f, 0.7f, 1f, 1f), new Color(0.92f, 0.97f, 1f, 1f)}, // electric blue
            {new Color(0.7f, 0.35f, 1f, 1f), new Color(1f, 0.9f, 1f, 1f)}, // violet
            {new Color(1f, 0.3f, 0.2f, 1f), new Color(1f, 0.95f, 0.75f, 1f)}, // red
            {new Color(0.3f, 1f, 0.55f, 1f), new Color(0.9f, 1f, 0.92f, 1f)}, // green
            {new RainbowColor(), new RainbowColor(6f, 0.15f, 1f, 1f)}, // RGB: cycling glow, pale core tracking the same hue
    };
    private static int palette;

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED
        Window window = new Window(new WindowOptions("Electric node").setDimensions(1000, 620));
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(1000, 620);
        root.getStyling().setBackgroundColor(new Color(0.04f, 0.05f, 0.09f, 1f));
        root.getStyling().setHoverColor(new Color(0.04f, 0.05f, 0.09f, 1f));
        root.getStyling().setBorderThickness(0f);
        window.addContainer(root);

        ElectricNodeBackground electric = new ElectricNodeBackground();
        electric.setNodeCount(100);
        electric.setNodeRadius(1.5f);
        electric.setMouseInteractionEnabled(true);
        root.setBackground(electric);

        TextOverlay hint = new TextOverlay("Move the mouse near a node to draw its arcs.", 15f, Color.WHITE);
        hint.setPosition(24, 24);
        root.addElement(hint);

        ButtonOverlay discharge = new ButtonOverlay("Discharge", 120, 32);
        discharge.setPosition(24, 560);
        discharge.onAction(electric::discharge);
        root.addElement(discharge);

        ButtonOverlay color = new ButtonOverlay("Color", 100, 32);
        color.setPosition(156, 560);
        color.onAction(() -> {
            palette = (palette + 1) % PALETTES.length;
            electric.setGlowColor(PALETTES[palette][0]);
            electric.setCoreColor(PALETTES[palette][1]);
        });
        root.addElement(color);

        new Engine().start(window);
    }
}
