package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.background.ConstellationBackground;
import me.piitex.engine.ui.background.DotGridBackground;
import me.piitex.engine.ui.background.ElectricNodeBackground;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.Paints;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ModalContainer;
import me.piitex.engine.ui.containers.TabContainer;
import me.piitex.engine.ui.containers.TreeView;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** The toggle, separator, modal backdrop, tabs, tree, dropdown, pickers, download bar and background effects take any paint. */
class WidgetPaintTest {
    private static final LinearGradient RED_TO_BLUE = new LinearGradient(Color.RED, Color.BLUE, 0f);

    /** Red for the first second, blue after, and counts how often it is advanced. */
    private static final class Pulse implements AnimatedPaint {
        float t;
        int advances;

        @Override
        public void advance(float deltaSeconds) {
            t += deltaSeconds;
            advances++;
        }

        @Override
        public Paint current() {
            return t < 1f ? Color.RED : Color.BLUE;
        }
    }

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
    void toggleSwitchTrackTakesAGradientWhenOn() {
        ToggleSwitchOverlay toggle = new ToggleSwitchOverlay(60, 24, true);
        toggle.setOnColor(RED_TO_BLUE);
        toggle.setOffColor(Color.GRAY);
        toggle.setPosition(0, 0);
        toggle.update(1f); // the knob slides across
        assertSame(RED_TO_BLUE, toggle.getOnColor());
        try (Bitmap bmp = draw(60, 24, toggle::render)) {
            assertTrue(red(bmp, 5, 12) > blue(bmp, 5, 12), "the left end is the first color");
            assertTrue(blue(bmp, 58, 12) > red(bmp, 58, 12), "the right end is the last");
        }
    }

    @Test
    void toggleSwitchStillBlendsTwoColors() {
        ToggleSwitchOverlay toggle = new ToggleSwitchOverlay(60, 24, false);
        toggle.setOffColor(Color.RED);
        toggle.setOnColor(Color.BLUE);
        toggle.setKnobColor(Color.TRANSPARENT);
        toggle.setPosition(0, 0);
        try (Bitmap bmp = draw(60, 24, toggle::render)) {
            assertTrue(red(bmp, 30, 12) > 200, "off is the off color");
        }
        toggle.setOn(true);
        toggle.update(1f);
        try (Bitmap bmp = draw(60, 24, toggle::render)) {
            assertTrue(blue(bmp, 30, 12) > 200, "on is the on color");
        }
    }

    @Test
    void aToggleAdvancesItsAnimatedPaints() {
        ToggleSwitchOverlay toggle = new ToggleSwitchOverlay(60, 24, false);
        Pulse pulse = new Pulse();
        toggle.setOffColor(pulse);
        toggle.setKnobColor(Color.TRANSPARENT);
        toggle.setPosition(0, 0);
        toggle.update(2f);
        assertEquals(1, pulse.advances);
        try (Bitmap bmp = draw(60, 24, toggle::render)) {
            assertTrue(blue(bmp, 30, 12) > red(bmp, 30, 12), "drawn as what the paint is now");
        }
    }

    @Test
    void mixingTwoPaintsFadesTheSecondOverTheFirst() {
        try (Bitmap bmp = draw(20, 20, r -> Paints.fillMixed(r, 0, 0, 20, 20, Color.RED, RED_TO_BLUE, 1f, 0f))) {
            assertTrue(red(bmp, 1, 10) > blue(bmp, 1, 10));
            assertTrue(blue(bmp, 18, 10) > red(bmp, 18, 10));
        }
        try (Bitmap bmp = draw(20, 20, r -> Paints.fillMixed(r, 0, 0, 20, 20, Color.RED, Color.BLUE, 0.5f, 0f))) {
            assertEquals(red(bmp, 10, 10), blue(bmp, 10, 10), 3, "halfway between two colors");
        }
    }

    @Test
    void separatorLineTakesAGradient() {
        SeparatorOverlay line = new SeparatorOverlay(60, 6f);
        line.setLineColor(RED_TO_BLUE);
        line.setPosition(0, 0);
        assertSame(RED_TO_BLUE, line.getLineColor());
        int y = (int) (line.getHeight() / 2);
        try (Bitmap bmp = draw(60, (int) Math.max(8, line.getHeight()), line::render)) {
            assertTrue(red(bmp, 4, y) > blue(bmp, 4, y));
            assertTrue(blue(bmp, 56, y) > red(bmp, 56, y));
        }
    }

    @Test
    void modalBackdropTakesAGradient() {
        Container content = new Container(10, 10);
        ModalContainer modal = new ModalContainer(content);
        modal.setBackdropColor(RED_TO_BLUE);
        assertSame(RED_TO_BLUE, modal.getBackdropColor());
        modal.setPosition(0, 0);
        modal.setWidth(100);
        modal.setHeight(40);
        content.setPosition(45, 15);
        try (Bitmap bmp = draw(100, 40, modal::render)) {
            assertTrue(red(bmp, 3, 2) > blue(bmp, 3, 2));
            assertTrue(blue(bmp, 96, 2) > red(bmp, 96, 2));
        }
    }

    @Test
    void tabStyleAndTreeViewAndDropdownKeepAGradient() {
        TabContainer tabs = new TabContainer(200, 100);
        tabs.getTabStyle().setTabColor(RED_TO_BLUE);
        tabs.getTabStyle().setTextColor(RED_TO_BLUE);
        tabs.getTabStyle().setIndicatorColor(RED_TO_BLUE);
        assertSame(RED_TO_BLUE, tabs.getTabStyle().getTabColor());
        assertSame(RED_TO_BLUE, tabs.getTabStyle().getTextColor());
        assertSame(RED_TO_BLUE, tabs.getTabStyle().getIndicatorColor());

        TreeView<String> tree = new TreeView<>(100, 100, _ -> List.of());
        tree.setTextColor(RED_TO_BLUE);
        tree.setDimTextColor(RED_TO_BLUE);
        tree.setSelectionColor(RED_TO_BLUE);
        assertSame(RED_TO_BLUE, tree.getTextColor());
        assertSame(RED_TO_BLUE, tree.getSelectionColor());

        DropdownOverlay<String> dropdown = new DropdownOverlay<>(100, 24);
        dropdown.setTextColor(RED_TO_BLUE);
        assertSame(RED_TO_BLUE, dropdown.getTextColor());

        DateTimePickerOverlay date = new DateTimePickerOverlay(160, 24);
        date.setTextColor(RED_TO_BLUE);
        assertSame(RED_TO_BLUE, date.getTextColor());

        ColorPickerOverlay picker = new ColorPickerOverlay(160, 24);
        picker.setTextColor(RED_TO_BLUE);
        assertSame(RED_TO_BLUE, picker.getTextColor());
    }

    @Test
    void tabsDropdownTreeAndPickersAdvanceAnimatedPaints() {
        TabContainer tabs = new TabContainer(200, 100);
        tabs.addTab("One", new Container(10, 10));
        Pulse tabPulse = new Pulse();
        tabs.getTabStyle().setTextColor(tabPulse);
        tabs.update(0.5f);
        assertEquals(1, tabPulse.advances, "the tab strip advances the style once a frame");

        TreeView<String> tree = new TreeView<>(100, 100, _ -> List.of());
        Pulse treePulse = new Pulse();
        tree.setSelectionColor(treePulse);
        tree.update(0.5f);
        assertEquals(1, treePulse.advances);

        DropdownOverlay<String> dropdown = new DropdownOverlay<>(100, 24);
        Pulse dropPulse = new Pulse();
        dropdown.setTextColor(dropPulse);
        dropdown.update(0.5f);
        assertEquals(1, dropPulse.advances);

        DateTimePickerOverlay date = new DateTimePickerOverlay(160, 24);
        Pulse datePulse = new Pulse();
        date.setTextColor(datePulse);
        date.update(0.5f);
        assertEquals(1, datePulse.advances);

        ColorPickerOverlay picker = new ColorPickerOverlay(160, 24);
        Pulse pickerPulse = new Pulse();
        picker.setTextColor(pickerPulse);
        picker.update(0.5f);
        assertEquals(1, pickerPulse.advances);

        SeparatorOverlay line = new SeparatorOverlay(60);
        Pulse linePulse = new Pulse();
        line.setLineColor(linePulse);
        line.update(0.5f);
        assertEquals(1, linePulse.advances);

        ModalContainer modal = new ModalContainer(new Container(10, 10));
        Pulse backdrop = new Pulse();
        modal.setBackdropColor(backdrop);
        modal.update(0.5f);
        assertEquals(1, backdrop.advances);
    }

    @Test
    void dotGridDotsTakeAnAnimatedPaint() {
        DotGridBackground grid = new DotGridBackground();
        grid.setSpacing(20f);
        grid.setDotRadius(5f);
        Pulse pulse = new Pulse();
        grid.setDotColor(pulse);
        try (Bitmap bmp = draw(60, 60, r -> grid.render(r, 0, 0, 60, 60))) {
            assertTrue(red(bmp, 20, 20) > blue(bmp, 20, 20));
        }
        grid.update(2f, 0, 0, 60, 60);
        try (Bitmap bmp = draw(60, 60, r -> grid.render(r, 0, 0, 60, 60))) {
            assertTrue(blue(bmp, 20, 20) > red(bmp, 20, 20));
        }
    }

    @Test
    void constellationDotsAndLinesTakeGradients() {
        ConstellationBackground field = new ConstellationBackground();
        field.setParticleColor(RED_TO_BLUE);
        field.setLineColor(RED_TO_BLUE);
        assertSame(RED_TO_BLUE, field.getParticleColor());
        assertSame(RED_TO_BLUE, field.getLineColor());
        Pulse pulse = new Pulse();
        field.setLineColor(pulse);
        field.update(0.5f, 0, 0, 200, 200);
        assertEquals(1, pulse.advances);
        assertDoesNotThrow(() -> draw(200, 200, r -> field.render(r, 0, 0, 200, 200)).close());
    }

    @Test
    void electricArcsDrawInAGradientAndAnimatedCore() {
        ElectricNodeBackground electric = new ElectricNodeBackground();
        Pulse core = new Pulse();
        electric.setGlowColor(RED_TO_BLUE);
        electric.setCoreColor(core);
        for (int i = 0; i < 20; i++) electric.update(0.05f, 0, 0, 300, 200);
        assertEquals(20, core.advances);
        assertDoesNotThrow(() -> draw(300, 200, r -> electric.render(r, 0, 0, 300, 200)).close());
    }

    @Test
    void aPolylineInAGradientRunsAlongItsLength() {
        float[] points = {2, 10, 30, 10, 30, 30, 58, 30};
        try (Bitmap bmp = draw(60, 40, r -> r.drawPolyline(points, 4, (Paint) RED_TO_BLUE, 6f))) {
            assertTrue(red(bmp, 6, 10) > blue(bmp, 6, 10), "the start is the first color");
            assertTrue(blue(bmp, 54, 30) > red(bmp, 54, 30), "the end is the last, round the corner");
        }
    }
}
