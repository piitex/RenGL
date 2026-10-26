package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.color.Color;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnobOverlayTest {
    private KnobOverlay knob;
    private final List<Double> changes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        knob = new KnobOverlay(64, 0, 100);
        knob.setPosition(0, 0);
        knob.onValueChange(changes::add);
    }

    /** Drags from y = 150 to y = {@code to}; 150 is arbitrary, only the distance counts. */
    private void dragTo(double to) {
        knob.onDragStart(32, 150);
        knob.onDragUpdate(32, to);
        knob.onDragEnd(32, to);
    }

    @Test
    void draggingUpRaisesTheValueByTheFractionOfTheDragRange() {
        dragTo(50); // 100 points up of 200: half the range
        assertEquals(50, knob.getValue(), 0.001);
        assertEquals(0.5, knob.getRatio(), 0.001);
        assertEquals(List.of(50.0), changes);
    }

    @Test
    void draggingDownLowersItAndTheValueStaysInRange() {
        knob.setValue(20);
        dragTo(400); // far below
        assertEquals(0, knob.getValue(), 0.001);
        dragTo(-1000); // far above
        assertEquals(100, knob.getValue(), 0.001);
    }

    @Test
    void theDragIsMeasuredFromWherePressBegan() {
        knob.setValue(40);
        knob.onDragStart(32, 150);
        knob.onDragUpdate(32, 100); // +25
        knob.onDragUpdate(32, 130); // back to +10 of the original, not +10 on top of +25
        assertEquals(50, knob.getValue(), 0.001);
        assertTrue(knob.isDragging());
        knob.onDragEnd(32, 130);
        assertFalse(knob.isDragging());
    }

    @Test
    void aStepSnapsTheValue() {
        knob.setStep(10);
        dragTo(150 - 66); // 33 of 100
        assertEquals(30, knob.getValue(), 0.001);
    }

    @Test
    void settingTheValueIsSilentAndClamped() {
        knob.setValue(250);
        assertEquals(100, knob.getValue(), 0.001);
        knob.setValue(-5);
        assertEquals(0, knob.getValue(), 0.001);
        assertTrue(changes.isEmpty());
    }

    @Test
    void shiftMakesTheDragFiveTimesFiner() {
        KnobOverlay fine = new KnobOverlay(64, 0, 100) {
            @Override
            protected int heldModifiers() {
                return GLFW.GLFW_MOD_SHIFT;
            }
        };
        fine.onDragStart(32, 150);
        fine.onDragUpdate(32, 50); // 100 points: 50 normally, 10 fine
        assertEquals(10, fine.getValue(), 0.001);
    }

    @Test
    void aDoubleClickGoesBackToTheDefault() {
        knob.setDefaultValue(30);
        dragTo(50);
        assertEquals(50, knob.getValue(), 0.001);
        knob.onDragStart(32, 32); // the second press, straight after the first
        knob.onDragEnd(32, 32);
        assertEquals(30, knob.getValue(), 0.001);
        assertFalse(knob.isDragging());
    }

    @Test
    void aDoubleClickWithNoDefaultDoesNothing() {
        dragTo(100);
        double before = knob.getValue();
        knob.onDragStart(32, 100);
        knob.onDragEnd(32, 100);
        assertEquals(before, knob.getValue(), 0.001);
    }

    @Test
    void theWheelTurnsItByAStep() {
        knob.onScroll(0, 1);
        assertEquals(1, knob.getValue(), 0.001); // a hundredth of the range with no step
        knob.setStep(5); // snaps the 1 to the grid: 0
        assertEquals(0, knob.getValue(), 0.001);
        knob.onScroll(0, 3); // a notch is one step, however hard the wheel was turned
        assertEquals(5, knob.getValue(), 0.001);
        knob.onScroll(0, -1);
        assertEquals(0, knob.getValue(), 0.001);
    }

    @Test
    void theKeysTurnItAndShiftTurnsItTenTimesAsFar() {
        knob.onKeyPressed(GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_PRESS, 0);
        assertEquals(1, knob.getValue(), 0.001);
        knob.onKeyPressed(GLFW.GLFW_KEY_UP, GLFW.GLFW_REPEAT, GLFW.GLFW_MOD_SHIFT);
        assertEquals(11, knob.getValue(), 0.001);
        knob.onKeyPressed(GLFW.GLFW_KEY_LEFT, GLFW.GLFW_PRESS, 0);
        assertEquals(10, knob.getValue(), 0.001);
        knob.onKeyPressed(GLFW.GLFW_KEY_END, GLFW.GLFW_PRESS, 0);
        assertEquals(100, knob.getValue(), 0.001);
        knob.onKeyPressed(GLFW.GLFW_KEY_HOME, GLFW.GLFW_PRESS, 0);
        assertEquals(0, knob.getValue(), 0.001);
        knob.onKeyPressed(GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_RELEASE, 0); // a release is not a press
        assertEquals(0, knob.getValue(), 0.001);
    }

    @Test
    void theArcAroundTheRimGrowsWithTheValue() {
        knob.setGrooveColor(Color.WHITE);
        knob.setArcColor(Color.RED);
        knob.setShowValueText(false);
        int atRest = pixelOnTheRightOfTheRim();
        knob.setValue(100); // the sweep passes 3 o'clock on its way to the lower right
        int full = pixelOnTheRightOfTheRim();
        assertTrue((atRest & 0xFF) > 200, "the empty groove is white");
        assertTrue(((full >> 16) & 0xFF) > 200 && (full & 0xFF) < 80, "the full arc is red");
        knob.dispose();
    }

    private int pixelOnTheRightOfTheRim() {
        try (OffscreenRenderer r = new OffscreenRenderer(64, 64, 1f)) {
            r.begin();
            knob.render(r);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                int pixel = bmp.getColor(59, 32);
                bmp.close();
                return pixel;
            }
        }
    }
}
