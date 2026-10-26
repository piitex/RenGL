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

class RangeSliderOverlayTest {
    // 208 wide with a thumb radius of 8: the track runs from x = 8 to x = 200, 192 points for 0..100
    private RangeSliderOverlay slider;
    private final List<double[]> changes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        slider = new RangeSliderOverlay(208, 0, 100);
        slider.setPosition(0, 0);
        slider.onRangeChange((low, high) -> changes.add(new double[]{low, high}));
    }

    private static double x(double value) {
        return 8 + value / 100.0 * 192;
    }

    private void drag(double fromValue, double toValue) {
        slider.onDragStart(x(fromValue), 10);
        slider.onDragUpdate(x(toValue), 10);
        slider.onDragEnd(x(toValue), 10);
    }

    @Test
    void startsOnTheWholeRange() {
        assertEquals(0, slider.getLow(), 0.001);
        assertEquals(100, slider.getHigh(), 0.001);
    }

    @Test
    void pressingNearTheLowThumbMovesIt() {
        drag(10, 30);
        assertEquals(30, slider.getLow(), 0.5);
        assertEquals(100, slider.getHigh(), 0.001);
        assertFalse(changes.isEmpty());
    }

    @Test
    void pressingNearTheHighThumbMovesIt() {
        drag(90, 60);
        assertEquals(0, slider.getLow(), 0.001);
        assertEquals(60, slider.getHigh(), 0.5);
    }

    @Test
    void theNearerThumbWinsWhenTheTrackIsPressed() {
        slider.setRange(20, 80);
        drag(30, 35);
        assertEquals(35, slider.getLow(), 0.5);
        assertEquals(80, slider.getHigh(), 0.001);
        slider.setRange(20, 80);
        drag(70, 65);
        assertEquals(20, slider.getLow(), 0.001);
        assertEquals(65, slider.getHigh(), 0.5);
    }

    @Test
    void theThumbsNeverCross() {
        slider.setRange(40, 60);
        drag(40, 90);
        assertEquals(60, slider.getLow(), 0.001);
        assertEquals(60, slider.getHigh(), 0.001);
        slider.setRange(40, 60);
        drag(60, 5);
        assertEquals(40, slider.getLow(), 0.001);
        assertEquals(40, slider.getHigh(), 0.001);
    }

    @Test
    void togetherTheSideOfThePressPicksTheThumb() {
        slider.setRange(50, 50);
        slider.onDragStart(x(50) - 2, 10); // left of both
        slider.onDragUpdate(x(20), 10);
        slider.onDragEnd(x(20), 10);
        assertEquals(20, slider.getLow(), 0.5);
        assertEquals(50, slider.getHigh(), 0.001);
        slider.setRange(50, 50);
        slider.onDragStart(x(50) + 2, 10);
        slider.onDragUpdate(x(80), 10);
        slider.onDragEnd(x(80), 10);
        assertEquals(50, slider.getLow(), 0.001);
        assertEquals(80, slider.getHigh(), 0.5);
    }

    @Test
    void minSpanKeepsTheThumbsApart() {
        slider.setMinSpan(10);
        slider.setRange(40, 60);
        drag(40, 90);
        assertEquals(50, slider.getLow(), 0.001);
        assertEquals(60, slider.getHigh(), 0.001);
        slider.setRange(70, 71); // too close: the high thumb gives way
        assertEquals(70, slider.getLow(), 0.001);
        assertEquals(80, slider.getHigh(), 0.001);
    }

    @Test
    void aStepSnapsBothThumbs() {
        slider.setStep(10);
        drag(0, 33);
        assertEquals(30, slider.getLow(), 0.001);
        slider.setRange(12, 98);
        assertEquals(10, slider.getLow(), 0.001);
        assertEquals(100, slider.getHigh(), 0.001);
    }

    @Test
    void setRangeIsSilentClampsAndOrders() {
        slider.setRange(-50, 500);
        assertEquals(0, slider.getLow(), 0.001);
        assertEquals(100, slider.getHigh(), 0.001);
        slider.setRange(80, 20); // swapped
        assertEquals(20, slider.getLow(), 0.001);
        assertEquals(80, slider.getHigh(), 0.001);
        assertTrue(changes.isEmpty());
    }

    @Test
    void changingTheLimitsPullsTheRangeIn() {
        slider.setRange(10, 90);
        slider.setMax(50);
        assertEquals(10, slider.getLow(), 0.001);
        assertEquals(50, slider.getHigh(), 0.001);
        slider.setMin(30);
        assertEquals(30, slider.getLow(), 0.001);
    }

    @Test
    void onlyARealChangeFiresTheCallback() {
        slider.setStep(1); // so a press on the thumb lands exactly on its value, not a rounding error away
        slider.setRange(20, 80);
        slider.onDragStart(x(20), 10); // pressed right on the thumb: nothing moves
        slider.onDragEnd(x(20), 10);
        assertTrue(changes.isEmpty());
        slider.onDragStart(x(20), 10);
        slider.onDragUpdate(x(40), 10);
        slider.onDragEnd(x(40), 10);
        assertEquals(1, changes.size());
        assertEquals(80, changes.getFirst()[1], 0.001);
    }

    @Test
    void keysMoveTheActiveThumbAndSwitchBetweenThumbs() {
        slider.setRange(20, 80);
        slider.setStep(5);
        slider.onKeyPressed(GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_PRESS, 0);
        assertEquals(25, slider.getLow(), 0.001);
        slider.onKeyPressed(GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
        assertEquals(75, slider.getLow(), 0.001);
        slider.onKeyPressed(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0); // the other thumb
        slider.onKeyPressed(GLFW.GLFW_KEY_END, GLFW.GLFW_PRESS, 0);
        assertEquals(100, slider.getHigh(), 0.001);
        slider.onKeyPressed(GLFW.GLFW_KEY_HOME, GLFW.GLFW_PRESS, 0); // cannot pass the low thumb
        assertEquals(75, slider.getHigh(), 0.001);
    }

    @Test
    void theFillBetweenTheThumbsIsTheAccentAndTheRestIsTheTrack() {
        slider.setTrackColor(Color.WHITE);
        slider.setFillColor(Color.RED);
        slider.setThumbColor(Color.BLUE);
        slider.setRange(25, 75);
        slider.setLabelFormatter(v -> Math.round(v) + "%");
        try (OffscreenRenderer r = new OffscreenRenderer(208, 20, 1f)) {
            r.begin();
            slider.render(r);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                int mid = bmp.getColor(104, 10), left = bmp.getColor(15, 10);
                assertTrue(((mid >> 16) & 0xFF) > 200 && (mid & 0xFF) < 80, "between the thumbs is red");
                assertTrue((left & 0xFF) > 200 && ((left >> 16) & 0xFF) > 200, "outside it is the white track");
                bmp.close();
            }
        }
        slider.dispose();
    }
}
