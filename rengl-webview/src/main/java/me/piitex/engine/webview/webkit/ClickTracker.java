package me.piitex.engine.webview.webkit;

/**
 * Counts rapid clicks (a double click is a click count of 2), which AppKit wants on the event and the page's
 * {@code dblclick} depends on. Same rule as {@code CdpInput}: the same button, within half a second and 5 pixels.
 */
public final class ClickTracker {
    private long lastPressAt;
    private int lastButton = -1, count;
    private double lastX, lastY;

    /** Registers a press and returns its click count (1 for a first click). */
    public int press(int button, double x, double y, long nowNanos) {
        boolean again = button == lastButton && nowNanos - lastPressAt < 500_000_000L
                && Math.abs(x - lastX) < 5 && Math.abs(y - lastY) < 5;
        count = again ? count + 1 : 1;
        lastPressAt = nowNanos;
        lastButton = button;
        lastX = x;
        lastY = y;
        return count;
    }
}
