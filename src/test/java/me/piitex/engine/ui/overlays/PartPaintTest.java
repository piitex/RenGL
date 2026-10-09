package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.ScrollingGradient;
import me.piitex.engine.ui.scroll.ScrollBarStyle;
import me.piitex.engine.ui.theme.BaseTheme;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** The caret, selection, scrollbar, slider, progress, checkbox, radio and tooltip colors take any paint. */
class PartPaintTest {
    private static final LinearGradient RED_TO_BLUE = new LinearGradient(Color.RED, Color.BLUE, 0f);

    private static Bitmap draw(int width, int height, Consumer<OffscreenRenderer> body) {
        try (OffscreenRenderer r = new OffscreenRenderer(width, height, 1f)) {
            r.begin();
            body.accept(r);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                return bmp;
            }
        }
    }

    private static int red(Bitmap bmp, int x, int y) {
        return (bmp.getColor(x, y) >> 16) & 0xFF;
    }

    private static int blue(Bitmap bmp, int x, int y) {
        return bmp.getColor(x, y) & 0xFF;
    }

    @Test
    void aGradientCircleChangesColorAcrossItsWidth() {
        try (Bitmap bmp = draw(40, 40, r -> r.drawCircle(20, 20, 18, RED_TO_BLUE))) {
            assertTrue(red(bmp, 6, 20) > blue(bmp, 6, 20), "left side is the first color");
            assertTrue(blue(bmp, 34, 20) > red(bmp, 34, 20), "right side is the last color");
        }
    }

    @Test
    void aGradientLineRunsFromItsStartToItsEnd() {
        try (Bitmap bmp = draw(60, 20, r -> r.drawLine(2, 10, 58, 10, RED_TO_BLUE, 6f))) {
            assertTrue(red(bmp, 6, 10) > blue(bmp, 6, 10));
            assertTrue(blue(bmp, 54, 10) > red(bmp, 54, 10));
        }
    }

    @Test
    void aGradientLineFollowsADiagonal() {
        try (Bitmap bmp = draw(60, 60, r -> r.drawLine(4, 4, 56, 56, RED_TO_BLUE, 6f))) {
            assertTrue(red(bmp, 10, 10) > blue(bmp, 10, 10));
            assertTrue(blue(bmp, 50, 50) > red(bmp, 50, 50));
        }
    }

    @Test
    void aSolidColorStillGoesThroughTheColorOverloads() {
        try (Bitmap bmp = draw(20, 20, r -> r.drawCircle(10, 10, 8, (me.piitex.engine.ui.color.Paint) Color.RED))) {
            assertEquals(255, red(bmp, 10, 10));
        }
    }

    @Test
    void sliderPartsTakeGradients() {
        SliderOverlay slider = new SliderOverlay(120, 0, 100, 100);
        slider.setTrackColor(RED_TO_BLUE);
        slider.setFillColor(RED_TO_BLUE);
        slider.setThumbColor(RED_TO_BLUE);
        slider.setThumbActiveColor(RED_TO_BLUE);
        slider.setTickColor(RED_TO_BLUE);
        assertSame(RED_TO_BLUE, slider.getTrackColor());
        assertSame(RED_TO_BLUE, slider.getFillColor());
        assertSame(RED_TO_BLUE, slider.getThumbColor());
        assertSame(RED_TO_BLUE, slider.getThumbActiveColor());
        assertSame(RED_TO_BLUE, slider.getTickColor());
        slider.setPosition(0, 0);
        try (Bitmap bmp = draw(160, 40, slider::render)) {
            int track = (int) (slider.getHeight() / 2);
            assertTrue(red(bmp, 10, track) > blue(bmp, 10, track), "the filled track starts red");
        }
    }

    @Test
    void progressBarFillTakesAGradient() {
        ProgressBarOverlay bar = new ProgressBarOverlay(100, 20);
        bar.setProgress(1.0);
        bar.setFillColor(RED_TO_BLUE);
        bar.setPosition(0, 0);
        try (Bitmap bmp = draw(100, 20, bar::render)) {
            assertTrue(red(bmp, 4, 10) > blue(bmp, 4, 10));
            assertTrue(blue(bmp, 95, 10) > red(bmp, 95, 10));
        }
    }

    @Test
    void radioDotAndCheckmarkTakeGradients() {
        RadioButtonOverlay radio = new RadioButtonOverlay(40);
        radio.setSelected(true);
        radio.setDotColor(RED_TO_BLUE);
        radio.setPosition(0, 0);
        assertSame(RED_TO_BLUE, radio.getDotColor());
        assertDoesNotThrow(() -> draw(40, 40, radio::render).close());

        CheckBoxOverlay box = new CheckBoxOverlay(40, true);
        box.setCheckColor(RED_TO_BLUE);
        box.setPosition(0, 0);
        assertSame(RED_TO_BLUE, box.getCheckColor());
        assertDoesNotThrow(() -> draw(40, 40, box::render).close());
    }

    @Test
    void caretAndSelectionTakeGradients() {
        TextFieldOverlay field = new TextFieldOverlay(120, 24);
        field.setCaretColor(RED_TO_BLUE);
        field.setSelectionColor(RED_TO_BLUE);
        assertSame(RED_TO_BLUE, field.getCaretColor());
        assertSame(RED_TO_BLUE, field.getSelectionColor());

        TextAreaOverlay area = new TextAreaOverlay(120, 60);
        area.setCaretColor(RED_TO_BLUE);
        area.setSelectionColor(RED_TO_BLUE);
        assertSame(RED_TO_BLUE, area.getCaretColor());
        assertSame(RED_TO_BLUE, area.getSelectionColor());
    }

    @Test
    void scrollBarColorsAreAnimatedWhenTheOwnerUpdates() {
        ScrollBarStyle style = new ScrollBarStyle();
        ScrollingGradient flow = new ScrollingGradient(Color.RED, Color.BLUE, 0f, 50f);
        style.setThumbColor(flow);
        assertSame(flow, style.getThumbColor());
        float before = flow.getOffset();
        style.advance(1f);
        assertNotEquals(before, flow.getOffset());
    }

    @Test
    void aGradientSamplesBetweenItsColors() {
        LinearGradient three = new LinearGradient(java.util.List.of(Color.RED, Color.GREEN, Color.BLUE), 0f);
        assertEquals(1f, three.colorAt(0f).getR());
        assertEquals(1f, three.colorAt(0.5f).getG());
        assertEquals(1f, three.colorAt(1f).getB());
        assertEquals(0.5f, three.colorAt(0.25f).getR(), 1e-6f);
        assertEquals(1f, three.colorAt(-3f).getR(), "clamped below");
        assertEquals(1f, three.colorAt(9f).getB(), "clamped above");
    }

    @Test
    void aLoadingSpinnerWithAGradientDrawsItsDotsInTheGradientsColors() {
        LoadingSpinnerOverlay spinner = new LoadingSpinnerOverlay(60);
        spinner.setColor(RED_TO_BLUE);
        spinner.setPosition(0, 0);
        assertSame(RED_TO_BLUE, spinner.getColor());
        assertDoesNotThrow(() -> draw(60, 60, r -> {
            spinner.update(0.25f);
            spinner.render(r);
        }).close());
    }

    @Test
    void tooltipThemeTokensTakePaints() {
        BaseTheme theme = new BaseTheme();
        theme.setTooltipBackground(RED_TO_BLUE);
        theme.setTooltipBorder(new ScrollingGradient(Color.RED, Color.BLUE, 10f));
        theme.setTooltipText(Color.WHITE);
        assertSame(RED_TO_BLUE, theme.getTooltipBackground());
        assertSame(Color.WHITE, theme.getTooltipText());
    }

    /** A pulse between red and blue, drawn as whichever solid color it is at. */
    private static final class Pulse implements AnimatedPaint {
        float t;

        @Override
        public void advance(float deltaSeconds) {
            t += deltaSeconds;
        }

        @Override
        public me.piitex.engine.ui.color.Paint current() {
            return t < 1f ? Color.RED : Color.BLUE;
        }
    }

    @Test
    void aCustomAnimatedPaintIsDrawnAsWhatItSaysItIsRightNow() {
        Pulse pulse = new Pulse();
        try (Bitmap bmp = draw(20, 20, r -> r.drawQuad(0, 0, 20, 20, pulse, 0f))) {
            assertTrue(red(bmp, 10, 10) > blue(bmp, 10, 10));
        }
        pulse.advance(2f);
        try (Bitmap bmp = draw(20, 20, r -> r.drawCircle(10, 10, 8, pulse))) {
            assertTrue(blue(bmp, 10, 10) > red(bmp, 10, 10));
        }
    }

    @Test
    void anElementBackgroundTakesACustomAnimatedPaint() {
        TextOverlay box = new TextOverlay("", 14f, Color.BLACK);
        box.setWidth(20);
        box.setHeight(20);
        box.setPosition(0, 0);
        Pulse pulse = new Pulse();
        box.getStyling().setBackgroundColor(pulse);
        try (Bitmap bmp = draw(20, 20, box::render)) {
            assertTrue(red(bmp, 10, 10) > blue(bmp, 10, 10));
        }
        box.update(2f);
        try (Bitmap bmp = draw(20, 20, box::render)) {
            assertTrue(blue(bmp, 10, 10) > red(bmp, 10, 10));
        }
    }
}
