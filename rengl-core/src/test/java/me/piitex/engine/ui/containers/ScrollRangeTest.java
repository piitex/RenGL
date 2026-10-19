package me.piitex.engine.ui.containers;

import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What a {@link ScrollContainer} counts as its content. */
class ScrollRangeTest {
    private static ScrollContainer scrollWithRows(boolean clipRows) {
        ScrollContainer scroll = new ScrollContainer(0, 0, 200, 100);
        VerticalLayout rows = new VerticalLayout(200, 50); // smaller than its ten rows
        rows.setClipping(clipRows);
        for (int i = 0; i < 10; i++) rows.addElement(new ButtonOverlay("Row " + i, 100, 30));
        scroll.addElement(rows);
        return scroll;
    }

    @Test
    void aClippingLayoutCountsAsItsOwnSize() {
        assertEquals(0, scrollWithRows(true).getMaxScrollY());
    }

    @Test
    void aLayoutThatDoesNotClipCountsWhatItHolds() {
        ScrollContainer scroll = scrollWithRows(false);
        assertTrue(scroll.getMaxScrollY() >= 10 * 30 - 100, "ten rows of 30 in a 100 viewport: " + scroll.getMaxScrollY());
    }
}
