package me.piitex.engine.webview.webkit;

/**
 * When to take the next picture of a page, for engines that can only be asked for one (a snapshot of the view) rather
 * than pushing them. Pure arithmetic on a clock the caller supplies, so it is tested without a browser.
 * <p>
 * A picture costs tens of milliseconds, so one is only taken while something is happening: back to back while the page
 * keeps changing, then a cheap check a couple of times a second. A picture that equals the last is not reported.
 * Not thread-safe; the backend's own thread uses it.
 */
public final class FramePacer {
    /** How long after the last sign of life (input, a navigation, a changed picture) pictures are checked often. */
    public static final long ACTIVE_NANOS = 1_200_000_000L;
    /** The gap between checks of a page that has settled. */
    public static final long IDLE_POLL_NANOS = 500_000_000L;
    /** The gap between checks of a page that is expected to change but has not yet. */
    public static final long BUSY_GAP_NANOS = 30_000_000L;
    /** How long a failed picture is left before another try. */
    public static final long RETRY_NANOS = 250_000_000L;

    private long nextAt;
    private long activeUntil;
    private int lastWidth = -1, lastHeight = -1, lastHash;

    /** Whether a picture should be taken now. */
    public boolean due(long now) {
        return now >= nextAt;
    }

    /** The page is doing something (input, a navigation, a resize): take pictures often for a while, starting now. */
    public void activity(long now) {
        activeUntil = Math.max(activeUntil, now + ACTIVE_NANOS);
        nextAt = Math.min(nextAt, now);
    }

    /** The page was hidden and is on screen again: whatever the last picture was, it may be stale. */
    public void shownAgain(long now) {
        activity(now);
        nextAt = now;
    }

    /** The page changed size, so the next picture is always new. */
    public void resized() {
        lastWidth = -1;
    }

    /** A picture could not be taken. */
    public void failed(long now) {
        nextAt = now + RETRY_NANOS;
    }

    /**
     * A picture was taken. Returns true when it differs from the last one and so should be reported; the next one is
     * then due at once. An unchanged picture is not reported, and the next check is soon while the page is expected to
     * move and rare once it has settled.
     */
    public boolean captured(long now, int width, int height, int hash) {
        boolean changed = width != lastWidth || height != lastHeight || hash != lastHash;
        lastWidth = width;
        lastHeight = height;
        lastHash = hash;
        if (changed) {
            activeUntil = Math.max(activeUntil, now + 300_000_000L);
            nextAt = now;
        } else {
            nextAt = now < activeUntil ? now + BUSY_GAP_NANOS : now + IDLE_POLL_NANOS;
        }
        return changed;
    }

    /** When the next picture is due (for tests and logs). */
    public long nextAt() {
        return nextAt;
    }
}
