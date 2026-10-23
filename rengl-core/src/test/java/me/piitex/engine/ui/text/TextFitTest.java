package me.piitex.engine.ui.text;

import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.overlays.TextOverlay;
import org.jetbrains.skia.Font;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextFitTest {
    private static Font font() {
        return new Font(me.piitex.engine.ui.text.Font.getDefault(), 16f);
    }

    @Test
    void textThatFitsIsUntouched() {
        try (Font font = font()) {
            String text = "report.zip";
            assertEquals(text, TextFit.ellipsize(text, font, font.measureTextWidth(text)));
            assertEquals(text, TextFit.ellipsize(text, font, 1000));
        }
    }

    @Test
    void longTextEndsInAnEllipsisAndFits() {
        try (Font font = font()) {
            String text = "a rather long file name for an archive.zip";
            float room = font.measureTextWidth(text) / 2;
            String fitted = TextFit.ellipsize(text, font, room);
            assertTrue(fitted.endsWith("…"), fitted);
            assertTrue(text.startsWith(fitted.substring(0, fitted.length() - 1)), fitted);
            assertTrue(font.measureTextWidth(fitted) <= room, fitted);
        }
    }

    @Test
    void theMiddleKeepsTheExtension() {
        try (Font font = font()) {
            String text = "quarterly-report-final-version-2.xlsx";
            float room = font.measureTextWidth(text) * 0.6f;
            String fitted = TextFit.ellipsize(text, font, room, true);
            assertTrue(fitted.contains("…"), fitted);
            assertTrue(fitted.endsWith(".xlsx"), fitted);
            assertTrue(fitted.startsWith("quar"), fitted);
            assertTrue(font.measureTextWidth(fitted) <= room, fitted);
        }
    }

    @Test
    void noRoomMeansNothing() {
        try (Font font = font()) {
            assertEquals("", TextFit.ellipsize("anything", font, 0));
            assertEquals("", TextFit.ellipsize("anything", font, 2));
            assertEquals("", TextFit.ellipsize(null, font, 100));
            assertEquals("", TextFit.ellipsize("", font, 100));
        }
    }

    @Test
    void aSurrogatePairIsNeverSplit() {
        try (Font font = font()) {
            String text = "😀😀😀😀😀😀😀😀😀😀";
            for (float room = 14; room < font.measureTextWidth(text); room += 3) {
                for (boolean middle : new boolean[]{false, true}) {
                    String fitted = TextFit.ellipsize(text, font, room, middle);
                    for (int i = 0; i < fitted.length(); i++) {
                        char c = fitted.charAt(i);
                        if (Character.isHighSurrogate(c)) assertTrue(i + 1 < fitted.length() && Character.isLowSurrogate(fitted.charAt(i + 1)), fitted);
                        if (Character.isLowSurrogate(c)) assertTrue(i > 0 && Character.isHighSurrogate(fitted.charAt(i - 1)), fitted);
                    }
                }
            }
        }
    }

    // --- TextOverlay ---

    @Test
    void aMaxWidthCapsTheOverlayAndKeepsTheFullText() {
        String text = "a status line that is much too long to fit in the room it has been given";
        TextOverlay overlay = new TextOverlay(text, 16f, Color.BLACK);
        double natural = overlay.getWidth();
        overlay.setMaxWidth(120);
        assertEquals(120, overlay.getWidth(), 0.001);
        assertTrue(natural > 120);
        assertEquals(text, overlay.getText());

        overlay.setText("short");
        assertTrue(overlay.getWidth() < 120, "short text takes only what it needs");

        overlay.setMaxWidth(0);
        overlay.setText(text);
        assertEquals(natural, overlay.getWidth(), 0.001);
    }

    private static final class Probe extends TextOverlay {
        Probe(String text) {
            super(text, 16f, Color.BLACK);
        }

        float limit() {
            return textLimit();
        }
    }

    @Test
    void ellipsizeOnlyActsOnAFixedWidth() {
        Probe probe = new Probe("a status line that is much too long to fit");
        probe.setEllipsize(true);
        assertEquals(Float.MAX_VALUE, probe.limit(), "an overlay sized to its text has no limit");
        probe.setWidth(100);
        assertEquals(100f, probe.limit(), 0.001);
        probe.setMaxWidth(80);
        assertEquals(80f, probe.limit(), 0.001, "the narrower of the two");
        probe.setEllipsize(false);
        assertEquals(80f, probe.limit(), 0.001);
        probe.setMaxWidth(0);
        assertEquals(Float.MAX_VALUE, probe.limit());
    }
}
