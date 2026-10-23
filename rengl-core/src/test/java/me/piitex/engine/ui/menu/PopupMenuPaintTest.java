package me.piitex.engine.ui.menu;

import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A popup menu's panel, highlight, text and border take any paint. */
class PopupMenuPaintTest {
    private static final LinearGradient RED_TO_BLUE = new LinearGradient(Color.RED, Color.BLUE, 0f);

    private static final class Pulse implements AnimatedPaint {
        int advances;

        @Override
        public void advance(float deltaSeconds) {
            advances++;
        }

        @Override
        public Paint current() {
            return Color.RED;
        }
    }

    @Test
    void thePanelAndRowsKeepAGradient() {
        PopupMenu menu = new PopupMenu();
        menu.setBackgroundColor(RED_TO_BLUE);
        menu.setHoverColor(RED_TO_BLUE);
        menu.setTextColor(RED_TO_BLUE);
        menu.setDisabledTextColor(RED_TO_BLUE);
        menu.setBorderColor(RED_TO_BLUE);
        assertSame(RED_TO_BLUE, menu.background());
        assertSame(RED_TO_BLUE, menu.highlight());
        assertSame(RED_TO_BLUE, menu.text());
        assertSame(RED_TO_BLUE, menu.disabledText());
        assertSame(RED_TO_BLUE, menu.border());
    }

    @Test
    void theMenuAdvancesItsAnimatedPaintsOncePerCall() {
        PopupMenu menu = new PopupMenu();
        Pulse pulse = new Pulse();
        menu.setTextColor(pulse);
        menu.advance(0.1f);
        menu.advance(0.1f);
        assertEquals(2, pulse.advances);
    }

    @Test
    void aNullPaintFallsBackToTheTheme() {
        PopupMenu menu = new PopupMenu();
        menu.setBackgroundColor(RED_TO_BLUE);
        menu.setBackgroundColor(null);
        assertNotSame(RED_TO_BLUE, menu.background());
    }
}
