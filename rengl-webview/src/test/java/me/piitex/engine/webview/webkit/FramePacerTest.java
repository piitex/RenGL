package me.piitex.engine.webview.webkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FramePacerTest {
    private static final long MS = 1_000_000L;

    @Test
    void theFirstPictureIsDueAtOnceAndIsReported() {
        FramePacer p = new FramePacer();
        assertTrue(p.due(0));
        assertTrue(p.captured(0, 100, 50, 7), "nothing was reported before");
    }

    @Test
    void aChangedPictureIsFollowedByAnotherAtOnce() {
        FramePacer p = new FramePacer();
        p.captured(0, 100, 50, 7);
        assertTrue(p.captured(10 * MS, 100, 50, 8));
        assertTrue(p.due(10 * MS), "back to back while the page moves");
    }

    @Test
    void anUnchangedPictureIsNotReportedAndTheNextCheckWaitsALittleWhileThePageIsActive() {
        FramePacer p = new FramePacer();
        p.activity(0);
        p.captured(0, 100, 50, 7);
        assertFalse(p.captured(40 * MS, 100, 50, 7));
        assertFalse(p.due(40 * MS), "not a busy loop");
        assertTrue(p.due(40 * MS + FramePacer.BUSY_GAP_NANOS));
    }

    @Test
    void aSettledPageIsOnlyCheckedEveryHalfSecond() {
        FramePacer p = new FramePacer();
        p.captured(0, 100, 50, 7);
        long late = FramePacer.ACTIVE_NANOS + 500 * MS;
        assertFalse(p.captured(late, 100, 50, 7));
        assertFalse(p.due(late + FramePacer.IDLE_POLL_NANOS - 1));
        assertTrue(p.due(late + FramePacer.IDLE_POLL_NANOS));
    }

    @Test
    void activityWakesASettledPage() {
        FramePacer p = new FramePacer();
        p.captured(0, 100, 50, 7);
        long late = 5_000 * MS;
        p.captured(late, 100, 50, 7);
        assertFalse(p.due(late + 10 * MS));
        p.activity(late + 10 * MS);
        assertTrue(p.due(late + 10 * MS));
    }

    @Test
    void aNewSizeIsAlwaysNewEvenWithTheSameHash() {
        FramePacer p = new FramePacer();
        p.captured(0, 100, 50, 7);
        assertTrue(p.captured(1, 200, 50, 7));
        p.resized();
        assertTrue(p.captured(2, 200, 50, 7), "a resize forgets the last picture");
    }

    @Test
    void aFailedPictureIsRetriedLater() {
        FramePacer p = new FramePacer();
        p.failed(0);
        assertFalse(p.due(FramePacer.RETRY_NANOS - 1));
        assertTrue(p.due(FramePacer.RETRY_NANOS));
    }

    @Test
    void showingAgainTakesAPictureAtOnce() {
        FramePacer p = new FramePacer();
        p.captured(0, 100, 50, 7);
        long late = 5_000 * MS;
        p.captured(late, 100, 50, 7);
        p.shownAgain(late + MS);
        assertTrue(p.due(late + MS));
    }
}
