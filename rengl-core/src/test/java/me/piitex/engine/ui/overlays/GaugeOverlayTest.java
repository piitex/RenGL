package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.color.Color;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GaugeOverlayTest {
    private GaugeOverlay gauge;

    @BeforeEach
    void setUp() {
        gauge = new GaugeOverlay(120, 0, 200);
        gauge.setPosition(0, 0);
    }

    @Test
    void theValueIsClampedToTheRange() {
        gauge.setSmoothing(0);
        gauge.setValue(500);
        assertEquals(200, gauge.getValue(), 0.001);
        gauge.setValue(-3);
        assertEquals(0, gauge.getValue(), 0.001);
    }

    @Test
    void withNoSmoothingTheDisplayedValueJumps() {
        gauge.setSmoothing(0);
        gauge.setValue(150);
        assertEquals(150, gauge.getDisplayedValue(), 0.001);
        assertEquals(0.75, gauge.getRatio(), 0.001);
    }

    @Test
    void withSmoothingTheDisplayedValueEasesTowardTheNewOne() {
        gauge.setSmoothing(0.1f);
        gauge.setValue(100);
        assertEquals(0, gauge.getDisplayedValue(), 0.001); // nothing has been drawn yet
        gauge.advance(0.1f); // one time constant: about 63 percent of the way
        assertEquals(63.2, gauge.getDisplayedValue(), 1.0);
        assertEquals(100, gauge.getValue(), 0.001);
        for (int i = 0; i < 100; i++) gauge.advance(0.1f);
        assertEquals(100, gauge.getDisplayedValue(), 0.001); // settles exactly, not forever approaching
    }

    @Test
    void aZoneIncludesItsStartAndTheLastZoneIncludesTheMaximum() {
        gauge.addZone(0, 100, Color.GREEN).addZone(100, 200, Color.RED);
        assertSame(Color.GREEN, gauge.getZoneColor(0));
        assertSame(Color.GREEN, gauge.getZoneColor(99.9));
        assertSame(Color.RED, gauge.getZoneColor(100));
        assertSame(Color.RED, gauge.getZoneColor(200));
        gauge.clearZones();
        assertNull(gauge.getZoneColor(50));
    }

    @Test
    void aValueOutsideEveryZoneHasNoZoneColor() {
        gauge.addZone(50, 100, Color.YELLOW);
        assertNull(gauge.getZoneColor(10));
    }

    @Test
    void theArcTakesTheColorOfTheZoneTheValueIsIn() {
        gauge.setSmoothing(0);
        gauge.setGrooveColor(Color.WHITE);
        gauge.addZone(0, 100, Color.GREEN).addZone(100, 200, Color.RED);
        gauge.setShowValueText(false);
        gauge.setValue(190); // sweeps past 3 o'clock, in the red zone
        int pixel = pixelAt(116 - 5 - 1, 60);
        assertTrue(((pixel >> 16) & 0xFF) > 200 && ((pixel >> 8) & 0xFF) < 80, "the arc is red");
        gauge.dispose();
    }

    @Test
    void aNeedleGaugeDrawsTheZonesOnTheRimAndANeedle() {
        gauge.setSmoothing(0);
        gauge.setStyle(GaugeOverlay.Style.NEEDLE);
        gauge.addZone(0, 200, Color.GREEN);
        gauge.setTickCount(5);
        gauge.setValue(100); // straight up
        gauge.setNeedleColor(Color.RED);
        gauge.setShowValueText(false);
        int onNeedle = pixelAt(60, 30);
        assertTrue(((onNeedle >> 16) & 0xFF) > 200 && ((onNeedle >> 8) & 0xFF) < 80, "the needle points up from the hub");
        gauge.dispose();
    }

    @Test
    void aHalfCircleSweepPutsTheMiddleStraightUp() {
        gauge.setSmoothing(0);
        gauge.setSweep(180, 180);
        gauge.setStyle(GaugeOverlay.Style.NEEDLE);
        gauge.setNeedleColor(Color.RED);
        gauge.setShowValueText(false);
        gauge.setValue(100);
        int onNeedle = pixelAt(60, 30);
        assertTrue(((onNeedle >> 16) & 0xFF) > 200, "the needle is vertical at the middle of the range");
        gauge.dispose();
    }

    @Test
    void textAndTitleDrawWithoutAWindow() {
        gauge.setSmoothing(0);
        gauge.setTitle("km/h");
        gauge.setLabelFormatter(v -> Math.round(v) + "!");
        gauge.setValue(80);
        assertDoesNotThrow(() -> pixelAt(60, 60));
        gauge.dispose();
    }

    private int pixelAt(int x, int y) {
        try (OffscreenRenderer r = new OffscreenRenderer(120, 120, 1f)) {
            r.begin();
            gauge.render(r);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                int pixel = bmp.getColor(x, y);
                bmp.close();
                return pixel;
            }
        }
    }
}
