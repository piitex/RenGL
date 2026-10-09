package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.icons.Feather;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ButtonOverlayTest {
    private static ButtonOverlay button(String text) {
        ButtonOverlay button = new ButtonOverlay(text, 160, 40);
        button.setX(10);
        button.setY(10);
        button.getStyling().setBackgroundColor(Color.WHITE);
        button.getStyling().setHoverColor(Color.WHITE);
        button.getStyling().setBorderThickness(0f);
        button.getStyling().setCornerRadius(0f);
        button.setTextColor(Color.BLACK);
        return button;
    }

    private static Bitmap draw(Consumer<OffscreenRenderer> body) {
        try (OffscreenRenderer r = new OffscreenRenderer(200, 60, 1f)) {
            r.clear(1f, 1f, 1f, 1f); // before begin(), which is what clears
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

    /** How many pixels in the box differ from white, left to right between x0 and x1. */
    private static int inked(Bitmap bmp, int x0, int x1) {
        int count = 0;
        for (int y = 10; y < 50; y++) {
            for (int x = x0; x < x1; x++) {
                if ((bmp.getColor(x, y) & 0xFFFFFF) != 0xFFFFFF) count++;
            }
        }
        return count;
    }

    private static int darkest(Bitmap bmp) {
        int min = 255;
        for (int y = 10; y < 50; y++) for (int x = 10; x < 170; x++) min = Math.min(min, bmp.getColor(x, y) & 0xFF);
        return min;
    }

    private static void click(ButtonOverlay button) {
        button.getMouseClickListenerConsumer().accept(new MouseClickEvent(0, 0, 0, 0));
    }

    // --- Disabled ---

    @Test
    void aDisabledButtonDoesNotFireItsAction() {
        ButtonOverlay button = button("Extract");
        AtomicInteger acted = new AtomicInteger(), declined = new AtomicInteger();
        button.onAction(acted::incrementAndGet);
        button.onDisabledClick(declined::incrementAndGet);

        click(button);
        assertEquals(1, acted.get());

        button.setDisabled(true);
        click(button);
        click(button);
        assertEquals(1, acted.get(), "the action stays quiet");
        assertEquals(2, declined.get());

        button.setDisabled(false);
        click(button);
        assertEquals(2, acted.get());
    }

    @Test
    void aDisabledButtonWithoutAHandlerJustIgnoresTheClick() {
        ButtonOverlay button = button("Extract");
        button.setDisabled(true);
        assertDoesNotThrow(() -> click(button));
    }

    @Test
    void aDisabledButtonStaysVisibleAndShowsNotAllowed() {
        ButtonOverlay button = button("Extract");
        assertEquals(CursorMode.POINTER, button.getCursorMode());
        button.setDisabled(true);
        assertTrue(button.isEnabled(), "disabled is not hidden");
        assertEquals(CursorMode.NOT_ALLOWED, button.getCursorMode());
        button.setDisabled(false);
        assertEquals(CursorMode.POINTER, button.getCursorMode());
    }

    @Test
    void aDisabledButtonIsDrawnFaded() {
        ButtonOverlay button = button("Extract");
        int normal = darkest(draw(button::render));
        button.setDisabled(true);
        int faded = darkest(draw(button::render));
        assertTrue(normal < 60, "the label is dark: " + normal);
        assertTrue(faded > normal + 60, "and faded when disabled: " + faded + " vs " + normal);

        button.setDisabledOpacity(0f);
        assertEquals(255, darkest(draw(button::render)), "fully faded draws nothing");
    }

    @Test
    void aDisabledButtonDoesNotShowItsHoverColor() {
        ButtonOverlay button = button("");
        button.getStyling().setHoverColor(new Color(1f, 0f, 0f, 1f));
        button.setDisabledOpacity(1f);
        button.setHovered(true);
        Bitmap hovered = draw(button::render);
        assertEquals(0xFF0000, hovered.getColor(100, 30) & 0xFFFFFF, "enabled and hovered shows the hover color");

        button.setDisabled(true);
        Bitmap disabled = draw(button::render);
        assertEquals(0xFFFFFF, disabled.getColor(100, 30) & 0xFFFFFF, "disabled does not");
        assertTrue(button.isHovered(), "the hover state itself is left as it was");
    }

    // --- Icon ---

    @Test
    void anIconSitsBesideTheLabelAndTheTwoAreCentered() {
        ButtonOverlay plain = button("Add files");
        Bitmap without = draw(plain::render);

        ButtonOverlay withIcon = button("Add files");
        withIcon.setIcon(Feather.PLUS);
        assertTrue(withIcon.hasIcon());
        Bitmap with = draw(withIcon::render);

        // Both centered, so adding the icon pushes the label right and puts ink on the left where there was none.
        int leftWithout = firstInkedColumn(without), leftWith = firstInkedColumn(with);
        assertTrue(leftWith < leftWithout, "the icon starts further left than the label did: " + leftWith + " vs " + leftWithout);
        int rightWithout = lastInkedColumn(without), rightWith = lastInkedColumn(with);
        assertTrue(rightWith > rightWithout, "and the label ends further right: " + rightWith + " vs " + rightWithout);
        double leftGap = leftWith - 10, rightGap = 170 - rightWith;
        assertEquals(leftGap, rightGap, 4, "the pair is centered in the button");
    }

    @Test
    void anIconAloneIsCentered() {
        ButtonOverlay button = button("");
        button.setIcon(Feather.PLUS);
        Bitmap bmp = draw(button::render);
        double left = firstInkedColumn(bmp) - 10, right = 170 - lastInkedColumn(bmp);
        assertEquals(left, right, 3);
        assertTrue(inked(bmp, 10, 170) > 0);
    }

    @Test
    void theIconCanGoAfterTheLabel() {
        ButtonOverlay before = button("Open");
        before.setIcon(Feather.CHEVRON_DOWN);
        ButtonOverlay after = button("Open");
        after.setIcon(Feather.CHEVRON_DOWN);
        after.setIconAfterText(true);
        assertTrue(after.isIconAfterText());
        Bitmap a = draw(before::render), b = draw(after::render);
        // The same two things in the other order, so the picture changes but the pair fills about the same span
        // (an icon has a little room round its lines, so the ends are not exactly equal).
        int different = 0;
        for (int y = 10; y < 50; y++) for (int x = 10; x < 170; x++) if (a.getColor(x, y) != b.getColor(x, y)) different++;
        assertTrue(different > 50, "the chevron moved: " + different);
        assertEquals(firstInkedColumn(a), firstInkedColumn(b), 8, "the pair fills the same span");
        assertEquals(lastInkedColumn(a), lastInkedColumn(b), 8);
    }

    @Test
    void theIconTakesTheLabelColor() {
        ButtonOverlay button = button("");
        button.setIcon(Feather.PLUS);
        button.setIconSize(24);
        button.setTextColor(new Color(1f, 0f, 0f, 1f));
        Bitmap bmp = draw(button::render);
        int redInk = 0;
        for (int y = 10; y < 50; y++) {
            for (int x = 10; x < 170; x++) {
                int c = bmp.getColor(x, y);
                if (((c >> 16) & 0xFF) > 200 && (c & 0xFF) < 80) redInk++;
            }
        }
        assertTrue(redInk > 20, "the plus is drawn in the label's red: " + redInk);
    }

    @Test
    void removingTheIconPutsTheLabelBack() {
        ButtonOverlay button = button("Add files");
        Bitmap before = draw(button::render);
        button.setIcon(Feather.PLUS);
        button.setIcon(null);
        assertFalse(button.hasIcon());
        Bitmap after = draw(button::render);
        assertEquals(firstInkedColumn(before), firstInkedColumn(after));
        assertEquals(lastInkedColumn(before), lastInkedColumn(after));
    }

    @Test
    void theIconSizeFollowsTheFontUnlessSet() {
        ButtonOverlay button = button("x");
        button.setFontSize(20f);
        assertEquals(25, button.getIconSize(), 0.5);
        button.setIconSize(18);
        assertEquals(18, button.getIconSize());
        button.setIconSize(0);
        assertEquals(25, button.getIconSize(), 0.5);
    }

    // --- Ellipsis ---

    @Test
    void aLongLabelStaysInsideTheButtonWithEllipsize() {
        String label = "this label is far too long to ever fit in a button this narrow";
        ButtonOverlay loose = button(label);
        Bitmap overflow = draw(loose::render);
        assertTrue(inked(overflow, 0, 10) > 0 || inked(overflow, 170, 200) > 0, "without it the text runs past the box");

        ButtonOverlay tidy = button(label);
        tidy.setEllipsize(true);
        Bitmap bmp = draw(tidy::render);
        assertEquals(0, inked(bmp, 0, 10), "nothing left of the box");
        assertEquals(0, inked(bmp, 170, 200), "nothing right of it");
        assertTrue(firstInkedColumn(bmp) >= 10 + 6 && lastInkedColumn(bmp) <= 170 - 6, "and it keeps away from the edges");
        assertEquals(label, tidy.getText());
    }

    private static int firstInkedColumn(Bitmap bmp) {
        for (int x = 0; x < 200; x++) if (inked(bmp, x, x + 1) > 0) return x;
        return -1;
    }

    private static int lastInkedColumn(Bitmap bmp) {
        for (int x = 199; x >= 0; x--) if (inked(bmp, x, x + 1) > 0) return x + 1;
        return -1;
    }
}
