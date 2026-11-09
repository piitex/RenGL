package me.piitex.engine.webview.webkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClickTrackerTest {
    private static final long MS = 1_000_000L;

    @Test
    void rapidClicksInTheSamePlaceCount() {
        ClickTracker c = new ClickTracker();
        assertEquals(1, c.press(0, 10, 10, 0));
        assertEquals(2, c.press(0, 12, 11, 200 * MS));
        assertEquals(3, c.press(0, 12, 11, 400 * MS));
    }

    @Test
    void aPauseAnotherButtonOrAnotherPlaceStartsOver() {
        ClickTracker c = new ClickTracker();
        c.press(0, 10, 10, 0);
        assertEquals(1, c.press(0, 10, 10, 600 * MS), "too slow");
        assertEquals(1, c.press(1, 10, 10, 650 * MS), "another button");
        assertEquals(1, c.press(1, 80, 10, 700 * MS), "another place");
    }
}
