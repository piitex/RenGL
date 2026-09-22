package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.gl.renderer.Snapshot;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.ScrollingGradient;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.ImageOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Shows {@link Snapshot}s used as save slot thumbnails. The animated "scene" on the left keeps moving. Press a
 * "Save" button to capture the scene container into that slot: the picture is written to {@code saves/slotN.png}
 * in the working directory and shown in the slot straight from memory. Saving again replaces it, so each slot
 * shows the frame you caught.
 * <p>
 * The scene is snapshotted on its own, at 0.375 scale (640x360 becomes a 240x135 thumbnail). The buttons, slots and
 * window background are not part of the picture, because a snapshot only renders the element it is taken from.
 */
public class SnapshotDemo {
    private static final int SCENE_W = 640, SCENE_H = 360;
    private static final int THUMB_W = 240, THUMB_H = 135;
    private static final int SLOTS = 3;

    private static final Snapshot[] saved = new Snapshot[SLOTS];
    private static final ImageOverlay[] previews = new ImageOverlay[SLOTS];
    private static final Container[] slotBoxes = new Container[SLOTS];
    private static final TextOverlay[] captions = new TextOverlay[SLOTS];

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED
        Window window = new Window(new WindowOptions("Snapshot demo").setDimensions(1000, 620));
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(1000, 620);
        root.getStyling().setBackgroundColor(Color.TRANSPARENT);
        root.getStyling().setBorderThickness(0f);

        Container scene = scene();
        root.addElement(scene);

        TextOverlay slotsTitle = new TextOverlay("Save slots", 20, Color.WHITE);
        slotsTitle.setPosition(710, 30);
        root.addElement(slotsTitle);

        for (int i = 0; i < SLOTS; i++) {
            root.addElement(slot(i));
            root.addElement(saveButton(scene, i));
        }

        TextOverlay hint = new TextOverlay("Save any time: each slot keeps the exact frame you caught.", 14, Color.WHITE);
        hint.setPosition(30, 30 + SCENE_H + 20);
        root.addElement(hint);

        window.addContainer(root);
        new Engine().start(window);
    }

    /**
     * A moving scene, so two captures never look the same.
     */
    private static Container scene() {
        Container scene = new Container(30, 60, SCENE_W, SCENE_H);
        scene.getStyling().setBackgroundColor(new ScrollingGradient(
                new Color(0.10f, 0.15f, 0.45f, 1f), new Color(0.75f, 0.30f, 0.45f, 1f), 0.3f));
        scene.getStyling().setCornerRadius(18);

        TextOverlay title = new TextOverlay("Chapter 3: The Lighthouse", 30, Color.WHITE);
        title.setPosition(30, 30);
        scene.addElement(title);

        TextOverlay walker = new TextOverlay("Player", 22, Color.YELLOW);
        walker.setY(240);
        scene.addElement(walker);

        double[] time = {0};
        scene.onUpdate(event -> {
            time[0] += event.getDelta();
            // Sweep left and right across the scene.
            walker.setX(60 + (SCENE_W - 200) * (0.5 + 0.5 * Math.sin(time[0])));
        });
        return scene;
    }

    /**
     * An empty slot: a thumbnail box with a caption under it.
     */
    private static Container slot(int index) {
        Container box = new Container(710, 70 + index * 178, THUMB_W + 20, THUMB_H + 34);
        box.getStyling().setBackgroundColor(new Color(0f, 0f, 0f, 0.35f));
        box.getStyling().setCornerRadius(10);
        slotBoxes[index] = box;

        captions[index] = new TextOverlay("Slot " + (index + 1) + ": empty", 14, Color.WHITE);
        captions[index].setPosition(10, THUMB_H + 14);
        box.addElement(captions[index]);
        return box;
    }

    private static ButtonOverlay saveButton(Container scene, int index) {
        ButtonOverlay button = new ButtonOverlay("Save to slot " + (index + 1), 150, 34);
        button.setPosition(30 + index * 165, 60 + SCENE_H + 50);
        // onAction runs on the render thread, which is what a synchronous takeSnapshot needs.
        button.onAction(() -> save(scene, index));
        return button;
    }

    private static void save(Container scene, int index) {
        Snapshot shot = scene.takeSnapshot((float) THUMB_W / SCENE_W);

        Path file = Path.of("saves", "slot" + (index + 1) + ".png");
        try {
            shot.savePng(file);
        } catch (IOException e) {
            captions[index].setText("Slot " + (index + 1) + ": could not write " + file);
            shot.close();
            return;
        }

        // Swap the thumbnail. The overlay draws the snapshot's image, so the old snapshot is closed only after
        // its overlay has been removed.
        if (previews[index] != null) slotBoxes[index].removeElement(previews[index]);
        if (saved[index] != null) saved[index].close();

        saved[index] = shot;
        previews[index] = shot.toOverlay(THUMB_W, THUMB_H);
        previews[index].setPosition(10, 10);
        slotBoxes[index].addElement(previews[index]);
        captions[index].setText("Slot " + (index + 1) + ": " + file);
    }
}
