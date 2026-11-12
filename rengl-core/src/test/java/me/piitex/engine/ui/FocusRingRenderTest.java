package me.piitex.engine.ui;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.gl.renderer.Snapshot;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import org.jetbrains.skia.Bitmap;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The ring around a keyboard-focused control is drawn, and only for the control that has focus. */
class FocusRingRenderTest {
    private final Window window = new Window(new WindowOptions("test").setDimensions(240, 120));
    private final Container panel = new Container(0, 0, 240, 120);
    private final ButtonOverlay button = new ButtonOverlay("Save", 80, 30);
    private final CheckBoxOverlay box = new CheckBoxOverlay(20);

    FocusRingRenderTest() {
        button.setPosition(20, 20);
        box.setPosition(20, 70);
        panel.addElement(button);
        panel.addElement(box);
        window.addContainer(panel);
    }

    private int pixel(Bitmap bitmap, int x, int y) {
        return bitmap.getColor(x, y);
    }

    @Test
    void theFocusedControlGetsARingAndTheOtherDoesNot() throws Exception {
        try (Snapshot before = Snapshot.of(panel, 1f)) {
            window.focusNext(); // the button, by keyboard
            try (Snapshot after = Snapshot.of(panel, 1f)) {
                Path dir = Path.of(System.getProperty("rengl.test.pictures", "target/test-pictures"));
                Files.createDirectories(dir);
                after.savePng(dir.resolve("focus-ring-button.png"));

                Bitmap a = Bitmap.Companion.makeFromImage(before.getImage());
                Bitmap b = Bitmap.Companion.makeFromImage(after.getImage());
                // Just left of the button's left edge, half way down: the ring sits there and nothing else does.
                int x = 20 - 2, y = 20 + 15;
                assertNotEquals(pixel(a, x, y), pixel(b, x, y), "ring appears beside the focused button");
                // The check box is not focused, so its surroundings did not change.
                int cx = 20 - 2, cy = 70 + 10;
                assertEquals(pixel(a, cx, cy), pixel(b, cx, cy), "no ring on the control that lacks focus");
            }
        }
    }

    @Test
    void aClickedControlShowsNoRing() throws Exception {
        try (Snapshot before = Snapshot.of(panel, 1f)) {
            window.requestFocus(button); // as a click or code does, not a key
            try (Snapshot after = Snapshot.of(panel, 1f)) {
                Bitmap a = Bitmap.Companion.makeFromImage(before.getImage());
                Bitmap b = Bitmap.Companion.makeFromImage(after.getImage());
                assertEquals(pixel(a, 18, 35), pixel(b, 18, 35));
            }
        }
    }
}
